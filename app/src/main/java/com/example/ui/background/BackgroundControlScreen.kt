package com.example.ui.background

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.ui.components.StatusBadge
import com.example.ui.theme.Coral
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.DeepGreen
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.ForestGreen
import com.example.ui.theme.SoftSage
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarmIvory

@Composable
fun BackgroundControlScreen(
    isForegroundRunning: Boolean,
    isFloatingRunning: Boolean,
    hasOverlayPermission: Boolean,
    onStartForegroundService: () -> Unit,
    onStopForegroundService: () -> Unit,
    onStartFloatingService: () -> Unit,
    onStopFloatingService: () -> Unit,
    onRequestOverlayPermission: () -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        Text(
            text = "Background & Floating Control",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "Manage Babu background foreground service and system floating overlay window.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Card 1: System B — Floating Assistant (WindowManager Overlay)
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
                            text = "Floating Assistant (Babu Bubble)",
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
                    text = "A real system-level draggable floating icon for Babu. Drag to reposition, tap to expand quick prompt panel, or drag to screen edge to snap.",
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

        // Card 2: System C — Background Agent Foreground Service
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
                            text = "Android Foreground Service (SpecialUse)",
                            style = MaterialTheme.typography.labelSmall,
                            color = ForestGreen
                        )
                    }
                    StatusBadge(
                        label = if (isForegroundRunning) "RUNNING" else "STOPPED",
                        isActive = isForegroundRunning,
                        testTag = "badge_foreground_state"
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Coordinates agent background lifecycle, standby notification, service recovery, and process recreation without continuous audio streaming.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (isForegroundRunning) {
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

        // Card 3: Privacy & OEM Guidelines Info
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
                    text = "• Privacy Rule: Microphone is never continuously active or streaming in the background.\n" +
                            "• Foreground Service Type: Declared as specialUse for lifecycle coordination.\n" +
                            "• OEM Battery Restrictions: Unrestricted battery execution is optional and requested transparently.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SoftSage
                )
            }
        }
    }
}
