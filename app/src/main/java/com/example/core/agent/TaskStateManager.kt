package com.example.core.agent

/**
 * Validates task state machine transitions, preventing invalid jumps
 * (e.g. from CREATED directly to COMPLETED without executing and verifying).
 */
class TaskStateManager {

    fun transition(task: AgentTask, nextState: TaskState, reason: String? = null): Boolean {
        val current = task.state

        val isValid = when (nextState) {
            TaskState.UNDERSTANDING -> current == TaskState.CREATED || current == TaskState.RETRYING
            TaskState.PLANNING -> current == TaskState.UNDERSTANDING
            TaskState.VALIDATING -> current == TaskState.PLANNING
            TaskState.WAITING_FOR_PERMISSION -> current == TaskState.VALIDATING
            TaskState.WAITING_FOR_CONFIRMATION -> current == TaskState.VALIDATING || current == TaskState.EXECUTING
            TaskState.READY -> current == TaskState.VALIDATING || current == TaskState.WAITING_FOR_CONFIRMATION || current == TaskState.WAITING_FOR_PERMISSION
            TaskState.EXECUTING -> current == TaskState.READY || current == TaskState.VERIFYING || current == TaskState.PAUSED || current == TaskState.RETRYING
            TaskState.VERIFYING -> current == TaskState.EXECUTING
            TaskState.PAUSED -> current == TaskState.EXECUTING || current == TaskState.READY
            TaskState.RETRYING -> current == TaskState.VERIFYING || current == TaskState.FAILED || current == TaskState.PAUSED
            TaskState.COMPLETED -> current == TaskState.VERIFYING || current == TaskState.EXECUTING
            TaskState.FAILED -> true // Can fail from any active state
            TaskState.CANCELLED -> true // Can be cancelled at any time
            TaskState.CREATED -> false
        }

        if (isValid) {
            task.state = nextState
            if (reason != null && nextState == TaskState.FAILED) {
                task.failureReason = reason
            }
            AgentEventBus.emit(AgentEvent.TaskStateChanged(task.taskId, nextState, reason))
            return true
        } else {
            return false
        }
    }
}
