package com.example.ui.subagent

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.core.subagent.SubAgentManager
import com.example.core.subagent.SubAgentType
import com.example.ui.theme.Coral
import com.example.ui.theme.ForestGreen

@Composable
fun SubAgentManagerScreen(
    subAgentManager: SubAgentManager,
    onDelegate: (SubAgentType, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val specialistAgents = remember { SubAgentType.values().toList() }
    val activeSubTasks = remember { subAgentManager.getActiveSubTasks() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag("subagent_manager_screen")
    ) {
        Text(
            text = "Specialist Sub-Agents Manager",
            style = MaterialTheme.typography.headlineSmall,
            color = ForestGreen
        )
        Text(
            text = "Multi-Agent Delegation: Specialist agents handle sub-goals independently.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (activeSubTasks.isNotEmpty()) {
            Text("Active Sub-Agent Tasks", style = MaterialTheme.typography.titleMedium, color = ForestGreen)
            Spacer(modifier = Modifier.height(8.dp))
            activeSubTasks.forEach { subTask ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Coral.copy(alpha = 0.1f)),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(text = "${subTask.agentType.displayName} • ${subTask.status}", style = MaterialTheme.typography.titleSmall, color = Coral)
                        Text(text = "Sub-Goal: ${subTask.subGoal}", style = MaterialTheme.typography.bodySmall)
                        subTask.outputResult?.let {
                            Text(text = "Result: $it", style = MaterialTheme.typography.labelSmall, color = ForestGreen)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        Text("Specialist Agents Roster", style = MaterialTheme.typography.titleMedium, color = ForestGreen)
        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(specialistAgents) { agent ->
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth().testTag("subagent_card_${agent.name}")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth().padding(12.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = agent.displayName, style = MaterialTheme.typography.titleSmall, color = ForestGreen)
                            Text(text = agent.description, style = MaterialTheme.typography.bodySmall)
                        }
                        Button(
                            onClick = { onDelegate(agent, "Analyze sub-goal for ${agent.displayName}") },
                            colors = ButtonDefaults.buttonColors(containerColor = ForestGreen),
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Text("Delegate", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}
