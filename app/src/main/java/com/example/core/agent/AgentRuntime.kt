package com.example.core.agent

import android.content.Context
import com.example.core.accessibility.ActionExecutionGuard
import com.example.core.accessibility.AppNavigationManager
import com.example.core.accessibility.GuardEvaluation
import com.example.core.capability.CapabilityManager
import com.example.core.memory.MemoryManager
import com.example.core.memory.NotebookManager
import com.example.core.permission.PermissionManager
import com.example.core.provider.AIProviderManager
import com.example.core.screen.ScreenIntelligenceManager
import com.example.core.skills.SkillManager
import com.example.core.subagent.SubAgentManager
import com.example.core.telephony.CallIntelligenceManager
import com.example.core.workspace.WorkspaceManager
import com.example.data.local.entities.TaskHistoryEntity
import com.example.data.repository.NayroRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

data class AgentExecutionSummary(
    val task: AgentTask,
    val isSuccess: Boolean,
    val message: String,
    val verificationStatus: VerificationStatus = VerificationStatus.VERIFIED
)

/**
 * Authoritative Agent Runtime Engine for NAYRO (BUG 01, BUG 02, BUG 05, BUG 06 Remediation).
 * Unifies AgentBrain planning/reasoning with permission validation, R0-R4 risk confirmation,
 * ActionExecutor execution, and strict ActionVerifier state verification.
 * Never marks a task COMPLETED or isSuccess=true when any step is FAILED, INCONCLUSIVE, or NOT_SUPPORTED.
 */
class AgentRuntime(
    private val context: Context,
    private val repository: NayroRepository,
    private val permissionManager: PermissionManager,
    private val capabilityManager: CapabilityManager,
    private val screenIntelligenceManager: ScreenIntelligenceManager,
    private val memoryManager: MemoryManager,
    private val notebookManager: NotebookManager,
    private val providerManager: AIProviderManager,
    val workspaceManager: WorkspaceManager = WorkspaceManager(context),
    val skillManager: SkillManager = SkillManager(),
    val subAgentManager: SubAgentManager = SubAgentManager(),
    val callIntelligenceManager: CallIntelligenceManager = CallIntelligenceManager(context)
) {

    private val _currentState = MutableStateFlow<AgentState>(AgentState.Idle)
    val currentState: StateFlow<AgentState> = _currentState.asStateFlow()

    private val _currentTask = MutableStateFlow<AgentTask?>(null)
    val currentTask: StateFlow<AgentTask?> = _currentTask.asStateFlow()

    private val stateManager = TaskStateManager()
    private val planValidator = PlanValidator(context, permissionManager, capabilityManager)
    val confirmationManager = ConfirmationManager()

    val actionExecutor = ActionExecutor(
        context = context,
        screenIntelligenceManager = screenIntelligenceManager,
        memoryManager = memoryManager,
        notebookManager = notebookManager,
        providerManager = providerManager,
        workspaceManager = workspaceManager,
        skillManager = skillManager,
        subAgentManager = subAgentManager,
        callIntelligenceManager = callIntelligenceManager
    )

    val agentBrain = AgentBrain(
        providerManager = providerManager,
        actionExecutor = actionExecutor,
        workspaceManager = workspaceManager,
        skillManager = skillManager,
        subAgentManager = subAgentManager
    )

    private val actionVerifier = ActionVerifier(screenIntelligenceManager)

    @Volatile
    private var isTaskCancelled = false

    fun setState(newState: AgentState) {
        _currentState.value = newState
    }

    fun cancelCurrentTask() {
        isTaskCancelled = true
        agentBrain.cancelActiveLoop()
        confirmationManager.clear()
        _currentTask.value?.let { task ->
            stateManager.transition(task, TaskState.CANCELLED, "User cancelled task.")
        }
        _currentState.value = AgentState.Cancelled("Task stopped by user.")
    }

    /**
     * Startup Recovery: Inspects incomplete tasks from previous sessions and marks them
     * cleanly interrupted without blindly re-executing side-effect actions.
     */
    suspend fun recoverInterruptedTasksOnBoot() {
        try {
            val incomplete = repository.getIncompleteTasks()
            for (entity in incomplete) {
                repository.recordTaskExecution(
                    entity.copy(
                        status = TaskState.FAILED.name,
                        resultSummary = "Task interrupted by application shutdown. Safe recovery halted automatic re-execution."
                    )
                )
                repository.logAuditAction(
                    actionType = "TASK_CONTINUITY_RECOVERY",
                    target = entity.resolvedIntent,
                    riskLevel = "R0",
                    outcome = "INTERRUPTED_RECOVERED",
                    details = "Cleaned up incomplete task #${entity.id} on boot."
                )
            }
        } catch (_: Exception) {}
    }

    /**
     * Main entry point for all user instructions (Typed Chat, Voice ASR, Floating Assistant, Wake Word).
     * Returns the user-facing response text after executing through the unified AgentBrain + AgentRuntime pipeline.
     */
    suspend fun executeUserInstruction(userPrompt: String): String {
        return executeUserInstructionDetailed(userPrompt).message
    }

    /**
     * Detailed execution entry point returning structured [AgentExecutionSummary] with
     * exact [TaskState], `isSuccess`, and [VerificationStatus].
     */
    suspend fun executeUserInstructionDetailed(userPrompt: String): AgentExecutionSummary {
        isTaskCancelled = false
        _currentState.value = AgentState.Thinking

        // 1. Plan & decompose via unified AgentBrain (supports single & multi-step instructions)
        val task = agentBrain.planTaskForInstruction(userPrompt)
        _currentTask.value = task
        AgentEventBus.emit(AgentEvent.TaskCreated(task))

        stateManager.transition(task, TaskState.UNDERSTANDING)
        stateManager.transition(task, TaskState.PLANNING)
        _currentState.value = AgentState.PlanningTask(task)

        // 2. Validate Plan Schema, Permissions & Capabilities
        stateManager.transition(task, TaskState.VALIDATING)
        when (val validation = planValidator.validatePlan(task)) {
            is ValidationResult.MissingCapability -> {
                stateManager.transition(task, TaskState.WAITING_FOR_PERMISSION, validation.reason)
                _currentState.value = AgentState.WaitingForPermission(validation.capabilityId.name, validation.reason)
                logAndPersistTask(task, "Blocked: ${validation.reason}")
                return AgentExecutionSummary(
                    task = task,
                    isSuccess = false,
                    message = validation.reason,
                    verificationStatus = VerificationStatus.REQUIRES_PERMISSION
                )
            }
            is ValidationResult.MissingPermission -> {
                stateManager.transition(task, TaskState.WAITING_FOR_PERMISSION, validation.reason)
                _currentState.value = AgentState.WaitingForPermission(validation.permission, validation.reason)
                logAndPersistTask(task, "Blocked: ${validation.reason}")
                return AgentExecutionSummary(
                    task = task,
                    isSuccess = false,
                    message = validation.reason,
                    verificationStatus = VerificationStatus.REQUIRES_PERMISSION
                )
            }
            is ValidationResult.InvalidPlan -> {
                stateManager.transition(task, TaskState.FAILED, validation.reason)
                _currentState.value = AgentState.Error(validation.reason)
                logAndPersistTask(task, "Failed: ${validation.reason}")
                return AgentExecutionSummary(
                    task = task,
                    isSuccess = false,
                    message = validation.reason,
                    verificationStatus = VerificationStatus.FAILED
                )
            }
            is ValidationResult.Valid -> {
                stateManager.transition(task, TaskState.READY)
            }
        }

        // 3. Multi-Step Execution Loop with Context Carryover, Risk Guard, Timeout & Strict Verification
        val stepOutputs = mutableListOf<String>()
        val brainTraceSteps = mutableListOf<BrainReasoningStep>()
        val stepContext = mutableMapOf<String, String>()
        var verifiedStepCount = 0

        for ((index, rawAction) in task.actions.withIndex()) {
            if (isTaskCancelled) {
                stateManager.transition(task, TaskState.CANCELLED, "Cancelled during multi-step loop.")
                logAndPersistTask(task, "Cancelled by user at step ${index + 1}.")
                return AgentExecutionSummary(
                    task = task,
                    isSuccess = false,
                    message = "Task cancelled by user.",
                    verificationStatus = VerificationStatus.CANCELLED
                )
            }

            task.currentActionIndex = index
            val enrichedParams = rawAction.parameters.toMutableMap()
            stepContext["previousStepOutput"]?.let { prev ->
                enrichedParams.putIfAbsent("previousStepOutput", prev)
            }
            val action = rawAction.copy(parameters = enrichedParams)

            var activeConfirmation: PendingConfirmation? = null

            // Handle R3 Confirmation Gate before executing step
            if (action.riskLevel == RiskLevel.R3_EXTERNAL_COMMUNICATION || action.actionType == ActionType.ASK_USER_CONFIRMATION) {
                stateManager.transition(task, TaskState.WAITING_FOR_CONFIRMATION)
                val recipient = action.target ?: action.parameters["recipient"] ?: "Recipient"
                val draft = action.parameters["draft"] ?: action.parameters["message"] ?: action.parameters["text"] ?: ""

                val pendingConf = PendingConfirmation(
                    taskId = task.taskId,
                    actionId = action.actionId,
                    actionType = action.actionType,
                    title = "Confirm ${action.actionType.name}",
                    description = "Recipient: $recipient\nMessage Draft: \"$draft\"\n\nNAYRO requires your explicit approval before proceeding.",
                    target = action.target,
                    parameters = action.parameters,
                    riskLevel = action.riskLevel
                )
                _currentState.value = AgentState.WaitingForConfirmation(pendingConf)

                val confStatus = confirmationManager.awaitConfirmation(pendingConf, timeoutMs = 60_000L)
                when (confStatus) {
                    ConfirmationStatus.CONFIRMED -> {
                        activeConfirmation = pendingConf
                        stateManager.transition(task, TaskState.READY)
                    }
                    ConfirmationStatus.EXPIRED -> {
                        val reason = "Confirmation request expired after 60s. Action was not executed."
                        stateManager.transition(task, TaskState.TIMED_OUT, reason)
                        _currentState.value = AgentState.Cancelled(reason)
                        logAndPersistTask(task, reason)
                        return AgentExecutionSummary(
                            task = task,
                            isSuccess = false,
                            message = reason,
                            verificationStatus = VerificationStatus.TIMED_OUT
                        )
                    }
                    else -> {
                        val reason = "Action rejected or cancelled by user."
                        stateManager.transition(task, TaskState.CANCELLED, reason)
                        _currentState.value = AgentState.Cancelled(reason)
                        logAndPersistTask(task, reason)
                        return AgentExecutionSummary(
                            task = task,
                            isSuccess = false,
                            message = reason,
                            verificationStatus = VerificationStatus.CANCELLED
                        )
                    }
                }
            }

            // Runtime Guard Validation
            val guardCheck = ActionExecutionGuard.validateExecution(
                action = action,
                isCapabilityAvailable = true,
                isPermissionGranted = true,
                confirmation = activeConfirmation
            )
            if (guardCheck is GuardEvaluation.Blocked) {
                stateManager.transition(task, TaskState.FAILED, guardCheck.reason)
                _currentState.value = AgentState.Error(guardCheck.reason)
                logAndPersistTask(task, "Blocked by Guard: ${guardCheck.reason}")
                return AgentExecutionSummary(
                    task = task,
                    isSuccess = false,
                    message = "Execution blocked for safety: ${guardCheck.reason}",
                    verificationStatus = VerificationStatus.FAILED
                )
            }

            // Transition to EXECUTING
            stateManager.transition(task, TaskState.EXECUTING)
            _currentState.value = AgentState.ExecutingTask(
                task = task,
                currentAction = action,
                step = index + 1,
                totalSteps = task.actions.size
            )
            AgentEventBus.emit(AgentEvent.ActionStarted(task.taskId, action, index + 1, task.actions.size))

            val preState = actionVerifier.capturePreState()
            val resolvedPkg = if (action.actionType == ActionType.LAUNCH_APP && action.target != null) {
                AppNavigationManager.resolvePackage(context, action.target)
            } else null

            val isConfirmedFlag = activeConfirmation?.status == ConfirmationStatus.CONFIRMED

            val execResult = withTimeoutOrNull(action.timeoutMs) {
                actionExecutor.execute(action, isConfirmed = isConfirmedFlag)
            } ?: ActionExecutionResult(
                isSuccess = false,
                outputMessage = "Step ${index + 1} (${action.actionType.name}) timed out after ${action.timeoutMs}ms.",
                status = VerificationStatus.TIMED_OUT
            )

            // Transition to VERIFYING
            stateManager.transition(task, TaskState.VERIFYING)
            if (action.actionType == ActionType.LAUNCH_APP || action.actionType == ActionType.OPEN_SETTINGS_SECTION || action.actionType == ActionType.CLICK_ELEMENT) {
                delay(350)
            }

            val verification = actionVerifier.verifyAction(
                action = action,
                executionResult = execResult,
                preStateSummary = preState,
                resolvedTargetPackage = resolvedPkg
            )

            if (verification.status == VerificationStatus.VERIFIED) {
                AgentEventBus.emit(AgentEvent.ActionCompleted(task.taskId, action.actionId, verification.details))
            } else {
                AgentEventBus.emit(AgentEvent.ActionFailed(task.taskId, action.actionId, verification.details))
            }

            repository.logAuditAction(
                actionType = action.actionType.name,
                target = action.target ?: "local",
                riskLevel = action.riskLevel.code,
                outcome = verification.status.name,
                details = verification.details
            )

            brainTraceSteps.add(
                BrainReasoningStep(
                    stepIndex = index + 1,
                    thought = "Step ${index + 1}/${task.actions.size}: ${action.actionType.name}",
                    selectedAction = action.actionType,
                    target = action.target,
                    executionOutput = verification.details,
                    verificationStatus = verification.status,
                    isComplete = (index == task.actions.lastIndex) && verification.status == VerificationStatus.VERIFIED
                )
            )
            agentBrain.recordTrace(brainTraceSteps)

            // Strict Verification Handling (BUG 05 & BUG 06 Fixed: NOT_SUPPORTED and INCONCLUSIVE are NEVER grouped with VERIFIED)
            when (verification.status) {
                VerificationStatus.VERIFIED -> {
                    verifiedStepCount++
                    stepOutputs.add(verification.details)
                    stepContext["previousStepOutput"] = verification.details
                }

                VerificationStatus.REQUIRES_PERMISSION -> {
                    stateManager.transition(task, TaskState.WAITING_FOR_PERMISSION, verification.details)
                    _currentState.value = AgentState.WaitingForPermission(
                        action.requiredPermission ?: "ANDROID_PERMISSION",
                        verification.details
                    )
                    logAndPersistTask(task, verification.details)
                    return AgentExecutionSummary(
                        task = task,
                        isSuccess = false,
                        message = verification.details,
                        verificationStatus = VerificationStatus.REQUIRES_PERMISSION
                    )
                }

                VerificationStatus.REQUIRES_CONFIRMATION -> {
                    stateManager.transition(task, TaskState.WAITING_FOR_CONFIRMATION, verification.details)
                    _currentState.value = AgentState.Error(verification.details)
                    logAndPersistTask(task, verification.details)
                    return AgentExecutionSummary(
                        task = task,
                        isSuccess = false,
                        message = verification.details,
                        verificationStatus = VerificationStatus.REQUIRES_CONFIRMATION
                    )
                }

                VerificationStatus.NOT_SUPPORTED -> {
                    val targetState = if (verifiedStepCount > 0) TaskState.PARTIALLY_COMPLETED else TaskState.NOT_SUPPORTED
                    val finalVerStatus = if (verifiedStepCount > 0) VerificationStatus.PARTIALLY_COMPLETED else VerificationStatus.NOT_SUPPORTED
                    stateManager.transition(task, targetState, verification.details)
                    val fullMsg = if (stepOutputs.isNotEmpty()) {
                        (stepOutputs + "Step ${index + 1} not supported: ${verification.details}").joinToString("\n")
                    } else {
                        verification.details
                    }
                    task.finalResult = fullMsg
                    _currentState.value = AgentState.Error(fullMsg)
                    logAndPersistTask(task, fullMsg)
                    return AgentExecutionSummary(
                        task = task,
                        isSuccess = false,
                        message = fullMsg,
                        verificationStatus = finalVerStatus
                    )
                }

                VerificationStatus.INCONCLUSIVE -> {
                    // BUG 05 Fix: Never transition INCONCLUSIVE to COMPLETED or return isSuccess = true
                    val targetState = if (verifiedStepCount > 0) TaskState.PARTIALLY_COMPLETED else TaskState.INCONCLUSIVE
                    val finalVerStatus = if (verifiedStepCount > 0) VerificationStatus.PARTIALLY_COMPLETED else VerificationStatus.INCONCLUSIVE
                    stateManager.transition(task, targetState, verification.details)
                    val fullMsg = if (stepOutputs.isNotEmpty()) {
                        (stepOutputs + "Step ${index + 1} inconclusive: ${verification.details}").joinToString("\n")
                    } else {
                        verification.details
                    }
                    task.finalResult = fullMsg
                    _currentState.value = AgentState.Error(fullMsg)
                    logAndPersistTask(task, fullMsg)
                    return AgentExecutionSummary(
                        task = task,
                        isSuccess = false,
                        message = fullMsg,
                        verificationStatus = finalVerStatus
                    )
                }

                VerificationStatus.TIMED_OUT -> {
                    val targetState = if (verifiedStepCount > 0) TaskState.PARTIALLY_COMPLETED else TaskState.TIMED_OUT
                    val finalVerStatus = if (verifiedStepCount > 0) VerificationStatus.PARTIALLY_COMPLETED else VerificationStatus.TIMED_OUT
                    stateManager.transition(task, targetState, verification.details)
                    val fullMsg = if (stepOutputs.isNotEmpty()) {
                        (stepOutputs + verification.details).joinToString("\n")
                    } else {
                        verification.details
                    }
                    task.finalResult = fullMsg
                    _currentState.value = AgentState.Error(fullMsg)
                    logAndPersistTask(task, fullMsg)
                    return AgentExecutionSummary(
                        task = task,
                        isSuccess = false,
                        message = fullMsg,
                        verificationStatus = finalVerStatus
                    )
                }

                VerificationStatus.CANCELLED -> {
                    stateManager.transition(task, TaskState.CANCELLED, verification.details)
                    _currentState.value = AgentState.Cancelled(verification.details)
                    logAndPersistTask(task, verification.details)
                    return AgentExecutionSummary(
                        task = task,
                        isSuccess = false,
                        message = verification.details,
                        verificationStatus = VerificationStatus.CANCELLED
                    )
                }

                VerificationStatus.FAILED, VerificationStatus.PARTIALLY_COMPLETED -> {
                    val targetState = if (verifiedStepCount > 0) TaskState.PARTIALLY_COMPLETED else TaskState.FAILED
                    val finalVerStatus = if (verifiedStepCount > 0) VerificationStatus.PARTIALLY_COMPLETED else VerificationStatus.FAILED
                    stateManager.transition(task, targetState, verification.details)
                    val fullMsg = if (stepOutputs.isNotEmpty()) {
                        (stepOutputs + "Step ${index + 1} failed: ${verification.details}").joinToString("\n")
                    } else {
                        verification.details
                    }
                    task.finalResult = fullMsg
                    _currentState.value = AgentState.Error(fullMsg)
                    logAndPersistTask(task, fullMsg)
                    return AgentExecutionSummary(
                        task = task,
                        isSuccess = false,
                        message = fullMsg,
                        verificationStatus = finalVerStatus
                    )
                }
            }
        }

        // Only reaches COMPLETED if every action in task.actions was VERIFIED
        stateManager.transition(task, TaskState.COMPLETED)
        val finalResponse = stepOutputs.joinToString("\n")
        task.finalResult = finalResponse
        _currentState.value = AgentState.Completed(finalResponse)
        AgentEventBus.emit(AgentEvent.TaskFinished(task.taskId, true, finalResponse))
        logAndPersistTask(task, finalResponse)

        return AgentExecutionSummary(
            task = task,
            isSuccess = true,
            message = finalResponse,
            verificationStatus = VerificationStatus.VERIFIED
        )
    }

    private suspend fun logAndPersistTask(task: AgentTask, summary: String) {
        repository.recordTaskExecution(
            TaskHistoryEntity(
                taskId = task.taskId,
                userInstruction = task.userInstruction,
                resolvedIntent = task.resolvedIntent,
                status = task.state.name,
                stepsCount = task.actions.size,
                resultSummary = summary,
                durationMs = System.currentTimeMillis() - task.createdAt
            )
        )
    }
}
