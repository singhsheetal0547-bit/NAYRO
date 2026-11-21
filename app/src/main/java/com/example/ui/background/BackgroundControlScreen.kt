package com.example.ui.background

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.core.notification.NotificationIntelligenceRepository
import com.example.core.permission.AccompanistPermissionUtility
import com.example.core.permission.PermissionType
import com.example.core.service.NayroForegroundService
import com.example.core.service.ServiceLifecycleState
import com.example.core.service.ServiceStateRepository
import com.example.ui.components.StatusBadge
import com.example.ui.theme.Coral
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.ForestGreen
import com.example.ui.theme.SoftSage
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarmIvory
import com.example.ui.theme.WarningAmber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Section 4 — Background Agent Control Center.
 * Provides unified status and controls for:
 * 1. Background Agent: ON/OFF
 * 2. Wake Word Detection: ON/OFF
 * 3. Floating Agent: ON/OFF
 * 4. Notification Access: ON/OFF status
 * 5. Accessibility Service: ON/OFF status
 * 6. Microphone Permission: ON/OFF status
 * 7. Current Agent State
 * 8. Dedicated PAUSE BACKGROUND AGENT (Duration / Until Resumed / Immediate) & RESUME BACKGROUND AGENT
 * 9. Battery Optimization Guidance & Background Activity Information
 */
@Composable
fun BackgroundControlScreen(
    isForegroundRunning: Boolean,
    isFloatingRunning: Boolean,
    hasOverlayPermission: Boolean,
    onStartForegroundService: () -> Unit,
    onStopForegroundService: () -> Unit,
    onStartFloatingService: () -> Unit,
    onStopFloatingService: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    isWakeWordEnabled: Boolean = false,
    onToggleWakeWord: (Boolean) -> Unit = {},
    currentAgentStateLabel: String = "IDLE",
    onPauseBackgroundAgent: (Int?) -> Unit = {},
    onResumeBackgroundAgent: () -> Unit = {},
    onOpenPermissionSettings: (PermissionType) -> Unit = {},
    assistantName: String = "NAYRO",
    configuredWakePhrases: List<String> = emptyList(),
    backgroundListeningStatus: String = "",
    oemBackgroundGuidance: String = ""
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val cleanAssistantName = assistantName.trim().ifEmpty { "NAYRO" }
    val primaryWakeLabel = configuredWakePhrases.firstOrNull()?.takeIf { it.isNotBlank() } ?: "Hi $cleanAssistantName"

    val foregroundStatus by ServiceStateRepository.foregroundServiceStatus.collectAsState()
    val isBackgroundPaused by ServiceStateRepository.isBackgroundAgentPaused.collectAsState()
    val pausedUntilMs by ServiceStateRepository.pausedUntilTimestampMs.collectAsState()
    val pauseReason by ServiceStateRepository.pauseReason.collectAsState()
    val activityLog by ServiceStateRepository.backgroundActivityLog.collectAsState()

    val isMicGranted = AccompanistPermissionUtility.isMicrophonePermissionGranted(context)
    val isAccessibilityEnabled = AccompanistPermissionUtility.isAccessibilityServiceEnabled(context)
    val isNotificationAccessGranted = NotificationIntelligenceRepository.isNotificationAccessEnabled(context)

    val isServiceActiveOrPaused = isForegroundRunning ||
        foregroundStatus.state == ServiceLifecycleState.RUNNING ||
        foregroundStatus.state == ServiceLifecycleState.PAUSED

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        Text(
            text = "Background Agent Control Center",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "Resident background agent lifecycle, temporary pause/resume, wake-word status, floating bubble & system access.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Master Card: Dedicated Background Voice Listening ON/OFF Control
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(
                width = 1.5.dp,
                color = if (isWakeWordEnabled) SuccessGreen else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
            ),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("bg_master_listening_card")
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isWakeWordEnabled) {
                                "Background Voice Listening: ON"
                            } else {
                                "Background Voice Listening: OFF"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = backgroundListeningStatus.ifBlank {
                                if (isWakeWordEnabled) {
                                    "Local wake-word detection active for '$primaryWakeLabel'. Microphone audio stays on-device."
                                } else {
                                    "Off — AudioRecord and microphone foreground service are stopped. In-app chat & mic continue working."
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = isWakeWordEnabled,
                        onCheckedChange = onToggleWakeWord,
                        colors = SwitchDefaults.colors(checkedThumbColor = Coral, checkedTrackColor = ForestGreen),
                        modifier = Modifier.testTag("bg_master_listening_switch")
                    )
                }
                if (oemBackgroundGuidance.isNotBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = oemBackgroundGuidance,
                        style = MaterialTheme.typography.bodySmall,
                        color = Coral
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Dual Voice Activation & Screen-Off System Assistant Card (Mode A + Mode B)
        val screenOffReport = com.example.core.voice.assistant.ScreenOffWakeCapabilityManager.evaluateCapability(
            context = context,
            isLocalWakeListening = isWakeWordEnabled && isForegroundRunning
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("card_dual_voice_activation")
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Dual Voice Activation (Floating + Screen-Off)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = screenOffReport.mode.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = Coral
                        )
                    }
                    StatusBadge(
                        label = if (screenOffReport.isDefaultAssistantRoleHeld || screenOffReport.isVoiceInteractionServiceActive) {
                            "ASSISTANT SET"
                        } else {
                            "LOCAL FALLBACK"
                        },
                        isActive = screenOffReport.isLocalFallbackListeningActive || screenOffReport.isDefaultAssistantRoleHeld,
                        testTag = "badge_screen_off_mode"
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Mode A (Floating Icon): ${if (isFloatingRunning) "Active — tap floating bubble to start continuous mic session immediately." else "Stopped — start Floating Assistant below."}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Mode B (Screen-Off & VoiceInteractionService): ${screenOffReport.honestStatusSummary}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Hardware DSP Hotword: ${if (screenOffReport.isHardwareDspHotwordSupported) "Supported (Privileged)" else "Unavailable on third-party installs (using local MFCC+DTW fallback)"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (screenOffReport.isHardwareDspHotwordSupported) SuccessGreen else WarningAmber
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = screenOffReport.oemCompatibilityNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = {
                            try {
                                val intent = com.example.core.voice.assistant.ScreenOffWakeCapabilityManager
                                    .createDefaultAssistantSettingsIntent(context)
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                        },
                        modifier = Modifier.testTag("btn_set_default_assistant")
                    ) {
                        Icon(imageVector = Icons.Default.Settings, contentDescription = "Set Default Assistant")
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            if (screenOffReport.isDefaultAssistantRoleHeld) "Assistant Settings" else "Set Default Assistant",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Card 0: Live Resident Status Matrix (Items 1-7)
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("card_resident_status_matrix")
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Resident Agent Telemetry",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Current Agent State: $currentAgentStateLabel",
                            style = MaterialTheme.typography.bodySmall,
                            color = Coral
                        )
                    }
                    StatusBadge(
                        label = when {
                            isBackgroundPaused -> "PAUSED"
                            isForegroundRunning -> "RESIDENT ON"
                            else -> "OFF"
                        },
                        isActive = isForegroundRunning && !isBackgroundPaused,
                        testTag = "badge_resident_overall"
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                Spacer(modifier = Modifier.height(10.dp))

                // 1. Background Agent: ON/OFF
                ControlStatusRow(
                    label = "1. Background Agent",
                    statusText = when {
                        isBackgroundPaused -> "PAUSED"
                        isForegroundRunning -> "ON"
                        else -> "OFF"
                    },
                    isReady = isForegroundRunning && !isBackgroundPaused
                )
                // 2. Wake Word Detection: ON/OFF
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = null,
                            tint = if (isWakeWordEnabled && isMicGranted) SuccessGreen else Coral,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "2. Wake Word Detection ('$primaryWakeLabel')",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Switch(
                        checked = isWakeWordEnabled,
                        onCheckedChange = onToggleWakeWord,
                        colors = SwitchDefaults.colors(checkedThumbColor = Coral, checkedTrackColor = ForestGreen),
                        modifier = Modifier.testTag("bg_toggle_wake_switch")
                    )
                }
                // 3. Floating Agent: ON/OFF
                ControlStatusRow(
                    label = "3. Floating Agent Overlay",
                    statusText = if (isFloatingRunning) "ON" else "OFF",
                    isReady = isFloatingRunning
                )
                // 4. Notification Access: ON/OFF
                ControlStatusRow(
                    label = "4. Notification Access",
                    statusText = if (isNotificationAccessGranted) "ON" else "OFF",
                    isReady = isNotificationAccessGranted,
                    actionLabel = if (!isNotificationAccessGranted) "Enable" else null,
                    onAction = { onOpenPermissionSettings(PermissionType.NOTIFICATION_LISTENER) }
                )
                // 5. Accessibility Service: ON/OFF
                ControlStatusRow(
                    label = "5. Accessibility Service",
                    statusText = if (isAccessibilityEnabled) "ON" else "OFF",
                    isReady = isAccessibilityEnabled,
                    actionLabel = if (!isAccessibilityEnabled) "Enable" else null,
                    onAction = { onOpenPermissionSettings(PermissionType.ACCESSIBILITY) }
                )
                // 6. Microphone Permission: ON/OFF
                ControlStatusRow(
                    label = "6. Microphone Permission",
                    statusText = if (isMicGranted) "ON" else "OFF",
                    isReady = isMicGranted,
                    actionLabel = if (!isMicGranted) "Grant" else null,
                    onAction = { onOpenPermissionSettings(PermissionType.MICROPHONE) }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Card 1: Temporary Pause & Resume Controls (Section 4 Mandatory Requirement)
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(
                1.dp,
                if (isBackgroundPaused) WarningAmber else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("card_pause_resume_background")
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Temporary Pause & Resume",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Pause background listening and new task initiation while preserving recoverable task checkpoints.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    StatusBadge(
                        label = if (isBackgroundPaused) "PAUSED" else "ACTIVE",
                        isActive = !isBackgroundPaused && isServiceActiveOrPaused,
                        testTag = "badge_pause_state"
                    )
                }

                if (isBackgroundPaused) {
                    Spacer(modifier = Modifier.height(10.dp))
                    val untilText = pausedUntilMs?.let {
                        "Auto-resumes at " + SimpleDateFormat("hh:mm a", Locale.US).format(Date(it))
                    } ?: "Paused until manually resumed"
                    Text(
                        text = "• Status: $untilText (${pauseReason ?: "User requested pause"})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = WarningAmber
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            NayroForegroundService.pauseService(context, durationMinutes = null, reason = "Immediate manual pause")
                            onPauseBackgroundAgent(null)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Coral),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("btn_pause_background_agent")
                    ) {
                        Icon(imageVector = Icons.Default.Pause, contentDescription = "Pause")
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("PAUSE BACKGROUND AGENT", style = MaterialTheme.typography.labelSmall)
                    }

                    Button(
                        onClick = {
                            NayroForegroundService.resumeService(context)
                            onResumeBackgroundAgent()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ForestGreen),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("btn_resume_background_agent")
                    ) {
                        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Resume")
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("RESUME BACKGROUND AGENT", style = MaterialTheme.typography.labelSmall)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Timed Pause Options:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        "15 Mins" to 15,
                        "30 Mins" to 30,
                        "1 Hour" to 60
                    ).forEach { (label, mins) ->
                        OutlinedButton(
                            onClick = {
                                NayroForegroundService.pauseService(
                                    context,
                                    durationMinutes = mins,
                                    reason = "Timed pause ($label)"
                                )
                                onPauseBackgroundAgent(mins)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("btn_pause_${mins}m")
                        ) {
                            Text(label, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Card 2: System B — Floating Assistant (WindowManager Overlay)
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().testTag("card_floating_service")
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Floating Assistant ($cleanAssistantName Bubble)",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "WindowManager TYPE_APPLICATION_OVERLAY",
                            style = MaterialTheme.typography.labelSmall,
                            color = Coral
                        )
                    }
                    StatusBadge(
                        label = if (isFloatingRunning) "ACTIVE" else "STOPPED",
                        isActive = isFloatingRunning,
                        testTag = "badge_floating_state"
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "A real system-level draggable floating icon for $cleanAssistantName. Drag to reposition, tap to expand compact voice/chat overlay with live waveform, or drag to screen edge to snap.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Overlay Permission Status check
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (hasOverlayPermission) SuccessGreen.copy(alpha = 0.1f) else Coral.copy(alpha = 0.1f))
                        .padding(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Permission Info",
                        tint = if (hasOverlayPermission) SuccessGreen else Coral
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (hasOverlayPermission) {
                            "Overlay Permission: GRANTED"
                        } else {
                            "Overlay Permission: REQUIRED (Display over other apps)"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (hasOverlayPermission) SuccessGreen else Coral
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (!hasOverlayPermission) {
                        Button(
                            onClick = onRequestOverlayPermission,
                            colors = ButtonDefaults.buttonColors(containerColor = Coral),
                            modifier = Modifier.testTag("grant_overlay_btn")
                        ) {
                            Icon(imageVector = Icons.Default.Settings, contentDescription = "Settings")
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Grant Overlay Permission")
                        }
                    } else {
                        if (isFloatingRunning) {
                            OutlinedButton(
                                onClick = onStopFloatingService,
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed),
                                modifier = Modifier.testTag("stop_floating_btn")
                            ) {
                                Icon(imageVector = Icons.Default.Stop, contentDescription = "Stop")
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Stop Floating Assistant")
                            }
                        } else {
                            Button(
                                onClick = onStartFloatingService,
                                colors = ButtonDefaults.buttonColors(containerColor = ForestGreen),
                                modifier = Modifier.testTag("start_floating_btn")
                            ) {
                                Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Start")
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Start Floating Assistant")
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Card 3: System C — Background Agent Foreground Service
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().testTag("card_foreground_service")
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Background Agent Engine",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Android Foreground Service (foregroundServiceType=microphone)",
                            style = MaterialTheme.typography.labelSmall,
                            color = ForestGreen
                        )
                    }
                    StatusBadge(
                        label = when {
                            isBackgroundPaused -> "PAUSED"
                            isForegroundRunning -> "RUNNING"
                            else -> "STOPPED"
                        },
                        isActive = isForegroundRunning && !isBackgroundPaused,
                        testTag = "badge_foreground_state"
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Coordinates agent background lifecycle, local wake-word listening, standby notification, and task recovery without continuous cloud audio streaming.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (isForegroundRunning || isBackgroundPaused) {
                        OutlinedButton(
                            onClick = onStopForegroundService,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed),
                            modifier = Modifier.testTag("stop_foreground_btn")
                        ) {
                            Icon(imageVector = Icons.Default.Stop, contentDescription = "Stop")
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Stop Agent Service")
                        }
                    } else {
                        Button(
                            onClick = onStartForegroundService,
                            colors = ButtonDefaults.buttonColors(containerColor = ForestGreen),
                            modifier = Modifier.testTag("start_foreground_btn")
                        ) {
                            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Start")
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Start Agent Service")
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Card 4: Real Runtime Health Diagnostics & Battery Optimization Guidance
        val healthReport = try {
            com.example.NayroApplication.instance.healthManager.evaluateHealth()
        } catch (e: Exception) {
            null
        }

        healthReport?.let { report ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().testTag("card_health_diagnostics")
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Runtime Health & Battery Optimization Guidance",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Evaluates live Android OS APIs, Doze exemptions, and engine states.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (report.isBatteryOptimized) {
                            OutlinedButton(
                                onClick = { onOpenPermissionSettings(PermissionType.BATTERY_OPTIMIZATION) },
                                modifier = Modifier.testTag("btn_battery_opt_guidance")
                            ) {
                                Icon(imageVector = Icons.Default.BatteryAlert, contentDescription = null, tint = Coral)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Unrestrict", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = report.healthSummaryMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = SoftSage
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    report.activeStates.forEach { state ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 2.dp)
                        ) {
                            StatusBadge(
                                label = state.name,
                                isActive = state.name.contains("RUNNING") || state.name.contains("GRANTED") || state.name.contains("ACTIVE") || state.name.contains("CONNECTED") || state.name.contains("OPTIMIZED"),
                                testTag = "badge_health_${state.name.lowercase()}"
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Card 5: Background Activity Information Log (Item 9)
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("card_background_activity_log")
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = null,
                        tint = ForestGreen
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Background Activity Information",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                if (activityLog.isEmpty()) {
                    Text(
                        text = "No background service events recorded in this session yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    activityLog.take(8).forEach { entry ->
                        Text(
                            text = "• $entry",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Card 6: Privacy & OEM Guidelines Info
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Android Background & Microphone Policy",
                    style = MaterialTheme.typography.titleLarge,
                    color = WarmIvory
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "• Privacy Rule: Standby microphone audio is processed locally on-device and never uploaded.\n" +
                            "• Foreground Service Type: Legitimate foregroundServiceType='microphone' for local wake phrase listening.\n" +
                            "• OEM Battery Restrictions: Unrestricted battery execution is optional and requested transparently.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SoftSage
                )
            }
        }
    }
}

@Composable
private fun ControlStatusRow(
    label: String,
    statusText: String,
    isReady: Boolean,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = if (isReady) SuccessGreen else Coral,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (isReady) SuccessGreen else Coral
            )
            if (actionLabel != null && onAction != null) {
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(onClick = onAction) {
                    Text(actionLabel, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
