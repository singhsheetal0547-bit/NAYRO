package com.advanceagent.core.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.advanceagent.core.AgentApplication
import com.advanceagent.core.ui.orb.FloatingOrbService
import com.advanceagent.core.vision.ScreenCaptureManager

object AgentPowerManager {

    fun turnOn(context: Context) {
        val app = context.applicationContext as AgentApplication
        app.prefs.isMasterPowerOn = true
        val intent = Intent(context, WakeWordForegroundService::class.java).apply {
            action = WakeWordForegroundService.ACTION_START_LISTENING
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun reloadWakeWordsIfRunning(context: Context) {
        val app = context.applicationContext as AgentApplication
        if (!app.prefs.isMasterPowerOn) return
        val intent = Intent(context, WakeWordForegroundService::class.java).apply {
            action = WakeWordForegroundService.ACTION_RELOAD_WAKE_WORDS
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun triggerManualLiveSession(context: Context) {
        val app = context.applicationContext as AgentApplication
        app.prefs.isMasterPowerOn = true
        val intent = Intent(context, WakeWordForegroundService::class.java).apply {
            action = WakeWordForegroundService.ACTION_START_LIVE_SESSION
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun completeShutdown(context: Context) {
        val app = context.applicationContext as AgentApplication
        app.prefs.isMasterPowerOn = false

        ScreenCaptureManager.release()
        context.stopService(Intent(context, FloatingOrbService::class.java))

        val stopIntent = Intent(context, WakeWordForegroundService::class.java).apply {
            action = WakeWordForegroundService.ACTION_COMPLETE_SHUTDOWN
        }
        try {
            context.startService(stopIntent)
        } catch (_: Exception) {
        }
        context.stopService(Intent(context, WakeWordForegroundService::class.java))
    }
}
