package com.example.adsh.device

import android.Manifest
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context.CLIPBOARD_SERVICE
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.Settings
import android.telephony.SmsManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import com.example.adsh.ReplySpeaker
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Device capabilities exposed to the dsh engine through the loopback bridge.
 * Every entry point re-checks whether the user granted the underlying access.
 */
object DeviceCapabilities {
    private val audit = ArrayDeque<String>()

    fun deviceInfo(context: Context): Map<String, Any?> {
        val metrics = context.resources.displayMetrics
        val battery = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
        return linkedMapOf(
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "device" to Build.DEVICE,
            "android" to Build.VERSION.RELEASE,
            "sdk" to Build.VERSION.SDK_INT,
            "screen" to "${metrics.widthPixels}x${metrics.heightPixels} @ ${"%.1f".format(Locale.US, metrics.density)}x",
            "locale" to Locale.getDefault().toLanguageTag(),
            "batteryPercent" to battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 },
            "charging" to battery?.isCharging,
            "appVersion" to version,
            "accessibilityEnabled" to AdshAccessibilityService.isEnabled(context),
            "notificationAccessEnabled" to AdshNotificationListenerService.isEnabled(context),
        )
    }

    fun notifications(limit: Int): List<Map<String, Any?>> {
        val service = AdshNotificationListenerService.instance()
            ?: throw BridgeFailure("通知使用权未开启：请在系统设置中授予“深度求索助手”通知使用权")
        val active = runCatching { service.activeNotifications }.getOrElse {
            throw BridgeFailure("读取通知失败：${it.message ?: it.javaClass.simpleName}")
        }
        record("notifications_list", "limit=$limit")
        return active.orEmpty()
            .sortedByDescending { it.postTime }
            .take(limit)
            .map { item ->
                val extras = item.notification.extras
                val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                    ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                linkedMapOf(
                    "package" to item.packageName,
                    "title" to title.orEmpty(),
                    "text" to text.orEmpty(),
                    "category" to item.notification.category,
                    "ongoing" to item.isOngoing,
                    "postedAt" to item.postTime,
                )
            }
    }

    fun uiSnapshot(context: Context, maxNodes: Int): Map<String, Any?> {
        val service = accessibility()
        val root = service.rootInActiveWindow
            ?: throw BridgeFailure("没有可读取的前台窗口；请先打开目标应用并保持屏幕亮起")
        val metrics = context.resources.displayMetrics
        val counter = intArrayOf(0)
        val output = StringBuilder()
        walk(root, 0, maxNodes, counter, output)
        record("screen_snapshot", "nodes=${counter[0]}")
        val hint = if (counter[0] == 0) {
            "（无障碍树为空：该应用可能屏蔽了无障碍节点，请改用 screen_ocr。）"
        } else {
            ""
        }
        return linkedMapOf(
            "screen" to "屏幕 ${metrics.widthPixels}x${metrics.heightPixels}，包名 ${root.packageName ?: "unknown"}，节点 ${counter[0]} 个",
            "text" to (output.toString().trimEnd() + if (hint.isEmpty()) "" else "\n$hint").trim(),
        )
    }

    /**
     * Screenshot + offline OCR. Coordinates are screen pixels and can be fed
     * straight into screen_tap / screen_long_press.
     */
    fun ocrSnapshot(context: Context, maxBlocks: Int): Map<String, Any?> {
        val service = accessibility()
        val packageName = foregroundPackage()
        val bitmap = service.captureScreen()
        try {
            val blocks = ScreenOcr.recognize(bitmap, maxBlocks)
            record("screen_ocr", "blocks=${blocks.size}")
            val output = StringBuilder()
            blocks.forEachIndexed { index, block ->
                output.append('[').append(index).append("] ")
                    .append(block["text"]).append(" @ (")
                    .append(block["x"]).append(',').append(block["y"]).append(")\n")
            }
            if (blocks.isEmpty()) output.append("（没有识别到文字，请等待页面加载或滑动后重试）")
            return linkedMapOf(
                "screen" to "屏幕 ${bitmap.width}x${bitmap.height}，包名 ${packageName ?: "unknown"}，" +
                    "OCR ${blocks.size} 块",
                "text" to output.toString().trimEnd(),
            )
        } finally {
            bitmap.recycle()
        }
    }

    fun longPress(
        text: String?,
        index: Int,
        x: Double?,
        y: Double?,
        durationMs: Int,
    ): String {
        val duration = durationMs.toLong().coerceIn(500L, 3_000L)
        if (!text.isNullOrBlank()) {
            val service = accessibility()
            val root = service.rootInActiveWindow
                ?: throw BridgeFailure("没有可读取的前台窗口")
            val exact = mutableListOf<AccessibilityNodeInfo>()
            val partial = mutableListOf<AccessibilityNodeInfo>()
            collect(root, text.trim(), exact, partial, 0)
            val matches = exact.ifEmpty { partial }
            if (matches.isEmpty()) throw BridgeFailure("没有找到文本包含“$text”的控件")
            if (index !in matches.indices) {
                throw BridgeFailure("匹配到 ${matches.size} 个“$text”，序号 $index 超出范围")
            }
            val node = matches[index]
            var target: AccessibilityNodeInfo? = node
            var depth = 0
            while (target != null && !target.isLongClickable && depth < 6) {
                target = target.parent
                depth++
            }
            if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
                record("screen_long_press", "text=$text")
                return "已长按“$text”"
            }
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            if (bounds.width() > 0 && bounds.height() > 0) {
                return pressGesture(
                    bounds.centerX().toDouble(),
                    bounds.centerY().toDouble(),
                    duration,
                    "text=$text",
                )
            }
            throw BridgeFailure("控件“$text”不支持长按")
        }
        if (x == null || y == null) throw BridgeFailure("需要 text，或者同时提供 x 与 y")
        return pressGesture(x, y, duration, "x=$x y=$y")
    }

    private fun pressGesture(x: Double, y: Double, durationMs: Long, detail: String): String {
        val service = accessibility()
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        if (!service.dispatchGesture(gesture, null, null)) {
            throw BridgeFailure("长按手势发送失败")
        }
        record("screen_long_press", detail)
        return "已长按坐标 (${x.toInt()}, ${y.toInt()}) ${durationMs}ms"
    }

    fun setClipboard(context: Context, text: String): String {
        if (text.isEmpty()) throw BridgeFailure("需要 text")
        val manager = context.getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
            ?: throw BridgeFailure("系统剪贴板不可用")
        val adshPackage = context.packageName
        val original = foregroundPackage()
        // ColorOS ignores clipboard writes from a background package, and the
        // input method then offers stale clipboard history. Briefly bring adsh
        // forward, write, and restore the previous app.
        val raised = original != null && original != adshPackage && raiseApp(context, adshPackage)
        if (raised) Thread.sleep(250)
        manager.setPrimaryClip(ClipData.newPlainText("adsh", text))
        val verified = runCatching {
            manager.primaryClip?.getItemAt(0)?.text?.toString() == text
        }.getOrDefault(false)
        if (raised && original != null) raiseApp(context, original)
        record("clipboard_set", "chars=${text.length} verified=$verified")
        return if (verified) {
            "已写入 ${text.length} 个字符到系统剪贴板；长按输入框后点击“粘贴”即可填入"
        } else {
            "已请求写入 ${text.length} 个字符，但系统未确认（可能限制了后台剪贴板）；" +
                "请先让 adsh 回到前台再试，或改用 share_text"
        }
    }

    /** OCR-locates the Nth block containing [target] and returns its screen center. */
    fun ocrFind(target: String, index: Int): Pair<Double, Double>? {
        val wanted = target.trim()
        if (wanted.isEmpty()) return null
        val bitmap = accessibility().captureScreen()
        val blocks = try {
            ScreenOcr.recognize(bitmap, 300)
        } finally {
            bitmap.recycle()
        }
        val synonyms = when (wanted) {
            "发送", "發送", "傳送", "传送" -> listOf("发送", "發送", "傳送", "传送", "Send", "send")
            "粘贴", "貼上" -> listOf("粘贴", "貼上", "Paste", "paste")
            "发表", "發布", "发布", "發佈" -> listOf("发表", "發表", "發布", "发布", "發佈", "Post", "post")
            else -> listOf(wanted)
        }
        val exact = blocks.filter { block ->
            synonyms.any { it == block["text"].toString().trim() }
        }
        val partial = blocks.filter { block ->
            synonyms.any { block["text"].toString().contains(it) }
        }
        val hit = exact.ifEmpty { partial }.getOrNull(index) ?: return null
        val x = (hit["x"] as? Number)?.toDouble() ?: return null
        val y = (hit["y"] as? Number)?.toDouble() ?: return null
        record("screen_ocr", "locate=$wanted")
        return x to y
    }

    fun shareText(
        context: Context,
        packageName: String?,
        text: String,
        subject: String?,
    ): String {
        if (text.isBlank()) throw BridgeFailure("需要 text")
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            subject?.takeIf { it.isNotBlank() }?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
            packageName?.takeIf { it.isNotBlank() }?.let { setPackage(it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val intent = if (packageName.isNullOrBlank()) {
            Intent.createChooser(send, "分享到").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        } else {
            send
        }
        if (intent.resolveActivity(context.packageManager) == null) {
            throw BridgeFailure("${packageName ?: "系统"}没有可用的分享入口")
        }
        context.startActivity(intent)
        record("share_text", "${packageName ?: "chooser"} chars=${text.length}")
        return "已打开分享面板，文本已预填；还需要选择联系人并点击发送"
    }

    fun composeMail(
        context: Context,
        packageName: String?,
        to: String,
        subject: String?,
        body: String,
    ): String {
        if (to.isBlank()) throw BridgeFailure("需要收件人 to")
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Uri.encode(to)}")).apply {
            subject?.takeIf { it.isNotBlank() }?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
            body.takeIf { it.isNotBlank() }?.let { putExtra(Intent.EXTRA_TEXT, it) }
            packageName?.takeIf { it.isNotBlank() }?.let { setPackage(it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(context.packageManager) == null) {
            throw BridgeFailure("${packageName ?: "系统"}没有可处理 mailto 的邮件应用")
        }
        context.startActivity(intent)
        record("compose_mail", "${packageName ?: "default"} subject=${subject?.length ?: 0} body=${body.length}")
        return "已打开邮件撰写页：收件人、主题和正文已预填，仍需点击发送"
    }

    fun tap(text: String?, index: Int, x: Double?, y: Double?): String {
        if (text != null && text.isNotBlank()) {
            val service = accessibility()
            val root = service.rootInActiveWindow
                ?: throw BridgeFailure("没有可读取的前台窗口")
            val exact = mutableListOf<AccessibilityNodeInfo>()
            val partial = mutableListOf<AccessibilityNodeInfo>()
            collect(root, text.trim(), exact, partial, 0)
            val matches = exact.ifEmpty { partial }
            if (matches.isEmpty()) {
                throw BridgeFailure(
                    "没有找到文本包含“$text”的控件；若该应用屏蔽了无障碍树，请改用 screen_ocr 后按坐标点击",
                )
            }
            if (index !in matches.indices) {
                throw BridgeFailure("匹配到 ${matches.size} 个“$text”，序号 $index 超出范围")
            }
            val node = matches[index]
            var clickable: AccessibilityNodeInfo? = node
            var depth = 0
            while (clickable != null && !clickable.isClickable && depth < 6) {
                clickable = clickable.parent
                depth++
            }
            val target = clickable ?: node
            val clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            record("screen_tap", "text=$text")
            if (!clicked) throw BridgeFailure("控件“$text”不支持点击")
            return "已点击“$text”"
        }
        if (x == null || y == null) throw BridgeFailure("需要 text，或者同时提供 x 与 y")
        val service = accessibility()
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        if (!service.dispatchGesture(gesture, null, null)) {
            throw BridgeFailure("点击手势发送失败")
        }
        record("screen_tap", "x=$x y=$y")
        return "已点击坐标 (${x.toInt()}, ${y.toInt()})"
    }

    fun swipe(x1: Double, y1: Double, x2: Double, y2: Double, durationMs: Int): String {
        val service = accessibility()
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.toLong()))
            .build()
        if (!service.dispatchGesture(gesture, null, null)) {
            throw BridgeFailure("滑动手势发送失败")
        }
        record("screen_swipe", "($x1,$y1)->($x2,$y2) ${durationMs}ms")
        return "已从 (${x1.toInt()}, ${y1.toInt()}) 滑动到 (${x2.toInt()}, ${y2.toInt()})"
    }

    fun inputText(text: String, selector: String?): String {
        val service = accessibility()
        val root = service.rootInActiveWindow ?: throw BridgeFailure("没有可读取的前台窗口")
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectEditable(root, candidates, 0)
        if (candidates.isEmpty()) {
            throw BridgeFailure(
                "当前窗口没有可编辑的输入框（该应用可能屏蔽了无障碍树）；" +
                    "请改用 clipboard_set 写入文本，再 screen_long_press 长按输入框并点击“粘贴”",
            )
        }
        val target = when {
            !selector.isNullOrBlank() -> candidates.firstOrNull { node ->
                listOfNotNull(node.text, node.contentDescription, node.hintText)
                    .any { it.toString().contains(selector, ignoreCase = true) }
            } ?: throw BridgeFailure("没有找到匹配“$selector”的输入框")
            else -> candidates.firstOrNull { it.isFocused } ?: candidates.first()
        }
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val arguments = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val updated = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        record("screen_input", "chars=${text.length}")
        if (!updated) throw BridgeFailure("输入框拒绝了文本写入")
        return "已向输入框写入 ${text.length} 个字符"
    }

    fun openApp(context: Context, packageName: String?, appName: String?): String {
        val resolved = resolveAppPackage(context, packageName, appName)
        val manager = context.packageManager
        val launch = manager.getLaunchIntentForPackage(resolved)
            ?: throw BridgeFailure("无法启动 $resolved：应用没有启动入口或未安装")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
        record("app_open", resolved)
        return "已打开 $resolved"
    }

    fun resolveAppPackage(context: Context, packageName: String?, appName: String?): String {
        val manager = context.packageManager
        return packageName?.trim()?.takeIf { it.isNotEmpty() } ?: run {
            val query = appName?.trim().orEmpty()
            if (query.isEmpty()) throw BridgeFailure("需要 package 或 name")
            val activities = manager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                0,
            )
            val match = activities.firstOrNull {
                it.loadLabel(manager).toString().contains(query, ignoreCase = true) ||
                    it.activityInfo.packageName.contains(query, ignoreCase = true)
            } ?: throw BridgeFailure("没有找到名称包含“$query”的应用")
            match.activityInfo.packageName
        }
    }

    /** Package of the window the accessibility service can currently see. */
    fun foregroundPackage(): String? = runCatching {
        AdshAccessibilityService.instance()?.rootInActiveWindow?.packageName?.toString()
    }.getOrNull()

    /**
     * The approval dialog brings this app to the foreground, so a tap approved
     * by the user would otherwise land on the wrong window. Re-raise the app
     * that was in front when the request was made and wait for it.
     */
    fun raiseApp(context: Context, packageName: String): Boolean {
        if (foregroundPackage() == packageName) return true
        val launch = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        runCatching { context.startActivity(launch) }
        repeat(150) {
            Thread.sleep(100)
            if (foregroundPackage() == packageName) return true
        }
        return false
    }

    /**
     * Mirrors the target selection in [inputText] to tell the policy whether
     * the field is a password or one-time-code input before any text is set.
     */
    fun inputFieldIsCredential(selector: String?): Boolean = runCatching {
        val service = AdshAccessibilityService.instance() ?: return false
        val root = service.rootInActiveWindow ?: return false
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectEditable(root, candidates, 0)
        if (candidates.isEmpty()) return false
        val target = when {
            !selector.isNullOrBlank() -> candidates.firstOrNull { node ->
                listOfNotNull(node.text, node.contentDescription, node.hintText)
                    .any { it.toString().contains(selector, ignoreCase = true) }
            } ?: return false
            else -> candidates.firstOrNull { it.isFocused } ?: candidates.first()
        }
        val hint = listOfNotNull(target.hintText, target.contentDescription, target.text)
            .joinToString(" ")
        DevicePolicy.isCredentialField(
            hint = hint,
            isPassword = target.isPassword,
            text = target.text?.toString().orEmpty(),
        )
    }.getOrDefault(false)

    fun setAlarm(context: Context, hour: Int?, minute: Int?, label: String?): String {
        if (hour == null || hour !in 0..23) throw BridgeFailure("需要 0-23 的 hour")
        if (minute == null || minute !in 0..59) throw BridgeFailure("需要 0-59 的 minute")
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            label?.takeIf { it.isNotBlank() }?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(context.packageManager) == null) {
            throw BridgeFailure("系统没有可用的闹钟应用")
        }
        context.startActivity(intent)
        val text = String.format(Locale.US, "%02d:%02d", hour, minute)
        record("set_alarm", text + (label?.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""))
        return "已创建 $text 的闹钟"
    }

    fun setTimer(context: Context, seconds: Int, label: String?): String {
        if (seconds !in 1..86_400) throw BridgeFailure("seconds 需要在 1-86400 之间")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            label?.takeIf { it.isNotBlank() }?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(context.packageManager) == null) {
            throw BridgeFailure("系统没有可用的计时器应用")
        }
        context.startActivity(intent)
        record("set_timer", "${seconds}s" + (label?.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""))
        return "已创建 ${seconds} 秒的计时器"
    }

    /**
     * Opens the messaging app with recipient and body prefilled. The user still
     * presses send, so no SEND_SMS permission is involved.
     */
    fun composeSms(context: Context, to: String?, body: String): String {
        if (body.isBlank()) throw BridgeFailure("需要短信正文 body")
        val target = to?.trim()?.takeIf { it.isNotEmpty() }
        val uri = if (target == null) Uri.parse("smsto:") else Uri.parse("smsto:$target")
        val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
            putExtra("sms_body", body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(context.packageManager) == null) {
            throw BridgeFailure("系统没有可用的短信应用")
        }
        context.startActivity(intent)
        record("compose_sms", "${target ?: "未指定收件人"} chars=${body.length}")
        return "已打开短信应用并预填内容，请确认后手动发送"
    }

    /**
     * Sends the message through the system SMS service and waits up to a few
     * seconds for the per-part sent receipt. Irreversible and requires the
     * runtime SEND_SMS grant; only call it on an explicit user order.
     */
    fun sendSms(context: Context, to: String?, body: String): String {
        if (body.isBlank()) throw BridgeFailure("需要短信正文 body")
        val recipient = to?.trim().orEmpty()
        if (recipient.isEmpty()) throw BridgeFailure("需要收件人 to")
        val address = recipient.replace(" ", "").replace("-", "")
        if (!address.matches(Regex("^\\+?[0-9]{2,20}$"))) {
            throw BridgeFailure("收件人号码格式不正确：$recipient")
        }
        if (context.checkSelfPermission(Manifest.permission.SEND_SMS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw BridgeFailure("未授予短信权限：请在“设置”页授权“短信发送”后重试")
        }
        val manager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        } ?: throw BridgeFailure("系统没有可用的短信服务")
        val parts = manager.divideMessage(body)

        val action = "com.example.adsh.SMS_SENT.${UUID.randomUUID()}"
        val codes = Collections.synchronizedList(mutableListOf<Int>())
        val latch = CountDownLatch(parts.size)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != action) return
                codes.add(resultCode)
                latch.countDown()
            }
        }
        val appContext = context.applicationContext
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(action),
            ContextCompat.RECEIVER_EXPORTED,
        )
        val deadline = System.nanoTime() +
            TimeUnit.SECONDS.toNanos(SmsReceipt.TIMEOUT_SECONDS.toLong())
        try {
            val requestBase = UUID.randomUUID().hashCode()
            val sentIntents = parts.indices.map { index ->
                PendingIntent.getBroadcast(
                    appContext,
                    requestBase + index,
                    Intent(action).setPackage(appContext.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
            try {
                if (parts.size > 1) {
                    manager.sendMultipartTextMessage(
                        address, null, parts, ArrayList(sentIntents), null,
                    )
                } else {
                    manager.sendTextMessage(address, null, body, sentIntents.first(), null)
                }
            } catch (error: Exception) {
                val detail = error.message.orEmpty()
                val reason = if (
                    detail.contains("MANAGE_SUBSCRIPTION_USER_ASSOCIATION") ||
                    detail.contains("getSubscriptionUserHandle")
                ) {
                    "系统短信服务暂不可用（请检查飞行模式、信号与 SIM 卡状态）"
                } else {
                    detail.ifBlank { error.javaClass.simpleName }
                }
                throw BridgeFailure("短信发送失败：$reason")
            }
            // sendTextMessage can block while the ROM shows its own send
            // confirmation, so the budget covers the whole call, not just the
            // broadcast wait.
            val remaining = deadline - System.nanoTime()
            if (remaining > 0) latch.await(remaining, TimeUnit.NANOSECONDS)
            val snapshot = synchronized(codes) { codes.toList() }
            record(
                "send_sms",
                SmsReceipt.audit(address, body.length, parts.size, snapshot),
            )
            return SmsReceipt.summary(address, parts.size, snapshot)
        } finally {
            runCatching { appContext.unregisterReceiver(receiver) }
        }
    }

    fun dial(context: Context, number: String): String {
        val clean = number.trim()
        if (clean.isEmpty()) throw BridgeFailure("需要号码 number")
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(clean)}")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(context.packageManager) == null) {
            throw BridgeFailure("系统没有可用的拨号应用")
        }
        context.startActivity(intent)
        record("dial", clean)
        return "已打开拨号界面：$clean（需要你手动点击拨出）"
    }

    fun listApps(context: Context, limit: Int): List<Map<String, Any?>> {
        val manager = context.packageManager
        val intents = manager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            0,
        )
        record("list_apps", "count=${intents.size}")
        return intents
            .map { it.activityInfo.packageName to it.loadLabel(manager).toString() }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase(Locale.getDefault()) }
            .take(limit)
            .map { (packageName, label) -> linkedMapOf("name" to label, "package" to packageName) }
    }

    fun speak(context: Context, text: String): String {
        val clean = text.trim()
        if (clean.isEmpty()) throw BridgeFailure("需要朗读的文本 text")
        val appContext = context.applicationContext
        Handler(Looper.getMainLooper()).post {
            val engine = speaker ?: ReplySpeaker(appContext).also { speaker = it }
            engine.speak(clean)
        }
        record("speak", "chars=${clean.length}")
        return "已开始朗读 ${clean.length} 个字符"
    }

    fun stopSpeaking() {
        Handler(Looper.getMainLooper()).post { speaker?.stop() }
        record("speak_stop", "")
    }

    private var speaker: ReplySpeaker? = null

    fun auditEntries(): List<String> = synchronized(audit) { audit.toList() }

    private fun record(tool: String, detail: String) {
        val entry = "${java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(java.util.Date())} $tool $detail"
        synchronized(audit) {
            audit.addLast(entry)
            while (audit.size > 30) audit.removeFirst()
        }
    }

    private fun accessibility(): AdshAccessibilityService =
        AdshAccessibilityService.instance()
            ?: throw BridgeFailure(
                "无障碍服务未开启：请在系统设置的“无障碍”中启用“深度求索助手”",
            )

    private fun walk(
        node: AccessibilityNodeInfo,
        depth: Int,
        maxNodes: Int,
        counter: IntArray,
        output: StringBuilder,
    ) {
        if (counter[0] >= maxNodes || depth > 30) return
        val text = oneLine(node.text)
        val description = oneLine(node.contentDescription)
        if (text.isNotEmpty() || description.isNotEmpty() || node.isClickable || node.isEditable) {
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            output.append('[').append(counter[0]).append("] ")
            output.append("cls=").append(shortClass(node.className))
            if (text.isNotEmpty()) output.append(" text=\"").append(text).append('"')
            if (description.isNotEmpty()) output.append(" desc=\"").append(description).append('"')
            if (node.isClickable) output.append(" clickable=true")
            if (node.isEditable) output.append(" editable=true")
            if (node.isFocused) output.append(" focused=true")
            if (node.isScrollable) output.append(" scrollable=true")
            output.append(" bounds=").append(bounds.left).append(',').append(bounds.top)
                .append('-').append(bounds.right).append(',').append(bounds.bottom)
            output.append('\n')
            counter[0]++
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            walk(child, depth + 1, maxNodes, counter, output)
            if (counter[0] >= maxNodes) return
        }
    }

    private fun collect(
        node: AccessibilityNodeInfo,
        query: String,
        exact: MutableList<AccessibilityNodeInfo>,
        partial: MutableList<AccessibilityNodeInfo>,
        depth: Int,
    ) {
        if (depth > 30) return
        val values = listOfNotNull(node.text, node.contentDescription).map { it.toString().trim() }
        if (values.any { it.equals(query, ignoreCase = true) }) exact.add(node)
        else if (values.any { it.contains(query, ignoreCase = true) }) partial.add(node)
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            collect(child, query, exact, partial, depth + 1)
        }
    }

    private fun collectEditable(
        node: AccessibilityNodeInfo,
        output: MutableList<AccessibilityNodeInfo>,
        depth: Int,
    ) {
        if (depth > 30 || output.size >= 40) return
        if (node.isEditable) output.add(node)
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            collectEditable(child, output, depth + 1)
        }
    }

    private fun oneLine(value: CharSequence?): String =
        value?.toString().orEmpty()
            .replace('\n', ' ')
            .replace('\r', ' ')
            .trim()
            .take(160)

    private fun shortClass(name: CharSequence?): String =
        name?.toString()?.substringAfterLast('.') ?: "?"

    class BridgeFailure(message: String) : IllegalArgumentException(message)
}
