package com.example.ui.capabilities

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
import androidx.compose.material.icons.filled.HourglassEmpty
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
import com.example.core.capability.CapabilityEvaluation
import com.example.core.capability.CapabilityId
import com.example.core.capability.CapabilityStatus
import com.example.ui.theme.Coral
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.ForestGreen
import com.example.ui.theme.SoftSage
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningAmber

@Composable
fun CapabilitiesScreen(
    capabilities: Map<CapabilityId, CapabilityEvaluation> = emptyMap(),
    onActionClick: (CapabilityId, String) -> Unit = { _, _ -> }
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        Text(
            text = "Capability & Device Architecture",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "Dynamic evaluation of real agent capabilities vs planned features",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(capabilities.values.toList()) { eval ->
                CapabilityCard(
                    eval = eval,
                    onAction = { label -> onActionClick(eval.definition.id, label) }
                )
            }
        }
    }
}

@Composable
fun CapabilityCard(
    eval: CapabilityEvaluation,
    onAction: (String) -> Unit
) {
    val (statusColor, statusIcon, statusLabel) = when (eval.status) {
        CapabilityStatus.AVAILABLE -> Triple(SuccessGreen, Icons.Default.CheckCircle, "AVAILABLE")
        CapabilityStatus.BLOCKED_BY_PERMISSION -> Triple(ErrorRed, Icons.Default.Warning, "BLOCKED BY PERMISSION")
        CapabilityStatus.BLOCKED_BY_SETTINGS -> Triple(WarningAmber, Icons.Default.Settings, "BLOCKED BY SETTINGS")
        CapabilityStatus.BLOCKED_BY_ANDROID_VERSION -> Triple(Color.Gray, Icons.Default.Info, "OS INCOMPATIBLE")
        CapabilityStatus.SERVICE_NOT_RUNNING -> Triple(WarningAmber, Icons.Default.Info, "SERVICE STOPPED")
        CapabilityStatus.NOT_IMPLEMENTED -> Triple(SoftSage, Icons.Default.HourglassEmpty, "FUTURE PHASE")
        CapabilityStatus.UNAVAILABLE -> Triple(Color.Gray, Icons.Default.Info, "UNAVAILABLE")
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("cap_card_${eval.definition.id.name}")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = eval.definition.displayName,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (eval.definition.isFutureCapability) {
                        Text(
                            text = "Phase 3+ Capability",
                            style = MaterialTheme.typography.labelSmall,
                            color = Coral
                        )
                    }
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
                        contentDescription = statusLabel,
                        tint = statusColor
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = eval.definition.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = eval.statusReason,
                style = MaterialTheme.typography.bodyMedium,
                color = if (eval.status == CapabilityStatus.AVAILABLE) SuccessGreen else MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Prerequisites: ${eval.definition.activationPrerequisites}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (eval.isActionable && eval.actionLabel != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = { onAction(eval.actionLabel) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (eval.status == CapabilityStatus.AVAILABLE) Coral else ForestGreen
                        ),
                        modifier = Modifier.testTag("cap_btn_${eval.definition.id.name}")
                    ) {
                        Text(eval.actionLabel)
                    }
                }
            }
        }
    }
}
