package com.example.core.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.NayroApplication
import com.example.core.automation.AutomationTriggerType
import com.example.core.floating.FloatingAssistantPreferences
import com.example.core.floating.NayroFloatingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Restores permitted NAYRO state, re-registers scheduled automations, and safely recovers
 * interrupted tasks on Android device boot (`BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`).
 * Respects Android 10–15+ background service start restrictions.
 */
class NayroBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val app = (context.applicationContext as? NayroApplication) ?: NayroApplication.instance
                app.operatingStateManager.onBootCompleted()
                app.permissionManager.refreshAllPermissions()
                app.capabilityManager.evaluateAll()
                app.agentRuntime.recoverInterruptedTasksOnBoot()

                // Re-register all enabled time-based automations with AlarmManager after reboot
                app.automationEngine.rescheduleAllEnabledRules(context)

                // Fire BOOT_COMPLETED automation rules if configured
                app.automationEngine.onEventTriggered(
                    triggerType = AutomationTriggerType.BOOT_COMPLETED,
                    eventValue = action
                ) { instruction ->
                    val summary = app.agentRuntime.executeUserInstructionDetailed(instruction)
                    summary.isSuccess to summary.message
                }

                // Restore floating overlay if user previously enabled it and overlay permission is still granted
                val floatingPrefs = FloatingAssistantPreferences(context)
                if (floatingPrefs.isFloatingEnabled() && app.permissionManager.canDrawOverlays()) {
                    NayroFloatingService.startService(context)
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
