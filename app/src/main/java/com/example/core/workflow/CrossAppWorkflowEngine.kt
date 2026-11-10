package com.example.core.workflow

import com.example.core.agent.ActionExecutionResult
import com.example.core.agent.ActionType
import com.example.core.agent.AgentAction
import com.example.core.agent.RiskLevel
import com.example.core.agent.TaskState
import com.example.core.agent.VerificationStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class WorkflowStepDefinition(
    val stepIndex: Int,
    val title: String,
    val actionType: ActionType,
    val target: String? = null,
    val parameters: Map<String, String> = emptyMap(),
    val riskLevel: RiskLevel = RiskLevel.R0_LOCAL_READONLY,
    val outputKey: String,
    val requiresPreviousOutputKey: String? = null
)

data class WorkflowInstance(
    val workflowId: String = UUID.randomUUID().toString(),
    val templateId: String,
    val title: String,
    val steps: List<WorkflowStepDefinition>,
    var currentStepIndex: Int = 0,
    var state: TaskState = TaskState.PENDING,
    val sharedContext: MutableMap<String, String> = mutableMapOf(),
    val stepHistory: MutableList<String> = mutableListOf(),
    var finalSummary: String? = null,
    val createdAtMs: Long = System.currentTimeMillis()
)

/**
 * Reusable Persistent Cross-App Workflow Orchestration Engine (Part 6).
 * Executes multi-application and multi-tool workflows with shared context transfer,
 * step dependencies, verification, pause/resume/cancel, and execution history.
 */
class CrossAppWorkflowEngine {

    private val _activeWorkflows = MutableStateFlow<List<WorkflowInstance>>(emptyList())
    val activeWorkflows: StateFlow<List<WorkflowInstance>> = _activeWorkflows.asStateFlow()

    private val _workflowHistory = MutableStateFlow<List<WorkflowInstance>>(emptyList())
    val workflowHistory: StateFlow<List<WorkflowInstance>> = _workflowHistory.asStateFlow()

    /**
     * Built-in Cross-App Workflow Templates covering all 6 canonical workflows in Part 6.
     */
    fun buildCalendarMeetingReminderWorkflow(): WorkflowInstance {
        return WorkflowInstance(
            templateId = "wf_calendar_to_reminder",
            title = "Calendar Meeting -> Pre-Meeting Reminder",
            steps = listOf(
                WorkflowStepDefinition(
                    stepIndex = 0,
                    title = "Read Upcoming Calendar Events",
                    actionType = ActionType.READ_CALENDAR_EVENTS,
                    parameters = mapOf("windowHours" to "48"),
                    outputKey = "calendar_events"
                ),
                WorkflowStepDefinition(
                    stepIndex = 1,
                    title = "Create Reminder 30m Before Meeting",
                    actionType = ActionType.CREATE_REMINDER,
                    parameters = mapOf("title" to "Upcoming Meeting Preparation (30m before)"),
                    riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE,
                    outputKey = "reminder_created",
                    requiresPreviousOutputKey = "calendar_events"
                )
            )
        )
    }

    fun buildNotificationToTaskAndReminderWorkflow(): WorkflowInstance {
        return WorkflowInstance(
            templateId = "wf_notification_to_task_reminder",
            title = "Read Notification -> Create Task & Reminder",
            steps = listOf(
                WorkflowStepDefinition(
                    stepIndex = 0,
                    title = "Read Authorized Notifications",
                    actionType = ActionType.READ_NOTIFICATIONS,
                    outputKey = "notification_content"
                ),
                WorkflowStepDefinition(
                    stepIndex = 1,
                    title = "Save Task Note from Notification",
                    actionType = ActionType.SAVE_NOTE,
                    parameters = mapOf("content" to "Follow-up task from notification"),
                    outputKey = "task_note",
                    requiresPreviousOutputKey = "notification_content"
                ),
                WorkflowStepDefinition(
                    stepIndex = 2,
                    title = "Create Reminder for Tomorrow",
                    actionType = ActionType.CREATE_REMINDER,
                    parameters = mapOf("title" to "Follow up on notification task"),
                    riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE,
                    outputKey = "reminder_result",
                    requiresPreviousOutputKey = "task_note"
                )
            )
        )
    }

    fun buildDocumentSummaryToNoteWorkflow(filename: String = "NAYRO_Workspace_Guide.md"): WorkflowInstance {
        return WorkflowInstance(
            templateId = "wf_doc_summary_to_note",
            title = "Find Document -> Summarize -> Save in Notes",
            steps = listOf(
                WorkflowStepDefinition(
                    stepIndex = 0,
                    title = "Read Workspace Document",
                    actionType = ActionType.FILE_READ_TEXT,
                    target = filename,
                    parameters = mapOf("filename" to filename),
                    outputKey = "doc_raw_text"
                ),
                WorkflowStepDefinition(
                    stepIndex = 1,
                    title = "Summarize Document",
                    actionType = ActionType.DOC_SUMMARIZE,
                    target = filename,
                    parameters = mapOf("filename" to filename),
                    outputKey = "doc_summary",
                    requiresPreviousOutputKey = "doc_raw_text"
                ),
                WorkflowStepDefinition(
                    stepIndex = 2,
                    title = "Save Summary in Personal Notes",
                    actionType = ActionType.SAVE_NOTE,
                    parameters = mapOf("content" to "Document Summary ($filename)"),
                    outputKey = "saved_note",
                    requiresPreviousOutputKey = "doc_summary"
                )
            )
        )
    }

    fun buildWhatsAppConfirmedSendWorkflow(recipient: String, message: String): WorkflowInstance {
        return WorkflowInstance(
            templateId = "wf_whatsapp_confirmed_send",
            title = "Resolve Contact -> Prepare WhatsApp Draft -> Confirm & Send",
            steps = listOf(
                WorkflowStepDefinition(
                    stepIndex = 0,
                    title = "Resolve Contact in Address Book",
                    actionType = ActionType.RESOLVE_CONTACT,
                    target = recipient,
                    parameters = mapOf("query" to recipient),
                    outputKey = "resolved_contact"
                ),
                WorkflowStepDefinition(
                    stepIndex = 1,
                    title = "Send Confirmed WhatsApp Message",
                    actionType = ActionType.MESSAGE_DRAFT_SEND,
                    target = recipient,
                    parameters = mapOf("recipient" to recipient, "message" to message),
                    riskLevel = RiskLevel.R3_EXTERNAL_COMMUNICATION,
                    outputKey = "whatsapp_send_status",
                    requiresPreviousOutputKey = "resolved_contact"
                )
            )
        )
    }

    fun buildDocumentToMeetingNoteWorkflow(filename: String = "NAYRO_Workspace_Guide.md"): WorkflowInstance {
        return WorkflowInstance(
            templateId = "wf_doc_to_meeting_note",
            title = "Extract Document Info -> Create Meeting Note -> Save",
            steps = listOf(
                WorkflowStepDefinition(
                    stepIndex = 0,
                    title = "Extract Document Text",
                    actionType = ActionType.PDF_EXTRACT_TEXT,
                    target = filename,
                    parameters = mapOf("filename" to filename),
                    outputKey = "extracted_info"
                ),
                WorkflowStepDefinition(
                    stepIndex = 1,
                    title = "Save Structured Meeting Note",
                    actionType = ActionType.SAVE_NOTE,
                    parameters = mapOf("content" to "Meeting Note from $filename"),
                    outputKey = "meeting_note_saved",
                    requiresPreviousOutputKey = "extracted_info"
                )
            )
        )
    }

    /**
     * Executes or resumes a [WorkflowInstance] step-by-step using [stepExecutor],
     * transferring output context between steps and halting accurately on failure/pause/cancellation.
     */
    suspend fun executeWorkflow(
        workflow: WorkflowInstance,
        stepExecutor: suspend (AgentAction) -> ActionExecutionResult
    ): WorkflowInstance {
        registerActive(workflow)
        workflow.state = TaskState.RUNNING

        while (workflow.currentStepIndex < workflow.steps.size) {
            if (workflow.state == TaskState.CANCELLED || workflow.state == TaskState.PAUSED) {
                break
            }

            val stepDef = workflow.steps[workflow.currentStepIndex]
            val reqKey = stepDef.requiresPreviousOutputKey
            if (reqKey != null && workflow.sharedContext[reqKey].isNullOrBlank()) {
                workflow.state = TaskState.FAILED
                workflow.finalSummary = "Step ${stepDef.stepIndex + 1} ('${stepDef.title}') missing dependency '$reqKey'."
                archiveWorkflow(workflow)
                return workflow
            }

            // Enrich step parameters with shared workflow context
            val enrichedParams = stepDef.parameters.toMutableMap()
            if (reqKey != null) {
                val depValue = workflow.sharedContext[reqKey].orEmpty()
                enrichedParams["previousStepOutput"] = depValue
                if (stepDef.actionType == ActionType.SAVE_NOTE) {
                    val baseLabel = enrichedParams["content"] ?: "Workflow Output"
                    enrichedParams["content"] = "$baseLabel: ${depValue.take(300)}"
                }
            }

            val action = AgentAction(
                taskId = workflow.workflowId,
                actionType = stepDef.actionType,
                target = stepDef.target,
                parameters = enrichedParams,
                riskLevel = stepDef.riskLevel
            )

            val result = stepExecutor(action)
            val status = result.status ?: if (result.isSuccess) VerificationStatus.VERIFIED else VerificationStatus.FAILED

            workflow.stepHistory.add(
                "Step ${stepDef.stepIndex + 1} (${stepDef.title}) -> ${status.name}: ${result.outputMessage}"
            )

            if (status == VerificationStatus.VERIFIED && result.isSuccess) {
                workflow.sharedContext[stepDef.outputKey] = result.outputMessage
                workflow.currentStepIndex++
            } else {
                workflow.state = when {
                    status == VerificationStatus.REQUIRES_PERMISSION -> TaskState.WAITING_FOR_PERMISSION
                    status == VerificationStatus.REQUIRES_CONFIRMATION -> TaskState.WAITING_FOR_CONFIRMATION
                    workflow.currentStepIndex > 0 -> TaskState.PARTIALLY_COMPLETED
                    status == VerificationStatus.INCONCLUSIVE -> TaskState.INCONCLUSIVE
                    status == VerificationStatus.NOT_SUPPORTED -> TaskState.NOT_SUPPORTED
                    else -> TaskState.FAILED
                }
                workflow.finalSummary = workflow.stepHistory.joinToString("\n")
                archiveWorkflow(workflow)
                return workflow
            }
        }

        if (workflow.currentStepIndex == workflow.steps.size) {
            workflow.state = TaskState.COMPLETED
            workflow.finalSummary = workflow.stepHistory.joinToString("\n")
            archiveWorkflow(workflow)
        }
        return workflow
    }

    fun getBuiltInTemplates(): List<WorkflowInstance> = listOf(
        buildCalendarMeetingReminderWorkflow(),
        buildNotificationToTaskAndReminderWorkflow(),
        buildDocumentSummaryToNoteWorkflow(),
        buildWhatsAppConfirmedSendWorkflow("Contact", "Hello"),
        buildDocumentToMeetingNoteWorkflow()
    )

    suspend fun executeTemplateById(
        templateId: String,
        parameters: Map<String, String> = emptyMap(),
        stepExecutor: suspend (AgentAction) -> ActionExecutionResult
    ): WorkflowInstance {
        val filename = parameters["filename"] ?: "NAYRO_Workspace_Guide.md"
        val recipient = parameters["recipient"] ?: "Contact"
        val message = parameters["message"] ?: "Hello"
        val wf = when (templateId) {
            "wf_calendar_to_reminder" -> buildCalendarMeetingReminderWorkflow()
            "wf_notification_to_task_reminder" -> buildNotificationToTaskAndReminderWorkflow()
            "wf_whatsapp_confirmed_send" -> buildWhatsAppConfirmedSendWorkflow(recipient, message)
            "wf_doc_to_meeting_note" -> buildDocumentToMeetingNoteWorkflow(filename)
            else -> buildDocumentSummaryToNoteWorkflow(filename)
        }
        return executeWorkflow(wf, stepExecutor)
    }

    fun cancelWorkflow(workflowId: String): Boolean {
        val found = _activeWorkflows.value.find { it.workflowId == workflowId } ?: return false
        found.state = TaskState.CANCELLED
        found.finalSummary = "Cancelled by user at step ${found.currentStepIndex + 1}."
        archiveWorkflow(found)
        return true
    }

    fun cancelAllActiveWorkflows() {
        val current = _activeWorkflows.value.toList()
        current.forEach { wf ->
            wf.state = TaskState.CANCELLED
            wf.finalSummary = "Cancelled by user."
            archiveWorkflow(wf)
        }
    }

    private fun registerActive(workflow: WorkflowInstance) {
        _activeWorkflows.value = _activeWorkflows.value.filterNot { it.workflowId == workflow.workflowId } + workflow
    }

    private fun archiveWorkflow(workflow: WorkflowInstance) {
        _activeWorkflows.value = _activeWorkflows.value.filterNot { it.workflowId == workflow.workflowId }
        _workflowHistory.value = (listOf(workflow) + _workflowHistory.value).take(30)
    }
}
