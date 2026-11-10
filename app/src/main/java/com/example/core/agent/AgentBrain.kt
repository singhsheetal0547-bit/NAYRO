package com.example.core.agent

import com.example.core.agent.tools.ToolRegistryManager
import com.example.core.provider.AIProviderManager
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
 * Authoritative Multi-Step Reasoning, Planning & Iterative Orchestration Engine (BUG 01 & BUG 02 Remediation).
 * Integrated directly with AgentRuntime so all user entry points share one unified planning and
 * multi-step execution loop. Never stops after `task.actions.first()` when multiple actions exist.
 */
class AgentBrain(
    private val providerManager: AIProviderManager,
    private val actionExecutor: ActionExecutor,
    private val workspaceManager: WorkspaceManager,
    private val skillManager: SkillManager,
    private val subAgentManager: SubAgentManager
) {

    companion object {
        const val MAX_ITERATIVE_STEPS = 10
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
     * Decomposes a single or compound natural-language instruction into an ordered [AgentTask]
     * with one or more [AgentAction] steps.
     */
    fun planTaskForInstruction(
        userPrompt: String,
        existingTaskId: String = UUID.randomUUID().toString()
    ): AgentTask {
        val trimmed = userPrompt.trim()
        // Check for compound multi-step instructions joined by " and then " or " then "
        val clauses = trimmed.split(Regex("(?i)\\s+(?:and then|then|aur phir)\\s+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (clauses.size > 1) {
            val taskId = existingTaskId
            val mergedActions = mutableListOf<AgentAction>()
            clauses.forEach { clause ->
                val subIntent = TaskClassifier.classify(clause)
                val subPlan = TaskPlanner.planTask(subIntent, existingTaskId = taskId)
                subPlan.actions.forEach { act ->
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
        return TaskPlanner.planTask(resolvedIntent, existingTaskId = existingTaskId)
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
