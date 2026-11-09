package com.example.adsh

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.security.KeyStore

private val Context.dataStore by preferencesDataStore(name = "adsh_settings")

class SecureSettings(private val context: Context) {
    private val keyAlias = "adsh-deepseek-api"
    private val keyName = stringPreferencesKey("deepseek_encrypted")
    private val profileName = stringPreferencesKey("profile")
    private val workspaceName = stringPreferencesKey("active_workspace")
    private val sessionName = stringPreferencesKey("active_session")
    private val deviceBridgeEnabled = stringPreferencesKey("device_bridge_enabled")
    private val deviceBridgeToken = stringPreferencesKey("device_bridge_token")
    private val themeModeKey = stringPreferencesKey("theme_mode")
    private val accentColorKey = stringPreferencesKey("accent_color")
    private val remoteEngineKey = stringPreferencesKey("remote_engine_url")
    private val speechLanguageKey = stringPreferencesKey("speech_language")
    private val speechEngineKey = stringPreferencesKey("speech_engine")
    private val speechItnKey = stringPreferencesKey("speech_itn")
    private val speechSecondPassKey = stringPreferencesKey("speech_second_pass")
    private val fastThinkingKey = stringPreferencesKey("fast_thinking")
    private val engineAutoStartKey = stringPreferencesKey("engine_auto_start")
    private val engineManualStopKey = stringPreferencesKey("engine_manual_stop")

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }

    suspend fun profile(): String = context.dataStore.data.first()[profileName] ?: "web"
    suspend fun setProfile(value: String) {
        context.dataStore.edit { it[profileName] = value }
    }

    suspend fun workspaceId(): String =
        context.dataStore.data.first()[workspaceName] ?: WorkspaceStore.DEFAULT_ID

    suspend fun setWorkspaceId(id: String) {
        context.dataStore.edit { it[workspaceName] = id }
    }

    suspend fun sessionId(): String? = context.dataStore.data.first()[sessionName]

    suspend fun setSessionId(id: String) {
        context.dataStore.edit { it[sessionName] = id }
    }

    suspend fun clearSessionId() {
        context.dataStore.edit { it.remove(sessionName) }
    }

    suspend fun deviceBridgeEnabled(): Boolean =
        context.dataStore.data.first()[deviceBridgeEnabled] == "true"

    suspend fun setDeviceBridgeEnabled(value: Boolean) {
        context.dataStore.edit { it[deviceBridgeEnabled] = value.toString() }
    }

    suspend fun deviceBridgeToken(): String {
        val values = context.dataStore.data.first()
        values[deviceBridgeToken]?.let { return it }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val token = bytes.joinToString("") { "%02x".format(it) }
        context.dataStore.edit { it[deviceBridgeToken] = token }
        return token
    }

    suspend fun themeMode(): String = context.dataStore.data.first()[themeModeKey] ?: "system"

    suspend fun setThemeMode(value: String) {
        context.dataStore.edit { it[themeModeKey] = value }
    }

    suspend fun accentColor(): String = context.dataStore.data.first()[accentColorKey] ?: "blue"

    suspend fun setAccentColor(value: String) {
        context.dataStore.edit { it[accentColorKey] = value }
    }

    suspend fun speechLanguage(): String =
        context.dataStore.data.first()[speechLanguageKey] ?: "zh-CN"

    suspend fun setSpeechLanguage(value: String) {
        context.dataStore.edit { it[speechLanguageKey] = value }
    }

    suspend fun speechEngine(): String =
        context.dataStore.data.first()[speechEngineKey] ?: "auto"

    suspend fun setSpeechEngine(value: String) {
        context.dataStore.edit { it[speechEngineKey] = value }
    }

    /** Convert unambiguous Chinese numerals (dates, clock times, percentages) to digits. */
    suspend fun speechItn(): Boolean =
        context.dataStore.data.first()[speechItnKey] != "false"

    suspend fun setSpeechItn(value: Boolean) {
        context.dataStore.edit { it[speechItnKey] = value.toString() }
    }

    /** Re-decode the whole utterance with the offline Paraformer model for the final result. */
    suspend fun speechSecondPass(): Boolean =
        context.dataStore.data.first()[speechSecondPassKey] != "false"

    suspend fun setSpeechSecondPass(value: Boolean) {
        context.dataStore.edit { it[speechSecondPassKey] = value.toString() }
    }

    /** Low reasoning effort for short device actions and quick questions. */
    suspend fun fastThinking(): Boolean =
        context.dataStore.data.first()[fastThinkingKey] != "false"

    suspend fun setFastThinking(value: Boolean) {
        context.dataStore.edit { it[fastThinkingKey] = value.toString() }
    }

    /** Start the engine when the app opens, unless the user stopped it explicitly. */
    suspend fun engineAutoStart(): Boolean =
        context.dataStore.data.first()[engineAutoStartKey] != "false"

    suspend fun setEngineAutoStart(value: Boolean) {
        context.dataStore.edit { it[engineAutoStartKey] = value.toString() }
    }

    /** A manual stop disables auto-start until the user starts the engine again. */
    suspend fun engineManuallyStopped(): Boolean =
        context.dataStore.data.first()[engineManualStopKey] == "true"

    suspend fun setEngineManuallyStopped(value: Boolean) {
        context.dataStore.edit { it[engineManualStopKey] = value.toString() }
    }

    suspend fun saveRemoteEngineUrl(value: String?) {
        if (value.isNullOrBlank()) {
            context.dataStore.edit { it.remove(remoteEngineKey) }
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encoded = Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)
        context.dataStore.edit { it[remoteEngineKey] = encoded }
    }

    suspend fun remoteEngineUrl(): String? {
        val value = context.dataStore.data.first()[remoteEngineKey] ?: return null
        return runCatching {
            val bytes = Base64.decode(value, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            }
            cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
        }.getOrNull()
    }

    suspend fun saveKey(value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encoded = Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)
        context.dataStore.edit { it[keyName] = encoded }
    }

    suspend fun apiKey(): String? {
        val value = context.dataStore.data.first()[keyName] ?: return null
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        }
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }
}
