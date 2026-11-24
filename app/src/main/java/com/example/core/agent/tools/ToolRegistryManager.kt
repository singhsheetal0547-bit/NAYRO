package com.example.core.agent.tools

import android.Manifest
import com.example.core.agent.ActionType
import com.example.core.agent.AgentAction
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
    val requiredParams: Set<String> = emptySet(),
    val riskLevel: RiskLevel,
    val requiredCapability: CapabilityId? = null,
    val requiredPermission: String? = null
)

/**
 * Runtime-extensible plugin tool definition allowing new tools to be registered and invoked
 * dynamically without modifying the fixed [ActionType] enum or rebuilding the architecture.
 */
data class DynamicToolPlugin(
    val pluginId: String,
    val displayName: String,
    val description: String,
    val category: ToolCategory = ToolCategory.PRODUCTIVITY,
    val parametersSchema: Map<String, String> = emptyMap(),
    val requiredParams: Set<String> = emptySet(),
    val riskLevel: RiskLevel = RiskLevel.R0_LOCAL_READONLY,
    val handler: suspend (Map<String, String>) -> com.example.core.agent.ActionExecutionResult
)

object ToolRegistryManager : ToolRegistry, ToolCatalog {

    private val catalog = mutableMapOf<ActionType, DynamicToolDefinition>()
    private val runtimePlugins = java.util.concurrent.ConcurrentHashMap<String, DynamicToolPlugin>()

    init {
        registerBuiltInTools()
    }

    fun resetToDefaults() {
        catalog.clear()
        runtimePlugins.clear()
        registerBuiltInTools()
    }

    fun registerPlugin(plugin: DynamicToolPlugin) {
        val cleanId = plugin.pluginId.trim().lowercase()
        require(cleanId.isNotEmpty()) { "Plugin ID cannot be empty." }
        runtimePlugins[cleanId] = plugin.copy(pluginId = cleanId)
    }

    fun unregisterPlugin(pluginId: String): Boolean =
        runtimePlugins.remove(pluginId.trim().lowercase()) != null

    fun getPlugin(pluginId: String): DynamicToolPlugin? =
        runtimePlugins[pluginId.trim().lowercase()]

    fun getAllPlugins(): List<DynamicToolPlugin> =
        runtimePlugins.values.sortedBy { it.pluginId }

    suspend fun executePlugin(
        pluginId: String,
        parameters: Map<String, String>
    ): com.example.core.agent.ActionExecutionResult {
        val plugin = getPlugin(pluginId)
            ?: return com.example.core.agent.ActionExecutionResult(
                isSuccess = false,
                outputMessage = "Dynamic plugin '$pluginId' is not registered.",
                status = com.example.core.agent.VerificationStatus.NOT_SUPPORTED
            )
        for (req in plugin.requiredParams) {
            if (parameters[req].isNullOrBlank()) {
                return com.example.core.agent.ActionExecutionResult(
                    isSuccess = false,
                    outputMessage = "Missing required parameter '$req' for plugin '${plugin.pluginId}'.",
                    status = com.example.core.agent.VerificationStatus.FAILED
                )
            }
        }
        return try {
            plugin.handler(parameters)
        } catch (e: Exception) {
            com.example.core.agent.ActionExecutionResult(
                isSuccess = false,
                outputMessage = "Plugin '${plugin.pluginId}' execution error: ${e.message ?: e::class.simpleName}",
                status = com.example.core.agent.VerificationStatus.FAILED
            )
        }
    }

    private fun registerBuiltInTools() {
        // 1. Device & System Tools
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
                actionType = ActionType.OPEN_SETTINGS_SECTION,
                toolId = "android.open_settings_section",
                displayName = "Open Android Settings Section",
                description = "Navigates directly to an Android Settings page (wifi, bluetooth, display, sound, battery, accessibility).",
                category = ToolCategory.DEVICE_SYSTEM,
                parametersSchema = mapOf("section" to "String - Settings section name"),
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
        register(
            DynamicToolDefinition(
                actionType = ActionType.GET_STORAGE_INFO,
                toolId = "android.get_storage_info",
                displayName = "Get Storage Info",
                description = "Returns internal storage total, available, and free space.",
                category = ToolCategory.DEVICE_SYSTEM,
                parametersSchema = emptyMap(),
                riskLevel = RiskLevel.R0_LOCAL_READONLY
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.GET_DEVICE_TIME,
                toolId = "android.get_device_time",
                displayName = "Get Local Device Time",
                description = "Returns current system date and time on device.",
                category = ToolCategory.DEVICE_SYSTEM,
                parametersSchema = emptyMap(),
                riskLevel = RiskLevel.R0_LOCAL_READONLY
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.SEARCH_INSTALLED_APPS,
                toolId = "android.search_installed_apps",
                displayName = "Search Installed Apps",
                description = "Searches installed launchable applications matching a query string.",
                category = ToolCategory.DEVICE_SYSTEM,
                parametersSchema = mapOf("query" to "String - Search filter for app label or package"),
                riskLevel = RiskLevel.R0_LOCAL_READONLY
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.TOGGLE_FLASHLIGHT,
                toolId = "android.toggle_flashlight",
                displayName = "Toggle Flashlight / Torch",
                description = "Turns the device camera flashlight (torch) ON or OFF using Android CameraManager.",
                category = ToolCategory.DEVICE_SYSTEM,
                parametersSchema = mapOf("enabled" to "Boolean ('true' or 'false') - Whether to turn torch on or off"),
                riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.CONTROL_VOLUME,
                toolId = "android.control_volume",
                displayName = "Control Device Volume",
                description = "Adjusts Android media, ring, alarm, or notification volume (UP, DOWN, MUTE, UNMUTE, SET percentage, or STATUS) via AudioManager and verifies resulting level.",
                category = ToolCategory.DEVICE_SYSTEM,
                parametersSchema = mapOf(
                    "operation" to "String - UP, DOWN, MUTE, UNMUTE, SET, or STATUS",
                    "stream" to "String - media (default), ring, alarm, notification, or call",
                    "levelPercent" to "Int - Optional percentage 0..100 when operation=SET"
                ),
                riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE
            )
        )

        // 2. Screen & Interaction Tools
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
                actionType = ActionType.INSPECT_ACCESSIBILITY_TREE,
                toolId = "screen.inspect_accessibility_tree",
                displayName = "Inspect Accessibility Node Tree",
                description = "Returns structured hierarchy of interactive and visible nodes on the active screen.",
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
                actionType = ActionType.LONG_PRESS_ELEMENT,
                toolId = "screen.long_press_element",
                displayName = "Long Press UI Element",
                description = "Performs an accessibility long-click on a UI node matching target text or view ID.",
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
        register(
            DynamicToolDefinition(
                actionType = ActionType.SCROLL_FORWARD,
                toolId = "screen.scroll_forward",
                displayName = "Scroll Forward",
                description = "Scrolls the active scrollable container forward.",
                category = ToolCategory.SCREEN_CONTROL,
                parametersSchema = emptyMap(),
                riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE,
                requiredCapability = CapabilityId.ACCESSIBILITY
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.SCROLL_BACKWARD,
                toolId = "screen.scroll_backward",
                displayName = "Scroll Backward",
                description = "Scrolls the active scrollable container backward.",
                category = ToolCategory.SCREEN_CONTROL,
                parametersSchema = emptyMap(),
                riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE,
                requiredCapability = CapabilityId.ACCESSIBILITY
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.NAVIGATE_BACK,
                toolId = "screen.navigate_back",
                displayName = "Navigate Back",
                description = "Performs global Android Back navigation via AccessibilityService.",
                category = ToolCategory.SCREEN_CONTROL,
                parametersSchema = emptyMap(),
                riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE,
                requiredCapability = CapabilityId.ACCESSIBILITY
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.TAKE_SCREENSHOT,
                toolId = "screen.take_screenshot",
                displayName = "Take Screen Capture",
                description = "Captures a verified screenshot via AccessibilityService on Android 11+ (API 30+).",
                category = ToolCategory.SCREEN_CONTROL,
                parametersSchema = emptyMap(),
                riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE,
                requiredCapability = CapabilityId.ACCESSIBILITY
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.INTERACT_COORDINATES,
                toolId = "screen.interact_coordinates",
                displayName = "Tap Screen Coordinates",
                description = "Dispatches a verified tap gesture at screen coordinates (x, y) via AccessibilityService.",
                category = ToolCategory.SCREEN_CONTROL,
                parametersSchema = mapOf("x" to "Float - X pixel coordinate", "y" to "Float - Y pixel coordinate"),
                requiredParams = setOf("x", "y"),
                riskLevel = RiskLevel.R2_APPLICATION_STATE,
                requiredCapability = CapabilityId.ACCESSIBILITY
            )
        )

        // 3. Communication Tools
        register(
            DynamicToolDefinition(
                actionType = ActionType.MESSAGE_DRAFT_SEND,
                toolId = "comm.send_whatsapp_message",
                displayName = "Send WhatsApp Message",
                description = "Resolves contact, opens WhatsApp chat, requires user confirmation, and verifies send state.",
                category = ToolCategory.COMMUNICATION,
                parametersSchema = mapOf("recipient" to "String - Target contact name or phone", "message" to "String - Message text"),
                riskLevel = RiskLevel.R3_EXTERNAL_COMMUNICATION
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.CHECK_INCOMING_CALL,
                toolId = "comm.check_incoming_call",
                displayName = "Check Incoming Call Info",
                description = "Queries active phone call state and resolves caller contact display name when permitted.",
                category = ToolCategory.COMMUNICATION,
                parametersSchema = emptyMap(),
                riskLevel = RiskLevel.R0_LOCAL_READONLY,
                requiredPermission = Manifest.permission.READ_PHONE_STATE
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.RESOLVE_CONTACT,
                toolId = "comm.resolve_contact",
                displayName = "Resolve Contact",
                description = "Queries Android ContactsContract to resolve a contact name to a verified phone number.",
                category = ToolCategory.COMMUNICATION,
                parametersSchema = mapOf("query" to "String - Contact display name"),
                riskLevel = RiskLevel.R0_LOCAL_READONLY,
                requiredPermission = Manifest.permission.READ_CONTACTS
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.OPEN_PHONE_DIALER,
                toolId = "comm.open_phone_dialer",
                displayName = "Open Phone Dialer",
                description = "Opens the Android phone dialer (ACTION_DIAL) with a resolved contact or phone number pre-filled without placing an active call.",
                category = ToolCategory.COMMUNICATION,
                parametersSchema = mapOf("target" to "String - Phone number or contact name"),
                riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.MAKE_PHONE_CALL,
                toolId = "comm.make_phone_call",
                displayName = "Place Outgoing Phone Call",
                description = "Resolves contact, validates phone number, checks CALL_PHONE permission, obtains user confirmation, initiates actual outgoing phone call (ACTION_CALL / TelecomManager), and observes call state.",
                category = ToolCategory.COMMUNICATION,
                parametersSchema = mapOf("target" to "String - Phone number or contact name"),
                riskLevel = RiskLevel.R3_EXTERNAL_COMMUNICATION,
                requiredPermission = Manifest.permission.CALL_PHONE
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.READ_NOTIFICATIONS,
                toolId = "comm.read_notifications",
                displayName = "Read Active Notifications",
                description = "Reads active status bar notifications when Notification Listener access is granted.",
                category = ToolCategory.COMMUNICATION,
                parametersSchema = emptyMap(),
                riskLevel = RiskLevel.R0_LOCAL_READONLY
            )
        )

        // 4. Workspace & File Tools
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
                parametersSchema = mapOf("filename" to "String - Relative file name in workspace"),
                riskLevel = RiskLevel.R0_LOCAL_READONLY
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.FILE_WRITE_TEXT,
                toolId = "workspace.file_write_text",
                displayName = "Create or Edit File",
                description = "Writes text content to a specified workspace file with path containment checks.",
                category = ToolCategory.WORKSPACE_FILES,
                parametersSchema = mapOf("filename" to "String - Relative file name", "content" to "String - File content"),
                requiredParams = setOf("content"),
                riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.FILE_SEARCH,
                toolId = "workspace.file_search",
                displayName = "Search Workspace Files",
                description = "Searches filenames and text contents within the NAYRO workspace.",
                category = ToolCategory.WORKSPACE_FILES,
                parametersSchema = mapOf("query" to "String - Search term"),
                riskLevel = RiskLevel.R0_LOCAL_READONLY
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.PDF_EXTRACT_TEXT,
                toolId = "workspace.pdf_extract_text",
                displayName = "Extract Document Text",
                description = "Extracts readable text streams from a document in the workspace.",
                category = ToolCategory.WORKSPACE_FILES,
                parametersSchema = mapOf("filename" to "String - Document file name in workspace"),
                riskLevel = RiskLevel.R0_LOCAL_READONLY
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.DOC_SUMMARIZE,
                toolId = "workspace.doc_summarize",
                displayName = "Summarize Workspace Document",
                description = "Reads a workspace file and generates an executive summary.",
                category = ToolCategory.WORKSPACE_FILES,
                parametersSchema = mapOf("filename" to "String - Target workspace file name"),
                riskLevel = RiskLevel.R0_LOCAL_READONLY
            )
        )

        // 5. Research & Browser Agent Tools
        register(
            DynamicToolDefinition(
                actionType = ActionType.WEB_SEARCH,
                toolId = "research.web_search",
                displayName = "Web Research Search",
                description = "Performs a real HTTPS search query with SSRF protection and extracts results.",
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
                description = "Downloads and extracts text from an HTTPS web URL with SSRF protection.",
                category = ToolCategory.WEB_RESEARCH,
                parametersSchema = mapOf("url" to "String - Target HTTPS website URL"),
                riskLevel = RiskLevel.R0_LOCAL_READONLY
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.SUMMARIZE_WEB_CONTENT,
                toolId = "research.summarize_web_content",
                displayName = "Summarize Web Content",
                description = "Synthesizes fetched web content or URL into a concise summary.",
                category = ToolCategory.WEB_RESEARCH,
                parametersSchema = mapOf("content" to "String - Web text or URL to summarize"),
                riskLevel = RiskLevel.R0_LOCAL_READONLY
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.BROWSER_OPEN_URL,
                toolId = "browser.open_url",
                displayName = "Open Website in Chrome",
                description = "Opens an SSRF-validated website URL in Google Chrome or the default Android browser.",
                category = ToolCategory.WEB_RESEARCH,
                parametersSchema = mapOf("url" to "String - Website URL or domain"),
                riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.BROWSER_COMPARE_SOURCES,
                toolId = "browser.compare_sources",
                displayName = "Compare Multiple Web Sources",
                description = "Searches and extracts multiple web sources and compares key facts with citations.",
                category = ToolCategory.WEB_RESEARCH,
                parametersSchema = mapOf("topic" to "String - Topic to research and compare", "urls" to "Optional comma-separated URLs"),
                riskLevel = RiskLevel.R0_LOCAL_READONLY
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.BROWSER_FILL_FORM,
                toolId = "browser.fill_form",
                displayName = "Fill Browser Form Field",
                description = "Uses authorized Accessibility service to enter text into a browser web form field.",
                category = ToolCategory.WEB_RESEARCH,
                parametersSchema = mapOf("field" to "String - Form field label", "value" to "String - Text to type", "submitButton" to "Optional submit button label"),
                riskLevel = RiskLevel.R2_APPLICATION_STATE,
                requiredCapability = CapabilityId.ACCESSIBILITY
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.BROWSER_DOWNLOAD_FILE,
                toolId = "browser.download_file",
                displayName = "Download Permitted Web File",
                description = "Enqueues an SSRF-validated HTTPS file download via Android DownloadManager into Downloads.",
                category = ToolCategory.WEB_RESEARCH,
                parametersSchema = mapOf("url" to "String - HTTPS file URL", "fileName" to "Optional output file name"),
                riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE
            )
        )

        // 6. Coding Agent Tools
        register(
            DynamicToolDefinition(
                actionType = ActionType.CODE_INSPECT_FILE,
                toolId = "coding.inspect_code",
                displayName = "Inspect Code File",
                description = "Analyzes source code structure, imports, and brace balance in a workspace file.",
                category = ToolCategory.CODING_AGENT,
                parametersSchema = mapOf("path" to "String - Relative workspace code file path"),
                riskLevel = RiskLevel.R0_LOCAL_READONLY
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.CODE_EDIT_FILE,
                toolId = "coding.edit_code",
                displayName = "Edit Source Code",
                description = "Applies code modifications or patches to a workspace code file and verifies changes.",
                category = ToolCategory.CODING_AGENT,
                parametersSchema = mapOf("path" to "String - Relative code file path", "patch" to "String - Updated code content"),
                requiredParams = setOf("patch"),
                riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.CODE_DIAGNOSE_ERROR,
                toolId = "coding.diagnose_error",
                displayName = "Diagnose Code Errors",
                description = "Inspects a workspace source file for structural syntax errors, unbalanced braces, and issues.",
                category = ToolCategory.CODING_AGENT,
                parametersSchema = mapOf("path" to "String - Relative code file path", "error" to "String - Optional compiler error text"),
                riskLevel = RiskLevel.R0_LOCAL_READONLY
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.CODE_GENERATE_DOCS,
                toolId = "coding.generate_docs",
                displayName = "Generate Code Documentation",
                description = "Generates Markdown documentation from declarations in a workspace source file.",
                category = ToolCategory.CODING_AGENT,
                parametersSchema = mapOf("path" to "String - Relative code file path"),
                riskLevel = RiskLevel.R0_LOCAL_READONLY
            )
        )

        // 7. Skills & Sub-Agents Tools
        register(
            DynamicToolDefinition(
                actionType = ActionType.EXECUTE_PROCEDURAL_SKILL,
                toolId = "skills.execute_skill",
                displayName = "Execute Procedural Skill",
                description = "Invokes a registered procedural skill pipeline by skill ID.",
                category = ToolCategory.SKILLS_SUBAGENTS,
                parametersSchema = mapOf("skillId" to "String - Target skill ID", "input" to "String - Skill parameters"),
                riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.REGISTER_CUSTOM_SKILL,
                toolId = "skills.register_custom_skill",
                displayName = "Register Custom Skill",
                description = "Registers a new custom procedural skill definition into SkillManager.",
                category = ToolCategory.SKILLS_SUBAGENTS,
                parametersSchema = mapOf("skillId" to "String - Unique skill ID", "name" to "String - Skill name", "description" to "String - Skill description"),
                riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.LIST_SKILLS,
                toolId = "skills.list_skills",
                displayName = "List Active Skills",
                description = "Lists all registered and enabled procedural skills.",
                category = ToolCategory.SKILLS_SUBAGENTS,
                parametersSchema = emptyMap(),
                riskLevel = RiskLevel.R0_LOCAL_READONLY
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.DELEGATE_SUBAGENT_TASK,
                toolId = "subagent.delegate_task",
                displayName = "Delegate Task to Sub-Agent",
                description = "Spawns a bounded specialist sub-agent (Research, Coding, Browser, Android) and executes its sub-goal.",
                category = ToolCategory.SKILLS_SUBAGENTS,
                parametersSchema = mapOf("agentType" to "String - Specialist type", "subGoal" to "String - Sub-task instruction"),
                requiredParams = setOf("subGoal"),
                riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.CHECK_SUBAGENT_STATUS,
                toolId = "subagent.check_status",
                displayName = "Check Sub-Agent Status",
                description = "Returns the current status and results of all delegated sub-agent tasks.",
                category = ToolCategory.SKILLS_SUBAGENTS,
                parametersSchema = emptyMap(),
                riskLevel = RiskLevel.R0_LOCAL_READONLY
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.AGGREGATE_SUBAGENT_RESULTS,
                toolId = "subagent.aggregate_results",
                displayName = "Aggregate Sub-Agent Results",
                description = "Aggregates verified outputs from completed sub-agent tasks.",
                category = ToolCategory.SKILLS_SUBAGENTS,
                parametersSchema = emptyMap(),
                riskLevel = RiskLevel.R0_LOCAL_READONLY
            )
        )

        // 8. Productivity Tools
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
        register(
            DynamicToolDefinition(
                actionType = ActionType.CREATE_REMINDER,
                toolId = "util.create_reminder",
                displayName = "Create Reminder",
                description = "Persists a reminder item to local storage and schedules a notification.",
                category = ToolCategory.PRODUCTIVITY,
                parametersSchema = mapOf("title" to "String - Reminder title", "timeMs" to "Long - Optional trigger timestamp"),
                riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.SCHEDULE_ALARM,
                toolId = "util.schedule_alarm",
                displayName = "Schedule Alarm",
                description = "Sets an Android system alarm via AlarmClock intent or AlarmManager.",
                category = ToolCategory.PRODUCTIVITY,
                parametersSchema = mapOf("hour" to "Int - Hour (0-23)", "minutes" to "Int - Minutes (0-59)", "message" to "String - Alarm label"),
                riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.CREATE_CALENDAR_EVENT,
                toolId = "util.create_calendar_event",
                displayName = "Create Calendar Event",
                description = "Inserts a calendar event via CalendarContract (if permitted) or launches Calendar intent.",
                category = ToolCategory.PRODUCTIVITY,
                parametersSchema = mapOf("title" to "String - Event title", "startTimeMs" to "Long - Start time in millis", "endTimeMs" to "Long - End time in millis"),
                riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE,
                requiredPermission = Manifest.permission.WRITE_CALENDAR
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.ASK_USER_CONFIRMATION,
                toolId = "util.ask_user_confirmation",
                displayName = "Ask User Confirmation",
                description = "Pauses task execution and prompts the user to confirm a high-risk operation.",
                category = ToolCategory.PRODUCTIVITY,
                parametersSchema = mapOf("recipient" to "String - Target entity", "draft" to "String - Proposed content"),
                riskLevel = RiskLevel.R3_EXTERNAL_COMMUNICATION
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.QUERY_LLM,
                toolId = "ai.query_llm",
                displayName = "Query AI Reasoning Provider",
                description = "Routes a natural language prompt to the active AI provider.",
                category = ToolCategory.PRODUCTIVITY,
                parametersSchema = mapOf("prompt" to "String - User prompt"),
                riskLevel = RiskLevel.R0_LOCAL_READONLY
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.GET_CURRENT_LOCATION,
                toolId = "android.get_current_location",
                displayName = "Get Approximate Device Location",
                description = "Retrieves the last known approximate device location when location permission is granted.",
                category = ToolCategory.DEVICE_SYSTEM,
                parametersSchema = emptyMap(),
                riskLevel = RiskLevel.R0_LOCAL_READONLY,
                requiredPermission = Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.SWIPE_SCREEN,
                toolId = "screen.swipe_screen",
                displayName = "Swipe Screen Gesture",
                description = "Dispatches a directional (up, down, left, right) or coordinate swipe gesture via AccessibilityService.",
                category = ToolCategory.SCREEN_CONTROL,
                parametersSchema = mapOf(
                    "direction" to "String - up, down, left, right (or startX/startY/endX/endY)",
                    "durationMs" to "Long - Swipe duration in ms"
                ),
                riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE,
                requiredCapability = CapabilityId.ACCESSIBILITY
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.ANALYZE_IMAGE_VISION,
                toolId = "screen.analyze_image_vision",
                displayName = "Multimodal Screen & Image Analysis",
                description = "Analyzes screen content or workspace image/document using the active multimodal AI provider or OCR context.",
                category = ToolCategory.SCREEN_CONTROL,
                parametersSchema = mapOf("prompt" to "String - Question about the screen or image", "filename" to "String - Optional workspace file"),
                riskLevel = RiskLevel.R0_LOCAL_READONLY
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.SEND_SMS,
                toolId = "comm.send_sms",
                displayName = "Send SMS Message",
                description = "Resolves recipient contact, validates phone number, checks SEND_SMS permission, obtains user confirmation, transmits SMS via SmsManager with PendingIntent sent verification, or offers user-mediated SMS composer fallback.",
                category = ToolCategory.COMMUNICATION,
                parametersSchema = mapOf("recipient" to "String - Contact name or phone number", "message" to "String - SMS body text"),
                riskLevel = RiskLevel.R3_EXTERNAL_COMMUNICATION,
                requiredPermission = Manifest.permission.SEND_SMS
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.SUMMARIZE_NOTIFICATIONS,
                toolId = "comm.summarize_notifications",
                displayName = "Summarize Active Notifications",
                description = "Summarizes recent status bar notifications grouped by priority and category.",
                category = ToolCategory.COMMUNICATION,
                parametersSchema = emptyMap(),
                riskLevel = RiskLevel.R0_LOCAL_READONLY,
                requiredCapability = CapabilityId.NOTIFICATION_ACCESS
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.CLASSIFY_NOTIFICATIONS,
                toolId = "comm.classify_notifications",
                displayName = "Classify Notifications by Priority",
                description = "Classifies active notifications into Message, Reminder, System Alert, Social/Promotion, or General.",
                category = ToolCategory.COMMUNICATION,
                parametersSchema = mapOf("category" to "String - Optional category filter"),
                riskLevel = RiskLevel.R0_LOCAL_READONLY,
                requiredCapability = CapabilityId.NOTIFICATION_ACCESS
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.CREATE_TASK_FROM_NOTIFICATION,
                toolId = "comm.create_task_from_notification",
                displayName = "Create Reminder/Note from Notification",
                description = "Extracts an actionable reminder or note from a recent notification.",
                category = ToolCategory.COMMUNICATION,
                parametersSchema = mapOf("notificationKey" to "String - Optional notification key or sender filter"),
                riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE,
                requiredCapability = CapabilityId.NOTIFICATION_ACCESS
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.COMPARE_DOCUMENTS,
                toolId = "workspace.compare_documents",
                displayName = "Compare Two Workspace Documents",
                description = "Compares two files inside the NAYRO workspace and highlights line/content differences.",
                category = ToolCategory.WORKSPACE_FILES,
                parametersSchema = mapOf("firstFile" to "String - First workspace filename", "secondFile" to "String - Second workspace filename"),
                requiredParams = setOf("firstFile", "secondFile"),
                riskLevel = RiskLevel.R0_LOCAL_READONLY
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.EXECUTE_CROSS_APP_WORKFLOW,
                toolId = "skills.execute_cross_app_workflow",
                displayName = "Execute Cross-App Multi-Step Workflow",
                description = "Executes a structured multi-step workflow across tools and apps with shared context transfer.",
                category = ToolCategory.SKILLS_SUBAGENTS,
                parametersSchema = mapOf("workflowId" to "String - Workflow template ID", "recipient" to "String - Optional recipient"),
                riskLevel = RiskLevel.R2_APPLICATION_STATE
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.SEARCH_NOTES,
                toolId = "util.search_notes",
                displayName = "Search Personal Notes",
                description = "Searches saved notes and reminders in the local Room database matching a query.",
                category = ToolCategory.PRODUCTIVITY,
                parametersSchema = mapOf("query" to "String - Search keyword"),
                riskLevel = RiskLevel.R0_LOCAL_READONLY
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.DELETE_NOTE,
                toolId = "util.delete_note",
                displayName = "Delete Personal Note",
                description = "Deletes a saved note by ID or matching title after user confirmation.",
                category = ToolCategory.PRODUCTIVITY,
                parametersSchema = mapOf("noteId" to "Long - Note ID", "title" to "String - Optional note title to match"),
                riskLevel = RiskLevel.R3_EXTERNAL_COMMUNICATION
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.READ_CALENDAR_EVENTS,
                toolId = "util.read_calendar_events",
                displayName = "Read / Search Calendar Events",
                description = "Queries upcoming or date-specific events from the Android Calendar provider, resolves relative dates (today, tomorrow), and detects overlapping schedule conflicts.",
                category = ToolCategory.PRODUCTIVITY,
                parametersSchema = mapOf(
                    "date" to "String - Optional relative or ISO date (today, tomorrow, YYYY-MM-DD)",
                    "query" to "String - Optional search keyword",
                    "limit" to "Int - Max events to return"
                ),
                riskLevel = RiskLevel.R0_LOCAL_READONLY,
                requiredPermission = Manifest.permission.READ_CALENDAR
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.UPDATE_CALENDAR_EVENT,
                toolId = "util.update_calendar_event",
                displayName = "Update Calendar Event",
                description = "Updates an existing calendar event's title, time, or location and verifies the change by re-reading the event.",
                category = ToolCategory.PRODUCTIVITY,
                parametersSchema = mapOf(
                    "eventId" to "Long - Target calendar event ID",
                    "title" to "String - Updated event title",
                    "startTimeMs" to "Long - Optional new start time",
                    "endTimeMs" to "Long - Optional new end time",
                    "location" to "String - Optional new location"
                ),
                requiredParams = setOf("eventId"),
                riskLevel = RiskLevel.R2_APPLICATION_STATE,
                requiredPermission = Manifest.permission.WRITE_CALENDAR
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.DELETE_CALENDAR_EVENT,
                toolId = "util.delete_calendar_event",
                displayName = "Delete Calendar Event",
                description = "Deletes a calendar event by ID after user confirmation and verifies removal from the Calendar provider.",
                category = ToolCategory.PRODUCTIVITY,
                parametersSchema = mapOf("eventId" to "Long - Target calendar event ID"),
                requiredParams = setOf("eventId"),
                riskLevel = RiskLevel.R3_EXTERNAL_COMMUNICATION,
                requiredPermission = Manifest.permission.WRITE_CALENDAR
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.WHATSAPP_READ_CONVERSATION,
                toolId = "comm.whatsapp_read_conversation",
                displayName = "Read / Search WhatsApp Conversation",
                description = "Opens WhatsApp, locates a contact's chat or searches for a topic, and summarizes visible recent messages.",
                category = ToolCategory.COMMUNICATION,
                parametersSchema = mapOf("query" to "String - Contact name or search topic"),
                riskLevel = RiskLevel.R0_LOCAL_READONLY,
                requiredCapability = CapabilityId.ACCESSIBILITY
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.READ_SMS,
                toolId = "comm.read_sms",
                displayName = "Read & Analyze SMS Messages",
                description = "Reads recent, unread, OTP, or bank SMS messages from Telephony.Sms or authorized Notification Listener buffer.",
                category = ToolCategory.COMMUNICATION,
                parametersSchema = mapOf(
                    "filter" to "String - latest, unread, otp, bank, or all",
                    "query" to "String - Optional sender name, phone number, or keyword"
                ),
                riskLevel = RiskLevel.R0_LOCAL_READONLY
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.MANAGE_CALL,
                toolId = "comm.manage_call",
                displayName = "Manage Incoming / Active Phone Call",
                description = "Answers, rejects, ends, or identifies an active or incoming phone call via TelecomManager or Accessibility In-Call UI.",
                category = ToolCategory.COMMUNICATION,
                parametersSchema = mapOf("operation" to "String - ANSWER, REJECT, END, or IDENTIFY"),
                riskLevel = RiskLevel.R2_APPLICATION_STATE
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.READ_CALL_HISTORY,
                toolId = "comm.read_call_history",
                displayName = "Read Call History & Missed Calls",
                description = "Queries recent incoming, outgoing, or missed calls from CallLog or authorized Notification Listener buffer.",
                category = ToolCategory.COMMUNICATION,
                parametersSchema = mapOf(
                    "filter" to "String - all, missed, incoming, or outgoing",
                    "query" to "String - Optional contact name or phone number"
                ),
                riskLevel = RiskLevel.R0_LOCAL_READONLY
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.SAVE_CONTACT,
                toolId = "comm.save_contact",
                displayName = "Save or Update Contact",
                description = "Creates or updates a contact in Android Contacts after user confirmation, optionally checking if the phone number is new.",
                category = ToolCategory.COMMUNICATION,
                parametersSchema = mapOf(
                    "name" to "String - Contact display name",
                    "phone" to "String - Phone number",
                    "condition" to "String - Optional IF_NEW_NUMBER condition"
                ),
                riskLevel = RiskLevel.R3_EXTERNAL_COMMUNICATION
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.CONTROL_MEDIA,
                toolId = "media.control_media",
                displayName = "Control Media & Music Playback",
                description = "Plays songs, artists, genres, or favourite music on YouTube/Spotify/preferred media app, or controls active playback (play, pause, resume, next, previous).",
                category = ToolCategory.DEVICE_SYSTEM,
                parametersSchema = mapOf(
                    "operation" to "String - PLAY_QUERY, PLAY_FAVOURITE_SONG, PLAY_BY_ARTIST, PLAY_BY_GENRE_OR_MOOD, PAUSE, RESUME, NEXT_TRACK, PREVIOUS_TRACK, STOP, or STATUS",
                    "query" to "String - Optional song title, artist, or genre query",
                    "artist" to "String - Optional artist name",
                    "genre" to "String - Optional genre or mood",
                    "app" to "String - Optional media app (YouTube, YouTube Music, Spotify)"
                ),
                riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.NAVIGATE_HOME,
                toolId = "screen.navigate_home",
                displayName = "Navigate Home",
                description = "Performs global Android Home navigation via AccessibilityService or HOME intent.",
                category = ToolCategory.SCREEN_CONTROL,
                parametersSchema = emptyMap(),
                riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.NAVIGATE_RECENTS,
                toolId = "screen.navigate_recents",
                displayName = "Open Recent Apps",
                description = "Opens the Android Recent Apps overview switcher via AccessibilityService.",
                category = ToolCategory.SCREEN_CONTROL,
                parametersSchema = emptyMap(),
                riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.DRAG_SCREEN,
                toolId = "screen.drag_gesture",
                displayName = "Drag Screen Gesture",
                description = "Performs a real press-and-drag gesture between two screen coordinates for sliders, maps, and game controls.",
                category = ToolCategory.SCREEN_CONTROL,
                parametersSchema = mapOf(
                    "startX" to "Float - Start X coordinate in pixels",
                    "startY" to "Float - Start Y coordinate in pixels",
                    "endX" to "Float - End X coordinate in pixels",
                    "endY" to "Float - End Y coordinate in pixels"
                ),
                riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.UNIVERSAL_SCREEN_OPERATE,
                toolId = "screen.universal_operate",
                displayName = "Universal AI Screen Operator",
                description = "Observes the live Android screen (Accessibility + MediaProjection/Screenshot + On-Device CV + Multimodal Vision AI), decides the next visual or semantic action, executes it, and verifies screen transition across apps, games, browsers, and system settings.",
                category = ToolCategory.SCREEN_CONTROL,
                parametersSchema = mapOf(
                    "goal" to "String - Natural-language on-screen goal or follow-up command",
                    "maxSteps" to "Int - Optional max observe-act-verify steps (default 6)"
                ),
                riskLevel = RiskLevel.R2_APPLICATION_STATE
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.MANAGE_SCREEN_RECORDING,
                toolId = "screen.manage_screen_recording",
                displayName = "Screen Recording (MediaProjection)",
                description = "Requests explicit per-session Android MediaProjection user consent to start or stop screen recording honestly without silent capture.",
                category = ToolCategory.SCREEN_CONTROL,
                parametersSchema = mapOf("operation" to "String - START, STOP, or STATUS"),
                riskLevel = RiskLevel.R2_APPLICATION_STATE
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.COMPOSE_EMAIL,
                toolId = "comm.compose_email",
                displayName = "Compose Email",
                description = "Opens an installed email app via mailto: intent with pre-filled recipient, subject, and body for user review and sending.",
                category = ToolCategory.COMMUNICATION,
                parametersSchema = mapOf(
                    "recipient" to "String - Recipient email address",
                    "subject" to "String - Email subject line",
                    "body" to "String - Email message body"
                ),
                riskLevel = RiskLevel.R2_APPLICATION_STATE
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.READ_EMAIL_NOTIFICATIONS,
                toolId = "comm.read_email_notifications",
                displayName = "Read Recent Email Previews",
                description = "Reads recent email alert previews from authorized Notification Listener access (Gmail, Outlook, Yahoo Mail).",
                category = ToolCategory.COMMUNICATION,
                parametersSchema = mapOf("limit" to "Int - Max email notifications to list"),
                riskLevel = RiskLevel.R0_LOCAL_READONLY
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.CHECK_COMMUNICATION_SPAM,
                toolId = "comm.check_communication_spam",
                displayName = "Spam & Phishing Detector",
                description = "Analyzes incoming phone numbers, recent SMS messages, or provided text for telemarketer patterns, OTP theft, and phishing links.",
                category = ToolCategory.COMMUNICATION,
                parametersSchema = mapOf(
                    "phoneNumber" to "String - Optional phone number to assess",
                    "sender" to "String - Optional message sender",
                    "body" to "String - Optional message content"
                ),
                riskLevel = RiskLevel.R0_LOCAL_READONLY
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.EXECUTE_SANDBOXED_CODE,
                toolId = "coding.execute_sandboxed_code",
                displayName = "Sandboxed Script Executor",
                description = "Runs code through the configured authenticated remote CPython/Node gateway when available; otherwise uses the restricted pure-Kotlin subset evaluator (not full CPython/Node, no pip/npm or OS shell).",
                category = ToolCategory.CODING_AGENT,
                parametersSchema = mapOf(
                    "code" to "String - Python or JavaScript code snippet to evaluate",
                    "language" to "String - 'python' (default) or 'javascript'"
                ),
                requiredParams = setOf("code"),
                riskLevel = RiskLevel.R0_LOCAL_READONLY
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.SCAFFOLD_DEV_PROJECT,
                toolId = "coding.scaffold_dev_project",
                displayName = "Scaffold Dev Project",
                description = "Creates multi-file Python, JavaScript/Node, React/Vite, or React Native project scaffolds inside NAYRO's confined workspace; building and package installation require a real development environment.",
                category = ToolCategory.CODING_AGENT,
                parametersSchema = mapOf(
                    "projectName" to "String - Project folder name inside workspace",
                    "template" to "String - python, javascript, react, or react-native"
                ),
                riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.ANALYZE_DATA_FILE,
                toolId = "workspace.analyze_data_file",
                displayName = "Analyze CSV/JSON Data",
                description = "Parses CSV/TSV/JSON workspace files or inline tabular data, computes column statistics (sum/avg/min/max), filters rows, and generates Markdown reports.",
                category = ToolCategory.WORKSPACE_FILES,
                parametersSchema = mapOf(
                    "filename" to "String - Optional workspace CSV/TSV/JSON file path",
                    "data" to "String - Optional inline CSV/JSON text",
                    "filter" to "String - Optional column=value row filter",
                    "saveReportAs" to "String - Optional workspace path to save Markdown report"
                ),
                riskLevel = RiskLevel.R0_LOCAL_READONLY
            )
        )
        register(
            DynamicToolDefinition(
                actionType = ActionType.EXECUTE_DYNAMIC_PLUGIN,
                toolId = "plugins.execute_dynamic_plugin",
                displayName = "Execute Registered Dynamic Plugin",
                description = "Invokes any dynamically registered runtime plugin tool by pluginId without rebuilding the agent architecture.",
                category = ToolCategory.SKILLS_SUBAGENTS,
                parametersSchema = mapOf(
                    "pluginId" to "String - Registered dynamic plugin identifier"
                ),
                requiredParams = setOf("pluginId"),
                riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE
            )
        )
    }

    /**
     * Validates an [AgentAction] against its registered tool schema before execution.
     */
    fun validateParameters(action: AgentAction): Pair<Boolean, String> {
        val definition = catalog[action.actionType]
            ?: return false to "Tool '${action.actionType.name}' is not registered in ToolRegistryManager."

        for (reqKey in definition.requiredParams) {
            val value = action.parameters[reqKey]
            if (value.isNullOrBlank()) {
                return false to "Missing required parameter '$reqKey' for tool '${definition.toolId}' (${action.actionType.name})."
            }
        }

        // Additional action-specific parameter checks
        when (action.actionType) {
            ActionType.LAUNCH_APP -> {
                val app = action.target ?: action.parameters["appName"]
                if (app.isNullOrBlank()) {
                    return false to "Missing target app name for LAUNCH_APP."
                }
            }
            ActionType.INTERACT_COORDINATES -> {
                val x = action.parameters["x"]?.toFloatOrNull()
                val y = action.parameters["y"]?.toFloatOrNull()
                if (x == null || y == null) {
                    return false to "Numeric coordinates 'x' and 'y' are required for INTERACT_COORDINATES."
                }
            }
            ActionType.OPEN_PHONE_DIALER,
            ActionType.MAKE_PHONE_CALL -> {
                val target = action.target ?: action.parameters["target"] ?: action.parameters["number"]
                if (target.isNullOrBlank()) {
                    return false to "Missing 'target' contact name or phone number for ${action.actionType.name}."
                }
            }
            ActionType.SEND_SMS -> {
                val recipient = action.target ?: action.parameters["recipient"]
                val message = action.parameters["message"] ?: action.parameters["draft"]
                if (recipient.isNullOrBlank() || message.isNullOrBlank()) {
                    return false to "Both 'recipient' and 'message' are required for SEND_SMS."
                }
            }
            ActionType.FETCH_WEB_PAGE -> {
                val url = action.target ?: action.parameters["url"]
                if (url.isNullOrBlank()) {
                    return false to "Missing 'url' parameter for FETCH_WEB_PAGE."
                }
            }
            ActionType.WEB_SEARCH -> {
                val query = action.target ?: action.parameters["query"]
                if (query.isNullOrBlank()) {
                    return false to "Missing 'query' parameter for WEB_SEARCH."
                }
            }
            else -> {}
        }

        return true to "Valid"
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

    fun getToolsByDomain(domain: com.example.core.agent.ExecutionDomain): List<DynamicToolDefinition> =
        catalog.values.filter { com.example.core.agent.ConversationalIntelligenceEngine.domainForAction(it.actionType) == domain }

    fun getDomainSummary(): Map<com.example.core.agent.ExecutionDomain, Int> =
        com.example.core.agent.ExecutionDomain.entries.associateWith { domain -> getToolsByDomain(domain).size }

    override fun generateLlmToolPromptSummary(): String {
        val baseTools = catalog.values.joinToString("\n") { tool ->
            "- ${tool.actionType.name} (${tool.displayName}): ${tool.description} Params: ${tool.parametersSchema.keys}"
        }
        val plugins = getAllPlugins()
        if (plugins.isEmpty()) return baseTools
        val pluginLines = plugins.joinToString("\n") { p ->
            "- EXECUTE_DYNAMIC_PLUGIN[pluginId=${p.pluginId}] (${p.displayName}): ${p.description} Params: ${p.parametersSchema.keys}"
        }
        return "$baseTools\nRegistered Runtime Plugins:\n$pluginLines"
    }
}

/**
 * Central CommandDispatcher (Section 3E & Section 7).
 * Enforces tool allowlist registration and schema parameter validation before invoking [ActionExecutor].
 */
object CommandDispatcher {
    suspend fun dispatch(
        action: AgentAction,
        executor: com.example.core.agent.ActionExecutor,
        isConfirmed: Boolean = false
    ): com.example.core.agent.ActionExecutionResult {
        if (!ToolRegistryManager.isToolAvailable(action.actionType)) {
            return com.example.core.agent.ActionExecutionResult(
                isSuccess = false,
                outputMessage = "CommandDispatcher blocked unregistered tool: ${action.actionType.name}",
                status = com.example.core.agent.VerificationStatus.NOT_SUPPORTED
            )
        }
        val (valid, validationMsg) = ToolRegistryManager.validateParameters(action)
        if (!valid) {
            return com.example.core.agent.ActionExecutionResult(
                isSuccess = false,
                outputMessage = "CommandDispatcher schema validation failed: $validationMsg",
                status = com.example.core.agent.VerificationStatus.FAILED
            )
        }
        return executor.execute(action, isConfirmed = isConfirmed)
    }
}

