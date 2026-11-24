package com.example.core.voice.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

enum class VadState {
    SILENCE,
    SPEECH_START,
    SPEECH_ACTIVE,
    SPEECH_HANGOVER,
    END_OF_UTTERANCE
}

data class VadConfig(
    val minSpeechRms: Double = 380.0,
    val minSnrMarginRatio: Double = 1.45,
    val minZcr: Double = 0.02,
    val maxZcr: Double = 0.46,
    val minVoicedFramesForSpeech: Int = 3,
    val silenceHangoverFrames: Int = 8,
    val initialNoiseFloorRms: Double = 140.0,
    val minNoiseFloorRms: Double = 60.0,
    val maxNoiseFloorRms: Double = 650.0
)

data class VadFrameDecision(
    val state: VadState,
    val isSpeechFrame: Boolean,
    val rms: Double,
    val zcr: Double,
    val peak: Float,
    val noiseFloorRms: Double,
    val snrRatio: Double,
    val activeVoicedFrames: Int,
    val silenceHangoverCount: Int,
    val utteranceEnded: Boolean
)

/**
 * Independent Voice Activity Detector (VAD) decoupled from Wake-Word Matching.
 * Detects speech start, active voiced segments, silence hangover, and end-of-utterance
 * using adaptive noise-floor tracking, RMS energy, SNR margin, and Zero-Crossing Rate (ZCR).
 */
class VoiceActivityDetector(
    private val config: VadConfig = VadConfig()
) {

    private val _vadState = MutableStateFlow(VadState.SILENCE)
    val vadState: StateFlow<VadState> = _vadState.asStateFlow()

    @Volatile
    var noiseFloorRms: Double = config.initialNoiseFloorRms
        private set

    private var activeVoicedFrames: Int = 0
    private var silenceHangoverCount: Int = 0
    private var inSpeechSegment: Boolean = false

    fun reset(initialNoiseFloor: Double = config.initialNoiseFloorRms) {
        noiseFloorRms = initialNoiseFloor.coerceIn(config.minNoiseFloorRms, config.maxNoiseFloorRms)
        activeVoicedFrames = 0
        silenceHangoverCount = 0
        inSpeechSegment = false
        _vadState.value = VadState.SILENCE
    }

    fun updateNoiseFloor(rms: Double) {
        if (rms > 0.0 && rms < config.minSpeechRms) {
            noiseFloorRms = (0.92 * noiseFloorRms + 0.08 * rms)
                .coerceIn(config.minNoiseFloorRms, config.maxNoiseFloorRms)
        }
    }

    /**
     * Evaluates a single 16-bit PCM audio frame and returns an explicit [VadFrameDecision].
     */
    fun processFrame(pcm: ShortArray, length: Int = pcm.size): VadFrameDecision {
        val safeLen = length.coerceIn(0, pcm.size)
        if (safeLen == 0) {
            return VadFrameDecision(
                state = _vadState.value,
                isSpeechFrame = false,
                rms = 0.0,
                zcr = 0.0,
                peak = 0f,
                noiseFloorRms = noiseFloorRms,
                snrRatio = 0.0,
                activeVoicedFrames = activeVoicedFrames,
                silenceHangoverCount = silenceHangoverCount,
                utteranceEnded = false
            )
        }

        var sumSq = 0.0
        var zeroCrossings = 0
        var peak = 0f
        var prev = pcm[0].toInt()
        for (i in 0 until safeLen) {
            val s = pcm[i].toInt()
            val absVal = abs(s).toFloat()
            if (absVal > peak) peak = absVal
            sumSq += s.toDouble() * s.toDouble()
            if (i > 0 && ((s >= 0 && prev < 0) || (s < 0 && prev >= 0))) {
                zeroCrossings++
            }
            prev = s
        }

        val rms = sqrt(sumSq / safeLen)
        val zcr = if (safeLen > 1) zeroCrossings.toDouble() / (safeLen - 1) else 0.0
        val snrRatio = rms / max(noiseFloorRms, 1.0)

        val isSpeechFrame = rms >= config.minSpeechRms &&
            snrRatio >= config.minSnrMarginRatio &&
            zcr in config.minZcr..config.maxZcr

        var utteranceEnded = false
        val nextState: VadState

        if (isSpeechFrame) {
            silenceHangoverCount = 0
            activeVoicedFrames++
            if (!inSpeechSegment) {
                if (activeVoicedFrames >= config.minVoicedFramesForSpeech) {
                    inSpeechSegment = true
                    nextState = VadState.SPEECH_ACTIVE
                } else {
                    nextState = VadState.SPEECH_START
                }
            } else {
                nextState = VadState.SPEECH_ACTIVE
            }
        } else {
            if (peak > 0f && rms < config.minSpeechRms) {
                updateNoiseFloor(rms)
            }
            if (inSpeechSegment || activeVoicedFrames > 0) {
                silenceHangoverCount++
                if (silenceHangoverCount >= config.silenceHangoverFrames) {
                    utteranceEnded = inSpeechSegment
                    nextState = if (inSpeechSegment) VadState.END_OF_UTTERANCE else VadState.SILENCE
                    inSpeechSegment = false
                    activeVoicedFrames = 0
                    silenceHangoverCount = 0
                } else {
                    nextState = if (inSpeechSegment) VadState.SPEECH_HANGOVER else VadState.SPEECH_START
                }
            } else {
                nextState = VadState.SILENCE
            }
        }

        _vadState.value = nextState
        return VadFrameDecision(
            state = nextState,
            isSpeechFrame = isSpeechFrame,
            rms = rms,
            zcr = zcr,
            peak = peak,
            noiseFloorRms = noiseFloorRms,
            snrRatio = snrRatio,
            activeVoicedFrames = activeVoicedFrames,
            silenceHangoverCount = silenceHangoverCount,
            utteranceEnded = utteranceEnded
        )
    }
}
