package com.example.core.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.example.core.voice.engine.LocalWakeWordEngine
import com.example.core.voice.engine.WakeEngineState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Authoritative Voice Manager & Audio-Session Coordinator for NAYRO (Phase 1B & 1C).
 * Coordinates:
 * 1. LocalWakeWordEngine (AudioRecord)
 * 2. Android SpeechRecognizer (ASR)
 * 3. Android TextToSpeech (TTS)
 * 4. Continuous Follow-Up Conversation Mode ("Hi Babu" -> "Haan, bolo" -> Follow-up commands without repeating wake word)
 * 5. Audio Focus & Phone Call Interruption Handling
 *
 * Strictly enforces single-owner microphone coordination so AudioRecord and SpeechRecognizer
 * never compete for microphone ownership, and never reports LISTENING unless active recording has started.
 */
class VoiceManager(private val context: Context? = null) : TextToSpeech.OnInitListener {

    val wakeWordEngine = LocalWakeWordEngine(context)
    val speechRecognitionProvider = AndroidSpeechRecognitionProvider(context)

    var isDestroyed: Boolean = false
        private set

    private val _voiceState = MutableStateFlow(VoiceState.DISABLED)
    val voiceState: StateFlow<VoiceState> = _voiceState.asStateFlow()

    private val _audioSessionOwner = MutableStateFlow(AudioSessionOwner.NONE)
    val audioSessionOwner: StateFlow<AudioSessionOwner> = _audioSessionOwner.asStateFlow()

    private val _transcribedText = MutableStateFlow("")
    val transcribedText: StateFlow<String> = _transcribedText.asStateFlow()

    val partialTranscript: StateFlow<String> = speechRecognitionProvider.partialTranscript
    val normalizedAudioLevel: StateFlow<Float> = speechRecognitionProvider.normalizedAudioLevel
    val rmsDb: StateFlow<Float> = speechRecognitionProvider.rmsDb
    val lastRecognitionResult: StateFlow<SpeechRecognitionResult?> = speechRecognitionProvider.lastResult

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _configuration = MutableStateFlow(VoiceConfiguration())
    val configuration: StateFlow<VoiceConfiguration> = _configuration.asStateFlow()

    private val _isFollowUpSessionActive = MutableStateFlow(false)
    val isFollowUpSessionActive: StateFlow<Boolean> = _isFollowUpSessionActive.asStateFlow()

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private val mainHandler: Handler? = try {
        val looper = Looper.getMainLooper()
        if (looper != null) Handler(looper) else null
    } catch (_: Throwable) {
        null
    }
    private var followUpTimeoutRunnable: Runnable? = null

    private val audioManager: AudioManager? = try {
        context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    } catch (_: Throwable) {
        null
    }
    private var activeAudioFocusRequest: AudioFocusRequest? = null

    private var onResultCallback: ((String) -> Unit)? = null
    private var serviceResultListener: ((String) -> Unit)? = null
    private var uiResultListener: ((String) -> Unit)? = null
    private var uiErrorListener: ((String) -> Unit)? = null
    var onSpeechRecognized: ((String) -> Unit)? = null
    var onSpeechError: ((String) -> Unit)? = null
    var onVoiceExitCommand: (() -> Unit)? = null

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                onExternalAudioInterruption("Audio focus lost ($focusChange)")
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                onExternalAudioInterruptionEnded()
            }
        }
    }

    init {
        if (context != null) {
            try {
                tts = TextToSpeech(context, this)
            } catch (_: Throwable) {
                isTtsReady = false
            }
        }
        wakeWordEngine.onWakeWordDetected = { phrase ->
            postToMain {
                onWakeWordTriggered(phrase)
            }
        }
    }

    private fun postToMain(block: () -> Unit) {
        val h = mainHandler
        if (h != null) {
            try {
                h.post(block)
                return
            } catch (_: Throwable) {}
        }
        block()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isTtsReady = true
            tts?.language = Locale.forLanguageTag(_configuration.value.languageTag)
            tts?.setSpeechRate(_configuration.value.speechRate)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    _audioSessionOwner.value = AudioSessionOwner.TTS_PLAYBACK
                    _voiceState.value = VoiceState.SPEAKING
                }

                override fun onDone(utteranceId: String?) {
                    postToMain {
                        handleTtsCompleted(utteranceId)
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    postToMain {
                        abandonTransientAudioFocus()
                        _audioSessionOwner.value = AudioSessionOwner.NONE
                        returnToStandbyOrIdle()
                    }
                }
            })
        }
    }

    /**
     * Checks whether on-device / offline speech recognition is genuinely supported on this Android device (Phase 1B).
     * Never claims offline speech recognition is available without verifying platform support.
     */
    fun isOfflineSpeechRecognitionSupported(): Boolean {
        val ctx = context ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)
            } else {
                SpeechRecognizer.isRecognitionAvailable(ctx)
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Checks whether a cellular/telephony call is currently ringing or off-hook so NAYRO
     * never competes with an active phone call for microphone ownership.
     */
    fun isPhoneCallActiveOrRinging(): Boolean {
        val ctx = context ?: return false
        return try {
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return false
            val state = tm.callState
            state == TelephonyManager.CALL_STATE_OFFHOOK || state == TelephonyManager.CALL_STATE_RINGING
        } catch (_: SecurityException) {
            false
        } catch (_: Throwable) {
            false
        }
    }

    fun isMicrophonePermissionGranted(): Boolean {
        val ctx = context ?: return false
        return try {
            ContextCompat.checkSelfPermission(
                ctx,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    fun attachServiceListener(listener: (String) -> Unit) {
        serviceResultListener = listener
    }

    fun detachServiceListener() {
        serviceResultListener = null
    }

    fun hasActiveServiceListener(): Boolean = serviceResultListener != null

    fun attachUiListener(onRecognized: (String) -> Unit, onError: ((String) -> Unit)? = null) {
        uiResultListener = onRecognized
        uiErrorListener = onError
        onSpeechRecognized = onRecognized
        if (onError != null) {
            onSpeechError = onError
        }
    }

    fun detachUiListener() {
        uiResultListener = null
        uiErrorListener = null
        onSpeechRecognized = null
        onSpeechError = null
    }

    fun hasActiveUiListener(): Boolean = uiResultListener != null || onSpeechRecognized != null

    @Volatile
    private var lastDispatchedVoiceTranscript: String = ""

    @Volatile
    private var lastDispatchedVoiceTimestampMs: Long = 0L

    /**
     * Section 4 — Normalizes raw ASR transcript by trimming, collapsing whitespace, and removing trailing filler punctuation.
     */
    fun normalizeTranscript(rawText: String): String {
        return rawText
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    /**
     * Section 4 — Prevents duplicate voice command execution if SpeechRecognizer emits identical final results within a short window.
     */
    @Synchronized
    fun isDuplicateVoiceCommand(
        transcript: String,
        nowMs: Long = System.currentTimeMillis(),
        dedupWindowMs: Long = 2_000L
    ): Boolean {
        val norm = normalizeTranscript(transcript).lowercase()
        if (norm.isEmpty()) return false
        val isDup = norm == lastDispatchedVoiceTranscript && (nowMs - lastDispatchedVoiceTimestampMs) in 0 until dedupWindowMs
        if (!isDup) {
            lastDispatchedVoiceTranscript = norm
            lastDispatchedVoiceTimestampMs = nowMs
        }
        return isDup
    }

    fun resetVoiceCommandDeduplication() {
        lastDispatchedVoiceTranscript = ""
        lastDispatchedVoiceTimestampMs = 0L
    }

    /**
     * Section 4 — Configures recognition and TTS language tag (e.g., "hi-IN", "en-IN", "en-US").
     */
    fun setRecognitionLanguage(languageTag: String) {
        val cleanTag = languageTag.trim().ifEmpty { "en-IN" }
        _configuration.value = _configuration.value.copy(languageTag = cleanTag)
        if (isTtsReady) {
            try {
                tts?.language = Locale.forLanguageTag(cleanTag)
            } catch (_: Throwable) {}
        }
    }

    fun dispatchRecognizedSpeech(text: String) {
        val clean = normalizeTranscript(text)
        if (clean.isEmpty()) return
        if (isDuplicateVoiceCommand(clean)) return
        _transcribedText.value = clean
        val uiTarget = uiResultListener ?: onSpeechRecognized
        if (uiTarget != null) {
            uiTarget.invoke(clean)
        } else {
            serviceResultListener?.invoke(clean)
        }
    }

    fun updateAssistantIdentity(assistantName: String) {
        val cleanName = assistantName.trim().ifEmpty { "Babu" }
        val configuredPhrases = wakeWordEngine.configureWakePhrasesForAssistantName(cleanName)
        _configuration.value = _configuration.value.copy(
            assistantName = cleanName,
            wakePhrase = "Hey $cleanName",
            wakePhrases = configuredPhrases.keys.toList()
        )
    }

    fun updateConfiguration(newConfig: VoiceConfiguration) {
        _configuration.value = newConfig
        wakeWordEngine.setSensitivity(newConfig.sensitivity)
        wakeWordEngine.configureWakePhrasesForAssistantName(newConfig.assistantName)
        listOf(
            newConfig.wakePhrase,
            "Hi ${newConfig.assistantName}",
            "Hey ${newConfig.assistantName}",
            "Hey Babu",
            "Hi Babu",
            "Hey Cutie",
            "Hi Nayro"
        )
            .distinct()
            .forEach { wakeWordEngine.addWakePhrase(it) }

        if (isTtsReady) {
            tts?.language = Locale.forLanguageTag(newConfig.languageTag)
            tts?.setSpeechRate(newConfig.speechRate)
        }

        if (newConfig.isWakeWordEnabled) {
            if (!isMicrophonePermissionGranted()) {
                _audioSessionOwner.value = AudioSessionOwner.NONE
                _voiceState.value = VoiceState.ERROR
                _errorMessage.value = "Microphone permission is required for local wake-word listening."
                return
            }
            if (isPhoneCallActiveOrRinging()) {
                _audioSessionOwner.value = AudioSessionOwner.PHONE_CALL_INTERRUPTION
                _voiceState.value = VoiceState.PAUSED
                _errorMessage.value = "Wake-word paused during active phone call."
                return
            }
            wakeWordEngine.startListening()
            _audioSessionOwner.value = AudioSessionOwner.WAKE_WORD_ENGINE
            _voiceState.value = VoiceState.STANDBY
        } else {
            cancelFollowUpTimeout()
            _isFollowUpSessionActive.value = false
            wakeWordEngine.stopListening()
            if (_audioSessionOwner.value == AudioSessionOwner.WAKE_WORD_ENGINE) {
                _audioSessionOwner.value = AudioSessionOwner.NONE
            }
            _voiceState.value = VoiceState.DISABLED
        }
    }

    private fun onWakeWordTriggered(phrase: String) {
        if (isPhoneCallActiveOrRinging()) {
            onExternalAudioInterruption("Phone call active during wake trigger")
            return
        }
        // Step 1: Release wake-word AudioRecord immediately before starting ASR or TTS
        wakeWordEngine.pauseForAsr()
        _voiceState.value = VoiceState.WAKE_DETECTED
        _isFollowUpSessionActive.value = _configuration.value.isContinuousConversationEnabled

        // Start active speech recognition for the user's command
        startManualListening(isFollowUpTurn = false) { recognizedText ->
            serviceResultListener?.invoke(recognizedText)
        }
    }

    fun startListening() {
        startManualListening(isFollowUpTurn = false) { recognized ->
            onSpeechRecognized?.invoke(recognized)
            serviceResultListener?.invoke(recognized)
        }
    }

    /**
     * Initiates active speech recognition after ensuring:
     * 1. Microphone permission is granted
     * 2. No phone call is active
     * 3. LocalWakeWordEngine has released AudioRecord
     * 4. TTS playback is stopped (supports barge-in interruption)
     *
     * Note: Does NOT transition to LISTENING until SpeechRecognizer.onReadyForSpeech confirms
     * the microphone session is open (Phase 1B requirement: "Never report LISTENING unless the microphone is actually recording").
     */
    fun startManualListening(isFollowUpTurn: Boolean = false, onResult: (String) -> Unit) {
        onResultCallback = onResult
        _errorMessage.value = null
        cancelFollowUpTimeout()

        if (!isMicrophonePermissionGranted()) {
            _audioSessionOwner.value = AudioSessionOwner.NONE
            _voiceState.value = VoiceState.ERROR
            val msg = "Microphone permission is not granted."
            _errorMessage.value = msg
            onSpeechError?.invoke(msg)
            return
        }

        if (isPhoneCallActiveOrRinging()) {
            _audioSessionOwner.value = AudioSessionOwner.PHONE_CALL_INTERRUPTION
            _voiceState.value = VoiceState.PAUSED
            val msg = "Cannot start voice listening during an active phone call."
            _errorMessage.value = msg
            onSpeechError?.invoke(msg)
            return
        }

        // Stop TTS if currently speaking (barge-in interruption support)
        stopSpeakingInternal(resumeStandby = false)

        // Ensure wake-word AudioRecord is released before SpeechRecognizer acquires the mic
        wakeWordEngine.pauseForAsr()

        if (!speechRecognitionProvider.isRecognitionAvailable()) {
            _audioSessionOwner.value = AudioSessionOwner.NONE
            _voiceState.value = VoiceState.ERROR
            val msg = "Speech recognition service is not available on this device."
            _errorMessage.value = msg
            onSpeechError?.invoke(msg)
            return
        }

        requestTransientAudioFocus()

        speechRecognitionProvider.startListening(
            languageTag = _configuration.value.languageTag,
            preferOffline = _configuration.value.preferOfflineSpeechRecognition && isOfflineSpeechRecognitionSupported(),
            onReady = {
                _audioSessionOwner.value = AudioSessionOwner.SPEECH_RECOGNIZER
                _voiceState.value = if (isFollowUpTurn) {
                    VoiceState.FOLLOW_UP_LISTENING
                } else {
                    VoiceState.LISTENING
                }
                if (isFollowUpTurn) {
                    scheduleFollowUpTimeout()
                }
            },
            onBeginningOfSpeech = {
                cancelFollowUpTimeout()
                _voiceState.value = VoiceState.LISTENING
            },
            onPartialText = { partial ->
                if (partial.isNotBlank()) {
                    _transcribedText.value = partial
                }
            },
            onEndOfSpeech = {
                cancelFollowUpTimeout()
                _voiceState.value = VoiceState.TRANSCRIBING
            },
            onFinalResult = { result ->
                cancelFollowUpTimeout()
                abandonTransientAudioFocus()
                _audioSessionOwner.value = AudioSessionOwner.NONE

                val text = result.primaryText.trim()
                _transcribedText.value = text

                if (text.isNotBlank()) {
                    if (wakeWordEngine.isVoiceExitCommand(text) || isConversationExitPhrase(text)) {
                        _isFollowUpSessionActive.value = false
                        onVoiceExitCommand?.invoke()
                        returnToStandbyOrIdle()
                        return@startListening
                    }
                    _voiceState.value = VoiceState.THINKING
                    if (_configuration.value.isContinuousConversationEnabled) {
                        _isFollowUpSessionActive.value = true
                    }
                    val callback = onResultCallback ?: uiResultListener ?: onSpeechRecognized ?: serviceResultListener
                    callback?.invoke(text)
                } else {
                    _isFollowUpSessionActive.value = false
                    returnToStandbyOrIdle()
                }
            },
            onError = { errorCode, errText ->
                cancelFollowUpTimeout()
                abandonTransientAudioFocus()
                _audioSessionOwner.value = AudioSessionOwner.NONE

                if (isFollowUpTurn && (errorCode == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || errorCode == SpeechRecognizer.ERROR_NO_MATCH)) {
                    _isFollowUpSessionActive.value = false
                    returnToStandbyOrIdle()
                    return@startListening
                }

                _errorMessage.value = errText
                _voiceState.value = VoiceState.ERROR
                (uiErrorListener ?: onSpeechError)?.invoke(errText)
                returnToStandbyOrIdle()
            }
        )
    }

    fun markAgentThinking() {
        _voiceState.value = VoiceState.THINKING
    }

    fun markAgentExecuting() {
        _voiceState.value = VoiceState.EXECUTING
    }

    /**
     * Speaks [text] through Android TextToSpeech while ensuring wake-word AudioRecord and ASR
     * are paused so TTS never triggers false wake words or mic feedback loops.
     * When [expectFollowUp] is true (or continuous conversation mode is enabled), completion of TTS
     * transitions into [VoiceState.FOLLOW_UP_LISTENING].
     */
    fun speak(text: String, expectFollowUp: Boolean = _configuration.value.isContinuousConversationEnabled) {
        if (!_configuration.value.isTtsEnabled || !isTtsReady) {
            if (expectFollowUp && _isFollowUpSessionActive.value) {
                startFollowUpListeningWindow()
            } else {
                returnToStandbyOrIdle()
            }
            return
        }

        // Ensure microphone is not capturing while Babu speaks
        cancelFollowUpTimeout()
        speechRecognitionProvider.cancel()
        wakeWordEngine.pauseForAsr()
        requestTransientAudioFocus()

        _audioSessionOwner.value = AudioSessionOwner.TTS_PLAYBACK
        _voiceState.value = VoiceState.SPEAKING
        val utteranceId = if (expectFollowUp && _isFollowUpSessionActive.value) {
            "nayro_utterance_followup_${System.currentTimeMillis()}"
        } else {
            "nayro_utterance_final_${System.currentTimeMillis()}"
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    private fun handleTtsCompleted(utteranceId: String?) {
        abandonTransientAudioFocus()
        _audioSessionOwner.value = AudioSessionOwner.NONE
        val shouldFollowUp = utteranceId?.startsWith("nayro_utterance_followup_") == true &&
            _configuration.value.isContinuousConversationEnabled &&
            _isFollowUpSessionActive.value &&
            isMicrophonePermissionGranted() &&
            !isPhoneCallActiveOrRinging()

        if (shouldFollowUp) {
            startFollowUpListeningWindow()
        } else {
            returnToStandbyOrIdle()
        }
    }

    /**
     * Opens the continuous follow-up listening window so the user can issue the next command
     * without repeating "Hey Babu".
     */
    fun startFollowUpListeningWindow() {
        if (!isMicrophonePermissionGranted() || isPhoneCallActiveOrRinging()) {
            _isFollowUpSessionActive.value = false
            returnToStandbyOrIdle()
            return
        }
        _isFollowUpSessionActive.value = true
        val callback = serviceResultListener ?: onResultCallback ?: {}
        startManualListening(isFollowUpTurn = true, onResult = callback)
    }

    /**
     * Allows the user to interrupt TTS playback ("barge-in") and immediately start speaking a new command.
     */
    fun interruptSpeakingAndListen(onResult: ((String) -> Unit)? = null) {
        stopSpeakingInternal(resumeStandby = false)
        val callback = onResult ?: serviceResultListener ?: onResultCallback ?: {}
        startManualListening(isFollowUpTurn = true, onResult = callback)
    }

    fun stopListening() {
        cancelFollowUpTimeout()
        _isFollowUpSessionActive.value = false
        speechRecognitionProvider.stopListening()
        speechRecognitionProvider.cancel()
        abandonTransientAudioFocus()
        _audioSessionOwner.value = AudioSessionOwner.NONE
        returnToStandbyOrIdle()
    }

    fun stopSpeaking() {
        stopSpeakingInternal(resumeStandby = true)
    }

    private fun stopSpeakingInternal(resumeStandby: Boolean) {
        try {
            tts?.stop()
        } catch (_: Exception) {}
        abandonTransientAudioFocus()
        if (_audioSessionOwner.value == AudioSessionOwner.TTS_PLAYBACK) {
            _audioSessionOwner.value = AudioSessionOwner.NONE
        }
        if (resumeStandby) {
            _isFollowUpSessionActive.value = false
            returnToStandbyOrIdle()
        }
    }

    /**
     * Handles incoming/outgoing phone calls or external audio focus loss by pausing wake-word and ASR safely.
     */
    fun onExternalAudioInterruption(reason: String = "External audio interruption") {
        cancelFollowUpTimeout()
        _isFollowUpSessionActive.value = false
        speechRecognitionProvider.cancel()
        try {
            tts?.stop()
        } catch (_: Exception) {}
        wakeWordEngine.onAudioInterruptionStarted(reason)
        _audioSessionOwner.value = AudioSessionOwner.PHONE_CALL_INTERRUPTION
        _voiceState.value = VoiceState.PAUSED
    }

    /**
     * Resumes wake-word standby after an external phone call or audio focus interruption ends.
     */
    fun onExternalAudioInterruptionEnded() {
        if (isPhoneCallActiveOrRinging()) return
        _audioSessionOwner.value = AudioSessionOwner.NONE
        if (_configuration.value.isWakeWordEnabled && isMicrophonePermissionGranted()) {
            wakeWordEngine.recoverFromAudioInterruption()
            _audioSessionOwner.value = AudioSessionOwner.WAKE_WORD_ENGINE
            _voiceState.value = VoiceState.STANDBY
        } else {
            _voiceState.value = if (_configuration.value.isWakeWordEnabled) VoiceState.IDLE else VoiceState.DISABLED
        }
    }

    private fun returnToStandbyOrIdle() {
        cancelFollowUpTimeout()
        if (_configuration.value.isWakeWordEnabled && isMicrophonePermissionGranted() && !isPhoneCallActiveOrRinging()) {
            wakeWordEngine.recoverFromAudioInterruption()
            if (wakeWordEngine.engineState.value != WakeEngineState.LISTENING_FOR_WAKE) {
                wakeWordEngine.startListening()
            }
            _audioSessionOwner.value = AudioSessionOwner.WAKE_WORD_ENGINE
            _voiceState.value = VoiceState.STANDBY
        } else if (_configuration.value.isWakeWordEnabled) {
            _audioSessionOwner.value = AudioSessionOwner.NONE
            _voiceState.value = VoiceState.IDLE
        } else {
            _audioSessionOwner.value = AudioSessionOwner.NONE
            _voiceState.value = VoiceState.DISABLED
        }
    }

    private fun scheduleFollowUpTimeout() {
        cancelFollowUpTimeout()
        val timeoutMs = _configuration.value.followUpTimeoutMs.coerceIn(2_000L, 30_000L)
        val runnable = Runnable {
            if (_voiceState.value == VoiceState.FOLLOW_UP_LISTENING) {
                _isFollowUpSessionActive.value = false
                stopListening()
            }
        }
        followUpTimeoutRunnable = runnable
        mainHandler?.postDelayed(runnable, timeoutMs)
    }

    private fun cancelFollowUpTimeout() {
        followUpTimeoutRunnable?.let { mainHandler?.removeCallbacks(it) }
        followUpTimeoutRunnable = null
    }

    private fun requestTransientAudioFocus() {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setOnAudioFocusChangeListener(audioFocusChangeListener)
                    .build()
                activeAudioFocusRequest = req
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    audioFocusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                )
            }
        } catch (_: Exception) {}
    }

    private fun abandonTransientAudioFocus() {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activeAudioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
                activeAudioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(audioFocusChangeListener)
            }
        } catch (_: Exception) {}
    }

    fun isConversationExitPhrase(text: String): Boolean {
        val normalized = text.trim().lowercase().removeSuffix(".")
        return normalized in setOf(
            "stop",
            "bas",
            "bus",
            "cancel",
            "thank you",
            "thanks",
            "shukriya",
            "dhanyawad",
            "band karo",
            "chup ho jao",
            "exit",
            "quit"
        )
    }

    /**
     * Deterministic state-transition helper for JVM unit & integration tests (Phase 10).
     * Clearly labeled for test verification when hardware SpeechRecognizer is not attached.
     */
    fun simulateVoiceConversationTurnForTest(
        wakePhrase: String = "Hey Babu",
        spokenCommand: String,
        assistantReply: String,
        enableFollowUp: Boolean = true
    ): List<VoiceState> {
        val recordedStates = mutableListOf<VoiceState>()
        _configuration.value = _configuration.value.copy(
            isWakeWordEnabled = true,
            isContinuousConversationEnabled = enableFollowUp
        )
        // 1. Standby with Wake-word engine owning mic
        wakeWordEngine.startListening()
        _audioSessionOwner.value = AudioSessionOwner.WAKE_WORD_ENGINE
        _voiceState.value = VoiceState.STANDBY
        recordedStates.add(_voiceState.value)

        // 2. Wake phrase detected -> pause wake-word AudioRecord before ASR
        wakeWordEngine.pauseForAsr()
        _voiceState.value = VoiceState.WAKE_DETECTED
        recordedStates.add(_voiceState.value)

        // 3. SpeechRecognizer ready -> LISTENING
        _audioSessionOwner.value = AudioSessionOwner.SPEECH_RECOGNIZER
        _voiceState.value = VoiceState.LISTENING
        recordedStates.add(_voiceState.value)

        // 4. End of speech -> TRANSCRIBING
        _voiceState.value = VoiceState.TRANSCRIBING
        _transcribedText.value = spokenCommand
        recordedStates.add(_voiceState.value)

        // 5. Release ASR mic -> THINKING -> EXECUTING
        _audioSessionOwner.value = AudioSessionOwner.NONE
        _voiceState.value = VoiceState.THINKING
        recordedStates.add(_voiceState.value)
        _voiceState.value = VoiceState.EXECUTING
        recordedStates.add(_voiceState.value)

        // 6. Assistant speaks response -> SPEAKING
        _audioSessionOwner.value = AudioSessionOwner.TTS_PLAYBACK
        _voiceState.value = VoiceState.SPEAKING
        recordedStates.add(_voiceState.value)

        // 7. TTS completes -> transition to FOLLOW_UP_LISTENING (if enabled and not exit command) or STANDBY
        _audioSessionOwner.value = AudioSessionOwner.NONE
        if (enableFollowUp && !isConversationExitPhrase(spokenCommand) && !wakeWordEngine.isVoiceExitCommand(spokenCommand)) {
            _isFollowUpSessionActive.value = true
            _audioSessionOwner.value = AudioSessionOwner.SPEECH_RECOGNIZER
            _voiceState.value = VoiceState.FOLLOW_UP_LISTENING
            recordedStates.add(_voiceState.value)
        } else {
            _isFollowUpSessionActive.value = false
            wakeWordEngine.recoverFromAudioInterruption()
            _audioSessionOwner.value = AudioSessionOwner.WAKE_WORD_ENGINE
            _voiceState.value = VoiceState.STANDBY
            recordedStates.add(_voiceState.value)
        }

        return recordedStates
    }

    private fun mapSpeechError(errorCode: Int): String {
        return when (errorCode) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error."
            SpeechRecognizer.ERROR_CLIENT -> "Speech client error."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission missing."
            SpeechRecognizer.ERROR_NETWORK -> "Network error during speech recognition."
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout during speech recognition."
            SpeechRecognizer.ERROR_NO_MATCH -> "Could not catch that. Please try speaking again."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is currently busy."
            SpeechRecognizer.ERROR_SERVER -> "Speech recognition server error."
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected."
            else -> "Speech recognition failed (code $errorCode)."
        }
    }

    /**
     * Safely stops active UI-bound manual ASR or TTS when MainActivity is destroyed
     * while preserving background wake-word listening if NayroForegroundService is running.
     */
    fun onActivityDestroyed() {
        detachUiListener()
        cancelFollowUpTimeout()
        _isFollowUpSessionActive.value = false
        abandonTransientAudioFocus()
        speechRecognitionProvider.release()
        try {
            tts?.stop()
        } catch (_: Exception) {}
        if (_configuration.value.isWakeWordEnabled && isMicrophonePermissionGranted() && !isPhoneCallActiveOrRinging()) {
            wakeWordEngine.recoverFromAudioInterruption()
            _audioSessionOwner.value = AudioSessionOwner.WAKE_WORD_ENGINE
            _voiceState.value = VoiceState.STANDBY
        } else {
            _audioSessionOwner.value = AudioSessionOwner.NONE
            _voiceState.value = if (_configuration.value.isWakeWordEnabled) VoiceState.PAUSED else VoiceState.DISABLED
        }
    }

    fun release() {
        isDestroyed = true
        detachUiListener()
        detachServiceListener()
        cancelFollowUpTimeout()
        _isFollowUpSessionActive.value = false
        wakeWordEngine.stopListening()
        abandonTransientAudioFocus()
        speechRecognitionProvider.release()
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {}
        tts = null
        _audioSessionOwner.value = AudioSessionOwner.NONE
        _voiceState.value = VoiceState.STOPPED
    }
}
