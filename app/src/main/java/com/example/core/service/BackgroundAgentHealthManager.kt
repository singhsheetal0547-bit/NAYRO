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
 * Detailed real-time health evaluation report for NAYRO Background Agent Engine.
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
    val healthSummaryMessage: String
)

/**
 * Central Health Evaluator for NAYRO Background & Agent Lifecycle.
 * Operates purely on live Android system APIs without hardcoded success states.
 */
class BackgroundAgentHealthManager(
    private val context: Context,
    private val permissionManager: PermissionManager,
    private val capabilityManager: CapabilityManager,
    private val voiceManager: VoiceManager
) {

    private val _healthReport = MutableStateFlow(evaluateHealth())
    val healthReport: StateFlow<BackgroundAgentHealthReport> = _healthReport.asStateFlow()

    fun evaluateHealth(): BackgroundAgentHealthReport {
        val foregroundStatus = ServiceStateRepository.foregroundServiceStatus.value
        val floatingStatus = ServiceStateRepository.floatingServiceStatus.value

        val isForegroundRunning = foregroundStatus.state == ServiceLifecycleState.RUNNING
        val isFloatingActive = floatingStatus.state == ServiceLifecycleState.RUNNING

        val overlayGranted = Settings.canDrawOverlays(context)
        val micGranted = permissionManager.isMicrophoneGranted()

        val wakeState = voiceManager.wakeWordEngine.engineState.value
        val isWakeActive = wakeState == WakeEngineState.LISTENING_FOR_WAKE

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
            ServiceLifecycleState.RUNNING -> statesList.add(BackgroundHealthState.SERVICE_RUNNING)
            ServiceLifecycleState.STARTING -> statesList.add(BackgroundHealthState.SERVICE_STARTING)
            else -> statesList.add(BackgroundHealthState.SERVICE_STOPPED)
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

        val summary = buildSummaryMessage(
            isForegroundRunning = isForegroundRunning,
            micGranted = micGranted,
            isWakeActive = isWakeActive,
            isAccConn = isAccConn,
            overlayGranted = overlayGranted,
            isFloatingActive = isFloatingActive,
            isBatteryRestricted = isBatteryRestricted,
            isBgRestricted = isBgRestricted
        )

        val report = BackgroundAgentHealthReport(
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
            healthSummaryMessage = summary
        )

        _healthReport.value = report
        return report
    }

    private fun buildSummaryMessage(
        isForegroundRunning: Boolean,
        micGranted: Boolean,
        isWakeActive: Boolean,
        isAccConn: Boolean,
        overlayGranted: Boolean,
        isFloatingActive: Boolean,
        isBatteryRestricted: Boolean,
        isBgRestricted: Boolean
    ): String {
        val parts = mutableListOf<String>()
        if (isForegroundRunning) parts.add("Foreground Agent Active") else parts.add("Agent Service Stopped")
        if (isWakeActive) parts.add("Wake Word ('Hey Babu') Listening") else if (!micGranted) parts.add("Microphone Permission Missing")
        if (isAccConn) parts.add("Accessibility Connected") else parts.add("Accessibility Disconnected")
        if (isFloatingActive) parts.add("Floating Overlay Visible")
        if (isBatteryRestricted) parts.add("Battery Optimized (May delay background wake)")
        if (isBgRestricted) parts.add("Background Execution Restricted by OS")
        return parts.joinToString(" • ")
    }
}
