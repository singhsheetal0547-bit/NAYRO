package com.advanceagent.core.wakeword

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

/**
 * Native Sherpa-ONNX Zipformer2/GigaSpeech open-vocabulary keyword spotter.
 * Model files are read from app/src/main/assets/kws-model using Android AssetManager.
 * The official sherpa-onnx AAR supplies the Kotlin API and JNI runtime.
 * Initialization failures are surfaced; no wake word is fabricated by a fallback.
 */
class SherpaWakeWordEngine(
    private val context: Context?,
    private val modelDirInAssets: String = "kws-model",
    var cooldownMs: Long = 2200L
) {
    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null
    private var activePhrases: List<String> = DEFAULT_WAKE_WORDS
    private var phraseByAlias: Map<String, String> = emptyMap()
    private var lastTriggerTimestampMs: Long = 0L

    @Volatile
    var lastErrorMessage: String? = null
        private set

    @Volatile
    var lastDetectionLatencyMs: Long = 0L
        private set

    var currentTimeProvider: () -> Long = { System.currentTimeMillis() }

    val isInitialized: Boolean
        @Synchronized get() = spotter != null && stream != null

    val keywordCount: Int
        @Synchronized get() = activePhrases.size

    fun areAssetsAvailable(): Boolean {
        val required = listOf(
            "encoder-epoch-12-avg-2-chunk-16-left-64.onnx",
            "decoder-epoch-12-avg-2-chunk-16-left-64.onnx",
            "joiner-epoch-12-avg-2-chunk-16-left-64.onnx",
            "tokens.txt",
            "keywords.txt"
        )
        val assetManager = context?.assets
        if (assetManager == null) {
            lastErrorMessage = "Android Context is unavailable; Sherpa-ONNX native inference requires an Android runtime."
            return false
        }
        return try {
            val names = assetManager.list(modelDirInAssets)?.toSet().orEmpty()
            required.all { name ->
                if (name !in names) return@all false
                assetManager.open("$modelDirInAssets/$name").use { it.read() >= 0 }
            }
        } catch (e: Throwable) {
            lastErrorMessage = "Sherpa model assets are unavailable: ${e.message ?: e.javaClass.simpleName}"
            false
        }
    }

    @Synchronized
    fun initialize(
        activeWakeWords: List<String>,
        boostingScore: Float = 2.0f,
        triggerThreshold: Float = 0.25f
    ): Boolean {
        release()
        lastErrorMessage = null

        val appContext = context
        if (appContext == null) {
            lastErrorMessage = "Android Context is unavailable; native Sherpa-ONNX initialization is not possible in a headless JVM test."
            // Do not call android.util.Log here: headless JVM tests intentionally have no Android runtime.
            return false
        }
        if (!areAssetsAvailable()) {
            lastErrorMessage = lastErrorMessage
                ?: "Missing encoder/decoder/joiner/tokens/keywords in assets/$modelDirInAssets."
            Log.e(TAG, lastErrorMessage!!)
            return false
        }

        return try {
            // keywords.txt was BPE-tokenized from the exact same model vocabulary by CI.
            // KeywordSpotter.createStream(String) expects encoded subword tokens, not raw text;
            // createStream() loads the verified pre-tokenized phrases from KeywordSpotterConfig.
            val phrases = resolvePhrases(activeWakeWords)
            val modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "$modelDirInAssets/encoder-epoch-12-avg-2-chunk-16-left-64.onnx",
                    decoder = "$modelDirInAssets/decoder-epoch-12-avg-2-chunk-16-left-64.onnx",
                    joiner = "$modelDirInAssets/joiner-epoch-12-avg-2-chunk-16-left-64.onnx"
                ),
                tokens = "$modelDirInAssets/tokens.txt",
                modelType = "zipformer2",
                numThreads = 2,
                debug = false,
                provider = "cpu"
            )
            val config = KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE_HZ, featureDim = 80),
                modelConfig = modelConfig,
                maxActivePaths = 4,
                keywordsFile = "$modelDirInAssets/keywords.txt",
                keywordsScore = boostingScore,
                keywordsThreshold = triggerThreshold
            )

            val nativeSpotter = KeywordSpotter(assetManager = appContext.assets, config = config)
            val nativeStream = nativeSpotter.createStream()

            spotter = nativeSpotter
            stream = nativeStream
            activePhrases = phrases
            phraseByAlias = phrases.associateBy(::toAlias)
            lastTriggerTimestampMs = 0L
            lastErrorMessage = null
            Log.i(TAG, "Native Sherpa-ONNX KWS ready; phrases=${phrases.joinToString()}")
            true
        } catch (e: Throwable) {
            release()
            lastErrorMessage = "Native Sherpa-ONNX initialization failed: ${e.message ?: e.javaClass.simpleName}"
            Log.e(TAG, lastErrorMessage!!, e)
            false
        }
    }

    @Synchronized
    fun reloadWakeWords(
        activeWakeWords: List<String>,
        boostingScore: Float = 2.0f,
        triggerThreshold: Float = 0.25f
    ): Boolean {
        val currentSpotter = spotter
        if (currentSpotter == null) return initialize(activeWakeWords, boostingScore, triggerThreshold)

        return try {
            val phrases = resolvePhrases(activeWakeWords)
            // Recreate from the model's pre-tokenized keywords.txt; never feed raw text to JNI.
            val replacement = currentSpotter.createStream()
            stream?.release()
            stream = replacement
            activePhrases = phrases
            phraseByAlias = phrases.associateBy(::toAlias)
            lastTriggerTimestampMs = 0L
            lastErrorMessage = null
            true
        } catch (e: Throwable) {
            lastErrorMessage = "Unable to reload Sherpa wake phrases: ${e.message ?: e.javaClass.simpleName}"
            Log.e(TAG, lastErrorMessage!!, e)
            false
        }
    }

    @Synchronized
    fun processPcm16(pcm16: ShortArray, length: Int): String? {
        val kws = spotter ?: return null
        val activeStream = stream ?: return null
        val safeLength = length.coerceIn(0, pcm16.size)
        if (safeLength == 0) return null

        val startedAt = System.nanoTime()
        val samples = FloatArray(safeLength) { index -> pcm16[index] / 32768.0f }
        activeStream.acceptWaveform(samples, sampleRate = SAMPLE_RATE_HZ)

        while (kws.isReady(activeStream)) {
            kws.decode(activeStream)
            val rawKeyword = kws.getResult(activeStream).keyword.trim()
            if (rawKeyword.isNotEmpty()) {
                kws.reset(activeStream)
                val now = currentTimeProvider()
                if (lastTriggerTimestampMs > 0L && now - lastTriggerTimestampMs < cooldownMs) return null

                val alias = rawKeyword.substringAfterLast("@", rawKeyword)
                    .trim()
                    .replace(' ', '_')
                    .uppercase()
                val normalized = rawKeyword
                    .substringBeforeLast("@", rawKeyword)
                    .replace("▁", " ")
                    .replace('_', ' ')
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .uppercase()
                val matchedPhrase = phraseByAlias[alias]
                    ?: activePhrases.firstOrNull { it.uppercase() == normalized }
                    ?: run {
                        Log.w(TAG, "Sherpa returned an unknown keyword label; ignoring it: $rawKeyword")
                        return null
                    }

                lastTriggerTimestampMs = now
                lastDetectionLatencyMs = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(1L)
                return matchedPhrase
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
        try { stream?.release() } catch (_: Throwable) { }
        try { spotter?.release() } catch (_: Throwable) { }
        stream = null
        spotter = null
    }

    private fun resolvePhrases(input: List<String>): List<String> {
        // keywords.txt inside this APK contains all four pre-tokenized phrases. Keep the
        // alias map aligned with that complete file; custom phrases are not claimed as active.
        val unsupported = input.map { it.trim() }
            .filter { candidate ->
                candidate.isNotEmpty() && DEFAULT_WAKE_WORDS.none { it.equals(candidate, ignoreCase = true) }
            }
        if (unsupported.isNotEmpty()) {
            Log.w(TAG, "Wake phrases not included in the packaged BPE model were ignored: ${unsupported.joinToString()}")
        }
        return DEFAULT_WAKE_WORDS
    }

    private fun toAlias(phrase: String): String =
        phrase.uppercase().replace(Regex("[^A-Z0-9]+"), "_").trim('_')

    companion object {
        private const val TAG = "SherpaWakeWordEngine"
        private const val SAMPLE_RATE_HZ = 16000
        private val DEFAULT_WAKE_WORDS = listOf("Hi Babu", "Hey Babu", "Hi Nayro", "Hey Nayro")
    }
}
