package com.example.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.core.agent.AgentRuntime
import com.example.core.agent.tools.ToolRegistryManager
import com.example.core.notification.NotificationIntelligenceRepository
import com.example.core.security.AutonomyLevel

/**
 * Agent Control Center Dashboard (Part 19).
 * Provides live observability and control over NAYRO's unified AgentRuntime:
 * - Overview & Live Task State
 * - Agent Autonomy Level (1-5) & Emergency Stop
 * - Cross-App Multi-Step Workflows
 * - Event-Based Automations
 * - Proactive Suggestions
 * - Notification Intelligence Buffer
 */
@Composable
fun AgentDashboardScreen(
    agentRuntime: AgentRuntime,
    onExecuteInstruction: (String) -> Unit
) {
    val currentState by agentRuntime.currentState.collectAsState()
    val currentTask by agentRuntime.currentTask.collectAsState()
    val autonomyLevel by agentRuntime.autonomyPolicyManager.autonomyLevel.collectAsState()
    val isAutomationPaused by agentRuntime.autonomyPolicyManager.isGlobalAutomationPaused.collectAsState()
    val isEmergencyStopped by agentRuntime.autonomyPolicyManager.isEmergencyStopActive.collectAsState()
    val workflows = remember { agentRuntime.workflowEngine.getBuiltInTemplates() }
    val automations by agentRuntime.automationEngine.rules.collectAsState()
    val suggestions by agentRuntime.proactiveEngine.suggestions.collectAsState()
    val notifications by NotificationIntelligenceRepository.notifications.collectAsState()
    val isNotifConnected by NotificationIntelligenceRepository.isListenerConnected.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag("agent_dashboard_list"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "NAYRO Agent Control Center",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Unified Runtime, Autonomy Governance, Cross-App Workflows & Automations",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 1. Live Runtime Status & Emergency Stop Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Runtime Status: ${currentState::class.simpleName ?: "Idle"}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = currentTask?.let {
                                    "Active Task: ${it.userInstruction} [${it.state.name}]"
                                } ?: "No active foreground task",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "Registered Tools: ${ToolRegistryManager.getAllTools().size} | Notifications Connected: $isNotifConnected (${notifications.size} buffered)",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Button(
                            onClick = {
                                if (isEmergencyStopped) {
                                    agentRuntime.autonomyPolicyManager.clearEmergencyStop()
                                } else {
                                    agentRuntime.triggerEmergencyStop()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isEmergencyStopped) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            ),
                            modifier = Modifier.testTag("emergency_stop_button")
                        ) {
                            Text(if (isEmergencyStopped) "Resume Agent" else "Emergency Stop")
                        }
                    }
                }
            }
        }

        // 2. Autonomy Policy Level (1-5) Card
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = autonomyLevel.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = autonomyLevel.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AutonomyLevel.entries.forEach { level ->
                            FilterChip(
                                selected = autonomyLevel == level,
                                onClick = { agentRuntime.autonomyPolicyManager.setAutonomyLevel(level) },
                                label = { Text("L${level.levelNumber}") },
                                modifier = Modifier.testTag("autonomy_level_${level.levelNumber}")
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Pause All Autonomous Actions", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = isAutomationPaused,
                            onCheckedChange = { agentRuntime.autonomyPolicyManager.setGlobalAutomationPaused(it) },
                            modifier = Modifier.testTag("pause_automation_switch")
                        )
                    }
                }
            }
        }

        // 3. Cross-App Multi-Step Workflows
        item {
            Text(
                text = "Cross-App Workflows (${workflows.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
        items(workflows, key = { it.templateId }) { wf ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(wf.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text("${wf.steps.size} steps (${wf.templateId})", style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(
                        onClick = { onExecuteInstruction("Execute workflow: ${wf.templateId}") },
                        modifier = Modifier.testTag("run_workflow_${wf.templateId}")
                    ) {
                        Text("Run")
                    }
                }
            }
        }

        // 4. Event-Based Automations
        item {
            Text(
                text = "Event-Based Automations (${automations.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
        items(automations, key = { it.ruleId }) { rule ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(rule.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${rule.triggerType.displayName} (${rule.triggerConditionValue}) -> ${rule.actionInstruction}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = rule.isEnabled,
                        onCheckedChange = { enabled ->
                            agentRuntime.automationEngine.toggleRule(rule.ruleId, enabled)
                        },
                        modifier = Modifier.testTag("toggle_automation_${rule.ruleId}")
                    )
                }
            }
        }

        // 5. Proactive Suggestions
        if (suggestions.isNotEmpty()) {
            item {
                Text(
                    text = "Proactive Suggestions (${suggestions.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            items(suggestions, key = { it.suggestionId }) { sug ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("[${sug.priority.name}] ${sug.title}", fontWeight = FontWeight.SemiBold)
                        Text(sug.description, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { onExecuteInstruction(sug.suggestedPrompt) }) {
                                Text("Accept")
                            }
                            OutlinedButton(onClick = {
                                agentRuntime.proactiveEngine.dismissSuggestion(sug.suggestionId)
                            }) {
                                Text("Dismiss")
                            }
                        }
                    }
                }
            }
        }
    }
}
