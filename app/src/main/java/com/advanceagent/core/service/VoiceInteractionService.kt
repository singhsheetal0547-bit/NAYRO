package com.advanceagent.core.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.advanceagent.core.AgentApplication
import com.advanceagent.core.R
import com.advanceagent.core.audio.AudioStreamManager
import com.advanceagent.core.audio.SoundEffectsManager
import com.advanceagent.core.data.SecurePreferences
import android.app.Activity
import androidx.core.app.ActivityCompat
import com.advanceagent.core.ui.MainActivity
import com.advanceagent.core.wakeword.OfficialSherpaOnnxJniBridge
import com.advanceagent.core.wakeword.SherpaOnnxBridge
import com.advanceagent.core.wakeword.SherpaOnnxJniBridge
import com.advanceagent.core.wakeword.SherpaWakeWordEngine
import com.example.NayroApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Foreground [Service] (`foregroundServiceType="microphone"`) dedicated to managing
 * continuous 16kHz PCM-16 microphone input for real-time offline Sherpa-ONNX wake-word
 * detection via [SherpaOnnxJniBridge] and [SherpaWakeWordEngine].
 *
 * Ensures proper Android 10–16 foreground service lifecycle management (foreground
 * promotion, partial WakeLock, pause/resume for ASR or phone calls, task removal recovery,
 * and deterministic resource teardown) to prevent system-killed background tasks.
 */
class VoiceInteractionService : Service() {

    enum class LifecycleState {
        IDLE,
        STARTING_FOREGROUND,
        FOREGROUND_ACTIVE,
        PAUSED,
        ERROR,
        STOPPED
    }

    companion object {
        private const val TAG = "VoiceInteractionSvc"
        const val CHANNEL_ID = "nayro_voice_interaction_channel"
        const val NOTIFICATION_ID = 1004
        const val REQUEST_CODE_RECORD_AUDIO = 4104
        const val EXTRA_REQUEST_RECORD_AUDIO = "com.advanceagent.core.EXTRA_REQUEST_RECORD_AUDIO"

        const val ACTION_START_FOREGROUND_MIC = "com.advanceagent.core.ACTION_START_FOREGROUND_MIC"
        const val ACTION_PAUSE_MIC = "com.advanceagent.core.ACTION_PAUSE_MIC"
        const val ACTION_RESUME_MIC = "com.advanceagent.core.ACTION_RESUME_MIC"
        const val ACTION_RELOAD_WAKE_WORDS = "com.advanceagent.core.ACTION_VOICE_RELOAD_WAKE_WORDS"
        const val ACTION_STOP_SERVICE = "com.advanceagent.core.ACTION_STOP_VOICE_INTERACTION_SERVICE"

        private val _lifecycleState = MutableStateFlow(LifecycleState.IDLE)
        val lifecycleState: StateFlow<LifecycleState> = _lifecycleState.asStateFlow()

        private val _isForegroundActive = MutableStateFlow(false)
        val isForegroundActive: StateFlow<Boolean> = _isForegroundActive.asStateFlow()

        private val _lastDetectedWakeWord = MutableStateFlow<String?>(null)
        val lastDetectedWakeWord: StateFlow<String?> = _lastDetectedWakeWord.asStateFlow()

        private val _liveMicAmplitude = MutableStateFlow(0f)
        val liveMicAmplitude: StateFlow<Float> = _liveMicAmplitude.asStateFlow()

        /**
         * Pure lifecycle state transition helper so unit tests and runtime share identical
         * foreground lifecycle validation rules.
         */
        fun computeNextLifecycleState(
            action: String?,
            hasRecordAudioPermission: Boolean,
            isMasterPowerOn: Boolean,
            isNativeJniReady: Boolean
        ): LifecycleState {
            if (action == ACTION_STOP_SERVICE || !isMasterPowerOn) {
                return LifecycleState.STOPPED
            }
            if (!hasRecordAudioPermission) {
                return LifecycleState.ERROR
            }
            return when (action) {
                ACTION_PAUSE_MIC -> LifecycleState.PAUSED
                ACTION_START_FOREGROUND_MIC, ACTION_RESUME_MIC, ACTION_RELOAD_WAKE_WORDS, null -> {
                    if (isNativeJniReady) LifecycleState.FOREGROUND_ACTIVE else LifecycleState.ERROR
                }
                else -> if (isNativeJniReady) LifecycleState.FOREGROUND_ACTIVE else LifecycleState.ERROR
            }
        }

        fun isRecordAudioPermissionGranted(context: Context): Boolean {
            return try {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
            } catch (_: Throwable) {
                false
            }
        }

        /**
         * Ensures `RECORD_AUDIO` permission is granted before starting the foreground service,
         * or requests `RECORD_AUDIO` directly when an [Activity] context is available.
         */
        fun ensureRecordAudioPermissionAndStart(
            context: Context,
            requestCode: Int = REQUEST_CODE_RECORD_AUDIO
        ): Boolean {
            if (!isRecordAudioPermissionGranted(context)) {
                if (context is Activity) {
                    ActivityCompat.requestPermissions(
                        context,
                        arrayOf(Manifest.permission.RECORD_AUDIO),
                        requestCode
                    )
                }
                _lifecycleState.value = LifecycleState.ERROR
                _isForegroundActive.value = false
                return false
            }
            return startForegroundListening(context)
        }

        fun startForegroundListening(context: Context): Boolean {
            if (!isRecordAudioPermissionGranted(context)) {
                if (context is Activity) {
                    ActivityCompat.requestPermissions(
                        context,
                        arrayOf(Manifest.permission.RECORD_AUDIO),
                        REQUEST_CODE_RECORD_AUDIO
                    )
                }
                _lifecycleState.value = LifecycleState.ERROR
                _isForegroundActive.value = false
                return false
            }
            return try {
                val intent = Intent(context, VoiceInteractionService::class.java).apply {
                    action = ACTION_START_FOREGROUND_MIC
                }
                _lifecycleState.value = LifecycleState.STARTING_FOREGROUND
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (t: Throwable) {
                _lifecycleState.value = LifecycleState.ERROR
                _isForegroundActive.value = false
                false
            }
        }

        fun pauseForegroundListening(context: Context) {
            try {
                val intent = Intent(context, VoiceInteractionService::class.java).apply {
                    action = ACTION_PAUSE_MIC
                }
                context.startService(intent)
            } catch (_: Throwable) {
                _lifecycleState.value = LifecycleState.PAUSED
            }
        }

        fun resumeForegroundListening(context: Context) {
            try {
                val intent = Intent(context, VoiceInteractionService::class.java).apply {
                    action = ACTION_RESUME_MIC
                }
                ContextCompat.startForegroundService(context, intent)
            } catch (_: Throwable) {
            }
        }

        fun stopForegroundListening(context: Context) {
            try {
                val stopIntent = Intent(context, VoiceInteractionService::class.java).apply {
                    action = ACTION_STOP_SERVICE
                }
                context.stopService(stopIntent)
            } catch (_: Throwable) {
            } finally {
                _isForegroundActive.value = false
                _lifecycleState.value = LifecycleState.STOPPED
            }
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): VoiceInteractionService = this@VoiceInteractionService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val audioStream = AudioStreamManager()
    val sherpaOnnxBridge: SherpaOnnxBridge = OfficialSherpaOnnxJniBridge
    private val jniBridge: SherpaOnnxJniBridge
        get() = sherpaOnnxBridge

    private lateinit var prefs: SecurePreferences
    private var soundEffects: SoundEffectsManager? = null
    private var kwsEngine: SherpaWakeWordEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder = binder

    private fun hasRecordAudioPermission(): Boolean = isRecordAudioPermissionGranted(this)

    private fun promoteToForegroundSafely(statusText: String): Boolean {
        val notification = buildNotification(statusText)
        return try {
            _lifecycleState.value = LifecycleState.STARTING_FOREGROUND
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            _isForegroundActive.value = true
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to promote VoiceInteractionService to foreground: ${t.message}")
            _isForegroundActive.value = false
            _lifecycleState.value = LifecycleState.ERROR
            false
        }
    }

    override fun onCreate() {
        super.onCreate()
        val app = application as? AgentApplication
        prefs = app?.prefs ?: SecurePreferences(applicationContext)
        soundEffects = SoundEffectsManager(applicationContext).apply {
            isEnabled = prefs.soundAndHapticsEnabled
        }
        createNotificationChannel()

        if (!hasRecordAudioPermission()) {
            Log.w(TAG, "RECORD_AUDIO permission missing; stopping VoiceInteractionService.")
            _lifecycleState.value = LifecycleState.ERROR
            _isForegroundActive.value = false
            stopSelf()
            return
        }

        if (!promoteToForegroundSafely("NAYRO (${prefs.assistantName}) Wake-Word Standby")) {
            stopSelf()
            return
        }

        acquirePartialWakeLock()
        initializeSherpaAndStartMicCapture()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                shutdownAndStop()
                return START_NOT_STICKY
            }
            ACTION_PAUSE_MIC -> {
                pauseMicrophoneInput()
                return START_STICKY
            }
            ACTION_RESUME_MIC -> {
                if (!hasRecordAudioPermission()) {
                    _lifecycleState.value = LifecycleState.ERROR
                    stopSelf()
                    return START_NOT_STICKY
                }
                initializeSherpaAndStartMicCapture()
                return START_STICKY
            }
            ACTION_RELOAD_WAKE_WORDS -> {
                reloadWakeWords()
                return START_STICKY
            }
            ACTION_START_FOREGROUND_MIC, null -> {
                if (!hasRecordAudioPermission()) {
                    _lifecycleState.value = LifecycleState.ERROR
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (!_isForegroundActive.value) {
                    if (!promoteToForegroundSafely("NAYRO (${prefs.assistantName}) Wake-Word Standby")) {
                        stopSelf()
                        return START_NOT_STICKY
                    }
                }
                initializeSherpaAndStartMicCapture()
                return START_STICKY
            }
        }
        return START_STICKY
    }

    private fun initializeSherpaAndStartMicCapture() {
        if (!prefs.isMasterPowerOn || !hasRecordAudioPermission()) {
            _lifecycleState.value = if (!hasRecordAudioPermission()) LifecycleState.ERROR else LifecycleState.STOPPED
            return
        }

        if (kwsEngine == null) {
            kwsEngine = SherpaWakeWordEngine(
                context = applicationContext,
                jniBridge = sherpaOnnxBridge
            )
        }

        // Explicitly ensure the SherpaOnnxBridge native JNI libraries are loaded before initializing KWS
        sherpaOnnxBridge.ensureNativeLibraryLoaded()

        val activeWakeWords = prefs.getAllActiveWakeWords()
        val initialized = kwsEngine?.initialize(
            activeWakeWords = activeWakeWords,
            boostingScore = prefs.wakeBoostingScore,
            triggerThreshold = prefs.wakeTriggerThreshold
        ) ?: false

        if (!initialized) {
            Log.e(TAG, "Sherpa-ONNX native KWS failed to initialize: ${kwsEngine?.lastInitError}")
            _lifecycleState.value = LifecycleState.ERROR
            updateNotification("Wake-Word Engine Error — tap to check settings")
            return
        }

        // Release any secondary AudioRecord instance before capturing microphone frames
        try {
            (application as? NayroApplication)?.voiceManager?.wakeWordEngine?.pauseForAsr()
        } catch (_: Throwable) {
        }

        val captureStarted = audioStream.startCapture(
            scope = serviceScope,
            onPcmShortChunk = { pcmShorts, readCount ->
                if (!prefs.isMasterPowerOn) return@startCapture
                val detected = kwsEngine?.processPcm16(pcmShorts, readCount)
                if (!detected.isNullOrBlank()) {
                    onWakeWordDetected(detected)
                }
            },
            onAmplitude = { amp ->
                _liveMicAmplitude.value = amp
            }
        )

        if (captureStarted) {
            _lifecycleState.value = LifecycleState.FOREGROUND_ACTIVE
            _isForegroundActive.value = true
            updateNotification("Listening locally for ${activeWakeWords.take(2).joinToString(" / ")}")
        } else {
            _lifecycleState.value = LifecycleState.ERROR
            updateNotification("Microphone busy or unavailable")
        }
    }

    private fun onWakeWordDetected(detectedPhrase: String) {
        Log.i(TAG, "Sherpa-ONNX Wake Word Detected in VoiceInteractionService: $detectedPhrase")
        _lastDetectedWakeWord.value = detectedPhrase
        soundEffects?.onWakeWordDetected()

        // Delegate live voice session activation to WakeWordForegroundService / UnifiedAgentRuntime
        try {
            val liveIntent = Intent(applicationContext, WakeWordForegroundService::class.java).apply {
                action = WakeWordForegroundService.ACTION_START_LIVE_SESSION
            }
            ContextCompat.startForegroundService(applicationContext, liveIntent)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to hand off wake trigger to live session: ${t.message}")
        }
    }

    private fun pauseMicrophoneInput() {
        audioStream.stopCapture()
        _liveMicAmplitude.value = 0f
        _lifecycleState.value = LifecycleState.PAUSED
        updateNotification("NAYRO (${prefs.assistantName}) Microphone Paused")
    }

    private fun reloadWakeWords() {
        kwsEngine?.reloadWakeWords(
            activeWakeWords = prefs.getAllActiveWakeWords(),
            boostingScore = prefs.wakeBoostingScore,
            triggerThreshold = prefs.wakeTriggerThreshold
        )
    }

    private fun shutdownAndStop() {
        audioStream.releaseAll()
        kwsEngine?.release()
        kwsEngine = null
        releasePartialWakeLock()
        _liveMicAmplitude.value = 0f
        _isForegroundActive.value = false
        _lifecycleState.value = LifecycleState.STOPPED
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Throwable) {
        }
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Preserve foreground wake-word detection when user swipes away task if master power is on
        if (prefs.isMasterPowerOn && hasRecordAudioPermission()) {
            Log.i(TAG, "Task removed; keeping VoiceInteractionService active in foreground.")
        } else {
            shutdownAndStop()
        }
    }

    override fun onDestroy() {
        audioStream.releaseAll()
        kwsEngine?.release()
        kwsEngine = null
        releasePartialWakeLock()
        serviceScope.cancel()
        _liveMicAmplitude.value = 0f
        _isForegroundActive.value = false
        _lifecycleState.value = LifecycleState.STOPPED
        super.onDestroy()
    }

    private fun acquirePartialWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "NAYRO:VoiceInteractionWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire(6 * 60 * 60 * 1000L)
            }
        } catch (_: Throwable) {
        }
    }

    private fun releasePartialWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Throwable) {
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "NAYRO Wake-Word Voice Interaction",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Foreground microphone service for offline Sherpa-ONNX wake-word detection"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NAYRO • ${prefs.assistantName}")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(statusText: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.notify(NOTIFICATION_ID, buildNotification(statusText))
        } catch (_: Throwable) {
        }
    }
}
