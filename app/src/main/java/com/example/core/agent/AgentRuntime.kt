package com.example.core.agent

import android.content.Context
import com.example.core.accessibility.ActionExecutionGuard
import com.example.core.accessibility.GuardEvaluation
import com.example.core.capability.CapabilityManager
import com.example.core.memory.MemoryManager
import com.example.core.memory.NotebookManager
import com.example.core.permission.PermissionManager
import com.example.core.provider.AIProviderManager
import com.example.core.screen.ScreenIntelligenceManager
import com.example.data.local.entities.TaskHistoryEntity
import com.example.data.repository.NayroRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * NAYRO Central Agent Runtime and Task Orchestration Engine.
 * Coordinates natural language understanding, planning, risk evaluation,
 * user confirmation, native tool & accessibility execution, and outcome verification.
 */
class AgentRuntime(
    private val context: Context,
    private val repository: NayroRepository,
    private val permissionManager: PermissionManager,
    private val capabilityManager: CapabilityManager,
    private val screenIntelligenceManager: ScreenIntelligenceManager,
    private val memoryManager: MemoryManager,
    private val notebookManager: NotebookManager,
    private val providerManager: AIProviderManager
) {

    private val _currentState = MutableStateFlow<AgentState>(AgentState.Idle)
    val currentState: StateFlow<AgentState> = _currentState

    private val _currentTask = MutableStateFlow<AgentTask?>(null)
    val currentTask: StateFlow<AgentTask?> = _currentTask

    val confirmationManager = ConfirmationManager()
    val taskStateManager = TaskStateManager()

    private val planValidator = PlanValidator(context, permissionManager, capabilityManager)
    private val actionExecutor = ActionExecutor(context, screenIntelligenceManager, memoryManager, notebookManager, providerManager)
    private val actionVerifier = ActionVerifier(screenIntelligenceManager)

    private val executionMutex = Mutex()

    fun setState(state: AgentState) {
        _currentState.value = state
    }

    suspend fun executeUserInstruction(instruction: String): String = executionMutex.withLock {
        val startTime = System.currentTimeMillis()
        val text = instruction.trim()
        if (text.isBlank()) return "Please provide an instruction."

        // 1. Understand & Classify Intent
        _currentState.value = AgentState.Thinking
        val resolvedIntent = TaskClassifier.classify(text)

        // 2. Task Planning
        val task = TaskPlanner.planTask(resolvedIntent)
        _currentTask.value = task
        taskStateManager.transition(task, TaskState.UNDERSTANDING)
        taskStateManager.transition(task, TaskState.PLANNING)
        _currentState.value = AgentState.PlanningTask(task)

        // 3. Plan Validation
        taskStateManager.transition(task, TaskState.VALIDATING)
        when (val validation = planValidator.validatePlan(task)) {
            is ValidationResult.MissingCapability -> {
                taskStateManager.transition(task, TaskState.FAILED, validation.reason)
                val failureMsg = "Babu needs capability: ${validation.reason}"
                _currentState.value = AgentState.Error(failureMsg)
                recordTaskHistory(task, "FAILED", failureMsg, startTime)
                return failureMsg
            }
            is ValidationResult.MissingPermission -> {
                taskStateManager.transition(task, TaskState.WAITING_FOR_PERMISSION, validation.reason)
                _currentState.value = AgentState.WaitingForPermission(validation.permission, validation.reason)
                recordTaskHistory(task, "FAILED", validation.reason, startTime)
                return "Permission needed: ${validation.reason}"
            }
            is ValidationResult.InvalidPlan -> {
                taskStateManager.transition(task, TaskState.FAILED, validation.reason)
                val msg = "Plan validation failed: ${validation.reason}"
                _currentState.value = AgentState.Error(msg)
                recordTaskHistory(task, "FAILED", msg, startTime)
                return msg
            }
            is ValidationResult.Valid -> {
                taskStateManager.transition(task, TaskState.READY)
                recordTaskHistory(task, "IN_PROGRESS", "Task execution started", startTime)
            }
        }

        // 4. Sequential Action Execution & Verification Loop
        val outputBuilder = StringBuilder()
        var isAllSuccess = true

        for ((index, action) in task.actions.withIndex()) {
            task.currentActionIndex = index

            // 1. Asynchronous Confirmation Guard for R3 and sensitive actions
            var pending: PendingConfirmation? = null
            if (action.riskLevel.requiresConfirmation || action.actionType == ActionType.ASK_USER_CONFIRMATION) {
                taskStateManager.transition(task, TaskState.WAITING_FOR_CONFIRMATION)
                val p = PendingConfirmation(
                    taskId = task.taskId,
                    actionId = action.actionId,
                    actionType = action.actionType,
                    title = "Confirm ${action.actionType.name}",
                    description = "Target: ${action.target ?: "System"}, Params: ${action.parameters}",
                    target = action.target,
                    parameters = action.parameters,
                    riskLevel = action.riskLevel
                )
                pending = p
                _currentState.value = AgentState.WaitingForConfirmation(p)

                val confirmationStatus = confirmationManager.awaitConfirmation(p, timeoutMs = 60_000L)
                when (confirmationStatus) {
                    ConfirmationStatus.REJECTED -> {
                        val cancelMsg = "Action was cancelled: user rejected confirmation for ${action.actionType.name}."
                        taskStateManager.transition(task, TaskState.CANCELLED, cancelMsg)
                        _currentState.value = AgentState.Cancelled(cancelMsg)
                        recordTaskHistory(task, "CANCELLED", cancelMsg, startTime)
                        return cancelMsg
                    }
                    ConfirmationStatus.EXPIRED -> {
                        val timeoutMsg = "Action timed out waiting for user confirmation."
                        taskStateManager.transition(task, TaskState.FAILED, timeoutMsg)
                        _currentState.value = AgentState.Error(timeoutMsg)
                        recordTaskHistory(task, "FAILED", timeoutMsg, startTime)
                        return timeoutMsg
                    }
                    ConfirmationStatus.CONFIRMED -> {
                        // User approved authentic action
                    }
                    else -> {
                        val failMsg = "Action confirmation failed or was cancelled."
                        taskStateManager.transition(task, TaskState.FAILED, failMsg)
                        _currentState.value = AgentState.Error(failMsg)
                        recordTaskHistory(task, "FAILED", failMsg, startTime)
                        return failMsg
                    }
                }
            }

            // 2. Centralized ActionExecutionGuard Security Gate
            val isCapAvail = action.requiredCapability?.let { capabilityManager.isCapabilityAvailable(it) } ?: true
            val isPermGranted = action.requiredPermission?.let { permissionManager.isPermissionGranted(it) } ?: true
            val guardResult = ActionExecutionGuard.validateExecution(
                action = action,
                isCapabilityAvailable = isCapAvail,
                isPermissionGranted = isPermGranted,
                confirmation = pending
            )

            when (guardResult) {
                is GuardEvaluation.Blocked -> {
                    val blockMsg = "Security Block: ${guardResult.reason}"
                    taskStateManager.transition(task, TaskState.FAILED, blockMsg)
                    AgentEventBus.emit(AgentEvent.ActionFailed(task.taskId, action.actionId, blockMsg))
                    _currentState.value = AgentState.Error(blockMsg)
                    recordTaskHistory(task, "FAILED", blockMsg, startTime)
                    return blockMsg
                }
                is GuardEvaluation.RequiresConfirmation -> {
                    val unconfMsg = "Security Block: Action requires confirmation: ${guardResult.reason}"
                    taskStateManager.transition(task, TaskState.FAILED, unconfMsg)
                    _currentState.value = AgentState.Error(unconfMsg)
                    recordTaskHistory(task, "FAILED", unconfMsg, startTime)
                    return unconfMsg
                }
                is GuardEvaluation.Allowed -> {
                    // Passed security gate
                }
            }

            // 3. Execute Action
            taskStateManager.transition(task, TaskState.EXECUTING)
            _currentState.value = AgentState.ExecutingTask(task, action, index + 1, task.actions.size)
            AgentEventBus.emit(AgentEvent.ActionStarted(task.taskId, action, index + 1, task.actions.size))

            val preSnapshot = screenIntelligenceManager.captureCurrentSnapshot()
            val execResult = actionExecutor.execute(action, isConfirmed = (pending?.status == ConfirmationStatus.CONFIRMED))

            if (!execResult.isSuccess) {
                isAllSuccess = false
                taskStateManager.transition(task, TaskState.FAILED, execResult.output)
                AgentEventBus.emit(AgentEvent.ActionFailed(task.taskId, action.actionId, execResult.output))
                val err = "Step ${index + 1} (${action.actionType}) failed: ${execResult.output}"
                _currentState.value = AgentState.Error(err)
                recordTaskHistory(task, "FAILED", err, startTime)
                return err
            }

            // 4. Verify Action Outcome
            taskStateManager.transition(task, TaskState.VERIFYING)
            val screenChange = screenIntelligenceManager.verifyScreenChange(preSnapshot)
            val verification = actionVerifier.verify(action, execResult, screenChange)

            when (verification.status) {
                VerificationStatus.FAILED -> {
                    isAllSuccess = false
                    val verifyFailMsg = "Verification failed on step ${index + 1}: ${verification.details}"
                    taskStateManager.transition(task, TaskState.FAILED, verifyFailMsg)
                    AgentEventBus.emit(AgentEvent.ActionFailed(task.taskId, action.actionId, verifyFailMsg))
                    _currentState.value = AgentState.Error(verifyFailMsg)
                    recordTaskHistory(task, "FAILED", verifyFailMsg, startTime)
                    return verifyFailMsg
                }
                VerificationStatus.INCONCLUSIVE -> {
                    val honestMsg = "${execResult.output} (Note: Execution outcome could not be verified automatically)"
                    AgentEventBus.emit(AgentEvent.ActionCompleted(task.taskId, action.actionId, honestMsg))
                    outputBuilder.append(honestMsg).append("\n")
                }
                VerificationStatus.NOT_SUPPORTED, VerificationStatus.VERIFIED -> {
                    AgentEventBus.emit(AgentEvent.ActionCompleted(task.taskId, action.actionId, execResult.output))
                    outputBuilder.append(execResult.output).append("\n")
                }
            }
        }

        // 5. Completion
        val finalResult = outputBuilder.toString().trim().ifBlank { "Task completed successfully." }
        task.finalResult = finalResult
        taskStateManager.transition(task, TaskState.COMPLETED)
        _currentState.value = AgentState.Completed(finalResult)
        AgentEventBus.emit(AgentEvent.TaskFinished(task.taskId, isSuccess = true, finalResult))

        recordTaskHistory(task, "COMPLETED", finalResult, startTime)
        return finalResult
    }

    private suspend fun recordTaskHistory(task: AgentTask, status: String, resultSummary: String, startTime: Long) {
        val duration = System.currentTimeMillis() - startTime
        repository.recordTaskExecution(
            TaskHistoryEntity(
                taskId = task.taskId,
                userInstruction = task.userInstruction,
                resolvedIntent = task.resolvedIntent,
                status = status,
                stepsCount = task.actions.size,
                resultSummary = resultSummary.take(200),
                durationMs = duration
            )
        )
        repository.logAuditAction(
            actionType = "TASK_EXECUTION_${task.resolvedIntent}",
            target = task.userInstruction.take(50),
            riskLevel = task.actions.maxByOrNull { it.riskLevel.ordinal }?.riskLevel?.code ?: "R0",
            outcome = status,
            details = "Steps: ${task.actions.size}, Duration: ${duration}ms, Result: ${resultSummary.take(100)}"
        )
    }

    fun cancelCurrentTask() {
        _currentTask.value?.let { task ->
            taskStateManager.transition(task, TaskState.CANCELLED, "Cancelled by user.")
        }
        confirmationManager.clear()
        _currentState.value = AgentState.Idle
    }

    /**
     * Recovers active task state after process recreation.
     * Prevents duplicate execution of non-idempotent actions and updates DB status to INTERRUPTED.
     */
    suspend fun recoverInterruptedTasksOnBoot() {
        val interrupted = repository.getIncompleteTasks()
        for (taskEntity in interrupted) {
            val failureMsg = "Task was interrupted by process restart. Non-idempotent actions were halted to prevent duplicate execution."
            repository.recordTaskExecution(
                taskEntity.copy(
                    status = "INTERRUPTED",
                    resultSummary = failureMsg,
                    durationMs = System.currentTimeMillis() - taskEntity.timestamp
                )
            )
            repository.logAuditAction(
                actionType = "TASK_RECOVERY_${taskEntity.resolvedIntent}",
                target = taskEntity.userInstruction.take(50),
                riskLevel = "R1",
                outcome = "INTERRUPTED",
                details = "Interrupted task recovered on process boot. Non-idempotent actions were halted."
            )
            AgentEventBus.emit(AgentEvent.TaskFinished(taskEntity.taskId, isSuccess = false, failureMsg))
        }
    }
}
