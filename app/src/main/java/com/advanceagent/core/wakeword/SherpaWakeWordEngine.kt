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
    var cooldownMs: Long = 2200L
) {
    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null
    private var tokenizer: WakeWordTokenizer? = null
    private var lastTriggerTimestampMs: Long = 0L

    var currentTimeProvider: () -> Long = { System.currentTimeMillis() }

    @Synchronized
    fun initialize(
        activeWakeWords: List<String>,
        boostingScore: Float = 2.0f,
        triggerThreshold: Float = 0.25f
    ): Boolean {
        release()
        return try {
            val tokensPath = "$modelDirInAssets/tokens.txt"
            val resolvedTokenizer = if (context != null) {
                WakeWordTokenizer(context.assets, tokensPath)
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

            val keywordsFile = if (context != null) {
                File(context.filesDir, "active_keywords.txt").apply {
                    writeText(keywordsSpec)
                }
            } else {
                File.createTempFile("active_keywords", ".txt").apply {
                    deleteOnExit()
                    writeText(keywordsSpec)
                }
            }

            val config = KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = "$modelDirInAssets/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                        decoder = "$modelDirInAssets/decoder-epoch-12-avg-2-chunk-16-left-64.onnx",
                        joiner = "$modelDirInAssets/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
                    ),
                    tokens = tokensPath,
                    numThreads = 2,
                    debug = false,
                    provider = "cpu"
                ),
                maxActivePaths = 4,
                keywordsFile = keywordsFile.absolutePath,
                keywordsScore = boostingScore,
                keywordsThreshold = triggerThreshold
            )

            spotter = KeywordSpotter(assetManager = context?.assets, config = config)
            stream = spotter?.createStream(keywordsSpec)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Sherpa KWS", e)
            false
        }
    }

    @Synchronized
    fun reloadWakeWords(
        activeWakeWords: List<String>,
        boostingScore: Float = 2.0f,
        triggerThreshold: Float = 0.25f
    ) {
        val currentSpotter = spotter ?: run {
            initialize(activeWakeWords, boostingScore, triggerThreshold)
            return
        }
        val spec = tokenizer?.buildKeywordsSpec(
            phrases = activeWakeWords,
            boostingScore = boostingScore,
            triggerThreshold = triggerThreshold
        ) ?: return
        stream?.release()
        stream = currentSpotter.createStream(spec)
    }

    @Synchronized
    fun processPcm16(pcm16: ShortArray, length: Int): String? {
        val kws = spotter ?: return null
        val s = stream ?: return null

        val safeLen = length.coerceIn(0, pcm16.size)
        if (safeLen == 0) return null

        val floatSamples = FloatArray(safeLen) { i -> pcm16[i] / 32768.0f }
        s.acceptWaveform(floatSamples, 16000)

        while (kws.isReady(s)) {
            kws.decode(s)
            val result = kws.getResult(s)
            val matched = result.keyword.trim()
            if (matched.isNotBlank()) {
                kws.reset(s)
                val now = currentTimeProvider()
                // False-positive suppression & cooldown gate
                if (now - lastTriggerTimestampMs < cooldownMs && lastTriggerTimestampMs > 0L) {
                    return null
                }
                lastTriggerTimestampMs = now
                return matched
            }
        }
        return null
    }

    @Synchronized
    fun resetCooldown() {
        lastTriggerTimestampMs = 0L
    }

    @Synchronized
    fun release() {
        try {
            stream?.release()
            spotter?.release()
        } catch (_: Exception) {
        } finally {
            stream = null
            spotter = null
        }
    }

    companion object {
        private const val TAG = "SherpaWakeWordEngine"
    }
}
