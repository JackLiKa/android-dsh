package com.example.adsh.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DevicePolicyTest {
    private val standard = PolicyConfig()

    @Test
    fun `preset two asks for messages calls payments and commits`() {
        listOf(
            DeviceAction.SEND_MESSAGE,
            DeviceAction.CALL,
            DeviceAction.PAYMENT,
            DeviceAction.COMMIT,
            DeviceAction.INSTALL_APP,
        ).forEach { action ->
            assertEquals(
                action.name,
                DeviceDecision.ASK,
                DevicePolicy.evaluate(standard, DeviceRequest(action)).decision,
            )
        }
    }

    @Test
    fun `preset two allows observation taps typing and speaking`() {
        listOf(
            DeviceAction.OBSERVE,
            DeviceAction.TAP,
            DeviceAction.SWIPE,
            DeviceAction.TYPE,
            DeviceAction.LAUNCH,
            DeviceAction.ALARM,
            DeviceAction.TIMER,
            DeviceAction.SPEAK,
        ).forEach { action ->
            assertEquals(
                action.name,
                DeviceDecision.ALLOW,
                DevicePolicy.evaluate(standard, DeviceRequest(action)).decision,
            )
        }
    }

    @Test
    fun `deny wins over allow even when the allow rule comes first`() {
        val config = PolicyConfig(
            rules = listOf(
                DeviceRule(DeviceDecision.ALLOW, app = "com.example.chat"),
                DeviceRule(DeviceDecision.DENY, app = "com.example.chat", action = DeviceAction.SEND_MESSAGE),
            ),
        )
        val outcome = DevicePolicy.evaluate(
            config,
            DeviceRequest(DeviceAction.SEND_MESSAGE, packageName = "com.example.chat"),
        )
        assertEquals(DeviceDecision.DENY, outcome.decision)
        assertEquals(DeviceDecision.DENY, outcome.rule?.decision)
    }

    @Test
    fun `ask wins over allow`() {
        val config = PolicyConfig(
            rules = listOf(
                DeviceRule(DeviceDecision.ALLOW, app = "com.example.chat"),
                DeviceRule(DeviceDecision.ASK, app = "com.example.chat"),
            ),
        )
        val outcome = DevicePolicy.evaluate(
            config,
            DeviceRequest(DeviceAction.SEND_MESSAGE, packageName = "com.example.chat"),
        )
        assertEquals(DeviceDecision.ASK, outcome.decision)
    }

    @Test
    fun `allow rule turns a preset ask into allow`() {
        val config = PolicyConfig(
            rules = listOf(
                DeviceRule(
                    DeviceDecision.ALLOW,
                    app = "com.example.chat",
                    action = DeviceAction.SEND_MESSAGE,
                ),
            ),
        )
        val outcome = DevicePolicy.evaluate(
            config,
            DeviceRequest(DeviceAction.SEND_MESSAGE, packageName = "com.example.chat"),
        )
        assertEquals(DeviceDecision.ALLOW, outcome.decision)
    }

    @Test
    fun `preset three asks even when a user allow rule matches`() {
        val config = PolicyConfig(
            preset = DevicePolicy.PRESET_ASK_ALL,
            rules = listOf(
                DeviceRule(DeviceDecision.ALLOW, app = "com.example.chat", action = DeviceAction.SEND_MESSAGE),
            ),
        )
        val outcome = DevicePolicy.evaluate(
            config,
            DeviceRequest(DeviceAction.SEND_MESSAGE, packageName = "com.example.chat"),
        )
        assertEquals(DeviceDecision.ASK, outcome.decision)
    }

    @Test
    fun `preset three still honours deny rules`() {
        val config = PolicyConfig(
            preset = DevicePolicy.PRESET_ASK_ALL,
            rules = listOf(DeviceRule(DeviceDecision.DENY, action = DeviceAction.PAYMENT)),
        )
        val outcome = DevicePolicy.evaluate(config, DeviceRequest(DeviceAction.PAYMENT))
        assertEquals(DeviceDecision.DENY, outcome.decision)
    }

    @Test
    fun `preset zero only follows explicit rules`() {
        val config = PolicyConfig(preset = DevicePolicy.PRESET_RULES_ONLY)
        assertEquals(
            DeviceDecision.ALLOW,
            DevicePolicy.evaluate(config, DeviceRequest(DeviceAction.SEND_MESSAGE)).decision,
        )
        val deny = config.copy(rules = listOf(DeviceRule(DeviceDecision.DENY, action = DeviceAction.SEND_MESSAGE)))
        assertEquals(
            DeviceDecision.DENY,
            DevicePolicy.evaluate(deny, DeviceRequest(DeviceAction.SEND_MESSAGE)).decision,
        )
    }

    @Test
    fun `strict preset asks for external links and settings changes`() {
        val config = PolicyConfig(preset = DevicePolicy.PRESET_STRICT)
        assertEquals(
            DeviceDecision.ASK,
            DevicePolicy.evaluate(config, DeviceRequest(DeviceAction.OPEN_URL)).decision,
        )
        assertEquals(
            DeviceDecision.ASK,
            DevicePolicy.evaluate(config, DeviceRequest(DeviceAction.CHANGE_SETTING)).decision,
        )
        assertEquals(
            DeviceDecision.ALLOW,
            DevicePolicy.evaluate(config, DeviceRequest(DeviceAction.TAP)).decision,
        )
    }

    @Test
    fun `strict preset asks before launching a browser or store`() {
        val config = PolicyConfig(preset = DevicePolicy.PRESET_STRICT)
        assertEquals(
            DeviceDecision.ASK,
            DevicePolicy.evaluate(
                config,
                DeviceRequest(DeviceAction.LAUNCH, packageName = "com.android.chrome"),
            ).decision,
        )
        assertEquals(
            DeviceDecision.ALLOW,
            DevicePolicy.evaluate(
                config,
                DeviceRequest(DeviceAction.LAUNCH, packageName = "com.example.chat"),
            ).decision,
        )
    }

    @Test
    fun `read only denies side effects but still allows navigation`() {
        val config = PolicyConfig(readOnly = true)
        assertEquals(
            DeviceDecision.ALLOW,
            DevicePolicy.evaluate(config, DeviceRequest(DeviceAction.TAP, label = "消息")).decision,
        )
        assertEquals(
            DeviceDecision.ALLOW,
            DevicePolicy.evaluate(config, DeviceRequest(DeviceAction.LAUNCH)).decision,
        )
        assertEquals(
            DeviceDecision.DENY,
            DevicePolicy.evaluate(config, DeviceRequest(DeviceAction.SEND_MESSAGE)).decision,
        )
        assertEquals(
            DeviceDecision.DENY,
            DevicePolicy.evaluate(config, DeviceRequest(DeviceAction.CALL)).decision,
        )
        assertEquals(
            DeviceDecision.DENY,
            DevicePolicy.evaluate(config, DeviceRequest(DeviceAction.ALARM)).decision,
        )
    }

    @Test
    fun `read only ignores user allow rules that would widen access`() {
        val config = PolicyConfig(
            readOnly = true,
            rules = listOf(DeviceRule(DeviceDecision.ALLOW, action = DeviceAction.SEND_MESSAGE)),
        )
        assertEquals(
            DeviceDecision.DENY,
            DevicePolicy.evaluate(config, DeviceRequest(DeviceAction.SEND_MESSAGE)).decision,
        )
    }

    @Test
    fun `finance and password apps are blocked by default`() {
        listOf(
            "com.eg.android.AlipayGphone",
            "com.icbc",
            "com.x8bit.bitwarden",
        ).forEach { packageName ->
            val outcome = DevicePolicy.evaluate(
                standard,
                DeviceRequest(DeviceAction.TAP, packageName = packageName),
            )
            assertEquals(packageName, DeviceDecision.DENY, outcome.decision)
        }
    }

    @Test
    fun `sensitive apps can be re enabled but credential guard still applies`() {
        val config = PolicyConfig(blockSensitiveApps = false)
        assertEquals(
            DeviceDecision.ALLOW,
            DevicePolicy.evaluate(
                config,
                DeviceRequest(DeviceAction.OBSERVE, packageName = "com.icbc"),
            ).decision,
        )
        assertEquals(
            DeviceDecision.DENY,
            DevicePolicy.evaluate(
                config,
                DeviceRequest(
                    DeviceAction.TYPE,
                    packageName = "com.icbc",
                    credential = true,
                ),
            ).decision,
        )
    }

    @Test
    fun `credential input is denied before any rule can allow it`() {
        val config = PolicyConfig(
            rules = listOf(DeviceRule(DeviceDecision.ALLOW, action = DeviceAction.TYPE)),
        )
        val outcome = DevicePolicy.evaluate(
            config,
            DeviceRequest(DeviceAction.TYPE, label = "密码", credential = true),
        )
        assertEquals(DeviceDecision.DENY, outcome.decision)
        assertTrue(outcome.reason.contains("密码"))
    }

    @Test
    fun `app rules match the package itself and its sub packages only`() {
        val rule = DeviceRule(DeviceDecision.ALLOW, app = "com.android.chrome")
        assertTrue(rule.matches(DeviceRequest(DeviceAction.TAP, packageName = "com.android.chrome"), null))
        assertTrue(rule.matches(DeviceRequest(DeviceAction.TAP, packageName = "com.android.chrome.dev"), null))
        assertFalse(rule.matches(DeviceRequest(DeviceAction.TAP, packageName = "com.android.chromex"), null))
        assertFalse(rule.matches(DeviceRequest(DeviceAction.TAP, packageName = null), null))
    }

    @Test
    fun `label match is case insensitive and ignores other fields`() {
        val rule = DeviceRule(DeviceDecision.ASK, label = "send")
        assertTrue(rule.matches(DeviceRequest(DeviceAction.TAP, label = "Send now"), null))
        assertFalse(rule.matches(DeviceRequest(DeviceAction.TAP, label = "Cancel"), null))
    }

    @Test
    fun `category mapping is prefix based and unknown apps stay uncategorised`() {
        assertEquals(DeviceCategory.MESSAGING, DevicePolicy.categoryOf("com.tencent.mm"))
        assertEquals(DeviceCategory.MESSAGING, DevicePolicy.categoryOf("com.tencent.mm.plugin"))
        assertEquals(DeviceCategory.FINANCE, DevicePolicy.categoryOf("com.eg.android.AlipayGphone"))
        assertNull(DevicePolicy.categoryOf("com.example.chat"))
        assertNull(DevicePolicy.categoryOf(null))
    }

    @Test
    fun `tap labels map to risky actions`() {
        assertEquals(DeviceAction.PAYMENT, DevicePolicy.inferTapAction("确认支付"))
        assertEquals(DeviceAction.PAYMENT, DevicePolicy.inferTapAction("Buy now"))
        assertEquals(DeviceAction.COMMIT, DevicePolicy.inferTapAction("删除对话"))
        assertEquals(DeviceAction.COMMIT, DevicePolicy.inferTapAction("Uninstall"))
        assertEquals(DeviceAction.INSTALL_APP, DevicePolicy.inferTapAction("安装"))
        assertEquals(DeviceAction.SEND_MESSAGE, DevicePolicy.inferTapAction("发送"))
        assertEquals(DeviceAction.SEND_MESSAGE, DevicePolicy.inferTapAction("傳送"))
        assertEquals(DeviceAction.SEND_MESSAGE, DevicePolicy.inferTapAction("發送"))
        assertEquals(DeviceAction.COMMIT, DevicePolicy.inferTapAction("刪除"))
        assertEquals(DeviceAction.INSTALL_APP, DevicePolicy.inferTapAction("安裝"))
        assertEquals(DeviceAction.PAYMENT, DevicePolicy.inferTapAction("購買"))
        assertNull(DevicePolicy.inferTapAction("查看详情"))
        assertNull(DevicePolicy.inferTapAction(null))
    }

    @Test
    fun `credential detection uses password flags hints and otp labels`() {
        assertTrue(DevicePolicy.isCredentialField("", isPassword = true, text = ""))
        assertTrue(DevicePolicy.isCredentialField("支付密码", isPassword = false, text = ""))
        assertTrue(DevicePolicy.isCredentialField("verification code", isPassword = false, text = "123456"))
        assertTrue(DevicePolicy.isCredentialField("短信验证码", isPassword = false, text = "4821"))
        assertFalse(DevicePolicy.isCredentialField("搜索", isPassword = false, text = "天气"))
        assertTrue(DevicePolicy.isCredentialField("验证码", isPassword = false, text = ""))
    }

    @Test
    fun `remember is only offered for requests with a known package`() {
        val sms = DeviceRequest(DeviceAction.SEND_MESSAGE, label = "发送短信")
        assertFalse(DevicePolicy.canRemember(sms))
        assertNull(DevicePolicy.alwaysAllowRule(sms))

        val inApp = DeviceRequest(DeviceAction.TAP, packageName = "com.android.chrome", label = "删除")
        assertTrue(DevicePolicy.canRemember(inApp))
        val rule = DevicePolicy.alwaysAllowRule(inApp)
        assertEquals("com.android.chrome", rule?.app)
        assertEquals(DeviceAction.TAP, rule?.action)
        assertEquals("删除", rule?.label)
    }

    @Test
    fun `always allow rule does not store coordinate taps`() {
        val request = DeviceRequest(DeviceAction.TAP, packageName = "com.example.chat", label = "坐标 120,340")
        assertNull(DevicePolicy.alwaysAllowRule(request)?.label)
    }

    @Test
    fun `preset is clamped to the supported range`() {
        assertEquals(DevicePolicy.PRESET_RULES_ONLY, DevicePolicy.sanitizePreset(-3))
        assertEquals(DevicePolicy.PRESET_ASK_ALL, DevicePolicy.sanitizePreset(9))
        assertEquals(DevicePolicy.PRESET_STANDARD, DevicePolicy.sanitizePreset(2))
    }

    @Test
    fun `phone numbers are masked before they reach the audit log`() {
        assertEquals("未指定号码", DevicePolicy.maskNumber(null))
        assertEquals("1234", DevicePolicy.maskNumber("1234"))
        assertEquals("13****38", DevicePolicy.maskNumber("13800138"))
        assertEquals("138****8000", DevicePolicy.maskNumber("13800138000"))
    }
}
