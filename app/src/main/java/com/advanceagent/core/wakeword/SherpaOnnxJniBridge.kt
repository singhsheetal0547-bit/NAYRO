package com.advanceagent.core.wakeword

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.KeywordSpotterResult
import com.k2fsa.sherpa.onnx.OnlineStream

/**
 * Contract for bridging the official Sherpa-ONNX Android AAR (`com.k2fsa.sherpa.onnx.*`)
 * and its native JNI libraries (`libsherpa-onnx-jni.so`, `libonnxruntime.so`) with the NAYRO runtime.
 */
interface SherpaOnnxJniBridge {
    val isNativeLibraryLoaded: Boolean
    val lastNativeLoadError: String?

    fun ensureNativeLibraryLoaded(): Boolean

    fun createKeywordSpotter(
        assetManager: AssetManager?,
        config: KeywordSpotterConfig
    ): KeywordSpotter?

    fun createStream(
        spotter: KeywordSpotter,
        keywordsSpec: String
    ): OnlineStream?

    fun acceptWaveform(
        stream: OnlineStream,
        samples: FloatArray,
        sampleRate: Int = 16000
    )

    fun isReady(
        spotter: KeywordSpotter,
        stream: OnlineStream
    ): Boolean

    fun decode(
        spotter: KeywordSpotter,
        stream: OnlineStream
    )

    fun getResult(
        spotter: KeywordSpotter,
        stream: OnlineStream
    ): KeywordSpotterResult

    fun reset(
        spotter: KeywordSpotter,
        stream: OnlineStream
    )

    fun releaseStream(stream: OnlineStream?)

    fun releaseSpotter(spotter: KeywordSpotter?)
}

typealias SherpaOnnxBridge = SherpaOnnxJniBridge

/**
 * Default Kotlin JNI bridge backed directly by the official `sherpa-onnx-2.0.3.aar`
 * native classes (`com.k2fsa.sherpa.onnx.KeywordSpotter`, `com.k2fsa.sherpa.onnx.OnlineStream`)
 * and native shared libraries (`libonnxruntime.so`, `libsherpa-onnx-jni.so`).
 *
 * Replaces all compatibility simulations with genuine native ONNX inference calls.
 */
object OfficialSherpaOnnxJniBridge : SherpaOnnxJniBridge {

    @Volatile
    override var isNativeLibraryLoaded: Boolean = false
        private set

    @Volatile
    override var lastNativeLoadError: String? = null
        private set

    @Synchronized
    override fun ensureNativeLibraryLoaded(): Boolean {
        if (isNativeLibraryLoaded) return true
        return try {
            try {
                System.loadLibrary("onnxruntime")
            } catch (_: UnsatisfiedLinkError) {
                // Linked transitively by sherpa-onnx-jni on some ABIs
            }
            System.loadLibrary("sherpa-onnx-jni")
            isNativeLibraryLoaded = true
            lastNativeLoadError = null
            true
        } catch (e: UnsatisfiedLinkError) {
            isNativeLibraryLoaded = false
            lastNativeLoadError = "UnsatisfiedLinkError loading libsherpa-onnx-jni.so: ${e.message}"
            false
        } catch (t: Throwable) {
            isNativeLibraryLoaded = false
            lastNativeLoadError = "Native JNI load error (${t.javaClass.simpleName}): ${t.message}"
            false
        }
    }

    override fun createKeywordSpotter(
        assetManager: AssetManager?,
        config: KeywordSpotterConfig
    ): KeywordSpotter? {
        if (!ensureNativeLibraryLoaded()) return null
        return KeywordSpotter(assetManager = assetManager, config = config)
    }

    override fun createStream(
        spotter: KeywordSpotter,
        keywordsSpec: String
    ): OnlineStream? {
        var stream = if (keywordsSpec.isNotBlank()) {
            spotter.createStream(keywordsSpec)
        } else {
            spotter.createStream("")
        }
        if (stream.ptr == 0L) {
            stream = spotter.createStream("")
        }
        return if (stream.ptr != 0L) stream else null
    }

    override fun acceptWaveform(
        stream: OnlineStream,
        samples: FloatArray,
        sampleRate: Int
    ) {
        if (stream.ptr != 0L && samples.isNotEmpty()) {
            stream.acceptWaveform(samples = samples, sampleRate = sampleRate)
        }
    }

    override fun isReady(
        spotter: KeywordSpotter,
        stream: OnlineStream
    ): Boolean {
        return stream.ptr != 0L && spotter.isReady(stream)
    }

    override fun decode(
        spotter: KeywordSpotter,
        stream: OnlineStream
    ) {
        if (stream.ptr != 0L) {
            spotter.decode(stream)
        }
    }

    override fun getResult(
        spotter: KeywordSpotter,
        stream: OnlineStream
    ): KeywordSpotterResult {
        return spotter.getResult(stream)
    }

    override fun reset(
        spotter: KeywordSpotter,
        stream: OnlineStream
    ) {
        if (stream.ptr != 0L) {
            spotter.reset(stream)
        }
    }

    override fun releaseStream(stream: OnlineStream?) {
        try {
            if (stream != null && stream.ptr != 0L) {
                stream.release()
            }
        } catch (_: Throwable) {
        }
    }

    override fun releaseSpotter(spotter: KeywordSpotter?) {
        try {
            spotter?.release()
        } catch (_: Throwable) {
        }
    }
}
