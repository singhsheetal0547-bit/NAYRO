package com.example.core.agent

/**
 * Validates task state machine transitions, preventing invalid jumps
 * (e.g. from CREATED directly to COMPLETED without executing and verifying).
 */
class TaskStateManager {

    fun transition(task: AgentTask, nextState: TaskState, reason: String? = null): Boolean {
        val current = task.state

        val isValid = when (nextState) {
            TaskState.IDLE -> true
            TaskState.LISTENING -> current == TaskState.IDLE || current == TaskState.CREATED || current == TaskState.WAITING_FOR_USER_INPUT
            TaskState.PENDING -> current == TaskState.CREATED || current == TaskState.IDLE || current == TaskState.LISTENING
            TaskState.UNDERSTANDING -> current == TaskState.CREATED || current == TaskState.IDLE || current == TaskState.LISTENING || current == TaskState.PENDING || current == TaskState.RETRYING || current == TaskState.RECOVERING
            TaskState.REASONING -> current == TaskState.UNDERSTANDING || current == TaskState.CREATED || current == TaskState.PENDING
            TaskState.PLANNING -> current == TaskState.UNDERSTANDING || current == TaskState.REASONING || current == TaskState.CREATED || current == TaskState.PENDING || current == TaskState.REPLANNING
            TaskState.VALIDATING -> current == TaskState.PLANNING || current == TaskState.REPLANNING
            TaskState.WAITING_FOR_PERMISSION, TaskState.AWAITING_PERMISSION -> current == TaskState.VALIDATING || current == TaskState.EXECUTING || current == TaskState.RUNNING || current == TaskState.VERIFYING || current == TaskState.READY || current == TaskState.RECOVERING
            TaskState.WAITING_FOR_DEVICE_UNLOCK -> current == TaskState.VALIDATING || current == TaskState.READY || current == TaskState.EXECUTING || current == TaskState.RUNNING || current == TaskState.PAUSED || current == TaskState.RECOVERING
            TaskState.WAITING_FOR_CONFIRMATION, TaskState.AWAITING_CONFIRMATION, TaskState.WAITING_FOR_USER_CONFIRMATION -> current == TaskState.VALIDATING || current == TaskState.EXECUTING || current == TaskState.RUNNING || current == TaskState.VERIFYING || current == TaskState.READY || current == TaskState.RECOVERING || current == TaskState.INCONCLUSIVE
            TaskState.WAITING_FOR_USER_INPUT -> current == TaskState.UNDERSTANDING || current == TaskState.PLANNING || current == TaskState.VALIDATING || current == TaskState.EXECUTING || current == TaskState.RUNNING
            TaskState.READY -> current == TaskState.VALIDATING || current == TaskState.WAITING_FOR_CONFIRMATION || current == TaskState.AWAITING_CONFIRMATION || current == TaskState.WAITING_FOR_USER_CONFIRMATION || current == TaskState.WAITING_FOR_PERMISSION || current == TaskState.AWAITING_PERMISSION || current == TaskState.WAITING_FOR_DEVICE_UNLOCK || current == TaskState.WAITING_FOR_USER_INPUT || current == TaskState.RECOVERING || current == TaskState.PAUSED
            TaskState.RUNNING -> current == TaskState.READY || current == TaskState.PLANNING || current == TaskState.PAUSED || current == TaskState.RECOVERING || current == TaskState.REPLANNING || current == TaskState.WAITING_FOR_DEVICE_UNLOCK || current == TaskState.WAITING_FOR_PERMISSION
            TaskState.EXECUTING -> current == TaskState.READY || current == TaskState.RUNNING || current == TaskState.VERIFYING || current == TaskState.OBSERVING || current == TaskState.PAUSED || current == TaskState.RETRYING || current == TaskState.RECOVERING || current == TaskState.REPLANNING || current == TaskState.WAITING_FOR_DEVICE_UNLOCK || current == TaskState.WAITING_FOR_PERMISSION
            TaskState.OBSERVING -> current == TaskState.EXECUTING || current == TaskState.RUNNING
            TaskState.VERIFYING -> current == TaskState.EXECUTING || current == TaskState.RUNNING || current == TaskState.OBSERVING
            TaskState.REPLANNING -> current == TaskState.VERIFYING || current == TaskState.EXECUTING || current == TaskState.FAILED || current == TaskState.INCONCLUSIVE
            TaskState.RECOVERING -> true
            TaskState.PAUSED -> current == TaskState.EXECUTING || current == TaskState.RUNNING || current == TaskState.READY || current == TaskState.WAITING_FOR_DEVICE_UNLOCK
            TaskState.RETRYING -> current == TaskState.VERIFYING || current == TaskState.FAILED || current == TaskState.PAUSED || current == TaskState.INCONCLUSIVE || current == TaskState.RECOVERING || current == TaskState.REPLANNING
            TaskState.COMPLETED -> current == TaskState.VERIFYING || current == TaskState.EXECUTING || current == TaskState.RUNNING || current == TaskState.READY
            TaskState.PARTIALLY_COMPLETED -> current == TaskState.VERIFYING || current == TaskState.EXECUTING || current == TaskState.RUNNING
            TaskState.INCONCLUSIVE -> current == TaskState.VERIFYING || current == TaskState.EXECUTING || current == TaskState.RUNNING
            TaskState.NOT_SUPPORTED -> current == TaskState.VALIDATING || current == TaskState.EXECUTING || current == TaskState.RUNNING || current == TaskState.VERIFYING
            TaskState.BLOCKED_BY_ANDROID -> current == TaskState.VALIDATING || current == TaskState.EXECUTING || current == TaskState.RUNNING || current == TaskState.VERIFYING || current == TaskState.WAITING_FOR_PERMISSION || current == TaskState.AWAITING_PERMISSION || current == TaskState.WAITING_FOR_DEVICE_UNLOCK
            TaskState.TIMED_OUT -> true
            TaskState.FAILED -> true // Can fail from any active state
            TaskState.CANCELLED -> true // Can be cancelled at any time
            TaskState.CREATED -> false
        }

        if (isValid) {
            task.state = nextState
            if (reason != null && (nextState == TaskState.FAILED || nextState == TaskState.PARTIALLY_COMPLETED || nextState == TaskState.INCONCLUSIVE || nextState == TaskState.NOT_SUPPORTED || nextState == TaskState.BLOCKED_BY_ANDROID || nextState == TaskState.TIMED_OUT)) {
                task.failureReason = reason
            }
            AgentEventBus.emit(AgentEvent.TaskStateChanged(task.taskId, nextState, reason))
            return true
        } else {
            return false
        }
    }
}
