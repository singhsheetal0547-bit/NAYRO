package com.advanceagent.core.wakeword

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

class SherpaWakeWordEngine(
    private val context: Context? = null,
    private val modelDirInAssets: String = "kws-model",
    var cooldownMs: Long = 2200L,
    val jniBridge: SherpaOnnxJniBridge = OfficialSherpaOnnxJniBridge
) {
    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null
    private var tokenizer: WakeWordTokenizer? = null
    private var lastTriggerTimestampMs: Long = 0L

    @Volatile
    var isNativeRuntimeActive: Boolean = false
        private set

    @Volatile
    var lastInitError: String? = null
        private set

    var currentTimeProvider: () -> Long = { System.currentTimeMillis() }

    /**
     * Builds the official Sherpa-ONNX [KeywordSpotterConfig] pointing to the bundled
     * pretrained GigaSpeech Zipformer INT8 ONNX models inside `assets/kws-model/`.
     * When loaded via Android [android.content.res.AssetManager], all paths (including
     * `keywordsFile`) MUST be asset-relative paths.
     */
    fun buildKeywordSpotterConfig(
        boostingScore: Float = 2.0f,
        triggerThreshold: Float = 0.25f
    ): KeywordSpotterConfig {
        return KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "$modelDirInAssets/$ENCODER_ASSET_NAME",
                    decoder = "$modelDirInAssets/$DECODER_ASSET_NAME",
                    joiner = "$modelDirInAssets/$JOINER_ASSET_NAME"
                ),
                tokens = "$modelDirInAssets/$TOKENS_ASSET_NAME",
                numThreads = 2,
                debug = false,
                provider = "cpu",
                modelType = "zipformer2"
            ),
            maxActivePaths = 4,
            keywordsFile = "$modelDirInAssets/$KEYWORDS_ASSET_NAME",
            keywordsScore = boostingScore,
            keywordsThreshold = triggerThreshold
        )
    }

    @Synchronized
    fun initialize(
        activeWakeWords: List<String>,
        boostingScore: Float = 2.0f,
        triggerThreshold: Float = 0.25f
    ): Boolean {
        release()
        lastInitError = null
        val ctx = context
        return try {
            val tokensPath = "$modelDirInAssets/$TOKENS_ASSET_NAME"
            val resolvedTokenizer = if (ctx != null) {
                WakeWordTokenizer(ctx.assets, tokensPath)
            } else {
                WakeWordTokenizer(WakeWordTokenizer.DEFAULT_FALLBACK_VOCAB)
            }
            tokenizer = resolvedTokenizer

            val defaultWords = listOf("HI BABU", "HEY BABU", "HI NAYRO")
            val keywordsSpec = resolvedTokenizer.buildKeywordsSpec(
                phrases = activeWakeWords.ifEmpty { defaultWords },
                boostingScore = boostingScore,
                triggerThreshold = triggerThreshold
            )

            if (ctx == null) {
                lastInitError = "Android Context / AssetManager is required to initialize native Sherpa-ONNX JNI."
                isNativeRuntimeActive = false
                return false
            }

            // Persist active tokenized spec for diagnostics/audit while loading base model from AssetManager
            try {
                File(ctx.filesDir, "active_keywords.txt").writeText(keywordsSpec)
            } catch (_: Exception) {
            }

            val config = buildKeywordSpotterConfig(
                boostingScore = boostingScore,
                triggerThreshold = triggerThreshold
            )

            val nativeSpotter = jniBridge.createKeywordSpotter(
                assetManager = ctx.assets,
                config = config
            )
            if (nativeSpotter == null) {
                lastInitError = jniBridge.lastNativeLoadError
                    ?: "Failed to initialize native Sherpa-ONNX KeywordSpotter via JNI bridge."
                isNativeRuntimeActive = false
                return false
            }

            val nativeStream = jniBridge.createStream(
                spotter = nativeSpotter,
                keywordsSpec = keywordsSpec
            )
            if (nativeStream == null) {
                jniBridge.releaseSpotter(nativeSpotter)
                lastInitError = "Sherpa-ONNX created a null OnlineStream (ptr == 0)."
                isNativeRuntimeActive = false
                return false
            }

            spotter = nativeSpotter
            stream = nativeStream
            isNativeRuntimeActive = true
            true
        } catch (t: Throwable) {
            isNativeRuntimeActive = false
            lastInitError = "Sherpa-ONNX native initialization failed: ${t.javaClass.simpleName}: ${t.message}"
            try {
                Log.e(TAG, lastInitError, t)
            } catch (_: Throwable) {
            }
            false
        }
    }

    @Synchronized
    fun reloadWakeWords(
        activeWakeWords: List<String>,
        boostingScore: Float = 2.0f,
        triggerThreshold: Float = 0.25f
    ): Boolean {
        val currentSpotter = spotter ?: return initialize(activeWakeWords, boostingScore, triggerThreshold)
        val spec = tokenizer?.buildKeywordsSpec(
            phrases = activeWakeWords,
            boostingScore = boostingScore,
            triggerThreshold = triggerThreshold
        ) ?: return false
        return try {
            val nextStream = jniBridge.createStream(
                spotter = currentSpotter,
                keywordsSpec = spec
            )
            if (nextStream == null) {
                lastInitError = "Sherpa-ONNX hot-reload produced null OnlineStream."
                return false
            }
            jniBridge.releaseStream(stream)
            stream = nextStream
            true
        } catch (t: Throwable) {
            lastInitError = "Failed to hot-reload Sherpa-ONNX wake words: ${t.message}"
            false
        }
    }

    @Synchronized
    fun processPcm16(pcm16: ShortArray, length: Int): String? {
        val kws = spotter ?: return null
        val s = stream ?: return null
        if (!isNativeRuntimeActive) return null

        val safeLen = length.coerceIn(0, pcm16.size)
        if (safeLen == 0) return null

        val floatSamples = FloatArray(safeLen) { i -> pcm16[i] / 32768.0f }
        jniBridge.acceptWaveform(s, floatSamples, 16000)

        while (jniBridge.isReady(kws, s)) {
            jniBridge.decode(kws, s)
            val result = jniBridge.getResult(kws, s)
            val matched = result.keyword.trim().replace('_', ' ')
            if (matched.isNotBlank()) {
                jniBridge.reset(kws, s)
                if (!shouldAllowTrigger()) {
                    return null
                }
                return matched
            }
        }
        return null
    }

    @Synchronized
    internal fun shouldAllowTrigger(): Boolean {
        val now = currentTimeProvider()
        if (now - lastTriggerTimestampMs < cooldownMs && lastTriggerTimestampMs > 0L) {
            return false
        }
        lastTriggerTimestampMs = now
        return true
    }

    @Synchronized
    fun resetCooldown() {
        lastTriggerTimestampMs = 0L
    }

    @Synchronized
    fun release() {
        try {
            jniBridge.releaseStream(stream)
            jniBridge.releaseSpotter(spotter)
        } catch (_: Throwable) {
        } finally {
            stream = null
            spotter = null
            isNativeRuntimeActive = false
        }
    }

    companion object {
        private const val TAG = "SherpaWakeWordEngine"
        const val ENCODER_ASSET_NAME = "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        const val DECODER_ASSET_NAME = "decoder-epoch-12-avg-2-chunk-16-left-64.onnx"
        const val JOINER_ASSET_NAME = "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        const val TOKENS_ASSET_NAME = "tokens.txt"
        const val KEYWORDS_ASSET_NAME = "keywords.txt"
    }
}
