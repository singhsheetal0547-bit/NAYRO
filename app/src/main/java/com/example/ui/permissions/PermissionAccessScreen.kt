package com.example.ui.permissions

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.core.permission.PermissionCategory
import com.example.core.permission.PermissionEvaluation
import com.example.core.permission.PermissionRequirementLevel
import com.example.core.permission.PermissionStatus
import com.example.core.permission.PermissionType
import com.example.ui.theme.Coral
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.ForestGreen
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningAmber

/**
 * Part 2 & Part 3 — Complete Permission Dashboard.
 * Displays:
 * PERMISSION NAME | PURPOSE | STATUS | REQUIRED / OPTIONAL | FEATURE DEPENDENCY | SETTINGS SHORTCUT | FALLBACK BEHAVIOR
 */
@Composable
fun PermissionAccessScreen(
    evaluations: Map<PermissionType, PermissionEvaluation> = emptyMap(),
    onRequestRuntimePermission: (PermissionType) -> Unit = {},
    onOpenSystemSettings: (PermissionType) -> Unit = {},
    onRefreshPermissions: () -> Unit = {},
    onLaunchSetupWizard: (() -> Unit)? = null
) {
    var selectedFilter by remember { mutableStateOf("ALL") }

    val allEvaluations = evaluations.values.toList()
    val filteredEvaluations = when (selectedFilter) {
        "CORE" -> allEvaluations.filter { it.item.requirementLevel == PermissionRequirementLevel.CORE_AGENT }
        "OPTIONAL" -> allEvaluations.filter { it.item.requirementLevel == PermissionRequirementLevel.OPTIONAL_FEATURE }
        "MISSING" -> allEvaluations.filter {
            it.status != PermissionStatus.GRANTED && it.status != PermissionStatus.NOT_REQUIRED
        }
        else -> allEvaluations
    }

    val totalCount = allEvaluations.size
    val grantedCount = allEvaluations.count {
        it.status == PermissionStatus.GRANTED || it.status == PermissionStatus.NOT_REQUIRED
    }
    val coreTotal = allEvaluations.count { it.item.requirementLevel == PermissionRequirementLevel.CORE_AGENT }
    val coreGranted = allEvaluations.count {
        it.item.requirementLevel == PermissionRequirementLevel.CORE_AGENT &&
            (it.status == PermissionStatus.GRANTED || it.status == PermissionStatus.NOT_REQUIRED)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Permission & Access Dashboard",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Real-time Android OS permission audit, feature dependencies & fallback behavior.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(
                onClick = onRefreshPermissions,
                modifier = Modifier.testTag("btn_refresh_permissions")
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Refresh",
                    tint = ForestGreen,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Verify")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Summary Verification Banner Card
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Core Agent Access: $coreGranted / $coreTotal Verified",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (coreGranted == coreTotal && coreTotal > 0) SuccessGreen else ForestGreen
                    )
                    Text(
                        text = "Total Permissions Verified: $grantedCount / $totalCount",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (onLaunchSetupWizard != null) {
                    Button(
                        onClick = onLaunchSetupWizard,
                        colors = ButtonDefaults.buttonColors(containerColor = ForestGreen),
                        modifier = Modifier.testTag("btn_open_setup_wizard")
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoFixHigh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Setup Wizard")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Filter Chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                "ALL" to "All ($totalCount)",
                "CORE" to "Required ($coreTotal)",
                "OPTIONAL" to "Optional (${totalCount - coreTotal})",
                "MISSING" to "Needs Action (${totalCount - grantedCount})"
            ).forEach { (key, label) ->
                FilterChip(
                    selected = selectedFilter == key,
                    onClick = { selectedFilter = key },
                    label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(filteredEvaluations, key = { it.item.type.name }) { eval ->
                PermissionCard(
                    eval = eval,
                    onRequestRuntime = { onRequestRuntimePermission(eval.item.type) },
                    onOpenSettings = { onOpenSystemSettings(eval.item.type) }
                )
            }
        }
    }
}

@Composable
fun PermissionCard(
    eval: PermissionEvaluation,
    onRequestRuntime: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val (statusColor, statusIcon, statusText) = when (eval.status) {
        PermissionStatus.GRANTED -> Triple(SuccessGreen, Icons.Default.CheckCircle, "GRANTED")
        PermissionStatus.DENIED -> Triple(ErrorRed, Icons.Default.Error, "DENIED")
        PermissionStatus.PERMANENTLY_DENIED -> Triple(ErrorRed, Icons.Default.Error, "PERMANENTLY DENIED")
        PermissionStatus.SPECIAL_ACCESS_REQUIRED -> Triple(WarningAmber, Icons.Default.Warning, "SPECIAL ACCESS REQUIRED")
        PermissionStatus.NOT_REQUIRED -> Triple(SuccessGreen, Icons.Default.Info, "NOT REQUIRED")
        PermissionStatus.UNAVAILABLE -> Triple(Color.Gray, Icons.Default.Info, "UNAVAILABLE")
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("perm_card_${eval.item.type.name}")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = eval.item.permissionName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${eval.item.requirementLevel.label} • ${eval.item.category.name}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Coral
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(statusColor.copy(alpha = 0.12f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = statusIcon,
                        contentDescription = statusText,
                        tint = statusColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = statusColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = eval.item.purpose,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(6.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            Spacer(modifier = Modifier.height(6.dp))

            if (eval.item.featureDependency.isNotBlank()) {
                Text(
                    text = "Feature Dependency: ${eval.item.featureDependency}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
            }

            Text(
                text = "OS Verification: ${eval.statusExplanation}",
                style = MaterialTheme.typography.labelSmall,
                color = if (eval.status == PermissionStatus.GRANTED) SuccessGreen else MaterialTheme.colorScheme.onSurface
            )

            if (eval.item.fallbackBehavior.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Fallback Behavior: ${eval.item.fallbackBehavior}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (eval.status != PermissionStatus.GRANTED && eval.status != PermissionStatus.NOT_REQUIRED) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (eval.item.category == PermissionCategory.RUNTIME && eval.status == PermissionStatus.DENIED) {
                        Button(
                            onClick = onRequestRuntime,
                            colors = ButtonDefaults.buttonColors(containerColor = ForestGreen),
                            modifier = Modifier.testTag("request_btn_${eval.item.type.name}")
                        ) {
                            Text("Request Permission")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }

                    if (eval.canLaunchSettings || eval.status == PermissionStatus.PERMANENTLY_DENIED) {
                        OutlinedButton(
                            onClick = onOpenSettings,
                            modifier = Modifier.testTag("settings_btn_${eval.item.type.name}")
                        ) {
                            Icon(imageVector = Icons.Default.Settings, contentDescription = "Settings", tint = Coral)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Open Settings")
                        }
                    }
                }
            }
        }
    }
}
