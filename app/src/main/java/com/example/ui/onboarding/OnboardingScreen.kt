package com.example.ui.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.core.agent.AgentState
import com.example.core.permission.PermissionEvaluation
import com.example.core.permission.PermissionRegistry
import com.example.core.permission.PermissionRequirementLevel
import com.example.core.permission.PermissionStatus
import com.example.core.permission.PermissionType
import com.example.core.permission.PermissionVerificationEntry
import com.example.core.permission.SetupWizardStep
import com.example.ui.components.AssistantOrb
import com.example.ui.theme.Coral
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.ForestGreen
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningAmber

/**
 * Part 2 & Part 3 — First-Launch Full Permission Setup Wizard for NAYRO (Babu).
 * Guides the user through:
 * 1. Welcome & Babu Persona Setup
 * 2. Capability & Privacy Architecture Overview
 * 3. Core Agent Permissions & Special Access (Microphone, Notifications, Overlay, Accessibility, Battery)
 * 4. Optional Tool Permissions (Notification Listener, Contacts, Phone State, Calendar, Location)
 * 5. Background Service & Floating Babu Activation
 * 6. Final Real-Time Permission Verification Report & Agent Activation
 */
@Composable
fun OnboardingScreen(
    evaluations: Map<PermissionType, PermissionEvaluation> = emptyMap(),
    initialAssistantName: String = "Babu",
    isForegroundRunning: Boolean = false,
    isFloatingRunning: Boolean = false,
    onRequestRuntimePermission: (PermissionType) -> Unit = {},
    onOpenPermissionSettings: (PermissionType) -> Unit = {},
    onRefreshPermissions: () -> Unit = {},
    onStartForegroundService: () -> Unit = {},
    onStartFloatingService: () -> Unit = {},
    onCompleteOnboarding: (String) -> Unit = {}
) {
    val totalSteps = SetupWizardStep.entries.size
    var stepIndex by remember { mutableIntStateOf(0) }
    var assistantNameInput by remember { mutableStateOf(initialAssistantName.ifBlank { "Babu" }) }

    val currentWizardStep = SetupWizardStep.entries.getOrElse(stepIndex) { SetupWizardStep.WELCOME }

    // Build verification entries from live evaluations (or fallback to PermissionRegistry)
    val verificationEntries = remember(evaluations) {
        PermissionRegistry.items.map { item ->
            val eval = evaluations[item.type]
            val status = eval?.status ?: PermissionStatus.DENIED
            PermissionVerificationEntry(
                type = item.type,
                permissionName = item.permissionName,
                purpose = item.purpose,
                status = status,
                statusExplanation = eval?.statusExplanation ?: item.purpose,
                requirementLevel = item.requirementLevel,
                category = item.category,
                featureDependency = item.featureDependency,
                verificationMethod = item.verificationMethod,
                fallbackBehavior = item.fallbackBehavior,
                canRequestRuntime = item.androidPermissionString != null &&
                    status != PermissionStatus.GRANTED &&
                    status != PermissionStatus.NOT_REQUIRED,
                canOpenSettings = item.settingsAction != null &&
                    status != PermissionStatus.GRANTED &&
                    status != PermissionStatus.NOT_REQUIRED
            )
        }
    }

    val coreEntries = verificationEntries.filter { it.requirementLevel == PermissionRequirementLevel.CORE_AGENT }
    val optionalEntries = verificationEntries.filter { it.requirementLevel == PermissionRequirementLevel.OPTIONAL_FEATURE }
    val grantedCount = verificationEntries.count { it.isEffectivelyGranted }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            // Wizard Top Header & Step Progress
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "NAYRO SETUP WIZARD • STEP ${stepIndex + 1} OF $totalSteps",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Coral
                    )
                    Text(
                        text = currentWizardStep.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }

                TextButton(
                    onClick = { onCompleteOnboarding(assistantNameInput) },
                    modifier = Modifier.testTag("onboarding_skip_button")
                ) {
                    Text("Skip for Now", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            LinearProgressIndicator(
                progress = { (stepIndex + 1).toFloat() / totalSteps.toFloat() },
                color = ForestGreen,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Main Step Content Area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when (currentWizardStep) {
                    SetupWizardStep.WELCOME -> {
                        WelcomeIdentityStep(
                            assistantName = assistantNameInput,
                            onNameChange = { assistantNameInput = it }
                        )
                    }
                    SetupWizardStep.CAPABILITY_OVERVIEW -> {
                        CapabilityOverviewStep(assistantName = assistantNameInput)
                    }
                    SetupWizardStep.CORE_PERMISSIONS_SETUP -> {
                        PermissionListStep(
                            headerSubtitle = "Required & recommended permissions for voice, floating Babu overlay, screen automation, and background survival.",
                            entries = coreEntries,
                            onRequestRuntimePermission = onRequestRuntimePermission,
                            onOpenPermissionSettings = onOpenPermissionSettings,
                            onRefreshPermissions = onRefreshPermissions
                        )
                    }
                    SetupWizardStep.OPTIONAL_PERMISSIONS_SETUP -> {
                        PermissionListStep(
                            headerSubtitle = "Optional permissions for contact lookup, notification summarization, calendar scheduling, and location context. NAYRO works even if you skip these.",
                            entries = optionalEntries,
                            onRequestRuntimePermission = onRequestRuntimePermission,
                            onOpenPermissionSettings = onOpenPermissionSettings,
                            onRefreshPermissions = onRefreshPermissions
                        )
                    }
                    SetupWizardStep.SERVICE_ACTIVATION -> {
                        ServiceActivationStep(
                            assistantName = assistantNameInput,
                            canDrawOverlays = evaluations[PermissionType.OVERLAY]?.status == PermissionStatus.GRANTED,
                            isAccessibilityEnabled = evaluations[PermissionType.ACCESSIBILITY]?.status == PermissionStatus.GRANTED,
                            isForegroundRunning = isForegroundRunning,
                            isFloatingRunning = isFloatingRunning,
                            onOpenOverlaySettings = { onOpenPermissionSettings(PermissionType.OVERLAY) },
                            onOpenAccessibilitySettings = { onOpenPermissionSettings(PermissionType.ACCESSIBILITY) },
                            onStartForegroundService = onStartForegroundService,
                            onStartFloatingService = onStartFloatingService
                        )
                    }
                    SetupWizardStep.VERIFICATION_REPORT -> {
                        VerificationReportStep(
                            entries = verificationEntries,
                            grantedCount = grantedCount,
                            totalCount = verificationEntries.size,
                            onRequestRuntimePermission = onRequestRuntimePermission,
                            onOpenPermissionSettings = onOpenPermissionSettings,
                            onRefreshPermissions = onRefreshPermissions
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Wizard Footer Navigation Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (stepIndex > 0) {
                    OutlinedButton(
                        onClick = { stepIndex-- },
                        modifier = Modifier.testTag("onboarding_prev_button")
                    ) {
                        Text("Back")
                    }
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }

                Button(
                    onClick = {
                        onRefreshPermissions()
                        if (stepIndex < totalSteps - 1) {
                            stepIndex++
                        } else {
                            onCompleteOnboarding(assistantNameInput)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ForestGreen),
                    modifier = Modifier.testTag("onboarding_next_button")
                ) {
                    Text(
                        text = if (stepIndex == totalSteps - 1) "Activate $assistantNameInput Agent" else "Continue"
                    )
                }
            }
        }
    }
}

@Composable
private fun WelcomeIdentityStep(
    assistantName: String,
    onNameChange: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        AssistantOrb(
            agentState = AgentState.Idle,
            size = 104.dp
        )
        Spacer(modifier = Modifier.height(20.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "Welcome to NAYRO",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Meet Babu — your autonomous, voice-first Android AI Agent supporting English, Hindi, and Hinglish. Let's configure your assistant identity and verify device permissions.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = assistantName,
                    onValueChange = onNameChange,
                    label = { Text("Assistant Persona Name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("onboarding_name_input")
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Default wake phrases: \"Hi Babu\", \"Hey Babu\", \"Hi Nayro\", \"Hey Nayro\"",
                    style = MaterialTheme.typography.labelSmall,
                    color = ForestGreen
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "For Gemini reasoning, add your own Google Gemini API key in Settings → AI Providers after setup. Voice commands use Android system speech recognition and device text-to-speech; an internet connection is needed for Gemini responses. The API key is not bundled in this APK.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun CapabilityOverviewStep(assistantName: String) {
    val pillars = listOf(
        Triple(
            "Local-First Voice & Privacy",
            "Standby wake-word detection runs locally on your device. Raw standby microphone audio is never streamed to the cloud.",
            Icons.Default.Mic
        ),
        Triple(
            "Andee-Style Floating Overlay",
            "$assistantName stays available as a draggable floating bubble across apps with instant text, voice, and screen reading.",
            Icons.Default.Layers
        ),
        Triple(
            "48 Verified Native Tools & Safety Guard",
            "Every action is schema-validated, permission-checked, risk-classified (R0–R4), and verified before reporting completion.",
            Icons.Default.Security
        ),
        Triple(
            "Transparent Permission Control",
            "No permission is marked granted until verified by Android OS. Every optional permission has a clean fallback.",
            Icons.Default.Notifications
        )
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(pillars) { (title, desc, icon) ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(ForestGreen.copy(alpha = 0.1f))
                    ) {
                        Icon(imageVector = icon, contentDescription = null, tint = ForestGreen)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = desc,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionListStep(
    headerSubtitle: String,
    entries: List<PermissionVerificationEntry>,
    onRequestRuntimePermission: (PermissionType) -> Unit,
    onOpenPermissionSettings: (PermissionType) -> Unit,
    onRefreshPermissions: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = headerSubtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(
                onClick = onRefreshPermissions,
                modifier = Modifier.testTag("wizard_refresh_perms_btn")
            ) {
                Icon(imageVector = Icons.Default.Refresh, contentDescription = "Verify", tint = ForestGreen)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Verify")
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(entries, key = { it.type.name }) { entry ->
                WizardPermissionCard(
                    entry = entry,
                    onRequestRuntime = { onRequestRuntimePermission(entry.type) },
                    onOpenSettings = { onOpenPermissionSettings(entry.type) }
                )
            }
        }
    }
}

@Composable
fun WizardPermissionCard(
    entry: PermissionVerificationEntry,
    onRequestRuntime: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val (badgeColor, badgeIcon, badgeLabel) = when (entry.status) {
        PermissionStatus.GRANTED -> Triple(SuccessGreen, Icons.Default.CheckCircle, "VERIFIED GRANTED")
        PermissionStatus.NOT_REQUIRED -> Triple(SuccessGreen, Icons.Default.CheckCircle, "AUTO GRANTED")
        PermissionStatus.SPECIAL_ACCESS_REQUIRED -> Triple(WarningAmber, Icons.Default.WarningAmber, "NEEDS SETTINGS")
        PermissionStatus.PERMANENTLY_DENIED -> Triple(ErrorRed, Icons.Default.ErrorOutline, "DENIED • SETTINGS")
        PermissionStatus.DENIED -> Triple(Coral, Icons.Default.WarningAmber, "NOT GRANTED")
        PermissionStatus.UNAVAILABLE -> Triple(ErrorRed, Icons.Default.ErrorOutline, "UNAVAILABLE")
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            width = 1.dp,
            color = if (entry.isEffectivelyGranted) SuccessGreen.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outline
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("wizard_perm_${entry.type.name}")
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.permissionName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${entry.requirementLevel.label} • ${entry.category.name}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Coral
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(badgeColor.copy(alpha = 0.12f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = badgeIcon,
                        contentDescription = null,
                        tint = badgeColor,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = badgeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = badgeColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = entry.purpose,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (entry.featureDependency.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Powers: ${entry.featureDependency}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (!entry.isEffectivelyGranted && entry.fallbackBehavior.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "If skipped: ${entry.fallbackBehavior}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!entry.isEffectivelyGranted) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (entry.canRequestRuntime && entry.status != PermissionStatus.PERMANENTLY_DENIED) {
                        Button(
                            onClick = onRequestRuntime,
                            colors = ButtonDefaults.buttonColors(containerColor = ForestGreen),
                            modifier = Modifier.testTag("wizard_req_${entry.type.name}")
                        ) {
                            Text("Grant Permission")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    if (entry.canOpenSettings) {
                        OutlinedButton(
                            onClick = onOpenSettings,
                            modifier = Modifier.testTag("wizard_settings_${entry.type.name}")
                        ) {
                            Icon(imageVector = Icons.Default.Settings, contentDescription = null, tint = ForestGreen)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Open Android Settings")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ServiceActivationStep(
    assistantName: String,
    canDrawOverlays: Boolean,
    isAccessibilityEnabled: Boolean,
    isForegroundRunning: Boolean,
    isFloatingRunning: Boolean,
    onOpenOverlaySettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onStartForegroundService: () -> Unit,
    onStartFloatingService: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "1. Floating $assistantName Overlay",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isFloatingRunning) {
                            "Floating Babu bubble is ACTIVE and ready over other apps."
                        } else if (canDrawOverlays) {
                            "Overlay permission verified. Tap below to launch the floating Babu bubble."
                        } else {
                            "Requires 'Display over other apps' permission in Android Settings."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    if (!canDrawOverlays) {
                        OutlinedButton(onClick = onOpenOverlaySettings) {
                            Text("Enable Overlay Permission in Settings")
                        }
                    } else if (!isFloatingRunning) {
                        Button(
                            onClick = onStartFloatingService,
                            colors = ButtonDefaults.buttonColors(containerColor = ForestGreen)
                        ) {
                            Text("Launch Floating Babu Bubble")
                        }
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "2. Background Agent & Wake Engine",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isForegroundRunning) {
                            "Foreground Agent Service is RUNNING with persistent status notification."
                        } else {
                            "Start the background service so Babu stays ready when you switch apps."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    if (!isForegroundRunning) {
                        Button(
                            onClick = onStartForegroundService,
                            colors = ButtonDefaults.buttonColors(containerColor = ForestGreen)
                        ) {
                            Text("Start Background Agent Service")
                        }
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "3. Accessibility Screen Intelligence",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isAccessibilityEnabled) {
                            "Accessibility Service is VERIFIED ENABLED. Screen reading & UI automation are ready."
                        } else {
                            "Required for full cross-app control: enable NAYRO in Android Accessibility Settings so Babu can read screens, click, type, scroll, and verify supported app actions. Without it, some actions will fall back to opening apps or drafting content for manual completion."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!isAccessibilityEnabled) {
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedButton(onClick = onOpenAccessibilitySettings) {
                            Text("Open Accessibility Settings")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VerificationReportStep(
    entries: List<PermissionVerificationEntry>,
    grantedCount: Int,
    totalCount: Int,
    onRequestRuntimePermission: (PermissionType) -> Unit,
    onOpenPermissionSettings: (PermissionType) -> Unit,
    onRefreshPermissions: () -> Unit
) {
    val coreEntries = entries.filter { it.requirementLevel == PermissionRequirementLevel.CORE_AGENT }
    val coreGranted = coreEntries.count { it.isEffectivelyGranted }

    Column(modifier = Modifier.fillMaxSize()) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Live OS Verification Summary",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Core Verified: $coreGranted/${coreEntries.size} • Total Verified: $grantedCount/$totalCount",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ForestGreen
                    )
                }
                OutlinedButton(onClick = onRefreshPermissions) {
                    Icon(imageVector = Icons.Default.Refresh, contentDescription = null, tint = ForestGreen)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Re-Verify")
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(entries, key = { "report_${it.type.name}" }) { entry ->
                WizardPermissionCard(
                    entry = entry,
                    onRequestRuntime = { onRequestRuntimePermission(entry.type) },
                    onOpenSettings = { onOpenPermissionSettings(entry.type) }
                )
            }
        }
    }
}
