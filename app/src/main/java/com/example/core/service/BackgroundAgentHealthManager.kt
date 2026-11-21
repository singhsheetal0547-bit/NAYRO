package com.example.core.service

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.example.core.accessibility.AccessibilityConnectionManager
import com.example.core.accessibility.AccessibilityConnectionState
import com.example.core.accessibility.NayroAccessibilityService
import com.example.core.capability.CapabilityManager
import com.example.core.permission.PermissionManager
import com.example.core.voice.VoiceManager
import com.example.core.voice.engine.WakeEngineState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Health states reported by BackgroundAgentHealthManager.
 */
enum class BackgroundHealthState {
    SERVICE_STOPPED,
    SERVICE_STARTING,
    SERVICE_RUNNING,
    BACKGROUND_VOICE_READY,
    PIPELINE_DEGRADED,
    PIPELINE_ERROR,
    MICROPHONE_UNAVAILABLE,
    MICROPHONE_GRANTED,
    WAKE_WORD_ACTIVE,
    WAKE_WORD_INACTIVE,
    ACCESSIBILITY_CONNECTED,
    ACCESSIBILITY_DISCONNECTED,
    OVERLAY_ACTIVE,
    OVERLAY_UNAVAILABLE,
    BATTERY_RESTRICTED,
    BATTERY_OPTIMIZED,
    BACKGROUND_RESTRICTED
}

/**
 * Detailed real-time health evaluation report for NAYRO Background Agent Engine (P0-3).
 */
data class BackgroundAgentHealthReport(
    val serviceLifecycleState: ServiceLifecycleState,
    val isForegroundRunning: Boolean,
    val isFloatingOverlayActive: Boolean,
    val isOverlayPermissionGranted: Boolean,
    val isMicrophonePermissionGranted: Boolean,
    val isWakeWordActive: Boolean,
    val wakeEngineState: WakeEngineState,
    val isAccessibilityConnected: Boolean,
    val isBatteryOptimized: Boolean,
    val isBackgroundRestricted: Boolean,
    val activeStates: List<BackgroundHealthState>,
    val healthSummaryMessage: String,
    val pipelineState: BackgroundVoicePipelineState = BackgroundVoicePipelineState.STOPPED,
    val isBackgroundVoiceReady: Boolean = false,
    val pipelineDetail: String = ""
)

/**
 * Central Health Evaluator for NAYRO Background & Agent Lifecycle.
 * Operates purely on live Android system APIs and verified pipeline state without hardcoded success states.
 */
class BackgroundAgentHealthManager(
    private val context: Context,
    private val permissionManager: PermissionManager,
    private val capabilityManager: CapabilityManager,
    private val voiceManager: VoiceManager
) {

    private val _healthReport: MutableStateFlow<BackgroundAgentHealthReport>
    val healthReport: StateFlow<BackgroundAgentHealthReport>

    init {
        val initialReport = evaluateHealthInternal()
        _healthReport = MutableStateFlow(initialReport)
        healthReport = _healthReport.asStateFlow()
    }

    fun evaluateHealth(): BackgroundAgentHealthReport {
        val report = evaluateHealthInternal()
        _healthReport.value = report
        return report
    }

    private fun evaluateHealthInternal(): BackgroundAgentHealthReport {
        val foregroundStatus = ServiceStateRepository.foregroundServiceStatus.value
        val pipelineStatus = ServiceStateRepository.backgroundVoicePipelineStatus.value
        val floatingStatus = ServiceStateRepository.floatingServiceStatus.value

        val isForegroundRunning = foregroundStatus.state.isActiveOrRunning
        val isBgVoiceReady = pipelineStatus.state == BackgroundVoicePipelineState.BACKGROUND_VOICE_READY &&
            pipelineStatus.isMicrophoneVerified &&
            pipelineStatus.isWakeEngineVerified &&
            pipelineStatus.isAudioCaptureVerified
        val isFloatingActive = floatingStatus.state == ServiceLifecycleState.RUNNING

        val overlayGranted = Settings.canDrawOverlays(context)
        val micGranted = permissionManager.isMicrophoneGranted()

        val wakeState = voiceManager.wakeWordEngine.engineState.value
        val isWakeActive = wakeState == WakeEngineState.LISTENING_FOR_WAKE &&
            voiceManager.wakeWordEngine.isAudioCaptureActive.value

        val isAccConn = NayroAccessibilityService.isConnected() ||
                AccessibilityConnectionManager.checkStatus(context) == AccessibilityConnectionState.OPERATIONAL

        // Real Battery Optimization check via PowerManager
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isIgnoringBatteryOpt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && powerManager != null) {
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } else {
            true
        }
        val isBatteryRestricted = !isIgnoringBatteryOpt

        // Real Background restrictions check (Android P+)
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val isBgRestricted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && activityManager != null) {
            activityManager.isBackgroundRestricted
        } else {
            false
        }

        val statesList = mutableListOf<BackgroundHealthState>()

        when (foregroundStatus.state) {
            ServiceLifecycleState.RUNNING,
            ServiceLifecycleState.ACTIVE,
            ServiceLifecycleState.LISTENING,
            ServiceLifecycleState.PROCESSING -> statesList.add(BackgroundHealthState.SERVICE_RUNNING)
            ServiceLifecycleState.STARTING -> statesList.add(BackgroundHealthState.SERVICE_STARTING)
            else -> statesList.add(BackgroundHealthState.SERVICE_STOPPED)
        }

        when (pipelineStatus.state) {
            BackgroundVoicePipelineState.BACKGROUND_VOICE_READY ->
                if (isBgVoiceReady) statesList.add(BackgroundHealthState.BACKGROUND_VOICE_READY)
            BackgroundVoicePipelineState.DEGRADED ->
                statesList.add(BackgroundHealthState.PIPELINE_DEGRADED)
            BackgroundVoicePipelineState.ERROR ->
                statesList.add(BackgroundHealthState.PIPELINE_ERROR)
            else -> {}
        }

        if (micGranted) {
            statesList.add(BackgroundHealthState.MICROPHONE_GRANTED)
        } else {
            statesList.add(BackgroundHealthState.MICROPHONE_UNAVAILABLE)
        }

        if (isWakeActive) {
            statesList.add(BackgroundHealthState.WAKE_WORD_ACTIVE)
        } else {
            statesList.add(BackgroundHealthState.WAKE_WORD_INACTIVE)
        }

        if (isAccConn) {
            statesList.add(BackgroundHealthState.ACCESSIBILITY_CONNECTED)
        } else {
            statesList.add(BackgroundHealthState.ACCESSIBILITY_DISCONNECTED)
        }

        if (isFloatingActive && overlayGranted) {
            statesList.add(BackgroundHealthState.OVERLAY_ACTIVE)
        } else {
            statesList.add(BackgroundHealthState.OVERLAY_UNAVAILABLE)
        }

        if (isBatteryRestricted) {
            statesList.add(BackgroundHealthState.BATTERY_RESTRICTED)
        } else {
            statesList.add(BackgroundHealthState.BATTERY_OPTIMIZED)
        }

        if (isBgRestricted) {
            statesList.add(BackgroundHealthState.BACKGROUND_RESTRICTED)
        }

        val primaryWakePhrase = voiceManager.configuration.value.wakePhrase.ifBlank {
            voiceManager.wakeWordEngine.getConfiguredWakePhrases().firstOrNull() ?: "Hi Nayro"
        }

        val summary = buildSummaryMessage(
            isForegroundRunning = isForegroundRunning,
            pipelineStatus = pipelineStatus,
            isBgVoiceReady = isBgVoiceReady,
            primaryWakePhrase = primaryWakePhrase,
            micGranted = micGranted,
            isWakeActive = isWakeActive,
            isAccConn = isAccConn,
            isFloatingActive = isFloatingActive,
            isBatteryRestricted = isBatteryRestricted,
            isBgRestricted = isBgRestricted
        )

        return BackgroundAgentHealthReport(
            serviceLifecycleState = foregroundStatus.state,
            isForegroundRunning = isForegroundRunning,
            isFloatingOverlayActive = isFloatingActive,
            isOverlayPermissionGranted = overlayGranted,
            isMicrophonePermissionGranted = micGranted,
            isWakeWordActive = isWakeActive,
            wakeEngineState = wakeState,
            isAccessibilityConnected = isAccConn,
            isBatteryOptimized = isBatteryRestricted,
            isBackgroundRestricted = isBgRestricted,
            activeStates = statesList,
            healthSummaryMessage = summary,
            pipelineState = pipelineStatus.state,
            isBackgroundVoiceReady = isBgVoiceReady,
            pipelineDetail = pipelineStatus.detail
        )
    }

    private fun buildSummaryMessage(
        isForegroundRunning: Boolean,
        pipelineStatus: BackgroundVoicePipelineStatus,
        isBgVoiceReady: Boolean,
        primaryWakePhrase: String,
        micGranted: Boolean,
        isWakeActive: Boolean,
        isAccConn: Boolean,
        isFloatingActive: Boolean,
        isBatteryRestricted: Boolean,
        isBgRestricted: Boolean
    ): String {
        val parts = mutableListOf<String>()
        when {
            isBgVoiceReady -> parts.add("Background Voice Ready")
            pipelineStatus.state == BackgroundVoicePipelineState.ERROR -> parts.add("Voice Pipeline Error")
            pipelineStatus.state == BackgroundVoicePipelineState.DEGRADED -> parts.add("Voice Pipeline Degraded")
            isForegroundRunning -> parts.add("Foreground Service Running (Voice Not Ready)")
            else -> parts.add("Agent Service Stopped")
        }
        if (isWakeActive) {
            parts.add("Wake Word ('$primaryWakePhrase') Listening")
        } else if (!micGranted) {
            parts.add("Microphone Permission Missing")
        }
        if (isAccConn) parts.add("Accessibility Connected") else parts.add("Accessibility Disconnected")
        if (isFloatingActive) parts.add("Floating Overlay Visible")
        if (isBatteryRestricted) parts.add("Battery Optimized (May delay background wake)")
        if (isBgRestricted) parts.add("Background Execution Restricted by OS")
        return parts.joinToString(" • ")
    }
}
