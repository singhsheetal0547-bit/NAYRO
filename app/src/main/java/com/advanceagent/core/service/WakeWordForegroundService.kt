package com.advanceagent.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.advanceagent.core.AgentApplication
import com.advanceagent.core.R
import com.advanceagent.core.audio.AudioStreamManager
import com.advanceagent.core.audio.SoundEffectsManager
import com.advanceagent.core.data.SecurePreferences
import com.advanceagent.core.data.chat.ChatRepository
import com.advanceagent.core.network.GeminiLiveWebSocket
import com.advanceagent.core.network.IncomingFunctionCall
import com.advanceagent.core.tools.AgentToolRouter
import com.advanceagent.core.ui.MainActivity
import com.advanceagent.core.vision.ScreenCaptureManager
import com.advanceagent.core.wakeword.SherpaWakeWordEngine
import com.example.NayroApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class WakeWordForegroundService : Service() {

    companion object {
        private const val TAG = "WakeWordService"
        private const val CHANNEL_ID = "advance_agent_channel"
        private const val NOTIF_ID = 1001

        const val ACTION_START_LISTENING = "com.advanceagent.core.ACTION_START_LISTENING"
        const val ACTION_START_ENGINE = "com.advanceagent.core.ACTION_START_ENGINE"
        const val ACTION_RELOAD_WAKE_WORDS = "com.advanceagent.core.ACTION_RELOAD_WAKE_WORDS"
        const val ACTION_START_LIVE_SESSION = "com.advanceagent.core.ACTION_START_LIVE_SESSION"
        const val ACTION_MANUAL_TRIGGER = "com.advanceagent.core.ACTION_MANUAL_TRIGGER"
        const val ACTION_STOP_LIVE_SESSION = "com.advanceagent.core.ACTION_STOP_LIVE_SESSION"
        const val ACTION_STOP_SESSION = "com.advanceagent.core.ACTION_STOP_SESSION"
        const val ACTION_COMPLETE_SHUTDOWN = "com.advanceagent.core.ACTION_COMPLETE_SHUTDOWN"
        const val ACTION_STREAM_SCREEN_VISION = "com.advanceagent.core.ACTION_STREAM_SCREEN_VISION"

        private val _agentOrbStateFlow = MutableStateFlow("IDLE")
        val agentOrbStateFlow = _agentOrbStateFlow.asStateFlow()

        private val _audioAmplitudeFlow = MutableStateFlow(0f)
        val audioAmplitudeFlow = _audioAmplitudeFlow.asStateFlow()

        @Volatile
        private var activeInstance: WakeWordForegroundService? = null

        fun streamCameraFrameToLiveSession(jpegBase64: String): Boolean {
            return activeInstance?.geminiClient?.sendJpegFrame(jpegBase64) == true
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: SecurePreferences
    private lateinit var soundEffects: SoundEffectsManager
    private var wakeLock: PowerManager.WakeLock? = null

    private val audioStream = AudioStreamManager()
    private var kwsEngine: SherpaWakeWordEngine? = null
    private var geminiClient: GeminiLiveWebSocket? = null
    private var toolRouter: AgentToolRouter? = null

    @Volatile
    private var isLiveSessionActive = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        val app = application as AgentApplication
        prefs = app.prefs
        soundEffects = SoundEffectsManager(applicationContext).apply {
            isEnabled = prefs.soundAndHapticsEnabled
        }
        ChatRepository.nayroRepository = (application as? NayroApplication)?.repository
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("NAYRO (${prefs.assistantName}) Standby"))

        toolRouter = AgentToolRouter(applicationContext, app.memoryDb)

        acquirePartialWakeLock()
        initializeEngines()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!prefs.isMasterPowerOn && intent?.action != ACTION_COMPLETE_SHUTDOWN) {
            Log.w(TAG, "Master Power is OFF. Stopping WakeWordForegroundService.")
            stopSelf()
            return START_NOT_STICKY
        }

        soundEffects.isEnabled = prefs.soundAndHapticsEnabled

        when (intent?.action) {
            ACTION_START_LISTENING, ACTION_START_ENGINE -> {
                initializeEngines()
            }
            ACTION_RELOAD_WAKE_WORDS -> {
                reloadWakeWordsLive()
            }
            ACTION_MANUAL_TRIGGER, ACTION_START_LIVE_SESSION -> {
                if (isLiveSessionActive) {
                    if (_agentOrbStateFlow.value == "SPEAKING") {
                        audioStream.flushPlaybackForBargeIn()
                        _agentOrbStateFlow.value = "LISTENING"
                    } else {
                        stopGeminiLiveSession()
                    }
                } else {
                    startGeminiLiveSession("MANUAL_ORB_TAP")
                }
            }
            ACTION_STOP_SESSION, ACTION_STOP_LIVE_SESSION -> {
                stopGeminiLiveSession()
            }
            ACTION_STREAM_SCREEN_VISION -> {
                streamScreenVisionFrame()
            }
            ACTION_COMPLETE_SHUTDOWN -> {
                soundEffects.onErrorOrShutdown()
                stopGeminiLiveSession()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    private fun initializeEngines() {
        if (!prefs.isMasterPowerOn) return

        if (kwsEngine == null) {
            kwsEngine = SherpaWakeWordEngine(context = applicationContext)
        }

        val activeWords = prefs.getAllActiveWakeWords()
        val initialized = kwsEngine?.initialize(
            activeWakeWords = activeWords,
            boostingScore = prefs.wakeBoostingScore,
            triggerThreshold = prefs.wakeTriggerThreshold
        ) ?: false

        if (!initialized) {
            Log.w(TAG, "Sherpa-ONNX KWS running in manual trigger standby mode.")
        }

        startContinuousMicrophoneLoop()
    }

    private fun startContinuousMicrophoneLoop() {
        audioStream.startCapture(
            scope = serviceScope,
            onPcmShortChunk = { pcmShorts, readCount ->
                if (!prefs.isMasterPowerOn) return@startCapture
                val detected = kwsEngine?.processPcm16(pcmShorts, readCount)
                if (!detected.isNullOrBlank()) {
                    Log.i(TAG, "Wake Word Triggered: $detected")
                    soundEffects.onWakeWordDetected()
                    if (!isLiveSessionActive) {
                        startGeminiLiveSession(detected)
                    } else {
                        audioStream.flushPlaybackForBargeIn()
                    }
                }
            },
            onAmplitude = { amp ->
                if (isLiveSessionActive && _agentOrbStateFlow.value == "LISTENING") {
                    _audioAmplitudeFlow.value = amp
                }
            },
            onPcmByteChunk = { pcmBytes ->
                if (isLiveSessionActive) {
                    geminiClient?.sendPcmAudioChunk(pcmBytes)
                }
            }
        )
    }

    private fun reloadWakeWordsLive() {
        Log.i(TAG, "Hot-reloading Dual Wake Words in Sherpa-ONNX...")
        kwsEngine?.reloadWakeWords(
            activeWakeWords = prefs.getAllActiveWakeWords(),
            boostingScore = prefs.wakeBoostingScore,
            triggerThreshold = prefs.wakeTriggerThreshold
        )
    }

    private fun startGeminiLiveSession(triggerSource: String) {
        val apiKey = prefs.geminiApiKey.trim()
        val ephemeralToken = prefs.ephemeralAccessToken.trim()
        if (apiKey.isEmpty() && ephemeralToken.isEmpty()) {
            Log.e(TAG, "Gemini API Key and Ephemeral Token are empty! Please set API key in Settings.")
            _agentOrbStateFlow.value = "ERROR"
            soundEffects.onErrorOrShutdown()
            updateNotification("Missing Gemini API Key in Settings")
            return
        }

        isLiveSessionActive = true
        _agentOrbStateFlow.value = "THINKING"
        soundEffects.onListeningStarted()
        updateNotification("Live Session Active ($triggerSource)")

        serviceScope.launch {
            val app = application as AgentApplication
            val recentMemories = try {
                app.memoryDb.memoryDao().getRecentMemories().map { it.fact }
            } catch (_: Exception) {
                emptyList()
            }

            geminiClient?.disconnect()
            geminiClient = GeminiLiveWebSocket(
                apiKey = apiKey,
                modelId = prefs.geminiModel,
                voiceName = prefs.voiceName,
                userName = prefs.userName,
                memories = recentMemories,
                assistantName = prefs.assistantName,
                personalityMode = prefs.personalityMode,
                includeGoogleSearchGrounding = prefs.googleSearchGroundingEnabled,
                enableLiveTranscription = prefs.liveTranscriptionEnabled,
                enableSessionResumption = prefs.sessionResumptionEnabled,
                initialSessionResumptionHandle = prefs.lastSessionResumptionHandle,
                ephemeralAccessToken = ephemeralToken,
                onAudioReceived = { pcm24kBytes ->
                    _agentOrbStateFlow.value = "SPEAKING"
                    audioStream.writePlaybackPcm(pcm24kBytes)
                },
                onInterrupted = {
                    audioStream.flushPlaybackForBargeIn()
                    _agentOrbStateFlow.value = "LISTENING"
                },
                onToolCall = { functionCall ->
                    handleFunctionCall(functionCall)
                },
                onStateChanged = { newState ->
                    _agentOrbStateFlow.value = newState
                    if (newState == "DISCONNECTED" || newState == "ERROR") {
                        isLiveSessionActive = false
                        _audioAmplitudeFlow.value = 0f
                        updateNotification("NAYRO (${prefs.assistantName}) Standby")
                    }
                },
                onSessionHandleUpdated = { newHandle ->
                    prefs.lastSessionResumptionHandle = newHandle
                }
            )
            geminiClient?.connect()
        }
    }

    private fun streamScreenVisionFrame() {
        if (!isLiveSessionActive) {
            Log.w(TAG, "Cannot stream Screen Vision frame: Gemini Live session is not active.")
            return
        }
        val base64Jpeg = ScreenCaptureManager.captureScreenJpegBase64(applicationContext, quality = 70)
        if (!base64Jpeg.isNullOrBlank()) {
            val sent = geminiClient?.sendJpegFrame(base64Jpeg) == true
            Log.i(TAG, "Streamed on-demand MediaProjection JPEG frame to Gemini Live: sent=$sent")
            if (sent) {
                soundEffects.onActionCompleted()
            }
        } else {
            Log.w(TAG, "MediaProjection frame not available; grant Screen Capture permission on HUD first.")
        }
    }

    private fun handleFunctionCall(call: IncomingFunctionCall) {
        serviceScope.launch {
            if (geminiClient?.isToolCallCancelled(call.id) == true) {
                Log.i(TAG, "Skipping execution of cancelled tool call ${call.id}")
                return@launch
            }
            _agentOrbStateFlow.value = "EXECUTING"
            val result = toolRouter?.executeTool(call.name, call.args)
                ?: mapOf("status" to "error", "message" to "Router unavailable")
            val statusStr = result["status"]?.toString() ?: "completed"
            if (statusStr == "error") {
                soundEffects.onErrorOrShutdown()
            } else {
                soundEffects.onActionCompleted()
            }
            geminiClient?.sendToolResponse(call.id, call.name, result)
            _agentOrbStateFlow.value = "LISTENING"
        }
    }

    private fun stopGeminiLiveSession() {
        isLiveSessionActive = false
        geminiClient?.disconnect()
        geminiClient = null
        audioStream.flushPlaybackForBargeIn()
        _agentOrbStateFlow.value = "IDLE"
        _audioAmplitudeFlow.value = 0f
        updateNotification("NAYRO (${prefs.assistantName}) Standby")
    }

    private fun acquirePartialWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AdvanceVoiceAgent::WakeWordLock").apply {
                setReferenceCounted(false)
                acquire(10 * 60 * 1000L)
            }
        } catch (e: Exception) {
            Log.e(TAG, "WakeLock error: ${e.message}")
        }
    }

    private fun releasePartialWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
        wakeLock = null
    }

    override fun onDestroy() {
        Log.i(TAG, "Destroying WakeWordForegroundService & releasing all resources...")
        if (activeInstance === this) {
            activeInstance = null
        }
        isLiveSessionActive = false
        geminiClient?.disconnect()
        geminiClient = null
        audioStream.releaseAll()
        kwsEngine?.release()
        kwsEngine = null
        releasePartialWakeLock()
        serviceScope.cancel()
        _agentOrbStateFlow.value = "IDLE"
        _audioAmplitudeFlow.value = 0f
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "NAYRO Voice & Vision Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps offline wake-word detection and Gemini Live voice active"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
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
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(statusText))
    }
}
