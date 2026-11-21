package com.example.core.agent

import com.example.core.capability.CapabilityId
import java.util.UUID

enum class TaskState {
    CREATED,
    IDLE,
    LISTENING,
    PENDING,
    UNDERSTANDING,
    REASONING,
    PLANNING,
    VALIDATING,
    AWAITING_PERMISSION,
    WAITING_FOR_PERMISSION,
    WAITING_FOR_DEVICE_UNLOCK,
    AWAITING_CONFIRMATION,
    WAITING_FOR_CONFIRMATION,
    WAITING_FOR_USER_CONFIRMATION,
    WAITING_FOR_USER_INPUT,
    READY,
    RUNNING,
    EXECUTING,
    OBSERVING,
    VERIFYING,
    REPLANNING,
    RECOVERING,
    PAUSED,
    RETRYING,
    COMPLETED,
    PARTIALLY_COMPLETED,
    INCONCLUSIVE,
    NOT_SUPPORTED,
    BLOCKED_BY_ANDROID,
    TIMED_OUT,
    FAILED,
    CANCELLED;

    fun toActionExecutionState(): ActionExecutionState = when (this) {
        COMPLETED -> ActionExecutionState.SUCCESS
        AWAITING_PERMISSION, WAITING_FOR_PERMISSION -> ActionExecutionState.WAITING_FOR_PERMISSION
        WAITING_FOR_DEVICE_UNLOCK -> ActionExecutionState.WAITING_FOR_UNLOCK
        AWAITING_CONFIRMATION, WAITING_FOR_CONFIRMATION, WAITING_FOR_USER_CONFIRMATION -> ActionExecutionState.WAITING_FOR_CONFIRMATION
        INCONCLUSIVE, PARTIALLY_COMPLETED -> ActionExecutionState.INCONCLUSIVE
        FAILED, NOT_SUPPORTED, BLOCKED_BY_ANDROID, TIMED_OUT, CANCELLED -> ActionExecutionState.FAILED
        else -> ActionExecutionState.IN_PROGRESS
    }
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
    GET_CURRENT_LOCATION,
    TOGGLE_FLASHLIGHT,

    // Screen & Interaction Tools
    READ_SCREEN,
    INSPECT_ACCESSIBILITY_TREE,
    CLICK_ELEMENT,
    LONG_PRESS_ELEMENT,
    SET_TEXT,
    SCROLL_FORWARD,
    SCROLL_BACKWARD,
    SWIPE_SCREEN,
    NAVIGATE_BACK,
    NAVIGATE_HOME,
    TAKE_SCREENSHOT,
    MANAGE_SCREEN_RECORDING,
    INTERACT_COORDINATES,
    ANALYZE_IMAGE_VISION,
    CONTROL_MEDIA,

    // Communication & Telephony Tools
    MESSAGE_DRAFT_SEND,
    WHATSAPP_READ_CONVERSATION,
    SEND_SMS,
    READ_SMS,
    CHECK_INCOMING_CALL,
    MANAGE_CALL,
    READ_CALL_HISTORY,
    RESOLVE_CONTACT,
    SAVE_CONTACT,
    MAKE_PHONE_CALL,
    OPEN_PHONE_DIALER,
    COMPOSE_EMAIL,
    READ_EMAIL_NOTIFICATIONS,
    CHECK_COMMUNICATION_SPAM,
    READ_NOTIFICATIONS,
    SUMMARIZE_NOTIFICATIONS,
    CLASSIFY_NOTIFICATIONS,
    CREATE_TASK_FROM_NOTIFICATION,

    // Workspace & File Tools
    FILE_BROWSE,
    FILE_READ_TEXT,
    FILE_WRITE_TEXT,
    FILE_SEARCH,
    PDF_EXTRACT_TEXT,
    DOC_SUMMARIZE,
    COMPARE_DOCUMENTS,

    // Web & Research Agent Tools
    WEB_SEARCH,
    FETCH_WEB_PAGE,
    SUMMARIZE_WEB_CONTENT,

    // Coding Agent Tools
    CODE_INSPECT_FILE,
    CODE_EDIT_FILE,
    CODE_DIAGNOSE_ERROR,
    CODE_GENERATE_DOCS,

    // Skills, Workflows & Sub-Agent Orchestration Tools
    EXECUTE_PROCEDURAL_SKILL,
    REGISTER_CUSTOM_SKILL,
    LIST_SKILLS,
    EXECUTE_CROSS_APP_WORKFLOW,
    DELEGATE_SUBAGENT_TASK,
    CHECK_SUBAGENT_STATUS,
    AGGREGATE_SUBAGENT_RESULTS,

    // Local Utilities & Productivity
    CALCULATE_LOCAL,
    SAVE_NOTE,
    SEARCH_NOTES,
    DELETE_NOTE,
    SAVE_MEMORY,
    CREATE_REMINDER,
    SCHEDULE_ALARM,
    READ_CALENDAR_EVENTS,
    CREATE_CALENDAR_EVENT,
    UPDATE_CALENDAR_EVENT,
    DELETE_CALENDAR_EVENT,
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

enum class ActionExecutionState {
    SUCCESS,
    FAILED,
    IN_PROGRESS,
    WAITING_FOR_PERMISSION,
    WAITING_FOR_UNLOCK,
    WAITING_FOR_CONFIRMATION,
    INCONCLUSIVE
}

enum class VerificationStatus {
    VERIFIED,
    FAILED,
    INCONCLUSIVE,
    NOT_SUPPORTED,
    REQUIRES_PERMISSION,
    WAITING_FOR_UNLOCK,
    REQUIRES_CONFIRMATION,
    CANCELLED,
    TIMED_OUT,
    PARTIALLY_COMPLETED;

    fun toActionExecutionState(): ActionExecutionState = when (this) {
        VERIFIED -> ActionExecutionState.SUCCESS
        REQUIRES_PERMISSION -> ActionExecutionState.WAITING_FOR_PERMISSION
        WAITING_FOR_UNLOCK -> ActionExecutionState.WAITING_FOR_UNLOCK
        REQUIRES_CONFIRMATION -> ActionExecutionState.WAITING_FOR_CONFIRMATION
        INCONCLUSIVE, PARTIALLY_COMPLETED -> ActionExecutionState.INCONCLUSIVE
        FAILED, NOT_SUPPORTED, CANCELLED, TIMED_OUT -> ActionExecutionState.FAILED
    }
}

fun TaskState.toActionExecutionState(): ActionExecutionState = when (this) {
    TaskState.COMPLETED -> ActionExecutionState.SUCCESS
    TaskState.AWAITING_PERMISSION, TaskState.WAITING_FOR_PERMISSION -> ActionExecutionState.WAITING_FOR_PERMISSION
    TaskState.WAITING_FOR_DEVICE_UNLOCK -> ActionExecutionState.WAITING_FOR_UNLOCK
    TaskState.AWAITING_CONFIRMATION, TaskState.WAITING_FOR_CONFIRMATION, TaskState.WAITING_FOR_USER_CONFIRMATION -> ActionExecutionState.WAITING_FOR_CONFIRMATION
    TaskState.INCONCLUSIVE, TaskState.PARTIALLY_COMPLETED -> ActionExecutionState.INCONCLUSIVE
    TaskState.FAILED, TaskState.NOT_SUPPORTED, TaskState.BLOCKED_BY_ANDROID, TaskState.TIMED_OUT, TaskState.CANCELLED -> ActionExecutionState.FAILED
    else -> ActionExecutionState.IN_PROGRESS
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
    val executionState: ActionExecutionState get() = status.toActionExecutionState()
}

data class AgentAction(
    val actionId: String = UUID.randomUUID().toString(),
    val taskId: String = "task-default",
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
    val displayFields: List<Pair<String, String>> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = System.currentTimeMillis() + 60_000L, // 60-second expiration timeout
    var status: ConfirmationStatus = ConfirmationStatus.PENDING
) {
    fun isExpired(): Boolean = System.currentTimeMillis() > expiresAt || status == ConfirmationStatus.EXPIRED

    /**
     * Returns schema-derived, user-facing display fields without exposing internal enum names
     * or internal orchestration metadata.
     */
    fun getSchemaDisplayFields(): List<Pair<String, String>> {
        if (displayFields.isNotEmpty()) return displayFields
        return ConfirmationSchemaBuilder.buildDisplayFields(actionType, target, parameters)
    }
}

/**
 * Section 8 — Schema-driven Confirmation Screen Generator.
 * Produces clean, human-readable titles, descriptions, and structured fields for Phone Calls,
 * SMS, WhatsApp, and other high-risk actions without exposing internal identifiers like MAKE_PHONE_CALL.
 */
object ConfirmationSchemaBuilder {

    fun buildUserFriendlyTitle(actionType: ActionType): String {
        return when (actionType) {
            ActionType.MAKE_PHONE_CALL -> "Confirm Phone Call"
            ActionType.OPEN_PHONE_DIALER -> "Confirm Dialer Launch"
            ActionType.SEND_SMS -> "Confirm SMS Message"
            ActionType.MESSAGE_DRAFT_SEND,
            ActionType.ASK_USER_CONFIRMATION -> "Confirm WhatsApp Message"
            ActionType.MANAGE_CALL -> "Confirm Call Control"
            ActionType.COMPOSE_EMAIL -> "Confirm Email Composer"
            ActionType.MANAGE_SCREEN_RECORDING -> "Confirm Screen Recording"
            ActionType.SAVE_CONTACT -> "Confirm New Contact"
            ActionType.DELETE_NOTE -> "Confirm Note Deletion"
            ActionType.DELETE_CALENDAR_EVENT -> "Confirm Calendar Event Deletion"
            else -> "Confirm Action"
        }
    }

    fun buildDisplayFields(
        actionType: ActionType,
        target: String?,
        parameters: Map<String, String>,
        resolvedContactName: String? = null,
        resolvedPhoneNumber: String? = null
    ): List<Pair<String, String>> {
        return when (actionType) {
            ActionType.MAKE_PHONE_CALL -> {
                val rawTarget = (target ?: parameters["recipient"] ?: parameters["target"] ?: parameters["number"]).orEmpty().trim()
                val contactName = resolvedContactName
                    ?: parameters["contactName"]?.takeIf { it.isNotBlank() }
                    ?: if (rawTarget.any { it.isLetter() }) rawTarget else "Direct Phone Number"
                val phoneNum = resolvedPhoneNumber
                    ?: parameters["phoneNumber"]?.takeIf { it.isNotBlank() }
                    ?: parameters["number"]?.takeIf { it.isNotBlank() }
                    ?: rawTarget
                val callActionLabel = if (parameters["observeCallState"] == "true") {
                    "Place Outgoing Call & Observe Answer State"
                } else {
                    "Place Outgoing Phone Call"
                }
                listOf(
                    "Contact Name" to contactName,
                    "Phone Number" to phoneNum,
                    "Call Action" to callActionLabel
                )
            }

            ActionType.SEND_SMS -> {
                val rawRecipient = (target ?: parameters["recipient"]).orEmpty().trim()
                val recipientName = resolvedContactName
                    ?: parameters["contactName"]?.takeIf { it.isNotBlank() }
                    ?: rawRecipient.ifBlank { "Unknown Recipient" }
                val phoneNum = resolvedPhoneNumber
                    ?: parameters["phoneNumber"]?.takeIf { it.isNotBlank() }
                    ?: parameters["number"]?.takeIf { it.isNotBlank() }
                    ?: rawRecipient
                val msg = (parameters["message"] ?: parameters["draft"]).orEmpty()
                listOf(
                    "Recipient" to recipientName,
                    "Phone Number" to phoneNum,
                    "Message" to msg
                )
            }

            ActionType.MESSAGE_DRAFT_SEND,
            ActionType.ASK_USER_CONFIRMATION -> {
                val recipient = resolvedContactName
                    ?: (target ?: parameters["recipient"]).orEmpty().trim().ifBlank { "Unknown Recipient" }
                val msg = (parameters["message"] ?: parameters["draft"] ?: parameters["text"]).orEmpty()
                val attachment = parameters["attachment"]?.takeIf { it.isNotBlank() } ?: "None (Text Only)"
                listOf(
                    "Recipient" to recipient,
                    "Message" to msg,
                    "Attachment" to attachment
                )
            }

            ActionType.SAVE_CONTACT -> {
                val name = (parameters["name"] ?: target).orEmpty().trim().ifBlank { "New Contact" }
                val phone = (parameters["phone"] ?: parameters["number"] ?: resolvedPhoneNumber).orEmpty().trim()
                listOf(
                    "Contact Name" to name,
                    "Phone Number" to phone
                )
            }

            ActionType.MANAGE_CALL -> {
                val op = (parameters["operation"] ?: target ?: "END").uppercase()
                listOf(
                    "Call Action" to op
                )
            }

            else -> {
                val hiddenInternalKeys = setOf(
                    "condition",
                    "previousStepOutput",
                    "usePreviousStepOutput",
                    "observeCallState",
                    "fromPreviousStep"
                )
                parameters
                    .filterKeys { it !in hiddenInternalKeys }
                    .map { (k, v) -> k.replaceFirstChar { it.uppercase() } to v }
            }
        }
    }

    fun buildConfirmationFromAction(
        taskId: String,
        action: AgentAction,
        resolvedContactName: String? = null,
        resolvedPhoneNumber: String? = null
    ): PendingConfirmation {
        val fields = buildDisplayFields(
            actionType = action.actionType,
            target = action.target,
            parameters = action.parameters,
            resolvedContactName = resolvedContactName,
            resolvedPhoneNumber = resolvedPhoneNumber
        )
        val title = buildUserFriendlyTitle(action.actionType)
        val summaryLines = fields.joinToString("\n") { (label, value) -> "$label: $value" }
        return PendingConfirmation(
            taskId = taskId,
            actionId = action.actionId,
            actionType = action.actionType,
            title = title,
            description = "$summaryLines\n\nPlease confirm to proceed.",
            target = action.target,
            parameters = action.parameters,
            riskLevel = action.riskLevel,
            displayFields = fields
        )
    }
}

fun ActionType.toUserFriendlyDescription(
    target: String? = null,
    parameters: Map<String, String> = emptyMap()
): String {
    val recipient = parameters["recipient"]?.takeIf { it.isNotBlank() }
    val query = parameters["query"]?.takeIf { it.isNotBlank() }
    return when (this) {
        ActionType.LAUNCH_APP -> "Opening ${target ?: "application"}..."
        ActionType.OPEN_SETTINGS_SECTION -> "Opening ${parameters["section"] ?: target ?: "system"} settings..."
        ActionType.GET_DEVICE_INFO -> "Checking device status..."
        ActionType.GET_BATTERY_STATE -> "Checking battery level..."
        ActionType.GET_NETWORK_STATUS -> "Checking network connectivity..."
        ActionType.GET_STORAGE_INFO -> "Checking available storage..."
        ActionType.GET_DEVICE_TIME -> "Checking current local time..."
        ActionType.SEARCH_INSTALLED_APPS -> "Searching installed applications..."
        ActionType.GET_CURRENT_LOCATION -> "Reading current device location..."
        ActionType.TOGGLE_FLASHLIGHT -> "Toggling device flashlight..."
        ActionType.READ_SCREEN -> "Reading current screen content..."
        ActionType.INSPECT_ACCESSIBILITY_TREE -> "Inspecting screen layout..."
        ActionType.CLICK_ELEMENT -> "Tapping ${target ?: "screen element"}..."
        ActionType.LONG_PRESS_ELEMENT -> "Long-pressing ${target ?: "screen element"}..."
        ActionType.SET_TEXT -> "Entering text into ${target ?: "input field"}..."
        ActionType.SCROLL_FORWARD -> "Scrolling down on screen..."
        ActionType.SCROLL_BACKWARD -> "Scrolling up on screen..."
        ActionType.SWIPE_SCREEN -> "Swiping on screen..."
        ActionType.NAVIGATE_BACK -> "Navigating back..."
        ActionType.NAVIGATE_HOME -> "Returning to Home screen..."
        ActionType.TAKE_SCREENSHOT -> "Capturing screen snapshot..."
        ActionType.MANAGE_SCREEN_RECORDING -> "Managing screen recording (${parameters["operation"] ?: target ?: "status"})..."
        ActionType.INTERACT_COORDINATES -> "Interacting with screen coordinates..."
        ActionType.ANALYZE_IMAGE_VISION -> "Analyzing screen image..."
        ActionType.CONTROL_MEDIA -> "Controlling media playback (${parameters["operation"] ?: target ?: "play"})..."
        ActionType.MESSAGE_DRAFT_SEND -> if (recipient != null) {
            "Preparing your WhatsApp message for $recipient..."
        } else {
            "Preparing your WhatsApp message."
        }
        ActionType.WHATSAPP_READ_CONVERSATION -> "Reading WhatsApp conversation..."
        ActionType.SEND_SMS -> "Preparing SMS message${if (recipient != null) " for $recipient" else ""}..."
        ActionType.READ_SMS -> "Reading recent SMS messages..."
        ActionType.CHECK_INCOMING_CALL -> "Checking incoming call state..."
        ActionType.MANAGE_CALL -> "Managing active/incoming phone call (${parameters["operation"] ?: target ?: "status"})..."
        ActionType.READ_CALL_HISTORY -> "Reading recent call history..."
        ActionType.RESOLVE_CONTACT -> "Looking up contact ${query ?: target ?: ""}...".trim()
        ActionType.SAVE_CONTACT -> "Saving contact ${parameters["name"] ?: target ?: ""}...".trim()
        ActionType.MAKE_PHONE_CALL -> "Placing phone call${if (recipient != null) " to $recipient" else ""}..."
        ActionType.OPEN_PHONE_DIALER -> "Opening phone dialer${if (recipient != null || target != null) " for ${recipient ?: target}" else ""}..."
        ActionType.COMPOSE_EMAIL -> "Preparing email draft${if (recipient != null) " for $recipient" else ""}..."
        ActionType.READ_EMAIL_NOTIFICATIONS -> "Checking recent email notifications..."
        ActionType.CHECK_COMMUNICATION_SPAM -> "Checking calls & messages for spam or phishing risk..."
        ActionType.READ_NOTIFICATIONS -> "Reading active notifications..."
        ActionType.SUMMARIZE_NOTIFICATIONS -> "Summarizing your recent notifications..."
        ActionType.CLASSIFY_NOTIFICATIONS -> "Organizing notifications by priority..."
        ActionType.CREATE_TASK_FROM_NOTIFICATION -> "Creating reminder from notification..."
        ActionType.FILE_BROWSE -> "Browsing workspace files..."
        ActionType.FILE_READ_TEXT -> "Reading document text..."
        ActionType.FILE_WRITE_TEXT -> "Saving text to file..."
        ActionType.FILE_SEARCH -> "Searching for files${if (query != null) " matching \"$query\"" else ""}..."
        ActionType.PDF_EXTRACT_TEXT -> "Extracting text from PDF..."
        ActionType.DOC_SUMMARIZE -> "Summarizing document..."
        ActionType.COMPARE_DOCUMENTS -> "Comparing documents..."
        ActionType.WEB_SEARCH -> "Searching the web${if (query != null) " for \"$query\"" else ""}..."
        ActionType.FETCH_WEB_PAGE -> "Fetching web page content..."
        ActionType.SUMMARIZE_WEB_CONTENT -> "Summarizing web article..."
        ActionType.CODE_INSPECT_FILE -> "Inspecting source file..."
        ActionType.CODE_EDIT_FILE -> "Updating code file..."
        ActionType.CODE_DIAGNOSE_ERROR -> "Diagnosing error log..."
        ActionType.CODE_GENERATE_DOCS -> "Generating documentation..."
        ActionType.EXECUTE_PROCEDURAL_SKILL -> "Running saved skill workflow..."
        ActionType.REGISTER_CUSTOM_SKILL -> "Saving new custom skill..."
        ActionType.LIST_SKILLS -> "Listing available skills..."
        ActionType.EXECUTE_CROSS_APP_WORKFLOW -> "Running multi-step cross-app workflow..."
        ActionType.DELEGATE_SUBAGENT_TASK -> "Delegating task to specialist sub-agent..."
        ActionType.CHECK_SUBAGENT_STATUS -> "Checking specialist sub-agent progress..."
        ActionType.AGGREGATE_SUBAGENT_RESULTS -> "Combining specialist sub-agent results..."
        ActionType.CALCULATE_LOCAL -> "Calculating math expression..."
        ActionType.SAVE_NOTE -> "Saving note to your local notebook..."
        ActionType.SEARCH_NOTES -> "Searching your saved notes..."
        ActionType.DELETE_NOTE -> "Deleting note from notebook..."
        ActionType.SAVE_MEMORY -> "Saving preference to local memory..."
        ActionType.CREATE_REMINDER -> "Scheduling your local reminder..."
        ActionType.SCHEDULE_ALARM -> "Scheduling device alarm..."
        ActionType.READ_CALENDAR_EVENTS -> "Checking your calendar schedule..."
        ActionType.CREATE_CALENDAR_EVENT -> "Creating calendar event..."
        ActionType.UPDATE_CALENDAR_EVENT -> "Updating calendar event..."
        ActionType.DELETE_CALENDAR_EVENT -> "Removing calendar event..."
        ActionType.ASK_USER_CONFIRMATION -> "Waiting for your confirmation before proceeding."
        ActionType.QUERY_LLM -> "Understanding your request..."
    }
}

fun TaskState.toUserFriendlyLabel(): String {
    return when (this) {
        TaskState.CREATED, TaskState.PENDING, TaskState.READY -> "Ready"
        TaskState.IDLE -> "Idle"
        TaskState.LISTENING -> "Listening"
        TaskState.UNDERSTANDING, TaskState.REASONING -> "Understanding Request"
        TaskState.PLANNING -> "Planning Steps"
        TaskState.VALIDATING -> "Checking Safety & Permissions"
        TaskState.AWAITING_PERMISSION, TaskState.WAITING_FOR_PERMISSION -> "Needs Permission"
        TaskState.WAITING_FOR_DEVICE_UNLOCK -> "Waiting for Device Unlock"
        TaskState.AWAITING_CONFIRMATION, TaskState.WAITING_FOR_CONFIRMATION, TaskState.WAITING_FOR_USER_CONFIRMATION -> "Waiting for Confirmation"
        TaskState.WAITING_FOR_USER_INPUT -> "Waiting for Input"
        TaskState.RUNNING, TaskState.EXECUTING -> "In Progress"
        TaskState.OBSERVING -> "Observing Screen"
        TaskState.VERIFYING -> "Verifying Outcome"
        TaskState.REPLANNING, TaskState.RECOVERING, TaskState.RETRYING -> "Adjusting Plan"
        TaskState.PAUSED -> "Paused"
        TaskState.COMPLETED -> "Completed"
        TaskState.PARTIALLY_COMPLETED -> "Partially Completed"
        TaskState.INCONCLUSIVE -> "Unverified"
        TaskState.NOT_SUPPORTED -> "Not Supported"
        TaskState.BLOCKED_BY_ANDROID -> "Blocked by Android"
        TaskState.TIMED_OUT -> "Timed Out"
        TaskState.FAILED -> "Failed"
        TaskState.CANCELLED -> "Stopped"
    }
}

fun String.toUserFriendlyIntentLabel(): String {
    val normalized = this.trim().uppercase()
    ActionType.entries.firstOrNull { it.name == normalized }?.let { actionType ->
        return actionType.toUserFriendlyDescription().removeSuffix("...").removeSuffix(".")
    }
    return when (normalized) {
        "APP_LAUNCH" -> "Opening Application"
        "SCREEN_READ" -> "Reading Current Screen"
        "YOUTUBE_SEARCH" -> "Searching on YouTube"
        "SETTINGS_NAVIGATE" -> "Opening System Settings"
        "MESSAGE_DRAFT", "MESSAGE_DRAFT_SEND" -> "Preparing your WhatsApp message"
        "WHATSAPP_READ" -> "Reading WhatsApp Conversation"
        "PHONE_CALL" -> "Preparing Phone Call"
        "FLASHLIGHT_CONTROL" -> "Controlling Flashlight"
        "NOTIFICATION_READ" -> "Reading Notifications"
        "FILE_FIND" -> "Searching Local Files"
        "REMINDER_CREATE" -> "Scheduling Reminder"
        "CALENDAR_READ" -> "Checking Calendar Schedule"
        "CALENDAR_CREATE" -> "Creating Calendar Event"
        "CALENDAR_UPDATE" -> "Updating Calendar Event"
        "CALENDAR_DELETE" -> "Removing Calendar Event"
        "NOTE_CREATE" -> "Saving Local Note"
        "MEMORY_SAVE" -> "Saving to Local Memory"
        "LOCAL_TIME" -> "Checking Current Time"
        "LOCAL_CALCULATOR" -> "Calculating Expression"
        "GENERAL_AI_QUERY" -> "Understanding your request"
        "MULTI_STEP_COMPOUND_TASK" -> "Multi-Step Workflow"
        "DYNAMIC_INFERRED_WORKFLOW" -> "Adaptive Multi-Step Task"
        "SECURITY_KEY_UPDATE" -> "Updated Security Key"
        else -> this.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
    }
}

