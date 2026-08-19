package com.herdroid.app.core.hermes

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ProviderConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("herdroid_provider", Context.MODE_PRIVATE)

    fun load(): ProviderConfig = ProviderConfig(
        baseUrl = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL).orEmpty().ifBlank { DEFAULT_BASE_URL },
        model = prefs.getString(KEY_MODEL, "").orEmpty(),
        apiKey = decryptApiKey(prefs.getString(KEY_API_KEY, null)).orEmpty(),
        maxIterations = prefs.getInt(KEY_MAX_ITERATIONS, 8).coerceIn(1, 32),
    )

    fun save(config: ProviderConfig) {
        val editor = prefs.edit()
            .putString(KEY_BASE_URL, config.baseUrl.trim().trimEnd('/'))
            .putString(KEY_MODEL, config.model.trim())
            .putInt(KEY_MAX_ITERATIONS, config.maxIterations.coerceIn(1, 32))

        if (config.apiKey.isBlank()) {
            editor.remove(KEY_API_KEY)
        } else {
            editor.putString(KEY_API_KEY, encryptApiKey(config.apiKey))
        }
        editor.apply()
    }

    private fun encryptApiKey(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val body = Base64.encodeToString(encrypted, Base64.NO_WRAP)
        return "$iv:$body"
    }

    private fun decryptApiKey(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return runCatching {
            val (ivText, bodyText) = value.split(':', limit = 2)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val iv = Base64.decode(ivText, Base64.NO_WRAP)
            val body = Base64.decode(bodyText, Base64.NO_WRAP)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://openrouter.ai/api/v1"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_ALIAS = "herdroid.provider.api_key"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_MODEL = "model"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MAX_ITERATIONS = "max_iterations"
    }
}
