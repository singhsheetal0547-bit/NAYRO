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
    // Device & System Tools
    LAUNCH_APP,
    OPEN_SETTINGS_SECTION,
    GET_DEVICE_INFO,
    GET_BATTERY_STATE,
    GET_NETWORK_STATUS,
    GET_STORAGE_INFO,
    GET_DEVICE_TIME,
    SEARCH_INSTALLED_APPS,

    // Screen & Interaction Tools
    READ_SCREEN,
    INSPECT_ACCESSIBILITY_TREE,
    CLICK_ELEMENT,
    LONG_PRESS_ELEMENT,
    SET_TEXT,
    SCROLL_FORWARD,
    SCROLL_BACKWARD,
    NAVIGATE_BACK,
    TAKE_SCREENSHOT,
    INTERACT_COORDINATES,

    // Communication & Telephony Tools
    MESSAGE_DRAFT_SEND,
    CHECK_INCOMING_CALL,
    RESOLVE_CONTACT,
    MAKE_PHONE_CALL,
    READ_NOTIFICATIONS,

    // Workspace & File Tools
    FILE_BROWSE,
    FILE_READ_TEXT,
    FILE_WRITE_TEXT,
    FILE_SEARCH,
    PDF_EXTRACT_TEXT,
    DOC_SUMMARIZE,

    // Web & Research Agent Tools
    WEB_SEARCH,
    FETCH_WEB_PAGE,
    SUMMARIZE_WEB_CONTENT,

    // Coding Agent Tools
    CODE_INSPECT_FILE,
    CODE_EDIT_FILE,
    CODE_DIAGNOSE_ERROR,
    CODE_GENERATE_DOCS,

    // Skills & Sub-Agent Orchestration Tools
    EXECUTE_PROCEDURAL_SKILL,
    REGISTER_CUSTOM_SKILL,
    LIST_SKILLS,
    DELEGATE_SUBAGENT_TASK,
    CHECK_SUBAGENT_STATUS,
    AGGREGATE_SUBAGENT_RESULTS,

    // Local Utilities & Productivity
    CALCULATE_LOCAL,
    SAVE_NOTE,
    SAVE_MEMORY,
    CREATE_REMINDER,
    SCHEDULE_ALARM,
    CREATE_CALENDAR_EVENT,
    ASK_USER_CONFIRMATION,
    QUERY_LLM
}

enum class ConfirmationStatus {
    PENDING,
    CONFIRMED,
    REJECTED,
    EXPIRED,
    CANCELLED
}

enum class VerificationStatus {
    VERIFIED,
    FAILED,
    INCONCLUSIVE,
    NOT_SUPPORTED
}

data class VerificationEvidence(
    val preState: String? = null,
    val postState: String? = null,
    val observedChange: String
)

data class VerificationResult(
    val status: VerificationStatus,
    val details: String,
    val evidence: VerificationEvidence? = null
) {
    val isVerified: Boolean get() = status == VerificationStatus.VERIFIED
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
    val verificationStrategy: String = "VERIFY_STATE_CHANGE",
    val retryPolicy: String = "NO_RETRY",
    val idempotencyClassification: String = "IDEMPOTENT"
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
    val actionType: ActionType,
    val title: String,
    val description: String,
    val target: String?,
    val parameters: Map<String, String>,
    val riskLevel: RiskLevel,
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = System.currentTimeMillis() + 60_000L, // 60-second expiration timeout
    var status: ConfirmationStatus = ConfirmationStatus.PENDING
) {
    fun isExpired(): Boolean = System.currentTimeMillis() > expiresAt || status == ConfirmationStatus.EXPIRED
}
