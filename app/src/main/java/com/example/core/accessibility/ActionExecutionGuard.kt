package com.example.core.accessibility

import com.example.core.agent.RiskLevel

sealed class GuardEvaluation {
    object Allowed : GuardEvaluation()
    data class RequiresConfirmation(val reason: String, val target: String?, val actionDetails: String) : GuardEvaluation()
    data class Blocked(val reason: String) : GuardEvaluation()
}

/**
 * Enforces risk policies and safeguards:
 * Prevents autonomous execution of R3 (external communications) without user confirmation,
 * and completely blocks R4 operations.
 */
object ActionExecutionGuard {

    fun evaluate(
        actionType: String,
        target: String?,
        parameters: Map<String, String>,
        riskLevel: RiskLevel,
        isUserConfirmed: Boolean
    ): GuardEvaluation {
        // Enforce R4 prohibited rule
        if (riskLevel == RiskLevel.R4_FINANCIAL_PROHIBITED) {
            return GuardEvaluation.Blocked(
                "Financial transactions, money transfers, and purchases are strictly prohibited from autonomous execution."
            )
        }

        // Enforce R3 external communication / destructive action rule
        if (riskLevel.requiresConfirmation && !isUserConfirmed) {
            val details = parameters.entries.joinToString(", ") { "${it.key}: ${it.value}" }
            return GuardEvaluation.RequiresConfirmation(
                reason = "Action '$actionType' requires explicit user authorization before execution.",
                target = target,
                actionDetails = details
            )
        }

        return GuardEvaluation.Allowed
    }
}
