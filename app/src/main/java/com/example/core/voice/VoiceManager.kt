package com.example.core.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.example.core.voice.engine.LocalWakeWordEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * System Two — Application-Scoped Voice Intelligence Pipeline (BUG 14 Remediation).
 * Supports Hindi, English, and Hinglish.
 * Coordinates microphone resource ownership between MainActivity (UI) and NayroForegroundService
 * without callback clobbering or premature singleton destruction on Activity rotation/close.
 */
class VoiceManager(private val context: Context?) : TextToSpeech.OnInitListener {

    private val _voiceState = MutableStateFlow(VoiceState.STANDBY)
    val voiceState: StateFlow<VoiceState> = _voiceState.asStateFlow()

    private val _configuration = MutableStateFlow(VoiceConfiguration())
    val configuration: StateFlow<VoiceConfiguration> = _configuration.asStateFlow()

    private val _transcribedText = MutableStateFlow("")
    val transcribedText: StateFlow<String> = _transcribedText.asStateFlow()

    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    val wakeWordEngine = LocalWakeWordEngine(context)

    // Scoped listeners preventing MainActivity and NayroForegroundService from overwriting each other
    @Volatile
    private var uiSpeechListener: ((String) -> Unit)? = null

    @Volatile
    private var uiErrorListener: ((String) -> Unit)? = null

    @Volatile
    private var serviceSpeechListener: ((String) -> Unit)? = null

    @Volatile
    var isDestroyed: Boolean = false
        private set

    // Legacy properties routed to UI listener for source compatibility
    var onSpeechRecognized: ((String) -> Unit)?
        get() = uiSpeechListener ?: serviceSpeechListener
        set(value) {
            uiSpeechListener = value
        }

    var onSpeechError: ((String) -> Unit)?
        get() = uiErrorListener
        set(value) {
            uiErrorListener = value
        }

    init {
        if (context != null) {
            try {
                textToSpeech = TextToSpeech(context.applicationContext, this)
            } catch (_: Exception) {}
        }

        wakeWordEngine.onWakeWordDetected = { _ ->
            _voiceState.value = VoiceState.WAKE_DETECTED
            startListening()
        }
    }

    fun attachUiListener(onRecognized: (String) -> Unit, onError: ((String) -> Unit)? = null) {
        uiSpeechListener = onRecognized
        uiErrorListener = onError
    }

    fun detachUiListener() {
        uiSpeechListener = null
        uiErrorListener = null
    }

    fun attachServiceListener(onRecognized: (String) -> Unit) {
        serviceSpeechListener = onRecognized
    }

    fun detachServiceListener() {
        serviceSpeechListener = null
    }

    fun hasActiveUiListener(): Boolean = uiSpeechListener != null

    fun hasActiveServiceListener(): Boolean = serviceSpeechListener != null

    /**
     * Dispatches recognized speech to exactly one active consumer:
     * foreground UI if attached, otherwise background service listener.
     */
    fun dispatchRecognizedSpeech(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        _transcribedText.value = clean
        val target = uiSpeechListener ?: serviceSpeechListener
        target?.invoke(clean)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = textToSpeech?.setLanguage(Locale("hi", "IN"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                textToSpeech?.setLanguage(Locale("en", "IN"))
            }
        }
    }

    fun updateConfiguration(newConfig: VoiceConfiguration) {
        _configuration.value = newConfig
        if (newConfig.isWakeWordEnabled) {
            wakeWordEngine.startListening()
        } else {
            wakeWordEngine.stopListening()
        }
    }

    fun startListening() {
        val ctx = context ?: return
        wakeWordEngine.pauseForAsr()

        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            _voiceState.value = VoiceState.ERROR
            uiErrorListener?.invoke("Speech recognition service is not available on this Android device.")
            resumeWakeIfEnabled()
            return
        }

        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(ctx.applicationContext).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        _voiceState.value = VoiceState.LISTENING
                    }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        _voiceState.value = VoiceState.TRANSCRIBING
                    }
                    override fun onError(error: Int) {
                        _voiceState.value = VoiceState.STANDBY
                        val msg = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech input timed out"
                            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                            else -> "Recognition error (code $error)"
                        }
                        uiErrorListener?.invoke(msg)
                        resumeWakeIfEnabled()
                    }
                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull() ?: ""
                        _voiceState.value = VoiceState.STANDBY
                        if (text.isNotBlank()) {
                            dispatchRecognizedSpeech(text)
                        }
                        resumeWakeIfEnabled()
                    }
                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        matches?.firstOrNull()?.let { _transcribedText.value = it }
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, _configuration.value.language)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }

            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            _voiceState.value = VoiceState.ERROR
            uiErrorListener?.invoke("Failed to start speech recognition: ${e.localizedMessage ?: "Unknown error"}")
            resumeWakeIfEnabled()
        }
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
        _voiceState.value = VoiceState.STANDBY
        resumeWakeIfEnabled()
    }

    fun cancelListening() {
        speechRecognizer?.cancel()
        _voiceState.value = VoiceState.STANDBY
        resumeWakeIfEnabled()
    }

    fun speak(text: String) {
        if (text.isBlank()) return

        stopSpeaking()
        _voiceState.value = VoiceState.SPEAKING

        textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _voiceState.value = VoiceState.SPEAKING
            }
            override fun onDone(utteranceId: String?) {
                _voiceState.value = VoiceState.STANDBY
                resumeWakeIfEnabled()
            }
            override fun onError(utteranceId: String?) {
                _voiceState.value = VoiceState.STANDBY
                resumeWakeIfEnabled()
            }
        })

        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nayro_speech_${System.currentTimeMillis()}")
    }

    fun stopSpeaking() {
        if (textToSpeech?.isSpeaking == true) {
            textToSpeech?.stop()
        }
        _voiceState.value = VoiceState.STANDBY
        resumeWakeIfEnabled()
    }

    private fun resumeWakeIfEnabled() {
        if (_configuration.value.isWakeWordEnabled) {
            wakeWordEngine.startListening()
        }
    }

    /**
     * Called when an Activity unbinds/destroys. Detaches the UI listener without
     * destroying the application-scoped TTS/ASR or background service pipeline (BUG 14 Fix).
     */
    fun onActivityDestroyed() {
        detachUiListener()
    }

    /**
     * Shuts down all underlying speech and TTS resources when the application terminates.
     */
    fun destroy() {
        isDestroyed = true
        detachUiListener()
        detachServiceListener()
        stopSpeaking()
        stopListening()
        wakeWordEngine.stopListening()
        textToSpeech?.shutdown()
        speechRecognizer?.destroy()
    }
}
