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

