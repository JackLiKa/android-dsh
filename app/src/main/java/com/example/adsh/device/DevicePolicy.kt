package com.example.adsh.device

import java.util.Locale

/**
 * On-device policy that the bridge evaluates before any side effect. The agent
 * cannot change a decision: it only receives the resulting error. Matching is
 * deliberately simple so every rule can be explained in one line in the UI.
 */
enum class DeviceDecision { ALLOW, ASK, DENY }

enum class DeviceCategory(val label: String) {
    FINANCE("金融"),
    PASSWORDS("密码管理"),
    MESSAGING("通讯"),
    SOCIAL("社交"),
    STORES("应用商店"),
    BROWSERS("浏览器"),
    PHONE("电话"),
    SETTINGS("系统设置"),
    SYSTEM("系统"),
    OTHER("其他"),
}

enum class DeviceAction(val label: String) {
    OBSERVE("读取设备"),
    TAP("点击"),
    SWIPE("滑动"),
    TYPE("输入文本"),
    LAUNCH("打开应用"),
    SEND_MESSAGE("发送消息"),
    CALL("拨打电话"),
    PAYMENT("支付"),
    COMMIT("破坏性操作"),
    INSTALL_APP("安装应用"),
    OPEN_URL("打开链接"),
    CHANGE_SETTING("修改设置"),
    ALARM("设置闹钟"),
    TIMER("设置计时器"),
    SPEAK("朗读"),
}

data class DeviceRequest(
    val action: DeviceAction,
    val packageName: String? = null,
    val label: String? = null,
    val detail: String = "",
    val credential: Boolean = false,
)

data class DeviceRule(
    val decision: DeviceDecision,
    val app: String? = null,
    val category: DeviceCategory? = null,
    val action: DeviceAction? = null,
    val label: String? = null,
) {
    fun matches(request: DeviceRequest, actualCategory: DeviceCategory?): Boolean {
        app?.takeIf { it.isNotBlank() }?.let { prefix ->
            val packageName = request.packageName?.lowercase(Locale.US) ?: return false
            val normalized = prefix.lowercase(Locale.US)
            if (packageName != normalized && !packageName.startsWith("$normalized.")) return false
        }
        if (category != null && category != actualCategory) return false
        if (action != null && action != request.action) return false
        label?.takeIf { it.isNotBlank() }?.let { needle ->
            val haystack = request.label.orEmpty()
            if (!haystack.contains(needle, ignoreCase = true)) return false
        }
        return true
    }

    fun describe(): String = buildList {
        add(
            when (decision) {
                DeviceDecision.ALLOW -> "允许"
                DeviceDecision.ASK -> "需确认"
                DeviceDecision.DENY -> "拒绝"
            },
        )
        add(app ?: category?.label ?: "所有应用")
        action?.let { add(it.label) }
        label?.takeIf { it.isNotBlank() }?.let { add("“${it.take(24)}”") }
    }.joinToString(" · ")
}

data class PolicyConfig(
    val preset: Int = 2,
    val readOnly: Boolean = false,
    val blockSensitiveApps: Boolean = true,
    val blockCredentialInput: Boolean = true,
    val rules: List<DeviceRule> = emptyList(),
)

data class PolicyOutcome(
    val decision: DeviceDecision,
    val reason: String,
    val rule: DeviceRule? = null,
    val category: DeviceCategory? = null,
)

object DevicePolicy {
    const val PRESET_RULES_ONLY = 0
    const val PRESET_STRICT = 1
    const val PRESET_STANDARD = 2
    const val PRESET_ASK_ALL = 3

    private val sensitiveCategories = setOf(DeviceCategory.FINANCE, DeviceCategory.PASSWORDS)
    private val standardAsk = setOf(
        DeviceAction.PAYMENT,
        DeviceAction.SEND_MESSAGE,
        DeviceAction.CALL,
        DeviceAction.COMMIT,
        DeviceAction.INSTALL_APP,
    )
    private val strictAsk = standardAsk + setOf(
        DeviceAction.OPEN_URL,
        DeviceAction.CHANGE_SETTING,
    )
    private val readOnlyActions = setOf(
        DeviceAction.OBSERVE,
        DeviceAction.LAUNCH,
        DeviceAction.TAP,
        DeviceAction.SWIPE,
        DeviceAction.TYPE,
        DeviceAction.SPEAK,
    )

    private val credentialHints = Regex(
        "密码|口令|验证码|动态码|支付密码|password|passcode|passphrase|verification code|one[- ]time code|\\botp\\b",
        RegexOption.IGNORE_CASE,
    )
    private val otpText = Regex("^\\d{4,8}$")

    private val categoryPrefixes: List<Pair<String, DeviceCategory>> = listOf(
        // Finance
        "com.icbc" to DeviceCategory.FINANCE,
        "com.chinamworld" to DeviceCategory.FINANCE,
        "com.ccb" to DeviceCategory.FINANCE,
        "com.abchina" to DeviceCategory.FINANCE,
        "com.bankcomm" to DeviceCategory.FINANCE,
        "com.cmbchina" to DeviceCategory.FINANCE,
        "cmb.pb" to DeviceCategory.FINANCE,
        "com.unionpay" to DeviceCategory.FINANCE,
        "com.eg.android.AlipayGphone" to DeviceCategory.FINANCE,
        "com.antfortune" to DeviceCategory.FINANCE,
        "com.pingan" to DeviceCategory.FINANCE,
        "com.cgbchina" to DeviceCategory.FINANCE,
        "com.spdb" to DeviceCategory.FINANCE,
        "com.ecitic" to DeviceCategory.FINANCE,
        "com.cebbank" to DeviceCategory.FINANCE,
        "com.psbc" to DeviceCategory.FINANCE,
        "com.paypal" to DeviceCategory.FINANCE,
        "com.venmo" to DeviceCategory.FINANCE,
        "com.coinbase" to DeviceCategory.FINANCE,
        "com.binance.dev" to DeviceCategory.FINANCE,
        // Password managers and authenticators
        "com.onepassword" to DeviceCategory.PASSWORDS,
        "com.agilebits.onepassword" to DeviceCategory.PASSWORDS,
        "com.lastpass" to DeviceCategory.PASSWORDS,
        "com.bitwarden" to DeviceCategory.PASSWORDS,
        "com.x8bit.bitwarden" to DeviceCategory.PASSWORDS,
        "com.dashlane" to DeviceCategory.PASSWORDS,
        "com.keepersecurity" to DeviceCategory.PASSWORDS,
        "com.google.android.apps.authenticator2" to DeviceCategory.PASSWORDS,
        "com.microsoft.authenticator" to DeviceCategory.PASSWORDS,
        "com.azure.authenticator" to DeviceCategory.PASSWORDS,
        "com.samsung.android.authfw" to DeviceCategory.PASSWORDS,
        "org.freeotp" to DeviceCategory.PASSWORDS,
        // Messaging
        "com.android.mms" to DeviceCategory.MESSAGING,
        "com.google.android.apps.messaging" to DeviceCategory.MESSAGING,
        "com.samsung.android.messaging" to DeviceCategory.MESSAGING,
        "com.tencent.mm" to DeviceCategory.MESSAGING,
        "com.tencent.mobileqq" to DeviceCategory.MESSAGING,
        "org.telegram.messenger" to DeviceCategory.MESSAGING,
        "com.google.android.gm" to DeviceCategory.MESSAGING,
        "com.tencent.androidqqmail" to DeviceCategory.MESSAGING,
        "com.microsoft.office.outlook" to DeviceCategory.MESSAGING,
        "com.whatsapp" to DeviceCategory.MESSAGING,
        "com.facebook.orca" to DeviceCategory.MESSAGING,
        "com.discord" to DeviceCategory.MESSAGING,
        "com.slack" to DeviceCategory.MESSAGING,
        "jp.naver.line.android" to DeviceCategory.MESSAGING,
        // Social
        "com.instagram.android" to DeviceCategory.SOCIAL,
        "com.twitter.android" to DeviceCategory.SOCIAL,
        "com.facebook.katana" to DeviceCategory.SOCIAL,
        "com.zhihu.android" to DeviceCategory.SOCIAL,
        "com.sina.weibo" to DeviceCategory.SOCIAL,
        "com.ss.android.ugc.aweme" to DeviceCategory.SOCIAL,
        "com.xingin.xhs" to DeviceCategory.SOCIAL,
        // Stores
        "com.android.vending" to DeviceCategory.STORES,
        "com.xiaomi.market" to DeviceCategory.STORES,
        "com.huawei.appmarket" to DeviceCategory.STORES,
        "com.heytap.market" to DeviceCategory.STORES,
        "com.oppo.market" to DeviceCategory.STORES,
        "com.bbk.appstore" to DeviceCategory.STORES,
        "com.sec.android.app.samsungapps" to DeviceCategory.STORES,
        // Browsers
        "com.android.chrome" to DeviceCategory.BROWSERS,
        "com.chrome.beta" to DeviceCategory.BROWSERS,
        "org.mozilla.firefox" to DeviceCategory.BROWSERS,
        "com.microsoft.emmx" to DeviceCategory.BROWSERS,
        "com.sec.android.app.sbrowser" to DeviceCategory.BROWSERS,
        "com.tencent.mtt" to DeviceCategory.BROWSERS,
        "com.UCMobile" to DeviceCategory.BROWSERS,
        "com.baidu.searchbox" to DeviceCategory.BROWSERS,
        "com.quark.browser" to DeviceCategory.BROWSERS,
        "com.mi.globalbrowser" to DeviceCategory.BROWSERS,
        // Phone and system
        "com.android.dialer" to DeviceCategory.PHONE,
        "com.google.android.dialer" to DeviceCategory.PHONE,
        "com.samsung.android.dialer" to DeviceCategory.PHONE,
        "com.android.incallui" to DeviceCategory.PHONE,
        "com.android.server.telecom" to DeviceCategory.PHONE,
        "com.android.settings" to DeviceCategory.SETTINGS,
        "com.google.android.settings" to DeviceCategory.SETTINGS,
        "com.android.systemui" to DeviceCategory.SYSTEM,
        "com.miui.securitycenter" to DeviceCategory.SETTINGS,
        "com.coloros.safecenter" to DeviceCategory.SETTINGS,
        "com.huawei.systemmanager" to DeviceCategory.SETTINGS,
        "com.lbe.security.miui" to DeviceCategory.SETTINGS,
        "com.android.permissioncontroller" to DeviceCategory.SETTINGS,
    ).sortedByDescending { it.first.length }

    fun categoryOf(packageName: String?): DeviceCategory? {
        val name = packageName?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotEmpty() } ?: return null
        return categoryPrefixes.firstOrNull { (prefix, _) ->
            name == prefix.lowercase(Locale.US) || name.startsWith(prefix.lowercase(Locale.US) + ".")
        }?.second
    }

    fun evaluate(config: PolicyConfig, request: DeviceRequest): PolicyOutcome {
        val category = categoryOf(request.packageName)

        if (request.credential && config.blockCredentialInput) {
            return PolicyOutcome(
                DeviceDecision.DENY,
                "检测到密码或验证码输入框，已阻止写入",
                category = category,
            )
        }
        if (config.blockSensitiveApps && category != null && category in sensitiveCategories) {
            return PolicyOutcome(
                DeviceDecision.DENY,
                "${category?.label}应用受保护，设备桥不能操作",
                category = category,
            )
        }

        if (config.readOnly) {
            if (request.action !in readOnlyActions) {
                return PolicyOutcome(
                    DeviceDecision.DENY,
                    "只读模式不允许${request.action.label}",
                    category = category,
                )
            }
            val outcome = decide(config, request, category, allowUserRules = false)
            if (outcome.decision != DeviceDecision.ALLOW) {
                return PolicyOutcome(
                    DeviceDecision.DENY,
                    "只读模式：${outcome.reason}",
                    outcome.rule,
                    category,
                )
            }
            return outcome
        }

        return decide(config, request, category, allowUserRules = config.preset < PRESET_ASK_ALL)
    }

    private fun decide(
        config: PolicyConfig,
        request: DeviceRequest,
        category: DeviceCategory?,
        allowUserRules: Boolean,
    ): PolicyOutcome {
        val matches = config.rules
            .filter { allowUserRules || it.decision != DeviceDecision.ALLOW }
            .filter { it.matches(request, category) }
        val decision = when {
            matches.any { it.decision == DeviceDecision.DENY } -> DeviceDecision.DENY
            matches.any { it.decision == DeviceDecision.ASK } -> DeviceDecision.ASK
            matches.any { it.decision == DeviceDecision.ALLOW } -> DeviceDecision.ALLOW
            else -> presetDecision(config, request, category)
        }
        val named = matches.firstOrNull { it.decision == decision }
        val reason = named?.describe() ?: presetReason(config, request, category, decision)
        return PolicyOutcome(decision, reason, named, category)
    }

    private fun presetDecision(
        config: PolicyConfig,
        request: DeviceRequest,
        category: DeviceCategory?,
    ): DeviceDecision = when (config.preset) {
        PRESET_ASK_ALL -> DeviceDecision.ASK
        PRESET_RULES_ONLY -> DeviceDecision.ALLOW
        PRESET_STRICT -> when {
            request.action in strictAsk -> DeviceDecision.ASK
            request.action == DeviceAction.LAUNCH &&
                category != null &&
                category in setOf(DeviceCategory.BROWSERS, DeviceCategory.STORES) -> DeviceDecision.ASK
            else -> DeviceDecision.ALLOW
        }
        else -> if (request.action in standardAsk) DeviceDecision.ASK else DeviceDecision.ALLOW
    }

    private fun presetReason(
        config: PolicyConfig,
        request: DeviceRequest,
        category: DeviceCategory?,
        decision: DeviceDecision,
    ): String {
        val preset = presetLabel(config.preset)
        val target = when {
            request.label?.isNotBlank() == true -> "${request.action.label}“${request.label.take(40)}”"
            category != null -> "${category.label} · ${request.action.label}"
            else -> request.action.label
        }
        return when (decision) {
            DeviceDecision.ASK -> "预设“$preset”要求确认：$target"
            DeviceDecision.DENY -> "预设“$preset”拒绝：$target"
            DeviceDecision.ALLOW -> "预设“$preset”允许：$target"
        }
    }

    fun presetLabel(preset: Int): String = when (preset) {
        PRESET_RULES_ONLY -> "仅规则"
        PRESET_STRICT -> "严格"
        PRESET_ASK_ALL -> "每次确认"
        else -> "标准"
    }

    fun sanitizePreset(value: Int): Int = value.coerceIn(PRESET_RULES_ONLY, PRESET_ASK_ALL)

    fun inferTapAction(label: String?): DeviceAction? {
        val text = label?.lowercase(Locale.US)?.replace(" ", "")?.takeIf { it.isNotEmpty() } ?: return null
        val groups = listOf(
            DeviceAction.PAYMENT to listOf(
                "支付", "付款", "转账", "轉賬", "轉帳", "购买", "購買", "下单", "下單",
                "结算", "結算", "充值",
                "pay", "payment", "purchase", "buy", "checkout", "transfer",
            ),
            DeviceAction.COMMIT to listOf(
                "删除", "刪除", "清空", "卸载", "卸載", "注销", "註銷",
                "退出登录", "退出登錄", "解绑", "解綁", "撤销", "撤銷",
                "delete", "remove", "uninstall", "logout", "revoke",
            ),
            DeviceAction.INSTALL_APP to listOf("安装", "安裝", "install"),
            DeviceAction.OPEN_URL to listOf(
                "打开链接", "打開連結", "在浏览器中打开", "在瀏覽器中打開",
                "openlink", "openinbrowser",
            ),
            DeviceAction.SEND_MESSAGE to listOf(
                "发送", "發送", "传送", "傳送", "寄出", "送出",
                "发表", "發表", "發布", "發佈", "发布", "发佈",
                "发帖", "發帖", "發文", "发文", "上传", "上傳", "貼文", "贴文",
                "发作品", "發作品", "发布作品", "發佈作品", "发图文", "發圖文",
                "发布视频", "發布視頻", "发布图文", "發佈圖文",
                "发日常", "發日常", "限时日常", "限時日常",
                "send", "post", "publish",
            ),
        )
        return groups.firstOrNull { (_, words) -> words.any { text.contains(it) } }?.first
    }

    fun isCredentialField(hint: String?, isPassword: Boolean, text: String): Boolean {
        if (isPassword) return true
        val label = hint.orEmpty()
        if (credentialHints.containsMatchIn(label)) return true
        return otpText.matches(text.trim()) &&
            Regex("验证码|动态码|code|otp", RegexOption.IGNORE_CASE).containsMatchIn(label)
    }

    /**
     * "Always allow" is intentionally limited to requests with a known app, so
     * an SMS or call approval can never widen into a blanket rule.
     */
    fun canRemember(request: DeviceRequest): Boolean =
        !request.packageName.isNullOrBlank()

    fun alwaysAllowRule(request: DeviceRequest): DeviceRule? {
        val app = request.packageName?.takeIf { it.isNotBlank() } ?: return null
        return DeviceRule(
            decision = DeviceDecision.ALLOW,
            app = app,
            action = request.action,
            label = request.label
                ?.takeIf { it.isNotBlank() && !it.startsWith("坐标") }
                ?.take(24),
        )
    }

    fun maskNumber(value: String?): String {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return "未指定号码"
        if (raw.length <= 4) return raw
        if (raw.length <= 8) return raw.take(2) + "****" + raw.takeLast(2)
        return raw.take(3) + "****" + raw.takeLast(4)
    }
}
