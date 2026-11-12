package com.example.core.service

/**
 * Genuine Android Service lifecycle states.
 */
enum class ServiceLifecycleState {
    STOPPED,
    STARTING,
    RUNNING,
    PAUSED,
    STOPPING,
    FAILED,
    PERMISSION_DENIED
}

data class ServiceStatus(
    val state: ServiceLifecycleState,
    val lastError: String? = null,
    val lastUpdatedTimestamp: Long = System.currentTimeMillis()
)
