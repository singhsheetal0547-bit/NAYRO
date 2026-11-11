package com.example.core.memory

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

data class ShortTermSessionContext(
    var lastMentionedContact: String? = null,
    var lastMentionedFile: String? = null,
    var lastMentionedApp: String? = null,
    var lastStepOutput: String? = null,
    var lastExecutedInstruction: String? = null,
    var lastCalendarEventTitle: String? = null,
    var updatedAtMs: Long = System.currentTimeMillis()
)

sealed class ContinuationResolution {
    object NoPendingContinuation : ContinuationResolution()
    data class SlotFilledResumeIntent(val taskId: String, val resolvedIntent: ResolvedIntent) : ContinuationResolution()
    data class ConfirmationReply(val approved: Boolean) : ContinuationResolution()
    data class NeedsClarification(val taskId: String, val question: String) : ContinuationResolution()
}

/**
 * Multi-Layer Conversation Continuity & Reference Resolution Manager (Part 9 & Phase 11).
 * Handles:
 * - Pending slot filling across turns (e.g. "Rahul ko WhatsApp message bhejna" -> "Kya message bhejna hai?" -> "Main busy hoon")
 * - Conversational Yes/No confirmation continuation
 * - Pronoun and reference resolution ("him", "her", "usko", "that file", "it")
 * - Follow-up task references ("Do the same thing again", "Pichhla task continue karo", "Wahi event kal ke liye bana do", "Remind me after the meeting")
 * - Context expiration so stale conversations are never merged into unrelated requests.
 */
class ConversationContinuityManager {

    private var pendingSlot: PendingSlotContinuation? = null
    private val sessionContext = ShortTermSessionContext()

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
        calendarEventTitle: String? = null
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
        sessionContext.updatedAtMs = System.currentTimeMillis()
    }

    fun getSessionContext(): ShortTermSessionContext = sessionContext.copy()

    /**
     * Resolves pronouns ("him", "her", "usko", "usse", "that file") and contextual follow-up
     * directives ("Do the same thing again", "Wahi event kal ke liye bana do", "Remind me after the meeting")
     * in [rawPrompt] using recent session context.
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

        // 2. "Wahi event kal ke liye bana do"
        if (lower.contains("wahi event") && (lower.contains("kal") || lower.contains("tomorrow"))) {
            val evTitle = sessionContext.lastCalendarEventTitle ?: "Scheduled Meeting"
            return "Create calendar event: $evTitle"
        }

        // 3. "Remind me after the meeting"
        if (lower.startsWith("remind me after the meeting") || lower.contains("meeting ke baad yaad dilana")) {
            val evTitle = sessionContext.lastCalendarEventTitle ?: "the meeting"
            return "Remind me in 60 minutes to follow up after $evTitle"
        }

        var resolved = rawPrompt
        val lastContact = sessionContext.lastMentionedContact
        if (!lastContact.isNullOrBlank()) {
            resolved = resolved
                .replace(Regex("(?i)\\b(usko|usse|unhe|him|her)\\b"), lastContact)
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
        if (lower in setOf("cancel", "stop", "rehnde do", "chhodo")) {
            clearPendingSlot()
            return ContinuationResolution.ConfirmationReply(approved = false)
        }

        if (activeSlot.intentType == UserIntentType.MESSAGE_DRAFT && activeSlot.missingSlotName == "message") {
            val recipient = activeSlot.collectedEntities["recipient"] ?: sessionContext.lastMentionedContact ?: "Contact"
            val cleanedBody = trimmed
                .replaceFirst(Regex("^(?i)(tell\\s+(him|her|them|${Regex.escape(recipient)})\\s*(that)?|say\\s*(that)?|bol\\s*do\\s*(ki)?)\\s*"), "")
                .trim()
                .removeSuffix(".")
                .ifBlank { trimmed }
            clearPendingSlot()
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
            recordEntityContext(contact = trimmed)
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
