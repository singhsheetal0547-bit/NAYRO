package com.example.core.voice

import android.content.Context
import android.content.SharedPreferences
import com.example.core.security.EncryptedStorageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Selectable Speech-to-Text (STT) engine types supported by NAYRO.
 * Local wake-word detection ALWAYS stays on-device and completely independent of cloud STT providers.
 */
enum class SpeechRecognitionEngineType(val displayName: String, val isCloud: Boolean) {
    ANDROID_SPEECH_RECOGNIZER("Android SpeechRecognizer (System / Local)", isCloud = false),
    DEEPGRAM_CLOUD("Deepgram Nova-2 (Cloud STT)", isCloud = true)
}

enum class SpeechSynthesisEngineType(val displayName: String, val isCloud: Boolean) {
    ANDROID_TTS("Android Native TextToSpeech", isCloud = false),
    OPENAI_COMPATIBLE_CLOUD("OpenAI-compatible Cloud TTS", isCloud = true)
}

data class VoiceProviderStatus(
    val selectedSttEngine: SpeechRecognitionEngineType = SpeechRecognitionEngineType.ANDROID_SPEECH_RECOGNIZER,
    val effectiveSttEngine: SpeechRecognitionEngineType = SpeechRecognitionEngineType.ANDROID_SPEECH_RECOGNIZER,
    val selectedTtsEngine: SpeechSynthesisEngineType = SpeechSynthesisEngineType.ANDROID_TTS,
    val effectiveTtsEngine: SpeechSynthesisEngineType = SpeechSynthesisEngineType.ANDROID_TTS,
    val hasDeepgramKey: Boolean = false,
    val hasCloudTtsKey: Boolean = false,
    val fallbackActiveReason: String? = null,
    val ttsFallbackActiveReason: String? = null,
    val localWakeWordCloudIndependent: Boolean = true
)

/**
 * Multi-provider voice router.
 * Missing or invalid cloud credentials always fall back to the existing Android-native engines.
 */
class VoiceProviderRouter(
    context: Context? = null,
    private val encryptedStorageManager: EncryptedStorageManager = EncryptedStorageManager(context),
    val androidSttProvider: AndroidSpeechRecognitionProvider = AndroidSpeechRecognitionProvider(context),
    val deepgramSttProvider: DeepgramSpeechRecognitionProvider = DeepgramSpeechRecognitionProvider(
        context = context,
        encryptedStorageManager = encryptedStorageManager
    ),
    val androidTtsProvider: SpeechSynthesisProvider = AndroidSpeechSynthesisProvider(context),
    val cloudTtsProvider: CloudSpeechSynthesisProvider = CloudSpeechSynthesisProvider(context, encryptedStorageManager)
) {
    companion object {
        private const val PREFS_NAME = "nayro_voice_provider_prefs"
        private const val KEY_SELECTED_STT = "selected_stt_engine"
        private const val KEY_SELECTED_TTS = "selected_tts_engine"
    }

    private val prefs: SharedPreferences? = context?.let {
        try {
            it.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        } catch (_: Throwable) {
            null
        }
    }

    private val _status = MutableStateFlow(
        computeStatus(loadPersistedSttEngine(), null, loadPersistedTtsEngine(), null)
    )
    val status: StateFlow<VoiceProviderStatus> = _status.asStateFlow()

    private fun loadPersistedSttEngine(): SpeechRecognitionEngineType {
        val saved = prefs?.getString(KEY_SELECTED_STT, null)
            ?: return SpeechRecognitionEngineType.ANDROID_SPEECH_RECOGNIZER
        return try {
            SpeechRecognitionEngineType.valueOf(saved)
        } catch (_: IllegalArgumentException) {
            SpeechRecognitionEngineType.ANDROID_SPEECH_RECOGNIZER
        }
    }

    private fun loadPersistedTtsEngine(): SpeechSynthesisEngineType {
        val saved = prefs?.getString(KEY_SELECTED_TTS, null)
            ?: return SpeechSynthesisEngineType.ANDROID_TTS
        return try {
            SpeechSynthesisEngineType.valueOf(saved)
        } catch (_: IllegalArgumentException) {
            SpeechSynthesisEngineType.ANDROID_TTS
        }
    }

    private fun computeStatus(
        selectedStt: SpeechRecognitionEngineType,
        sttRuntimeFallbackReason: String?,
        selectedTts: SpeechSynthesisEngineType,
        ttsRuntimeFallbackReason: String?
    ): VoiceProviderStatus {
        val hasDeepgramKey = encryptedStorageManager.hasApiKey(DeepgramSpeechRecognitionProvider.PROVIDER_ID)
        val (effectiveStt, sttReason) = when {
            selectedStt == SpeechRecognitionEngineType.DEEPGRAM_CLOUD && !hasDeepgramKey ->
                SpeechRecognitionEngineType.ANDROID_SPEECH_RECOGNIZER to
                    "Deepgram API key not configured; automatically using Android SpeechRecognizer."
            selectedStt == SpeechRecognitionEngineType.DEEPGRAM_CLOUD && sttRuntimeFallbackReason != null ->
                SpeechRecognitionEngineType.ANDROID_SPEECH_RECOGNIZER to sttRuntimeFallbackReason
            else -> selectedStt to null
        }

        val hasCloudTtsKey = cloudTtsProvider.hasApiKey()
        val cloudTtsReady = cloudTtsProvider.isReady.value
        val (effectiveTts, ttsReason) = when {
            selectedTts == SpeechSynthesisEngineType.OPENAI_COMPATIBLE_CLOUD && (!hasCloudTtsKey || !cloudTtsReady) ->
                SpeechSynthesisEngineType.ANDROID_TTS to
                    "Cloud TTS is not configured or unavailable; using Android Native TextToSpeech."
            selectedTts == SpeechSynthesisEngineType.OPENAI_COMPATIBLE_CLOUD && ttsRuntimeFallbackReason != null ->
                SpeechSynthesisEngineType.ANDROID_TTS to ttsRuntimeFallbackReason
            else -> selectedTts to null
        }

        return VoiceProviderStatus(
            selectedSttEngine = selectedStt,
            effectiveSttEngine = effectiveStt,
            selectedTtsEngine = selectedTts,
            effectiveTtsEngine = effectiveTts,
            hasDeepgramKey = hasDeepgramKey,
            hasCloudTtsKey = hasCloudTtsKey,
            fallbackActiveReason = sttReason,
            ttsFallbackActiveReason = ttsReason,
            localWakeWordCloudIndependent = true
        )
    }

    fun selectSttEngine(engine: SpeechRecognitionEngineType): VoiceProviderStatus {
        prefs?.edit()?.putString(KEY_SELECTED_STT, engine.name)?.apply()
        val updated = computeStatus(engine, null, _status.value.selectedTtsEngine, null)
        _status.value = updated
        return updated
    }

    fun selectTtsEngine(engine: SpeechSynthesisEngineType): VoiceProviderStatus {
        prefs?.edit()?.putString(KEY_SELECTED_TTS, engine.name)?.apply()
        val updated = computeStatus(_status.value.selectedSttEngine, null, engine, null)
        _status.value = updated
        return updated
    }

    fun saveDeepgramApiKey(apiKey: String): VoiceProviderStatus {
        encryptedStorageManager.saveApiKey(DeepgramSpeechRecognitionProvider.PROVIDER_ID, apiKey)
        val updated = computeStatus(_status.value.selectedSttEngine, null, _status.value.selectedTtsEngine, null)
        _status.value = updated
        return updated
    }

    fun deleteDeepgramApiKey(): VoiceProviderStatus {
        encryptedStorageManager.removeApiKey(DeepgramSpeechRecognitionProvider.PROVIDER_ID)
        val updated = computeStatus(_status.value.selectedSttEngine, null, _status.value.selectedTtsEngine, null)
        _status.value = updated
        return updated
    }

    fun saveCloudTtsConfiguration(endpoint: String, model: String, voice: String, apiKey: String): VoiceProviderStatus {
        cloudTtsProvider.configureEndpoint(endpoint, model, voice, apiKey)
        val updated = computeStatus(
            _status.value.selectedSttEngine, null,
            _status.value.selectedTtsEngine, null
        )
        _status.value = updated
        return updated
    }

    fun deleteCloudTtsCredentials(): VoiceProviderStatus {
        cloudTtsProvider.deleteCredentials()
        val updated = computeStatus(
            _status.value.selectedSttEngine, null,
            _status.value.selectedTtsEngine, null
        )
        _status.value = updated
        return updated
    }

    fun hasDeepgramApiKey(): Boolean = encryptedStorageManager.hasApiKey(DeepgramSpeechRecognitionProvider.PROVIDER_ID)
    fun hasCloudTtsApiKey(): Boolean = cloudTtsProvider.hasApiKey()

    fun refreshStatus(): VoiceProviderStatus {
        val updated = computeStatus(
            _status.value.selectedSttEngine, null,
            _status.value.selectedTtsEngine, null
        )
        _status.value = updated
        return updated
    }

    fun markDeepgramFallback(reason: String) {
        _status.value = computeStatus(
            _status.value.selectedSttEngine, reason,
            _status.value.selectedTtsEngine, null
        )
    }

    fun markCloudTtsFallback(reason: String) {
        _status.value = computeStatus(
            _status.value.selectedSttEngine, null,
            _status.value.selectedTtsEngine, reason
        )
    }

    fun resolveActiveSttProvider(): SpeechRecognitionProvider {
        val current = refreshStatus()
        return if (current.effectiveSttEngine == SpeechRecognitionEngineType.DEEPGRAM_CLOUD &&
            deepgramSttProvider.hasApiKey()
        ) deepgramSttProvider else androidSttProvider
    }

    fun resolveActiveTtsProvider(): SpeechSynthesisProvider {
        val current = refreshStatus()
        return if (current.effectiveTtsEngine == SpeechSynthesisEngineType.OPENAI_COMPATIBLE_CLOUD &&
            cloudTtsProvider.isReady.value
        ) cloudTtsProvider else androidTtsProvider
    }

    suspend fun testDeepgramConnection(): Pair<Boolean, String> = deepgramSttProvider.testConnection()
}
