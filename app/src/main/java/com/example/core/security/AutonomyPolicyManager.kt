package com.example.core.security

import com.example.core.agent.ActionType
import com.example.core.agent.AgentAction
import com.example.core.agent.RiskLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 5 Configurable Agent Autonomy Levels (Part 11 Specification).
 */
enum class AutonomyLevel(val levelNumber: Int, val title: String, val description: String) {
    LEVEL_1_ALWAYS_ASK(1, "Level 1: Always Ask", "Requires explicit user confirmation before executing any tool action."),
    LEVEL_2_ASK_FOR_SENSITIVE(2, "Level 2: Ask for Sensitive Actions", "Automatically executes read-only/low-risk tools (R0/R1); requires confirmation for R2/R3."),
    LEVEL_3_APPROVED_WORKFLOWS(3, "Level 3: Approved Workflows", "Allows pre-approved multi-step workflows within defined bounds; asks for R3 external communication."),
    LEVEL_4_RESTRICTED_AUTONOMY(4, "Level 4: Restricted Autonomy", "Allows R0-R2 actions autonomously; strictly gates R3 external actions and blocks R4."),
    LEVEL_5_CUSTOM_POLICY(5, "Level 5: Custom Per-Tool & Per-App Policies", "Evaluates fine-grained per-tool and per-application permission rules.")
}

enum class ToolAccessPolicy {
    ALWAYS_ALLOW,
    ASK_EVERY_TIME,
    NEVER_ALLOW
}

data class TemporaryPermissionGrant(
    val key: String, // tool ActionType name or package name
    val expiresAtMs: Long
) {
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs >= expiresAtMs
}

sealed class AutonomyDecision {
    object AllowImmediately : AutonomyDecision()
    data class RequireUserConfirmation(val reason: String) : AutonomyDecision()
    data class DenyExecution(val reason: String) : AutonomyDecision()
}

/**
 * Agent Autonomy & Security Policy Manager (Part 11).
 * Controls autonomy levels 1-5, per-tool policies, per-app policies, temporary grants with expiration,
 * global automation pause, and immediate emergency stop.
 */
class AutonomyPolicyManager {

    private val _autonomyLevel = MutableStateFlow(AutonomyLevel.LEVEL_2_ASK_FOR_SENSITIVE)
    val autonomyLevel: StateFlow<AutonomyLevel> = _autonomyLevel.asStateFlow()

    private val _isEmergencyStopActive = MutableStateFlow(false)
    val isEmergencyStopActive: StateFlow<Boolean> = _isEmergencyStopActive.asStateFlow()

    private val _isGlobalAutomationPaused = MutableStateFlow(false)
    val isGlobalAutomationPaused: StateFlow<Boolean> = _isGlobalAutomationPaused.asStateFlow()

    private val perToolPolicies = mutableMapOf<ActionType, ToolAccessPolicy>()
    private val perAppPolicies = mutableMapOf<String, ToolAccessPolicy>()
    private val approvedWorkflowIds = mutableSetOf<String>()
    private val temporaryGrants = mutableMapOf<String, TemporaryPermissionGrant>()

    fun setAutonomyLevel(level: AutonomyLevel) {
        _autonomyLevel.value = level
    }

    fun setToolPolicy(actionType: ActionType, policy: ToolAccessPolicy) {
        synchronized(perToolPolicies) {
            perToolPolicies[actionType] = policy
        }
    }

    fun getToolPolicy(actionType: ActionType): ToolAccessPolicy? = synchronized(perToolPolicies) {
        perToolPolicies[actionType]
    }

    fun setAppPolicy(packageName: String, policy: ToolAccessPolicy) {
        synchronized(perAppPolicies) {
            perAppPolicies[packageName.lowercase().trim()] = policy
        }
    }

    fun getAppPolicy(packageName: String): ToolAccessPolicy? = synchronized(perAppPolicies) {
        perAppPolicies[packageName.lowercase().trim()]
    }

    fun approveWorkflowId(workflowId: String) {
        synchronized(approvedWorkflowIds) {
            approvedWorkflowIds.add(workflowId)
        }
    }

    fun grantTemporaryPermission(key: String, durationMs: Long) {
        val cleanKey = key.trim()
        if (cleanKey.isEmpty() || durationMs <= 0L) return
        synchronized(temporaryGrants) {
            temporaryGrants[cleanKey] = TemporaryPermissionGrant(
                key = cleanKey,
                expiresAtMs = System.currentTimeMillis() + durationMs
            )
        }
    }

    fun hasValidTemporaryGrant(key: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        synchronized(temporaryGrants) {
            val grant = temporaryGrants[key.trim()] ?: return false
            if (grant.isExpired(nowMs)) {
                temporaryGrants.remove(key.trim())
                return false
            }
            return true
        }
    }

    fun triggerEmergencyStop() {
        _isEmergencyStopActive.value = true
        _isGlobalAutomationPaused.value = true
        synchronized(temporaryGrants) {
            temporaryGrants.clear()
        }
    }

    fun clearEmergencyStop() {
        _isEmergencyStopActive.value = false
    }

    fun setGlobalAutomationPaused(paused: Boolean) {
        _isGlobalAutomationPaused.value = paused
    }

    /**
     * Evaluates whether [action] is allowed immediately, requires confirmation, or is denied.
     * R4 financial/prohibited actions and high-risk unconfirmed operations are never bypassed.
     */
    fun evaluateAction(
        action: AgentAction,
        workflowId: String? = null,
        nowMs: Long = System.currentTimeMillis()
    ): AutonomyDecision {
        // 1. Emergency stop blocks everything immediately
        if (_isEmergencyStopActive.value) {
            return AutonomyDecision.DenyExecution("Emergency Stop is active. All agent actions are halted.")
        }

        // 2. R4 Financial/Prohibited actions are strictly forbidden at all autonomy levels
        if (action.riskLevel == RiskLevel.R4_FINANCIAL_PROHIBITED) {
            return AutonomyDecision.DenyExecution("Action ${action.actionType.name} is classified as R4 (Financial/Prohibited) and is never allowed.")
        }

        // 3. Explicit per-app policy check
        val targetApp = action.target?.lowercase()?.trim()
        if (!targetApp.isNullOrEmpty()) {
            val appPolicy = getAppPolicy(targetApp)
            if (appPolicy == ToolAccessPolicy.NEVER_ALLOW) {
                return AutonomyDecision.DenyExecution("Target application '$targetApp' is blocked by per-app policy (NEVER_ALLOW).")
            }
            if (appPolicy == ToolAccessPolicy.ASK_EVERY_TIME) {
                return AutonomyDecision.RequireUserConfirmation("Application '$targetApp' is configured with ASK_EVERY_TIME policy.")
            }
        }

        // 4. Explicit per-tool policy check
        val toolPolicy = getToolPolicy(action.actionType)
        if (toolPolicy == ToolAccessPolicy.NEVER_ALLOW) {
            return AutonomyDecision.DenyExecution("Tool '${action.actionType.name}' is blocked by per-tool policy (NEVER_ALLOW).")
        }
        if (toolPolicy == ToolAccessPolicy.ASK_EVERY_TIME) {
            return AutonomyDecision.RequireUserConfirmation("Tool '${action.actionType.name}' requires confirmation per custom tool policy.")
        }

        // 5. High-risk R3 actions (sending messages, calls, deleting records) ALWAYS require confirmation
        if (action.riskLevel == RiskLevel.R3_EXTERNAL_COMMUNICATION || action.actionType == ActionType.ASK_USER_CONFIRMATION) {
            return AutonomyDecision.RequireUserConfirmation(
                "High-risk external action '${action.actionType.name}' (R3) requires explicit user confirmation."
            )
        }

        // 6. Check valid non-expired temporary grant for R0-R2 actions
        if (hasValidTemporaryGrant(action.actionType.name, nowMs)) {
            return AutonomyDecision.AllowImmediately
        }

        // 7. Evaluate by active AutonomyLevel
        return when (_autonomyLevel.value) {
            AutonomyLevel.LEVEL_1_ALWAYS_ASK -> {
                AutonomyDecision.RequireUserConfirmation("Autonomy Level 1 (Always Ask) requires confirmation for ${action.actionType.name}.")
            }
            AutonomyLevel.LEVEL_2_ASK_FOR_SENSITIVE -> {
                if (action.riskLevel == RiskLevel.R2_APPLICATION_STATE) {
                    AutonomyDecision.RequireUserConfirmation("Autonomy Level 2 requires confirmation for application state change (${action.actionType.name}).")
                } else {
                    AutonomyDecision.AllowImmediately
                }
            }
            AutonomyLevel.LEVEL_3_APPROVED_WORKFLOWS -> {
                val isApprovedWf = workflowId != null && synchronized(approvedWorkflowIds) { approvedWorkflowIds.contains(workflowId) }
                if (isApprovedWf || action.riskLevel == RiskLevel.R0_LOCAL_READONLY || action.riskLevel == RiskLevel.R1_LOW_IMPACT_REVERSIBLE) {
                    AutonomyDecision.AllowImmediately
                } else {
                    AutonomyDecision.RequireUserConfirmation("Action ${action.actionType.name} is outside pre-approved workflow scope.")
                }
            }
            AutonomyLevel.LEVEL_4_RESTRICTED_AUTONOMY -> {
                // R0, R1, R2 allowed; R3/R4 already handled above
                AutonomyDecision.AllowImmediately
            }
            AutonomyLevel.LEVEL_5_CUSTOM_POLICY -> {
                if (toolPolicy == ToolAccessPolicy.ALWAYS_ALLOW) {
                    AutonomyDecision.AllowImmediately
                } else if (action.riskLevel == RiskLevel.R0_LOCAL_READONLY || action.riskLevel == RiskLevel.R1_LOW_IMPACT_REVERSIBLE) {
                    AutonomyDecision.AllowImmediately
                } else {
                    AutonomyDecision.RequireUserConfirmation("Custom Policy requires confirmation for ${action.actionType.name}.")
                }
            }
        }
    }
}
