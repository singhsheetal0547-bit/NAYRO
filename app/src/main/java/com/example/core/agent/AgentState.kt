package com.example.core.agent

sealed class AgentState {
    object Idle : AgentState()
    object StandbyListening : AgentState()
    object UserListening : AgentState()
    object Transcribing : AgentState()
    object Thinking : AgentState()
    data class Speaking(val text: String) : AgentState()
    data class ProposingAction(val proposal: ActionProposal) : AgentState()
    data class PlanningTask(val task: AgentTask) : AgentState()
    data class WaitingForConfirmation(val confirmation: PendingConfirmation) : AgentState()
    data class WaitingForPermission(val permission: String, val reason: String) : AgentState()
    data class ExecutingTask(val task: AgentTask, val currentAction: AgentAction, val step: Int, val totalSteps: Int) : AgentState()
    data class ExecutingAction(val toolName: String) : AgentState()
    data class Completed(val responseText: String) : AgentState()
    data class Cancelled(val reason: String) : AgentState()
    data class Error(val message: String) : AgentState()
}
