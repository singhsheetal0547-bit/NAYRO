package com.example.core.capability

import android.os.Build

/**
 * Central registry of all current and planned NAYRO agent capabilities.
 */
object CapabilityRegistry {

    val definitions: List<CapabilityDefinition> = listOf(
        CapabilityDefinition(
            id = CapabilityId.FLOATING_ASSISTANT,
            displayName = "Floating Assistant (Babu Bubble)",
            description = "Persistent draggable assistant bubble and compact quick panel over any Android app.",
            requiredPermissions = listOf("android.permission.SYSTEM_ALERT_WINDOW"),
            requiredServices = listOf("NayroFloatingService"),
            minAndroidSdk = 24,
            activationPrerequisites = "Grant 'Display over other apps' special permission in Settings."
        ),
        CapabilityDefinition(
            id = CapabilityId.BACKGROUND_AGENT,
            displayName = "Background Agent Engine",
            description = "Maintains persistent standby service, agent lifecycle, and low-latency activation.",
            requiredPermissions = listOf("android.permission.FOREGROUND_SERVICE"),
            requiredServices = listOf("NayroForegroundService"),
            minAndroidSdk = 24,
            activationPrerequisites = "Foreground service notification active."
        ),
        CapabilityDefinition(
            id = CapabilityId.MICROPHONE_ACCESS,
            displayName = "Microphone Access",
            description = "Local voice input recording and wake-word audio analysis.",
            requiredPermissions = listOf("android.permission.RECORD_AUDIO"),
            requiredServices = emptyList(),
            minAndroidSdk = 24,
            activationPrerequisites = "Grant Microphone runtime permission."
        ),
        CapabilityDefinition(
            id = CapabilityId.NOTIFICATION_ACCESS,
            displayName = "Notification Assistance",
            description = "Displaying status notifications, reminders, and background execution alerts.",
            requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                listOf("android.permission.POST_NOTIFICATIONS")
            } else emptyList(),
            requiredServices = emptyList(),
            minAndroidSdk = 24,
            activationPrerequisites = "Allow notifications in Android app permissions."
        ),
        CapabilityDefinition(
            id = CapabilityId.ACCESSIBILITY,
            displayName = "Accessibility Service",
            description = "Screen reading context and user-authorized Android UI navigation assistance.",
            requiredPermissions = emptyList(),
            requiredServices = listOf("AccessibilityService"),
            minAndroidSdk = 24,
            activationPrerequisites = "Enable NAYRO in Android Accessibility Settings manually.",
            isFutureCapability = true
        ),
        CapabilityDefinition(
            id = CapabilityId.SCREEN_CAPTURE,
            displayName = "MediaProjection Screen Capture",
            description = "Real-time screen snapshot analysis for multimodal vision understanding.",
            requiredPermissions = emptyList(),
            requiredServices = emptyList(),
            minAndroidSdk = 24,
            activationPrerequisites = "User consent required per screen capture session.",
            isFutureCapability = true
        ),
        CapabilityDefinition(
            id = CapabilityId.WAKE_WORD_READY,
            displayName = "Local Wake-Word Engine",
            description = "On-device wake-phrase detection ('Hey Babu', 'Hi Nayro') without cloud streaming.",
            requiredPermissions = listOf("android.permission.RECORD_AUDIO", "android.permission.FOREGROUND_SERVICE"),
            requiredServices = listOf("NayroForegroundService"),
            minAndroidSdk = 24,
            activationPrerequisites = "Requires Microphone permission, Foreground Service, and DSP model enrollment.",
            isFutureCapability = true
        ),
        CapabilityDefinition(
            id = CapabilityId.VOICE_INTERACTION_READY,
            displayName = "Voice Interaction Pipeline (ASR + TTS)",
            description = "Speech recognition and speech synthesis in Hindi, English, and Hinglish.",
            requiredPermissions = listOf("android.permission.RECORD_AUDIO"),
            requiredServices = emptyList(),
            minAndroidSdk = 24,
            activationPrerequisites = "Requires SpeechRecognizer and TextToSpeech engine initialization.",
            isFutureCapability = true
        ),
        CapabilityDefinition(
            id = CapabilityId.DEVICE_AUTOMATION_READY,
            displayName = "Device Automation & Tools",
            description = "Autonomous task planning and native Android tool execution (Apps, Alarms, Reminders).",
            requiredPermissions = emptyList(),
            requiredServices = emptyList(),
            minAndroidSdk = 24,
            activationPrerequisites = "Requires Agent Runtime tool execution engine and risk verification.",
            isFutureCapability = true
        )
    )

    fun getById(id: CapabilityId): CapabilityDefinition? = definitions.firstOrNull { it.id == id }
}
