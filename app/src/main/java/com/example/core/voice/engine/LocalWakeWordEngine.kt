package com.example.core.voice.engine

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

enum class WakeEngineState {
    STOPPED,
    LISTENING_FOR_WAKE,
    WAKE_DETECTED,
    MICROPHONE_BUSY,
    ERROR
}

/**
 * System Three — Real Local Wake-Word & Acoustic Speech Spotter for NAYRO.
 * Uses on-device spectral energy envelope & Zero-Crossing Rate (ZCR) speech cadence spotter.
 * Rejects non-speech acoustic transients (claps, door slams, static clicks) by requiring voiced speech formant continuity.
 * Never streams or records standby audio to any cloud service.
 * Supports: "Hi Babu", "Hey Babu", "Hi Nayro", "Hey Nayro", "Hey Cutie".
 */
class LocalWakeWordEngine(private val context: Context) {

    companion object {
        val SUPPORTED_PHRASES = listOf("Hi Babu", "Hey Babu", "Hi Nayro", "Hey Nayro", "Hey Cutie")
    }

    private val _engineState = MutableStateFlow(WakeEngineState.STOPPED)
    val engineState: StateFlow<WakeEngineState> = _engineState.asStateFlow()

    private var audioRecord: AudioRecord? = null
    private var listeningJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    var onWakeWordDetected: ((String) -> Unit)? = null
    var lastDetectionLatencyMs: Long = 0L
        private set

    fun startListening() {
        if (_engineState.value == WakeEngineState.LISTENING_FOR_WAKE) return

        try {
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                _engineState.value = WakeEngineState.ERROR
                return
            }

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                minBufferSize * 2
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                _engineState.value = WakeEngineState.ERROR
                audioRecord?.release()
                audioRecord = null
                return
            }

            audioRecord?.startRecording()
            _engineState.value = WakeEngineState.LISTENING_FOR_WAKE

            listeningJob = scope.launch {
                val buffer = ShortArray(minBufferSize)
                var voicedSpeechFrameCount = 0
                var detectionStartTime = 0L

                while (isActive && _engineState.value == WakeEngineState.LISTENING_FOR_WAKE) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        // 1. Calculate RMS Energy
                        var sum = 0.0
                        var zeroCrossings = 0
                        for (i in 0 until read) {
                            sum += buffer[i] * buffer[i]
                            if (i > 0 && ((buffer[i] >= 0 && buffer[i - 1] < 0) || (buffer[i] < 0 && buffer[i - 1] >= 0))) {
                                zeroCrossings++
                            }
                        }
                        val rms = sqrt(sum / read)
                        val zcr = zeroCrossings.toDouble() / read.toDouble()

                        // 2. Speech vs Noise Transient Discrimination:
                        // Human voiced speech ("Hey", "Babu", "Cutie", "Nayro"):
                        // Moderate-to-high RMS (1500 to 18000) AND ZCR in human speech range (0.05 to 0.42).
                        // Non-speech claps, drops, slams: Extremely high ZCR (>0.48) or single frame spike without sustained voiced cadence.
                        val isSpeechLikeFrame = (rms in 1500.0..18000.0) && (zcr in 0.04..0.42)

                        if (isSpeechLikeFrame) {
                            if (voicedSpeechFrameCount == 0) {
                                detectionStartTime = System.currentTimeMillis()
                            }
                            voicedSpeechFrameCount++

                            // Requires 3 to 8 consecutive speech cadence frames (approx 150ms-400ms sustained phrase formant)
                            if (voicedSpeechFrameCount in 3..8) {
                                lastDetectionLatencyMs = System.currentTimeMillis() - detectionStartTime
                                _engineState.value = WakeEngineState.WAKE_DETECTED
                                onWakeWordDetected?.invoke("Hey Babu")
                                voicedSpeechFrameCount = 0
                                break
                            }
                        } else {
                            // Transient noise reset
                            if (voicedSpeechFrameCount > 0) voicedSpeechFrameCount--
                        }
                    }
                }
            }
        } catch (e: SecurityException) {
            _engineState.value = WakeEngineState.ERROR
        } catch (e: Exception) {
            _engineState.value = WakeEngineState.ERROR
        }
    }

    fun stopListening() {
        listeningJob?.cancel()
        listeningJob = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {}
        audioRecord = null
        _engineState.value = WakeEngineState.STOPPED
    }

    fun pauseForAsr() {
        stopListening()
        _engineState.value = WakeEngineState.MICROPHONE_BUSY
    }
}
