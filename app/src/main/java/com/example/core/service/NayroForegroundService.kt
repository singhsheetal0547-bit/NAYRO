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
import kotlinx.coroutines.flow.firstOrNull
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

        const val MAX_PIPELINE_RECOVERY_RETRIES = 3
        const val RECOVERY_COOLDOWN_MS = 60_000L
        val RECOVERY_BACKOFF_MS = longArrayOf(2_000L, 5_000L, 12_000L)

        /**
         * Pure, deterministic P0-3 pipeline verification evaluator usable both inside
         * [NayroForegroundService] and in automated JVM unit tests.
         *
         * Enforces:
         * - [BackgroundVoicePipelineState.BACKGROUND_VOICE_READY] ONLY when service foreground binding,
         *   microphone permission + initialization, valid wake templates, AND active AudioRecord capture
         *   are ALL verified.
         * - Explicit [BackgroundVoicePipelineState.ERROR] on missing permission or fatal startup exception.
         * - Explicit [BackgroundVoicePipelineState.DEGRADED] when service runs but wake model/audio capture failed.
         */
        fun evaluatePipelineReadiness(
            isServiceInForeground: Boolean,
            hasMicPermission: Boolean,
            isWakeTemplatesReady: Boolean,
            isMicInitialized: Boolean,
            isAudioCaptureActive: Boolean,
            isScreenOff: Boolean = false,
            retryCount: Int = 0,
            failureReason: String? = null
        ): BackgroundVoicePipelineStatus {
            return when {
                !isServiceInForeground -> BackgroundVoicePipelineStatus(
                    state = if (failureReason != null) BackgroundVoicePipelineState.ERROR else BackgroundVoicePipelineState.STOPPED,
                    detail = failureReason ?: "Foreground service is not running.",
                    isMicrophoneVerified = false,
                    isWakeEngineVerified = false,
                    isAudioCaptureVerified = false,
                    retryCount = retryCount,
                    maxRetries = MAX_PIPELINE_RECOVERY_RETRIES,
                    isScreenOff = isScreenOff
                )
                !hasMicPermission -> BackgroundVoicePipelineStatus(
                    state = BackgroundVoicePipelineState.ERROR,
                    detail = failureReason ?: "Microphone permission (RECORD_AUDIO) is missing or revoked.",
                    isMicrophoneVerified = false,
                    isWakeEngineVerified = isWakeTemplatesReady,
                    isAudioCaptureVerified = false,
                    retryCount = retryCount,
                    maxRetries = MAX_PIPELINE_RECOVERY_RETRIES,
                    isScreenOff = isScreenOff
                )
                !isWakeTemplatesReady -> BackgroundVoicePipelineStatus(
                    state = BackgroundVoicePipelineState.DEGRADED,
                    detail = failureReason ?: "Foreground service running, but wake-word acoustic templates are missing or invalid.",
                    isMicrophoneVerified = isMicInitialized,
                    isWakeEngineVerified = false,
                    isAudioCaptureVerified = false,
                    retryCount = retryCount,
                    maxRetries = MAX_PIPELINE_RECOVERY_RETRIES,
                    isScreenOff = isScreenOff
                )
                !isMicInitialized || !isAudioCaptureActive -> BackgroundVoicePipelineStatus(
                    state = BackgroundVoicePipelineState.DEGRADED,
                    detail = failureReason ?: "Foreground service running, but AudioRecord microphone capture is not active.",
                    isMicrophoneVerified = isMicInitialized,
                    isWakeEngineVerified = true,
                    isAudioCaptureVerified = false,
                    retryCount = retryCount,
                    maxRetries = MAX_PIPELINE_RECOVERY_RETRIES,
                    isScreenOff = isScreenOff
                )
                else -> BackgroundVoicePipelineStatus(
                    state = BackgroundVoicePipelineState.BACKGROUND_VOICE_READY,
                    detail = if (isScreenOff) {
                        "Local wake-word capture active while screen is off (subject to Android/OEM Doze; not hardware DSP)."
                    } else {
                        "Background Voice Ready — local on-device wake detection active."
                    },
                    isMicrophoneVerified = true,
                    isWakeEngineVerified = true,
                    isAudioCaptureVerified = true,
                    retryCount = 0,
                    maxRetries = MAX_PIPELINE_RECOVERY_RETRIES,
                    isScreenOff = isScreenOff
                )
            }
        }

        /**
         * Computes bounded recovery backoff delay or returns `null` when [retryAttempt] exceeds
         * [MAX_PIPELINE_RECOVERY_RETRIES], preventing infinite restart loops (P0-3).
         */
        fun computeBoundedRecoveryDelayMs(retryAttempt: Int): Long? {
            if (retryAttempt <= 0 || retryAttempt > MAX_PIPELINE_RECOVERY_RETRIES) return null
            return RECOVERY_BACKOFF_MS[(retryAttempt - 1).coerceIn(0, RECOVERY_BACKOFF_MS.lastIndex)]
        }

        fun startService(context: Context) {
            ServiceStateRepository.updateForegroundStatus(ServiceLifecycleState.STARTING)
            ServiceStateRepository.updateBackgroundVoicePipeline(
                state = BackgroundVoicePipelineState.SERVICE_STARTING,
                detail = "Requesting Android foreground microphone service start...",
                isMicrophoneVerified = false,
                isWakeEngineVerified = false,
                isAudioCaptureVerified = false,
                retryCount = 0
            )
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
                val msg = "SecurityException: ${e.localizedMessage ?: "Foreground microphone service permission not granted"}"
                ServiceStateRepository.updateForegroundStatus(ServiceLifecycleState.PERMISSION_DENIED, msg)
            } catch (e: IllegalStateException) {
                val isFgsBlocked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    e is android.app.ForegroundServiceStartNotAllowedException
                val msg = if (isFgsBlocked) {
                    "ForegroundServiceStartNotAllowedException: Android blocked starting microphone FGS from background. Open NAYRO to start."
                } else {
                    "IllegalStateException: Foreground service start restricted in background."
                }
                ServiceStateRepository.updateForegroundStatus(ServiceLifecycleState.FAILED, msg)
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
            // Context.stopService() does not deliver ACTION_STOP to onStartCommand. Persist the
            // explicit user OFF choice here before Android invokes onDestroy(), which otherwise
            // must preserve the preference for START_STICKY service recreation.
            try {
                val app = NayroApplication.instance
                app.voiceManager.updateConfiguration(
                    app.voiceManager.configuration.value.copy(isWakeWordEnabled = false)
                )
                app.assistantIdentityStore.updateVoiceAndLanguage(isWakeWordEnabled = false)
                app.backgroundVoiceController.refreshState()
            } catch (_: Exception) {}
            try {
                context.stopService(Intent(context, NayroForegroundService::class.java))
                ServiceStateRepository.updateForegroundStatus(ServiceLifecycleState.STOPPED)
            } catch (_: Exception) {
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
    private var recoveryRetryJob: Job? = null
    private var isScreenReceiverRegistered = false
    @Volatile
    private var isRunningInForeground = false
    @Volatile
    private var isScreenCurrentlyOff = false
    @Volatile
    private var recoveryRetryCount = 0

    private val screenAndUnlockReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                val app = NayroApplication.instance
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        isScreenCurrentlyOff = true
                        app.agentRuntime.operatingStateManager.refreshDeviceOperatingState(
                            isScreenInteractive = false,
                            isKeyguardLocked = true
                        )
                        if (isRunningInForeground && !ServiceStateRepository.isBackgroundAgentPaused.value) {
                            ServiceStateRepository.updateForegroundStatus(ServiceLifecycleState.LOCKED)
                            refreshVerifiedPipelineStatus(app, reasonOverride = null)
                        }
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        isScreenCurrentlyOff = false
                        app.agentRuntime.operatingStateManager.refreshDeviceOperatingState()
                        if (isRunningInForeground && !ServiceStateRepository.isBackgroundAgentPaused.value) {
                            refreshVerifiedPipelineStatus(app, reasonOverride = null)
                        }
                    }
                    Intent.ACTION_USER_PRESENT -> {
                        isScreenCurrentlyOff = false
                        if (isRunningInForeground && !ServiceStateRepository.isBackgroundAgentPaused.value) {
                            ServiceStateRepository.updateForegroundStatus(ServiceLifecycleState.RECOVERING)
                        }
                        serviceScope.launch {
                            app.agentRuntime.onDeviceUnlocked()
                            if (isRunningInForeground && !ServiceStateRepository.isBackgroundAgentPaused.value) {
                                ServiceStateRepository.updateForegroundStatus(ServiceLifecycleState.RUNNING)
                                // If capture degraded during Doze/screen-off, attempt clean bounded recovery on unlock
                                val wakeActive = app.voiceManager.wakeWordEngine.isAudioCaptureActive.value
                                if (!wakeActive && app.voiceManager.configuration.value.isWakeWordEnabled) {
                                    recoveryRetryCount = 0
                                    setupBackgroundVoicePipeline()
                                } else {
                                    refreshVerifiedPipelineStatus(app, reasonOverride = null)
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    data class BackgroundVoiceEligibility(
        val canStartMicrophoneFgs: Boolean,
        val batteryOptimizationExempt: Boolean,
        val reason: String
    )

    private fun resolveAssistantName(): String {
        com.example.core.identity.AssistantIdentityStore.sharedInstance?.getDisplayName()
            ?.takeIf { it.isNotBlank() }?.let { return it }
        return try {
            NayroApplication.instance.permissionSetupManager.getSavedAssistantName()
        } catch (_: Exception) {
            com.example.core.identity.AssistantIdentityProfile.DEFAULT_DISPLAY_NAME
        }
    }

    private fun hasRecordAudioPermission(): Boolean {
        return try {
            androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    private fun isBatteryOptimizationIgnored(): Boolean {
        return try {
            val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            pm?.isIgnoringBatteryOptimizations(packageName) == true
        } catch (_: Exception) {
            false
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
                recoveryRetryJob?.cancel()
                recoveryRetryCount = 0
                ServiceStateRepository.updateForegroundStatus(ServiceLifecycleState.STOPPING)
                try {
                    NayroApplication.instance.voiceManager.updateConfiguration(
                        NayroApplication.instance.voiceManager.configuration.value.copy(isWakeWordEnabled = false)
                    )
                } catch (_: Exception) {}
                stopWakeWordEngine()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                ServiceStateRepository.updateForegroundStatus(ServiceLifecycleState.STOPPED)
                try {
                    NayroApplication.instance.assistantIdentityStore.updateVoiceAndLanguage(isWakeWordEnabled = false)
                    NayroApplication.instance.backgroundVoiceController.refreshState()
                } catch (_: Exception) {}
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
                            val lastStoredTurn = app.repository.conversations.firstOrNull().orEmpty().lastOrNull()
                            if (!lastStoredTurn?.sender.equals("user", ignoreCase = true) ||
                                lastStoredTurn?.content?.trim() != instruction.trim()
                            ) {
                                app.repository.addConversationMessage(
                                    ConversationEntity(sender = "user", content = instruction)
                                )
                            }
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
            recoveryRetryJob?.cancel()
            recoveryRetryCount = 0
            val personaName = resolveAssistantName()
            val hasMicPerm = hasRecordAudioPermission()
            val batteryExempt = isBatteryOptimizationIgnored()

            ServiceStateRepository.updateBackgroundVoicePipeline(
                state = BackgroundVoicePipelineState.SERVICE_STARTING,
                detail = "Starting NAYRO foreground service...",
                isMicrophoneVerified = false,
                isWakeEngineVerified = false,
                isAudioCaptureVerified = false,
                retryCount = 0,
                isScreenOff = isScreenCurrentlyOff
            )

            // Android 14+ (API 34+) strictly enforces RECORD_AUDIO before starting a microphone-typed FGS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !hasMicPerm) {
                isRunningInForeground = false
                val msg = "Android 14+ requires RECORD_AUDIO permission before starting NAYRO's microphone foreground service."
                ServiceStateRepository.updateForegroundStatus(ServiceLifecycleState.PERMISSION_DENIED, msg)
                stopSelf()
                return START_NOT_STICKY
            }

            if (!batteryExempt) {
                ServiceStateRepository.appendActivityLog(
                    "Battery optimization is active for NAYRO; Android OEM power policies may throttle background listening."
                )
            }

            // Initial honest notification — never claims "Ready" until microphone & wake engine are verified
            val startingNotification = createNotification(
                title = "NAYRO Service Starting ($personaName)",
                content = "Initializing microphone and verifying local wake-word templates...",
                isPaused = false
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && hasMicPerm) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    0
                }
                startForeground(NOTIFICATION_ID, startingNotification, fgsType)
            } else {
                startForeground(NOTIFICATION_ID, startingNotification)
            }

            isRunningInForeground = true
            ServiceStateRepository.updateForegroundStatus(ServiceLifecycleState.RUNNING)
            ServiceStateRepository.updateBackgroundVoicePipeline(
                state = BackgroundVoicePipelineState.SERVICE_RUNNING,
                detail = "Foreground service bound; verifying microphone and local wake engine...",
                isMicrophoneVerified = false,
                isWakeEngineVerified = false,
                isAudioCaptureVerified = false,
                retryCount = 0,
                isScreenOff = isScreenCurrentlyOff
            )

            // Independently verify microphone permission, wake templates, and AudioRecord capture (P0-3)
            setupBackgroundVoicePipeline()

        } catch (se: SecurityException) {
            isRunningInForeground = false
            val msg = "Microphone foreground service blocked by Android SecurityException (missing RECORD_AUDIO or while-in-use restriction): ${se.localizedMessage ?: "denied"}"
            ServiceStateRepository.updateForegroundStatus(ServiceLifecycleState.PERMISSION_DENIED, msg)
            stopSelf()
            return START_NOT_STICKY
        } catch (e: Exception) {
            isRunningInForeground = false
            val isBgStartBlocked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e is android.app.ForegroundServiceStartNotAllowedException
            val msg = if (isBgStartBlocked) {
                "Android blocked background foreground-service start (ForegroundServiceStartNotAllowedException). Open NAYRO to reactivate voice standby."
            } else {
                "startForeground failed: ${e.localizedMessage ?: e.javaClass.simpleName}"
            }
            ServiceStateRepository.updateForegroundStatus(ServiceLifecycleState.FAILED, msg)
            stopSelf()
            return START_NOT_STICKY
        }

        // Note: START_STICKY requests restart if OS reclaims memory, but Android 12+/14+ OEM policies
        // may still restrict background microphone restarts without user foreground interaction.
        return START_STICKY
    }

    private var watchdogJob: Job? = null

    private fun startHealthWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = serviceScope.launch {
            while (isRunningInForeground) {
                delay(15_000L)
                if (!isRunningInForeground || ServiceStateRepository.isBackgroundAgentPaused.value) continue
                try {
                    val app = NayroApplication.instance
                    app.healthManager.evaluateHealth()

                    // Verify microphone permission has not been revoked while running in background
                    if (!hasRecordAudioPermission() || !app.permissionManager.isMicrophoneGranted()) {
                        app.voiceManager.onMicrophonePermissionRevoked()
                        handleRuntimePipelineDegradation(app, "RECORD_AUDIO permission revoked in background")
                        continue
                    }

                    // Skip recovery if user is actively speaking, thinking, TTS playing, enrolling, or on a phone call
                    val owner = app.voiceManager.audioSessionOwner.value
                    val vState = app.voiceManager.voiceState.value
                    val isBusyWithLegitSession = owner == com.example.core.voice.AudioSessionOwner.SPEECH_RECOGNIZER ||
                        owner == com.example.core.voice.AudioSessionOwner.TTS_PLAYBACK ||
                        owner == com.example.core.voice.AudioSessionOwner.ENROLLMENT_CAPTURE ||
                        owner == com.example.core.voice.AudioSessionOwner.PHONE_CALL_INTERRUPTION ||
                        vState == com.example.core.voice.VoiceState.LISTENING ||
                        vState == com.example.core.voice.VoiceState.FOLLOW_UP_LISTENING ||
                        vState == com.example.core.voice.VoiceState.TRANSCRIBING ||
                        vState == com.example.core.voice.VoiceState.THINKING ||
                        vState == com.example.core.voice.VoiceState.EXECUTING ||
                        vState == com.example.core.voice.VoiceState.SPEAKING ||
                        app.voiceManager.isPhoneCallActiveOrRinging()

                    if (isBusyWithLegitSession) {
                        continue
                    }

                    // If wake-word is enabled but capture silently stopped/degraded, trigger honest degradation & bounded recovery
                    val wakeEngine = app.voiceManager.wakeWordEngine
                    val captureAlive = wakeEngine.isAudioCaptureActive.value &&
                        wakeEngine.engineState.value == com.example.core.voice.engine.WakeEngineState.LISTENING_FOR_WAKE
                    if (app.voiceManager.configuration.value.isWakeWordEnabled && !captureAlive) {
                        val reason = wakeEngine.lastFailureReason.value
                            ?: "Watchdog detected background wake-word AudioRecord capture is inactive (state=${wakeEngine.engineState.value.name})."
                        handleRuntimePipelineDegradation(app, reason)
                    } else if (captureAlive) {
                        // Ensure pipeline state stays truthfully synced if previously degraded
                        if (ServiceStateRepository.backgroundVoicePipelineStatus.value.state != BackgroundVoicePipelineState.BACKGROUND_VOICE_READY) {
                            refreshVerifiedPipelineStatus(app, null)
                        }
                    }
                } catch (_: Throwable) {}
            }
        }
    }

    private fun handlePauseCommand(durationMinutes: Int?, reason: String) {
        pauseTimerJob?.cancel()
        recoveryRetryJob?.cancel()
        watchdogJob?.cancel()
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

        updateActiveNotification(
            title = "NAYRO Background Agent Paused",
            content = pauseSubtitle,
            isPaused = true
        )

        if (durationMinutes != null && durationMinutes > 0) {
            pauseTimerJob = serviceScope.launch {
                delay(durationMinutes * 60_000L)
                handleResumeCommand()
            }
        }
    }

    private fun handleResumeCommand() {
        pauseTimerJob?.cancel()
        recoveryRetryJob?.cancel()
        recoveryRetryCount = 0
        ServiceStateRepository.resumeBackgroundAgent()
        try {
            val app = NayroApplication.instance
            app.automationEngine.resumeAllAutomations()
            app.agentRuntime.resumeCurrentTask()
            app.agentRuntime.recordHeartbeat()
        } catch (_: Exception) {}

        setupBackgroundVoicePipeline()
    }

    /**
     * P0-3 staged background voice pipeline startup & independent verification:
     * `SERVICE_RUNNING` -> `MICROPHONE_INITIALIZED` -> `WAKE_ENGINE_READY` ->
     * `AUDIO_CAPTURE_ACTIVE` -> `BACKGROUND_VOICE_READY` (or honest `DEGRADED` / `ERROR`).
     */
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

            if (ServiceStateRepository.isBackgroundAgentPaused.value) {
                return
            }

            // Stage 1: Verify RECORD_AUDIO permission independently
            if (!hasRecordAudioPermission() || !app.permissionManager.isMicrophoneGranted()) {
                val msg = "Microphone permission (RECORD_AUDIO) is not granted. Background wake listening cannot start."
                ServiceStateRepository.updateBackgroundVoicePipeline(
                    state = BackgroundVoicePipelineState.ERROR,
                    detail = msg,
                    isMicrophoneVerified = false,
                    isWakeEngineVerified = false,
                    isAudioCaptureVerified = false,
                    retryCount = recoveryRetryCount,
                    isScreenOff = isScreenCurrentlyOff
                )
                updateActiveNotification(
                    title = "NAYRO Voice Error ($personaName)",
                    content = "Microphone permission required. Open NAYRO to grant access.",
                    isPaused = false
                )
                app.backgroundVoiceController.refreshState()
                return
            }

            // Stage 2: Load & validate acoustic templates independently
            val wakeEngine = app.voiceManager.wakeWordEngine
            if (!wakeEngine.hasUsableAcousticTemplates()) {
                wakeEngine.loadAcousticModel(allowEmbeddedFallbackInTests = false)
            }
            if (!wakeEngine.hasUsableAcousticTemplates()) {
                val reason = wakeEngine.lastFailureReason.value
                    ?: "No production-trusted wake model is active. Bundled MFCC references are test fixtures; record at least two real voice samples for a configured wake phrase in Settings."
                ServiceStateRepository.updateBackgroundVoicePipeline(
                    state = BackgroundVoicePipelineState.DEGRADED,
                    detail = reason,
                    isMicrophoneVerified = false,
                    isWakeEngineVerified = false,
                    isAudioCaptureVerified = false,
                    retryCount = recoveryRetryCount,
                    isScreenOff = isScreenCurrentlyOff
                )
                updateActiveNotification(
                    title = "NAYRO Degraded ($personaName)",
                    content = "Wake templates not ready. Tap to enroll or use manual/floating voice.",
                    isPaused = false
                )
                app.backgroundVoiceController.refreshState()
                return
            }

            ServiceStateRepository.updateBackgroundVoicePipeline(
                state = BackgroundVoicePipelineState.WAKE_ENGINE_READY,
                detail = "Validated ${wakeEngine.getLoadedTemplateCount()} local wake templates; initializing AudioRecord...",
                isMicrophoneVerified = false,
                isWakeEngineVerified = true,
                isAudioCaptureVerified = false,
                retryCount = recoveryRetryCount,
                isScreenOff = isScreenCurrentlyOff
            )

            // Wire runtime capture error hook so AudioRecord drops immediately downgrade state and trigger bounded recovery
            wakeEngine.onCaptureError = { captureError ->
                serviceScope.launch {
                    handleRuntimePipelineDegradation(app, captureError)
                }
            }

            // Stage 3: Activate wake-word via single VoiceManager ownership coordinator and verify real AudioRecord capture
            val config = app.voiceManager.configuration.value.copy(isWakeWordEnabled = true)
            app.voiceManager.updateConfiguration(config)

            val micInitialized = wakeEngine.isMicrophoneInitialized.value
            val captureActive = wakeEngine.isAudioCaptureActive.value &&
                wakeEngine.engineState.value == com.example.core.voice.engine.WakeEngineState.LISTENING_FOR_WAKE

            if (micInitialized) {
                ServiceStateRepository.updateBackgroundVoicePipeline(
                    state = BackgroundVoicePipelineState.MICROPHONE_INITIALIZED,
                    detail = "AudioRecord initialized at 16kHz PCM-16.",
                    isMicrophoneVerified = true,
                    isWakeEngineVerified = true,
                    isAudioCaptureVerified = captureActive,
                    retryCount = recoveryRetryCount,
                    isScreenOff = isScreenCurrentlyOff
                )
            }

            if (captureActive) {
                ServiceStateRepository.updateBackgroundVoicePipeline(
                    state = BackgroundVoicePipelineState.AUDIO_CAPTURE_ACTIVE,
                    detail = "Local AudioRecord capture verified active.",
                    isMicrophoneVerified = true,
                    isWakeEngineVerified = true,
                    isAudioCaptureVerified = true,
                    retryCount = 0,
                    isScreenOff = isScreenCurrentlyOff
                )
                recoveryRetryCount = 0
                recoveryRetryJob?.cancel()
                recoveryRetryJob = null
                val phrases = wakeEngine.getConfiguredWakePhrases().take(2).joinToString(" / ")
                ServiceStateRepository.updateBackgroundVoicePipeline(
                    state = BackgroundVoicePipelineState.BACKGROUND_VOICE_READY,
                    detail = "Background Voice Ready — listening locally for $phrases.",
                    isMicrophoneVerified = true,
                    isWakeEngineVerified = true,
                    isAudioCaptureVerified = true,
                    retryCount = 0,
                    isScreenOff = isScreenCurrentlyOff
                )
                updateActiveNotification(
                    title = "NAYRO Voice Ready ($personaName)",
                    content = "Listening locally for $phrases. Tap to open.",
                    isPaused = false
                )
            } else {
                val failureReason = wakeEngine.lastFailureReason.value
                    ?: wakeEngine.statusMessage.value
                handleRuntimePipelineDegradation(app, failureReason)
            }

            app.voiceManager.attachServiceListener { rawText ->
                val normalizedCommand = app.voiceManager.normalizeVoiceInvocationCommand(rawText)
                if (normalizedCommand.isNotBlank() && !ServiceStateRepository.isBackgroundAgentPaused.value) {
                    if (app.voiceManager.wakeWordEngine.isVoiceExitCommand(normalizedCommand, personaName) ||
                        app.voiceManager.isConversationExitPhrase(normalizedCommand)
                    ) {
                        app.agentRuntime.cancelCurrentTask()
                        app.voiceManager.endConversationSession(returnToStandby = true)
                        return@attachServiceListener
                    }
                    serviceScope.launch {
                        var response = ""
                        try {
                            app.agentRuntime.recordHeartbeat()
                            ServiceStateRepository.updateForegroundStatus(ServiceLifecycleState.PROCESSING)
                            ServiceStateRepository.appendActivityLog("Background voice command: \"$normalizedCommand\"")
                            app.repository.addConversationMessage(
                                ConversationEntity(sender = "user", content = normalizedCommand)
                            )
                            response = app.agentRuntime.executeUserInstruction(normalizedCommand)
                            app.repository.addConversationMessage(
                                ConversationEntity(sender = "assistant", content = response)
                            )
                        } catch (e: Exception) {
                            response = "Background execution failed: ${e.localizedMessage ?: e.javaClass.simpleName}"
                            ServiceStateRepository.appendActivityLog(response)
                        } finally {
                            if (isRunningInForeground && !ServiceStateRepository.isBackgroundAgentPaused.value) {
                                ServiceStateRepository.updateForegroundStatus(ServiceLifecycleState.RUNNING)
                            }
                            // Always complete the voice turn so microphone ownership returns to follow-up or wake standby
                            app.voiceManager.completeCommandTurn(
                                responseText = response,
                                shouldSpeak = app.voiceManager.configuration.value.isTtsEnabled,
                                expectFollowUp = app.voiceManager.configuration.value.isContinuousConversationEnabled
                            )
                        }
                    }
                }
            }
            startHealthWatchdog()
            app.healthManager.evaluateHealth()
            app.backgroundVoiceController.refreshState()
        } catch (e: Exception) {
            val msg = "Background voice pipeline setup failed: ${e.localizedMessage ?: e.javaClass.simpleName}"
            ServiceStateRepository.updateBackgroundVoicePipeline(
                state = BackgroundVoicePipelineState.ERROR,
                detail = msg,
                isMicrophoneVerified = false,
                isWakeEngineVerified = false,
                isAudioCaptureVerified = false,
                retryCount = recoveryRetryCount,
                isScreenOff = isScreenCurrentlyOff
            )
            updateActiveNotification(
                title = "NAYRO Voice Error",
                content = msg,
                isPaused = false
            )
        }
    }

    private fun refreshVerifiedPipelineStatus(app: NayroApplication, reasonOverride: String?) {
        val wakeEngine = app.voiceManager.wakeWordEngine
        val evaluated = evaluatePipelineReadiness(
            isServiceInForeground = isRunningInForeground,
            hasMicPermission = hasRecordAudioPermission() && app.permissionManager.isMicrophoneGranted(),
            isWakeTemplatesReady = wakeEngine.hasUsableAcousticTemplates(),
            isMicInitialized = wakeEngine.isMicrophoneInitialized.value,
            isAudioCaptureActive = wakeEngine.isAudioCaptureActive.value &&
                wakeEngine.engineState.value == com.example.core.voice.engine.WakeEngineState.LISTENING_FOR_WAKE,
            isScreenOff = isScreenCurrentlyOff,
            retryCount = recoveryRetryCount,
            failureReason = reasonOverride
        )
        ServiceStateRepository.updateBackgroundVoicePipeline(
            state = evaluated.state,
            detail = evaluated.detail,
            isMicrophoneVerified = evaluated.isMicrophoneVerified,
            isWakeEngineVerified = evaluated.isWakeEngineVerified,
            isAudioCaptureVerified = evaluated.isAudioCaptureVerified,
            retryCount = evaluated.retryCount,
            isScreenOff = evaluated.isScreenOff
        )
    }

    private fun handleRuntimePipelineDegradation(app: NayroApplication, reason: String) {
        val personaName = resolveAssistantName()
        if (!hasRecordAudioPermission() || !app.permissionManager.isMicrophoneGranted()) {
            recoveryRetryJob?.cancel()
            ServiceStateRepository.updateBackgroundVoicePipeline(
                state = BackgroundVoicePipelineState.ERROR,
                detail = "Microphone permission revoked: $reason",
                isMicrophoneVerified = false,
                isWakeEngineVerified = app.voiceManager.wakeWordEngine.hasUsableAcousticTemplates(),
                isAudioCaptureVerified = false,
                retryCount = recoveryRetryCount,
                isScreenOff = isScreenCurrentlyOff
            )
            updateActiveNotification(
                title = "NAYRO Voice Error ($personaName)",
                content = "Microphone permission missing. Tap to re-enable.",
                isPaused = false
            )
            return
        }

        val nextAttempt = recoveryRetryCount + 1
        val backoffMs = computeBoundedRecoveryDelayMs(nextAttempt)
        if (backoffMs != null && isRunningInForeground && !ServiceStateRepository.isBackgroundAgentPaused.value) {
            recoveryRetryCount = nextAttempt
            ServiceStateRepository.updateBackgroundVoicePipeline(
                state = BackgroundVoicePipelineState.DEGRADED,
                detail = "$reason (Retry $nextAttempt/$MAX_PIPELINE_RECOVERY_RETRIES scheduled in ${backoffMs / 1000}s)",
                isMicrophoneVerified = false,
                isWakeEngineVerified = app.voiceManager.wakeWordEngine.hasUsableAcousticTemplates(),
                isAudioCaptureVerified = false,
                retryCount = nextAttempt,
                isScreenOff = isScreenCurrentlyOff
            )
            updateActiveNotification(
                title = "NAYRO Voice Degraded ($personaName)",
                content = "Audio capture interrupted; retrying ($nextAttempt/$MAX_PIPELINE_RECOVERY_RETRIES)...",
                isPaused = false
            )
            recoveryRetryJob?.cancel()
            recoveryRetryJob = serviceScope.launch {
                delay(backoffMs)
                if (isRunningInForeground && !ServiceStateRepository.isBackgroundAgentPaused.value) {
                    setupBackgroundVoicePipeline()
                }
            }
        } else {
            ServiceStateRepository.updateBackgroundVoicePipeline(
                state = BackgroundVoicePipelineState.ERROR,
                detail = "Background voice failed after $MAX_PIPELINE_RECOVERY_RETRIES recovery attempts: $reason. A controlled retry will be attempted after a cooldown.",
                isMicrophoneVerified = false,
                isWakeEngineVerified = app.voiceManager.wakeWordEngine.hasUsableAcousticTemplates(),
                isAudioCaptureVerified = false,
                retryCount = recoveryRetryCount,
                isScreenOff = isScreenCurrentlyOff
            )
            updateActiveNotification(
                title = "NAYRO Voice Error ($personaName)",
                content = "Microphone capture unavailable. Retrying after a 60-second recovery cooldown.",
                isPaused = false
            )
            // Avoid a hot restart loop when the Audio HAL or OEM service is unavailable.
            if (recoveryRetryJob?.isActive != true) {
                recoveryRetryJob = serviceScope.launch {
                    delay(RECOVERY_COOLDOWN_MS)
                    if (isRunningInForeground &&
                        !ServiceStateRepository.isBackgroundAgentPaused.value &&
                        hasRecordAudioPermission()
                    ) {
                        recoveryRetryCount = 0
                        setupBackgroundVoicePipeline()
                    }
                }
            }
        }
        app.backgroundVoiceController.refreshState()
    }

    private fun updateActiveNotification(title: String, content: String, isPaused: Boolean) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.notify(NOTIFICATION_ID, createNotification(title, content, isPaused))
        } catch (_: Exception) {}
    }

    private fun stopWakeWordEngine() {
        try {
            val app = NayroApplication.instance
            app.voiceManager.wakeWordEngine.onCaptureError = null
            app.voiceManager.detachServiceListener()
            // Release audio resources only. Android may destroy/recreate a START_STICKY service;
            // that lifecycle event must not silently change the user's persisted ON preference.
            app.voiceManager.endConversationSession(returnToStandby = false)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        isRunningInForeground = false
        recoveryRetryJob?.cancel()
        watchdogJob?.cancel()
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
