package com.example.core.agent.tools

import com.example.core.agent.ActionType
import com.example.core.agent.RiskLevel
import com.example.core.capability.CapabilityId

enum class ToolCategory(val displayName: String) {
    DEVICE_SYSTEM("Device & System"),
    SCREEN_CONTROL("Screen & Accessibility"),
    COMMUNICATION("Communication & Phone"),
    WORKSPACE_FILES("Workspace & Files"),
    WEB_RESEARCH("Web Research & Search"),
    CODING_AGENT("Coding Workspace"),
    SKILLS_SUBAGENTS("Skills & Sub-Agents"),
    PRODUCTIVITY("Productivity & Utilities")
}

data class DynamicToolDefinition(
    val actionType: ActionType,
    val toolId: String,
    val displayName: String,
    val description: String,
    val category: ToolCategory,
    val parametersSchema: Map<String, String>,
    val riskLevel: RiskLevel,
    val requiredCapability: CapabilityId? = null,
    val requiredPermission: String? = null
)

object ToolRegistryManager : ToolRegistry, ToolCatalog {

    private val catalog = mutableMapOf<ActionType, DynamicToolDefinition>()

    init {
        registerBuiltInTools()
    }

    private fun registerBuiltInTools() {
        // Device & System Tools
        register(
            DynamicToolDefinition(
                actionType = ActionType.LAUNCH_APP,
                toolId = "android.launch_app",
                displayName = "Launch Installed App",
                description = "Launches an installed Android application by package or display name.",
                category = ToolCategory.DEVICE_SYSTEM,
                parametersSchema = mapOf("appName" to "String - Display or package name of the app"),
                riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.GET_DEVICE_INFO,
                toolId = "android.get_device_info",
                displayName = "Get Device Information",
                description = "Retrieves Android version, device model, battery status, and memory info.",
                category = ToolCategory.DEVICE_SYSTEM,
                parametersSchema = emptyMap(),
                riskLevel = RiskLevel.R0_LOCAL_READONLY
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.GET_BATTERY_STATE,
                toolId = "android.get_battery_state",
                displayName = "Get Battery Status",
                description = "Returns current battery percentage, charging state, and power saver mode.",
                category = ToolCategory.DEVICE_SYSTEM,
                parametersSchema = emptyMap(),
                riskLevel = RiskLevel.R0_LOCAL_READONLY
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.GET_NETWORK_STATUS,
                toolId = "android.get_network_status",
                displayName = "Get Network & Wi-Fi Status",
                description = "Inspects active Wi-Fi, cellular connectivity, and internet accessibility.",
                category = ToolCategory.DEVICE_SYSTEM,
                parametersSchema = emptyMap(),
                riskLevel = RiskLevel.R0_LOCAL_READONLY
            )
        )

        // Screen & Interaction Tools
        register(
            DynamicToolDefinition(
                actionType = ActionType.READ_SCREEN,
                toolId = "screen.read_screen",
                displayName = "Read Screen Text",
                description = "Extracts all visible text and accessibility nodes from the current foreground app.",
                category = ToolCategory.SCREEN_CONTROL,
                parametersSchema = emptyMap(),
                riskLevel = RiskLevel.R0_LOCAL_READONLY,
                requiredCapability = CapabilityId.ACCESSIBILITY
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.CLICK_ELEMENT,
                toolId = "screen.click_element",
                displayName = "Click UI Element",
                description = "Performs an accessibility tap on a button or UI element matching target text or view ID.",
                category = ToolCategory.SCREEN_CONTROL,
                parametersSchema = mapOf("target" to "String - Text label or ID of UI element"),
                riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE,
                requiredCapability = CapabilityId.ACCESSIBILITY
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.SET_TEXT,
                toolId = "screen.set_text",
                displayName = "Enter Text in Field",
                description = "Injects text into an active editable text field on screen.",
                category = ToolCategory.SCREEN_CONTROL,
                parametersSchema = mapOf("text" to "String - Text content to enter"),
                riskLevel = RiskLevel.R2_APPLICATION_STATE,
                requiredCapability = CapabilityId.ACCESSIBILITY
            )
        )

        // Communication Tools
        register(
            DynamicToolDefinition(
                actionType = ActionType.MESSAGE_DRAFT_SEND,
                toolId = "comm.send_whatsapp_message",
                displayName = "Send WhatsApp Message",
                description = "Navigates to WhatsApp contact, prepares draft, requires user confirmation, and sends.",
                category = ToolCategory.COMMUNICATION,
                parametersSchema = mapOf("recipient" to "String - Target contact name", "message" to "String - Message text"),
                riskLevel = RiskLevel.R3_EXTERNAL_COMMUNICATION,
                requiredCapability = CapabilityId.ACCESSIBILITY
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.CHECK_INCOMING_CALL,
                toolId = "comm.check_incoming_call",
                displayName = "Check Incoming Call Info",
                description = "Queries active phone call state and resolves caller contact display name.",
                category = ToolCategory.COMMUNICATION,
                parametersSchema = emptyMap(),
                riskLevel = RiskLevel.R0_LOCAL_READONLY
            )
        )

        // Workspace & File Tools
        register(
            DynamicToolDefinition(
                actionType = ActionType.FILE_BROWSE,
                toolId = "workspace.file_browse",
                displayName = "Browse Workspace Files",
                description = "Lists files and directories inside the NAYRO application workspace folder.",
                category = ToolCategory.WORKSPACE_FILES,
                parametersSchema = mapOf("path" to "String - Relative workspace path"),
                riskLevel = RiskLevel.R0_LOCAL_READONLY
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.FILE_READ_TEXT,
                toolId = "workspace.file_read_text",
                displayName = "Read File Content",
                description = "Reads text content from a file in the persistent workspace.",
                category = ToolCategory.WORKSPACE_FILES,
                parametersSchema = mapOf("filename" to "String - Target file name"),
                riskLevel = RiskLevel.R0_LOCAL_READONLY
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.FILE_WRITE_TEXT,
                toolId = "workspace.file_write_text",
                displayName = "Create or Edit File",
                description = "Writes text content to a specified workspace file.",
                category = ToolCategory.WORKSPACE_FILES,
                parametersSchema = mapOf("filename" to "String - Target file name", "content" to "String - File content"),
                riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE
            )
        )

        // Research & Browser Agent Tools
        register(
            DynamicToolDefinition(
                actionType = ActionType.WEB_SEARCH,
                toolId = "research.web_search",
                displayName = "Web Research Search",
                description = "Queries search sources to gather web articles, summaries, and citations.",
                category = ToolCategory.WEB_RESEARCH,
                parametersSchema = mapOf("query" to "String - Search query string"),
                riskLevel = RiskLevel.R0_LOCAL_READONLY
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.FETCH_WEB_PAGE,
                toolId = "research.fetch_web_page",
                displayName = "Fetch Web Page Content",
                description = "Downloads and extracts main article text from a target web URL.",
                category = ToolCategory.WEB_RESEARCH,
                parametersSchema = mapOf("url" to "String - Target website URL"),
                riskLevel = RiskLevel.R0_LOCAL_READONLY
            )
        )

        // Coding Agent Tools
        register(
            DynamicToolDefinition(
                actionType = ActionType.CODE_INSPECT_FILE,
                toolId = "coding.inspect_code",
                displayName = "Inspect Code File",
                description = "Analyzes source code structure, imports, and syntax in a workspace file.",
                category = ToolCategory.CODING_AGENT,
                parametersSchema = mapOf("path" to "String - Code file path"),
                riskLevel = RiskLevel.R0_LOCAL_READONLY
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.CODE_EDIT_FILE,
                toolId = "coding.edit_code",
                displayName = "Edit Source Code",
                description = "Applies code modifications, patches, or updates to a code file.",
                category = ToolCategory.CODING_AGENT,
                parametersSchema = mapOf("path" to "String - Code file path", "patch" to "String - Modified code block"),
                riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE
            )
        )

        // Skills & Sub-Agents Tools
        register(
            DynamicToolDefinition(
                actionType = ActionType.EXECUTE_PROCEDURAL_SKILL,
                toolId = "skills.execute_skill",
                displayName = "Execute Procedural Skill",
                description = "Invokes a pre-configured procedural skill pipeline (e.g. Research Skill, WhatsApp Skill).",
                category = ToolCategory.SKILLS_SUBAGENTS,
                parametersSchema = mapOf("skillId" to "String - Target skill ID", "input" to "String - Skill parameters"),
                riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.DELEGATE_SUBAGENT_TASK,
                toolId = "subagent.delegate_task",
                displayName = "Delegate Task to Sub-Agent",
                description = "Spawns a specialist sub-agent (Research, Coding, Browser, Android) for multi-agent delegation.",
                category = ToolCategory.SKILLS_SUBAGENTS,
                parametersSchema = mapOf("agentType" to "String - Specialist type", "subGoal" to "String - Sub-task instruction"),
                riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE
            )
        )

        // Productivity Tools
        register(
            DynamicToolDefinition(
                actionType = ActionType.CALCULATE_LOCAL,
                toolId = "util.local_calculator",
                displayName = "Local Math Calculator",
                description = "Evaluates math expressions deterministically on-device without LLM delay.",
                category = ToolCategory.PRODUCTIVITY,
                parametersSchema = mapOf("expression" to "String - Math formula"),
                riskLevel = RiskLevel.R0_LOCAL_READONLY
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.SAVE_NOTE,
                toolId = "util.save_note",
                displayName = "Save Personal Note",
                description = "Stores a personal note into Room database notebook.",
                category = ToolCategory.PRODUCTIVITY,
                parametersSchema = mapOf("content" to "String - Note content"),
                riskLevel = RiskLevel.R0_LOCAL_READONLY
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.SAVE_MEMORY,
                toolId = "util.save_memory",
                displayName = "Save Long-Term Memory",
                description = "Stores user preference or fact into long-term memory store.",
                category = ToolCategory.PRODUCTIVITY,
                parametersSchema = mapOf("prompt" to "String - User preference text"),
                riskLevel = RiskLevel.R0_LOCAL_READONLY
            )
        )
    }

    fun register(definition: DynamicToolDefinition) = registerTool(definition)

    override fun registerTool(definition: DynamicToolDefinition) {
        catalog[definition.actionType] = definition
    }

    override fun unregisterTool(actionType: ActionType) {
        catalog.remove(actionType)
    }

    override fun isToolAvailable(actionType: ActionType): Boolean = catalog.containsKey(actionType)

    override fun getTool(actionType: ActionType): DynamicToolDefinition? = catalog[actionType]

    override fun getAllTools(): List<DynamicToolDefinition> = catalog.values.toList()

    override fun getToolsByCategory(category: ToolCategory): List<DynamicToolDefinition> =
        catalog.values.filter { it.category == category }

    override fun generateLlmToolPromptSummary(): String {
        return catalog.values.joinToString("\n") { tool ->
            "- ${tool.actionType.name} (${tool.displayName}): ${tool.description} Params: ${tool.parametersSchema.keys}"
        }
    }
}
