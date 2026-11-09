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
import com.example.R
import kotlinx.coroutines.flow.StateFlow

/**
 * System C — Android Foreground Service for NAYRO Background Agent Engine.
 * Implements real Android service lifecycle tracking with STARTING, RUNNING, STOPPING, STOPPED, FAILED.
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
                    "SecurityException: ${e.localizedMessage ?: "Permission not granted"}"
                )
            } catch (e: IllegalStateException) {
                ServiceStateRepository.updateForegroundStatus(
                    ServiceLifecycleState.FAILED,
                    "IllegalStateException: Foreground service start not allowed in background."
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

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ServiceStateRepository.updateForegroundStatus(ServiceLifecycleState.STOPPING)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            ServiceStateRepository.updateForegroundStatus(ServiceLifecycleState.STOPPED)
            return START_NOT_STICKY
        }

        try {
            val notification = createNotification("NAYRO Agent Active", "Babu background engine is ready. Tap to open.")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            ServiceStateRepository.updateForegroundStatus(ServiceLifecycleState.RUNNING)
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

    override fun onDestroy() {
        super.onDestroy()
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
