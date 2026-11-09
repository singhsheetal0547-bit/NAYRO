package com.example.core.agent

data class ActionProposal(
    val id: String,
    val toolName: String,
    val description: String,
    val targetRecipientOrApp: String?,
    val parameters: Map<String, String>,
    val riskLevel: RiskLevel
)

data class ExecutionResult(
    val proposalId: String,
    val toolName: String,
    val isSuccess: Boolean,
    val verifiedOutput: String,
    val timestamp: Long = System.currentTimeMillis()
)
