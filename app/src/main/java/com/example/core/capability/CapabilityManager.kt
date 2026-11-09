package com.example.core.capability

import android.content.Context
import android.os.Build
import com.example.core.permission.PermissionManager
import com.example.core.permission.PermissionType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * System D — Capability Evaluation Engine.
 * Evaluates real-time availability of every capability based on system SDK,
 * permissions, service states, and future phase implementation status.
 * Never falsely marks future capabilities as AVAILABLE.
 */
class CapabilityManager(
    private val context: Context,
    private val permissionManager: PermissionManager
) {

    private val _capabilities = MutableStateFlow<Map<CapabilityId, CapabilityEvaluation>>(emptyMap())
    val capabilities: StateFlow<Map<CapabilityId, CapabilityEvaluation>> = _capabilities.asStateFlow()

    // State of services
    private var isForegroundServiceRunning = false
    private var isFloatingServiceRunning = false

    init {
        evaluateAll()
    }

    fun updateServiceStates(foregroundRunning: Boolean, floatingRunning: Boolean) {
        isForegroundServiceRunning = foregroundRunning
        isFloatingServiceRunning = floatingRunning
        evaluateAll()
    }

    fun evaluateAll() {
        val result = mutableMapOf<CapabilityId, CapabilityEvaluation>()
        for (def in CapabilityRegistry.definitions) {
            result[def.id] = evaluate(def)
        }
        _capabilities.value = result
    }

    private fun evaluate(def: CapabilityDefinition): CapabilityEvaluation {
        // 1. Android Version check
        if (Build.VERSION.SDK_INT < def.minAndroidSdk) {
            return CapabilityEvaluation(
                definition = def,
                status = CapabilityStatus.BLOCKED_BY_ANDROID_VERSION,
                statusReason = "Requires Android SDK ${def.minAndroidSdk}+ (Current: ${Build.VERSION.SDK_INT}).",
                isActionable = false
            )
        }

        // 2. Future Capability check (Honest capabilities - do not mark as active in Step 02)
        if (def.isFutureCapability) {
            return when (def.id) {
                CapabilityId.ACCESSIBILITY -> {
                    val enabled = permissionManager.isAccessibilityServiceEnabled()
                    if (enabled) {
                        CapabilityEvaluation(
                            definition = def,
                            status = CapabilityStatus.NOT_IMPLEMENTED,
                            statusReason = "Accessibility service granted in Android settings, but automation engine is scheduled for future phase.",
                            isActionable = false
                        )
                    } else {
                        CapabilityEvaluation(
                            definition = def,
                            status = CapabilityStatus.BLOCKED_BY_SETTINGS,
                            statusReason = "Accessibility service not enabled in Android settings (Phase 3 feature).",
                            isActionable = true,
                            actionLabel = "Open Accessibility Settings"
                        )
                    }
                }
                CapabilityId.SCREEN_CAPTURE -> {
                    CapabilityEvaluation(
                        definition = def,
                        status = CapabilityStatus.NOT_IMPLEMENTED,
                        statusReason = "MediaProjection screen understanding engine scheduled for future phase.",
                        isActionable = false
                    )
                }
                CapabilityId.WAKE_WORD_READY -> {
                    val micGranted = permissionManager.isMicrophoneGranted()
                    if (!micGranted) {
                        CapabilityEvaluation(
                            definition = def,
                            status = CapabilityStatus.BLOCKED_BY_PERMISSION,
                            statusReason = "Microphone permission required for wake-word engine.",
                            isActionable = true,
                            actionLabel = "Grant Microphone Permission"
                        )
                    } else {
                        CapabilityEvaluation(
                            definition = def,
                            status = CapabilityStatus.NOT_IMPLEMENTED,
                            statusReason = "Local wake-word acoustic DSP engine scheduled for Phase 3.",
                            isActionable = false
                        )
                    }
                }
                CapabilityId.VOICE_INTERACTION_READY -> {
                    CapabilityEvaluation(
                        definition = def,
                        status = CapabilityStatus.NOT_IMPLEMENTED,
                        statusReason = "Full ASR/TTS speech pipeline scheduled for Phase 3.",
                        isActionable = false
                    )
                }
                CapabilityId.DEVICE_AUTOMATION_READY -> {
                    CapabilityEvaluation(
                        definition = def,
                        status = CapabilityStatus.NOT_IMPLEMENTED,
                        statusReason = "Native Android tools and intent planning engine scheduled for Phase 4.",
                        isActionable = false
                    )
                }
                else -> CapabilityEvaluation(
                    definition = def,
                    status = CapabilityStatus.NOT_IMPLEMENTED,
                    statusReason = "Scheduled for subsequent development phase.",
                    isActionable = false
                )
            }
        }

        // 3. Implemented Step 02 Capabilities Evaluation
        return when (def.id) {
            CapabilityId.FLOATING_ASSISTANT -> {
                val hasOverlay = permissionManager.canDrawOverlays()
                if (!hasOverlay) {
                    CapabilityEvaluation(
                        definition = def,
                        status = CapabilityStatus.BLOCKED_BY_SETTINGS,
                        statusReason = "Requires 'Display over other apps' special permission.",
                        isActionable = true,
                        actionLabel = "Grant Overlay Permission"
                    )
                } else if (!isFloatingServiceRunning) {
                    CapabilityEvaluation(
                        definition = def,
                        status = CapabilityStatus.SERVICE_NOT_RUNNING,
                        statusReason = "Overlay permission granted. Floating service is currently stopped.",
                        isActionable = true,
                        actionLabel = "Start Floating Assistant"
                    )
                } else {
                    CapabilityEvaluation(
                        definition = def,
                        status = CapabilityStatus.AVAILABLE,
                        statusReason = "Floating Babu bubble is active on screen.",
                        isActionable = true,
                        actionLabel = "Stop Floating Assistant"
                    )
                }
            }
            CapabilityId.BACKGROUND_AGENT -> {
                if (!isForegroundServiceRunning) {
                    CapabilityEvaluation(
                        definition = def,
                        status = CapabilityStatus.SERVICE_NOT_RUNNING,
                        statusReason = "Foreground service is currently stopped.",
                        isActionable = true,
                        actionLabel = "Start Agent Service"
                    )
                } else {
                    CapabilityEvaluation(
                        definition = def,
                        status = CapabilityStatus.AVAILABLE,
                        statusReason = "Persistent foreground agent service is active.",
                        isActionable = true,
                        actionLabel = "Stop Agent Service"
                    )
                }
            }
            CapabilityId.MICROPHONE_ACCESS -> {
                val micGranted = permissionManager.isMicrophoneGranted()
                if (micGranted) {
                    CapabilityEvaluation(
                        definition = def,
                        status = CapabilityStatus.AVAILABLE,
                        statusReason = "Microphone access granted for voice commands.",
                        isActionable = false
                    )
                } else {
                    CapabilityEvaluation(
                        definition = def,
                        status = CapabilityStatus.BLOCKED_BY_PERMISSION,
                        statusReason = "RECORD_AUDIO runtime permission not granted.",
                        isActionable = true,
                        actionLabel = "Grant Microphone Permission"
                    )
                }
            }
            CapabilityId.NOTIFICATION_ACCESS -> {
                val notifGranted = permissionManager.isNotificationGranted()
                if (notifGranted) {
                    CapabilityEvaluation(
                        definition = def,
                        status = CapabilityStatus.AVAILABLE,
                        statusReason = "Notifications enabled for agent alerts and foreground status.",
                        isActionable = false
                    )
                } else {
                    CapabilityEvaluation(
                        definition = def,
                        status = CapabilityStatus.BLOCKED_BY_PERMISSION,
                        statusReason = "POST_NOTIFICATIONS permission not granted.",
                        isActionable = true,
                        actionLabel = "Enable Notifications"
                    )
                }
            }
            else -> CapabilityEvaluation(
                definition = def,
                status = CapabilityStatus.AVAILABLE,
                statusReason = "Capability active.",
                isActionable = false
            )
        }
    }
}
