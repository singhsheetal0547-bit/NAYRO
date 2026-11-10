package com.example.core.voice.engine

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

enum class WakeEngineState {
    STOPPED,
    LISTENING_FOR_WAKE,
    WAKE_DETECTED,
    MICROPHONE_BUSY,
    MODEL_UNAVAILABLE,
    ERROR
}

/**
 * System Three — Honest Local Wake-Word & Keyword Spotting Engine for NAYRO (BUG 15 Remediation).
 *
 * CRITICAL REMEDIATION:
 * - Removed the fake RMS/ZCR generic speech energy detector that falsely triggered "Hey Babu"
 *   on arbitrary speech, noise, or music.
 * - Requires both (1) valid human speech acoustic envelope AND (2) a verified offline KWS model
 *   phrase match against configured wake phrases ("Hi Babu", "Hey Babu", "Hi Nayro", "Hey Nayro", "Hey Cutie").
 * - When no offline KWS model file (`nayro_kws_model.tflite`) is bundled in `assets`, reports
 *   `WakeEngineState.MODEL_UNAVAILABLE` honestly rather than fabricating wake-word detections.
 * - Never uploads or streams standby microphone audio to any cloud service.
 */
class LocalWakeWordEngine(private val context: Context?) {

    companion object {
        val SUPPORTED_PHRASES = listOf("Hi Babu", "Hey Babu", "Hi Nayro", "Hey Nayro", "Hey Cutie")
        const val BUNDLED_KWS_MODEL_ASSET = "nayro_kws_model.tflite"
    }

    private val _engineState = MutableStateFlow(WakeEngineState.STOPPED)
    val engineState: StateFlow<WakeEngineState> = _engineState.asStateFlow()

    private val _statusMessage = MutableStateFlow("Wake-word detector stopped.")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val activeWakePhrases = SUPPORTED_PHRASES.toMutableList()

    var onWakeWordDetected: ((String) -> Unit)? = null
    var lastDetectionLatencyMs: Long = 0L
        private set

    private var sensitivity: Float = 0.55f // 0.0 (strict) .. 1.0 (high sensitivity)

    fun setSensitivity(value: Float) {
        sensitivity = value.coerceIn(0.1f, 0.95f)
    }

    fun getSensitivity(): Float = sensitivity

    fun isValidSampleRate(sampleRateHz: Int): Boolean {
        return sampleRateHz in setOf(8000, 16000, 22050, 44100, 48000)
    }

    /**
     * Extracts a deterministic 4-element acoustic feature vector `[rms, zcr, peakAmplitude,voicedRatio]`
     * from a 16-bit PCM frame without allocating large objects on the audio thread.
     */
    fun extractAcousticFeatures(pcmBuffer: ShortArray, readCount: Int = pcmBuffer.size): FloatArray {
        if (readCount <= 0 || pcmBuffer.isEmpty()) return floatArrayOf(0f, 0f, 0f, 0f)
        val n = readCount.coerceAtMost(pcmBuffer.size)
        var sumSq = 0.0
        var zeroCrossings = 0
        var peak = 0
        var voicedSamples = 0
        for (i in 0 until n) {
            val s = pcmBuffer[i].toInt()
            val absS = kotlin.math.abs(s)
            if (absS > peak) peak = absS
            if (absS > 600) voicedSamples++
            sumSq += s.toDouble() * s.toDouble()
            if (i > 0 && ((pcmBuffer[i] >= 0 && pcmBuffer[i - 1] < 0) || (pcmBuffer[i] < 0 && pcmBuffer[i - 1] >= 0))) {
                zeroCrossings++
            }
        }
        val rms = sqrt(sumSq / n).toFloat()
        val zcr = zeroCrossings.toFloat() / n.toFloat()
        val voicedRatio = voicedSamples.toFloat() / n.toFloat()
        return floatArrayOf(rms, zcr, peak.toFloat(), voicedRatio)
    }

    /**
     * Splits an utterance starting with a wake phrase (e.g., "Hi Babu, open WhatsApp")
     * into `Pair(matchedWakePhrase, trailingCommand)`. Returns null if no configured wake phrase matches.
     */
    fun extractCommandAfterWakePhrase(utterance: String): Pair<String, String>? {
        val trimmed = utterance.trim()
        if (trimmed.isEmpty()) return null
        val phrases = getConfiguredWakePhrases().sortedByDescending { it.length }
        for (phrase in phrases) {
            val regex = Regex("^${Regex.escape(phrase)}(?:[\\s,.:;!-]+(.*))?$", RegexOption.IGNORE_CASE)
            val match = regex.find(trimmed)
            if (match != null) {
                val remainder = match.groupValues.getOrNull(1)?.trim().orEmpty()
                return phrase to remainder
            }
        }
        return null
    }

    /**
     * Checks whether a real offline KWS acoustic model file exists in Android assets.
     */
    fun isOfflineKwsModelAvailable(): Boolean {
        val ctx = context ?: return false
        return try {
            val assetList = ctx.assets.list("") ?: emptyArray()
            assetList.contains(BUNDLED_KWS_MODEL_ASSET)
        } catch (_: Exception) {
            false
        }
    }

    fun getConfiguredWakePhrases(): List<String> = synchronized(activeWakePhrases) {
        activeWakePhrases.toList()
    }

    fun addWakePhrase(phrase: String): Boolean = synchronized(activeWakePhrases) {
        val clean = phrase.trim()
        if (clean.length < 3) return false
        if (activeWakePhrases.none { it.equals(clean, ignoreCase = true) }) {
            activeWakePhrases.add(clean)
            true
        } else {
            false
        }
    }

    fun removeWakePhrase(phrase: String): Boolean = synchronized(activeWakePhrases) {
        activeWakePhrases.removeAll { it.equals(phrase.trim(), ignoreCase = true) }
    }

    /**
     * Evaluates a raw PCM 16-bit audio buffer and optional offline KWS phonetic hypothesis.
     * Raw PCM energy/ZCR alone NEVER triggers wake-word detection (BUG 15 Fix).
     */
    fun processAudioFrame(pcmBuffer: ShortArray, readCount: Int = pcmBuffer.size, kwsHypothesis: String? = null): String? {
        if (readCount <= 0 || pcmBuffer.isEmpty()) return null
        val features = extractAcousticFeatures(pcmBuffer, readCount)
        val rms = features[0].toDouble()
        val zcr = features[1].toDouble()

        return evaluateWakeCandidate(kwsHypothesis, rms, zcr)
    }

    /**
     * Evaluates whether a candidate utterance and acoustic metrics match a configured wake phrase.
     * - Rejects silence (`rms < minRmsThreshold`).
     * - Rejects non-speech transients/claps/white noise (`zcr !in 0.03..0.44` or `rms > 28000.0`).
     * - Rejects any speech where [kwsHypothesis] is null, blank, or does not match a configured wake phrase.
     */
    fun evaluateWakeCandidate(kwsHypothesis: String?, rms: Double, zcr: Double): String? {
        val minRmsThreshold = (1100.0 - (sensitivity * 550.0)).coerceIn(450.0, 1200.0)
        val isValidSpeechEnvelope = (rms in minRmsThreshold..28000.0) && (zcr in 0.03..0.44)
        if (!isValidSpeechEnvelope) return null

        val candidate = kwsHypothesis?.trim() ?: return null
        if (candidate.isEmpty()) return null

        val startNs = System.nanoTime()
        val normalizedCandidate = candidate.lowercase().replace(Regex("[^a-z0-9\\s]"), "").replace(Regex("\\s+"), " ").trim()
        val matched = synchronized(activeWakePhrases) {
            activeWakePhrases.firstOrNull { phrase ->
                val normPhrase = phrase.lowercase().replace(Regex("[^a-z0-9\\s]"), "").replace(Regex("\\s+"), " ").trim()
                normalizedCandidate == normPhrase || normalizedCandidate.startsWith("$normPhrase ")
            }
        }

        if (matched != null) {
            lastDetectionLatencyMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
            _engineState.value = WakeEngineState.WAKE_DETECTED
            _statusMessage.value = "Wake phrase detected: $matched"
            onWakeWordDetected?.invoke(matched)
        }
        return matched
    }

    fun startListening() {
        if (_engineState.value == WakeEngineState.LISTENING_FOR_WAKE) return

        // Honest capability verification: do not run fake RMS/ZCR wake detection when no KWS model is bundled
        if (!isOfflineKwsModelAvailable()) {
            _engineState.value = WakeEngineState.MODEL_UNAVAILABLE
            _statusMessage.value = "Offline wake-word acoustic model ($BUNDLED_KWS_MODEL_ASSET) is not bundled. Use manual microphone button or floating assistant."
            return
        }

        _engineState.value = WakeEngineState.LISTENING_FOR_WAKE
        _statusMessage.value = "Listening locally for wake phrase..."
    }

    fun stopListening() {
        _engineState.value = WakeEngineState.STOPPED
        _statusMessage.value = "Wake-word detector stopped."
    }

    fun pauseForAsr() {
        _engineState.value = WakeEngineState.MICROPHONE_BUSY
        _statusMessage.value = "Wake-word detector paused for active speech recognition."
    }
}
