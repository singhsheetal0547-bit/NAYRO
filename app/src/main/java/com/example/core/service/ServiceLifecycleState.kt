package com.example.core.service

/**
 * Genuine Android Service lifecycle states (Section 12).
 */
enum class ServiceLifecycleState {
    STOPPED,
    STARTING,
    RUNNING,
    ACTIVE,
    LISTENING,
    PROCESSING,
    PAUSED,
    LOCKED,
    WAITING_FOR_PERMISSION,
    RECOVERING,
    STOPPING,
    FAILED,
    ERROR,
    PERMISSION_DENIED;

    val isActiveOrRunning: Boolean
        get() = this == RUNNING ||
            this == ACTIVE ||
            this == LISTENING ||
            this == PROCESSING ||
            this == LOCKED ||
            this == WAITING_FOR_PERMISSION ||
            this == RECOVERING
}

data class ServiceStatus(
    val state: ServiceLifecycleState,
    val lastError: String? = null,
    val lastUpdatedTimestamp: Long = System.currentTimeMillis()
)

/**
 * Granular, independently verified Background Voice Pipeline lifecycle states (P0-3).
 * Ensures [BACKGROUND_VOICE_READY] is NEVER published merely because Android started the service:
 * every prerequisite (service foreground binding, microphone initialization, valid wake templates,
 * and active audio capture) must be verified first.
 */
enum class BackgroundVoicePipelineState {
    STOPPED,
    SERVICE_STARTING,
    SERVICE_RUNNING,
    MICROPHONE_INITIALIZED,
    WAKE_ENGINE_READY,
    AUDIO_CAPTURE_ACTIVE,
    BACKGROUND_VOICE_READY,
    DEGRADED,
    ERROR;

    val isOperational: Boolean
        get() = this == BACKGROUND_VOICE_READY
}

data class BackgroundVoicePipelineStatus(
    val state: BackgroundVoicePipelineState = BackgroundVoicePipelineState.STOPPED,
    val detail: String = "Background voice service is stopped.",
    val isMicrophoneVerified: Boolean = false,
    val isWakeEngineVerified: Boolean = false,
    val isAudioCaptureVerified: Boolean = false,
    val retryCount: Int = 0,
    val maxRetries: Int = 3,
    val isScreenOff: Boolean = false,
    val lastUpdatedTimestamp: Long = System.currentTimeMillis()
)


