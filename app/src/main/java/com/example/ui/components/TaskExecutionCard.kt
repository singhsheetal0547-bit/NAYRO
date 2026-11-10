package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.core.agent.AgentTask
import com.example.core.agent.TaskState
import com.example.ui.theme.Coral
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.ForestGreen
import com.example.ui.theme.SuccessGreen

@Composable
fun TaskExecutionCard(
    task: AgentTask,
    onCancelTask: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("task_execution_card")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    val stateIcon = when (task.state) {
                        TaskState.COMPLETED, TaskState.PARTIALLY_COMPLETED -> Icons.Default.CheckCircle
                        TaskState.FAILED, TaskState.BLOCKED_BY_ANDROID -> Icons.Default.Warning
                        TaskState.EXECUTING, TaskState.RUNNING, TaskState.VERIFYING -> Icons.Default.PlayArrow
                        else -> Icons.Default.Refresh
                    }
                    val iconColor = when (task.state) {
                        TaskState.COMPLETED -> SuccessGreen
                        TaskState.FAILED, TaskState.BLOCKED_BY_ANDROID -> ErrorRed
                        else -> ForestGreen
                    }
                    Icon(imageVector = stateIcon, contentDescription = null, tint = iconColor)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = task.resolvedIntent,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                val badgeColor = when (task.state) {
                    TaskState.COMPLETED -> SuccessGreen
                    TaskState.FAILED, TaskState.BLOCKED_BY_ANDROID -> ErrorRed
                    TaskState.WAITING_FOR_CONFIRMATION, TaskState.WAITING_FOR_USER_INPUT -> Coral
                    else -> ForestGreen
                }

                Text(
                    text = task.state.name,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = badgeColor,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(badgeColor.copy(alpha = 0.12f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "\"${task.userInstruction}\"",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(10.dp))

            val currentStep = (task.currentActionIndex + 1).coerceAtMost(task.actions.size.coerceAtLeast(1))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Step $currentStep of ${task.actions.size.coerceAtLeast(1)}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                task.currentAction?.let { action ->
                    Text(
                        text = "${action.actionType} • ${action.riskLevel.code}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Coral
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            LinearProgressIndicator(
                progress = { task.progressPercentage },
                color = ForestGreen,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
            )

            if (task.state != TaskState.COMPLETED &&
                task.state != TaskState.FAILED &&
                task.state != TaskState.CANCELLED &&
                task.state != TaskState.BLOCKED_BY_ANDROID &&
                task.state != TaskState.NOT_SUPPORTED
            ) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = onCancelTask,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed),
                        modifier = Modifier.testTag("btn_cancel_task")
                    ) {
                        Icon(imageVector = Icons.Default.Cancel, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Stop Task")
                    }
                }
            }
        }
    }
}
