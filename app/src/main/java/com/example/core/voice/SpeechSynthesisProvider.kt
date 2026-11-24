package com.example.core.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.example.core.agent.ConversationalIntelligenceEngine
import com.example.core.agent.ConversationLanguage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

data class TtsSynthesisConfig(
    val languageTag: String = "en-IN",
    val speechRate: Float = 1.0f,
    val pitch: Float = 1.0f,
    val voiceGender: com.example.core.identity.VoiceGender = com.example.core.identity.VoiceGender.SYSTEM_DEFAULT,
    val preferredVoiceName: String? = null,
    val autoDetectUtteranceLanguage: Boolean = true
)

data class TtsVoiceDescriptor(
    val name: String,
    val localeTag: String,
    val isNetworkConnectionRequired: Boolean = false
)

/**
 * Independent, extensible Speech-Synthesis (TTS) provider interface for NAYRO.
 * Supports Android native TextToSpeech and future configurable cloud/local TTS providers
 * with automatic Hindi/English/Hinglish locale resolution, rate/voice control, and barge-in stop.
 */
interface SpeechSynthesisProvider {
    val providerId: String
    val displayName: String
    val isReady: StateFlow<Boolean>
    val isSpeaking: StateFlow<Boolean>

    fun configure(config: TtsSynthesisConfig)
    fun resolveLocaleForText(text: String, fallbackLanguageTag: String = "en-IN"): Locale
    fun listAvailableVoices(): List<TtsVoiceDescriptor>
    fun speak(
        text: String,
        utteranceId: String = UUID.randomUUID().toString(),
        onStart: (() -> Unit)? = null,
        onDone: ((String) -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ): Boolean
    fun stop()
    fun release()
}

/**
 * Default Android native [TextToSpeech] implementation of [SpeechSynthesisProvider].
 * Supports natural Hindi (`hi-IN`), English (`en-IN` / `en-US`), and Hinglish voice selection,
 * configurable speech rate/pitch, and immediate barge-in interruption via [stop].
 */
class AndroidSpeechSynthesisProvider(
    private val context: Context? = null
) : SpeechSynthesisProvider, TextToSpeech.OnInitListener {

    override val providerId: String = "android_native_tts"
    override val displayName: String = "Android Native TextToSpeech"

    private val _isReady = MutableStateFlow(false)
    override val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    override val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private var tts: TextToSpeech? = null
    private var config: TtsSynthesisConfig = TtsSynthesisConfig()

    @Volatile
    private var activeOnStart: (() -> Unit)? = null
    @Volatile
    private var activeOnDone: ((String) -> Unit)? = null
    @Volatile
    private var activeOnError: ((String) -> Unit)? = null

    init {
        if (context != null) {
            try {
                tts = TextToSpeech(context, this)
            } catch (_: Throwable) {
                _isReady.value = false
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            _isReady.value = true
            applyConfigToEngine()
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    _isSpeaking.value = true
                    activeOnStart?.invoke()
                }

                override fun onDone(utteranceId: String?) {
                    _isSpeaking.value = false
                    activeOnDone?.invoke(utteranceId.orEmpty())
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    _isSpeaking.value = false
                    activeOnError?.invoke("TTS synthesis failed for utterance ${utteranceId.orEmpty()}")
                }
            })
        } else {
            _isReady.value = false
        }
    }

    override fun configure(config: TtsSynthesisConfig) {
        this.config = config
        applyConfigToEngine()
    }

    private fun applyConfigToEngine() {
        val engine = tts ?: return
        if (!_isReady.value) return
        try {
            val targetLocale = Locale.forLanguageTag(config.languageTag)
            engine.language = targetLocale
            engine.setSpeechRate(config.speechRate.coerceIn(0.5f, 2.0f))
            val effectivePitch = resolveEffectivePitch(config.pitch, config.voiceGender)
            engine.setPitch(effectivePitch)
            val preferred = config.preferredVoiceName
            if (!preferred.isNullOrBlank()) {
                engine.voices?.firstOrNull { it.name.equals(preferred, ignoreCase = true) }?.let {
                    engine.voice = it
                    return
                }
            }
            selectVoiceForGender(engine, targetLocale, config.voiceGender)
        } catch (_: Throwable) {}
    }

    companion object {
        fun resolveEffectivePitch(basePitch: Float, gender: com.example.core.identity.VoiceGender): Float {
            val clamped = basePitch.coerceIn(0.6f, 1.6f)
            return when (gender) {
                com.example.core.identity.VoiceGender.MALE -> (clamped * 0.92f).coerceIn(0.6f, 1.6f)
                com.example.core.identity.VoiceGender.FEMALE -> (clamped * 1.06f).coerceIn(0.6f, 1.6f)
                com.example.core.identity.VoiceGender.SYSTEM_DEFAULT -> clamped
            }
        }

        fun selectVoiceForGender(
            engine: TextToSpeech,
            locale: Locale,
            gender: com.example.core.identity.VoiceGender
        ): Boolean {
            if (gender == com.example.core.identity.VoiceGender.SYSTEM_DEFAULT) return false
            val voices = try { engine.voices } catch (_: Throwable) { null } ?: return false
            val langPrefix = locale.language.lowercase()
            val matchingLocale = voices.filter { it.locale?.language?.lowercase() == langPrefix }
            val pool = matchingLocale.ifEmpty { voices.toList() }
            val desiredToken = if (gender == com.example.core.identity.VoiceGender.FEMALE) "female" else "male"
            val oppositeToken = if (gender == com.example.core.identity.VoiceGender.FEMALE) "male" else "female"
            val matched = pool.firstOrNull { v ->
                val n = v.name.lowercase()
                val features = try { v.features.orEmpty().joinToString(" ").lowercase() } catch (_: Throwable) { "" }
                val combined = "$n $features"
                combined.contains(desiredToken) && (desiredToken != "male" || !combined.contains("female"))
            } ?: pool.firstOrNull { v ->
                val n = v.name.lowercase()
                !n.contains(oppositeToken)
            }
            if (matched != null) {
                try {
                    engine.voice = matched
                    return true
                } catch (_: Throwable) {}
            }
            return false
        }
    }

    override fun resolveLocaleForText(text: String, fallbackLanguageTag: String): Locale {
        if (!config.autoDetectUtteranceLanguage) {
            return Locale.forLanguageTag(fallbackLanguageTag)
        }
        return when (ConversationalIntelligenceEngine.detectLanguage(text)) {
            ConversationLanguage.HINDI -> Locale.forLanguageTag("hi-IN")
            ConversationLanguage.HINGLISH -> Locale.forLanguageTag("hi-IN")
            ConversationLanguage.ENGLISH -> {
                if (fallbackLanguageTag.startsWith("en", ignoreCase = true)) {
                    Locale.forLanguageTag(fallbackLanguageTag)
                } else {
                    Locale.forLanguageTag("en-IN")
                }
            }
        }
    }

    override fun listAvailableVoices(): List<TtsVoiceDescriptor> {
        val engine = tts ?: return emptyList()
        return try {
            engine.voices.orEmpty().map { v ->
                TtsVoiceDescriptor(
                    name = v.name,
                    localeTag = v.locale.toLanguageTag(),
                    isNetworkConnectionRequired = v.isNetworkConnectionRequired
                )
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    override fun speak(
        text: String,
        utteranceId: String,
        onStart: (() -> Unit)?,
        onDone: ((String) -> Unit)?,
        onError: ((String) -> Unit)?
    ): Boolean {
        val clean = text.trim()
        if (clean.isEmpty()) return false
        val engine = tts ?: return false
        if (!_isReady.value) return false

        activeOnStart = onStart
        activeOnDone = onDone
        activeOnError = onError

        return try {
            val targetLocale = resolveLocaleForText(clean, config.languageTag)
            val availability = engine.isLanguageAvailable(targetLocale)
            if (availability >= TextToSpeech.LANG_AVAILABLE) {
                engine.language = targetLocale
            } else {
                engine.language = Locale.forLanguageTag("en-IN")
            }
            engine.setSpeechRate(config.speechRate.coerceIn(0.5f, 2.0f))
            engine.setPitch(config.pitch.coerceIn(0.5f, 2.0f))
            val result = engine.speak(clean, TextToSpeech.QUEUE_FLUSH, Bundle(), utteranceId)
            result == TextToSpeech.SUCCESS
        } catch (e: Throwable) {
            _isSpeaking.value = false
            onError?.invoke(e.localizedMessage ?: e.javaClass.simpleName)
            false
        }
    }

    override fun stop() {
        _isSpeaking.value = false
        try {
            tts?.stop()
        } catch (_: Throwable) {}
    }

    override fun release() {
        stop()
        try {
            tts?.shutdown()
        } catch (_: Throwable) {}
        tts = null
        _isReady.value = false
    }
}
