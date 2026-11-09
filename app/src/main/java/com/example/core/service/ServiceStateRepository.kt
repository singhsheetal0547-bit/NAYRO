package com.example.core.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Authoritative in-memory state repository for all NAYRO background and overlay services.
 * Updated exclusively through real Android service lifecycle callbacks (onCreate, onStartCommand, onDestroy, exceptions).
 */
object ServiceStateRepository {

    private val _foregroundServiceStatus = MutableStateFlow(
        ServiceStatus(ServiceLifecycleState.STOPPED)
    )
    val foregroundServiceStatus: StateFlow<ServiceStatus> = _foregroundServiceStatus.asStateFlow()

    private val _floatingServiceStatus = MutableStateFlow(
        ServiceStatus(ServiceLifecycleState.STOPPED)
    )
    val floatingServiceStatus: StateFlow<ServiceStatus> = _floatingServiceStatus.asStateFlow()

    fun updateForegroundStatus(state: ServiceLifecycleState, error: String? = null) {
        _foregroundServiceStatus.value = ServiceStatus(state, error)
    }

    fun updateFloatingStatus(state: ServiceLifecycleState, error: String? = null) {
        _floatingServiceStatus.value = ServiceStatus(state, error)
    }
}
