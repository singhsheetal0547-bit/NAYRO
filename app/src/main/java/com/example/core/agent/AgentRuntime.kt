package com.example.core.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * NAYRO Core Agent Runtime Skeleton.
 * Manages request lifecycle, intent classification routing, tool validation, and verification flow.
 */
class AgentRuntime {

    private val _currentState = MutableStateFlow<AgentState>(AgentState.Idle)
    val currentState: StateFlow<AgentState> = _currentState

    private val _lastExecutionResult = MutableStateFlow<ExecutionResult?>(null)
    val lastExecutionResult: StateFlow<ExecutionResult?> = _lastExecutionResult

    fun setState(state: AgentState) {
        _currentState.value = state
    }

    fun submitUserRequest(prompt: String) {
        // Lifecycle Step 1: Capture User Request
        _currentState.value = AgentState.Thinking
        // Step 01 Architecture Skeleton placeholder:
        // Will be wired to AI Provider Manager & Native Tools in subsequent steps.
    }

    fun confirmAction(proposal: ActionProposal) {
        _currentState.value = AgentState.ExecutingAction(proposal.toolName)
    }

    fun cancelAction() {
        _currentState.value = AgentState.Idle
    }

    fun recordExecutionResult(result: ExecutionResult) {
        _lastExecutionResult.value = result
        _currentState.value = AgentState.Completed(result.verifiedOutput)
    }
}
