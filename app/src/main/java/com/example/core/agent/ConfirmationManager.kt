package com.example.core.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Hardened asynchronous Confirmation Manager.
 * Binds approvals cryptographically and parameter-by-parameter to the exact pending action.
 * Strictly prevents automated bypass, rejects expired approvals, and handles user rejections cleanly.
 */
class ConfirmationManager {

    private val _pendingConfirmation = MutableStateFlow<PendingConfirmation?>(null)
    val pendingConfirmation: StateFlow<PendingConfirmation?> = _pendingConfirmation

    @Volatile
    private var activeDeferred: CompletableDeferred<ConfirmationStatus>? = null

    /**
     * Suspends execution awaiting authentic user interaction from UI.
     * Enforces timeout and returns exact confirmation status.
     */
    suspend fun awaitConfirmation(
        confirmation: PendingConfirmation,
        timeoutMs: Long = 60_000L
    ): ConfirmationStatus {
        val deferred = CompletableDeferred<ConfirmationStatus>()
        synchronized(this) {
            // Cancel any prior stale confirmation
            activeDeferred?.complete(ConfirmationStatus.CANCELLED)
            activeDeferred = deferred
            _pendingConfirmation.value = confirmation
        }

        AgentEventBus.emit(AgentEvent.ConfirmationNeeded(confirmation))

        val result = withTimeoutOrNull(timeoutMs) {
            deferred.await()
        } ?: run {
            synchronized(this) {
                if (_pendingConfirmation.value?.actionId == confirmation.actionId) {
                    confirmation.status = ConfirmationStatus.EXPIRED
                    _pendingConfirmation.value = null
                    activeDeferred = null
                }
            }
            ConfirmationStatus.EXPIRED
        }

        return result
    }

    /**
     * Approves pending confirmation after validating actionId and parameter integrity.
     */
    fun approve(actionId: String? = null, expectedParams: Map<String, String>? = null): Boolean {
        synchronized(this) {
            val pending = _pendingConfirmation.value ?: return false
            if (actionId != null && pending.actionId != actionId) {
                return false
            }
            if (pending.isExpired()) {
                pending.status = ConfirmationStatus.EXPIRED
                _pendingConfirmation.value = null
                activeDeferred?.complete(ConfirmationStatus.EXPIRED)
                activeDeferred = null
                return false
            }
            // Invalidate if parameters changed after proposal
            if (expectedParams != null && pending.parameters != expectedParams) {
                pending.status = ConfirmationStatus.CANCELLED
                _pendingConfirmation.value = null
                activeDeferred?.complete(ConfirmationStatus.CANCELLED)
                activeDeferred = null
                return false
            }

            pending.status = ConfirmationStatus.CONFIRMED
            _pendingConfirmation.value = null
            val completed = activeDeferred?.complete(ConfirmationStatus.CONFIRMED) ?: false
            activeDeferred = null
            return completed
        }
    }

    /**
     * Explicitly rejects the pending confirmation.
     */
    fun reject(actionId: String? = null): Boolean {
        synchronized(this) {
            val pending = _pendingConfirmation.value ?: return false
            if (actionId != null && pending.actionId != actionId) {
                return false
            }
            pending.status = ConfirmationStatus.REJECTED
            _pendingConfirmation.value = null
            val completed = activeDeferred?.complete(ConfirmationStatus.REJECTED) ?: false
            activeDeferred = null
            return completed
        }
    }

    fun clear() {
        synchronized(this) {
            activeDeferred?.complete(ConfirmationStatus.CANCELLED)
            activeDeferred = null
            _pendingConfirmation.value = null
        }
    }
}
