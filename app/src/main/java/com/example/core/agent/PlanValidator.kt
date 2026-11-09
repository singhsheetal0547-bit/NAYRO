package com.example.core.agent

import android.content.Context
import com.example.core.accessibility.AccessibilityConnectionManager
import com.example.core.capability.CapabilityId
import com.example.core.capability.CapabilityManager
import com.example.core.permission.PermissionManager

sealed class ValidationResult {
    object Valid : ValidationResult()
    data class MissingCapability(val capabilityId: CapabilityId, val reason: String) : ValidationResult()
    data class MissingPermission(val permission: String, val reason: String) : ValidationResult()
    data class InvalidPlan(val reason: String) : ValidationResult()
}

class PlanValidator(
    private val context: Context,
    private val permissionManager: PermissionManager,
    private val capabilityManager: CapabilityManager
) {

    fun validatePlan(task: AgentTask): ValidationResult {
        if (task.actions.isEmpty()) {
            return ValidationResult.InvalidPlan("Task contains no executable actions.")
        }

        for (action in task.actions) {
            // Check R4 prohibition
            if (action.riskLevel == RiskLevel.R4_FINANCIAL_PROHIBITED) {
                return ValidationResult.InvalidPlan("Action '${action.actionType}' is classified as R4 (Financial/Prohibited) and cannot be executed.")
            }

            // Check Accessibility capability if required
            if (action.requiredCapability == CapabilityId.ACCESSIBILITY) {
                val accState = AccessibilityConnectionManager.checkStatus(context)
                if (accState != com.example.core.accessibility.AccessibilityConnectionState.OPERATIONAL &&
                    accState != com.example.core.accessibility.AccessibilityConnectionState.CONNECTED
                ) {
                    return ValidationResult.MissingCapability(
                        capabilityId = CapabilityId.ACCESSIBILITY,
                        reason = "Action '${action.actionType}' requires NAYRO Accessibility Service. Please enable it in Android Settings."
                    )
                }
            }

            // Check runtime permission if required
            action.requiredPermission?.let { perm ->
                if (!permissionManager.isPermissionGranted(perm)) {
                    return ValidationResult.MissingPermission(
                        permission = perm,
                        reason = "Action '${action.actionType}' requires permission '$perm'."
                    )
                }
            }
        }

        return ValidationResult.Valid
    }
}
