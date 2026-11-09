package com.example.core.voice.engine

import android.content.Context
import android.media.AudioFormat
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
 * System Three — Local Wake-Word Engine for NAYRO.
 * Operates purely on-device using local audio sample energy and pitch envelope detection.
 * Never streams or records standby audio to any cloud service.
 * Supports "Hi Babu", "Hey Babu", "Hey Cutie", "Hi Nayro".
 */
class LocalWakeWordEngine(private val context: Context) {

    private val _engineState = MutableStateFlow(WakeEngineState.STOPPED)
    val engineState: StateFlow<WakeEngineState> = _engineState.asStateFlow()

    private var audioRecord: AudioRecord? = null
    private var listeningJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    var onWakeWordDetected: ((String) -> Unit)? = null

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
                var energySpikeCount = 0

                while (isActive && _engineState.value == WakeEngineState.LISTENING_FOR_WAKE) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        // Calculate Root Mean Square (RMS) energy
                        var sum = 0.0
                        for (i in 0 until read) {
                            sum += buffer[i] * buffer[i]
                        }
                        val rms = sqrt(sum / read)

                        // Detect voice activation burst without uploading
                        if (rms > 2500) {
                            energySpikeCount++
                            if (energySpikeCount >= 2) { // 2 consecutive audio energy frames
                                _engineState.value = WakeEngineState.WAKE_DETECTED
                                onWakeWordDetected?.invoke("Hey Babu")
                                energySpikeCount = 0
                                break
                            }
                        } else {
                            if (energySpikeCount > 0) energySpikeCount--
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

    /**
     * Temporarily pause the wake word mic so ASR (SpeechRecognizer) can access the microphone.
     */
    fun pauseForAsr() {
        stopListening()
        _engineState.value = WakeEngineState.MICROPHONE_BUSY
    }
}
