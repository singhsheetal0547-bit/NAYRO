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

/**
 * Selectable Text-to-Speech (TTS) engine types supported by NAYRO.
 */
enum class SpeechSynthesisEngineType(val displayName: String) {
    ANDROID_TTS("Android Native TextToSpeech")
}

data class VoiceProviderStatus(
    val selectedSttEngine: SpeechRecognitionEngineType = SpeechRecognitionEngineType.ANDROID_SPEECH_RECOGNIZER,
    val effectiveSttEngine: SpeechRecognitionEngineType = SpeechRecognitionEngineType.ANDROID_SPEECH_RECOGNIZER,
    val selectedTtsEngine: SpeechSynthesisEngineType = SpeechSynthesisEngineType.ANDROID_TTS,
    val hasDeepgramKey: Boolean = false,
    val fallbackActiveReason: String? = null,
    val localWakeWordCloudIndependent: Boolean = true
)

/**
 * Multi-provider voice router for NAYRO.
 *
 * Manages STT/TTS provider selection, Deepgram API key add/edit/delete/test via [EncryptedStorageManager],
 * and automatic failover from Deepgram Cloud STT to Android [SpeechRecognizer] whenever a key is
 * missing, deleted, or a cloud transcription error occurs.
 */
class VoiceProviderRouter(
    context: Context? = null,
    private val encryptedStorageManager: EncryptedStorageManager = EncryptedStorageManager(context),
    val androidSttProvider: AndroidSpeechRecognitionProvider = AndroidSpeechRecognitionProvider(context),
    val deepgramSttProvider: DeepgramSpeechRecognitionProvider = DeepgramSpeechRecognitionProvider(
        context = context,
        encryptedStorageManager = encryptedStorageManager
    ),
    val androidTtsProvider: SpeechSynthesisProvider = AndroidSpeechSynthesisProvider(context)
) {
    companion object {
        private const val PREFS_NAME = "nayro_voice_provider_prefs"
        private const val KEY_SELECTED_STT = "selected_stt_engine"
    }

    private val prefs: SharedPreferences? = context?.let {
        try {
            it.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        } catch (_: Throwable) {
            null
        }
    }

    private val _status = MutableStateFlow(computeStatus(loadPersistedSttEngine(), null))
    val status: StateFlow<VoiceProviderStatus> = _status.asStateFlow()

    private fun loadPersistedSttEngine(): SpeechRecognitionEngineType {
        val saved = prefs?.getString(KEY_SELECTED_STT, null) ?: return SpeechRecognitionEngineType.ANDROID_SPEECH_RECOGNIZER
        return try {
            SpeechRecognitionEngineType.valueOf(saved)
        } catch (_: IllegalArgumentException) {
            SpeechRecognitionEngineType.ANDROID_SPEECH_RECOGNIZER
        }
    }

    private fun computeStatus(
        selected: SpeechRecognitionEngineType,
        runtimeFallbackReason: String?
    ): VoiceProviderStatus {
        val hasDeepgramKey = encryptedStorageManager.hasApiKey(DeepgramSpeechRecognitionProvider.PROVIDER_ID)
        val (effective, reason) = when {
            selected == SpeechRecognitionEngineType.DEEPGRAM_CLOUD && !hasDeepgramKey -> {
                SpeechRecognitionEngineType.ANDROID_SPEECH_RECOGNIZER to
                    "Deepgram API key not configured; automatically using Android SpeechRecognizer."
            }
            selected == SpeechRecognitionEngineType.DEEPGRAM_CLOUD && runtimeFallbackReason != null -> {
                SpeechRecognitionEngineType.ANDROID_SPEECH_RECOGNIZER to runtimeFallbackReason
            }
            else -> selected to null
        }
        return VoiceProviderStatus(
            selectedSttEngine = selected,
            effectiveSttEngine = effective,
            selectedTtsEngine = SpeechSynthesisEngineType.ANDROID_TTS,
            hasDeepgramKey = hasDeepgramKey,
            fallbackActiveReason = reason,
            localWakeWordCloudIndependent = true
        )
    }

    fun selectSttEngine(engine: SpeechRecognitionEngineType): VoiceProviderStatus {
        prefs?.edit()?.putString(KEY_SELECTED_STT, engine.name)?.apply()
        val updated = computeStatus(engine, null)
        _status.value = updated
        return updated
    }

    fun saveDeepgramApiKey(apiKey: String): VoiceProviderStatus {
        encryptedStorageManager.saveApiKey(DeepgramSpeechRecognitionProvider.PROVIDER_ID, apiKey)
        val updated = computeStatus(_status.value.selectedSttEngine, null)
        _status.value = updated
        return updated
    }

    fun deleteDeepgramApiKey(): VoiceProviderStatus {
        encryptedStorageManager.removeApiKey(DeepgramSpeechRecognitionProvider.PROVIDER_ID)
        val updated = computeStatus(_status.value.selectedSttEngine, null)
        _status.value = updated
        return updated
    }

    fun hasDeepgramApiKey(): Boolean = encryptedStorageManager.hasApiKey(DeepgramSpeechRecognitionProvider.PROVIDER_ID)

    fun refreshStatus(): VoiceProviderStatus {
        val updated = computeStatus(_status.value.selectedSttEngine, null)
        _status.value = updated
        return updated
    }

    fun markDeepgramFallback(reason: String) {
        _status.value = computeStatus(_status.value.selectedSttEngine, reason)
    }

    /**
     * Resolves the currently active [SpeechRecognitionProvider], automatically falling back
     * to [androidSttProvider] when Deepgram is unconfigured.
     */
    fun resolveActiveSttProvider(): SpeechRecognitionProvider {
        val current = refreshStatus()
        return if (current.effectiveSttEngine == SpeechRecognitionEngineType.DEEPGRAM_CLOUD &&
            deepgramSttProvider.hasApiKey()
        ) {
            deepgramSttProvider
        } else {
            androidSttProvider
        }
    }

    fun resolveActiveTtsProvider(): SpeechSynthesisProvider = androidTtsProvider

    suspend fun testDeepgramConnection(): Pair<Boolean, String> {
        return deepgramSttProvider.testConnection()
    }
}
