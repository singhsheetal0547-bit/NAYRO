package com.example.core.telephony

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.example.NayroApplication
import com.example.core.automation.AutomationTriggerType
import com.example.core.notification.CapturedNotificationItem
import com.example.core.notification.NotificationCategory
import com.example.core.notification.NotificationIntelligenceRepository
import com.example.core.service.ServiceStateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Phase 3B — Authorized Android SMS BroadcastReceiver (`Telephony.Sms.Intents.SMS_RECEIVED_ACTION`).
 * Provides a genuine hardware/OS event source for `INCOMING_SMS_EVENT` when SMS permissions are granted,
 * feeding incoming SMS alerts into both NotificationIntelligenceRepository and AutomationEngine.
 */
class NayroSmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = try {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
        } catch (_: Exception) {
            null
        } ?: return

        if (messages.isEmpty()) return

        val sender = messages.firstOrNull()?.displayOriginatingAddress?.trim().orEmpty().ifEmpty { "Unknown Sender" }
        val combinedBody = messages.joinToString(separator = "") { it.displayMessageBody.orEmpty() }.trim()
        if (combinedBody.isEmpty()) return

        val nowMs = System.currentTimeMillis()
        // Populate authorized buffer so read_sms / OTP extraction also sees real-time incoming SMS
        NotificationIntelligenceRepository.onNotificationPosted(
            CapturedNotificationItem(
                key = "sms_${sender}_$nowMs",
                packageName = "com.android.mms",
                title = sender,
                text = combinedBody,
                category = NotificationCategory.MESSAGE,
                postTime = nowMs
            )
        )

        if (ServiceStateRepository.isBackgroundAgentPaused.value) return

        val pendingResult = try { goAsync() } catch (_: Exception) { null }
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val app = (context.applicationContext as? NayroApplication) ?: NayroApplication.instance
                val payload = "$sender: $combinedBody"
                app.automationEngine.onEventTriggered(
                    triggerType = AutomationTriggerType.INCOMING_SMS_EVENT,
                    eventValue = payload,
                    nowMs = nowMs
                ) { instruction ->
                    val summary = app.agentRuntime.executeUserInstructionDetailed(instruction)
                    summary.isSuccess to summary.message
                }
            } catch (_: Exception) {
                // Safe no-op in test or restricted context
            } finally {
                try {
                    pendingResult?.finish()
                } catch (_: Exception) {}
            }
        }
    }
}
