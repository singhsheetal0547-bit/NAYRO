package com.example.core.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.NayroApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Section 15 — Event-Driven System BroadcastReceiver for NAYRO Automations.
 * Handles scheduled AlarmManager intents (`ACTION_EXECUTE_AUTOMATION`), charging state changes,
 * and low-battery broadcasts, routing matched rules directly through UnifiedAgentRuntime.
 */
class NayroAutomationReceiver : BroadcastReceiver() {

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
                            engine.executeRuleNow(ruleId) { instruction ->
                                val res = runtime.executeUserInstructionDetailed(instruction)
                                res.isSuccess to res.message
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
}
