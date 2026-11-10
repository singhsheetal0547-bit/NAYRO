package com.example.core.agent.tools

import com.example.core.agent.ActionType

/**
 * Interface contract for managing and discovering agent capabilities.
 * Allows the LLM to dynamically query and select tools like Android navigation or system settings.
 */
interface ToolCatalog {
    fun getTool(actionType: ActionType): DynamicToolDefinition?
    fun getAllTools(): List<DynamicToolDefinition>
    fun getToolsByCategory(category: ToolCategory): List<DynamicToolDefinition>
    fun generateLlmToolPromptSummary(): String
}

/**
 * Interface contract for registering dynamic capabilities into the agent system.
 */
interface ToolRegistry {
    fun registerTool(definition: DynamicToolDefinition)
    fun unregisterTool(actionType: ActionType)
    fun isToolAvailable(actionType: ActionType): Boolean
}
