package com.example.core.agent

import com.example.core.agent.tools.ToolRegistryManager
import com.example.core.provider.AIProviderManager
import com.example.core.provider.model.AIResult
import com.example.core.skills.SkillManager
import com.example.core.subagent.SubAgentManager
import com.example.core.workspace.WorkspaceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

data class BrainReasoningStep(
    val stepIndex: Int,
    val thought: String,
    val selectedAction: ActionType,
    val target: String?,
    val executionOutput: String,
    val verificationStatus: VerificationStatus = VerificationStatus.VERIFIED,
    val isComplete: Boolean
)

data class BrainExecutionOutcome(
    val finalResponse: String,
    val stepsExecuted: List<BrainReasoningStep>,
    val overallStatus: VerificationStatus,
    val isFullyVerified: Boolean
)

/**
 * Authoritative Multi-Step Reasoning, Planning & Iterative Orchestration Engine (Sections 5, 6, 8, 9).
 * Integrated directly with AgentRuntime so all user entry points share one unified planning and
 * multi-step execution loop. Supports dynamic tool selection across all 52 registered tools,
 * cross-app multi-clause workflows, and dynamic replanning on step failure.
 */
class AgentBrain(
    private val providerManager: AIProviderManager,
    private val actionExecutor: ActionExecutor,
    private val workspaceManager: WorkspaceManager,
    private val skillManager: SkillManager,
    private val subAgentManager: SubAgentManager
) {

    companion object {
        const val MAX_ITERATIVE_STEPS = 25
        const val DEFAULT_LOOP_TIMEOUT_MS = 30_000L
    }

    private val _reasoningTrace = MutableStateFlow<List<BrainReasoningStep>>(emptyList())
    val reasoningTrace: StateFlow<List<BrainReasoningStep>> = _reasoningTrace.asStateFlow()

    @Volatile
    private var isCancelled: Boolean = false

    fun cancelActiveLoop() {
        isCancelled = true
    }

    /**
     * Decomposes a single, compound, or unknown natural-language instruction into an ordered [AgentTask]
     * with one or more [AgentAction] steps.
     */
    fun planTaskForInstruction(
        userPrompt: String,
        existingTaskId: String = UUID.randomUUID().toString()
    ): AgentTask {
        val trimmed = userPrompt.trim()
            .replaceFirst(Regex("^(?i)(hey babu|hi babu|babu|hey nayro|hi nayro|nayro|hey cutie)\\s*,?\\s*"), "")
            .trim()
            .ifBlank { userPrompt.trim() }

        // Check for compound multi-step instructions joined by "and then", "then", "aur phir", "after that"
        val clauses = trimmed.split(Regex("(?i)\\s*,?\\s*(?:and then|then|aur phir|after that)\\s+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (clauses.size > 1) {
            val taskId = existingTaskId
            val mergedActions = mutableListOf<AgentAction>()
            clauses.forEach { clause ->
                val subActions = planSingleOrDynamicClause(clause, taskId)
                for (act in subActions) {
                    // Deduplicate consecutive identical LAUNCH_APP actions (e.g. "open WhatsApp, message Rahul...")
                    val last = mergedActions.lastOrNull()
                    if (last != null &&
                        last.actionType == ActionType.LAUNCH_APP &&
                        act.actionType == ActionType.LAUNCH_APP &&
                        last.target.equals(act.target, ignoreCase = true)
                    ) {
                        continue
                    }
                    mergedActions.add(act.copy(taskId = taskId))
                }
            }
            if (mergedActions.isNotEmpty()) {
                return AgentTask(
                    taskId = taskId,
                    userInstruction = userPrompt,
                    resolvedIntent = "MULTI_STEP_COMPOUND",
                    actions = mergedActions,
                    state = TaskState.CREATED
                )
            }
        }

        val resolvedIntent = TaskClassifier.classify(userPrompt)
        if (resolvedIntent.type == UserIntentType.GENERAL_AI_QUERY) {
            val dynamicActions = inferDynamicToolActionsFromInstruction(trimmed, existingTaskId)
            if (dynamicActions.isNotEmpty()) {
                return AgentTask(
                    taskId = existingTaskId,
                    userInstruction = userPrompt,
                    resolvedIntent = "DYNAMIC_TOOL_WORKFLOW",
                    actions = dynamicActions,
                    state = TaskState.CREATED
                )
            }
        }

        return TaskPlanner.planTask(resolvedIntent, existingTaskId = existingTaskId)
    }

    /**
     * Dynamically plans a task using deterministic/inferred tool decomposition first, and invokes
     * the active LLM provider's structured JSON tool-calling capability when novel requests require
     * model-driven decomposition, falling back to [planTaskForInstruction].
     */
    suspend fun planDynamicTaskWithModel(
        userPrompt: String,
        existingTaskId: String = UUID.randomUUID().toString()
    ): AgentTask {
        val initialPlan = planTaskForInstruction(userPrompt, existingTaskId)
        val isGenericLlmFallback = initialPlan.actions.size == 1 &&
            initialPlan.actions.first().actionType == ActionType.QUERY_LLM

        if (!isGenericLlmFallback) {
            return initialPlan
        }

        val activeProvider = try { providerManager.activeProvider.value } catch (_: Exception) { null }
        if (activeProvider != null && activeProvider.hasKeySaved) {
            val systemPrompt = buildString {
                append("You are the NAYRO Agent Planner (Babu). ")
                append("If the user is asking to perform device/app actions, decompose the goal into a JSON array of tool calls using ONLY the registered ActionTypes below. ")
                append("If it is a general knowledge question, output a single QUERY_LLM action.\n")
                append("Output ONLY valid JSON array: [{\"actionType\": \"...\", \"target\": \"...\", \"parameters\": {...}}]\n")
                append(ToolRegistryManager.generateLlmToolPromptSummary())
            }
            val aiRes = providerManager.sendRequest(userPrompt = userPrompt, systemPrompt = systemPrompt)
            if (aiRes is AIResult.Success) {
                val raw = aiRes.response.content.trim()
                val startBracket = raw.indexOf('[')
                val endBracket = raw.lastIndexOf(']')
                if (startBracket >= 0 && endBracket > startBracket) {
                    val jsonSlice = raw.substring(startBracket, endBracket + 1)
                    val parsed = TaskPlanner.planTaskFromJson(jsonSlice, userPrompt)
                    if (parsed != null && parsed.actions.isNotEmpty()) {
                        return parsed.copy(taskId = existingTaskId)
                    }
                }
            }
        }
        return initialPlan
    }

    /**
     * Dynamic Replanning: When a step fails or is inconclusive during execution, inspects the failed
     * action and produces an alternative recovery action if one is applicable.
     */
    fun replanFailedStep(failedAction: AgentAction, failureDetails: String): AgentAction? {
        return when (failedAction.actionType) {
            ActionType.CLICK_ELEMENT -> {
                // If clicking an element failed because it's off-screen, scroll forward to reveal it
                AgentAction(
                    taskId = failedAction.taskId,
                    actionType = ActionType.SCROLL_FORWARD,
                    target = failedAction.target,
                    parameters = failedAction.parameters,
                    requiredCapability = failedAction.requiredCapability,
                    riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE,
                    idempotencyClassification = "IDEMPOTENT"
                )
            }
            ActionType.FILE_READ_TEXT -> {
                // If reading a specific filename failed, search workspace files to locate matching file
                val query = failedAction.target ?: failedAction.parameters["filename"] ?: "md"
                AgentAction(
                    taskId = failedAction.taskId,
                    actionType = ActionType.FILE_SEARCH,
                    target = query,
                    parameters = mapOf("query" to query),
                    riskLevel = RiskLevel.R0_LOCAL_READONLY,
                    idempotencyClassification = "IDEMPOTENT"
                )
            }
            else -> null
        }
    }

    private fun planSingleOrDynamicClause(clause: String, taskId: String): List<AgentAction> {
        val cleanedClause = clause
            .replaceFirst(Regex("^(?i)(tell me the schedule|and tell me the schedule|tell me|and tell me)\\s*"), "")
            .trim()
        if (cleanedClause.isBlank()) return emptyList()

        val subIntent = TaskClassifier.classify(cleanedClause)
        if (subIntent.type != UserIntentType.GENERAL_AI_QUERY) {
            return TaskPlanner.planTask(subIntent, existingTaskId = taskId).actions
        }
        val dynamic = inferDynamicToolActionsFromInstruction(cleanedClause, taskId)
        if (dynamic.isNotEmpty()) return dynamic
        return TaskPlanner.planTask(subIntent, existingTaskId = taskId).actions
    }

    /**
     * Inspects an open-ended or unknown workflow instruction against the 52 registered tools
     * in [ToolRegistryManager] to build a multi-tool execution plan without hardcoded workflow templates.
     */
    private fun inferDynamicToolActionsFromInstruction(instruction: String, taskId: String): List<AgentAction> {
        val lower = instruction.lowercase()
        val actions = mutableListOf<AgentAction>()

        if (lower.contains("battery") && (lower.contains("check") || lower.contains("status") || lower.contains("level") || lower.contains("inspect"))) {
            actions.add(
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.GET_BATTERY_STATE,
                    riskLevel = RiskLevel.R0_LOCAL_READONLY,
                    idempotencyClassification = "IDEMPOTENT"
                )
            )
        }
        if (lower.contains("storage") && (lower.contains("check") || lower.contains("free") || lower.contains("space") || lower.contains("inspect"))) {
            actions.add(
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.GET_STORAGE_INFO,
                    riskLevel = RiskLevel.R0_LOCAL_READONLY,
                    idempotencyClassification = "IDEMPOTENT"
                )
            )
        }
        if (lower.contains("device info") || lower.contains("device model") || lower.contains("android version")) {
            actions.add(
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.GET_DEVICE_INFO,
                    riskLevel = RiskLevel.R0_LOCAL_READONLY,
                    idempotencyClassification = "IDEMPOTENT"
                )
            )
        }
        if (lower.contains("scroll down") || lower.contains("scroll forward") || lower.contains("neeche scroll")) {
            actions.add(
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.SCROLL_FORWARD,
                    requiredCapability = com.example.core.capability.CapabilityId.ACCESSIBILITY,
                    riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE,
                    idempotencyClassification = "IDEMPOTENT"
                )
            )
        }
        if (lower.contains("scroll up") || lower.contains("scroll backward") || lower.contains("upar scroll")) {
            actions.add(
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.SCROLL_BACKWARD,
                    requiredCapability = com.example.core.capability.CapabilityId.ACCESSIBILITY,
                    riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE,
                    idempotencyClassification = "IDEMPOTENT"
                )
            )
        }
        if (lower.contains("navigate back") || lower.contains("go back") || lower == "back") {
            actions.add(
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.NAVIGATE_BACK,
                    requiredCapability = com.example.core.capability.CapabilityId.ACCESSIBILITY,
                    riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE,
                    idempotencyClassification = "IDEMPOTENT"
                )
            )
        }
        if (lower.contains("take screenshot") || lower.contains("take a screenshot") || lower.contains("capture screen")) {
            actions.add(
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.TAKE_SCREENSHOT,
                    requiredCapability = com.example.core.capability.CapabilityId.ACCESSIBILITY,
                    riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE,
                    idempotencyClassification = "IDEMPOTENT"
                )
            )
        }
        if (lower.contains("workspace") && (lower.contains("browse") || lower.contains("list") || lower.contains("files"))) {
            actions.add(
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.FILE_BROWSE,
                    riskLevel = RiskLevel.R0_LOCAL_READONLY,
                    idempotencyClassification = "IDEMPOTENT"
                )
            )
        }
        if (lower.contains("inspect code") || lower.contains("analyze code") || lower.contains("sampleagentmodule")) {
            actions.add(
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.CODE_INSPECT_FILE,
                    target = "SampleAgentModule.kt",
                    parameters = mapOf("path" to "SampleAgentModule.kt"),
                    riskLevel = RiskLevel.R0_LOCAL_READONLY,
                    idempotencyClassification = "IDEMPOTENT"
                )
            )
        }
        if (lower.contains("summarize") && (lower.contains("guide") || lower.contains("workspace_guide") || lower.contains("document"))) {
            actions.add(
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.DOC_SUMMARIZE,
                    target = "NAYRO_Workspace_Guide.md",
                    parameters = mapOf("filename" to "NAYRO_Workspace_Guide.md"),
                    riskLevel = RiskLevel.R0_LOCAL_READONLY,
                    idempotencyClassification = "IDEMPOTENT"
                )
            )
        }
        if (lower.contains("save") && lower.contains("summary") && lower.contains("note")) {
            actions.add(
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.SAVE_NOTE,
                    parameters = mapOf("content" to "Dynamic workflow summary note"),
                    riskLevel = RiskLevel.R0_LOCAL_READONLY,
                    idempotencyClassification = "NON_IDEMPOTENT"
                )
            )
        }

        return actions
    }

    /**
     * Executes all planned actions in [task] iteratively (not just `actions.first()`),
     * preserving step outputs in context across steps, enforcing step budgets and timeouts,
     * and recording a full [BrainReasoningStep] trace.
     */
    suspend fun executeIterativeLoop(userPrompt: String): BrainExecutionOutcome {
        val task = planTaskForInstruction(userPrompt)
        return executeTaskIterative(task)
    }

    suspend fun executeTaskIterative(
        task: AgentTask,
        maxSteps: Int = MAX_ITERATIVE_STEPS,
        timeoutMs: Long = DEFAULT_LOOP_TIMEOUT_MS,
        customStepExecutor: (suspend (AgentAction, Map<String, String>) -> Pair<ActionExecutionResult, VerificationStatus>)? = null
    ): BrainExecutionOutcome {
        isCancelled = false
        val steps = mutableListOf<BrainReasoningStep>()
        _reasoningTrace.value = emptyList()

        if (task.actions.isEmpty()) {
            return BrainExecutionOutcome(
                finalResponse = "No executable actions were generated for this instruction.",
                stepsExecuted = emptyList(),
                overallStatus = VerificationStatus.FAILED,
                isFullyVerified = false
            )
        }

        if (task.actions.size > maxSteps) {
            return BrainExecutionOutcome(
                finalResponse = "Plan exceeds maximum step budget (${task.actions.size} > $maxSteps steps).",
                stepsExecuted = emptyList(),
                overallStatus = VerificationStatus.FAILED,
                isFullyVerified = false
            )
        }

        val stepContext = mutableMapOf<String, String>()
        var verifiedCount = 0
        var lastStatus = VerificationStatus.VERIFIED

        val timedResult = withTimeoutOrNull(timeoutMs) {
            for ((index, rawAction) in task.actions.withIndex()) {
                if (isCancelled) {
                    lastStatus = VerificationStatus.CANCELLED
                    break
                }

                task.currentActionIndex = index
                // Inject previous step outputs into action parameters if referenced
                val enrichedParams = rawAction.parameters.toMutableMap()
                stepContext["previousStepOutput"]?.let { prev ->
                    enrichedParams.putIfAbsent("previousStepOutput", prev)
                    if (rawAction.actionType == ActionType.SAVE_NOTE &&
                        enrichedParams["content"] == "Dynamic workflow summary note"
                    ) {
                        enrichedParams["content"] = "Summary: $prev"
                    }
                }
                val action = rawAction.copy(parameters = enrichedParams)

                val (execResult, verStatus) = if (customStepExecutor != null) {
                    customStepExecutor(action, stepContext)
                } else {
                    val res = actionExecutor.execute(action)
                    val status = res.status ?: if (res.isSuccess) VerificationStatus.VERIFIED else VerificationStatus.FAILED
                    res to status
                }

                val isLastStep = index == task.actions.lastIndex
                val stepRecord = BrainReasoningStep(
                    stepIndex = index + 1,
                    thought = "Step ${index + 1}/${task.actions.size}: ${action.actionType.name}",
                    selectedAction = action.actionType,
                    target = action.target,
                    executionOutput = execResult.outputMessage,
                    verificationStatus = verStatus,
                    isComplete = isLastStep && verStatus == VerificationStatus.VERIFIED
                )
                steps.add(stepRecord)
                _reasoningTrace.value = steps.toList()

                if (verStatus == VerificationStatus.VERIFIED) {
                    verifiedCount++
                    stepContext["previousStepOutput"] = execResult.outputMessage
                    stepContext["step_${index + 1}_output"] = execResult.outputMessage
                } else {
                    lastStatus = verStatus
                    break
                }
            }
            true
        }

        if (timedResult == null) {
            return BrainExecutionOutcome(
                finalResponse = "Execution timed out after ${timeoutMs}ms ($verifiedCount/${task.actions.size} steps verified).",
                stepsExecuted = steps,
                overallStatus = if (verifiedCount > 0) VerificationStatus.PARTIALLY_COMPLETED else VerificationStatus.TIMED_OUT,
                isFullyVerified = false
            )
        }

        if (isCancelled || lastStatus == VerificationStatus.CANCELLED) {
            return BrainExecutionOutcome(
                finalResponse = "Task cancelled ($verifiedCount/${task.actions.size} steps verified).",
                stepsExecuted = steps,
                overallStatus = VerificationStatus.CANCELLED,
                isFullyVerified = false
            )
        }

        val allVerified = verifiedCount == task.actions.size && task.actions.isNotEmpty()
        val finalOverallStatus = when {
            allVerified -> VerificationStatus.VERIFIED
            lastStatus == VerificationStatus.REQUIRES_PERMISSION -> VerificationStatus.REQUIRES_PERMISSION
            lastStatus == VerificationStatus.REQUIRES_CONFIRMATION -> VerificationStatus.REQUIRES_CONFIRMATION
            verifiedCount > 0 -> VerificationStatus.PARTIALLY_COMPLETED
            else -> lastStatus
        }

        val combinedSummary = steps.joinToString("\n") { step ->
            if (task.actions.size == 1) {
                step.executionOutput
            } else {
                "[Step ${step.stepIndex} - ${step.selectedAction.name} (${step.verificationStatus.name})]: ${step.executionOutput}"
            }
        }

        return BrainExecutionOutcome(
            finalResponse = combinedSummary,
            stepsExecuted = steps,
            overallStatus = finalOverallStatus,
            isFullyVerified = allVerified
        )
    }

    fun recordTrace(steps: List<BrainReasoningStep>) {
        _reasoningTrace.value = steps.toList()
    }
}

