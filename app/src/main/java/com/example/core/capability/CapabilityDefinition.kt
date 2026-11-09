package com.example.core.capability

/**
 * Standard capability identifiers in NAYRO.
 */
enum class CapabilityId {
    FLOATING_ASSISTANT,
    BACKGROUND_AGENT,
    MICROPHONE_ACCESS,
    NOTIFICATION_ACCESS,
    ACCESSIBILITY,
    SCREEN_CAPTURE,
    WAKE_WORD_READY,
    VOICE_INTERACTION_READY,
    DEVICE_AUTOMATION_READY
}

/**
 * Availability status for capabilities.
 */
enum class CapabilityStatus {
    AVAILABLE,
    BLOCKED_BY_PERMISSION,
    BLOCKED_BY_SETTINGS,
    BLOCKED_BY_ANDROID_VERSION,
    SERVICE_NOT_RUNNING,
    NOT_IMPLEMENTED,
    UNAVAILABLE
}

/**
 * Full definition of a NAYRO Capability.
 */
data class CapabilityDefinition(
    val id: CapabilityId,
    val displayName: String,
    val description: String,
    val requiredPermissions: List<String>,
    val requiredServices: List<String>,
    val minAndroidSdk: Int = 24,
    val activationPrerequisites: String,
    val isFutureCapability: Boolean = false
)

/**
 * Dynamically evaluated status of a capability.
 */
data class CapabilityEvaluation(
    val definition: CapabilityDefinition,
    val status: CapabilityStatus,
    val statusReason: String,
    val isActionable: Boolean,
    val actionLabel: String? = null
) {
    val isAvailable: Boolean get() = status == CapabilityStatus.AVAILABLE
}

