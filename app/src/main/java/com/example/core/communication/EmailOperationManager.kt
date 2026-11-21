package com.example.core.communication

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.core.agent.ActionExecutionResult
import com.example.core.agent.VerificationStatus
import com.example.core.notification.NotificationIntelligenceRepository

enum class EmailOperationState {
    EMAIL_COMPOSER_OPENED,
    EMAIL_NOTIFICATIONS_READ,
    EMAIL_CLIENT_UNAVAILABLE,
    INVALID_RECIPIENT
}

data class EmailDraftRequest(
    val recipientEmail: String,
    val subject: String,
    val body: String
)

/**
 * Supported Android Email Integration for NAYRO's Autonomous Communication Agent.
 *
 * Uses official Android `Intent.ACTION_SENDTO` (`mailto:` URI) to open Gmail or the user's
 * installed email client pre-filled with recipient, subject, and body.
 *
 * Honesty Guarantee:
 * Opening an email composer is NEVER reported as a sent/delivered email (`VERIFIED`).
 * It honestly reports `EMAIL_COMPOSER_OPENED` (`INCONCLUSIVE`) until the user taps Send in their email app.
 */
class EmailOperationManager(private val context: Context?) {

    companion object {
        private val EMAIL_REGEX = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
        private val EMAIL_APP_PACKAGES = setOf(
            "com.google.android.gm",
            "com.microsoft.office.outlook",
            "com.yahoo.mobile.client.android.mail",
            "com.samsung.android.email.provider",
            "ch.protonmail.android",
            "me.bluemail.mail"
        )

        fun isValidEmailAddress(email: String): Boolean {
            return EMAIL_REGEX.matches(email.trim())
        }
    }

    var testOverrideEmailClientAvailable: Boolean? = null

    fun hasInstalledEmailClient(): Boolean {
        testOverrideEmailClientAvailable?.let { return it }
        val ctx = context ?: return false
        return try {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
            intent.resolveActivity(ctx.packageManager) != null
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Opens the user's email composer pre-populated with [draft].
     * Never claims the email was transmitted — returns [VerificationStatus.INCONCLUSIVE] with
     * `EMAIL_COMPOSER_OPENED` so the user reviews and taps Send.
     */
    fun openEmailComposer(draft: EmailDraftRequest): ActionExecutionResult {
        val cleanRecipient = draft.recipientEmail.trim()
        if (!isValidEmailAddress(cleanRecipient)) {
            return ActionExecutionResult(
                isSuccess = false,
                outputMessage = "Invalid email address: '$cleanRecipient'. Please provide a valid recipient email.",
                status = VerificationStatus.FAILED
            )
        }

        if (!hasInstalledEmailClient()) {
            return ActionExecutionResult(
                isSuccess = false,
                outputMessage = "No supported email client (such as Gmail or Outlook) is installed on this device.",
                status = VerificationStatus.NOT_SUPPORTED
            )
        }

        val ctx = context
        if (ctx != null) {
            try {
                val uriString = buildString {
                    append("mailto:")
                    append(Uri.encode(cleanRecipient))
                    append("?subject=")
                    append(Uri.encode(draft.subject))
                    append("&body=")
                    append(Uri.encode(draft.body))
                }
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse(uriString)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(intent)
            } catch (e: Exception) {
                return ActionExecutionResult(
                    isSuccess = false,
                    outputMessage = "Failed to open email client: ${e.localizedMessage ?: e.javaClass.simpleName}",
                    status = VerificationStatus.FAILED
                )
            }
        }

        return ActionExecutionResult(
            isSuccess = false, // Honest: opening composer does NOT equal confirmed email transmission
            outputMessage = "[${EmailOperationState.EMAIL_COMPOSER_OPENED.name}] Email draft opened in your email app for $cleanRecipient (Subject: \"${draft.subject}\"). Please review and tap Send — NAYRO does not claim delivery until you send it.",
            status = VerificationStatus.INCONCLUSIVE
        )
    }

    /**
     * Reads recent email notification previews captured via Android NotificationListenerService.
     * Clearly labels results as notification previews rather than full mailbox IMAP/Gmail API access.
     */
    fun readEmailNotificationPreviews(maxItems: Int = 5): ActionExecutionResult {
        if (!NotificationIntelligenceRepository.isNotificationAccessEnabled(context)) {
            return ActionExecutionResult(
                isSuccess = false,
                outputMessage = "Notification Listener access is required to read recent email notification previews.",
                status = VerificationStatus.REQUIRES_PERMISSION
            )
        }
        val all = NotificationIntelligenceRepository.notifications.value
        val emailItems = all.filter { n ->
            n.packageName in EMAIL_APP_PACKAGES ||
                n.packageName.contains("mail", ignoreCase = true) ||
                n.packageName.contains("gmail", ignoreCase = true) ||
                n.packageName.contains("outlook", ignoreCase = true)
        }.take(maxItems.coerceAtLeast(1))

        if (emailItems.isEmpty()) {
            return ActionExecutionResult(
                isSuccess = true,
                outputMessage = "No recent email notifications found (Notification Preview mode — full mailbox access requires an email account integration).",
                status = VerificationStatus.VERIFIED
            )
        }

        val formatted = emailItems.mapIndexed { idx, item ->
            "${idx + 1}. [${item.packageName}] ${item.title}: ${item.text.take(120)}"
        }.joinToString("\n")

        return ActionExecutionResult(
            isSuccess = true,
            outputMessage = "Recent email notifications (Notification Preview, count=${emailItems.size}):\n$formatted",
            status = VerificationStatus.VERIFIED
        )
    }
}
