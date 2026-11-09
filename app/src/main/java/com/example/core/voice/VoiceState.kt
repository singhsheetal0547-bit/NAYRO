package com.example.core.voice

enum class VoiceState {
    STANDBY,
    WAKE_DETECTED,
    LISTENING,
    TRANSCRIBING,
    THINKING,
    SPEAKING,
    DISABLED,
    ERROR
}

data class VoiceConfiguration(
    val assistantName: String = "Babu",
    val wakePhrases: List<String> = listOf("Hi Babu", "Hey Babu", "Hi Nayro", "Hey Nayro", "Hey Cutie"),
    val isWakeWordEnabled: Boolean = false,
    val sensitivity: Float = 0.7f,
    val isScreenOffWakeEnabled: Boolean = false,
    val language: String = "en-IN", // Hindi/English/Hinglish
    val speechRate: Float = 1.0f
)
