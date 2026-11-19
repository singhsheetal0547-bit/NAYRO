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

            // Check Accessibility capability if required (fail-closed on errors)
            if (action.requiredCapability == CapabilityId.ACCESSIBILITY) {
                val accState = try {
                    AccessibilityConnectionManager.checkStatus(context)
                } catch (_: Exception) {
                    com.example.core.accessibility.AccessibilityConnectionState.DISCONNECTED
                }
                if (accState != com.example.core.accessibility.AccessibilityConnectionState.OPERATIONAL &&
                    accState != com.example.core.accessibility.AccessibilityConnectionState.CONNECTED
                ) {
                    return ValidationResult.MissingCapability(
                        capabilityId = CapabilityId.ACCESSIBILITY,
                        reason = "Action '${action.actionType}' requires NAYRO Accessibility Service. Please enable it in Android Settings."
                    )
                }
            }

            // Check runtime permission if required (strict fail-closed)
            action.requiredPermission?.let { perm ->
                val checkDetail = try {
                    permissionManager.checkPermissionFailClosed(perm)
                } catch (e: Exception) {
                    PermissionManager.PermissionVerificationDetail(
                        permission = perm,
                        outcome = PermissionManager.PermissionCheckOutcome.INCONCLUSIVE,
                        reason = "Unable to verify permission '$perm' (${e.javaClass.simpleName}). Failing closed."
                    )
                }
                if (!checkDetail.isGranted) {
                    return ValidationResult.MissingPermission(
                        permission = perm,
                        reason = "Action '${action.actionType}' blocked (${checkDetail.outcome.name}): ${checkDetail.reason}"
                    )
                }
            }
        }

        return ValidationResult.Valid
    }
}
