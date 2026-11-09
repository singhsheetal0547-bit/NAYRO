package com.example.core.accessibility

import com.example.core.agent.ActionType
import com.example.core.agent.AgentAction
import com.example.core.agent.ConfirmationStatus
import com.example.core.agent.PendingConfirmation
import com.example.core.agent.RiskLevel

sealed class GuardEvaluation {
    object Allowed : GuardEvaluation()
    data class RequiresConfirmation(val reason: String, val target: String?, val actionDetails: String) : GuardEvaluation()
    data class Blocked(val reason: String) : GuardEvaluation()
}

/**
 * Centralized Action Execution Guard.
 * Every Android action MUST pass through this security gate immediately prior to execution.
 * Validates schema, capability readiness, permissions, risk levels, and authentic user confirmation tokens.
 */
object ActionExecutionGuard {

    /**
     * Backward-compatible evaluation check for basic validation.
     */
    fun evaluate(
        actionType: String,
        target: String?,
        parameters: Map<String, String>,
        riskLevel: RiskLevel,
        isUserConfirmed: Boolean
    ): GuardEvaluation {
        if (riskLevel == RiskLevel.R4_FINANCIAL_PROHIBITED) {
            return GuardEvaluation.Blocked(
                "Financial transactions, money transfers, and purchases are strictly prohibited from autonomous execution."
            )
        }

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

    /**
     * Full hardened pipeline gate for AgentRuntime.
     */
    fun validateExecution(
        action: AgentAction,
        isCapabilityAvailable: Boolean,
        isPermissionGranted: Boolean,
        confirmation: PendingConfirmation? = null
    ): GuardEvaluation {
        // 1. Schema Validation
        if (action.actionType == ActionType.LAUNCH_APP && action.target.isNullOrBlank()) {
            return GuardEvaluation.Blocked("Malformed action: Target application package or label is required for LAUNCH_APP.")
        }
        if (action.actionType == ActionType.SET_TEXT && action.parameters.isEmpty()) {
            return GuardEvaluation.Blocked("Malformed action: SET_TEXT requires valid text parameters.")
        }

        // 2. Capability Check
        if (action.requiredCapability != null && !isCapabilityAvailable) {
            return GuardEvaluation.Blocked(
                "Required capability '${action.requiredCapability.name}' is not available or disconnected on this device."
            )
        }

        // 3. Permission Check
        if (action.requiredPermission != null && !isPermissionGranted) {
            return GuardEvaluation.Blocked(
                "Required Android permission '${action.requiredPermission}' is not granted."
            )
        }

        // 4. Strict Risk Level R4 Block
        if (action.riskLevel == RiskLevel.R4_FINANCIAL_PROHIBITED) {
            return GuardEvaluation.Blocked(
                "Financial transactions, money transfers, and purchases are strictly prohibited from autonomous execution."
            )
        }

        // 5. User Confirmation Verification for R3 and Sensitive Operations
        if (action.riskLevel.requiresConfirmation || action.actionType == ActionType.ASK_USER_CONFIRMATION) {
            if (confirmation == null) {
                val details = action.parameters.entries.joinToString(", ") { "${it.key}: ${it.value}" }
                return GuardEvaluation.RequiresConfirmation(
                    reason = "Action '${action.actionType}' requires explicit user confirmation.",
                    target = action.target,
                    actionDetails = details
                )
            }

            if (confirmation.isExpired()) {
                return GuardEvaluation.Blocked("Action confirmation expired before execution.")
            }

            if (confirmation.status == ConfirmationStatus.REJECTED) {
                return GuardEvaluation.Blocked("Action was explicitly rejected by user.")
            }

            if (confirmation.status != ConfirmationStatus.CONFIRMED) {
                val details = action.parameters.entries.joinToString(", ") { "${it.key}: ${it.value}" }
                return GuardEvaluation.RequiresConfirmation(
                    reason = "Action '${action.actionType}' has not been confirmed by user.",
                    target = action.target,
                    actionDetails = details
                )
            }

            // Cryptographic parameter binding check
            if (confirmation.actionId != action.actionId || confirmation.parameters != action.parameters) {
                return GuardEvaluation.Blocked("Confirmation parameter mismatch detected. Action was modified after proposal.")
            }
        }

        return GuardEvaluation.Allowed
    }
}
