package com.example.core.voice

import android.content.Context
import android.media.MediaPlayer
import com.example.core.agent.ConversationalIntelligenceEngine
import com.example.core.agent.ConversationLanguage
import com.example.core.security.EncryptedStorageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * OpenAI-compatible cloud TTS provider.
 *
 * The endpoint must be HTTPS and return audio bytes for POST /v1/audio/speech.
 * API keys are stored using Android Keystore via EncryptedStorageManager.
 * Nothing is synthesized locally by this provider: network/API errors are surfaced to the caller.
 */
class CloudSpeechSynthesisProvider(
    context: Context?,
    private val secureStorage: EncryptedStorageManager,
    private val httpClient: OkHttpClient = OkHttpClient()
) : SpeechSynthesisProvider {

    companion object {
        const val PROVIDER_ID = "openai_compatible_cloud_tts"
        private const val KEY_ENDPOINT = "cloud_tts_endpoint"
        private const val KEY_MODEL = "cloud_tts_model"
        private const val KEY_VOICE = "cloud_tts_voice"
        private const val DEFAULT_MODEL = "gpt-4o-mini-tts"
        private const val DEFAULT_VOICE = "alloy"
    }

    private val appContext = context?.applicationContext
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val lock = Any()
    private var config = TtsSynthesisConfig()
    private var player: MediaPlayer? = null
    private var activeRequest: okhttp3.Call? = null
    private var activeAudioFile: File? = null

    private val _isReady = MutableStateFlow(isConfigurationReady())
    override val isReady: StateFlow<Boolean> = _isReady.asStateFlow()
    private val _isSpeaking = MutableStateFlow(false)
    override val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    override val providerId: String = PROVIDER_ID
    override val displayName: String = "OpenAI-compatible Cloud TTS"

    fun configureEndpoint(endpoint: String, model: String, voice: String, apiKey: String) {
        val cleanEndpoint = endpoint.trim().trimEnd('/')
        val cleanModel = model.trim().ifBlank { DEFAULT_MODEL }
        val cleanVoice = voice.trim().ifBlank { DEFAULT_VOICE }
        require(cleanEndpoint.startsWith("https://", ignoreCase = true)) {
            "Cloud TTS endpoint must use HTTPS."
        }
        require(cleanEndpoint.length <= 512) { "Cloud TTS endpoint is too long." }
        require(cleanModel.length <= 120 && cleanVoice.length <= 80) {
            "Cloud TTS model or voice value is too long."
        }
        secureStorage.saveApiKey(PROVIDER_ID, apiKey)
        secureStorage.savePreference(KEY_ENDPOINT, cleanEndpoint)
        secureStorage.savePreference(KEY_MODEL, cleanModel)
        secureStorage.savePreference(KEY_VOICE, cleanVoice)
        _isReady.value = isConfigurationReady()
    }

    fun deleteCredentials() {
        secureStorage.removeApiKey(PROVIDER_ID)
        _isReady.value = false
    }

    fun hasApiKey(): Boolean = secureStorage.hasApiKey(PROVIDER_ID)

    fun configuredEndpoint(): String = secureStorage.getPreference(KEY_ENDPOINT)
    fun configuredModel(): String = secureStorage.getPreference(KEY_MODEL, DEFAULT_MODEL)
    fun configuredVoice(): String = secureStorage.getPreference(KEY_VOICE, DEFAULT_VOICE)

    private fun isConfigurationReady(): Boolean {
        val endpoint = secureStorage.getPreference(KEY_ENDPOINT)
        return appContext != null &&
            endpoint.startsWith("https://", ignoreCase = true) &&
            secureStorage.hasApiKey(PROVIDER_ID)
    }

    override fun configure(config: TtsSynthesisConfig) {
        this.config = config
    }

    override fun resolveLocaleForText(text: String, fallbackLanguageTag: String): Locale {
        return if (!config.autoDetectUtteranceLanguage) {
            Locale.forLanguageTag(fallbackLanguageTag)
        } else {
            when (ConversationalIntelligenceEngine.detectLanguage(text)) {
                ConversationLanguage.HINDI, ConversationLanguage.HINGLISH -> Locale.forLanguageTag("hi-IN")
                ConversationLanguage.ENGLISH -> Locale.forLanguageTag(
                    if (fallbackLanguageTag.startsWith("en", true)) fallbackLanguageTag else "en-IN"
                )
            }
        }
    }

    override fun listAvailableVoices(): List<TtsVoiceDescriptor> = emptyList()

    override fun speak(
        text: String,
        utteranceId: String,
        onStart: (() -> Unit)?,
        onDone: ((String) -> Unit)?,
        onError: ((String) -> Unit)?
    ): Boolean {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) return false
        if (!_isReady.value || !isConfigurationReady()) {
            onError?.invoke("Cloud TTS is not configured. Select Android TTS or add an HTTPS endpoint and API key.")
            return false
        }

        synchronized(lock) {
            releasePlayerLocked()
            activeRequest?.cancel()
            _isSpeaking.value = false
        }

        executor.execute {
            var audioFile: File? = null
            try {
                val base = configuredEndpoint().trimEnd('/')
                val speechUrl = when {
                    base.endsWith("/v1/audio/speech", true) -> base
                    base.endsWith("/v1", true) -> "$base/audio/speech"
                    else -> "$base/v1/audio/speech"
                }
                val lang = resolveLocaleForText(cleanText, config.languageTag).language
                val requestJson = JSONObject()
                    .put("model", configuredModel())
                    .put("input", cleanText)
                    .put("voice", configuredVoice())
                    .put("response_format", "mp3")
                    .put("speed", config.speechRate.coerceIn(0.5f, 2.0f))
                    .put("language", lang)
                val body = requestJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder()
                    .url(speechUrl)
                    .header("Authorization", "Bearer ${secureStorage.getApiKey(PROVIDER_ID)}")
                    .post(body)
                    .build()
                val call = httpClient.newCall(request)
                synchronized(lock) { activeRequest = call }
                val bytes = call.execute().use { response ->
                    if (!response.isSuccessful) {
                        val detail = response.body?.string()?.take(500).orEmpty()
                        throw IOException("Cloud TTS HTTP ${response.code}: ${detail.ifBlank { response.message }}")
                    }
                    val responseBody = response.body ?: throw IOException("Cloud TTS returned an empty response body.")
                    val contentType = response.header("Content-Type").orEmpty()
                    if (contentType.contains("application/json", true)) {
                        throw IOException("Cloud TTS returned JSON instead of audio. Check the endpoint and model.")
                    }
                    responseBody.bytes().also {
                        if (it.isEmpty()) throw IOException("Cloud TTS returned an empty audio file.")
                    }
                }

                val cache = appContext?.cacheDir ?: throw IOException("Cloud TTS requires an Android cache directory.")
                audioFile = File(cache, "nayro-tts-${UUID.randomUUID()}.mp3")
                audioFile.writeBytes(bytes)
                val file = audioFile
                val mediaPlayer = MediaPlayer()
                synchronized(lock) {
                    player = mediaPlayer
                    activeAudioFile = file
                    activeRequest = null
                }
                mediaPlayer.setOnPreparedListener { mp ->
                    _isSpeaking.value = true
                    onStart?.invoke()
                    try {
                        mp.start()
                    } catch (e: Throwable) {
                        finishWithError("Cloud TTS playback failed: ${e.localizedMessage ?: e.javaClass.simpleName}", onError)
                    }
                }
                mediaPlayer.setOnCompletionListener {
                    _isSpeaking.value = false
                    synchronized(lock) { releasePlayerLocked() }
                    onDone?.invoke(utteranceId)
                }
                mediaPlayer.setOnErrorListener { _, what, extra ->
                    finishWithError("Cloud TTS media playback error ($what/$extra).", onError)
                    true
                }
                mediaPlayer.setDataSource(file.absolutePath)
                mediaPlayer.prepareAsync()
            } catch (e: Throwable) {
                audioFile?.delete()
                synchronized(lock) {
                    activeRequest = null
                    _isSpeaking.value = false
                }
                onError?.invoke(e.localizedMessage ?: "Cloud TTS request failed.")
            }
        }
        return true
    }

    private fun finishWithError(message: String, onError: ((String) -> Unit)?) {
        synchronized(lock) {
            _isSpeaking.value = false
            releasePlayerLocked()
        }
        onError?.invoke(message)
    }

    private fun releasePlayerLocked() {
        try { player?.setOnPreparedListener(null) } catch (_: Throwable) {}
        try { player?.setOnCompletionListener(null) } catch (_: Throwable) {}
        try { player?.setOnErrorListener(null) } catch (_: Throwable) {}
        try { player?.stop() } catch (_: Throwable) {}
        try { player?.release() } catch (_: Throwable) {}
        player = null
        try { activeAudioFile?.delete() } catch (_: Throwable) {}
        activeAudioFile = null
    }

    override fun stop() {
        synchronized(lock) {
            activeRequest?.cancel()
            activeRequest = null
            _isSpeaking.value = false
            releasePlayerLocked()
        }
    }

    override fun release() {
        stop()
        executor.shutdownNow()
        _isReady.value = false
    }
}
