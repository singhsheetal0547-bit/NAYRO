package com.example.core.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class ConfirmationManager {

    private val _pendingConfirmation = MutableStateFlow<PendingConfirmation?>(null)
    val pendingConfirmation: StateFlow<PendingConfirmation?> = _pendingConfirmation

    fun requestConfirmation(confirmation: PendingConfirmation) {
        _pendingConfirmation.value = confirmation
        AgentEventBus.emit(AgentEvent.ConfirmationNeeded(confirmation))
    }

    fun approve(): PendingConfirmation? {
        val current = _pendingConfirmation.value
        _pendingConfirmation.value = null
        return current
    }

    fun reject(): PendingConfirmation? {
        val current = _pendingConfirmation.value
        _pendingConfirmation.value = null
        return current
    }

    fun clear() {
        _pendingConfirmation.value = null
    }
}
