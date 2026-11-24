package com.example.core.identity

import com.example.core.agent.ConversationLanguage
import com.example.core.agent.ConversationalIntelligenceEngine
import com.example.core.memory.ConversationContinuityManager
import com.example.core.memory.MemoryCategory
import com.example.core.memory.MemoryManager
import com.example.core.voice.TtsSynthesisConfig
import kotlinx.coroutines.flow.StateFlow

/**
 * Manages the user's custom assistant name (e.g., NAYRO, QT, Babu, Wavy) and profile synchronization.
 */
class AssistantIdentityManager(private val store: AssistantIdentityStore) {
    val profile: StateFlow<AssistantIdentityProfile> = store.profile

    fun getAssistantName(): String = store.getDisplayName()

    fun setAssistantName(newName: String, syncWakePhrases: Boolean = true): AssistantIdentityProfile {
        return store.updateDisplayName(newName, autoSyncWakePhrases = syncWakePhrases)
    }
}

/**
 * Manages multiple custom wake phrases (e.g., Hey NAYRO, Hi QT, Hey Babu).
 */
class WakeWordProfileManager(private val store: AssistantIdentityStore) {
    fun getWakePhrases(): List<String> = store.getWakePhrases()

    fun addWakePhrase(phrase: String): AssistantIdentityProfile = store.addWakePhrase(phrase)

    fun removeWakePhrase(phrase: String): AssistantIdentityProfile = store.removeWakePhrase(phrase)

    fun replaceWakePhrases(phrases: List<String>): AssistantIdentityProfile = store.setWakePhrases(phrases)

    fun setBackgroundVoiceEnabled(enabled: Boolean): AssistantIdentityProfile =
        store.updatePersonalPreferences(isWakeWordEnabled = enabled)

    fun setFloatingIconEnabled(enabled: Boolean): AssistantIdentityProfile =
        store.updatePersonalPreferences(isFloatingIconEnabled = enabled)
}

/**
 * Manages how NAYRO addresses the user (Sir / Madam / Boss / Babu / Sona / Custom).
 */
class UserAddressingManager(private val store: AssistantIdentityStore) {
    fun getUserAddress(): String = store.getCurrentProfile().userAddress

    fun setUserAddress(address: String): AssistantIdentityProfile {
        return store.updatePersonalPreferences(userAddress = address)
    }

    fun formatGreeting(language: ConversationLanguage): String {
        val address = getUserAddress()
        val name = store.getDisplayName()
        return when (language) {
            ConversationLanguage.HINDI -> "नमस्ते $address! मैं $name हूँ, बताइए आज क्या मदद करूँ?"
            ConversationLanguage.HINGLISH -> "Hello $address! Main $name hoon, bataiye kya karna hai?"
            ConversationLanguage.ENGLISH -> "Hello $address! I'm $name. How can I help you today?"
        }
    }
}

/**
 * Manages assistant TTS voice persona (Male / Female / System Default, adjustable speed & pitch).
 */
class VoicePersonaManager(private val store: AssistantIdentityStore) {
    fun setVoiceGender(gender: VoiceGender): AssistantIdentityProfile =
        store.updatePersonalPreferences(voiceGender = gender)

    fun setVoiceSpeed(rate: Float): AssistantIdentityProfile =
        store.updatePersonalPreferences(speechRate = rate)

    fun setVoicePitch(pitch: Float): AssistantIdentityProfile =
        store.updatePersonalPreferences(voicePitch = pitch)

    fun toTtsConfig(): TtsSynthesisConfig {
        val p = store.getCurrentProfile()
        return TtsSynthesisConfig(
            languageTag = p.languageTag,
            speechRate = p.speechRate,
            pitch = p.voicePitch,
            voiceGender = p.voiceGender,
            autoDetectUtteranceLanguage = p.conversationLanguageMode == ConversationLanguageMode.AUTO
        )
    }
}

/**
 * Controls personality (Professional / Friendly / Casual / Funny / Custom) and response length
 * (Short / Balanced / Detailed) for both online LLM prompts and local replies.
 */
class ConversationStyleEngine(private val store: AssistantIdentityStore) {
    fun setPersonality(mode: PersonalityMode, customNote: String? = null): AssistantIdentityProfile {
        return store.updatePersonalPreferences(
            personalityMode = mode,
            customPersonalityNote = customNote
        )
    }

    fun setResponseLength(length: ResponseLengthMode): AssistantIdentityProfile {
        return store.updatePersonalPreferences(responseLength = length)
    }

    fun buildStyleInstructions(): String {
        val p = store.getCurrentProfile()
        val personalityDirective = when (p.personalityMode) {
            PersonalityMode.PROFESSIONAL -> "Maintain a polished, composed, executive, and reliable tone."
            PersonalityMode.FRIENDLY -> "Be warm, approachable, supportive, and naturally conversational."
            PersonalityMode.CASUAL -> "Keep it relaxed, natural, and everyday conversational."
            PersonalityMode.FUNNY -> "Be witty, light-hearted, and clever while remaining genuinely helpful and accurate."
            PersonalityMode.CUSTOM -> p.customPersonalityNote.ifBlank {
                "Adapt naturally to the user's preferred style while staying helpful and accurate."
            }
        }
        val lengthDirective = when (p.responseLength) {
            ResponseLengthMode.SHORT -> "Keep replies crisp and concise (1-3 short sentences unless a list is needed)."
            ResponseLengthMode.BALANCED -> "Give clear, well-structured, balanced answers without unnecessary filler."
            ResponseLengthMode.DETAILED -> "Provide thorough, step-by-step, comprehensive explanations with helpful details."
        }
        return "$personalityDirective $lengthDirective"
    }
}

/**
 * Adapts output language across Hindi, English, Hinglish, and Auto detection.
 */
class LanguageAdaptationEngine(private val store: AssistantIdentityStore) {
    fun setLanguageMode(mode: ConversationLanguageMode): AssistantIdentityProfile {
        return store.updatePersonalPreferences(conversationLanguageMode = mode)
    }

    fun resolveReplyLanguage(userUtterance: String): ConversationLanguage {
        return when (store.getCurrentProfile().conversationLanguageMode) {
            ConversationLanguageMode.ENGLISH -> ConversationLanguage.ENGLISH
            ConversationLanguageMode.HINDI -> ConversationLanguage.HINDI
            ConversationLanguageMode.HINGLISH -> ConversationLanguage.HINGLISH
            ConversationLanguageMode.AUTO -> ConversationalIntelligenceEngine.detectLanguage(userUtterance)
        }
    }

    fun buildLanguageDirective(userUtterance: String? = null): String {
        return when (store.getCurrentProfile().conversationLanguageMode) {
            ConversationLanguageMode.ENGLISH -> "Always respond clearly in natural English."
            ConversationLanguageMode.HINDI -> "हमेशा स्वाभाविक और स्पष्ट हिंदी (Devanagari) में उत्तर दें।"
            ConversationLanguageMode.HINGLISH -> "Always respond naturally in conversational Hinglish (Hindi written in Latin script mixed smoothly with English)."
            ConversationLanguageMode.AUTO -> {
                if (!userUtterance.isNullOrBlank()) {
                    when (ConversationalIntelligenceEngine.detectLanguage(userUtterance)) {
                        ConversationLanguage.HINDI -> "Match the user's Hindi language naturally."
                        ConversationLanguage.HINGLISH -> "Match the user's Hinglish style naturally."
                        ConversationLanguage.ENGLISH -> "Match the user's English naturally; switch seamlessly if they switch to Hindi or Hinglish."
                    }
                } else {
                    "Automatically detect and mirror the user's language (Hindi, English, or Hinglish) naturally."
                }
            }
        }
    }
}

/**
 * Persists and retrieves user preferences cleanly via [AssistantIdentityStore] and [MemoryManager].
 */
class UserPreferenceMemory(
    private val store: AssistantIdentityStore,
    private val memoryManager: MemoryManager? = null
) {
    fun isMemoryEnabled(): Boolean = store.getCurrentProfile().isMemoryEnabled

    fun setMemoryEnabled(enabled: Boolean): AssistantIdentityProfile =
        store.updatePersonalPreferences(isMemoryEnabled = enabled)

    suspend fun rememberUserPreference(key: String, value: String): Boolean {
        if (!isMemoryEnabled()) return false
        val manager = memoryManager ?: return false
        return manager.saveDirectMemory(MemoryCategory.USER_PREFERENCE, key, value)
    }

    suspend fun getPreferenceContextSnippet(limit: Int = 5): String {
        if (!isMemoryEnabled()) return ""
        val manager = memoryManager ?: return ""
        val memories = manager.refreshCachedMemories().filter { it.userConfirmed }.take(limit)
        if (memories.isEmpty()) return ""
        return "Remembered user facts & preferences: " +
            memories.joinToString("; ") { "${it.keyName}: ${it.memoryValue}" }
    }
}

/**
 * Maintains multi-turn conversation continuity and active task context across turns.
 */
class ConversationContextManager(
    private val continuityManager: ConversationContinuityManager,
    private val conversationalEngine: ConversationalIntelligenceEngine
) {
    fun recordUserTurn(text: String) {
        continuityManager.recordConversationTurn("user", text)
        conversationalEngine.recordTurn("user", text)
    }

    fun recordAssistantTurn(text: String) {
        continuityManager.recordConversationTurn("assistant", text)
        conversationalEngine.recordTurn("assistant", text)
    }

    fun buildRecentContextSummary(limit: Int = 6): String {
        val turns = continuityManager.getRecentTurns(limit)
        if (turns.isEmpty()) return ""
        return turns.joinToString("\n") { "${it.role}: ${it.content.take(240)}" }
    }

    fun clearSessionContext() {
        continuityManager.clearConversationTurns()
        conversationalEngine.clearHistory()
    }
}

/**
 * Unifies identity, addressing, style, language adaptation, and memory into a single personalized
 * system prompt builder for online AI reasoning and adaptive conversational replies.
 */
class AdaptiveConversationEngine(
    private val identityManager: AssistantIdentityManager,
    private val addressingManager: UserAddressingManager,
    private val styleEngine: ConversationStyleEngine,
    private val languageEngine: LanguageAdaptationEngine,
    private val preferenceMemory: UserPreferenceMemory
) {
    fun buildPersonalizedSystemPrompt(
        userUtterance: String? = null,
        extraMemorySnippet: String = ""
    ): String {
        val name = identityManager.getAssistantName()
        val address = addressingManager.getUserAddress()
        val style = styleEngine.buildStyleInstructions()
        val languageDirective = languageEngine.buildLanguageDirective(userUtterance)
        val memoryPart = extraMemorySnippet.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""
        return "You are $name, NAYRO's personalized autonomous Android AI agent. " +
            "Address the user respectfully as '$address' when natural. " +
            "$style $languageDirective " +
            "Never pretend an Android action succeeded unless the runtime verified it.$memoryPart"
    }

    suspend fun buildPersonalizedSystemPromptWithMemory(userUtterance: String? = null): String {
        val memSnippet = preferenceMemory.getPreferenceContextSnippet()
        return buildPersonalizedSystemPrompt(userUtterance, memSnippet)
    }
}

data class PreferenceCommandOutcome(
    val handled: Boolean,
    val confirmationMessage: String = "",
    val requiresVoiceSync: Boolean = false,
    val backgroundVoiceToggle: Boolean? = null,
    val floatingIconToggle: Boolean? = null
)

/**
 * Parses and executes natural-language Personal AI Identity & Preference commands in English,
 * Hinglish, and Hindi so users can update settings seamlessly by voice or chat.
 */
class PersonalPreferenceCommandHandler(
    private val identityManager: AssistantIdentityManager,
    private val wakeProfileManager: WakeWordProfileManager,
    private val addressingManager: UserAddressingManager,
    private val voicePersonaManager: VoicePersonaManager,
    private val styleEngine: ConversationStyleEngine,
    private val languageEngine: LanguageAdaptationEngine,
    private val preferenceMemory: UserPreferenceMemory
) {

    fun tryHandleCommand(rawInput: String): PreferenceCommandOutcome {
        val text = rawInput.trim()
        val lower = text.lowercase()
        if (text.isEmpty()) return PreferenceCommandOutcome(handled = false)

        // 1. Change assistant name ("Change your name to QT", "Tumhara naam Babu rakho", "Rename assistant to Wavy")
        val nameMatch = Regex(
            "(?:change|set|rename)\\s+(?:your|assistant|ai)?\\s*name\\s+(?:to|as)\\s+([A-Za-z0-9_\\- ]{2,24})|" +
                "(?:tumhara|apna)\\s+naam\\s+([A-Za-z0-9_\\- ]{2,24})\\s+(?:rakho|kar\\s*do|rakh\\s*do)",
            RegexOption.IGNORE_CASE
        ).find(text)
        if (nameMatch != null) {
            val newName = (nameMatch.groupValues[1].ifBlank { nameMatch.groupValues[2] })
                .trim().trimEnd('.', '!', '?')
            if (newName.isNotBlank()) {
                val updated = identityManager.setAssistantName(newName, syncWakePhrases = true)
                return PreferenceCommandOutcome(
                    handled = true,
                    confirmationMessage = "Done, ${updated.userAddress}! My assistant name is now '${updated.assistantDisplayName}', and wake phrases are synced (${updated.wakePhrases.take(3).joinToString(", ")}).",
                    requiresVoiceSync = true
                )
            }
        }

        // 2. User addressing ("Call me Boss", "Mujhe Sona bolo", "Address me as Madam")
        val addressMatch = Regex(
            "(?:call|address)\\s+me\\s+(?:as\\s+)?([A-Za-z\\u0900-\\u097F0-9_\\- ]{2,20})|" +
                "(?:mujhe|mujhko)\\s+([A-Za-z\\u0900-\\u097F0-9_\\- ]{2,20})\\s+(?:bolo|bulana|bulao|kaho)",
            RegexOption.IGNORE_CASE
        ).find(text)
        if (addressMatch != null && !lower.contains("call me at") && !lower.contains("call me on")) {
            val newAddr = (addressMatch.groupValues[1].ifBlank { addressMatch.groupValues[2] })
                .trim().trimEnd('.', '!', '?')
            if (newAddr.isNotBlank() && !newAddr.equals("back", ignoreCase = true) && !newAddr.equals("later", ignoreCase = true)) {
                val updated = addressingManager.setUserAddress(newAddr)
                return PreferenceCommandOutcome(
                    handled = true,
                    confirmationMessage = "Got it, ${updated.userAddress}! I will address you as '${updated.userAddress}' from now on."
                )
            }
        }

        // 3. Add / remove wake word ("Add wake word Hi QT", "Remove wake word Hi Qki")
        val addWakeMatch = Regex(
            "(?:add|create)\\s+(?:a\\s+)?wake\\s*(?:word|phrase)\\s+([A-Za-z0-9_\\- ]{3,32})",
            RegexOption.IGNORE_CASE
        ).find(text)
        if (addWakeMatch != null) {
            val phrase = addWakeMatch.groupValues[1].trim().trimEnd('.', '!', '?')
            if (phrase.isNotBlank()) {
                val updated = wakeProfileManager.addWakePhrase(phrase)
                return PreferenceCommandOutcome(
                    handled = true,
                    confirmationMessage = "Added wake phrase '$phrase'. Active wake phrases: ${updated.wakePhrases.joinToString(", ")}.",
                    requiresVoiceSync = true
                )
            }
        }
        val removeWakeMatch = Regex(
            "(?:remove|delete)\\s+wake\\s*(?:word|phrase)\\s+([A-Za-z0-9_\\- ]{3,32})",
            RegexOption.IGNORE_CASE
        ).find(text)
        if (removeWakeMatch != null) {
            val phrase = removeWakeMatch.groupValues[1].trim().trimEnd('.', '!', '?')
            if (phrase.isNotBlank()) {
                val updated = wakeProfileManager.removeWakePhrase(phrase)
                return PreferenceCommandOutcome(
                    handled = true,
                    confirmationMessage = "Updated wake phrases: ${updated.wakePhrases.joinToString(", ")}.",
                    requiresVoiceSync = true
                )
            }
        }

        // 4. Voice gender ("Switch voice to female", "Use male voice")
        if ((lower.contains("voice") || lower.contains("aawaz") || lower.contains("awaz")) &&
            (lower.contains("female") || lower.contains("woman") || lower.contains("ladki") || lower.contains("male") || lower.contains("man") || lower.contains("ladke"))
        ) {
            val gender = if (lower.contains("female") || lower.contains("woman") || lower.contains("ladki")) {
                VoiceGender.FEMALE
            } else {
                VoiceGender.MALE
            }
            voicePersonaManager.setVoiceGender(gender)
            return PreferenceCommandOutcome(
                handled = true,
                confirmationMessage = "Assistant voice switched to ${gender.displayName}.",
                requiresVoiceSync = true
            )
        }

        // 5. Voice speed / pitch ("Speak faster", "Speak slower", "Normal voice speed", "Higher pitch", "Lower pitch")
        if (lower.contains("speak faster") || lower.contains("voice faster") || lower.contains("increase voice speed") || lower.contains("tez bolo")) {
            val next = (identityManager.profile.value.speechRate + 0.2f).coerceIn(0.6f, 1.8f)
            voicePersonaManager.setVoiceSpeed(next)
            return PreferenceCommandOutcome(
                handled = true,
                confirmationMessage = "Voice speed increased to ${"%.1f".format(next)}x.",
                requiresVoiceSync = true
            )
        }
        if (lower.contains("speak slower") || lower.contains("voice slower") || lower.contains("decrease voice speed") || lower.contains("dheere bolo")) {
            val next = (identityManager.profile.value.speechRate - 0.2f).coerceIn(0.6f, 1.8f)
            voicePersonaManager.setVoiceSpeed(next)
            return PreferenceCommandOutcome(
                handled = true,
                confirmationMessage = "Voice speed decreased to ${"%.1f".format(next)}x.",
                requiresVoiceSync = true
            )
        }
        if (lower.contains("higher pitch") || lower.contains("increase voice pitch") || lower.contains("increase pitch")) {
            val next = (identityManager.profile.value.voicePitch + 0.15f).coerceIn(0.6f, 1.6f)
            voicePersonaManager.setVoicePitch(next)
            return PreferenceCommandOutcome(
                handled = true,
                confirmationMessage = "Voice pitch adjusted to ${"%.2f".format(next)}x.",
                requiresVoiceSync = true
            )
        }
        if (lower.contains("lower pitch") || lower.contains("decrease voice pitch") || lower.contains("decrease pitch")) {
            val next = (identityManager.profile.value.voicePitch - 0.15f).coerceIn(0.6f, 1.6f)
            voicePersonaManager.setVoicePitch(next)
            return PreferenceCommandOutcome(
                handled = true,
                confirmationMessage = "Voice pitch adjusted to ${"%.2f".format(next)}x.",
                requiresVoiceSync = true
            )
        }

        // 6. Conversation language ("Talk in Hinglish", "Switch language to Hindi", "Speak in English", "Auto language")
        if (Regex("(?:talk|speak|reply|respond|switch\\s+language|set\\s+language)\\s+(?:in|to)\\s+(hindi|english|hinglish|auto)").containsMatchIn(lower) ||
            lower == "auto language" || lower.contains("hindi mein baat karo") || lower.contains("hinglish mein baat karo")
        ) {
            val mode = when {
                lower.contains("hinglish") -> ConversationLanguageMode.HINGLISH
                lower.contains("hindi") -> ConversationLanguageMode.HINDI
                lower.contains("english") -> ConversationLanguageMode.ENGLISH
                else -> ConversationLanguageMode.AUTO
            }
            languageEngine.setLanguageMode(mode)
            return PreferenceCommandOutcome(
                handled = true,
                confirmationMessage = "Conversation language set to ${mode.displayName}.",
                requiresVoiceSync = true
            )
        }

        // 7. Personality ("Be funny", "Switch personality to professional", "Be casual", "Be friendly")
        if (lower.contains("personality") || lower.startsWith("be professional") || lower.startsWith("be friendly") ||
            lower.startsWith("be casual") || lower.startsWith("be funny") || lower.contains("talk casually") || lower.contains("talk professionally")
        ) {
            val mode = when {
                lower.contains("professional") -> PersonalityMode.PROFESSIONAL
                lower.contains("funny") || lower.contains("humorous") -> PersonalityMode.FUNNY
                lower.contains("casual") -> PersonalityMode.CASUAL
                lower.contains("friendly") -> PersonalityMode.FRIENDLY
                else -> null
            }
            if (mode != null) {
                styleEngine.setPersonality(mode)
                return PreferenceCommandOutcome(
                    handled = true,
                    confirmationMessage = "Personality updated to ${mode.displayName}."
                )
            }
        }

        // 8. Response length ("Keep answers short", "Detailed responses", "Balanced replies")
        if ((lower.contains("answer") || lower.contains("response") || lower.contains("repl")) &&
            (lower.contains("short") || lower.contains("brief") || lower.contains("balanced") || lower.contains("detailed") || lower.contains("long"))
        ) {
            val len = when {
                lower.contains("short") || lower.contains("brief") -> ResponseLengthMode.SHORT
                lower.contains("detailed") || lower.contains("long") -> ResponseLengthMode.DETAILED
                else -> ResponseLengthMode.BALANCED
            }
            styleEngine.setResponseLength(len)
            return PreferenceCommandOutcome(
                handled = true,
                confirmationMessage = "Response length set to ${len.displayName}."
            )
        }

        // 9. Memory ON/OFF ("Turn off memory", "Enable memory")
        if (Regex("(?:turn\\s+off|disable)\\s+(?:personal\\s+)?memory").containsMatchIn(lower)) {
            preferenceMemory.setMemoryEnabled(false)
            return PreferenceCommandOutcome(
                handled = true,
                confirmationMessage = "Personal memory is now OFF. I will not save new preferences until you turn it back ON."
            )
        }
        if (Regex("(?:turn\\s+on|enable)\\s+(?:personal\\s+)?memory").containsMatchIn(lower)) {
            preferenceMemory.setMemoryEnabled(true)
            return PreferenceCommandOutcome(
                handled = true,
                confirmationMessage = "Personal memory is now ON."
            )
        }

        // 10. Independent Floating Icon & Background Voice toggles
        if (Regex("(?:turn\\s+on|enable|show)\\s+floating\\s+(?:icon|assistant|bubble)").containsMatchIn(lower)) {
            wakeProfileManager.setFloatingIconEnabled(true)
            return PreferenceCommandOutcome(
                handled = true,
                confirmationMessage = "Floating icon preference set to ON (independent of background voice).",
                floatingIconToggle = true
            )
        }
        if (Regex("(?:turn\\s+off|disable|hide)\\s+floating\\s+(?:icon|assistant|bubble)").containsMatchIn(lower)) {
            wakeProfileManager.setFloatingIconEnabled(false)
            return PreferenceCommandOutcome(
                handled = true,
                confirmationMessage = "Floating icon preference set to OFF. Background voice remains unaffected.",
                floatingIconToggle = false
            )
        }
        if (Regex("(?:turn\\s+on|enable|start)\\s+background\\s+voice").containsMatchIn(lower)) {
            wakeProfileManager.setBackgroundVoiceEnabled(true)
            return PreferenceCommandOutcome(
                handled = true,
                confirmationMessage = "Background voice mode requested ON.",
                backgroundVoiceToggle = true
            )
        }
        if (Regex("(?:turn\\s+off|disable|stop)\\s+background\\s+voice").containsMatchIn(lower)) {
            wakeProfileManager.setBackgroundVoiceEnabled(false)
            return PreferenceCommandOutcome(
                handled = true,
                confirmationMessage = "Background voice mode turned OFF.",
                backgroundVoiceToggle = false
            )
        }

        return PreferenceCommandOutcome(handled = false)
    }
}

/**
 * Unified Personal Intelligence Hub wiring all 9 personal intelligence components cleanly
 * to NAYRO's existing AssistantIdentityStore, MemoryManager, and ConversationContinuityManager.
 */
class PersonalIntelligenceHub(
    val store: AssistantIdentityStore,
    memoryManager: MemoryManager? = null,
    continuityManager: ConversationContinuityManager = ConversationContinuityManager(),
    conversationalEngine: ConversationalIntelligenceEngine = ConversationalIntelligenceEngine()
) {
    val identityManager = AssistantIdentityManager(store)
    val wakeWordProfileManager = WakeWordProfileManager(store)
    val userAddressingManager = UserAddressingManager(store)
    val voicePersonaManager = VoicePersonaManager(store)
    val conversationStyleEngine = ConversationStyleEngine(store)
    val languageAdaptationEngine = LanguageAdaptationEngine(store)
    val userPreferenceMemory = UserPreferenceMemory(store, memoryManager)
    val conversationContextManager = ConversationContextManager(continuityManager, conversationalEngine)
    val adaptiveConversationEngine = AdaptiveConversationEngine(
        identityManager = identityManager,
        addressingManager = userAddressingManager,
        styleEngine = conversationStyleEngine,
        languageEngine = languageAdaptationEngine,
        preferenceMemory = userPreferenceMemory
    )
    val preferenceCommandHandler = PersonalPreferenceCommandHandler(
        identityManager = identityManager,
        wakeProfileManager = wakeWordProfileManager,
        addressingManager = userAddressingManager,
        voicePersonaManager = voicePersonaManager,
        styleEngine = conversationStyleEngine,
        languageEngine = languageAdaptationEngine,
        preferenceMemory = userPreferenceMemory
    )
}
