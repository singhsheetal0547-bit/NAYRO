package com.example.core.agent

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed class AgentEvent {
    data class TaskCreated(val task: AgentTask) : AgentEvent()
    data class TaskStateChanged(val taskId: String, val newState: TaskState, val message: String? = null) : AgentEvent()
    data class ActionStarted(val taskId: String, val action: AgentAction, val step: Int, val totalSteps: Int) : AgentEvent()
    data class ActionCompleted(val taskId: String, val actionId: String, val output: String) : AgentEvent()
    data class ActionFailed(val taskId: String, val actionId: String, val error: String) : AgentEvent()
    data class ConfirmationNeeded(val confirmation: PendingConfirmation) : AgentEvent()
    data class TaskFinished(val taskId: String, val isSuccess: Boolean, val result: String) : AgentEvent()
}

object AgentEventBus {
    private val _events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    fun emit(event: AgentEvent) {
        _events.tryEmit(event)
    }
}
