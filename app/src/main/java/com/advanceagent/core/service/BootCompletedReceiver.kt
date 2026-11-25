package com.advanceagent.core.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.advanceagent.core.AgentApplication

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as? AgentApplication ?: return
        if (app.prefs.isMasterPowerOn && app.prefs.autoStartOnBoot) {
            AgentPowerManager.turnOn(context)
        }
    }
}
