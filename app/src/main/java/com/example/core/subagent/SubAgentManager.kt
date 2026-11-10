package com.example.core.subagent

import com.example.core.agent.ActionExecutionResult
import com.example.core.agent.ActionType
import com.example.core.agent.AgentAction
import kotlinx.coroutines.withTimeoutOrNull
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
    val depth: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Bounded Sub-Agent Delegation Manager (BUG 12 Remediation).
 * Enforces delegation depth limits, timeouts, and real tool execution rather than immediate fake completion.
 */
class SubAgentManager {

    companion object {
        const val MAX_DELEGATION_DEPTH = 2
        const val DEFAULT_SUBAGENT_TIMEOUT_MS = 15_000L
    }

    private val activeSubTasks = mutableListOf<SubAgentTask>()

    fun delegateTask(agentType: SubAgentType, subGoal: String, depth: Int = 0): SubAgentTask {
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

        val initialStatus = if (depth > MAX_DELEGATION_DEPTH || subGoal.isBlank()) {
            SubAgentStatus.FAILED
        } else {
            SubAgentStatus.DELEGATED
        }

        val initialOutput = when {
            depth > MAX_DELEGATION_DEPTH -> "Sub-agent delegation rejected: recursion depth $depth exceeds limit ($MAX_DELEGATION_DEPTH)."
            subGoal.isBlank() -> "Sub-agent delegation rejected: subGoal cannot be empty."
            else -> null
        }

        val subTask = SubAgentTask(
            agentType = agentType,
            subGoal = subGoal,
            status = initialStatus,
            assignedActions = actions,
            outputResult = initialOutput,
            depth = depth
        )
        synchronized(activeSubTasks) {
            activeSubTasks.add(subTask)
        }
        return subTask
    }

    /**
     * Executes a delegated sub-task through the real action runner with recursion bounds and timeout.
     */
    suspend fun executeSubTask(
        agentType: SubAgentType,
        subGoal: String,
        depth: Int = 0,
        timeoutMs: Long = DEFAULT_SUBAGENT_TIMEOUT_MS,
        actionRunner: suspend (AgentAction) -> ActionExecutionResult
    ): SubAgentTask {
        val task = delegateTask(agentType, subGoal, depth)
        if (task.status == SubAgentStatus.FAILED) {
            return task
        }

        updateStatus(task.subTaskId, SubAgentStatus.RUNNING, null)

        val concreteAction = buildConcreteSubAgentAction(task.subTaskId, agentType, subGoal, depth)
        val execResult = withTimeoutOrNull(timeoutMs) {
            actionRunner(concreteAction)
        }

        if (execResult == null) {
            failSubTask(task.subTaskId, "Sub-agent (${agentType.displayName}) timed out after ${timeoutMs}ms.")
        } else if (execResult.isSuccess) {
            completeSubTask(task.subTaskId, execResult.outputMessage)
        } else {
            failSubTask(task.subTaskId, execResult.outputMessage)
        }

        return getSubTask(task.subTaskId) ?: task
    }

    private fun buildConcreteSubAgentAction(
        subTaskId: String,
        agentType: SubAgentType,
        subGoal: String,
        depth: Int
    ): AgentAction {
        return when (agentType) {
            SubAgentType.RESEARCH -> AgentAction(
                taskId = subTaskId,
                actionType = ActionType.WEB_SEARCH,
                target = subGoal,
                parameters = mapOf("query" to subGoal, "depth" to (depth + 1).toString())
            )
            SubAgentType.BROWSER -> {
                val isUrl = subGoal.startsWith("http://") || subGoal.startsWith("https://")
                AgentAction(
                    taskId = subTaskId,
                    actionType = if (isUrl) ActionType.FETCH_WEB_PAGE else ActionType.WEB_SEARCH,
                    target = subGoal,
                    parameters = if (isUrl) mapOf("url" to subGoal) else mapOf("query" to subGoal)
                )
            }
            SubAgentType.CODING -> AgentAction(
                taskId = subTaskId,
                actionType = ActionType.CODE_INSPECT_FILE,
                target = subGoal.ifBlank { "SampleAgentModule.kt" },
                parameters = mapOf("path" to subGoal.ifBlank { "SampleAgentModule.kt" })
            )
            SubAgentType.FILE_WORKSPACE -> AgentAction(
                taskId = subTaskId,
                actionType = ActionType.FILE_BROWSE,
                parameters = mapOf("path" to ".")
            )
            SubAgentType.DOCUMENT -> AgentAction(
                taskId = subTaskId,
                actionType = ActionType.FILE_READ_TEXT,
                target = subGoal.ifBlank { "NAYRO_Workspace_Guide.md" },
                parameters = mapOf("filename" to subGoal.ifBlank { "NAYRO_Workspace_Guide.md" })
            )
            SubAgentType.PRODUCTIVITY -> {
                val isMath = subGoal.any { it.isDigit() } && (subGoal.contains("+") || subGoal.contains("-") || subGoal.contains("*") || subGoal.contains("/"))
                if (isMath) {
                    AgentAction(
                        taskId = subTaskId,
                        actionType = ActionType.CALCULATE_LOCAL,
                        parameters = mapOf("expression" to subGoal)
                    )
                } else {
                    AgentAction(
                        taskId = subTaskId,
                        actionType = ActionType.SAVE_NOTE,
                        parameters = mapOf("content" to subGoal)
                    )
                }
            }
            SubAgentType.SCREEN_ANALYSIS, SubAgentType.VERIFICATION -> AgentAction(
                taskId = subTaskId,
                actionType = ActionType.READ_SCREEN
            )
            SubAgentType.ANDROID_AUTOMATION -> AgentAction(
                taskId = subTaskId,
                actionType = ActionType.LAUNCH_APP,
                target = subGoal
            )
            SubAgentType.PLANNING -> AgentAction(
                taskId = subTaskId,
                actionType = ActionType.QUERY_LLM,
                parameters = mapOf("prompt" to subGoal)
            )
        }
    }

    private fun updateStatus(subTaskId: String, status: SubAgentStatus, output: String?) {
        synchronized(activeSubTasks) {
            val index = activeSubTasks.indexOfFirst { it.subTaskId == subTaskId }
            if (index != -1) {
                val item = activeSubTasks[index]
                activeSubTasks[index] = item.copy(status = status, outputResult = output ?: item.outputResult)
            }
        }
    }

    fun completeSubTask(subTaskId: String, output: String) {
        updateStatus(subTaskId, SubAgentStatus.COMPLETED, output)
    }

    fun failSubTask(subTaskId: String, errorMsg: String) {
        updateStatus(subTaskId, SubAgentStatus.FAILED, errorMsg)
    }

    fun cancelSubTask(subTaskId: String) {
        updateStatus(subTaskId, SubAgentStatus.CANCELLED, "Cancelled by user or runtime.")
    }

    fun getSubTask(subTaskId: String): SubAgentTask? = synchronized(activeSubTasks) {
        activeSubTasks.find { it.subTaskId == subTaskId }
    }

    fun getActiveSubTasks(): List<SubAgentTask> = synchronized(activeSubTasks) {
        activeSubTasks.toList()
    }

    fun clearSubTasks() = synchronized(activeSubTasks) {
        activeSubTasks.clear()
    }
}
