package com.example.adsh.device

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DeviceApprovalReceiverTest {
    private lateinit var context: Context
    private lateinit var store: DevicePolicyStore
    private var originalPreset = DevicePolicy.PRESET_STANDARD

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        DeviceGovernor.attach(context)
        store = DevicePolicyStore(context)
        originalPreset = store.policy().preset
        store.setPreset(DevicePolicy.PRESET_STANDARD)
        store.clearRules()
        awaitNoPending()
    }

    @After
    fun tearDown() {
        store.setPreset(originalPreset)
        store.clearRules()
        awaitNoPending()
    }

    @Test
    fun notificationDenyResolvesBlockedCall() {
        val authorization = awaitDecision(DeviceDecision.DENY)
        assertEquals(DeviceDecision.DENY, authorization.decision)
        assertTrue(authorization.message.contains("用户拒绝"))
        assertTrue(lastAuditEntry().contains("拒绝"))
    }

    @Test
    fun notificationAllowResolvesBlockedCall() {
        val authorization = awaitDecision(DeviceDecision.ALLOW)
        assertEquals(DeviceDecision.ALLOW, authorization.decision)
        assertTrue(authorization.message.contains("用户允许"))
        assertTrue(lastAuditEntry().contains("允许"))
    }

    @Test
    fun rememberedAllowSkipsNextApproval() {
        val request = DeviceRequest(
            action = DeviceAction.SEND_MESSAGE,
            packageName = "com.android.mms",
            label = "发送短信",
            detail = "收件人 10****86，记住规则测试",
        )
        val result = CompletableFuture<Authorization>()
        val worker = Thread { result.complete(DeviceGovernor.authorize(context, request)) }
        worker.start()

        val approval = awaitPending()
        assertNotNull("authorize should publish a pending approval", approval)
        assertTrue(DeviceGovernor.resolve(approval!!.id, DeviceDecision.ALLOW, rememberRule = true))
        val first = result.get(15, TimeUnit.SECONDS)
        assertEquals(DeviceDecision.ALLOW, first.decision)
        assertTrue(first.message.contains("始终允许"))
        assertTrue(
            store.policy().rules.any {
                it.decision == DeviceDecision.ALLOW &&
                    it.app == "com.android.mms" &&
                    it.action == DeviceAction.SEND_MESSAGE
            },
        )
        awaitNoPending()

        val second = DeviceGovernor.authorize(context, request)
        assertEquals(DeviceDecision.ALLOW, second.decision)
        assertEquals(null, DeviceGovernor.pending.value)
    }

    private fun awaitDecision(decision: DeviceDecision): Authorization {
        val request = DeviceRequest(
            action = DeviceAction.SEND_MESSAGE,
            label = "发送短信",
            detail = "收件人 10****86，接收器测试",
        )
        val result = CompletableFuture<Authorization>()
        val worker = Thread { result.complete(DeviceGovernor.authorize(context, request)) }
        worker.start()

        val approval = awaitPending()
        assertNotNull("authorize should publish a pending approval", approval)
        context.sendBroadcast(
            Intent(context, DeviceApprovalReceiver::class.java).apply {
                action = DeviceApprovalReceiver.ACTION_RESOLVE
                putExtra(DeviceApprovalReceiver.EXTRA_ID, approval!!.id)
                putExtra(DeviceApprovalReceiver.EXTRA_DECISION, decision.name)
            },
        )

        val authorization = result.get(15, TimeUnit.SECONDS)
        worker.join(1_000)
        awaitNoPending()
        return authorization
    }

    private fun awaitPending(): PendingApproval? {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            DeviceGovernor.pending.value?.let { return it }
            Thread.sleep(50)
        }
        return DeviceGovernor.pending.value
    }

    private fun awaitNoPending() {
        val deadline = System.currentTimeMillis() + 65_000
        while (DeviceGovernor.pending.value != null && System.currentTimeMillis() < deadline) {
            Thread.sleep(200)
        }
    }

    private fun lastAuditEntry(): String =
        DeviceAuditLog(context).recent(1).lastOrNull().orEmpty()
}
