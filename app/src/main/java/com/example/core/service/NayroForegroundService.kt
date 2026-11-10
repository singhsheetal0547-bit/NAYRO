package com.example.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.NayroApplication
import com.example.R
import com.example.data.local.entities.ConversationEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * System C — Android Foreground Service for NAYRO Background Agent Engine.
 * Operates as a legitimate Android Foreground Service (type: MICROPHONE).
 * Connects background local wake-word listening directly to AgentRuntime & VoiceManager.
 */
class NayroForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "nayro_agent_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.example.nayro.action.START"
        const val ACTION_STOP = "com.example.nayro.action.STOP"

        val status: StateFlow<ServiceStatus> = ServiceStateRepository.foregroundServiceStatus

        fun startService(context: Context) {
            ServiceStateRepository.updateForegroundStatus(ServiceLifecycleState.STARTING)
            val intent = Intent(context, NayroForegroundService::class.java).apply {
                action = ACTION_START
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: SecurityException) {
                ServiceStateRepository.updateForegroundStatus(
                    ServiceLifecycleState.FAILED,
                    "SecurityException: ${e.localizedMessage ?: "Foreground service permission not granted"}"
                )
            } catch (e: IllegalStateException) {
                ServiceStateRepository.updateForegroundStatus(
                    ServiceLifecycleState.FAILED,
                    "IllegalStateException: Foreground service start restricted in background."
                )
            } catch (e: Exception) {
                ServiceStateRepository.updateForegroundStatus(
                    ServiceLifecycleState.FAILED,
                    "Service start error: ${e.localizedMessage ?: e.javaClass.simpleName}"
                )
            }
        }

        fun stopService(context: Context) {
            ServiceStateRepository.updateForegroundStatus(ServiceLifecycleState.STOPPING)
            val intent = Intent(context, NayroForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.stopService(intent)
            } catch (e: Exception) {
                ServiceStateRepository.updateForegroundStatus(ServiceLifecycleState.STOPPED)
            }
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ServiceStateRepository.updateForegroundStatus(ServiceLifecycleState.STOPPING)
            stopWakeWordEngine()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            ServiceStateRepository.updateForegroundStatus(ServiceLifecycleState.STOPPED)
            return START_NOT_STICKY
        }

        try {
            val notification = createNotification("NAYRO Agent Active", "Babu background agent engine is ready. Tap to open.")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    0
                }
                startForeground(NOTIFICATION_ID, notification, fgsType)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            ServiceStateRepository.updateForegroundStatus(ServiceLifecycleState.RUNNING)

            // Activate local wake-word listening in background if permission is available
            setupBackgroundVoicePipeline()

        } catch (e: Exception) {
            ServiceStateRepository.updateForegroundStatus(
                ServiceLifecycleState.FAILED,
                "startForeground failed: ${e.localizedMessage ?: e.javaClass.simpleName}"
            )
            stopSelf()
            return START_NOT_STICKY
        }

        return START_STICKY
    }

    private fun setupBackgroundVoicePipeline() {
        try {
            val app = NayroApplication.instance
            if (app.permissionManager.isMicrophoneGranted()) {
                val config = app.voiceManager.configuration.value.copy(isWakeWordEnabled = true)
                app.voiceManager.updateConfiguration(config)

                app.voiceManager.attachServiceListener { text ->
                    if (text.isNotBlank()) {
                        serviceScope.launch {
                            app.repository.addConversationMessage(
                                ConversationEntity(sender = "user", content = text)
                            )
                            val response = app.agentRuntime.executeUserInstruction(text)
                            app.repository.addConversationMessage(
                                ConversationEntity(sender = "babu", content = response)
                            )
                            if (app.voiceManager.configuration.value.isTtsEnabled) {
                                app.voiceManager.speak(response)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopWakeWordEngine() {
        try {
            val app = NayroApplication.instance
            app.voiceManager.detachServiceListener()
            val config = app.voiceManager.configuration.value.copy(isWakeWordEnabled = false)
            app.voiceManager.updateConfiguration(config)
        } catch (e: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        stopWakeWordEngine()
        ServiceStateRepository.updateForegroundStatus(ServiceLifecycleState.STOPPED)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Ensure service status is accurately updated if process task is removed from Recents
        ServiceStateRepository.updateForegroundStatus(ServiceLifecycleState.STOPPED)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "NAYRO Agent Engine",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Displays the background lifecycle status of NAYRO assistant."
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(title: String, content: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, NayroForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Agent", stopPendingIntent)
            .build()
    }
}
