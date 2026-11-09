package com.example.core.agent

sealed class AgentState {
    object Idle : AgentState()
    object StandbyListening : AgentState()
    object UserListening : AgentState()
    object Transcribing : AgentState()
    object Thinking : AgentState()
    data class Speaking(val text: String) : AgentState()
    data class ProposingAction(val proposal: ActionProposal) : AgentState()
    data class ExecutingAction(val toolName: String) : AgentState()
    data class Completed(val responseText: String) : AgentState()
    data class Error(val message: String) : AgentState()
}
