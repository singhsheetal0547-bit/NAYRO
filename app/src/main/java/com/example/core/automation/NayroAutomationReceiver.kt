package com.example.core.automation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.NayroApplication
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Section 15 & Phase 9 — Event-Driven System BroadcastReceiver for NAYRO Automations & Reminders.
 * Handles scheduled AlarmManager intents (`ACTION_EXECUTE_AUTOMATION`), charging state changes,
 * and low-battery broadcasts, routing matched rules directly through UnifiedAgentRuntime and
 * delivering user-visible notifications for scheduled reminders without duplicates.
 */
class NayroAutomationReceiver : BroadcastReceiver() {

    companion object {
        const val REMINDER_CHANNEL_ID = "nayro_reminders_channel"
        private val deliveredNotifications = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.Default).launch {
            try {
                val app = (context.applicationContext as? NayroApplication) ?: NayroApplication.instance
                val engine = app.automationEngine
                val runtime = app.agentRuntime

                when (action) {
                    AutomationEngine.ACTION_EXECUTE_AUTOMATION -> {
                        val ruleId = intent.getStringExtra(AutomationEngine.EXTRA_RULE_ID)
                        if (!ruleId.isNullOrBlank()) {
                            val record = engine.executeRuleNow(ruleId) { instruction ->
                                val res = runtime.executeUserInstructionDetailed(instruction)
                                res.isSuccess to res.message
                            }
                            if (record != null) {
                                postReminderNotificationIfEligible(context, record.ruleId, record.ruleName, record.outputSummary)
                            }
                        }
                    }

                    Intent.ACTION_POWER_CONNECTED -> {
                        engine.onEventTriggered(
                            triggerType = AutomationTriggerType.CHARGING_STATE_CHANGED,
                            eventValue = "CONNECTED"
                        ) { instruction ->
                            val res = runtime.executeUserInstructionDetailed(instruction)
                            res.isSuccess to res.message
                        }
                    }

                    Intent.ACTION_POWER_DISCONNECTED -> {
                        engine.onEventTriggered(
                            triggerType = AutomationTriggerType.CHARGING_STATE_CHANGED,
                            eventValue = "DISCONNECTED"
                        ) { instruction ->
                            val res = runtime.executeUserInstructionDetailed(instruction)
                            res.isSuccess to res.message
                        }
                    }

                    Intent.ACTION_BATTERY_LOW -> {
                        engine.onEventTriggered(
                            triggerType = AutomationTriggerType.BATTERY_LOW,
                            eventValue = "15"
                        ) { instruction ->
                            val res = runtime.executeUserInstructionDetailed(instruction)
                            res.isSuccess to res.message
                        }
                    }
                }
            } catch (_: Exception) {
                // Safe no-op in restricted background or test contexts
            } finally {
                try {
                    pendingResult?.finish()
                } catch (_: Exception) {}
            }
        }
    }

    private fun postReminderNotificationIfEligible(
        context: Context,
        ruleId: String,
        title: String,
        body: String
    ) {
        val minuteBucket = System.currentTimeMillis() / 60_000L
        val dedupeKey = "$ruleId:$minuteBucket"
        if (!deliveredNotifications.add(dedupeKey)) return

        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    REMINDER_CHANNEL_ID,
                    "Babu Reminders & Scheduled Tasks",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Alerts for scheduled NAYRO reminders and automated tasks."
                }
                nm.createNotificationChannel(channel)
            }
            val notif = NotificationCompat.Builder(context, REMINDER_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            nm.notify(ruleId.hashCode(), notif)
        } catch (_: Exception) {}
    }
}

