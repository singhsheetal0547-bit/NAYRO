package com.advanceagent.core.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.advanceagent.core.vision.UiGroundingEngine
import com.example.core.accessibility.NayroAccessibilityService
import java.net.URLEncoder
import java.util.UUID

data class ResolvedContactCandidate(
    val displayName: String,
    val phoneNumber: String,
    val normalizedDigits: String
)

data class PendingWhatsAppDraft(
    val draftId: String = UUID.randomUUID().toString().take(8),
    val recipientName: String,
    val phoneNumber: String,
    val messageText: String,
    val requiresConfirmation: Boolean = true,
    val isConfirmed: Boolean = false,
    val createdAtMs: Long = System.currentTimeMillis()
)

class ContactResolver(private val context: Context?) {

    private val testContacts = mutableListOf<ResolvedContactCandidate>()

    fun seedTestContacts(candidates: List<ResolvedContactCandidate>) {
        testContacts.clear()
        testContacts.addAll(candidates)
    }

    fun resolveCandidates(targetQuery: String): List<ResolvedContactCandidate> {
        val clean = targetQuery.trim()
        if (clean.isEmpty()) return emptyList()

        val digitsOnly = clean.replace(Regex("[^0-9+]"), "")
        if (digitsOnly.count { it.isDigit() } >= 7) {
            return listOf(
                ResolvedContactCandidate(
                    displayName = clean,
                    phoneNumber = digitsOnly,
                    normalizedDigits = digitsOnly.replace(Regex("[^0-9]"), "")
                )
            )
        }

        if (testContacts.isNotEmpty()) {
            return testContacts.filter {
                it.displayName.equals(clean, ignoreCase = true) ||
                    it.displayName.contains(clean, ignoreCase = true)
            }
        }

        val ctx = context ?: return emptyList()
        val matches = mutableListOf<ResolvedContactCandidate>()
        try {
            val cursor = ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$clean%"),
                null
            )
            cursor?.use {
                while (it.moveToNext()) {
                    val name = it.getString(0)?.trim().orEmpty()
                    val num = it.getString(1)?.trim().orEmpty()
                    val norm = num.replace(Regex("[^0-9]"), "")
                    if (norm.isNotEmpty()) {
                        matches.add(ResolvedContactCandidate(name, num, norm))
                    }
                }
            }
        } catch (_: Exception) {
        }
        return matches.distinctBy { "${it.displayName.lowercase()}_${it.normalizedDigits}" }
    }
}

/**
 * Verified WhatsApp Workflow Manager (Section 8):
 * 1. Resolves contact via [ContactResolver] and asks user to disambiguate if multiple contacts match.
 * 2. Stages a [PendingWhatsAppDraft] and requests explicit user confirmation when [requireConfirmation] is true.
 * 3. Opens the exact chat, attempts Accessibility UI Send click & verifies chat state before claiming "sent".
 * 4. Never claims "Message sent" merely because a WhatsApp Intent opened.
 */
class WhatsAppAssistant(
    private val context: Context?,
    val contactResolver: ContactResolver = ContactResolver(context)
) {
    @Volatile
    var pendingDraft: PendingWhatsAppDraft? = null
        private set

    fun stageOrExecuteWhatsApp(
        target: String,
        message: String,
        requireConfirmation: Boolean = true,
        confirmedByUser: Boolean = false
    ): Map<String, Any> {
        val cleanTarget = target.trim()
        val cleanMsg = message.trim()
        if (cleanTarget.isEmpty() || cleanMsg.isEmpty()) {
            return mapOf(
                "status" to "error",
                "verified_sent" to false,
                "message" to "Both recipient target and message text are required."
            )
        }

        val candidates = contactResolver.resolveCandidates(cleanTarget)
        if (candidates.isEmpty()) {
            return mapOf(
                "status" to "not_found",
                "verified_sent" to false,
                "message" to "Contact '$cleanTarget' not found in device contacts."
            )
        }

        // Disambiguation when multiple distinct contacts match (unless exact full name match is unique)
        val exactMatches = candidates.filter { it.displayName.equals(cleanTarget, ignoreCase = true) }
        val resolvedList = if (exactMatches.size == 1) exactMatches else candidates
        if (resolvedList.size > 1) {
            val options = resolvedList.take(5).map { "${it.displayName} (${it.phoneNumber})" }
            return mapOf(
                "status" to "disambiguation_required",
                "verified_sent" to false,
                "candidates" to options,
                "message" to "Multiple contacts match '$cleanTarget': ${options.joinToString(", ")}. Please specify which one."
            )
        }

        val chosen = resolvedList.first()
        val draft = PendingWhatsAppDraft(
            recipientName = chosen.displayName,
            phoneNumber = chosen.normalizedDigits,
            messageText = cleanMsg,
            requiresConfirmation = requireConfirmation,
            isConfirmed = confirmedByUser || !requireConfirmation
        )
        pendingDraft = draft

        if (requireConfirmation && !confirmedByUser) {
            return mapOf(
                "status" to "confirmation_required",
                "verified_sent" to false,
                "draft_id" to draft.draftId,
                "recipient" to draft.recipientName,
                "phone" to draft.phoneNumber,
                "message_preview" to draft.messageText,
                "message" to "Staged WhatsApp draft (${draft.draftId}) to ${draft.recipientName}: \"${draft.messageText}\". Ask the user to confirm before calling confirm_whatsapp_draft."
            )
        }

        return executeConfirmedDraft(draft)
    }

    fun confirmAndSendPendingDraft(draftId: String? = null): Map<String, Any> {
        val draft = pendingDraft ?: return mapOf(
            "status" to "no_pending_draft",
            "verified_sent" to false,
            "message" to "No pending WhatsApp draft found to confirm."
        )
        if (!draftId.isNullOrBlank() && !draft.draftId.equals(draftId.trim(), ignoreCase = true)) {
            return mapOf(
                "status" to "draft_id_mismatch",
                "verified_sent" to false,
                "message" to "Pending draft ID is ${draft.draftId}, not $draftId."
            )
        }
        val confirmed = draft.copy(isConfirmed = true)
        pendingDraft = null
        return executeConfirmedDraft(confirmed)
    }

    private fun executeConfirmedDraft(draft: PendingWhatsAppDraft): Map<String, Any> {
        val ctx = context ?: return mapOf(
            "status" to "staged_for_device",
            "verified_sent" to false,
            "recipient" to draft.recipientName,
            "phone" to draft.phoneNumber,
            "message" to "WhatsApp draft confirmed in headless environment; device UI required for final send verification."
        )

        return try {
            val encodedMsg = URLEncoder.encode(draft.messageText, "UTF-8")
            val uri = Uri.parse("https://wa.me/${draft.phoneNumber}?text=$encodedMsg")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)

            // Verify via Accessibility if connected
            val activeRoot = AgentAccessibilityService.instance?.rootInActiveWindow
                ?: NayroAccessibilityService.instance?.getSafeRootInActiveWindow()

            if (activeRoot != null) {
                val clickedSend = AgentAccessibilityService.clickNodeInRoot(activeRoot, "Send")
                val postElements = UiGroundingEngine.extractScreenElements(activeRoot)
                val messageVisibleInChat = postElements.any {
                    it.text.contains(draft.messageText, ignoreCase = true)
                }
                if (clickedSend && messageVisibleInChat) {
                    return mapOf(
                        "status" to "verified_sent",
                        "verified_sent" to true,
                        "recipient" to draft.recipientName,
                        "message" to "Verified: WhatsApp message sent to ${draft.recipientName} via Accessibility."
                    )
                }
            }

            // Honest outcome when only the pre-filled WhatsApp chat Intent opened
            mapOf(
                "status" to "chat_opened_awaiting_send_tap",
                "verified_sent" to false,
                "recipient" to draft.recipientName,
                "phone" to draft.phoneNumber,
                "message" to "Opened WhatsApp chat for ${draft.recipientName} with pre-filled message. Accessibility Send tap was not yet verified."
            )
        } catch (e: Exception) {
            mapOf(
                "status" to "error",
                "verified_sent" to false,
                "message" to (e.message ?: "Failed to open WhatsApp")
            )
        }
    }
}

class ContactCaller(
    private val context: Context?,
    private val contactResolver: ContactResolver = ContactResolver(context)
) {
    fun dialOrCallContact(target: String, directCallConfirmed: Boolean = false): Map<String, Any> {
        val clean = target.trim()
        if (clean.isEmpty()) {
            return mapOf("status" to "error", "message" to "Call target cannot be empty.")
        }

        val candidates = contactResolver.resolveCandidates(clean)
        if (candidates.isEmpty()) {
            return mapOf("status" to "not_found", "message" to "No phone number found for '$clean'.")
        }

        val exactMatches = candidates.filter { it.displayName.equals(clean, ignoreCase = true) }
        val resolvedList = if (exactMatches.size == 1) exactMatches else candidates
        if (resolvedList.size > 1) {
            val options = resolvedList.take(5).map { "${it.displayName} (${it.phoneNumber})" }
            return mapOf(
                "status" to "disambiguation_required",
                "candidates" to options,
                "message" to "Multiple contacts match '$clean': ${options.joinToString(", ")}. Please clarify."
            )
        }

        val chosen = resolvedList.first()
        val ctx = context ?: return mapOf(
            "status" to "resolved_in_test",
            "recipient" to chosen.displayName,
            "phone" to chosen.phoneNumber
        )

        val hasCallPermission = ContextCompat.checkSelfPermission(
            ctx,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        val action = if (directCallConfirmed && hasCallPermission) {
            Intent.ACTION_CALL
        } else {
            Intent.ACTION_DIAL
        }

        return try {
            val intent = Intent(action, Uri.parse("tel:${chosen.phoneNumber}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
            mapOf(
                "status" to "ok",
                "mode" to if (action == Intent.ACTION_CALL) "direct_call_initiated" else "dialer_opened",
                "recipient" to chosen.displayName,
                "dialing" to chosen.phoneNumber
            )
        } catch (e: Exception) {
            mapOf("status" to "error", "message" to (e.message ?: "Failed to launch dialer"))
        }
    }
}
