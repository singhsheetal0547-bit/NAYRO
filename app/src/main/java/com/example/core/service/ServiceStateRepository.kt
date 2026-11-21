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

    private val _backgroundVoicePipelineStatus = MutableStateFlow(
        BackgroundVoicePipelineStatus()
    )
    val backgroundVoicePipelineStatus: StateFlow<BackgroundVoicePipelineStatus> =
        _backgroundVoicePipelineStatus.asStateFlow()

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
            _backgroundVoicePipelineStatus.value = BackgroundVoicePipelineStatus(
                state = BackgroundVoicePipelineState.STOPPED,
                detail = error ?: "Background voice service is stopped.",
                isMicrophoneVerified = false,
                isWakeEngineVerified = false,
                isAudioCaptureVerified = false,
                retryCount = 0,
                lastUpdatedTimestamp = System.currentTimeMillis()
            )
        } else if (state == ServiceLifecycleState.STARTING) {
            _backgroundVoicePipelineStatus.value = _backgroundVoicePipelineStatus.value.copy(
                state = BackgroundVoicePipelineState.SERVICE_STARTING,
                detail = error ?: "Starting foreground service...",
                isMicrophoneVerified = false,
                isWakeEngineVerified = false,
                isAudioCaptureVerified = false,
                lastUpdatedTimestamp = System.currentTimeMillis()
            )
        } else if (state == ServiceLifecycleState.FAILED || state == ServiceLifecycleState.ERROR || state == ServiceLifecycleState.PERMISSION_DENIED) {
            _backgroundVoicePipelineStatus.value = _backgroundVoicePipelineStatus.value.copy(
                state = BackgroundVoicePipelineState.ERROR,
                detail = error ?: "Foreground service failed ($state).",
                isMicrophoneVerified = false,
                isWakeEngineVerified = false,
                isAudioCaptureVerified = false,
                lastUpdatedTimestamp = System.currentTimeMillis()
            )
        }
        appendActivityLog("Foreground Service -> ${state.name}${if (error != null) " ($error)" else ""}")
    }

    /**
     * Publishes a granular P0-3 background voice pipeline state.
     * Enforces an invariant: [BackgroundVoicePipelineState.BACKGROUND_VOICE_READY] can NEVER be published
     * unless [isMicrophoneVerified], [isWakeEngineVerified], and [isAudioCaptureVerified] are ALL true.
     * If any prerequisite is false, downgrades automatically and honestly to [BackgroundVoicePipelineState.DEGRADED].
     */
    fun updateBackgroundVoicePipeline(
        state: BackgroundVoicePipelineState,
        detail: String,
        isMicrophoneVerified: Boolean = _backgroundVoicePipelineStatus.value.isMicrophoneVerified,
        isWakeEngineVerified: Boolean = _backgroundVoicePipelineStatus.value.isWakeEngineVerified,
        isAudioCaptureVerified: Boolean = _backgroundVoicePipelineStatus.value.isAudioCaptureVerified,
        retryCount: Int = _backgroundVoicePipelineStatus.value.retryCount,
        maxRetries: Int = _backgroundVoicePipelineStatus.value.maxRetries,
        isScreenOff: Boolean = _backgroundVoicePipelineStatus.value.isScreenOff
    ) {
        val allVerified = isMicrophoneVerified && isWakeEngineVerified && isAudioCaptureVerified
        val effectiveState = if (state == BackgroundVoicePipelineState.BACKGROUND_VOICE_READY && !allVerified) {
            BackgroundVoicePipelineState.DEGRADED
        } else {
            state
        }
        val effectiveDetail = if (effectiveState != state) {
            "Downgraded to DEGRADED: cannot claim BACKGROUND_VOICE_READY without verified microphone ($isMicrophoneVerified), wake engine ($isWakeEngineVerified), and audio capture ($isAudioCaptureVerified)."
        } else {
            detail
        }
        _backgroundVoicePipelineStatus.value = BackgroundVoicePipelineStatus(
            state = effectiveState,
            detail = effectiveDetail,
            isMicrophoneVerified = isMicrophoneVerified,
            isWakeEngineVerified = isWakeEngineVerified,
            isAudioCaptureVerified = isAudioCaptureVerified,
            retryCount = retryCount,
            maxRetries = maxRetries,
            isScreenOff = isScreenOff,
            lastUpdatedTimestamp = System.currentTimeMillis()
        )
        appendActivityLog("Voice Pipeline -> ${effectiveState.name}: $effectiveDetail")
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
        _backgroundVoicePipelineStatus.value = _backgroundVoicePipelineStatus.value.copy(
            state = BackgroundVoicePipelineState.DEGRADED,
            detail = "Background voice paused: $reason",
            isAudioCaptureVerified = false,
            lastUpdatedTimestamp = nowMs
        )
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
        appendActivityLog("Background Agent RESUMED; awaiting voice pipeline verification.")
    }

    fun appendActivityLog(entry: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val formatted = "[$stamp] $entry"
        _backgroundActivityLog.value = (listOf(formatted) + _backgroundActivityLog.value).take(25)
    }

    fun clearForTesting() {
        _foregroundServiceStatus.value = ServiceStatus(ServiceLifecycleState.STOPPED)
        _backgroundVoicePipelineStatus.value = BackgroundVoicePipelineStatus()
        _floatingServiceStatus.value = ServiceStatus(ServiceLifecycleState.STOPPED)
        _isBackgroundAgentPaused.value = false
        _pausedUntilTimestampMs.value = null
        _pauseReason.value = null
        _backgroundActivityLog.value = emptyList()
    }
}
