package com.example.core.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Structured result emitted when Android SpeechRecognizer finishes converting speech to text.
 */
data class SpeechRecognitionResult(
    val primaryText: String,
    val alternatives: List<String> = emptyList(),
    val confidenceScore: Float = 0f,
    val languageTag: String = "en-IN",
    val isFinal: Boolean = true,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Independent Speech-to-Text (ASR) provider contract (Master Spec Section 3.5).
 * Converts spoken user audio into natural language text commands for the NAYRO AgentRuntime.
 */
interface SpeechRecognitionProvider {
    val isListening: StateFlow<Boolean>
    val partialTranscript: StateFlow<String>
    val lastResult: StateFlow<SpeechRecognitionResult?>
    val rmsDb: StateFlow<Float>
    val normalizedAudioLevel: StateFlow<Float>
    val lastError: StateFlow<String?>

    fun isRecognitionAvailable(): Boolean
    fun isOfflineRecognitionAvailable(): Boolean
    fun buildRecognizerIntent(
        languageTag: String = "en-IN",
        preferOffline: Boolean = false,
        maxResults: Int = 5,
        completeSilenceMs: Long = 1500L
    ): Intent

    fun startListening(
        languageTag: String = "en-IN",
        preferOffline: Boolean = false,
        onReady: (() -> Unit)? = null,
        onBeginningOfSpeech: (() -> Unit)? = null,
        onPartialText: ((String) -> Unit)? = null,
        onEndOfSpeech: (() -> Unit)? = null,
        onFinalResult: (SpeechRecognitionResult) -> Unit,
        onError: ((Int, String) -> Unit)? = null
    )

    fun stopListening()
    fun cancel()
    fun release()
}

/**
 * Production Android implementation of [SpeechRecognitionProvider] using [android.speech.SpeechRecognizer]
 * and [RecognizerIntent.ACTION_RECOGNIZE_SPEECH].
 *
 * Supports:
 * - Hindi (`hi-IN`), Indian English / Hinglish (`en-IN`), and US English (`en-US`)
 * - Real-time partial transcription streaming (`EXTRA_PARTIAL_RESULTS`)
 * - Confidence score extraction (`EXTRA_CONFIDENCE_SCORES`)
 * - Live microphone RMS dB and normalized `[0f..1f]` audio level telemetry
 * - On-device / offline speech recognition when supported on Android 12+ (API 31+)
 */
class AndroidSpeechRecognitionProvider(
    private val context: Context? = null
) : SpeechRecognitionProvider {

    private val _isListening = MutableStateFlow(false)
    override val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _partialTranscript = MutableStateFlow("")
    override val partialTranscript: StateFlow<String> = _partialTranscript.asStateFlow()

    private val _lastResult = MutableStateFlow<SpeechRecognitionResult?>(null)
    override val lastResult: StateFlow<SpeechRecognitionResult?> = _lastResult.asStateFlow()

    private val _rmsDb = MutableStateFlow(0f)
    override val rmsDb: StateFlow<Float> = _rmsDb.asStateFlow()

    private val _normalizedAudioLevel = MutableStateFlow(0f)
    override val normalizedAudioLevel: StateFlow<Float> = _normalizedAudioLevel.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    override val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var speechRecognizer: SpeechRecognizer? = null
    private var activeLanguageTag: String = "en-IN"

    private val mainHandler: Handler? = try {
        val looper = Looper.getMainLooper()
        if (looper != null) Handler(looper) else null
    } catch (_: Throwable) {
        null
    }

    private var onReadyCallback: (() -> Unit)? = null
    private var onBeginningCallback: (() -> Unit)? = null
    private var onPartialCallback: ((String) -> Unit)? = null
    private var onEndCallback: (() -> Unit)? = null
    private var onFinalResultCallback: ((SpeechRecognitionResult) -> Unit)? = null
    private var onErrorCallback: ((Int, String) -> Unit)? = null

    private fun postToMain(block: () -> Unit) {
        val handler = mainHandler
        if (handler != null) {
            try {
                handler.post(block)
                return
            } catch (_: Throwable) {}
        }
        block()
    }

    override fun isRecognitionAvailable(): Boolean {
        val ctx = context ?: return false
        return try {
            SpeechRecognizer.isRecognitionAvailable(ctx)
        } catch (_: Throwable) {
            false
        }
    }

    override fun isOfflineRecognitionAvailable(): Boolean {
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

    override fun buildRecognizerIntent(
        languageTag: String,
        preferOffline: Boolean,
        maxResults: Int,
        completeSilenceMs: Long
    ): Intent {
        val cleanLang = languageTag.ifBlank { Locale.getDefault().toLanguageTag().ifBlank { "en-IN" } }
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, cleanLang)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, cleanLang)
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
            // Enable automatic Hindi / English / Hinglish switching on Android 14+ (API 34+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                putStringArrayListExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES,
                    arrayListOf("hi-IN", "en-IN", "en-US")
                )
            }
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, maxResults.coerceIn(1, 5))
            putExtra(RecognizerIntent.EXTRA_CONFIDENCE_SCORES, true)
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                completeSilenceMs.coerceIn(800L, 5000L)
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                (completeSilenceMs * 2 / 3).coerceIn(600L, 4000L)
            )
            if (preferOffline) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }
    }

    override fun startListening(
        languageTag: String,
        preferOffline: Boolean,
        onReady: (() -> Unit)?,
        onBeginningOfSpeech: (() -> Unit)?,
        onPartialText: ((String) -> Unit)?,
        onEndOfSpeech: (() -> Unit)?,
        onFinalResult: (SpeechRecognitionResult) -> Unit,
        onError: ((Int, String) -> Unit)?
    ) {
        activeLanguageTag = languageTag.ifBlank { "en-IN" }
        onReadyCallback = onReady
        onBeginningCallback = onBeginningOfSpeech
        onPartialCallback = onPartialText
        onEndCallback = onEndOfSpeech
        onFinalResultCallback = onFinalResult
        onErrorCallback = onError

        _lastError.value = null
        _partialTranscript.value = ""
        _rmsDb.value = 0f
        _normalizedAudioLevel.value = 0f

        val ctx = context ?: run {
            val msg = "Android Context is not attached for SpeechRecognizer."
            _lastError.value = msg
            _isListening.value = false
            onError?.invoke(SpeechRecognizer.ERROR_CLIENT, msg)
            return
        }

        if (!isRecognitionAvailable()) {
            val msg = "Speech recognition service is not available on this device."
            _lastError.value = msg
            _isListening.value = false
            onError?.invoke(SpeechRecognizer.ERROR_CLIENT, msg)
            return
        }

        postToMain {
            try {
                speechRecognizer?.destroy()
                speechRecognizer = if (preferOffline &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)
                ) {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
                } else {
                    SpeechRecognizer.createSpeechRecognizer(ctx)
                }

                speechRecognizer?.setRecognitionListener(createRecognitionListener())
                val recognizerIntent = buildRecognizerIntent(
                    languageTag = activeLanguageTag,
                    preferOffline = preferOffline && isOfflineRecognitionAvailable()
                )
                speechRecognizer?.startListening(recognizerIntent)
            } catch (e: Throwable) {
                _isListening.value = false
                _rmsDb.value = 0f
                _normalizedAudioLevel.value = 0f
                val msg = "Failed to start SpeechRecognizer: ${e.localizedMessage ?: "unknown error"}"
                _lastError.value = msg
                onError?.invoke(SpeechRecognizer.ERROR_CLIENT, msg)
            }
        }
    }

    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                _isListening.value = true
                _lastError.value = null
                onReadyCallback?.invoke()
            }

            override fun onBeginningOfSpeech() {
                _isListening.value = true
                onBeginningCallback?.invoke()
            }

            override fun onRmsChanged(rmsdB: Float) {
                _rmsDb.value = rmsdB
                _normalizedAudioLevel.value = normalizeRmsDb(rmsdB)
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                _isListening.value = false
                _rmsDb.value = 0f
                _normalizedAudioLevel.value = 0f
                onEndCallback?.invoke()
            }

            override fun onError(error: Int) {
                _isListening.value = false
                _rmsDb.value = 0f
                _normalizedAudioLevel.value = 0f
                // Automatic one-time multilingual fallback if requested locale pack is unavailable (Android 13+ codes 12/13)
                if ((error == 12 || error == 13) && !activeLanguageTag.equals("en-IN", ignoreCase = true)) {
                    activeLanguageTag = "en-IN"
                    postToMain {
                        try {
                            val fallbackIntent = buildRecognizerIntent(languageTag = "en-IN", preferOffline = false)
                            speechRecognizer?.startListening(fallbackIntent)
                        } catch (_: Throwable) {
                            val message = mapSpeechRecognizerError(error)
                            _lastError.value = message
                            onErrorCallback?.invoke(error, message)
                        }
                    }
                    return
                }
                val message = mapSpeechRecognizerError(error)
                _lastError.value = message
                onErrorCallback?.invoke(error, message)
            }

            override fun onResults(results: Bundle?) {
                _isListening.value = false
                _rmsDb.value = 0f
                _normalizedAudioLevel.value = 0f

                val matches = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    .orEmpty()

                val scores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                val topConfidence = if (scores != null && scores.isNotEmpty() && scores[0] >= 0f) {
                    scores[0].coerceIn(0f, 1f)
                } else if (matches.isNotEmpty()) {
                    0.90f
                } else {
                    0f
                }

                val primary = matches.firstOrNull().orEmpty()
                _partialTranscript.value = primary

                val inferredLanguage = when (
                    com.example.core.agent.ConversationalIntelligenceEngine.detectLanguage(primary)
                ) {
                    com.example.core.agent.ConversationLanguage.HINDI -> "hi-IN"
                    com.example.core.agent.ConversationLanguage.HINGLISH,
                    com.example.core.agent.ConversationLanguage.ENGLISH -> "en-IN"
                }
                val structured = SpeechRecognitionResult(
                    primaryText = primary,
                    alternatives = matches.drop(1),
                    confidenceScore = topConfidence,
                    languageTag = if (activeLanguageTag.isNotBlank() &&
                        activeLanguageTag != "en-IN" &&
                        activeLanguageTag != "hi-IN"
                    ) activeLanguageTag else inferredLanguage,
                    isFinal = true
                )
                _lastResult.value = structured
                onFinalResultCallback?.invoke(structured)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                if (partial.isNotEmpty()) {
                    _partialTranscript.value = partial
                    onPartialCallback?.invoke(partial)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}

            // API 34+: retain the recognizer's language-detection result when provided.
            override fun onLanguageDetection(results: Bundle) {
                val detected = results.getString(SpeechRecognizer.DETECTED_LANGUAGE)?.trim()
                if (!detected.isNullOrEmpty()) activeLanguageTag = detected
            }
        }
    }

    override fun stopListening() {
        _isListening.value = false
        _rmsDb.value = 0f
        _normalizedAudioLevel.value = 0f
        postToMain {
            try {
                speechRecognizer?.stopListening()
            } catch (_: Throwable) {}
        }
    }

    override fun cancel() {
        _isListening.value = false
        _rmsDb.value = 0f
        _normalizedAudioLevel.value = 0f
        postToMain {
            try {
                speechRecognizer?.cancel()
            } catch (_: Throwable) {}
        }
    }

    override fun release() {
        _isListening.value = false
        _rmsDb.value = 0f
        _normalizedAudioLevel.value = 0f
        onReadyCallback = null
        onBeginningCallback = null
        onPartialCallback = null
        onEndCallback = null
        onFinalResultCallback = null
        onErrorCallback = null
        postToMain {
            try {
                speechRecognizer?.destroy()
            } catch (_: Throwable) {}
            speechRecognizer = null
        }
    }

    /**
     * Converts Android SpeechRecognizer RMS dB (typically in `-2f..10f`) to a normalized `0f..1f` value
     * for responsive UI waveform visualization.
     */
    fun normalizeRmsDb(rmsdB: Float): Float {
        if (!rmsdB.isFinite()) return 0f
        return ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
    }

    /**
     * Maps [SpeechRecognizer] error constants to user-friendly, actionable descriptions.
     */
    fun mapSpeechRecognizerError(errorCode: Int): String {
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
            12 -> "Selected language is not supported by the device speech service." // ERROR_LANGUAGE_NOT_SUPPORTED (API 31+)
            13 -> "Selected language pack is not yet downloaded for offline recognition." // ERROR_LANGUAGE_UNAVAILABLE (API 31+)
            else -> "Speech recognition failed (code $errorCode)."
        }
    }

    /**
     * Deterministic simulation helper for JVM unit & integration tests where Android hardware
     * SpeechRecognizer service is not bound. Exercises the exact callback & state pipeline.
     */
    fun simulateSpeechRecognitionLifecycleForTest(
        partialHypotheses: List<String>,
        finalTranscript: String,
        alternatives: List<String> = emptyList(),
        confidence: Float = 0.94f,
        simulatedRmsDb: Float = 6.5f,
        languageTag: String = "en-IN",
        onReady: (() -> Unit)? = null,
        onPartial: ((String) -> Unit)? = null,
        onFinal: ((SpeechRecognitionResult) -> Unit)? = null
    ): SpeechRecognitionResult {
        activeLanguageTag = languageTag
        _lastError.value = null
        _isListening.value = true
        onReady?.invoke()
        onReadyCallback?.invoke()

        onBeginningCallback?.invoke()
        _rmsDb.value = simulatedRmsDb
        _normalizedAudioLevel.value = normalizeRmsDb(simulatedRmsDb)

        for (partial in partialHypotheses) {
            _partialTranscript.value = partial
            onPartial?.invoke(partial)
            onPartialCallback?.invoke(partial)
        }

        _isListening.value = false
        _rmsDb.value = 0f
        _normalizedAudioLevel.value = 0f
        onEndCallback?.invoke()

        val cleanFinal = finalTranscript.trim()
        _partialTranscript.value = cleanFinal
        val result = SpeechRecognitionResult(
            primaryText = cleanFinal,
            alternatives = alternatives,
            confidenceScore = confidence.coerceIn(0f, 1f),
            languageTag = languageTag,
            isFinal = true
        )
        _lastResult.value = result
        onFinal?.invoke(result)
        onFinalResultCallback?.invoke(result)
        return result
    }
}
