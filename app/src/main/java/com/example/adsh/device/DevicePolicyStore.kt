package com.example.adsh.device

import android.content.Context
import com.example.adsh.engine.DshJson

/**
 * Synchronous preference store for the policy so bridge worker threads can
 * evaluate a rule without waiting for a coroutine.
 */
class DevicePolicyStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun policy(): PolicyConfig = PolicyConfig(
        preset = DevicePolicy.sanitizePreset(prefs.getInt(KEY_PRESET, DevicePolicy.PRESET_STANDARD)),
        readOnly = prefs.getBoolean(KEY_READ_ONLY, false),
        blockSensitiveApps = prefs.getBoolean(KEY_SENSITIVE, true),
        blockCredentialInput = prefs.getBoolean(KEY_CREDENTIAL, true),
        rules = rules(),
    )

    fun rules(): List<DeviceRule> {
        val raw = prefs.getString(KEY_RULES, null) ?: return emptyList()
        val items = runCatching { DshJson.parse(raw) as? List<*> }.getOrNull().orEmpty()
        return items.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val decision = enumOrNull<DeviceDecision>(map["decision"]) ?: return@mapNotNull null
            DeviceRule(
                decision = decision,
                app = map["app"]?.toString()?.takeIf { it.isNotBlank() },
                category = enumOrNull<DeviceCategory>(map["category"]),
                action = enumOrNull<DeviceAction>(map["action"]),
                label = map["label"]?.toString()?.takeIf { it.isNotBlank() },
            )
        }
    }

    fun setPreset(preset: Int) {
        prefs.edit().putInt(KEY_PRESET, DevicePolicy.sanitizePreset(preset)).apply()
    }

    fun setReadOnly(value: Boolean) {
        prefs.edit().putBoolean(KEY_READ_ONLY, value).apply()
    }

    fun setBlockSensitiveApps(value: Boolean) {
        prefs.edit().putBoolean(KEY_SENSITIVE, value).apply()
    }

    fun setBlockCredentialInput(value: Boolean) {
        prefs.edit().putBoolean(KEY_CREDENTIAL, value).apply()
    }

    fun addRule(rule: DeviceRule) {
        val updated = (rules() + rule).distinct()
        writeRules(updated)
    }

    fun removeRule(index: Int) {
        val current = rules().toMutableList()
        if (index !in current.indices) return
        current.removeAt(index)
        writeRules(current)
    }

    fun clearRules() {
        prefs.edit().remove(KEY_RULES).apply()
    }

    private fun writeRules(rules: List<DeviceRule>) {
        val encoded = rules.map { rule ->
            linkedMapOf(
                "decision" to rule.decision.name,
                "app" to rule.app,
                "category" to rule.category?.name,
                "action" to rule.action?.name,
                "label" to rule.label,
            )
        }
        prefs.edit().putString(KEY_RULES, DshJson.stringify(encoded)).apply()
    }

    private inline fun <reified T : Enum<T>> enumOrNull(value: Any?): T? {
        val name = value?.toString()?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { enumValueOf<T>(name) }.getOrNull()
    }

    private companion object {
        const val PREFS = "adsh_device_policy"
        const val KEY_PRESET = "preset"
        const val KEY_READ_ONLY = "read_only"
        const val KEY_SENSITIVE = "block_sensitive_apps"
        const val KEY_CREDENTIAL = "block_credential_input"
        const val KEY_RULES = "always_allow"
    }
}
