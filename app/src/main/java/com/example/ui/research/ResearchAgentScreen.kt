package com.example.ui.research

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.core.provider.AIProviderManager
import com.example.core.workspace.WorkspaceManager
import com.example.ui.theme.ForestGreen
import kotlinx.coroutines.launch

@Composable
fun ResearchAgentScreen(
    providerManager: AIProviderManager,
    workspaceManager: WorkspaceManager,
    modifier: Modifier = Modifier
) {
    var query by remember { mutableStateOf("") }
    var researchOutput by remember { mutableStateOf("") }
    var isResearching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag("research_agent_screen")
    ) {
        Text(
            text = "Web Research & Browser Agent",
            style = MaterialTheme.typography.headlineSmall,
            color = ForestGreen
        )
        Text(
            text = "Multi-Source Research, Web Page Extraction, Citation Analysis & Synthesis.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(14.dp))

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Research Topic / Query") },
            modifier = Modifier.fillMaxWidth().testTag("research_query_input")
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                if (query.isNotBlank()) {
                    isResearching = true
                    scope.launch {
                        val prompt = "Perform multi-step research on '$query'. Synthesize findings, extract key facts, and format a report."
                        val result = providerManager.sendRequest(prompt)
                        researchOutput = when (result) {
                            is com.example.core.provider.model.AIResult.Success -> result.response.content
                            is com.example.core.provider.model.AIResult.Error -> "Research Error: ${result.message}"
                        }
                        workspaceManager.createReportArtifact(query, researchOutput)
                        isResearching = false
                    }
                }
            },
            enabled = !isResearching,
            colors = ButtonDefaults.buttonColors(containerColor = ForestGreen),
            modifier = Modifier.align(Alignment.End).testTag("start_research_btn")
        ) {
            Text(if (isResearching) "Researching..." else "Start Research")
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (researchOutput.isNotBlank()) {
            Text("Research Report & Citations", style = MaterialTheme.typography.titleMedium, color = ForestGreen)
            Spacer(modifier = Modifier.height(6.dp))
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth().weight(1f)
            ) {
                LazyColumn(modifier = Modifier.padding(14.dp)) {
                    item {
                        Text(text = researchOutput, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
