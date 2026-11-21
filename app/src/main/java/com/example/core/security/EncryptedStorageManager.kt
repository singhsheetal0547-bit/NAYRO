package com.example.core.security

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * Storage manager leveraging Android Keystore encryption for secure API key persistence.
 * Supports nullable [context] for deterministic JVM unit tests while using hardware-backed
 * Android KeyStore AES-GCM encryption on Android devices.
 */
class EncryptedStorageManager(context: Context? = null) {
    private val keystoreManager: KeystoreManager? = context?.let {
        try {
            KeystoreManager(it)
        } catch (_: Throwable) {
            null
        }
    }
    private val prefs: SharedPreferences? = context?.let {
        try {
            it.getSharedPreferences("nayro_secure_prefs", Context.MODE_PRIVATE)
        } catch (_: Throwable) {
            null
        }
    }
    private val inMemoryFallback = ConcurrentHashMap<String, String>()

    fun saveApiKey(providerId: String, apiKey: String) {
        val cleanId = providerId.trim()
        val cleanKey = apiKey.trim()
        if (cleanId.isEmpty()) return
        if (cleanKey.isEmpty()) {
            removeApiKey(cleanId)
            return
        }
        val km = keystoreManager
        val sp = prefs
        if (km != null && sp != null) {
            try {
                val encrypted = km.encrypt(cleanKey)
                sp.edit().putString("key_$cleanId", encrypted).apply()
            } catch (_: Throwable) {
                inMemoryFallback[cleanId] = cleanKey
            }
        } else {
            inMemoryFallback[cleanId] = cleanKey
        }
    }

    fun getApiKey(providerId: String): String {
        val cleanId = providerId.trim()
        if (cleanId.isEmpty()) return ""
        val km = keystoreManager
        val sp = prefs
        if (km != null && sp != null) {
            val encrypted = sp.getString("key_$cleanId", "") ?: ""
            if (encrypted.isNotEmpty()) {
                return try {
                    km.decrypt(encrypted)
                } catch (_: Throwable) {
                    // Keystore key invalidated or ciphertext corrupted — recover gracefully without crashing startup
                    inMemoryFallback[cleanId].orEmpty()
                }
            }
        }
        return inMemoryFallback[cleanId].orEmpty()
    }

    fun hasApiKey(providerId: String): Boolean = getApiKey(providerId).isNotBlank()

    fun removeApiKey(providerId: String) {
        val cleanId = providerId.trim()
        if (cleanId.isEmpty()) return
        prefs?.edit()?.remove("key_$cleanId")?.apply()
        inMemoryFallback.remove(cleanId)
    }

    fun savePreference(key: String, value: String) {
        val cleanKey = key.trim()
        if (cleanKey.isEmpty()) return
        prefs?.edit()?.putString("pref_$cleanKey", value)?.apply()
        inMemoryFallback["pref_$cleanKey"] = value
    }

    fun getPreference(key: String, defaultValue: String = ""): String {
        val cleanKey = key.trim()
        if (cleanKey.isEmpty()) return defaultValue
        val fromSp = prefs?.getString("pref_$cleanKey", null)
        if (fromSp != null) return fromSp
        return inMemoryFallback["pref_$cleanKey"] ?: defaultValue
    }
}
