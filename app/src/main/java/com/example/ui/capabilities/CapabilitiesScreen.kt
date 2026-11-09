package com.example.ui.capabilities

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.core.permission.PermissionState
import com.example.core.permission.PermissionType
import com.example.ui.components.StatusBadge
import com.example.ui.theme.Coral
import com.example.ui.theme.ForestGreen

@Composable
fun CapabilitiesScreen(
    permissionStates: Map<PermissionType, PermissionState> = emptyMap(),
    onToggleCapability: (PermissionType) -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        Text(
            text = "Native Android Capabilities",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "Transparent tool registry and Android security permissions",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(PermissionType.values()) { perm ->
                val state = permissionStates[perm] ?: PermissionState.NotRequested
                val isGranted = state is PermissionState.Granted

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .testTag("capability_card_${perm.name}")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = perm.title,
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = perm.description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            StatusBadge(
                                label = if (isGranted) "Permission Active" else "Setup Required",
                                isActive = isGranted,
                                testTag = "badge_perm_${perm.name}"
                            )
                        }
                        Switch(
                            checked = isGranted,
                            onCheckedChange = { onToggleCapability(perm) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Coral,
                                checkedTrackColor = ForestGreen
                            ),
                            modifier = Modifier.testTag("switch_${perm.name}")
                        )
                    }
                }
            }
        }
    }
}
