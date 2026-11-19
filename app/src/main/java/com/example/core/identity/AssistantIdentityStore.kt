package com.example.core.identity

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single authoritative persistent configuration source for NAYRO's assistant identity,
 * configurable wake phrases, voice/TTS preferences, and language settings.
 *
 * - Never hardcodes a permanent assistant name in business logic.
 * - Reads existing user preference from `nayro_permission_setup_prefs` ("assistant_persona_name")
 *   without overwriting existing user settings on upgrade.
 * - Safe in headless JVM unit tests when [context] is null (uses in-memory backing store).
 */
data class AssistantIdentityProfile(
    val assistantDisplayName: String = DEFAULT_DISPLAY_NAME,
    val wakePhrases: List<String> = DEFAULT_WAKE_PHRASES,
    val languageTag: String = "en-IN",
    val responseTone: String = "Balanced",
    val speechRate: Float = 1.0f,
    val isTtsEnabled: Boolean = true,
    val isWakeWordEnabled: Boolean = false,
    val isContinuousConversationEnabled: Boolean = true,
    val activationResponse: String = "Haan, boliye"
) {
    companion object {
        const val PRODUCT_NAME = "NAYRO"
        const val DEFAULT_DISPLAY_NAME = "NAYRO"
        val DEFAULT_WAKE_PHRASES = listOf(
            "Hi Nayro",
            "Hey Nayro",
            "Hi Nero",
            "Hi Babu",
            "Hi Qki"
        )
    }
}

class AssistantIdentityStore(private val context: Context?) {

    companion object {
        private const val IDENTITY_PREFS_NAME = "nayro_assistant_identity_prefs"
        private const val LEGACY_SETUP_PREFS_NAME = "nayro_permission_setup_prefs"
        private const val LEGACY_PERSONA_KEY = "assistant_persona_name"

        private const val KEY_DISPLAY_NAME = "identity_display_name"
        private const val KEY_WAKE_PHRASES = "identity_wake_phrases_csv"
        private const val KEY_LANGUAGE_TAG = "identity_language_tag"
        private const val KEY_RESPONSE_TONE = "identity_response_tone"
        private const val KEY_SPEECH_RATE = "identity_speech_rate"
        private const val KEY_TTS_ENABLED = "identity_tts_enabled"
        private const val KEY_WAKE_ENABLED = "identity_wake_word_enabled"
        private const val KEY_CONTINUOUS_CONVERSATION = "identity_continuous_conversation"
        private const val KEY_ACTIVATION_RESPONSE = "identity_activation_response"

        private const val PHRASE_DELIMITER = "||"

        @Volatile
        var sharedInstance: AssistantIdentityStore? = null

        fun buildDefaultWakePhrasesForName(name: String): List<String> {
            val clean = name.trim()
            if (clean.isEmpty()) return AssistantIdentityProfile.DEFAULT_WAKE_PHRASES
            return listOf("Hi $clean", "Hey $clean", "Hi Nayro", "Hey Nayro").distinctBy { it.lowercase() }
        }
    }

    private val prefs: SharedPreferences? = try {
        context?.getSharedPreferences(IDENTITY_PREFS_NAME, Context.MODE_PRIVATE)
    } catch (_: Exception) {
        null
    }

    private val legacyPrefs: SharedPreferences? = try {
        context?.getSharedPreferences(LEGACY_SETUP_PREFS_NAME, Context.MODE_PRIVATE)
    } catch (_: Exception) {
        null
    }

    private val _profile = MutableStateFlow(loadInitialProfile())
    val profile: StateFlow<AssistantIdentityProfile> = _profile.asStateFlow()

    init {
        sharedInstance = this
    }

    private fun loadInitialProfile(): AssistantIdentityProfile {
        val savedName = prefs?.getString(KEY_DISPLAY_NAME, null)?.takeIf { it.isNotBlank() }
        val legacyName = legacyPrefs?.getString(LEGACY_PERSONA_KEY, null)?.takeIf { it.isNotBlank() }
        val resolvedName = savedName ?: legacyName ?: AssistantIdentityProfile.DEFAULT_DISPLAY_NAME

        val rawPhrases = prefs?.getString(KEY_WAKE_PHRASES, null)
        val resolvedPhrases = if (!rawPhrases.isNullOrBlank()) {
            rawPhrases.split(PHRASE_DELIMITER)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinctBy { it.lowercase() }
                .ifEmpty { buildDefaultWakePhrasesForName(resolvedName) }
        } else {
            // Preserve user's existing custom name in wake phrases if migrated from legacy prefs
            if (legacyName != null) {
                buildDefaultWakePhrasesForName(legacyName)
            } else {
                AssistantIdentityProfile.DEFAULT_WAKE_PHRASES
            }
        }

        val lang = prefs?.getString(KEY_LANGUAGE_TAG, "en-IN")?.takeIf { it.isNotBlank() } ?: "en-IN"
        val tone = prefs?.getString(KEY_RESPONSE_TONE, "Balanced")?.takeIf { it.isNotBlank() } ?: "Balanced"
        val speechRate = prefs?.getFloat(KEY_SPEECH_RATE, 1.0f)?.coerceIn(0.6f, 1.8f) ?: 1.0f
        val ttsEnabled = prefs?.getBoolean(KEY_TTS_ENABLED, true) ?: true
        val wakeEnabled = prefs?.getBoolean(KEY_WAKE_ENABLED, false) ?: false
        val continuousEnabled = prefs?.getBoolean(KEY_CONTINUOUS_CONVERSATION, true) ?: true
        val activationResp = prefs?.getString(KEY_ACTIVATION_RESPONSE, null)?.takeIf { it.isNotBlank() }
            ?: defaultActivationResponseFor(lang)

        return AssistantIdentityProfile(
            assistantDisplayName = resolvedName,
            wakePhrases = resolvedPhrases,
            languageTag = lang,
            responseTone = tone,
            speechRate = speechRate,
            isTtsEnabled = ttsEnabled,
            isWakeWordEnabled = wakeEnabled,
            isContinuousConversationEnabled = continuousEnabled,
            activationResponse = activationResp
        )
    }

    fun getCurrentProfile(): AssistantIdentityProfile = _profile.value

    fun getDisplayName(): String = _profile.value.assistantDisplayName

    fun getWakePhrases(): List<String> = _profile.value.wakePhrases

    fun updateDisplayName(newName: String, autoSyncWakePhrases: Boolean = true): AssistantIdentityProfile {
        val clean = newName.trim().take(24)
        if (clean.isEmpty()) return _profile.value

        val current = _profile.value
        val updatedPhrases = if (autoSyncWakePhrases) {
            val base = listOf("Hi $clean", "Hey $clean")
            val retainedCustom = current.wakePhrases.filterNot { phrase ->
                phrase.equals("Hi ${current.assistantDisplayName}", ignoreCase = true) ||
                    phrase.equals("Hey ${current.assistantDisplayName}", ignoreCase = true)
            }
            (base + retainedCustom).distinctBy { it.lowercase() }
        } else {
            current.wakePhrases
        }

        val updated = current.copy(
            assistantDisplayName = clean,
            wakePhrases = updatedPhrases
        )
        persistProfile(updated)
        return updated
    }

    fun setWakePhrases(phrases: List<String>): AssistantIdentityProfile {
        val cleaned = phrases.map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
        if (cleaned.isEmpty()) return _profile.value
        val updated = _profile.value.copy(wakePhrases = cleaned)
        persistProfile(updated)
        return updated
    }

    fun addWakePhrase(phrase: String): AssistantIdentityProfile {
        val clean = phrase.trim()
        if (clean.isEmpty()) return _profile.value
        val current = _profile.value.wakePhrases
        if (current.any { it.equals(clean, ignoreCase = true) }) return _profile.value
        val updated = _profile.value.copy(wakePhrases = current + clean)
        persistProfile(updated)
        return updated
    }

    fun removeWakePhrase(phrase: String): AssistantIdentityProfile {
        val current = _profile.value.wakePhrases
        val remaining = current.filterNot { it.equals(phrase.trim(), ignoreCase = true) }
        if (remaining.isEmpty()) return _profile.value
        val updated = _profile.value.copy(wakePhrases = remaining)
        persistProfile(updated)
        return updated
    }

    fun updateVoiceAndLanguage(
        languageTag: String? = null,
        responseTone: String? = null,
        speechRate: Float? = null,
        isTtsEnabled: Boolean? = null,
        isWakeWordEnabled: Boolean? = null,
        isContinuousConversationEnabled: Boolean? = null,
        activationResponse: String? = null
    ): AssistantIdentityProfile {
        val cur = _profile.value
        val nextLang = languageTag?.trim()?.takeIf { it.isNotBlank() } ?: cur.languageTag
        val updated = cur.copy(
            languageTag = nextLang,
            responseTone = responseTone?.trim()?.takeIf { it.isNotBlank() } ?: cur.responseTone,
            speechRate = speechRate?.coerceIn(0.6f, 1.8f) ?: cur.speechRate,
            isTtsEnabled = isTtsEnabled ?: cur.isTtsEnabled,
            isWakeWordEnabled = isWakeWordEnabled ?: cur.isWakeWordEnabled,
            isContinuousConversationEnabled = isContinuousConversationEnabled ?: cur.isContinuousConversationEnabled,
            activationResponse = activationResponse?.trim()?.takeIf { it.isNotBlank() }
                ?: if (languageTag != null && cur.activationResponse == defaultActivationResponseFor(cur.languageTag)) {
                    defaultActivationResponseFor(nextLang)
                } else {
                    cur.activationResponse
                }
        )
        persistProfile(updated)
        return updated
    }

    /**
     * Builds a regex prefix stripping any configured wake phrase or assistant display name
     * from a spoken/typed instruction without hardcoding a single permanent persona.
     */
    fun stripWakePrefix(rawInput: String): String {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return trimmed

        val candidates = buildList {
            addAll(_profile.value.wakePhrases)
            val name = _profile.value.assistantDisplayName
            if (name.isNotBlank()) {
                add("Hey $name")
                add("Hi $name")
                add(name)
            }
            add("Hey Babu NAYRO cute baby")
            add("Hi Babu NAYRO cute baby")
            add("Babu NAYRO cute baby")
            add("Hey Nayro")
            add("Hi Nayro")
            add("Nayro")
        }.distinctBy { it.lowercase() }
            .sortedByDescending { it.length }

        for (prefix in candidates) {
            if (trimmed.equals(prefix, ignoreCase = true)) {
                return trimmed
            }
            val pattern = Regex("^(?i)${Regex.escape(prefix)}(?:[\\s,:!.-]+|$)")
            if (pattern.containsMatchIn(trimmed)) {
                val stripped = pattern.replaceFirst(trimmed, "").trim()
                if (stripped.isNotBlank()) return stripped
            }
        }
        return trimmed
    }

    /**
     * Checks whether [utterance] starts with or matches any currently configured wake phrase.
     * Returns a pair of (matchedWakePhrase, optionalInlineCommand).
     */
    fun matchWakePhraseInText(utterance: String): Pair<String, String>? {
        val trimmed = utterance.trim()
        if (trimmed.isEmpty()) return null

        val sortedPhrases = _profile.value.wakePhrases
            .filter { it.isNotBlank() }
            .sortedByDescending { it.length }

        for (phrase in sortedPhrases) {
            if (trimmed.equals(phrase, ignoreCase = true)) {
                return phrase to ""
            }
            val startRegex = Regex("^(?i)${Regex.escape(phrase)}(?:[\\s,:!.-]+)(.+)$")
            val match = startRegex.find(trimmed)
            if (match != null) {
                return phrase to match.groupValues[1].trim()
            }
            if (trimmed.lowercase().contains(phrase.lowercase())) {
                val idx = trimmed.lowercase().indexOf(phrase.lowercase())
                val remainder = trimmed.substring(idx + phrase.length).trim().trimStart(',', ':', '!', '.', '-')
                return phrase to remainder.trim()
            }
        }
        return null
    }

    private fun defaultActivationResponseFor(languageTag: String): String {
        return when {
            languageTag.startsWith("hi", ignoreCase = true) -> "Haan, boliye"
            languageTag.startsWith("en-IN", ignoreCase = true) -> "Yes, listening"
            else -> "Yes, I'm listening"
        }
    }

    private fun persistProfile(updated: AssistantIdentityProfile) {
        _profile.value = updated
        try {
            prefs?.edit()
                ?.putString(KEY_DISPLAY_NAME, updated.assistantDisplayName)
                ?.putString(KEY_WAKE_PHRASES, updated.wakePhrases.joinToString(PHRASE_DELIMITER))
                ?.putString(KEY_LANGUAGE_TAG, updated.languageTag)
                ?.putString(KEY_RESPONSE_TONE, updated.responseTone)
                ?.putFloat(KEY_SPEECH_RATE, updated.speechRate)
                ?.putBoolean(KEY_TTS_ENABLED, updated.isTtsEnabled)
                ?.putBoolean(KEY_WAKE_ENABLED, updated.isWakeWordEnabled)
                ?.putBoolean(KEY_CONTINUOUS_CONVERSATION, updated.isContinuousConversationEnabled)
                ?.putString(KEY_ACTIVATION_RESPONSE, updated.activationResponse)
                ?.apply()
            // Keep legacy permission setup pref synchronized so existing UI screens stay consistent
            legacyPrefs?.edit()
                ?.putString(LEGACY_PERSONA_KEY, updated.assistantDisplayName)
                ?.apply()
        } catch (_: Exception) {
            // Safe no-op in headless JVM tests
        }
    }
}
