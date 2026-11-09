package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.core.agent.AgentTask
import com.example.core.agent.TaskState
import com.example.ui.theme.Coral
import com.example.ui.theme.DeepGreen
import com.example.ui.theme.ForestGreen
import com.example.ui.theme.SoftSage
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
            containerColor = ForestGreen
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val stateIcon = when (task.state) {
                        TaskState.COMPLETED -> Icons.Default.CheckCircle
                        TaskState.FAILED -> Icons.Default.Warning
                        TaskState.EXECUTING, TaskState.VERIFYING -> Icons.Default.PlayArrow
                        else -> Icons.Default.Refresh
                    }
                    val iconColor = when (task.state) {
                        TaskState.COMPLETED -> SuccessGreen
                        TaskState.FAILED -> Coral
                        else -> SoftSage
                    }
                    Icon(imageVector = stateIcon, contentDescription = null, tint = iconColor)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "TASK: ${task.resolvedIntent}",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                }

                // State badge
                Text(
                    text = task.state.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = when (task.state) {
                        TaskState.COMPLETED -> SuccessGreen
                        TaskState.FAILED -> Coral
                        TaskState.WAITING_FOR_CONFIRMATION -> Coral
                        else -> SoftSage
                    },
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(DeepGreen)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "\"${task.userInstruction}\"",
                style = MaterialTheme.typography.bodyMedium,
                color = SoftSage
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Step Indicator & Progress
            val currentStep = (task.currentActionIndex + 1).coerceAtMost(task.actions.size)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Step $currentStep of ${task.actions.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White
                )
                task.currentAction?.let { action ->
                    Text(
                        text = "${action.actionType} (${action.riskLevel.code})",
                        style = MaterialTheme.typography.labelSmall,
                        color = SoftSage
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            LinearProgressIndicator(
                progress = { task.progressPercentage },
                color = Coral,
                trackColor = DeepGreen,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Cancel action if in progress
            if (task.state != TaskState.COMPLETED && task.state != TaskState.FAILED && task.state != TaskState.CANCELLED) {
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = onCancelTask,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = SoftSage),
                        modifier = Modifier.testTag("btn_cancel_task")
                    ) {
                        Icon(imageVector = Icons.Default.Cancel, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Cancel Task")
                    }
                }
            }
        }
    }
}
