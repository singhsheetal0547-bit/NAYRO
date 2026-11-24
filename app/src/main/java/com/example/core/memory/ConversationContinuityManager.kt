package com.example.core.memory

import com.example.data.local.entities.ConversationEntity

import com.example.core.agent.ResolvedIntent
import com.example.core.agent.UserIntentType

data class PendingSlotContinuation(
    val taskId: String,
    val intentType: UserIntentType,
    val missingSlotName: String,
    val promptToUser: String,
    val collectedEntities: Map<String, String>,
    val createdAtMs: Long = System.currentTimeMillis(),
    val expiresAtMs: Long = System.currentTimeMillis() + 300_000L // 5-minute context window
) {
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs >= expiresAtMs
}

data class ConversationTurn(
    val role: String, // "user" or "assistant"
    val content: String,
    val timestampMs: Long = System.currentTimeMillis()
)

data class ShortTermSessionContext(
    var lastMentionedContact: String? = null,
    var lastMentionedFile: String? = null,
    var lastMentionedApp: String? = null,
    var lastStepOutput: String? = null,
    var lastExecutedInstruction: String? = null,
    var lastCalendarEventTitle: String? = null,
    var lastDraftedOrSentMessage: String? = null,
    var lastDiscussedTopic: String? = null,
    var lastSearchQuery: String? = null,
    var recentCandidateContacts: List<String> = emptyList(),
    var updatedAtMs: Long = System.currentTimeMillis()
)

sealed class ContinuationResolution {
    object NoPendingContinuation : ContinuationResolution()
    data class SlotFilledResumeIntent(val taskId: String, val resolvedIntent: ResolvedIntent) : ContinuationResolution()
    data class ConfirmationReply(val approved: Boolean) : ContinuationResolution()
    data class NeedsClarification(val taskId: String, val question: String) : ContinuationResolution()
}

/**
 * Multi-Layer Conversation Continuity & Reference Resolution Manager (Section 3, Part 9 & Phase 11).
 * Handles:
 * - Pending slot filling across turns (e.g. "Hi Nairo, WhatsApp par Prakash ko message bhejo." -> "Usko bol do main 10 minute mein aa raha hoon.")
 * - Conversational Yes/No confirmation continuation
 * - Pronoun and reference resolution ("Usko", "Usse", "Wahi", "Pehle wala", "Dusra wala", "Abhi wala", "Usi chat mein", "Same contact", "Previous message")
 * - Natural language corrections ("Nahi, message mein 10 minute ki jagah 20 minute likho.")
 * - Follow-up task references ("Do the same thing again", "Pichhla task continue karo", "Wahi event kal ke liye bana do", "Remind me after the meeting")
 * - Context expiration so stale conversations are never merged into unrelated requests.
 */
class ConversationContinuityManager {

    private var pendingSlot: PendingSlotContinuation? = null
    private val sessionContext = ShortTermSessionContext()
    private val recentTurns = mutableListOf<ConversationTurn>()

    fun recordConversationTurn(role: String, content: String) {
        val clean = content.trim()
        if (clean.isEmpty()) return
        recentTurns.add(ConversationTurn(role = role, content = clean))
        while (recentTurns.size > 10) {
            recentTurns.removeAt(0)
        }
        if (role.equals("user", ignoreCase = true)) {
            extractTopicFromQuestion(clean)?.let { topic ->
                sessionContext.lastDiscussedTopic = topic
            }
        }
        sessionContext.updatedAtMs = System.currentTimeMillis()
    }

    /**
     * Rehydrates the short-term conversation window from Room after Android recreates the process.
     * The caller passes prior turns, excluding the current user utterance if it was already saved.
     */
    fun restoreRecentTurnsFromStorage(messages: List<ConversationEntity>) {
        if (messages.isEmpty()) return
        recentTurns.clear()
        messages.sortedBy { it.timestamp }.takeLast(10).forEach { message ->
            val role = if (message.sender.equals("user", ignoreCase = true)) "user" else "assistant"
            recordConversationTurn(role, message.content)
        }
    }

    fun getRecentTurns(limit: Int = 8): List<ConversationTurn> = recentTurns.takeLast(limit)

    fun clearConversationTurns() {
        recentTurns.clear()
        sessionContext.lastDiscussedTopic = null
        sessionContext.lastSearchQuery = null
    }

    fun buildOpenEndedContextSummary(maxTurns: Int = 6): String {
        val turns = recentTurns.takeLast(maxTurns)
        if (turns.isEmpty() && sessionContext.lastDiscussedTopic.isNullOrBlank()) return ""
        return buildString {
            sessionContext.lastDiscussedTopic?.takeIf { it.isNotBlank() }?.let {
                append("Active topic: ").append(it).append("\n")
            }
            if (turns.isNotEmpty()) {
                append("Recent conversation:\n")
                for (t in turns) {
                    val label = if (t.role.equals("user", ignoreCase = true)) "User" else "Assistant"
                    append(label).append(": ").append(t.content.take(280)).append("\n")
                }
            }
        }.trim()
    }

    private fun extractTopicFromQuestion(prompt: String): String? {
        val patterns = listOf(
            Regex("(?i)^(?:what\\s+is|what\\s+are|explain|tell\\s+me\\s+about|define|who\\s+is|how\\s+does)\\s+(.+?)(?:\\s+in\\s+simple.*|\\?|$)"),
            Regex("(?i)^(.+?)\\s+(?:kya\\s+hai|kya\\s+hota\\s+hai|ke\\s+baare\\s+mein\\s+batao|samjhao)[?.!\\s]*$")
        )
        for (p in patterns) {
            val match = p.find(prompt.trim())
            val candidate = match?.groupValues?.getOrNull(1)?.trim()?.trimEnd('?', '.', '!')
            if (!candidate.isNullOrBlank() && candidate.length in 2..80 &&
                candidate.lowercase() !in setOf("it", "this", "that", "is", "them")
            ) {
                return candidate
            }
        }
        return null
    }

    fun getPendingSlot(nowMs: Long = System.currentTimeMillis()): PendingSlotContinuation? {
        val current = pendingSlot ?: return null
        if (current.isExpired(nowMs)) {
            pendingSlot = null
            return null
        }
        return current
    }

    fun registerPendingSlot(continuation: PendingSlotContinuation) {
        pendingSlot = continuation
    }

    fun clearPendingSlot() {
        pendingSlot = null
    }

    fun recordEntityContext(
        contact: String? = null,
        file: String? = null,
        app: String? = null,
        stepOutput: String? = null,
        executedInstruction: String? = null,
        calendarEventTitle: String? = null,
        draftedOrSentMessage: String? = null,
        candidateContacts: List<String>? = null
    ) {
        if (!contact.isNullOrBlank() && !contact.equals("Contact", ignoreCase = true)) {
            sessionContext.lastMentionedContact = contact.trim()
        }
        if (!file.isNullOrBlank()) {
            sessionContext.lastMentionedFile = file.trim()
        }
        if (!app.isNullOrBlank()) {
            sessionContext.lastMentionedApp = app.trim()
        }
        if (!stepOutput.isNullOrBlank()) {
            sessionContext.lastStepOutput = stepOutput.trim()
        }
        if (!executedInstruction.isNullOrBlank()) {
            sessionContext.lastExecutedInstruction = executedInstruction.trim()
        }
        if (!calendarEventTitle.isNullOrBlank()) {
            sessionContext.lastCalendarEventTitle = calendarEventTitle.trim()
        }
        if (!draftedOrSentMessage.isNullOrBlank()) {
            sessionContext.lastDraftedOrSentMessage = draftedOrSentMessage.trim()
        }
        if (!candidateContacts.isNullOrEmpty()) {
            sessionContext.recentCandidateContacts = candidateContacts.map { it.trim() }.filter { it.isNotEmpty() }
        }
        sessionContext.updatedAtMs = System.currentTimeMillis()
    }

    fun recordTopicAndSearchContext(topic: String? = null, searchQuery: String? = null) {
        if (!topic.isNullOrBlank()) {
            sessionContext.lastDiscussedTopic = topic.trim()
        }
        if (!searchQuery.isNullOrBlank()) {
            sessionContext.lastSearchQuery = searchQuery.trim()
        }
        sessionContext.updatedAtMs = System.currentTimeMillis()
    }

    fun getSessionContext(): ShortTermSessionContext = sessionContext.copy()

    /**
     * Resolves pronouns ("him", "her", "usko", "usse", "wahi", "pehle wala", "dusra wala",
     * "abhi wala", "usi chat mein", "same contact", "previous message", "that file"),
     * natural-language corrections ("Nahi, message mein 10 minute ki jagah 20 minute likho"),
     * and contextual follow-up directives in [rawPrompt] using recent session context.
     */
    fun resolveReferencesInPrompt(rawPrompt: String): String {
        val trimmed = rawPrompt.trim()
        val lower = trimmed.lowercase()

        // 1. Repeat / continue previous task follow-ups
        if (lower in setOf(
                "do the same thing again",
                "do the same thing again.",
                "repeat that",
                "wahi phir se karo",
                "pichhla task continue karo",
                "continue where you stopped",
                "continue where you stopped."
            )
        ) {
            sessionContext.lastExecutedInstruction?.takeIf { it.isNotBlank() }?.let { prev ->
                return prev
            }
        }

        // 2. Natural-language message correction (Section 3):
        // "Nahi, message mein 10 minute ki jagah 20 minute likho." / "Replace 10 minute with 20 minute in the message"
        val hindiCorrectionMatch = Regex(
            "(?i)^(?:nahi[,\\s]+|no[,\\s]+)?(?:message|msg)\\s+mein\\s+(.+?)\\s+ki\\s+jagah\\s+(.+?)\\s+(?:likho|likh\\s+do|kar\\s+do|bhejo)\\.?$"
        ).find(trimmed)
        val englishCorrectionMatch = Regex(
            "(?i)^(?:no[,\\s]+)?(?:in\\s+the\\s+message[,\\s]+)?replace\\s+(.+?)\\s+with\\s+(.+?)(?:\\s+in\\s+the\\s+message)?\\.?$"
        ).find(trimmed)
        val correctionPair: Pair<String, String>? = when {
            hindiCorrectionMatch != null -> hindiCorrectionMatch.groupValues[1].trim() to hindiCorrectionMatch.groupValues[2].trim()
            englishCorrectionMatch != null -> englishCorrectionMatch.groupValues[1].trim() to englishCorrectionMatch.groupValues[2].trim()
            else -> null
        }
        if (correctionPair != null) {
            val (oldFragment, newFragment) = correctionPair
            val baseMsg = sessionContext.lastDraftedOrSentMessage ?: "main $oldFragment mein aa raha hoon"
            val updatedMsg = if (baseMsg.contains(oldFragment, ignoreCase = true)) {
                baseMsg.replace(Regex(Regex.escape(oldFragment), RegexOption.IGNORE_CASE), newFragment)
            } else {
                newFragment
            }
            val contact = sessionContext.lastMentionedContact ?: "Contact"
            recordEntityContext(contact = contact, draftedOrSentMessage = updatedMsg)
            return "Send WhatsApp message to $contact: $updatedMsg"
        }

        // 3. Inspect previous sent/drafted message (Section 3):
        // "Jo message maine abhi bheja tha, woh mujhe dikhao." / "Show me the previous message" / "Abhi wala message dikhao"
        if (Regex("(?i)(?:jo\\s+message\\s+maine\\s+abhi\\s+bheja\\s+tha|abhi\\s+wala\\s+message\\s+dikhao|previous\\s+message\\s+dikhao|show\\s+(?:me\\s+)?(?:the\\s+)?(?:previous|last)\\s+message)").containsMatchIn(trimmed)) {
            val contact = sessionContext.lastMentionedContact ?: "Contact"
            return "Read WhatsApp conversation with $contact"
        }

        // 4. "Wahi event kal ke liye bana do"
        if (lower.contains("wahi event") && (lower.contains("kal") || lower.contains("tomorrow"))) {
            val evTitle = sessionContext.lastCalendarEventTitle ?: "Scheduled Meeting"
            return "Create calendar event: $evTitle"
        }

        // 5. "Remind me after the meeting"
        if (lower.startsWith("remind me after the meeting") || lower.contains("meeting ke baad yaad dilana")) {
            val evTitle = sessionContext.lastCalendarEventTitle ?: "the meeting"
            return "Remind me in 60 minutes to follow up after $evTitle"
        }

        // 5b. Open-ended topic follow-up: "Now search for a good video about it" / "is par video dikhao"
        val activeTopic = sessionContext.lastDiscussedTopic
        if (!activeTopic.isNullOrBlank()) {
            val videoFollowUp = Regex(
                "(?i)(?:search\\s+(?:for\\s+)?(?:a\\s+)?(?:good\\s+)?video\\s+(?:about|on)\\s+(?:it|this|that)|(?:is|us)\\s+(?:par|pe|ke\\s+baare\\s+mein)\\s+video\\s+(?:search|dikhao|chalao))"
            ).containsMatchIn(trimmed)
            if (videoFollowUp) {
                sessionContext.lastSearchQuery = activeTopic
                return "Open YouTube and search $activeTopic video"
            }
            val genericSearchFollowUp = Regex(
                "(?i)^(?:now\\s+)?(?:search|find|research)\\s+(?:more\\s+)?(?:about|on)\\s+(?:it|this|that)\\.?$"
            ).containsMatchIn(trimmed)
            if (genericSearchFollowUp) {
                sessionContext.lastSearchQuery = activeTopic
                return "Research $activeTopic"
            }
        }

        // 5c. Ordinal screen result selection: "Open the second result" / "Open the first result" / "Dusra result kholo"
        val ordinalResultMatch = Regex(
            "(?i)^(?:open|play|click|tap|select|kholo|chalao)\\s+(?:the\\s+)?(first|second|third|1st|2nd|3rd|pehla|pehle|dusra|dusre|teesra)\\s+(?:result|video|link|option|item)(?:\\s+(?:kholo|chalao))?\\.?$"
        ).find(trimmed)
        if (ordinalResultMatch != null) {
            val ordinalWord = ordinalResultMatch.groupValues[1].lowercase()
            val indexLabel = when (ordinalWord) {
                "first", "1st", "pehla", "pehle" -> "1"
                "second", "2nd", "dusra", "dusre" -> "2"
                "third", "3rd", "teesra" -> "3"
                else -> "1"
            }
            val topicHint = sessionContext.lastSearchQuery ?: sessionContext.lastDiscussedTopic ?: ""
            return if (topicHint.isNotBlank()) {
                "Tap result $indexLabel for $topicHint"
            } else {
                "Tap result $indexLabel on screen"
            }
        }

        // 6. Ordinal candidate contact references: "Pehle wala" / "Dusra wala"
        var resolved = trimmed
        if (sessionContext.recentCandidateContacts.isNotEmpty()) {
            val firstCandidate = sessionContext.recentCandidateContacts.getOrNull(0)
            val secondCandidate = sessionContext.recentCandidateContacts.getOrNull(1)
            if (firstCandidate != null && Regex("(?i)\\b(?:pehle\\s+wala|pehla\\s+wala|first\\s+one)\\b").containsMatchIn(resolved)) {
                recordEntityContext(contact = firstCandidate)
                resolved = resolved.replace(Regex("(?i)\\b(?:pehle\\s+wala|pehla\\s+wala|first\\s+one)\\b"), firstCandidate)
            } else if (secondCandidate != null && Regex("(?i)\\b(?:dusre\\s+wala|dusra\\s+wala|second\\s+one)\\b").containsMatchIn(resolved)) {
                recordEntityContext(contact = secondCandidate)
                resolved = resolved.replace(Regex("(?i)\\b(?:dusre\\s+wala|dusra\\s+wala|second\\s+one)\\b"), secondCandidate)
            }
        }

        val lastContact = sessionContext.lastMentionedContact
        if (!lastContact.isNullOrBlank()) {
            // 7. Follow-up message command: "Usko bol do main 10 minute mein aa raha hoon." / "Usi chat mein likho ..."
            val uskoBolDoMatch = Regex(
                "^(?i)(?:usko|usse|unhe|usi\\s+chat\\s+mein|same\\s+contact\\s+ko|wahi\\s+ko)\\s+(?:bol\\s+do|bata\\s+do|likho|likh\\s+do|message\\s+bhejo|message\\s+kar\\s+do)\\s+(?:ki\\s+|that\\s+)?(.+?)\\.?$"
            ).find(resolved)
            if (uskoBolDoMatch != null) {
                val msgBody = uskoBolDoMatch.groupValues[1].trim()
                recordEntityContext(contact = lastContact, draftedOrSentMessage = msgBody)
                return "Send WhatsApp message to $lastContact: $msgBody"
            }

            // 8. Contextual follow-up call without repeating contact name:
            // "Ab call bhi karo", "Ab usko call bhi kar do.", "Call bhi karo", "Ab call karo"
            if (lower in setOf(
                    "ab call bhi karo",
                    "ab call bhi karo.",
                    "ab usko call bhi kar do",
                    "ab usko call bhi kar do.",
                    "ab usse call bhi kar do",
                    "ab usse call bhi kar do.",
                    "usko call bhi kar do",
                    "usko call karo",
                    "call bhi karo",
                    "call bhi kar do",
                    "ab call karo",
                    "ab phone karo",
                    "phone bhi lagao"
                )
            ) {
                return "Call $lastContact"
            }

            resolved = resolved
                .replace(Regex("(?i)\\b(usko|usse|unhe|him|her|same\\s+contact|wahi\\s+contact)\\b"), lastContact)
                .replace(Regex("(?i)\\b(?:usi\\s+chat\\s+mein|in\\s+the\\s+same\\s+chat)\\b"), "to $lastContact on WhatsApp")
        }

        // 9. Replace "previous message" / "abhi wala message" with lastDraftedOrSentMessage
        val lastMsg = sessionContext.lastDraftedOrSentMessage
        if (!lastMsg.isNullOrBlank()) {
            resolved = resolved
                .replace(Regex("(?i)\\b(previous\\s+message|abhi\\s+wala\\s+message|abhi\\s+wala)\\b"), lastMsg)
        }

        val lastFile = sessionContext.lastMentionedFile
        if (!lastFile.isNullOrBlank()) {
            resolved = resolved
                .replace(Regex("(?i)\\b(that file|this file|that document|us file)\\b"), lastFile)
        }
        return resolved
    }

    /**
     * Evaluates whether a new user turn is answering an active confirmation or filling a missing slot
     * from the previous turn on the same task.
     */
    fun evaluateIncomingTurn(
        userTurn: String,
        hasActivePendingConfirmation: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ): ContinuationResolution {
        val trimmed = userTurn.trim()
        val lower = trimmed.lowercase()

        // 1. Check if user is answering an active Yes/No confirmation prompt
        if (hasActivePendingConfirmation) {
            if (lower in setOf("yes", "y", "haan", "ha", "han", "bhej do", "send it", "ok", "confirm", "kar do")) {
                return ContinuationResolution.ConfirmationReply(approved = true)
            }
            if (lower in setOf("no", "n", "nahi", "na", "cancel", "mat bhejo", "stop", "reject")) {
                return ContinuationResolution.ConfirmationReply(approved = false)
            }
        }

        // 2. Check if there is a non-expired pending slot waiting for user input
        val activeSlot = getPendingSlot(nowMs) ?: return ContinuationResolution.NoPendingContinuation

        // If user explicitly cancels or switches to a completely different command
        if (lower in setOf("cancel", "stop", "rehnde do", "rehne do", "chhodo")) {
            clearPendingSlot()
            return ContinuationResolution.ConfirmationReply(approved = false)
        }

        if (activeSlot.intentType == UserIntentType.MESSAGE_DRAFT && activeSlot.missingSlotName == "message") {
            val recipient = activeSlot.collectedEntities["recipient"] ?: sessionContext.lastMentionedContact ?: "Contact"
            val cleanedBody = trimmed
                .replaceFirst(
                    Regex("^(?i)(?:(?:usko|usse|unhe)\\s+bol\\s+do\\s*(?:ki)?|tell\\s+(?:him|her|them|${Regex.escape(recipient)})\\s*(?:that)?|say\\s*(?:that)?|bol\\s*do\\s*(?:ki)?)\\s*"),
                    ""
                )
                .trim()
                .removeSuffix(".")
                .ifBlank { trimmed }
            clearPendingSlot()
            recordEntityContext(contact = recipient, draftedOrSentMessage = cleanedBody)
            return ContinuationResolution.SlotFilledResumeIntent(
                taskId = activeSlot.taskId,
                resolvedIntent = ResolvedIntent(
                    type = UserIntentType.MESSAGE_DRAFT,
                    targetAppOrEntity = "com.whatsapp",
                    recipient = recipient,
                    searchQueryOrContent = cleanedBody,
                    rawPrompt = "Send WhatsApp message to $recipient: $cleanedBody"
                )
            )
        }

        if (activeSlot.intentType == UserIntentType.MESSAGE_DRAFT && activeSlot.missingSlotName == "recipient") {
            val draftMsg = activeSlot.collectedEntities["message"].orEmpty()
            clearPendingSlot()
            if (draftMsg.isBlank()) {
                // Now we have recipient, ask for message body on the same taskId!
                val nextSlot = PendingSlotContinuation(
                    taskId = activeSlot.taskId,
                    intentType = UserIntentType.MESSAGE_DRAFT,
                    missingSlotName = "message",
                    promptToUser = "Kya message bhejna hai $trimmed ko?",
                    collectedEntities = mapOf("recipient" to trimmed)
                )
                registerPendingSlot(nextSlot)
                recordEntityContext(contact = trimmed)
                return ContinuationResolution.NeedsClarification(
                    taskId = activeSlot.taskId,
                    question = nextSlot.promptToUser
                )
            }
            recordEntityContext(contact = trimmed, draftedOrSentMessage = draftMsg)
            return ContinuationResolution.SlotFilledResumeIntent(
                taskId = activeSlot.taskId,
                resolvedIntent = ResolvedIntent(
                    type = UserIntentType.MESSAGE_DRAFT,
                    targetAppOrEntity = "com.whatsapp",
                    recipient = trimmed,
                    searchQueryOrContent = draftMsg,
                    rawPrompt = "Send WhatsApp message to $trimmed: $draftMsg"
                )
            )
        }

        return ContinuationResolution.NoPendingContinuation
    }
}
