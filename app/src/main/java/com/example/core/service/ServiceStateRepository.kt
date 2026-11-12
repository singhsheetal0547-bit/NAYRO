package com.example.core.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Authoritative in-memory state repository for all NAYRO background and overlay services.
 * Updated exclusively through real Android service lifecycle callbacks (onCreate, onStartCommand, onDestroy,
 * pause/resume actions, and exceptions).
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

    private val _isBackgroundAgentPaused = MutableStateFlow(false)
    val isBackgroundAgentPaused: StateFlow<Boolean> = _isBackgroundAgentPaused.asStateFlow()

    private val _pausedUntilTimestampMs = MutableStateFlow<Long?>(null)
    val pausedUntilTimestampMs: StateFlow<Long?> = _pausedUntilTimestampMs.asStateFlow()

    private val _pauseReason = MutableStateFlow<String?>(null)
    val pauseReason: StateFlow<String?> = _pauseReason.asStateFlow()

    private val _backgroundActivityLog = MutableStateFlow<List<String>>(emptyList())
    val backgroundActivityLog: StateFlow<List<String>> = _backgroundActivityLog.asStateFlow()

    fun updateForegroundStatus(state: ServiceLifecycleState, error: String? = null) {
        _foregroundServiceStatus.value = ServiceStatus(state, error)
        if (state == ServiceLifecycleState.RUNNING) {
            _isBackgroundAgentPaused.value = false
            _pausedUntilTimestampMs.value = null
            _pauseReason.value = null
        } else if (state == ServiceLifecycleState.STOPPED) {
            _isBackgroundAgentPaused.value = false
            _pausedUntilTimestampMs.value = null
            _pauseReason.value = null
        }
        appendActivityLog("Foreground Service -> ${state.name}${if (error != null) " ($error)" else ""}")
    }

    fun updateFloatingStatus(state: ServiceLifecycleState, error: String? = null) {
        _floatingServiceStatus.value = ServiceStatus(state, error)
        appendActivityLog("Floating Assistant -> ${state.name}${if (error != null) " ($error)" else ""}")
    }

    /**
     * Section 4 — Pauses the Background Resident Agent either for [durationMinutes] or until manually resumed.
     */
    fun pauseBackgroundAgent(
        durationMinutes: Int? = null,
        reason: String = "Paused by user",
        nowMs: Long = System.currentTimeMillis()
    ) {
        val untilMs = if (durationMinutes != null && durationMinutes > 0) {
            nowMs + durationMinutes * 60_000L
        } else {
            null
        }
        _isBackgroundAgentPaused.value = true
        _pausedUntilTimestampMs.value = untilMs
        _pauseReason.value = reason
        _foregroundServiceStatus.value = ServiceStatus(ServiceLifecycleState.PAUSED)
        val durationDesc = if (durationMinutes != null && durationMinutes > 0) {
            "for ${durationMinutes}m"
        } else {
            "until manually resumed"
        }
        appendActivityLog("Background Agent PAUSED ($durationDesc): $reason")
    }

    /**
     * Section 4 — Resumes the Background Resident Agent and restores active state.
     */
    fun resumeBackgroundAgent() {
        _isBackgroundAgentPaused.value = false
        _pausedUntilTimestampMs.value = null
        _pauseReason.value = null
        _foregroundServiceStatus.value = ServiceStatus(ServiceLifecycleState.RUNNING)
        appendActivityLog("Background Agent RESUMED and operational.")
    }

    fun appendActivityLog(entry: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val formatted = "[$stamp] $entry"
        _backgroundActivityLog.value = (listOf(formatted) + _backgroundActivityLog.value).take(25)
    }

    fun clearForTesting() {
        _foregroundServiceStatus.value = ServiceStatus(ServiceLifecycleState.STOPPED)
        _floatingServiceStatus.value = ServiceStatus(ServiceLifecycleState.STOPPED)
        _isBackgroundAgentPaused.value = false
        _pausedUntilTimestampMs.value = null
        _pauseReason.value = null
        _backgroundActivityLog.value = emptyList()
    }
}
