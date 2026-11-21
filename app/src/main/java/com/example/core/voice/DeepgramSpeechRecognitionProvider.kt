package com.example.core.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.example.core.security.EncryptedStorageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Cloud Speech-to-Text provider backed by Deepgram Nova-2 REST API (`https://api.deepgram.com/v1/listen`).
 *
 * - Captures short spoken commands via Android [AudioRecord] (16kHz mono PCM-16) with live RMS telemetry
 *   and voice-activity end-of-speech detection (never uploads continuous standby wake audio).
 * - Uses user-configured API key stored in hardware-encrypted [EncryptedStorageManager].
 * - Reports honest failures so [VoiceProviderRouter] can fall back automatically to Android [SpeechRecognizer].
 */
class DeepgramSpeechRecognitionProvider(
    private val context: Context? = null,
    private val encryptedStorageManager: EncryptedStorageManager? = null,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
) : SpeechRecognitionProvider {

    companion object {
        const val PROVIDER_ID = "deepgram_stt"
        const val DEFAULT_MODEL = "nova-2"
        const val SAMPLE_RATE_HZ = 16_000
        private const val MAX_CAPTURE_MS = 12_000L
        private const val SILENCE_AFTER_SPEECH_MS = 1_300L
        private const val NO_SPEECH_TIMEOUT_MS = 5_000L
        private const val SPEECH_RMS_THRESHOLD = 420.0

        /**
         * Maps Android BCP-47 language tags to Deepgram query language parameters.
         * Uses `multi` for Indian Hinglish (`en-IN` / `hi-IN`) where supported so code-switched
         * Hindi/English speech transcribes naturally.
         */
        fun mapLanguageTagForDeepgram(languageTag: String): String {
            val clean = languageTag.trim().lowercase()
            return when {
                clean.startsWith("hi") -> "hi"
                clean == "en-in" || clean == "hinglish" -> "multi"
                clean.startsWith("en") -> "en"
                clean.isNotBlank() -> clean.substringBefore("-")
                else -> "multi"
            }
        }

        /**
         * Wraps raw little-endian 16-bit mono PCM audio into a valid RIFF/WAV byte array
         * suitable for Deepgram's `audio/wav` REST transcription endpoint.
         */
        fun encodePcm16ToWav(pcmBytes: ByteArray, sampleRateHz: Int = SAMPLE_RATE_HZ): ByteArray {
            val channels = 1
            val bitsPerSample = 16
            val byteRate = sampleRateHz * channels * bitsPerSample / 8
            val blockAlign = (channels * bitsPerSample / 8).toShort()
            val dataSize = pcmBytes.size
            val chunkSize = 36 + dataSize

            val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
            buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
            buffer.putInt(chunkSize)
            buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
            buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
            buffer.putInt(16) // PCM subchunk size
            buffer.putShort(1) // AudioFormat = 1 (PCM)
            buffer.putShort(channels.toShort())
            buffer.putInt(sampleRateHz)
            buffer.putInt(byteRate)
            buffer.putShort(blockAlign)
            buffer.putShort(bitsPerSample.toShort())
            buffer.put("data".toByteArray(Charsets.US_ASCII))
            buffer.putInt(dataSize)
            buffer.put(pcmBytes)
            return buffer.array()
        }

        /**
         * Parses Deepgram's `/v1/listen` JSON response into a structured [SpeechRecognitionResult].
         * Supports both Android's runtime [JSONObject] and pure JVM environments where `android.jar`
         * stubs `org.json.*` methods to return defaults.
         */
        fun parseDeepgramTranscriptJson(
            rawJson: String,
            languageTag: String = "en-IN"
        ): SpeechRecognitionResult? {
            if (rawJson.isBlank()) return null

            // Primary path: Android runtime JSONObject
            try {
                val root = JSONObject(rawJson)
                val results = root.optJSONObject("results")
                val channels = results?.optJSONArray("channels")
                if (channels != null && channels.length() > 0) {
                    val alternatives = channels.optJSONObject(0)?.optJSONArray("alternatives")
                    if (alternatives != null && alternatives.length() > 0) {
                        val parsedAlts = mutableListOf<String>()
                        var bestConfidence = 0f
                        for (i in 0 until alternatives.length()) {
                            val alt = alternatives.optJSONObject(i) ?: continue
                            val transcript = alt.optString("transcript", "").trim()
                            if (transcript.isNotEmpty()) {
                                parsedAlts.add(transcript)
                                if (i == 0) {
                                    bestConfidence = alt.optDouble("confidence", 0.90).toFloat().coerceIn(0f, 1f)
                                }
                            }
                        }
                        val primary = parsedAlts.firstOrNull()
                        if (!primary.isNullOrBlank()) {
                            return SpeechRecognitionResult(
                                primaryText = primary,
                                alternatives = parsedAlts.drop(1),
                                confidenceScore = bestConfidence,
                                languageTag = languageTag,
                                isFinal = true
                            )
                        }
                    }
                }
            } catch (_: Throwable) {
                // Fall through to pure-Kotlin parser
            }

            // Pure-Kotlin parser fallback (works identically on Android & headless JVM unit tests)
            val transcriptRegex = Regex("\"transcript\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
            val confidenceRegex = Regex("\"confidence\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)")
            val transcripts = transcriptRegex.findAll(rawJson)
                .map { match ->
                    match.groupValues[1]
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\")
                        .replace("\\n", "\n")
                        .trim()
                }
                .filter { it.isNotEmpty() }
                .toList()

            val primary = transcripts.firstOrNull() ?: return null
            val confidence = confidenceRegex.find(rawJson)
                ?.groupValues?.getOrNull(1)
                ?.toFloatOrNull()
                ?.coerceIn(0f, 1f)
                ?: 0.90f

            return SpeechRecognitionResult(
                primaryText = primary,
                alternatives = transcripts.drop(1),
                confidenceScore = confidence,
                languageTag = languageTag,
                isFinal = true
            )
        }
    }

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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var captureJob: Job? = null
    @Volatile
    private var stopCaptureRequested = false
    @Volatile
    private var cancelRequested = false

    private val mainHandler: Handler? = try {
        val looper = Looper.getMainLooper()
        if (looper != null) Handler(looper) else null
    } catch (_: Throwable) {
        null
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

    fun getApiKey(): String = encryptedStorageManager?.getApiKey(PROVIDER_ID)?.trim().orEmpty()

    fun hasApiKey(): Boolean = getApiKey().isNotBlank()

    override fun isRecognitionAvailable(): Boolean {
        return hasApiKey() && context != null
    }

    override fun isOfflineRecognitionAvailable(): Boolean = false

    override fun buildRecognizerIntent(
        languageTag: String,
        preferOffline: Boolean,
        maxResults: Int,
        completeSilenceMs: Long
    ): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
        }
    }

    @SuppressLint("MissingPermission")
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
        val apiKey = getApiKey()
        if (apiKey.isBlank()) {
            val msg = "Deepgram API key is not configured."
            _lastError.value = msg
            _isListening.value = false
            postToMain { onError?.invoke(SpeechRecognizer.ERROR_CLIENT, msg) }
            return
        }

        val ctx = context ?: run {
            val msg = "Android Context unavailable for Deepgram audio capture."
            _lastError.value = msg
            _isListening.value = false
            postToMain { onError?.invoke(SpeechRecognizer.ERROR_CLIENT, msg) }
            return
        }

        val hasMic = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            val msg = "Microphone permission is required for Deepgram voice capture."
            _lastError.value = msg
            _isListening.value = false
            postToMain { onError?.invoke(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, msg) }
            return
        }

        cancel()
        stopCaptureRequested = false
        cancelRequested = false
        _lastError.value = null
        _partialTranscript.value = ""

        captureJob = scope.launch {
            var recorder: AudioRecord? = null
            try {
                val minBuf = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBuf <= 0) {
                    val msg = "AudioRecord.getMinBufferSize failed ($minBuf) for Deepgram STT."
                    _lastError.value = msg
                    _isListening.value = false
                    postToMain { onError?.invoke(SpeechRecognizer.ERROR_AUDIO, msg) }
                    return@launch
                }

                recorder = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf.coerceAtLeast(3200) * 2
                )

                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    try { recorder.release() } catch (_: Throwable) {}
                    recorder = null
                    val msg = "Failed to initialize Android AudioRecord for Deepgram STT."
                    _lastError.value = msg
                    _isListening.value = false
                    postToMain { onError?.invoke(SpeechRecognizer.ERROR_AUDIO, msg) }
                    return@launch
                }

                recorder.startRecording()
                if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    try { recorder.stop() } catch (_: Throwable) {}
                    try { recorder.release() } catch (_: Throwable) {}
                    recorder = null
                    val msg = "AudioRecord failed to enter RECORDSTATE_RECORDING for Deepgram STT."
                    _lastError.value = msg
                    _isListening.value = false
                    postToMain { onError?.invoke(SpeechRecognizer.ERROR_AUDIO, msg) }
                    return@launch
                }
                _isListening.value = true
                postToMain { onReady?.invoke() }

                val pcmStream = ByteArrayOutputStream()
                val shortBuf = ShortArray(1600) // ~100ms frame @ 16kHz
                val byteBuf = ByteArray(shortBuf.size * 2)
                val startMs = System.currentTimeMillis()
                var speechStarted = false
                var lastSpeechMs = startMs

                while (isActive && !stopCaptureRequested && !cancelRequested) {
                    val read = recorder.read(shortBuf, 0, shortBuf.size)
                    if (read <= 0) continue

                    var sumSq = 0.0
                    for (i in 0 until read) {
                        val sample = shortBuf[i]
                        sumSq += sample.toDouble() * sample.toDouble()
                        byteBuf[i * 2] = (sample.toInt() and 0xFF).toByte()
                        byteBuf[i * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
                    }
                    pcmStream.write(byteBuf, 0, read * 2)

                    val rms = sqrt(sumSq / read)
                    val db = if (rms > 1.0) (20.0 * log10(rms / 32768.0)).toFloat() else -60f
                    val normalized = ((rms / 6000.0).toFloat()).coerceIn(0f, 1f)
                    _rmsDb.value = db
                    _normalizedAudioLevel.value = normalized

                    val now = System.currentTimeMillis()
                    if (rms >= SPEECH_RMS_THRESHOLD) {
                        if (!speechStarted) {
                            speechStarted = true
                            postToMain { onBeginningOfSpeech?.invoke() }
                        }
                        lastSpeechMs = now
                    }

                    if (speechStarted && (now - lastSpeechMs) >= SILENCE_AFTER_SPEECH_MS) {
                        break
                    }
                    if (!speechStarted && (now - startMs) >= NO_SPEECH_TIMEOUT_MS) {
                        break
                    }
                    if ((now - startMs) >= MAX_CAPTURE_MS) {
                        break
                    }
                }

                try {
                    recorder.stop()
                } catch (_: Throwable) {}
                try {
                    recorder.release()
                } catch (_: Throwable) {}
                recorder = null

                _isListening.value = false
                _rmsDb.value = 0f
                _normalizedAudioLevel.value = 0f

                if (cancelRequested) return@launch

                postToMain { onEndOfSpeech?.invoke() }

                val pcmData = pcmStream.toByteArray()
                if (!speechStarted || pcmData.size < 3200) {
                    val msg = "No speech detected."
                    _lastError.value = msg
                    postToMain { onError?.invoke(SpeechRecognizer.ERROR_SPEECH_TIMEOUT, msg) }
                    return@launch
                }

                val wavBytes = encodePcm16ToWav(pcmData, SAMPLE_RATE_HZ)
                val dgLang = mapLanguageTagForDeepgram(languageTag)
                val url = "https://api.deepgram.com/v1/listen?model=$DEFAULT_MODEL&smart_format=true&language=$dgLang"
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Token $apiKey")
                    .addHeader("Accept", "application/json")
                    .post(wavBytes.toRequestBody("audio/wav".toMediaType()))
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val bodyStr = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val errMsg = when (response.code) {
                            401, 403 -> "Deepgram authentication failed (${response.code}). Check your API key."
                            429 -> "Deepgram rate limit reached (429)."
                            else -> "Deepgram transcription failed (HTTP ${response.code})."
                        }
                        _lastError.value = errMsg
                        postToMain { onError?.invoke(SpeechRecognizer.ERROR_SERVER, errMsg) }
                        return@launch
                    }

                    val parsed = parseDeepgramTranscriptJson(bodyStr, languageTag)
                    if (parsed == null || parsed.primaryText.isBlank()) {
                        val msg = "Deepgram returned no transcript."
                        _lastError.value = msg
                        postToMain { onError?.invoke(SpeechRecognizer.ERROR_NO_MATCH, msg) }
                    } else {
                        _partialTranscript.value = parsed.primaryText
                        _lastResult.value = parsed
                        postToMain { onFinalResult(parsed) }
                    }
                }
            } catch (e: Exception) {
                try {
                    recorder?.stop()
                } catch (_: Throwable) {}
                try {
                    recorder?.release()
                } catch (_: Throwable) {}
                _isListening.value = false
                _rmsDb.value = 0f
                _normalizedAudioLevel.value = 0f
                if (!cancelRequested) {
                    val msg = "Deepgram voice error: ${e.localizedMessage ?: e.javaClass.simpleName}"
                    _lastError.value = msg
                    postToMain { onError?.invoke(SpeechRecognizer.ERROR_NETWORK, msg) }
                }
            }
        }
    }

    /**
     * Verifies a Deepgram API key against Deepgram's `/v1/projects` endpoint.
     */
    suspend fun testConnection(apiKeyOverride: String? = null): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val key = (apiKeyOverride ?: getApiKey()).trim()
        if (key.isBlank()) {
            return@withContext false to "No Deepgram API key saved."
        }
        try {
            val request = Request.Builder()
                .url("https://api.deepgram.com/v1/projects")
                .addHeader("Authorization", "Token $key")
                .addHeader("Accept", "application/json")
                .get()
                .build()
            httpClient.newCall(request).execute().use { resp ->
                when {
                    resp.isSuccessful -> true to "Deepgram API key verified successfully (Nova-2 ready)."
                    resp.code == 401 || resp.code == 403 -> false to "Deepgram API key rejected (HTTP ${resp.code})."
                    else -> false to "Deepgram returned HTTP ${resp.code}."
                }
            }
        } catch (e: Exception) {
            false to "Deepgram connection error: ${e.localizedMessage ?: e.javaClass.simpleName}"
        }
    }

    override fun stopListening() {
        stopCaptureRequested = true
        _isListening.value = false
    }

    override fun cancel() {
        cancelRequested = true
        stopCaptureRequested = true
        captureJob?.cancel()
        captureJob = null
        _isListening.value = false
        _rmsDb.value = 0f
        _normalizedAudioLevel.value = 0f
    }

    override fun release() {
        cancel()
    }
}
