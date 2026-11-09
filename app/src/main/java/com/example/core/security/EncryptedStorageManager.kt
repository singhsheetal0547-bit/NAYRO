package com.example.core.security

import android.content.Context
import android.content.SharedPreferences

/**
 * Storage manager leveraging Android Keystore encryption for secure API key persistence.
 */
class EncryptedStorageManager(context: Context) {
    private val keystoreManager = KeystoreManager(context)
    private val prefs: SharedPreferences = context.getSharedPreferences("nayro_secure_prefs", Context.MODE_PRIVATE)

    fun saveApiKey(providerId: String, apiKey: String) {
        val encrypted = keystoreManager.encrypt(apiKey)
        prefs.edit().putString("key_$providerId", encrypted).apply()
    }

    fun getApiKey(providerId: String): String {
        val encrypted = prefs.getString("key_$providerId", "") ?: ""
        return if (encrypted.isNotEmpty()) keystoreManager.decrypt(encrypted) else ""
    }

    fun removeApiKey(providerId: String) {
        prefs.edit().remove("key_$providerId").apply()
    }
}
