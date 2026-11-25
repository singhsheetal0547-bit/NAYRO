package com.advanceagent.core

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.advanceagent.core.data.SecurePreferences
import com.advanceagent.core.data.memory.MemoryDatabase

open class AgentApplication : Application() {

    lateinit var prefs: SecurePreferences
        private set

    lateinit var memoryDb: MemoryDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = SecurePreferences(this)
        memoryDb = MemoryDatabase.getInstance(this)
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_AGENT_SERVICE,
                "Advance Voice Agent Background Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps offline wake-word listening and live voice sessions active."
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_AGENT_SERVICE = "advance_agent_service_channel"
    }
}
