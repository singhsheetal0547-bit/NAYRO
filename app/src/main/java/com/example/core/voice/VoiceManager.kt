package com.example.core.voice

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Architecture Skeleton for Babu Voice Manager.
 * Coordinates local wake-word engine, microphone audio focus, ASR, and TTS lifecycle.
 */
class VoiceManager(private val context: Context) {

    private val _voiceState = MutableStateFlow(VoiceState.STANDBY)
    val voiceState: StateFlow<VoiceState> = _voiceState

    private val _configuration = MutableStateFlow(VoiceConfiguration())
    val configuration: StateFlow<VoiceConfiguration> = _configuration

    fun updateConfiguration(newConfig: VoiceConfiguration) {
        _configuration.value = newConfig
    }

    fun startListening() {
        _voiceState.value = VoiceState.LISTENING
    }

    fun stopListening() {
        _voiceState.value = VoiceState.STANDBY
    }

    fun speak(text: String) {
        _voiceState.value = VoiceState.SPEAKING
    }

    fun stopSpeaking() {
        _voiceState.value = VoiceState.STANDBY
    }
}
