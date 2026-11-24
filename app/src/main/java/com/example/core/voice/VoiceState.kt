package com.example.core.voice

/**
 * Authoritative Voice Assistant & Continuous Conversation Lifecycle States (Phase 1B & 1C).
 */
enum class VoiceState {
    IDLE,
    STANDBY,
    WAKE_DETECTED,
    LISTENING,
    TRANSCRIBING,
    THINKING,
    EXECUTING,
    SPEAKING,
    FOLLOW_UP_LISTENING,
    PAUSED,
    STOPPED,
    DISABLED,
    ERROR
}

/**
 * Tracks which subsystem currently owns the microphone or active audio session (Phase 1B).
 * Prevents AudioRecord (wake-word), SpeechRecognizer (ASR), and TextToSpeech (TTS) from conflicting.
 */
enum class AudioSessionOwner {
    NONE,
    WAKE_WORD_ENGINE,
    SPEECH_RECOGNIZER,
    ENROLLMENT_CAPTURE,
    TTS_PLAYBACK,
    PHONE_CALL_INTERRUPTION
}

data class VoiceConfiguration(
    val assistantName: String = "Babu",
    val isWakeWordEnabled: Boolean = false,
    val wakePhrase: String = "Hey Babu",
    val wakePhrases: List<String> = listOf("Hi Babu", "Hey Babu", "Hi Nayro"),
    val sensitivity: Float = 0.5f,
    val isTtsEnabled: Boolean = true,
    val speechRate: Float = 1.0f,
    val voicePitch: Float = 1.0f,
    val voiceGender: com.example.core.identity.VoiceGender = com.example.core.identity.VoiceGender.SYSTEM_DEFAULT,
    val languageTag: String = "en-IN",
    val isContinuousConversationEnabled: Boolean = true,
    val followUpTimeoutMs: Long = 8_000L,
    val preferOfflineSpeechRecognition: Boolean = false
)
