package com.example.adsh

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
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
    private val providerName = stringPreferencesKey("model_provider")
    private val modelName = stringPreferencesKey("model_name")

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

    suspend fun selectedModel(): ModelRoute {
        val values = context.dataStore.data.first()
        return ModelRoute(values[providerName] ?: BuiltInModels.DEEPSEEK_PROVIDER,
            values[modelName] ?: BuiltInModels.DEEPSEEK_FLASH)
    }

    suspend fun setSelectedModel(route: ModelRoute) {
        context.dataStore.edit {
            it[providerName] = route.provider
            it[modelName] = route.model
        }
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
