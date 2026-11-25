package com.advanceagent.core.data

import android.content.Context
import android.content.SharedPreferences
import com.example.core.security.KeystoreManager
import java.util.concurrent.ConcurrentHashMap

enum class NayroPersonalityMode(val label: String, val systemInstructionDirective: String) {
    ASSISTANT(
        label = "Assistant Mode",
        systemInstructionDirective = "Be crisp, action-oriented, and professional. Prioritize fast tool execution and verified answers."
    ),
    COMPANION(
        label = "Companion Mode",
        systemInstructionDirective = "Be warm, conversational, empathetic, and friendly while still executing Android actions reliably."
    ),
    FOCUS(
        label = "Focus Mode",
        systemInstructionDirective = "Be ultra-brief, distraction-free, and direct with minimal chatter. State only essential outcomes."
    );

    companion object {
        fun fromName(raw: String?): NayroPersonalityMode {
            return entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: ASSISTANT
        }
    }
}

class SecurePreferences(context: Context? = null) {

    private val appContext: Context? = context?.applicationContext ?: context

    private val keystoreManager: KeystoreManager? = appContext?.let {
        try {
            KeystoreManager(it)
        } catch (_: Throwable) {
            null
        }
    }

    private val nayroEncryptedStorage: com.example.core.security.EncryptedStorageManager? = appContext?.let {
        try {
            com.example.core.security.EncryptedStorageManager(it)
        } catch (_: Throwable) {
            null
        }
    }

    private val prefs: SharedPreferences? = appContext?.let {
        try {
            it.getSharedPreferences("advance_agent_secure_prefs", Context.MODE_PRIVATE)
        } catch (_: Throwable) {
            null
        }
    }

    private val inMemoryStore = ConcurrentHashMap<String, Any>()

    private fun getString(key: String, default: String): String {
        val sp = prefs
        if (sp != null) {
            val raw = sp.getString(key, null)
            if (raw != null) return raw
        }
        return (inMemoryStore[key] as? String) ?: default
    }

    private fun putString(key: String, value: String) {
        inMemoryStore[key] = value
        prefs?.edit()?.putString(key, value)?.apply()
    }

    private fun getBoolean(key: String, default: Boolean): Boolean {
        val sp = prefs
        if (sp != null && sp.contains(key)) {
            return sp.getBoolean(key, default)
        }
        return (inMemoryStore[key] as? Boolean) ?: default
    }

    private fun putBoolean(key: String, value: Boolean) {
        inMemoryStore[key] = value
        prefs?.edit()?.putBoolean(key, value)?.apply()
    }

    private fun getFloat(key: String, default: Float): Float {
        val sp = prefs
        if (sp != null && sp.contains(key)) {
            return sp.getFloat(key, default)
        }
        return (inMemoryStore[key] as? Float) ?: default
    }

    private fun putFloat(key: String, value: Float) {
        inMemoryStore[key] = value
        prefs?.edit()?.putFloat(key, value)?.apply()
    }

    private fun encryptSecret(plain: String, plainCacheKey: String): String {
        val clean = plain.trim()
        inMemoryStore[plainCacheKey] = clean
        val km = keystoreManager
        return if (km != null && clean.isNotEmpty()) {
            try {
                "ENC:${km.encrypt(clean)}"
            } catch (_: Throwable) {
                clean
            }
        } else {
            clean
        }
    }

    private fun decryptSecret(storageKey: String, plainCacheKey: String): String {
        val raw = getString(storageKey, "")
        if (raw.isEmpty()) return ""
        val km = keystoreManager
        return if (km != null && raw.startsWith("ENC:")) {
            try {
                km.decrypt(raw.removePrefix("ENC:"))
            } catch (_: Throwable) {
                (inMemoryStore[plainCacheKey] as? String).orEmpty()
            }
        } else {
            raw
        }
    }

    private fun resolveBuildConfigGeminiKey(): String {
        val candidateClasses = listOf(
            "com.example.BuildConfig",
            "com.advanceagent.core.BuildConfig"
        )
        for (className in candidateClasses) {
            try {
                val clazz = Class.forName(className)
                val field = clazz.getDeclaredField("GEMINI_API_KEY")
                field.isAccessible = true
                val value = (field.get(null) as? String)?.trim().orEmpty()
                if (value.isNotEmpty() && !value.startsWith("YOUR_", ignoreCase = true)) {
                    return value
                }
            } catch (_: Throwable) {
            }
        }
        return ""
    }

    var geminiApiKey: String
        get() {
            val localKey = decryptSecret(KEY_GEMINI_API, KEY_GEMINI_API_PLAIN).trim()
            if (localKey.isNotEmpty()) return localKey
            val nayroKey = try {
                nayroEncryptedStorage?.getApiKey("gemini_default")?.trim().orEmpty()
            } catch (_: Throwable) {
                ""
            }
            if (nayroKey.isNotEmpty()) {
                inMemoryStore[KEY_GEMINI_API_PLAIN] = nayroKey
                return nayroKey
            }
            return resolveBuildConfigGeminiKey()
        }
        set(value) {
            val clean = value.trim()
            putString(KEY_GEMINI_API, encryptSecret(clean, KEY_GEMINI_API_PLAIN))
            try {
                if (clean.isEmpty()) {
                    nayroEncryptedStorage?.removeApiKey("gemini_default")
                } else {
                    nayroEncryptedStorage?.saveApiKey("gemini_default", clean)
                }
                val nayroApp = appContext as? com.example.NayroApplication
                nayroApp?.providerManager?.refreshActiveProvider()
            } catch (_: Throwable) {
            }
        }

    var ephemeralAccessToken: String
        get() = decryptSecret(KEY_EPHEMERAL_TOKEN, KEY_EPHEMERAL_TOKEN_PLAIN)
        set(value) = putString(KEY_EPHEMERAL_TOKEN, encryptSecret(value, KEY_EPHEMERAL_TOKEN_PLAIN))

    var youtubeApiKey: String
        get() = decryptSecret(KEY_YOUTUBE_API, KEY_YOUTUBE_API_PLAIN)
        set(value) = putString(KEY_YOUTUBE_API, encryptSecret(value, KEY_YOUTUBE_API_PLAIN))

    var youtubeApiEnabled: Boolean
        get() = getBoolean(KEY_YOUTUBE_API_ENABLED, false)
        set(value) = putBoolean(KEY_YOUTUBE_API_ENABLED, value)

    var geminiModel: String
        get() = normalizeModelId(getString(KEY_GEMINI_MODEL, DEFAULT_MODEL))
        set(value) = putString(KEY_GEMINI_MODEL, normalizeModelId(value))

    var voiceName: String
        get() = getString(KEY_VOICE_NAME, "Puck")
        set(value) = putString(KEY_VOICE_NAME, value.trim().ifEmpty { "Puck" })

    var assistantName: String
        get() = getString(KEY_ASSISTANT_NAME, "Babu")
        set(value) = putString(KEY_ASSISTANT_NAME, value.trim().ifEmpty { "Babu" })

    var userName: String
        get() = getString(KEY_USER_NAME, "Boss")
        set(value) = putString(KEY_USER_NAME, value.trim().ifEmpty { "Boss" })

    var personalityMode: NayroPersonalityMode
        get() = NayroPersonalityMode.fromName(getString(KEY_PERSONALITY_MODE, NayroPersonalityMode.ASSISTANT.name))
        set(value) = putString(KEY_PERSONALITY_MODE, value.name)

    var appWakeWordEnabled: Boolean
        get() = getBoolean(KEY_APP_WAKE_ENABLED, true)
        set(value) = putBoolean(KEY_APP_WAKE_ENABLED, value)

    var appWakeWord: String
        get() = getString(KEY_APP_WAKE_WORD, DEFAULT_APP_WAKE_PHRASES)
        set(value) = putString(KEY_APP_WAKE_WORD, value.trim().uppercase())

    var userWakeWordEnabled: Boolean
        get() = getBoolean(KEY_USER_WAKE_ENABLED, true)
        set(value) = putBoolean(KEY_USER_WAKE_ENABLED, value)

    var userCustomWakeWordsRaw: String
        get() = getString(KEY_USER_WAKE_WORDS, "")
        set(value) = putString(KEY_USER_WAKE_WORDS, value.trim())

    var wakeBoostingScore: Float
        get() = getFloat(KEY_WAKE_BOOSTING_SCORE, 2.0f)
        set(value) = putFloat(KEY_WAKE_BOOSTING_SCORE, value.coerceIn(0.5f, 5.0f))

    var wakeTriggerThreshold: Float
        get() = getFloat(KEY_WAKE_TRIGGER_THRESHOLD, 0.25f)
        set(value) = putFloat(KEY_WAKE_TRIGGER_THRESHOLD, value.coerceIn(0.05f, 0.90f))

    var googleSearchGroundingEnabled: Boolean
        get() = getBoolean(KEY_GOOGLE_SEARCH_GROUNDING, true)
        set(value) = putBoolean(KEY_GOOGLE_SEARCH_GROUNDING, value)

    var liveTranscriptionEnabled: Boolean
        get() = getBoolean(KEY_LIVE_TRANSCRIPTION, true)
        set(value) = putBoolean(KEY_LIVE_TRANSCRIPTION, value)

    var sessionResumptionEnabled: Boolean
        get() = getBoolean(KEY_SESSION_RESUMPTION, true)
        set(value) = putBoolean(KEY_SESSION_RESUMPTION, value)

    var lastSessionResumptionHandle: String
        get() = getString(KEY_SESSION_HANDLE, "")
        set(value) = putString(KEY_SESSION_HANDLE, value.trim())

    var soundAndHapticsEnabled: Boolean
        get() = getBoolean(KEY_SOUND_HAPTICS, true)
        set(value) = putBoolean(KEY_SOUND_HAPTICS, value)

    var requireWhatsAppConfirmation: Boolean
        get() = getBoolean(KEY_WHATSAPP_CONFIRM, true)
        set(value) = putBoolean(KEY_WHATSAPP_CONFIRM, value)

    fun getAllActiveWakeWords(): List<String> {
        val list = mutableListOf<String>()
        if (appWakeWordEnabled && appWakeWord.isNotBlank()) {
            appWakeWord
                .split(",", "\n")
                .map { it.trim().uppercase() }
                .filter { it.isNotBlank() }
                .forEach { list.add(it) }
        }
        if (userWakeWordEnabled && userCustomWakeWordsRaw.isNotBlank()) {
            userCustomWakeWordsRaw
                .split(",", "\n")
                .map { it.trim().uppercase() }
                .filter { it.isNotBlank() }
                .forEach { list.add(it) }
        }
        return list.distinct()
    }

    var isMasterPowerOn: Boolean
        get() = getBoolean(KEY_MASTER_POWER, true)
        set(value) = putBoolean(KEY_MASTER_POWER, value)

    var autoStartOnBoot: Boolean
        get() = getBoolean(KEY_BOOT_AUTO_START, true)
        set(value) = putBoolean(KEY_BOOT_AUTO_START, value)

    companion object {
        private const val KEY_GEMINI_API = "gemini_api_key"
        private const val KEY_GEMINI_API_PLAIN = "gemini_api_key_plain"
        private const val KEY_EPHEMERAL_TOKEN = "gemini_ephemeral_token"
        private const val KEY_EPHEMERAL_TOKEN_PLAIN = "gemini_ephemeral_token_plain"
        private const val KEY_YOUTUBE_API = "youtube_api_key"
        private const val KEY_YOUTUBE_API_PLAIN = "youtube_api_key_plain"
        private const val KEY_YOUTUBE_API_ENABLED = "youtube_api_enabled"
        private const val KEY_GEMINI_MODEL = "gemini_model"
        private const val KEY_VOICE_NAME = "voice_name"
        private const val KEY_ASSISTANT_NAME = "assistant_name"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_PERSONALITY_MODE = "personality_mode"
        private const val KEY_APP_WAKE_ENABLED = "app_wake_enabled"
        private const val KEY_APP_WAKE_WORD = "app_wake_word"
        private const val KEY_USER_WAKE_ENABLED = "user_wake_enabled"
        private const val KEY_USER_WAKE_WORDS = "user_wake_words"
        private const val KEY_WAKE_BOOSTING_SCORE = "wake_boosting_score"
        private const val KEY_WAKE_TRIGGER_THRESHOLD = "wake_trigger_threshold"
        private const val KEY_GOOGLE_SEARCH_GROUNDING = "google_search_grounding"
        private const val KEY_LIVE_TRANSCRIPTION = "live_transcription_enabled"
        private const val KEY_SESSION_RESUMPTION = "session_resumption_enabled"
        private const val KEY_SESSION_HANDLE = "session_resumption_handle"
        private const val KEY_SOUND_HAPTICS = "sound_haptics_enabled"
        private const val KEY_WHATSAPP_CONFIRM = "whatsapp_confirm_enabled"
        private const val KEY_MASTER_POWER = "master_power_on"
        private const val KEY_BOOT_AUTO_START = "boot_auto_start"

        const val DEFAULT_APP_WAKE_PHRASES = "HI BABU, HEY BABU, HI NAYRO"
        const val DEFAULT_MODEL = "models/gemini-2.0-flash-exp"

        val SUPPORTED_VOICES = listOf("Puck", "Charon", "Kore", "Fenrir", "Aoede")
        val RECOMMENDED_LIVE_MODELS = listOf(
            "models/gemini-2.0-flash-exp",
            "models/gemini-2.0-flash-live-001",
            "models/gemini-2.5-flash-native-audio-preview-09-2025",
            "models/gemini-2.5-flash"
        )

        fun normalizeModelId(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return DEFAULT_MODEL
            return if (trimmed.startsWith("models/")) trimmed else "models/$trimmed"
        }

        fun validateGeminiLiveModelId(raw: String): Pair<Boolean, String> {
            val clean = raw.trim()
            if (clean.isEmpty()) {
                return false to "Model ID cannot be empty."
            }
            val normalized = normalizeModelId(clean)
            val slug = normalized.removePrefix("models/")
            if (!slug.matches(Regex("^[a-zA-Z0-9._-]+$"))) {
                return false to "Invalid model ID format: '$clean'."
            }
            if (!slug.startsWith("gemini-", ignoreCase = true)) {
                return false to "Gemini Live requires a valid 'gemini-*' model ID."
            }
            return true to "Valid Gemini Live model ID: $normalized"
        }
    }
}
