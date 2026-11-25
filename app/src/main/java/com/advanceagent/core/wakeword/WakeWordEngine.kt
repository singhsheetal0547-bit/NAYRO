package com.advanceagent.core.wakeword

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.advanceagent.core.data.SecurePreferences
import com.advanceagent.core.service.WakeWordForegroundService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Unified WakeWordEngine integrating Sherpa-ONNX Keyword Spotting (KWS),
 * [WakeWordTokenizer] BPE subword tokenization, and the foreground microphone
 * service lifecycle ([WakeWordForegroundService]) within NAYRO's [com.example.core.agent.UnifiedAgentRuntime].
 */
class WakeWordEngine(
    private val context: Context? = null,
    private val preferences: SecurePreferences = SecurePreferences(context),
    private val sherpaEngine: SherpaWakeWordEngine = SherpaWakeWordEngine(context = context),
    var onWakePhraseDetected: ((String) -> Unit)? = null
) {
    enum class LifecycleState {
        IDLE,
        INITIALIZED,
        FOREGROUND_LISTENING,
        WAKE_DETECTED,
        STOPPED,
        ERROR
    }

    private val _state = MutableStateFlow(LifecycleState.IDLE)
    val state: StateFlow<LifecycleState> = _state.asStateFlow()

    private val _lastDetectedKeyword = MutableStateFlow<String?>(null)
    val lastDetectedKeyword: StateFlow<String?> = _lastDetectedKeyword.asStateFlow()

    val tokenizer: WakeWordTokenizer by lazy {
        if (context != null) {
            WakeWordTokenizer(context.assets, "kws-model/tokens.txt")
        } else {
            WakeWordTokenizer(WakeWordTokenizer.DEFAULT_FALLBACK_VOCAB)
        }
    }

    /**
     * Initializes the offline Sherpa-ONNX KWS engine and tokenizes all active wake phrases
     * ("Hi Babu", "Hey Babu", "Hi Nayro" + user custom wake words) via [WakeWordTokenizer].
     */
    @Synchronized
    fun initialize(
        customPhrases: List<String>? = null,
        boostingScore: Float = preferences.wakeBoostingScore,
        triggerThreshold: Float = preferences.wakeTriggerThreshold
    ): Boolean {
        val activePhrases = customPhrases?.takeIf { it.isNotEmpty() }
            ?: preferences.getAllActiveWakeWords()
        val ok = sherpaEngine.initialize(
            activeWakeWords = activePhrases,
            boostingScore = boostingScore,
            triggerThreshold = triggerThreshold
        )
        _state.value = if (ok) LifecycleState.INITIALIZED else LifecycleState.ERROR
        return ok
    }

    /**
     * Builds the BPE-tokenized Sherpa-ONNX keywords specification string for the given phrases.
     */
    fun tokenizeWakePhrases(
        phrases: List<String> = preferences.getAllActiveWakeWords(),
        boostingScore: Float = preferences.wakeBoostingScore,
        triggerThreshold: Float = preferences.wakeTriggerThreshold
    ): String {
        return tokenizer.buildKeywordsSpec(
            phrases = phrases,
            boostingScore = boostingScore,
            triggerThreshold = triggerThreshold
        )
    }

    /**
     * Hot-reloads wake phrases into the active Sherpa-ONNX stream without restarting the app.
     */
    @Synchronized
    fun reloadWakeWords(
        phrases: List<String> = preferences.getAllActiveWakeWords(),
        boostingScore: Float = preferences.wakeBoostingScore,
        triggerThreshold: Float = preferences.wakeTriggerThreshold
    ) {
        sherpaEngine.reloadWakeWords(
            activeWakeWords = phrases,
            boostingScore = boostingScore,
            triggerThreshold = triggerThreshold
        )
    }

    /**
     * Processes a 16kHz mono 16-bit PCM audio frame and triggers [onWakePhraseDetected] when
     * a wake phrase exceeds the boosting/threshold gate outside the cooldown window.
     */
    @Synchronized
    fun processPcm16Frame(pcm16: ShortArray, length: Int = pcm16.size): String? {
        val detected = sherpaEngine.processPcm16(pcm16, length)
        if (!detected.isNullOrBlank()) {
            _lastDetectedKeyword.value = detected
            _state.value = LifecycleState.WAKE_DETECTED
            onWakePhraseDetected?.invoke(detected)
        }
        return detected
    }

    /**
     * Starts the foreground microphone service ([WakeWordForegroundService]) for continuous
     * offline wake-word detection.
     */
    fun startForegroundMicrophoneService(): Boolean {
        val ctx = context ?: run {
            _state.value = LifecycleState.FOREGROUND_LISTENING
            return true
        }
        return try {
            preferences.isMasterPowerOn = true
            val intent = Intent(ctx, WakeWordForegroundService::class.java).apply {
                action = WakeWordForegroundService.ACTION_START_LISTENING
            }
            ContextCompat.startForegroundService(ctx, intent)
            _state.value = LifecycleState.FOREGROUND_LISTENING
            true
        } catch (_: Exception) {
            _state.value = LifecycleState.ERROR
            false
        }
    }

    /**
     * Stops the foreground microphone wake-word service and releases KWS resources.
     */
    fun stopForegroundMicrophoneService() {
        val ctx = context
        if (ctx != null) {
            try {
                val stopIntent = Intent(ctx, WakeWordForegroundService::class.java).apply {
                    action = WakeWordForegroundService.ACTION_COMPLETE_SHUTDOWN
                }
                ctx.stopService(stopIntent)
            } catch (_: Exception) {
            }
        }
        release()
        _state.value = LifecycleState.STOPPED
    }

    @Synchronized
    fun release() {
        sherpaEngine.release()
    }
}
