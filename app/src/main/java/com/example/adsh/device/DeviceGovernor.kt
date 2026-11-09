package com.example.adsh.device

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import com.example.adsh.MainActivity
import com.example.adsh.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

data class PendingApproval(
    val id: String,
    val request: DeviceRequest,
    val outcome: PolicyOutcome,
    val createdAt: Long,
    val expiresAt: Long,
) {
    val title: String
        get() = "${request.action.label}需要确认"

    val summary: String
        get() = buildString {
            val target = when {
                outcome.category != null -> outcome.category.label
                !request.packageName.isNullOrBlank() -> request.packageName
                else -> "系统"
            }
            append("应用：").append(target)
            if (!request.detail.isBlank()) {
                append('\n').append("内容：").append(request.detail)
            } else if (!request.label.isNullOrBlank()) {
                append('\n').append("控件：").append(request.label.take(60))
            }
            append('\n').append("策略：").append(outcome.reason)
        }
}

data class Authorization(val decision: DeviceDecision, val reason: String) {
    val message: String
        get() = when (decision) {
            DeviceDecision.ALLOW -> reason
            DeviceDecision.ASK -> "需要用户确认：$reason"
            DeviceDecision.DENY -> "操作已被安全策略阻止：$reason"
        }
}

/**
 * Evaluates every bridge request before the side effect and blocks the calling
 * worker while the user decides. Only the in-app dialog or the notification
 * action can complete a pending request; the agent has no route to approve
 * itself.
 */
object DeviceGovernor {
    const val APPROVAL_TIMEOUT_MS = 60_000L

    private class Waiter(
        val approval: PendingApproval,
        val future: CompletableFuture<DeviceDecision>,
    ) {
        @Volatile var remembered = false
        @Volatile var notified = false
        @Volatile var answeredByUser = false
    }

    private val lock = Any()
    private val waiters = ArrayDeque<Waiter>()
    private var appContext: Context? = null
    private var store: DevicePolicyStore? = null
    private var audit: DeviceAuditLog? = null

    private val _pending = MutableStateFlow<PendingApproval?>(null)
    val pending: StateFlow<PendingApproval?> = _pending.asStateFlow()

    private val _auditVersion = MutableStateFlow(0)
    val auditVersion: StateFlow<Int> = _auditVersion.asStateFlow()

    fun attach(context: Context) {
        synchronized(lock) {
            if (appContext == null) {
                val app = context.applicationContext
                appContext = app
                store = DevicePolicyStore(app)
                audit = DeviceAuditLog(app)
            }
        }
    }

    fun policy(): PolicyConfig = store?.policy() ?: PolicyConfig()

    fun recentAudit(limit: Int = 20): List<String> = audit?.recent(limit).orEmpty()

    fun clearAudit() {
        audit?.clear()
        _auditVersion.value++
    }

    fun authorize(context: Context, request: DeviceRequest): Authorization {
        attach(context)
        val outcome = DevicePolicy.evaluate(store?.policy() ?: PolicyConfig(), request)
        if (outcome.decision != DeviceDecision.ASK) {
            record(request, outcome, outcome.decision, outcome.reason)
            return Authorization(outcome.decision, outcome.reason)
        }

        val now = System.currentTimeMillis()
        val approval = PendingApproval(
            id = UUID.randomUUID().toString(),
            request = request,
            outcome = outcome,
            createdAt = now,
            expiresAt = now + APPROVAL_TIMEOUT_MS,
        )
        val waiter = Waiter(approval, CompletableFuture())
        synchronized(lock) {
            waiters.addLast(waiter)
            publishLocked()
        }

        val decision = try {
            waiter.future.get(APPROVAL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            DeviceDecision.DENY
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            DeviceDecision.DENY
        } catch (_: Exception) {
            DeviceDecision.DENY
        } finally {
            synchronized(lock) { waiters.remove(waiter) }
            dismiss(waiter.approval)
            synchronized(lock) { publishLocked() }
        }

        val reason = when {
            decision == DeviceDecision.ALLOW && waiter.remembered ->
                "用户选择始终允许：${outcome.reason}"
            decision == DeviceDecision.ALLOW -> "用户允许：${outcome.reason}"
            waiter.answeredByUser -> "用户拒绝：${outcome.reason}"
            else -> "等待用户确认超时（60 秒）"
        }
        record(request, outcome, decision, reason)
        return Authorization(decision, reason)
    }

    fun resolve(id: String, decision: DeviceDecision, rememberRule: Boolean): Boolean {
        if (decision == DeviceDecision.ASK) return false
        val waiter = synchronized(lock) {
            val found = waiters.firstOrNull { it.approval.id == id } ?: return false
            waiters.remove(found)
            found.answeredByUser = true
            found.remembered = rememberRule && decision == DeviceDecision.ALLOW
            found
        }
        if (waiter.remembered) {
            DevicePolicy.alwaysAllowRule(waiter.approval.request)?.let { rule ->
                store?.addRule(rule)
            }
        }
        dismiss(waiter.approval)
        waiter.future.complete(decision)
        synchronized(lock) { publishLocked() }
        return true
    }

    private fun publishLocked() {
        val head = waiters.firstOrNull()
        _pending.value = head?.approval
        if (head != null && !head.notified) {
            head.notified = true
            appContext?.let { DeviceApprovalNotifier.show(it, head.approval) }
            AdshAccessibilityService.instance()?.showApproval(head.approval)
        }
    }

    private fun record(
        request: DeviceRequest,
        outcome: PolicyOutcome,
        decision: DeviceDecision,
        reason: String,
    ) {
        audit?.record(
            DeviceAuditEntry(
                time = System.currentTimeMillis(),
                decision = decision,
                action = request.action,
                packageName = request.packageName,
                category = outcome.category,
                label = request.label,
                detail = request.detail,
                reason = reason,
            ),
        )
        _auditVersion.value++
    }

    private fun dismiss(approval: PendingApproval) {
        AdshAccessibilityService.instance()?.dismissApproval(approval.id)
        DeviceApprovalNotifier.dismiss(appContext ?: return, approval)
    }
}

internal object DeviceApprovalNotifier {
    fun show(context: Context, approval: PendingApproval) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    "设备操作审批",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "dsh 请求执行需要确认的设备操作"
                },
            )
            val notification = Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle("需要确认：${approval.title}")
                .setContentText(approval.summary.replace('\n', ' '))
                .setStyle(
                    Notification.BigTextStyle()
                        .bigText(approval.summary + "\n\n60 秒内未处理将自动拒绝。"),
                )
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setPriority(Notification.PRIORITY_HIGH)
                .setContentIntent(openIntent(context, approval))
                .addAction(action(context, approval, DeviceDecision.ALLOW, "允许一次"))
                .addAction(action(context, approval, DeviceDecision.DENY, "拒绝"))
                .setAutoCancel(false)
                .setOngoing(true)
                .build()
            manager.notify(notificationId(approval), notification)
        }
    }

    fun dismiss(context: Context, approval: PendingApproval) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.cancel(notificationId(approval)) }
    }

    private fun openIntent(context: Context, approval: PendingApproval): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }
        return PendingIntent.getActivity(
            context,
            approval.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun action(
        context: Context,
        approval: PendingApproval,
        decision: DeviceDecision,
        label: String,
    ): Notification.Action {
        val intent = Intent(context, DeviceApprovalReceiver::class.java).apply {
            action = DeviceApprovalReceiver.ACTION_RESOLVE
            putExtra(DeviceApprovalReceiver.EXTRA_ID, approval.id)
            putExtra(DeviceApprovalReceiver.EXTRA_DECISION, decision.name)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            approval.id.hashCode() * 31 + decision.ordinal,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(
            Icon.createWithResource(context, R.drawable.ic_mic),
            label,
            pending,
        ).build()
    }

    private fun notificationId(approval: PendingApproval): Int =
        approval.id.hashCode() and 0x7fffffff

    private const val CHANNEL = "adsh-approvals"
}
