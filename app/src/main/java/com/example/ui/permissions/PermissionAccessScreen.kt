package com.example.ui.permissions

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.core.permission.PermissionCategory
import com.example.core.permission.PermissionEvaluation
import com.example.core.permission.PermissionStatus
import com.example.core.permission.PermissionType
import com.example.ui.theme.Coral
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.ForestGreen
import com.example.ui.theme.SoftSage
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningAmber

@Composable
fun PermissionAccessScreen(
    evaluations: Map<PermissionType, PermissionEvaluation> = emptyMap(),
    onRequestRuntimePermission: (PermissionType) -> Unit = {},
    onOpenSystemSettings: (PermissionType) -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        Text(
            text = "Permission & Access Architecture",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "Centralized system permission status, explanation, and settings navigation.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(evaluations.values.toList()) { eval ->
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
        PermissionStatus.NOT_REQUIRED -> Triple(SoftSage, Icons.Default.Info, "NOT REQUIRED")
        PermissionStatus.UNAVAILABLE -> Triple(Color.Gray, Icons.Default.Info, "UNAVAILABLE")
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("perm_card_${eval.item.type.name}")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = eval.item.permissionName,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Category: ${eval.item.category.name}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Coral
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(statusColor.copy(alpha = 0.15f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = statusIcon,
                        contentDescription = statusText,
                        tint = statusColor
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
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

            Text(
                text = eval.statusExplanation,
                style = MaterialTheme.typography.bodyMedium,
                color = if (eval.status == PermissionStatus.GRANTED) SuccessGreen else MaterialTheme.colorScheme.onSurface
            )

            if (eval.item.associatedCapabilities.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Associated Capabilities: ${eval.item.associatedCapabilities.joinToString(", ")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Action Buttons
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                if (eval.status != PermissionStatus.GRANTED && eval.status != PermissionStatus.NOT_REQUIRED) {
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
