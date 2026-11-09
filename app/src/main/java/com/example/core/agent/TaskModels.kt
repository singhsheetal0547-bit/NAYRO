package com.example.core.agent

import com.example.core.capability.CapabilityId
import java.util.UUID

enum class TaskState {
    CREATED,
    UNDERSTANDING,
    PLANNING,
    VALIDATING,
    WAITING_FOR_PERMISSION,
    WAITING_FOR_CONFIRMATION,
    READY,
    EXECUTING,
    VERIFYING,
    PAUSED,
    RETRYING,
    COMPLETED,
    FAILED,
    CANCELLED
}

enum class ActionType {
    LAUNCH_APP,
    OPEN_SETTINGS_SECTION,
    READ_SCREEN,
    CLICK_ELEMENT,
    SET_TEXT,
    SCROLL_FORWARD,
    SCROLL_BACKWARD,
    NAVIGATE_BACK,
    CALCULATE_LOCAL,
    GET_DEVICE_TIME,
    SAVE_NOTE,
    SAVE_MEMORY,
    ASK_USER_CONFIRMATION,
    QUERY_LLM
}

data class AgentAction(
    val actionId: String = UUID.randomUUID().toString(),
    val taskId: String,
    val actionType: ActionType,
    val target: String? = null,
    val parameters: Map<String, String> = emptyMap(),
    val requiredCapability: CapabilityId? = null,
    val requiredPermission: String? = null,
    val riskLevel: RiskLevel = RiskLevel.R0_LOCAL_READONLY,
    val timeoutMs: Long = 5000L,
    val verificationStrategy: String = "VERIFY_STATE_CHANGE"
)

data class AgentTask(
    val taskId: String = UUID.randomUUID().toString(),
    val userInstruction: String,
    val resolvedIntent: String,
    val actions: List<AgentAction>,
    var currentActionIndex: Int = 0,
    var state: TaskState = TaskState.CREATED,
    var failureReason: String? = null,
    var finalResult: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    val currentAction: AgentAction?
        get() = actions.getOrNull(currentActionIndex)

    val progressPercentage: Float
        get() = if (actions.isEmpty()) 1f else (currentActionIndex.toFloat() / actions.size.toFloat()).coerceIn(0f, 1f)
}

data class PendingConfirmation(
    val taskId: String,
    val actionId: String,
    val title: String,
    val description: String,
    val target: String?,
    val parameters: Map<String, String>,
    val riskLevel: RiskLevel,
    val createdAt: Long = System.currentTimeMillis()
)
