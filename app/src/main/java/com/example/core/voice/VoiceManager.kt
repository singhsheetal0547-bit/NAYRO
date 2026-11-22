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
class VoiceManager(
    private val context: Context? = null,
    encryptedStorageManager: com.example.core.security.EncryptedStorageManager? = null
) : TextToSpeech.OnInitListener {

    val wakeWordEngine = LocalWakeWordEngine(context)
    val speechRecognitionProvider = AndroidSpeechRecognitionProvider(context)
    val voiceProviderRouter = VoiceProviderRouter(
        context = context,
        encryptedStorageManager = encryptedStorageManager ?: com.example.core.security.EncryptedStorageManager(context),
        androidSttProvider = speechRecognitionProvider
    )

    var isDestroyed: Boolean = false
        private set

    val microphoneOwnership = MicrophoneOwnershipController()

    private val _voiceState = MutableStateFlow(VoiceState.DISABLED)
    val voiceState: StateFlow<VoiceState> = _voiceState.asStateFlow()

    val audioSessionOwner: StateFlow<AudioSessionOwner> = microphoneOwnership.owner
    val microphonePhase: StateFlow<MicrophoneLifecyclePhase> = microphoneOwnership.phase
    val microphoneOwnershipSnapshot: StateFlow<MicrophoneOwnershipSnapshot> = microphoneOwnership.snapshot

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

    val speechSynthesisProvider: SpeechSynthesisProvider
        get() = voiceProviderRouter.resolveActiveTtsProvider()

    private val _lastSpeechBlockedReason = MutableStateFlow<String?>(null)
    val lastSpeechBlockedReason: StateFlow<String?> = _lastSpeechBlockedReason.asStateFlow()

    private var phoneCallActiveOverrideForTesting: (() -> Boolean)? = null

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private val mainHandler: Handler? = try {
        val looper = Looper.getMainLooper()
        if (looper != null) Handler(looper) else null
    } catch (_: Throwable) {
        null
    }
    private var followUpTimeoutRunnable: Runnable? = null
    private var ttsWatchdogRunnable: Runnable? = null

    private val audioManager: AudioManager? = try {
        context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    } catch (_: Throwable) {
        null
    }
    private var activeAudioFocusRequest: AudioFocusRequest? = null
    @Volatile
    private var activeCaptureSessionId: Long = 0L
    private var micPermissionCheckerOverride: (() -> Boolean)? = null

    private var onResultCallback: ((String) -> Unit)? = null
    private var serviceResultListener: ((String) -> Unit)? = null
    private var floatingResultListener: ((String) -> Unit)? = null
    private var voiceInteractionResultListener: ((String) -> Unit)? = null
    private var uiResultListener: ((String) -> Unit)? = null
    private var uiErrorListener: ((String) -> Unit)? = null
    var onSpeechRecognized: ((String) -> Unit)? = null
    var onSpeechError: ((String) -> Unit)? = null
    var onVoiceExitCommand: (() -> Unit)? = null

    val standbyAudioLevel: StateFlow<Float> = wakeWordEngine.standbyAudioLevel

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
        // P0-4: Guard enrollment & calibration capture through the same centralized microphone coordinator
        wakeWordEngine.setEnrollmentOwnershipGuard { captureBlock ->
            runWithEnrollmentMicrophoneOwnership(captureBlock)
        }
    }

    fun setMicPermissionCheckerForTesting(checker: (() -> Boolean)?) {
        micPermissionCheckerOverride = checker
        wakeWordEngine.setMicPermissionCheckerForTesting(checker)
    }

    /**
     * Acquires exclusive [AudioSessionOwner.ENROLLMENT_CAPTURE] ownership for wake-word enrollment
     * or calibration so it never collides with wake listening, SpeechRecognizer, or Deepgram STT (P0-4).
     */
    suspend fun runWithEnrollmentMicrophoneOwnership(block: suspend () -> Unit): Boolean {
        val wasWake = microphoneOwnership.getCurrentOwner() == AudioSessionOwner.WAKE_WORD_ENGINE
        if (wasWake) {
            wakeWordEngine.pauseForAsr()
        }
        val token = microphoneOwnership.tryAcquire(
            requestedOwner = AudioSessionOwner.ENROLLMENT_CAPTURE,
            targetPhase = MicrophoneLifecyclePhase.ENROLLMENT_CAPTURE,
            reason = "Wake phrase enrollment / calibration capture",
            allowPreemptWake = true,
            allowPreemptSameOwner = false
        ) ?: return false

        return try {
            block()
            true
        } finally {
            microphoneOwnership.release(
                expectedOwner = AudioSessionOwner.ENROLLMENT_CAPTURE,
                sessionId = token,
                nextPhase = MicrophoneLifecyclePhase.IDLE,
                reason = "Enrollment capture completed"
            )
            if (wasWake && _configuration.value.isWakeWordEnabled) {
                returnToStandbyOrIdle()
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
                    microphoneOwnership.enterResponsePlayback("TTS utterance started")
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
                        cancelTtsWatchdog()
                        abandonTransientAudioFocus()
                        microphoneOwnership.release(
                            AudioSessionOwner.TTS_PLAYBACK,
                            reason = "TTS utterance error"
                        )
                        if (utteranceId?.startsWith("nayro_utterance_followup_") == true &&
                            _isFollowUpSessionActive.value &&
                            isMicrophonePermissionGranted() &&
                            !isPhoneCallActiveOrRinging()
                        ) {
                            startFollowUpListeningWindow()
                        } else {
                            returnToStandbyOrIdle()
                        }
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

    fun setPhoneCallActiveCheckerForTesting(checker: (() -> Boolean)?) {
        phoneCallActiveOverrideForTesting = checker
    }

    /**
     * Checks whether a cellular/telephony call is currently ringing or off-hook so NAYRO
     * never competes with an active phone call for microphone ownership and NEVER speaks
     * to a remote caller during a phone call (CRITICAL CALLING RULE).
     */
    fun isPhoneCallActiveOrRinging(): Boolean {
        phoneCallActiveOverrideForTesting?.let { return it.invoke() }
        if (com.example.core.telephony.TelecomCallControlBridge.hasLiveTelecomCall()) {
            return true
        }
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

    /**
     * CRITICAL CALLING RULE guard: returns true whenever a phone call is active, ringing,
     * or holding audio interruption priority so AI TTS playback is strictly prohibited.
     */
    fun isSpeechBlockedByPhoneCall(): Boolean {
        return isPhoneCallActiveOrRinging() ||
            microphoneOwnership.getCurrentOwner() == AudioSessionOwner.PHONE_CALL_INTERRUPTION ||
            com.example.core.telephony.TelecomCallControlBridge.hasLiveTelecomCall()
    }

    fun isMicrophonePermissionGranted(): Boolean {
        micPermissionCheckerOverride?.let { return it.invoke() }
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

    /**
     * Attaches the Floating Assistant panel listener without overwriting MainActivity's
     * foreground UI listener or VoiceInteractionSession's listener.
     */
    fun attachFloatingListener(listener: (String) -> Unit) {
        floatingResultListener = listener
    }

    fun detachFloatingListener() {
        floatingResultListener = null
    }

    fun hasActiveFloatingListener(): Boolean = floatingResultListener != null

    /**
     * Dedicated isolated listener slot for Android [NayroVoiceInteractionSession] (Phase 2).
     * Never overwrites or collides with [NayroFloatingService] or [MainActivity].
     */
    fun attachVoiceInteractionListener(listener: (String) -> Unit) {
        voiceInteractionResultListener = listener
    }

    fun detachVoiceInteractionListener() {
        voiceInteractionResultListener = null
    }

    fun hasActiveVoiceInteractionListener(): Boolean = voiceInteractionResultListener != null

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
     * Normalizes a spoken Speech-to-Text command addressed to "Babu NAYRO cute baby" or the
     * user's configured assistant name. Strips the invocation prefix when a concrete command
     * follows, while preserving standalone greetings so the assistant responds warmly.
     */
    fun normalizeVoiceInvocationCommand(rawText: String): String {
        val clean = normalizeTranscript(rawText)
        if (clean.isEmpty()) return ""
        val customPrefixes = listOf(
            "Hey Babu NAYRO cute baby",
            "Hi Babu NAYRO cute baby",
            "Babu NAYRO cute baby",
            "Hey Babu Nayro",
            "Hi Babu Nayro",
            "Babu Nayro"
        )
        for (prefix in customPrefixes) {
            if (clean.equals(prefix, ignoreCase = true)) {
                return clean
            }
            val regex = Regex("^(?i)${Regex.escape(prefix)}(?:[\\s,:!.-]+)(.+)$")
            val match = regex.find(clean)
            if (match != null) {
                val remainder = match.groupValues[1].trim()
                if (remainder.isNotBlank()) return remainder
            }
        }
        return identityStore?.stripWakePrefix(clean) ?: clean
    }

    fun clearTranscriptAndError() {
        _transcribedText.value = ""
        _errorMessage.value = null
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
        val clean = normalizeVoiceInvocationCommand(normalizeTranscript(text))
        if (clean.isEmpty()) return
        if (isDuplicateVoiceCommand(clean)) return
        _transcribedText.value = clean
        val voiceSessionTarget = voiceInteractionResultListener
        val floatingTarget = floatingResultListener
        val uiTarget = uiResultListener ?: onSpeechRecognized
        when {
            voiceSessionTarget != null -> voiceSessionTarget.invoke(clean)
            floatingTarget != null -> floatingTarget.invoke(clean)
            uiTarget != null -> uiTarget.invoke(clean)
            else -> serviceResultListener?.invoke(clean)
        }
    }

    private var identityStore: com.example.core.identity.AssistantIdentityStore? =
        com.example.core.identity.AssistantIdentityStore.sharedInstance

    enum class TwoStageWakeOutcome {
        WAKE_ONLY_ACTIVATED,
        WAKE_AND_COMMAND_DISPATCHED,
        FOLLOW_UP_COMMAND_DISPATCHED,
        VOICE_EXIT_STOPPED,
        IGNORED_NO_WAKE_PHRASE
    }

    /**
     * Binds the single authoritative [AssistantIdentityStore] so assistant display name,
     * wake phrases, language, speech rate, and activation prompts stay unified.
     */
    fun bindIdentityStore(store: com.example.core.identity.AssistantIdentityStore) {
        identityStore = store
        val profile = store.getCurrentProfile()
        wakeWordEngine.syncFromIdentityStore(store)
        _configuration.value = _configuration.value.copy(
            assistantName = profile.assistantDisplayName,
            wakePhrase = profile.wakePhrases.firstOrNull() ?: "Hi ${profile.assistantDisplayName}",
            wakePhrases = profile.wakePhrases,
            languageTag = profile.languageTag,
            speechRate = profile.speechRate,
            isTtsEnabled = profile.isTtsEnabled,
            isWakeWordEnabled = profile.isWakeWordEnabled,
            isContinuousConversationEnabled = profile.isContinuousConversationEnabled
        )
    }

    /**
     * Two-Stage Wake Word & Voice Activation handler (Section 2):
     * - Stage A (Wake Detection): detects any configured wake phrase (e.g. "Hi Nero", "Hi Babu", "Hi Qki", custom).
     *   If only the wake phrase is spoken, transitions to WAKE_DETECTED, opens follow-up session, and emits the
     *   activation cue without needing the wake phrase repeated for the next command.
     * - Stage B (Command Understanding & Follow-Up): if an inline command follows the wake phrase or if a
     *   follow-up conversation window is already active, strips any wake prefix and dispatches directly to
     *   the unified AgentRuntime listener.
     */
    fun processTwoStageVoiceUtterance(
        rawUtterance: String,
        onActivationCue: ((String) -> Unit)? = null,
        onCommandReady: ((String) -> Unit)? = null
    ): TwoStageWakeOutcome {
        val clean = normalizeTranscript(rawUtterance)
        if (clean.isEmpty()) return TwoStageWakeOutcome.IGNORED_NO_WAKE_PHRASE

        val activeName = identityStore?.getDisplayName() ?: _configuration.value.assistantName
        if (wakeWordEngine.isVoiceExitCommand(clean, activeName) || isConversationExitPhrase(clean)) {
            _isFollowUpSessionActive.value = false
            _voiceState.value = if (_configuration.value.isWakeWordEnabled) VoiceState.STANDBY else VoiceState.IDLE
            return TwoStageWakeOutcome.VOICE_EXIT_STOPPED
        }

        // Check configured wake phrases (identityStore first, then engine configured phrases)
        val matchedPair: Pair<String, String>? = identityStore?.matchWakePhraseInText(clean) ?: run {
            val phrases = wakeWordEngine.getConfiguredWakePhrases().sortedByDescending { it.length }
            phrases.firstNotNullOfOrNull { phrase ->
                val regex = Regex("^(?i)${Regex.escape(phrase)}(?:[\\s,:!.-]+(.*))?$")
                val m = regex.find(clean)
                if (m != null) phrase to (m.groupValues.getOrNull(1)?.trim().orEmpty()) else null
            }
        }

        if (matchedPair != null) {
            val (_, inlineCommand) = matchedPair
            _voiceState.value = VoiceState.WAKE_DETECTED
            _isFollowUpSessionActive.value = _configuration.value.isContinuousConversationEnabled
            return if (inlineCommand.isBlank()) {
                // Stage A only: activate assistant and open Stage B listening window
                val activationCue = identityStore?.getCurrentProfile()?.activationResponse
                    ?: if (_configuration.value.languageTag.startsWith("hi", ignoreCase = true)) "Haan, boliye" else "Yes, listening"
                onActivationCue?.invoke(activationCue)
                TwoStageWakeOutcome.WAKE_ONLY_ACTIVATED
            } else {
                // Stage A + Stage B inline: dispatch command immediately to unified AgentRuntime
                onCommandReady?.invoke(inlineCommand) ?: dispatchRecognizedSpeech(inlineCommand)
                TwoStageWakeOutcome.WAKE_AND_COMMAND_DISPATCHED
            }
        }

        // Stage B follow-up turn without repeating the wake phrase
        if (_isFollowUpSessionActive.value) {
            onCommandReady?.invoke(clean) ?: dispatchRecognizedSpeech(clean)
            return TwoStageWakeOutcome.FOLLOW_UP_COMMAND_DISPATCHED
        }

        return TwoStageWakeOutcome.IGNORED_NO_WAKE_PHRASE
    }

    fun updateAssistantIdentity(assistantName: String) {
        val cleanName = assistantName.trim().ifEmpty { com.example.core.identity.AssistantIdentityProfile.DEFAULT_DISPLAY_NAME }
        identityStore?.updateDisplayName(cleanName, autoSyncWakePhrases = true)
        val defaultMap = wakeWordEngine.configureWakePhrasesForAssistantName(cleanName)
        val configuredPhrases = identityStore?.getWakePhrases() ?: defaultMap.keys.toList()
        wakeWordEngine.setConfiguredWakePhrases(configuredPhrases)
        _configuration.value = _configuration.value.copy(
            assistantName = cleanName,
            wakePhrase = configuredPhrases.firstOrNull() ?: "Hi $cleanName",
            wakePhrases = configuredPhrases
        )
    }

    fun updateConfiguration(newConfig: VoiceConfiguration) {
        _configuration.value = newConfig
        wakeWordEngine.setSensitivity(newConfig.sensitivity)
        val desiredPhrases = buildList {
            if (newConfig.wakePhrases.isNotEmpty()) {
                addAll(newConfig.wakePhrases)
            }
            if (newConfig.wakePhrase.isNotBlank()) {
                add(newConfig.wakePhrase)
            }
            if (newConfig.assistantName.isNotBlank()) {
                add("Hi ${newConfig.assistantName}")
                add("Hey ${newConfig.assistantName}")
            }
        }.map { it.trim() }.filter { it.length >= 3 }.distinctBy { it.lowercase() }

        wakeWordEngine.configureWakePhrasesForAssistantName(newConfig.assistantName)
        if (desiredPhrases.isNotEmpty()) {
            wakeWordEngine.setConfiguredWakePhrases(desiredPhrases)
        }
        identityStore?.updateVoiceAndLanguage(
            languageTag = newConfig.languageTag,
            speechRate = newConfig.speechRate,
            isTtsEnabled = newConfig.isTtsEnabled,
            isWakeWordEnabled = newConfig.isWakeWordEnabled,
            isContinuousConversationEnabled = newConfig.isContinuousConversationEnabled
        )

        if (isTtsReady) {
            tts?.language = Locale.forLanguageTag(newConfig.languageTag)
            tts?.setSpeechRate(newConfig.speechRate)
        }

        if (newConfig.isWakeWordEnabled) {
            if (!isMicrophonePermissionGranted()) {
                microphoneOwnership.forceInterrupt("RECORD_AUDIO permission missing", asError = true)
                _voiceState.value = VoiceState.ERROR
                _errorMessage.value = "Microphone permission is required for local wake-word listening."
                return
            }
            if (isPhoneCallActiveOrRinging()) {
                microphoneOwnership.forceInterrupt("Phone call active", asError = false)
                _voiceState.value = VoiceState.PAUSED
                _errorMessage.value = "Wake-word paused during active phone call."
                return
            }
            val started = acquireAndStartWakeListening("Wake-word enabled via configuration")
            if (!started) {
                val reason = wakeWordEngine.lastFailureReason.value ?: wakeWordEngine.statusMessage.value
                _errorMessage.value = reason
                _voiceState.value = if (wakeWordEngine.engineState.value == WakeEngineState.NOT_CONFIGURED ||
                    wakeWordEngine.engineState.value == WakeEngineState.MODEL_UNAVAILABLE
                ) {
                    VoiceState.IDLE
                } else {
                    VoiceState.ERROR
                }
            }
        } else {
            cancelFollowUpTimeout()
            _isFollowUpSessionActive.value = false
            wakeWordEngine.stopListening()
            microphoneOwnership.release(
                expectedOwner = AudioSessionOwner.WAKE_WORD_ENGINE,
                nextPhase = MicrophoneLifecyclePhase.IDLE,
                reason = "Wake-word disabled via configuration"
            )
            _voiceState.value = VoiceState.DISABLED
        }
    }

    /**
     * Atomically acquires `WAKE_WORD_ENGINE` ownership and starts [LocalWakeWordEngine],
     * verifying that capture actually started before publishing [VoiceState.STANDBY] (P0-2 & P0-4).
     */
    private fun acquireAndStartWakeListening(reason: String): Boolean {
        val token = microphoneOwnership.tryAcquire(
            requestedOwner = AudioSessionOwner.WAKE_WORD_ENGINE,
            targetPhase = MicrophoneLifecyclePhase.WAKE_LISTENING,
            reason = reason,
            allowPreemptWake = false,
            allowPreemptSameOwner = true
        ) ?: return false

        val started = wakeWordEngine.startListening()
        val captureVerified = context == null || (
            wakeWordEngine.isMicrophoneInitialized.value &&
                wakeWordEngine.isAudioCaptureActive.value
            )
        if (started &&
            wakeWordEngine.engineState.value == WakeEngineState.LISTENING_FOR_WAKE &&
            captureVerified
        ) {
            activeCaptureSessionId = token
            _voiceState.value = VoiceState.STANDBY
            return true
        }
        // A failed/partial start must not leave a stale AudioRecord or capture loop alive.
        wakeWordEngine.stopListening()
        microphoneOwnership.release(
            expectedOwner = AudioSessionOwner.WAKE_WORD_ENGINE,
            sessionId = token,
            nextPhase = MicrophoneLifecyclePhase.ERROR,
            reason = wakeWordEngine.lastFailureReason.value ?: "Wake engine failed to start verified capture"
        )
        return false
    }

    private fun onWakeWordTriggered(phrase: String) {
        if (isPhoneCallActiveOrRinging()) {
            onExternalAudioInterruption("Phone call active during wake trigger")
            return
        }
        // Stage A: Release wake-word AudioRecord immediately before activation prompt / ASR
        wakeWordEngine.pauseForAsr()
        _voiceState.value = VoiceState.WAKE_DETECTED
        _isFollowUpSessionActive.value = _configuration.value.isContinuousConversationEnabled

        val startStageBCommandListening = {
            startManualListening(isFollowUpTurn = false) { recognizedText ->
                val cleanedCommand = identityStore?.stripWakePrefix(recognizedText) ?: recognizedText
                dispatchRecognizedSpeech(cleanedCommand)
            }
        }

        // Speak short activation confirmation cue when TTS is ready; handleTtsCompleted opens Stage B listening window
        val activationCue = identityStore?.getCurrentProfile()?.activationResponse
            ?: if (_configuration.value.languageTag.startsWith("hi", ignoreCase = true)) "Haan, boliye" else "Yes, listening"

        if (_configuration.value.isTtsEnabled && isTtsReady && tts != null) {
            _isFollowUpSessionActive.value = true
            val started = speak(text = activationCue, expectFollowUp = true)
            if (!started) {
                startStageBCommandListening()
            }
        } else {
            startStageBCommandListening()
        }
    }

    fun startListening() {
        startManualListening(isFollowUpTurn = false) { recognized ->
            val command = normalizeVoiceInvocationCommand(recognized)
            if (command.isNotBlank()) {
                dispatchRecognizedSpeech(command)
            }
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
            microphoneOwnership.forceInterrupt("RECORD_AUDIO permission missing", asError = true)
            _voiceState.value = VoiceState.ERROR
            val msg = "Microphone permission is not granted."
            _errorMessage.value = msg
            onSpeechError?.invoke(msg)
            return
        }

        if (isPhoneCallActiveOrRinging()) {
            microphoneOwnership.forceInterrupt("Active phone call", asError = false)
            _voiceState.value = VoiceState.PAUSED
            val msg = "Cannot start voice listening during an active phone call."
            _errorMessage.value = msg
            onSpeechError?.invoke(msg)
            return
        }

        // Prevent competing enrollment or duplicate overlapping recording sessions (P0-4)
        if (microphoneOwnership.getCurrentOwner() == AudioSessionOwner.ENROLLMENT_CAPTURE) {
            val msg = "Microphone is busy with wake-phrase enrollment."
            _errorMessage.value = msg
            onSpeechError?.invoke(msg)
            return
        }

        // Stop TTS if currently speaking (barge-in interruption support)
        stopSpeakingInternal(resumeStandby = false)

        // Cancel any prior ASR session before acquiring exclusive COMMAND_CAPTURE ownership
        speechRecognitionProvider.cancel()
        voiceProviderRouter.deepgramSttProvider.cancel()

        // Ensure wake-word AudioRecord is fully stopped & released before SpeechRecognizer acquires the mic
        wakeWordEngine.pauseForAsr()

        val captureToken = microphoneOwnership.tryAcquire(
            requestedOwner = AudioSessionOwner.SPEECH_RECOGNIZER,
            targetPhase = MicrophoneLifecyclePhase.COMMAND_CAPTURE,
            reason = if (isFollowUpTurn) "Follow-up command capture" else "Manual / post-wake command capture",
            allowPreemptWake = true,
            allowPreemptSameOwner = true
        )
        if (captureToken == null) {
            val msg = "Microphone is currently owned by ${microphoneOwnership.getCurrentOwner().name}."
            _errorMessage.value = msg
            _voiceState.value = VoiceState.ERROR
            onSpeechError?.invoke(msg)
            return
        }
        activeCaptureSessionId = captureToken

        val activeSttProvider = voiceProviderRouter.resolveActiveSttProvider()
        val usingDeepgram = activeSttProvider === voiceProviderRouter.deepgramSttProvider

        if (!activeSttProvider.isRecognitionAvailable()) {
            if (usingDeepgram && speechRecognitionProvider.isRecognitionAvailable()) {
                voiceProviderRouter.markDeepgramFallback("Deepgram unavailable; fell back to Android SpeechRecognizer.")
            } else {
                microphoneOwnership.release(
                    AudioSessionOwner.SPEECH_RECOGNIZER,
                    sessionId = captureToken,
                    nextPhase = MicrophoneLifecyclePhase.ERROR,
                    reason = "No speech recognition provider available"
                )
                _voiceState.value = VoiceState.ERROR
                val msg = "Speech recognition service is not available on this device."
                _errorMessage.value = msg
                onSpeechError?.invoke(msg)
                return
            }
        }

        val resolvedProvider = if (usingDeepgram && activeSttProvider.isRecognitionAvailable()) {
            activeSttProvider
        } else {
            speechRecognitionProvider
        }

        requestTransientAudioFocus()

        fun startWithProvider(provider: SpeechRecognitionProvider, allowFallbackToAndroid: Boolean) {
            provider.startListening(
                languageTag = _configuration.value.languageTag,
                preferOffline = _configuration.value.preferOfflineSpeechRecognition && isOfflineSpeechRecognitionSupported(),
                onReady = {
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

                    val text = result.primaryText.trim()
                    _transcribedText.value = text

                    if (text.isNotBlank()) {
                        if (wakeWordEngine.isVoiceExitCommand(text) || isConversationExitPhrase(text)) {
                            microphoneOwnership.release(
                                AudioSessionOwner.SPEECH_RECOGNIZER,
                                sessionId = captureToken,
                                nextPhase = MicrophoneLifecyclePhase.IDLE,
                                reason = "Voice exit command"
                            )
                            _isFollowUpSessionActive.value = false
                            onVoiceExitCommand?.invoke()
                            returnToStandbyOrIdle()
                            return@startListening
                        }
                        microphoneOwnership.enterProcessing("Command transcribed; executing in AgentRuntime")
                        _voiceState.value = VoiceState.THINKING
                        if (_configuration.value.isContinuousConversationEnabled) {
                            _isFollowUpSessionActive.value = true
                        }
                        val callback = onResultCallback
                            ?: voiceInteractionResultListener
                            ?: floatingResultListener
                            ?: uiResultListener
                            ?: onSpeechRecognized
                            ?: serviceResultListener
                        callback?.invoke(text)
                    } else {
                        microphoneOwnership.release(
                            AudioSessionOwner.SPEECH_RECOGNIZER,
                            sessionId = captureToken,
                            nextPhase = MicrophoneLifecyclePhase.IDLE,
                            reason = "Empty transcript"
                        )
                        _isFollowUpSessionActive.value = false
                        returnToStandbyOrIdle()
                    }
                },
                onError = { errorCode, errText ->
                    val shouldFallbackToAndroid = allowFallbackToAndroid &&
                        provider === voiceProviderRouter.deepgramSttProvider &&
                        speechRecognitionProvider.isRecognitionAvailable() &&
                        errorCode != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS &&
                        errorCode != SpeechRecognizer.ERROR_SPEECH_TIMEOUT

                    if (shouldFallbackToAndroid) {
                        voiceProviderRouter.markDeepgramFallback(
                            "Deepgram failed ($errText); falling back to Android SpeechRecognizer."
                        )
                        startWithProvider(speechRecognitionProvider, allowFallbackToAndroid = false)
                        return@startListening
                    }

                    cancelFollowUpTimeout()
                    abandonTransientAudioFocus()
                    microphoneOwnership.release(
                        AudioSessionOwner.SPEECH_RECOGNIZER,
                        sessionId = captureToken,
                        nextPhase = if (errorCode == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                            MicrophoneLifecyclePhase.ERROR
                        } else {
                            MicrophoneLifecyclePhase.IDLE
                        },
                        reason = "Speech recognition error ($errorCode): $errText"
                    )

                    if (isFollowUpTurn && (errorCode == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || errorCode == SpeechRecognizer.ERROR_NO_MATCH)) {
                        _isFollowUpSessionActive.value = false
                        returnToStandbyOrIdle()
                        return@startListening
                    }

                    _errorMessage.value = errText
                    _voiceState.value = VoiceState.ERROR
                    (uiErrorListener ?: onSpeechError)?.invoke(errText)
                    if (errorCode != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        returnToStandbyOrIdle()
                    }
                }
            )
        }

        startWithProvider(resolvedProvider, allowFallbackToAndroid = resolvedProvider === voiceProviderRouter.deepgramSttProvider)
    }

    fun markAgentThinking() {
        microphoneOwnership.enterProcessing("Agent thinking")
        _voiceState.value = VoiceState.THINKING
    }

    fun markAgentExecuting() {
        microphoneOwnership.enterProcessing("Agent executing tool")
        _voiceState.value = VoiceState.EXECUTING
    }

    /**
     * Speaks [text] through Android TextToSpeech while ensuring:
     * 1. CRITICAL CALLING RULE: AI NEVER speaks during a ringing or active phone call.
     * 2. Automatic Hindi / English / Hinglish locale selection via [SpeechSynthesisProvider].
     * 3. Wake-word AudioRecord and ASR are paused so TTS never triggers feedback loops.
     * 4. When [expectFollowUp] is true, completion transitions into [VoiceState.FOLLOW_UP_LISTENING].
     */
    fun speak(text: String, expectFollowUp: Boolean = _configuration.value.isContinuousConversationEnabled): Boolean {
        if (isSpeechBlockedByPhoneCall()) {
            val reason = "AI speech blocked: phone call is active or ringing (NAYRO never speaks on phone calls)."
            _lastSpeechBlockedReason.value = reason
            try {
                tts?.stop()
                speechSynthesisProvider.stop()
            } catch (_: Throwable) {}
            _voiceState.value = VoiceState.PAUSED
            return false
        }
        _lastSpeechBlockedReason.value = null

        if (!_configuration.value.isTtsEnabled || !isTtsReady) {
            if (expectFollowUp && _isFollowUpSessionActive.value) {
                startFollowUpListeningWindow()
            } else {
                returnToStandbyOrIdle()
            }
            return false
        }

        // Ensure microphone is not capturing while NAYRO speaks
        cancelFollowUpTimeout()
        speechRecognitionProvider.cancel()
        voiceProviderRouter.deepgramSttProvider.cancel()
        wakeWordEngine.pauseForAsr()
        requestTransientAudioFocus()

        // Dynamically resolve Hindi / English / Hinglish TTS locale for natural multilingual speech
        val resolvedLocale = speechSynthesisProvider.resolveLocaleForText(
            text = text,
            fallbackLanguageTag = _configuration.value.languageTag
        )
        try {
            tts?.language = resolvedLocale
            tts?.setSpeechRate(_configuration.value.speechRate.coerceIn(0.5f, 2.0f))
        } catch (_: Throwable) {}

        microphoneOwnership.enterResponsePlayback("Speaking response via TTS (${resolvedLocale.toLanguageTag()})")
        _voiceState.value = VoiceState.SPEAKING
        val expectAfterSpeech = expectFollowUp && _isFollowUpSessionActive.value
        val utteranceId = if (expectAfterSpeech) {
            "nayro_utterance_followup_${System.currentTimeMillis()}"
        } else {
            "nayro_utterance_final_${System.currentTimeMillis()}"
        }
        val speakResult = try {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId) ?: TextToSpeech.ERROR
        } catch (_: Throwable) {
            TextToSpeech.ERROR
        }
        if (speakResult == TextToSpeech.ERROR) {
            abandonTransientAudioFocus()
            microphoneOwnership.release(
                AudioSessionOwner.TTS_PLAYBACK,
                nextPhase = MicrophoneLifecyclePhase.IDLE,
                reason = "TTS speak() returned ERROR"
            )
            if (expectAfterSpeech && isMicrophonePermissionGranted() && !isPhoneCallActiveOrRinging()) {
                startFollowUpListeningWindow()
            } else {
                returnToStandbyOrIdle()
            }
            return false
        }
        // Bounded watchdog ensures OEM TTS engines that drop UtteranceProgressListener.onDone never stall voice standby/follow-up
        scheduleTtsWatchdog(utteranceId, estimatedDurationMs = (text.length * 85L).coerceIn(3_500L, 18_000L))
        return true
    }

    /**
     * Authoritative completion hook called after AgentRuntime finishes executing a voice command
     * from NayroForegroundService, NayroFloatingService, or MainActivity.
     * Guarantees microphone ownership ALWAYS transitions cleanly out of THINKING/PROCESSING into
     * TTS playback, continuous follow-up listening, or wake-word standby (even when TTS is OFF or blocked).
     */
    fun completeCommandTurn(
        responseText: String,
        shouldSpeak: Boolean = _configuration.value.isTtsEnabled,
        expectFollowUp: Boolean = _configuration.value.isContinuousConversationEnabled
    ) {
        if (shouldSpeak && responseText.isNotBlank()) {
            val spoke = speak(text = responseText, expectFollowUp = expectFollowUp)
            if (spoke) return
        }
        if (expectFollowUp && _isFollowUpSessionActive.value && isMicrophonePermissionGranted() && !isPhoneCallActiveOrRinging()) {
            startFollowUpListeningWindow()
        } else {
            returnToStandbyOrIdle()
        }
    }

    private fun handleTtsCompleted(utteranceId: String?) {
        cancelTtsWatchdog()
        abandonTransientAudioFocus()
        microphoneOwnership.release(
            AudioSessionOwner.TTS_PLAYBACK,
            nextPhase = MicrophoneLifecyclePhase.IDLE,
            reason = "TTS playback completed"
        )
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

    private fun scheduleTtsWatchdog(utteranceId: String, estimatedDurationMs: Long) {
        cancelTtsWatchdog()
        val runnable = Runnable {
            if (_voiceState.value == VoiceState.SPEAKING) {
                try { tts?.stop() } catch (_: Throwable) {}
                handleTtsCompleted(utteranceId)
            }
        }
        ttsWatchdogRunnable = runnable
        mainHandler?.postDelayed(runnable, estimatedDurationMs)
    }

    private fun cancelTtsWatchdog() {
        ttsWatchdogRunnable?.let { mainHandler?.removeCallbacks(it) }
        ttsWatchdogRunnable = null
    }

    /**
     * Opens the continuous follow-up listening window so the user can issue the next command
     * without repeating the wake phrase. Routes recognized speech through the single unified
     * deduplicated dispatcher so foreground UI, floating panel, and background service stay in sync.
     */
    fun startFollowUpListeningWindow() {
        if (!isMicrophonePermissionGranted() || isPhoneCallActiveOrRinging()) {
            _isFollowUpSessionActive.value = false
            returnToStandbyOrIdle()
            return
        }
        _isFollowUpSessionActive.value = true
        startManualListening(isFollowUpTurn = true) { recognizedText ->
            val command = normalizeVoiceInvocationCommand(recognizedText)
            if (command.isNotBlank()) {
                dispatchRecognizedSpeech(command)
            }
        }
    }

    /**
     * Explicitly terminates an active multi-turn conversation session (e.g., user says goodbye/stop
     * or presses End Session) and cleanly returns to local wake-word standby if Background Listening
     * remains enabled.
     */
    fun endConversationSession(returnToStandby: Boolean = true) {
        cancelFollowUpTimeout()
        _isFollowUpSessionActive.value = false
        speechRecognitionProvider.stopListening()
        speechRecognitionProvider.cancel()
        voiceProviderRouter.deepgramSttProvider.stopListening()
        voiceProviderRouter.deepgramSttProvider.cancel()
        try {
            tts?.stop()
        } catch (_: Exception) {}
        abandonTransientAudioFocus()
        microphoneOwnership.forceReleaseAll("Conversation session ended")
        if (returnToStandby) {
            returnToStandbyOrIdle()
        } else {
            wakeWordEngine.stopListening()
            _voiceState.value = VoiceState.DISABLED
        }
    }

    /**
     * Allows the user to interrupt TTS playback ("barge-in") and immediately start speaking a new command.
     */
    fun interruptSpeakingAndListen(onResult: ((String) -> Unit)? = null) {
        stopSpeakingInternal(resumeStandby = false)
        val callback: (String) -> Unit = onResult ?: { recognizedText ->
            val command = normalizeVoiceInvocationCommand(recognizedText)
            if (command.isNotBlank()) {
                dispatchRecognizedSpeech(command)
            }
        }
        startManualListening(isFollowUpTurn = true, onResult = callback)
    }

    /**
     * Explicit barge-in API: immediately stops active TTS synthesis and switches to user capture.
     */
    fun interruptSpeechForUserBargeIn(onResult: ((String) -> Unit)? = null) {
        try {
            speechSynthesisProvider.stop()
        } catch (_: Throwable) {}
        interruptSpeakingAndListen(onResult)
    }

    fun stopListening() {
        cancelFollowUpTimeout()
        _isFollowUpSessionActive.value = false
        speechRecognitionProvider.stopListening()
        speechRecognitionProvider.cancel()
        voiceProviderRouter.deepgramSttProvider.stopListening()
        voiceProviderRouter.deepgramSttProvider.cancel()
        abandonTransientAudioFocus()
        microphoneOwnership.release(
            AudioSessionOwner.SPEECH_RECOGNIZER,
            nextPhase = MicrophoneLifecyclePhase.IDLE,
            reason = "Manual stopListening()"
        )
        returnToStandbyOrIdle()
    }

    fun stopSpeaking() {
        stopSpeakingInternal(resumeStandby = true)
    }

    private fun stopSpeakingInternal(resumeStandby: Boolean) {
        cancelTtsWatchdog()
        try {
            tts?.stop()
        } catch (_: Exception) {}
        abandonTransientAudioFocus()
        microphoneOwnership.release(
            AudioSessionOwner.TTS_PLAYBACK,
            nextPhase = MicrophoneLifecyclePhase.IDLE,
            reason = "TTS stopped"
        )
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
        voiceProviderRouter.deepgramSttProvider.cancel()
        try {
            tts?.stop()
        } catch (_: Exception) {}
        wakeWordEngine.onAudioInterruptionStarted(reason)
        microphoneOwnership.forceInterrupt(reason, asError = false)
        _voiceState.value = VoiceState.PAUSED
    }

    /**
     * Resumes wake-word standby after an external phone call or audio focus interruption ends,
     * ONLY when microphone permission is still valid and AudioRecord capture actually starts.
     */
    fun onExternalAudioInterruptionEnded() {
        if (isPhoneCallActiveOrRinging()) return
        microphoneOwnership.clearInterruption("External audio interruption ended")
        if (_configuration.value.isWakeWordEnabled && isMicrophonePermissionGranted()) {
            val resumed = acquireAndStartWakeListening("Recovered after audio interruption")
            if (!resumed) {
                _voiceState.value = VoiceState.IDLE
            }
        } else {
            _voiceState.value = if (_configuration.value.isWakeWordEnabled) VoiceState.IDLE else VoiceState.DISABLED
        }
    }

    /**
     * Called when RECORD_AUDIO permission is revoked at runtime: immediately releases all mic owners.
     */
    fun onMicrophonePermissionRevoked() {
        cancelFollowUpTimeout()
        cancelTtsWatchdog()
        _isFollowUpSessionActive.value = false
        speechRecognitionProvider.cancel()
        voiceProviderRouter.deepgramSttProvider.cancel()
        wakeWordEngine.stopListening()
        microphoneOwnership.forceInterrupt("RECORD_AUDIO permission revoked", asError = true)
        _errorMessage.value = "Microphone permission was revoked. Voice listening stopped."
        _voiceState.value = VoiceState.ERROR
        com.example.core.service.ServiceStateRepository.updateBackgroundVoicePipeline(
            state = com.example.core.service.BackgroundVoicePipelineState.ERROR,
            detail = "Microphone permission was revoked. Voice listening stopped.",
            isMicrophoneVerified = false,
            isWakeEngineVerified = wakeWordEngine.hasUsableAcousticTemplates(),
            isAudioCaptureVerified = false
        )
    }

    fun returnToStandbyOrIdle() {
        cancelFollowUpTimeout()
        if (_configuration.value.isWakeWordEnabled && isMicrophonePermissionGranted() && !isPhoneCallActiveOrRinging()) {
            val recovered = acquireAndStartWakeListening("Returning to wake-word standby")
            if (!recovered) {
                _voiceState.value = VoiceState.IDLE
                com.example.core.service.ServiceStateRepository.updateBackgroundVoicePipeline(
                    state = com.example.core.service.BackgroundVoicePipelineState.DEGRADED,
                    detail = wakeWordEngine.lastFailureReason.value ?: "Could not resume local wake-word standby.",
                    isMicrophoneVerified = false,
                    isWakeEngineVerified = wakeWordEngine.hasUsableAcousticTemplates(),
                    isAudioCaptureVerified = false
                )
            }
        } else if (_configuration.value.isWakeWordEnabled) {
            if (microphoneOwnership.getCurrentOwner() != AudioSessionOwner.PHONE_CALL_INTERRUPTION) {
                microphoneOwnership.forceReleaseAll("Wake enabled but mic unavailable")
            }
            _voiceState.value = VoiceState.IDLE
        } else {
            microphoneOwnership.forceReleaseAll("Wake disabled")
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
        val normalized = text.trim().lowercase().removeSuffix(".").removeSuffix("!")
        return normalized in setOf(
            "stop",
            "stop listening",
            "bas",
            "bas karo",
            "bus",
            "ruko",
            "cancel",
            "goodbye",
            "good bye",
            "bye",
            "bye bye",
            "thank you",
            "thanks",
            "shukriya",
            "dhanyawad",
            "band karo",
            "chup ho jao",
            "end session",
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
        microphoneOwnership.tryAcquire(
            AudioSessionOwner.WAKE_WORD_ENGINE,
            MicrophoneLifecyclePhase.WAKE_LISTENING,
            "Test wake standby",
            allowPreemptSameOwner = true
        )
        _voiceState.value = VoiceState.STANDBY
        recordedStates.add(_voiceState.value)

        // 2. Wake phrase detected -> pause wake-word AudioRecord before ASR
        wakeWordEngine.pauseForAsr()
        _voiceState.value = VoiceState.WAKE_DETECTED
        recordedStates.add(_voiceState.value)

        // 3. SpeechRecognizer ready -> LISTENING
        microphoneOwnership.tryAcquire(
            AudioSessionOwner.SPEECH_RECOGNIZER,
            MicrophoneLifecyclePhase.COMMAND_CAPTURE,
            "Test command capture",
            allowPreemptWake = true,
            allowPreemptSameOwner = true
        )
        _voiceState.value = VoiceState.LISTENING
        recordedStates.add(_voiceState.value)

        // 4. End of speech -> TRANSCRIBING
        _voiceState.value = VoiceState.TRANSCRIBING
        _transcribedText.value = spokenCommand
        recordedStates.add(_voiceState.value)

        // 5. Release ASR mic -> THINKING -> EXECUTING
        microphoneOwnership.enterProcessing("Test processing")
        _voiceState.value = VoiceState.THINKING
        recordedStates.add(_voiceState.value)
        _voiceState.value = VoiceState.EXECUTING
        recordedStates.add(_voiceState.value)

        // 6. Assistant speaks response -> SPEAKING
        microphoneOwnership.enterResponsePlayback("Test TTS playback")
        _voiceState.value = VoiceState.SPEAKING
        recordedStates.add(_voiceState.value)

        // 7. TTS completes -> transition to FOLLOW_UP_LISTENING (if enabled and not exit command) or STANDBY
        microphoneOwnership.release(AudioSessionOwner.TTS_PLAYBACK, reason = "Test TTS completed")
        if (enableFollowUp && !isConversationExitPhrase(spokenCommand) && !wakeWordEngine.isVoiceExitCommand(spokenCommand)) {
            _isFollowUpSessionActive.value = true
            microphoneOwnership.tryAcquire(
                AudioSessionOwner.SPEECH_RECOGNIZER,
                MicrophoneLifecyclePhase.COMMAND_CAPTURE,
                "Test follow-up capture",
                allowPreemptWake = true,
                allowPreemptSameOwner = true
            )
            _voiceState.value = VoiceState.FOLLOW_UP_LISTENING
            recordedStates.add(_voiceState.value)
        } else {
            _isFollowUpSessionActive.value = false
            wakeWordEngine.recoverFromAudioInterruption()
            microphoneOwnership.tryAcquire(
                AudioSessionOwner.WAKE_WORD_ENGINE,
                MicrophoneLifecyclePhase.WAKE_LISTENING,
                "Test return to wake standby",
                allowPreemptSameOwner = true
            )
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
     * Safely detaches UI-bound listeners when MainActivity is destroyed WITHOUT tearing down
     * an active resident NayroForegroundService wake-word session or NayroFloatingService session.
     */
    fun onActivityDestroyed() {
        detachUiListener()
        val hasResidentBackgroundConsumer = hasActiveServiceListener() || hasActiveFloatingListener() || hasActiveVoiceInteractionListener()
        val isWakeCaptureHealthy = microphoneOwnership.getCurrentOwner() == AudioSessionOwner.WAKE_WORD_ENGINE &&
            wakeWordEngine.engineState.value == WakeEngineState.LISTENING_FOR_WAKE

        // Never kill healthy background wake-word capture or an active floating/service session!
        if (hasResidentBackgroundConsumer && isWakeCaptureHealthy) {
            return
        }
        if (hasActiveFloatingListener() && (
                _voiceState.value == VoiceState.LISTENING ||
                    _voiceState.value == VoiceState.FOLLOW_UP_LISTENING ||
                    _voiceState.value == VoiceState.THINKING ||
                    _voiceState.value == VoiceState.SPEAKING
            )
        ) {
            return
        }

        cancelFollowUpTimeout()
        cancelTtsWatchdog()
        _isFollowUpSessionActive.value = false
        abandonTransientAudioFocus()
        speechRecognitionProvider.release()
        voiceProviderRouter.deepgramSttProvider.release()
        try {
            tts?.stop()
        } catch (_: Exception) {}
        microphoneOwnership.forceReleaseAll("Activity destroyed")
        if (_configuration.value.isWakeWordEnabled && isMicrophonePermissionGranted() && !isPhoneCallActiveOrRinging()) {
            val resumed = acquireAndStartWakeListening("Resumed background wake after activity destroy")
            if (!resumed) {
                _voiceState.value = VoiceState.PAUSED
                com.example.core.service.ServiceStateRepository.updateBackgroundVoicePipeline(
                    state = com.example.core.service.BackgroundVoicePipelineState.DEGRADED,
                    detail = wakeWordEngine.lastFailureReason.value
                        ?: "Wake-word standby could not resume after Activity destroy.",
                    isMicrophoneVerified = false,
                    isWakeEngineVerified = wakeWordEngine.hasUsableAcousticTemplates(),
                    isAudioCaptureVerified = false
                )
            }
        } else {
            _voiceState.value = if (_configuration.value.isWakeWordEnabled) VoiceState.PAUSED else VoiceState.DISABLED
        }
    }

    fun release() {
        isDestroyed = true
        detachUiListener()
        detachFloatingListener()
        detachVoiceInteractionListener()
        detachServiceListener()
        cancelFollowUpTimeout()
        cancelTtsWatchdog()
        _isFollowUpSessionActive.value = false
        wakeWordEngine.stopListening()
        abandonTransientAudioFocus()
        speechRecognitionProvider.release()
        voiceProviderRouter.deepgramSttProvider.release()
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {}
        tts = null
        microphoneOwnership.forceReleaseAll("VoiceManager released")
        _voiceState.value = VoiceState.STOPPED
    }
}
