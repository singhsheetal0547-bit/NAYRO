package com.example.ui.tools

import androidx.compose.foundation.background
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
import com.example.core.agent.tools.DynamicToolDefinition
import com.example.core.agent.tools.ToolCategory
import com.example.core.agent.tools.ToolRegistryManager
import com.example.ui.theme.Coral
import com.example.ui.theme.ForestGreen
import com.example.ui.theme.SoftSage

@Composable
fun ToolsCatalogScreen(
    modifier: Modifier = Modifier
) {
    var selectedCategory by remember { mutableStateOf<ToolCategory?>(null) }
    val allTools = remember { ToolRegistryManager.getAllTools() }
    val categories = remember { ToolCategory.values().toList() }

    val displayedTools = remember(selectedCategory) {
        if (selectedCategory == null) allTools else ToolRegistryManager.getToolsByCategory(selectedCategory!!)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag("tools_catalog_screen")
    ) {
        Text(
            text = "Dynamic Tool Ecosystem",
            style = MaterialTheme.typography.headlineSmall,
            color = ForestGreen
        )
        Text(
            text = "${allTools.size} Active Tools available for autonomous LLM discovery & execution.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Category Filter Chips
        ScrollableTabRow(
            selectedTabIndex = categories.indexOf(selectedCategory).coerceAtLeast(0),
            edgePadding = 0.dp,
            containerColor = SoftSage.copy(alpha = 0.3f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Tab(
                selected = selectedCategory == null,
                onClick = { selectedCategory = null },
                text = { Text("All (${allTools.size})", style = MaterialTheme.typography.labelMedium) }
            )
            categories.forEach { cat ->
                val count = allTools.count { it.category == cat }
                Tab(
                    selected = selectedCategory == cat,
                    onClick = { selectedCategory = cat },
                    text = { Text("${cat.displayName} ($count)", style = MaterialTheme.typography.labelMedium) }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(displayedTools, key = { it.toolId }) { tool ->
                ToolItemCard(tool = tool)
            }
        }
    }
}

@Composable
fun ToolItemCard(tool: DynamicToolDefinition) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth().testTag("tool_card_${tool.toolId}")
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = tool.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = ForestGreen
                )
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Coral.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = tool.riskLevel.code,
                        style = MaterialTheme.typography.labelSmall,
                        color = Coral,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = tool.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "ID: ${tool.toolId} | Category: ${tool.category.displayName}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (tool.parametersSchema.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Params: ${tool.parametersSchema.keys.joinToString(", ")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = ForestGreen
                )
            }
        }
    }
}
