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
import com.example.core.voice.engine.WakeEngineState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * System Two — Voice Intelligence Pipeline (Speech-to-Text & Text-to-Speech)
 * Supports Hindi, English, and Hinglish.
 * Coordinates microphone resource ownership with LocalWakeWordEngine.
 */
class VoiceManager(private val context: Context) : TextToSpeech.OnInitListener {

    private val _voiceState = MutableStateFlow(VoiceState.STANDBY)
    val voiceState: StateFlow<VoiceState> = _voiceState.asStateFlow()

    private val _configuration = MutableStateFlow(VoiceConfiguration())
    val configuration: StateFlow<VoiceConfiguration> = _configuration.asStateFlow()

    private val _transcribedText = MutableStateFlow("")
    val transcribedText: StateFlow<String> = _transcribedText.asStateFlow()

    // Engines
    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    val wakeWordEngine = LocalWakeWordEngine(context)

    var onSpeechRecognized: ((String) -> Unit)? = null
    var onSpeechError: ((String) -> Unit)? = null

    init {
        textToSpeech = TextToSpeech(context, this)

        wakeWordEngine.onWakeWordDetected = { phrase ->
            _voiceState.value = VoiceState.WAKE_DETECTED
            startListening()
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = textToSpeech?.setLanguage(Locale("hi", "IN"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                // Fallback to English (India)
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
        // Pause standby wake-word capture so SpeechRecognizer has exclusive microphone access
        wakeWordEngine.pauseForAsr()

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _voiceState.value = VoiceState.ERROR
            onSpeechError?.invoke("Speech recognition service is not available on this Android device.")
            resumeWakeIfEnabled()
            return
        }

        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
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
                        onSpeechError?.invoke(msg)
                        resumeWakeIfEnabled()
                    }
                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull() ?: ""
                        _transcribedText.value = text
                        _voiceState.value = VoiceState.STANDBY
                        if (text.isNotBlank()) {
                            onSpeechRecognized?.invoke(text)
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
            onSpeechError?.invoke("Failed to start speech recognition: ${e.localizedMessage ?: "Unknown error"}")
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

    fun destroy() {
        stopSpeaking()
        stopListening()
        wakeWordEngine.stopListening()
        textToSpeech?.shutdown()
        speechRecognizer?.destroy()
    }
}
