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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * System C — Android Foreground Service for NAYRO Background Resident Agent Engine (Section 4).
 * Operates as a legitimate Android Foreground Service (type: MICROPHONE).
 * Connects background local wake-word listening, authorized notification triggers,
 * and temporary Pause / Resume controls directly to AgentRuntime & VoiceManager.
 */
class NayroForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "nayro_agent_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.example.nayro.action.START"
        const val ACTION_STOP = "com.example.nayro.action.STOP"
        const val ACTION_PAUSE = "com.example.nayro.action.PAUSE"
        const val ACTION_RESUME = "com.example.nayro.action.RESUME"
        const val ACTION_EXECUTE_BACKGROUND_TASK = "com.example.nayro.action.EXECUTE_BACKGROUND_TASK"

        const val EXTRA_PAUSE_DURATION_MINUTES = "com.example.nayro.extra.PAUSE_DURATION_MINUTES"
        const val EXTRA_PAUSE_REASON = "com.example.nayro.extra.PAUSE_REASON"
        const val EXTRA_USER_INSTRUCTION = "com.example.nayro.extra.USER_INSTRUCTION"

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

        fun pauseService(
            context: Context?,
            durationMinutes: Int? = null,
            reason: String = "Paused by user"
        ) {
            ServiceStateRepository.pauseBackgroundAgent(durationMinutes, reason)
            if (context == null) return
            val intent = Intent(context, NayroForegroundService::class.java).apply {
                action = ACTION_PAUSE
                if (durationMinutes != null && durationMinutes > 0) {
                    putExtra(EXTRA_PAUSE_DURATION_MINUTES, durationMinutes)
                }
                putExtra(EXTRA_PAUSE_REASON, reason)
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {
                // In headless tests or when service is not yet started, state repository already reflects PAUSED
            }
        }

        fun resumeService(context: Context?) {
            ServiceStateRepository.resumeBackgroundAgent()
            if (context == null) return
            val intent = Intent(context, NayroForegroundService::class.java).apply {
                action = ACTION_RESUME
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
                // State repository already updated
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

        fun enqueueBackgroundTask(context: Context?, userInstruction: String) {
            val clean = userInstruction.trim()
            if (clean.isEmpty()) return
            ServiceStateRepository.appendActivityLog("Background task queued: \"$clean\"")
            if (context == null) return
            val intent = Intent(context, NayroForegroundService::class.java).apply {
                action = ACTION_EXECUTE_BACKGROUND_TASK
                putExtra(EXTRA_USER_INSTRUCTION, clean)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                ServiceStateRepository.appendActivityLog("Background task start failed: ${e.localizedMessage ?: e.javaClass.simpleName}")
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pauseTimerJob: Job? = null
    private var isScreenReceiverRegistered = false
    @Volatile
    private var isRunningInForeground = false

    private val screenAndUnlockReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                val app = NayroApplication.instance
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        app.agentRuntime.operatingStateManager.refreshDeviceOperatingState(
                            isScreenInteractive = false,
                            isKeyguardLocked = true
                        )
                        if (isRunningInForeground && !ServiceStateRepository.isBackgroundAgentPaused.value) {
                            ServiceStateRepository.updateForegroundStatus(ServiceLifecycleState.LOCKED)
                        }
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        app.agentRuntime.operatingStateManager.refreshDeviceOperatingState()
                    }
                    Intent.ACTION_USER_PRESENT -> {
                        if (isRunningInForeground && !ServiceStateRepository.isBackgroundAgentPaused.value) {
                            ServiceStateRepository.updateForegroundStatus(ServiceLifecycleState.RECOVERING)
                        }
                        serviceScope.launch {
                            app.agentRuntime.onDeviceUnlocked()
                            if (isRunningInForeground && !ServiceStateRepository.isBackgroundAgentPaused.value) {
                                ServiceStateRepository.updateForegroundStatus(ServiceLifecycleState.RUNNING)
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun resolveAssistantName(): String {
        return try {
            NayroApplication.instance.permissionSetupManager.getSavedAssistantName()
        } catch (_: Exception) {
            "Babu"
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        try {
            val filter = android.content.IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            registerReceiver(screenAndUnlockReceiver, filter)
            isScreenReceiverRegistered = true
        } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                isRunningInForeground = false
                pauseTimerJob?.cancel()
                ServiceStateRepository.updateForegroundStatus(ServiceLifecycleState.STOPPING)
                stopWakeWordEngine()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                ServiceStateRepository.updateForegroundStatus(ServiceLifecycleState.STOPPED)
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                val durationMins = intent.getIntExtra(EXTRA_PAUSE_DURATION_MINUTES, -1).takeIf { it > 0 }
                val reason = intent.getStringExtra(EXTRA_PAUSE_REASON) ?: "Paused by user"
                handlePauseCommand(durationMins, reason)
                return START_STICKY
            }
            ACTION_RESUME -> {
                handleResumeCommand()
                return START_STICKY
            }
            ACTION_EXECUTE_BACKGROUND_TASK -> {
                val instruction = intent.getStringExtra(EXTRA_USER_INSTRUCTION)?.trim().orEmpty()
                if (instruction.isNotEmpty() && !ServiceStateRepository.isBackgroundAgentPaused.value) {
                    serviceScope.launch {
                        try {
                            val app = NayroApplication.instance
                            app.agentRuntime.recordHeartbeat()
                            ServiceStateRepository.updateForegroundStatus(ServiceLifecycleState.PROCESSING)
                            val response = app.agentRuntime.executeUserInstruction(instruction)
                            app.repository.addConversationMessage(
                                ConversationEntity(sender = "babu", content = response)
                            )
                            if (isRunningInForeground && !ServiceStateRepository.isBackgroundAgentPaused.value) {
                                ServiceStateRepository.updateForegroundStatus(ServiceLifecycleState.RUNNING)
                            }
                        } catch (_: Exception) {}
                    }
                }
                return START_STICKY
            }
        }

        try {
            pauseTimerJob?.cancel()
            val personaName = resolveAssistantName()
            val notification = createNotification(
                title = "NAYRO Agent Active ($personaName)",
                content = "$personaName background agent engine is ready. Tap to open.",
                isPaused = false
            )

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

            isRunningInForeground = true
            ServiceStateRepository.updateForegroundStatus(ServiceLifecycleState.RUNNING)

            // Activate local wake-word listening in background if permission is available
            setupBackgroundVoicePipeline()

        } catch (e: Exception) {
            isRunningInForeground = false
            ServiceStateRepository.updateForegroundStatus(
                ServiceLifecycleState.FAILED,
                "startForeground failed: ${e.localizedMessage ?: e.javaClass.simpleName}"
            )
            stopSelf()
            return START_NOT_STICKY
        }

        return START_STICKY
    }

    private fun handlePauseCommand(durationMinutes: Int?, reason: String) {
        pauseTimerJob?.cancel()
        stopWakeWordEngine()
        try {
            val app = NayroApplication.instance
            app.agentRuntime.pauseCurrentTask()
            app.automationEngine.pauseAllAutomations()
        } catch (_: Exception) {}

        ServiceStateRepository.pauseBackgroundAgent(durationMinutes, reason)

        val pauseSubtitle = if (durationMinutes != null && durationMinutes > 0) {
            "Paused for ${durationMinutes}m ($reason). Tap Resume to reactivate."
        } else {
            "Paused until manually resumed ($reason)."
        }

        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.notify(
                NOTIFICATION_ID,
                createNotification(
                    title = "NAYRO Background Agent Paused",
                    content = pauseSubtitle,
                    isPaused = true
                )
            )
        } catch (_: Exception) {}

        if (durationMinutes != null && durationMinutes > 0) {
            pauseTimerJob = serviceScope.launch {
                delay(durationMinutes * 60_000L)
                handleResumeCommand()
            }
        }
    }

    private fun handleResumeCommand() {
        pauseTimerJob?.cancel()
        ServiceStateRepository.resumeBackgroundAgent()
        try {
            val app = NayroApplication.instance
            app.automationEngine.resumeAllAutomations()
            app.agentRuntime.resumeCurrentTask()
            app.agentRuntime.recordHeartbeat()
        } catch (_: Exception) {}

        setupBackgroundVoicePipeline()

        try {
            val personaName = resolveAssistantName()
            val nm = getSystemService(NotificationManager::class.java)
            nm?.notify(
                NOTIFICATION_ID,
                createNotification(
                    title = "NAYRO Agent Active ($personaName)",
                    content = "$personaName background agent resumed and listening locally.",
                    isPaused = false
                )
            )
        } catch (_: Exception) {}
    }

    private fun setupBackgroundVoicePipeline() {
        try {
            val app = NayroApplication.instance
            val personaName = resolveAssistantName()
            app.agentRuntime.setAssistantIdentity(personaName)
            app.voiceManager.updateAssistantIdentity(personaName)
            app.agentRuntime.recordHeartbeat()
            app.voiceManager.onVoiceExitCommand = {
                app.agentRuntime.cancelCurrentTask()
            }
            if (!app.permissionManager.isMicrophoneGranted()) {
                ServiceStateRepository.appendActivityLog("Background voice standby waiting for RECORD_AUDIO permission.")
                return
            }
            if (!ServiceStateRepository.isBackgroundAgentPaused.value) {
                val config = app.voiceManager.configuration.value.copy(isWakeWordEnabled = true)
                app.voiceManager.updateConfiguration(config)

                app.voiceManager.attachServiceListener { text ->
                    if (text.isNotBlank() && !ServiceStateRepository.isBackgroundAgentPaused.value) {
                        if (app.voiceManager.wakeWordEngine.isVoiceExitCommand(text)) {
                            app.agentRuntime.cancelCurrentTask()
                            return@attachServiceListener
                        }
                        serviceScope.launch {
                            app.agentRuntime.recordHeartbeat()
                            ServiceStateRepository.updateForegroundStatus(ServiceLifecycleState.PROCESSING)
                            ServiceStateRepository.appendActivityLog("Voice command received: \"$text\"")
                            app.repository.addConversationMessage(
                                ConversationEntity(sender = "user", content = text)
                            )
                            val response = app.agentRuntime.executeUserInstruction(text)
                            app.repository.addConversationMessage(
                                ConversationEntity(sender = "babu", content = response)
                            )
                            if (isRunningInForeground && !ServiceStateRepository.isBackgroundAgentPaused.value) {
                                ServiceStateRepository.updateForegroundStatus(ServiceLifecycleState.RUNNING)
                            }
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
        isRunningInForeground = false
        if (isScreenReceiverRegistered) {
            try {
                unregisterReceiver(screenAndUnlockReceiver)
            } catch (_: Exception) {}
            isScreenReceiverRegistered = false
        }
        super.onDestroy()
        pauseTimerJob?.cancel()
        stopWakeWordEngine()
        ServiceStateRepository.updateForegroundStatus(ServiceLifecycleState.STOPPED)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        try {
            super.onTaskRemoved(rootIntent)
        } catch (_: Exception) {}
        // Section 12 Fix: NayroForegroundService is a START_STICKY resident foreground service.
        // Swiping the Activity from Recents must NOT mark the running foreground service as STOPPED!
        if (isRunningInForeground || ServiceStateRepository.foregroundServiceStatus.value.state.isActiveOrRunning) {
            ServiceStateRepository.appendActivityLog("UI task removed from Recents; resident foreground service remains RUNNING.")
        } else if (ServiceStateRepository.foregroundServiceStatus.value.state != ServiceLifecycleState.PAUSED) {
            ServiceStateRepository.updateForegroundStatus(ServiceLifecycleState.STOPPED)
        }
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

    private fun createNotification(title: String, content: String, isPaused: Boolean): Notification {
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

        val togglePauseIntent = Intent(this, NayroForegroundService::class.java).apply {
            action = if (isPaused) ACTION_RESUME else ACTION_PAUSE
        }
        val togglePausePendingIntent = PendingIntent.getService(
            this,
            2,
            togglePauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                if (isPaused) "Resume Agent" else "Pause Agent",
                togglePausePendingIntent
            )
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Agent", stopPendingIntent)
            .build()
    }
}
