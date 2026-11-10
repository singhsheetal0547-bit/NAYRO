package com.example.core.subagent

import com.example.core.agent.ActionExecutionResult
import com.example.core.agent.ActionType
import com.example.core.agent.AgentAction
import java.util.UUID

enum class SubAgentType(val displayName: String, val description: String) {
    RESEARCH("Research Sub-Agent", "Specializes in multi-source web research, article retrieval, and topic synthesis."),
    BROWSER("Browser Sub-Agent", "Specializes in web page navigation, URL fetching, and structured web content extraction."),
    CODING("Coding Sub-Agent", "Specializes in code inspection, syntax diagnosis, patch generation, and file updates."),
    ANDROID_AUTOMATION("Android Sub-Agent", "Specializes in app launch, UI node tree inspection, element clicking, and screen interaction."),
    SCREEN_ANALYSIS("Screen Analysis Sub-Agent", "Specializes in real-time visual grounding, node tree inspection, and text extraction."),
    FILE_WORKSPACE("Workspace Sub-Agent", "Specializes in workspace file browsing, document reading, and report generation."),
    DOCUMENT("Document Sub-Agent", "Specializes in document parsing, text extraction, and executive summaries."),
    PRODUCTIVITY("Productivity Sub-Agent", "Specializes in notes, memory updates, math calculation, and reminders."),
    PLANNING("Planning Sub-Agent", "Specializes in complex multi-step graph decomposition and dynamic replanning."),
    VERIFICATION("Verification Sub-Agent", "Specializes in pre/post screen state change analysis and evidence validation.")
}

enum class SubAgentStatus {
    IDLE,
    DELEGATED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class SubAgentTask(
    val subTaskId: String = UUID.randomUUID().toString(),
    val agentType: SubAgentType,
    val subGoal: String,
    var status: SubAgentStatus = SubAgentStatus.IDLE,
    var assignedActions: List<ActionType> = emptyList(),
    var outputResult: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

class SubAgentManager {

    private val activeSubTasks = mutableListOf<SubAgentTask>()

    fun delegateTask(agentType: SubAgentType, subGoal: String): SubAgentTask {
        val actions = when (agentType) {
            SubAgentType.RESEARCH -> listOf(ActionType.WEB_SEARCH, ActionType.FETCH_WEB_PAGE, ActionType.FILE_WRITE_TEXT)
            SubAgentType.BROWSER -> listOf(ActionType.FETCH_WEB_PAGE, ActionType.SUMMARIZE_WEB_CONTENT)
            SubAgentType.CODING -> listOf(ActionType.CODE_INSPECT_FILE, ActionType.CODE_EDIT_FILE)
            SubAgentType.ANDROID_AUTOMATION -> listOf(ActionType.LAUNCH_APP, ActionType.CLICK_ELEMENT, ActionType.SET_TEXT)
            SubAgentType.SCREEN_ANALYSIS -> listOf(ActionType.READ_SCREEN, ActionType.INSPECT_ACCESSIBILITY_TREE)
            SubAgentType.FILE_WORKSPACE -> listOf(ActionType.FILE_BROWSE, ActionType.FILE_READ_TEXT, ActionType.FILE_WRITE_TEXT)
            SubAgentType.DOCUMENT -> listOf(ActionType.FILE_READ_TEXT, ActionType.SAVE_NOTE)
            SubAgentType.PRODUCTIVITY -> listOf(ActionType.SAVE_NOTE, ActionType.SAVE_MEMORY, ActionType.CALCULATE_LOCAL)
            SubAgentType.PLANNING -> listOf(ActionType.QUERY_LLM)
            SubAgentType.VERIFICATION -> listOf(ActionType.READ_SCREEN)
        }

        val subTask = SubAgentTask(
            agentType = agentType,
            subGoal = subGoal,
            status = SubAgentStatus.DELEGATED,
            assignedActions = actions
        )
        activeSubTasks.add(subTask)
        return subTask
    }

    fun completeSubTask(subTaskId: String, output: String) {
        val index = activeSubTasks.indexOfFirst { it.subTaskId == subTaskId }
        if (index != -1) {
            val item = activeSubTasks[index]
            activeSubTasks[index] = item.copy(status = SubAgentStatus.COMPLETED, outputResult = output)
        }
    }

    fun failSubTask(subTaskId: String, errorMsg: String) {
        val index = activeSubTasks.indexOfFirst { it.subTaskId == subTaskId }
        if (index != -1) {
            val item = activeSubTasks[index]
            activeSubTasks[index] = item.copy(status = SubAgentStatus.FAILED, outputResult = errorMsg)
        }
    }

    fun getActiveSubTasks(): List<SubAgentTask> = activeSubTasks.toList()

    fun clearSubTasks() {
        activeSubTasks.clear()
    }
}
