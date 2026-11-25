package com.advanceagent.core.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class AudioStreamManager {

    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var audioTrack: AudioTrack? = null
    private var captureJob: Job? = null

    @SuppressLint("MissingPermission")
    fun startCapture(
        scope: CoroutineScope,
        onPcmShortChunk: (ShortArray, Int) -> Unit,
        onAmplitude: ((Float) -> Unit)? = null,
        onPcmByteChunk: ((ByteArray) -> Unit)? = null
    ) {
        stopCapture()

        val minBuf = AudioRecord.getMinBufferSize(
            INPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(3200)

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            INPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 2
        )

        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler = AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true }
        }
        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true }
        }

        audioRecord = record
        record.startRecording()

        captureJob = scope.launch(Dispatchers.IO) {
            val shortBuf = ShortArray(1600)
            while (isActive) {
                val read = record.read(shortBuf, 0, shortBuf.size)
                if (read > 0) {
                    onPcmShortChunk(shortBuf, read)
                    onAmplitude?.let { cb ->
                        var maxSample = 0
                        for (i in 0 until read) {
                            val a = abs(shortBuf[i].toInt())
                            if (a > maxSample) maxSample = a
                        }
                        cb((maxSample / 32768f).coerceIn(0f, 1f))
                    }
                    onPcmByteChunk?.let { byteCallback ->
                        val bb = ByteBuffer.allocate(read * 2).order(ByteOrder.LITTLE_ENDIAN)
                        for (i in 0 until read) bb.putShort(shortBuf[i])
                        byteCallback(bb.array())
                    }
                }
            }
        }
    }

    fun stopCapture() {
        captureJob?.cancel()
        captureJob = null
        try {
            echoCanceler?.release()
            noiseSuppressor?.release()
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {
        } finally {
            echoCanceler = null
            noiseSuppressor = null
            audioRecord = null
        }
    }

    fun startPlayback() {
        if (audioTrack != null) return
        val minBuf = AudioTrack.getMinBufferSize(
            OUTPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4800)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(OUTPUT_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .apply { play() }
    }

    fun writePlaybackPcm(pcm24kBytes: ByteArray) {
        if (audioTrack == null) startPlayback()
        audioTrack?.write(pcm24kBytes, 0, pcm24kBytes.size)
    }

    fun flushPlaybackForBargeIn() {
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.play()
        } catch (_: Exception) {
        }
    }

    fun stopPlayback() {
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {
        } finally {
            audioTrack = null
        }
    }

    fun releaseAll() {
        stopCapture()
        stopPlayback()
    }

    companion object {
        const val INPUT_SAMPLE_RATE = 16000
        const val OUTPUT_SAMPLE_RATE = 24000
    }
}
