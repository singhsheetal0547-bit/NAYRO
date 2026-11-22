package com.example.core.agent

import android.content.Context
import com.example.core.accessibility.ActionExecutionGuard
import com.example.core.accessibility.AppNavigationManager
import com.example.core.accessibility.GuardEvaluation
import com.example.core.automation.AutomationEngine
import com.example.core.capability.CapabilityManager
import com.example.core.memory.ContinuationResolution
import com.example.core.memory.ConversationContinuityManager
import com.example.core.memory.MemoryManager
import com.example.core.memory.NotebookManager
import com.example.core.memory.PendingSlotContinuation
import com.example.core.permission.PermissionManager
import com.example.core.proactive.ProactiveIntelligenceEngine
import com.example.core.provider.AIProviderManager
import com.example.core.screen.ScreenIntelligenceManager
import com.example.core.security.AutonomyDecision
import com.example.core.security.AutonomyPolicyManager
import com.example.core.service.DeviceOperatingStateManager
import com.example.core.service.LockScreenPolicyDecision
import com.example.core.skills.SkillManager
import com.example.core.subagent.SubAgentManager
import com.example.core.telephony.CallIntelligenceManager
import com.example.core.workflow.CrossAppWorkflowEngine
import com.example.core.workspace.WorkspaceManager
import com.example.data.local.entities.TaskHistoryEntity
import com.example.data.repository.NayroRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull

data class AgentExecutionSummary(
    val task: AgentTask,
    val isSuccess: Boolean,
    val message: String,
    val verificationStatus: VerificationStatus = VerificationStatus.VERIFIED
) {
    val executionState: ActionExecutionState
        get() = verificationStatus.toActionExecutionState()
}

typealias UnifiedAgentRuntime = AgentRuntime

/**
 * Authoritative Agent Runtime Engine for NAYRO (Sections 4, 17, 18).
 * Unifies AgentBrain planning/reasoning with ConversationContinuityManager multi-turn slot filling,
 * AutonomyPolicyManager (Levels 1-5), permission validation, R0-R4 risk confirmation,
 * ActionExecutor execution, strict ActionVerifier state verification, and durable step checkpointing.
 * Never marks a task COMPLETED or isSuccess=true when any step is FAILED, INCONCLUSIVE, or NOT_SUPPORTED.
 */
class AgentRuntime(
    private val context: Context,
    private val repository: NayroRepository,
    private val permissionManager: PermissionManager,
    private val capabilityManager: CapabilityManager,
    private val screenIntelligenceManager: ScreenIntelligenceManager,
    private val memoryManager: MemoryManager,
    private val notebookManager: NotebookManager,
    private val providerManager: AIProviderManager,
    val workspaceManager: WorkspaceManager = WorkspaceManager(context),
    val skillManager: SkillManager = SkillManager(),
    val subAgentManager: SubAgentManager = SubAgentManager(),
    val callIntelligenceManager: CallIntelligenceManager = CallIntelligenceManager(context),
    val autonomyPolicyManager: AutonomyPolicyManager = AutonomyPolicyManager(),
    val continuityManager: ConversationContinuityManager = ConversationContinuityManager(),
    val workflowEngine: CrossAppWorkflowEngine = CrossAppWorkflowEngine(),
    val proactiveEngine: ProactiveIntelligenceEngine = ProactiveIntelligenceEngine(),
    val automationEngine: AutomationEngine = AutomationEngine(context),
    val operatingStateManager: DeviceOperatingStateManager = DeviceOperatingStateManager(context)
) {

    private val _currentState = MutableStateFlow<AgentState>(AgentState.Idle)
    val currentState: StateFlow<AgentState> = _currentState.asStateFlow()

    private val _currentTask = MutableStateFlow<AgentTask?>(null)
    val currentTask: StateFlow<AgentTask?> = _currentTask.asStateFlow()

    private val stateManager = TaskStateManager()
    private val planValidator = PlanValidator(context, permissionManager, capabilityManager)
    val confirmationManager = ConfirmationManager()
    val conversationalEngine = ConversationalIntelligenceEngine()

    val actionExecutor = ActionExecutor(
        context = context,
        screenIntelligenceManager = screenIntelligenceManager,
        memoryManager = memoryManager,
        notebookManager = notebookManager,
        providerManager = providerManager,
        workspaceManager = workspaceManager,
        skillManager = skillManager,
        subAgentManager = subAgentManager,
        callIntelligenceManager = callIntelligenceManager,
        workflowEngine = workflowEngine,
        automationEngine = automationEngine,
        conversationalEngine = conversationalEngine
    )

    val agentBrain = AgentBrain(
        providerManager = providerManager,
        actionExecutor = actionExecutor,
        workspaceManager = workspaceManager,
        skillManager = skillManager,
        subAgentManager = subAgentManager
    )

    private val actionVerifier = ActionVerifier(screenIntelligenceManager)

    private val completedSideEffectSignatures = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val uncertainExternalActionSignatures = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    @Volatile
    var assistantName: String = com.example.core.identity.AssistantIdentityStore.sharedInstance?.getDisplayName()
        ?: com.example.core.identity.AssistantIdentityProfile.DEFAULT_DISPLAY_NAME
        private set

    @Volatile
    private var isTaskCancelled = false

    @Volatile
    private var isTaskPaused = false

    @Volatile
    var lastHeartbeatTimestampMs: Long = System.currentTimeMillis()
        private set

    @Volatile
    private var lastDispatchedInstruction: String = ""

    @Volatile
    private var lastDispatchedAtMs: Long = 0L

    @Volatile
    var pendingPermissionBlockedTask: AgentTask? = null
        private set

    @Volatile
    var pendingRequiredPermission: String? = null
        private set

    @Volatile
    var pendingUnlockBlockedTask: AgentTask? = null
        private set

    @Volatile
    var pendingUnlockBlockedReason: String? = null
        private set

    @Volatile
    var permissionDecisionInvocationCount: Int = 0
        private set

    @Volatile
    var globalRefreshCalledDuringPermissionDecision: Boolean = false
        private set

    @Volatile
    var persistedPendingPermissionDecision: Pair<String, Boolean>? = null
        private set

    fun setAssistantIdentity(newName: String) {
        val clean = newName.trim().ifEmpty { com.example.core.identity.AssistantIdentityProfile.DEFAULT_DISPLAY_NAME }
        assistantName = clean
        com.example.core.identity.AssistantIdentityStore.sharedInstance?.updateDisplayName(clean, autoSyncWakePhrases = true)
    }

    fun recordHeartbeat() {
        lastHeartbeatTimestampMs = System.currentTimeMillis()
    }

    fun isHeartbeatHealthy(maxStaleMs: Long = 60_000L): Boolean {
        return (System.currentTimeMillis() - lastHeartbeatTimestampMs) <= maxStaleMs
    }

    /**
     * Reports which of NAYRO's three Execution Domains ([ExecutionDomain.DEVICE],
     * [ExecutionDomain.VOICE], [ExecutionDomain.COMMUNICATION]) are coordinated by this
     * single unified [AgentRuntime] for [task].
     */
    fun getActiveDomainsForTask(task: AgentTask, invokedViaVoice: Boolean = false): Set<ExecutionDomain> {
        val domains = linkedSetOf<ExecutionDomain>()
        if (invokedViaVoice) {
            domains.add(ExecutionDomain.VOICE)
        }
        for (action in task.actions) {
            domains.add(ConversationalIntelligenceEngine.domainForAction(action.actionType))
        }
        return domains
    }

    fun setState(newState: AgentState) {
        _currentState.value = newState
    }

    fun isPaused(): Boolean = isTaskPaused

    fun pauseCurrentTask() {
        isTaskPaused = true
        _currentTask.value?.let { task ->
            stateManager.transition(task, TaskState.PAUSED, "Task paused by user.")
        }
    }

    fun resumeCurrentTask() {
        isTaskPaused = false
        _currentTask.value?.let { task ->
            if (task.state == TaskState.PAUSED) {
                stateManager.transition(task, TaskState.EXECUTING, "Task resumed by user.")
            }
        }
    }

    /**
     * Section 12 & Phase 4 — Permission Intelligence & Automatic Task Resume:
     * Records a task paused due to a missing Android permission/capability so it can be automatically
     * resumed as soon as the permission is granted.
     */
    fun recordPermissionBlockedTask(task: AgentTask, permissionOrCapability: String) {
        pendingPermissionBlockedTask = task
        pendingRequiredPermission = permissionOrCapability
    }

    /**
     * Persists a permission decision across Activity/process recreation so if the permission callback
     * arrives before AgentRuntime is attached, it can be consumed on restoration.
     */
    fun persistPendingPermissionDecision(permission: String, granted: Boolean) {
        persistedPendingPermissionDecision = permission to granted
        try {
            context.getSharedPreferences("nayro_runtime_checkpoint", Context.MODE_PRIVATE)
                .edit()
                .putString("pending_perm_name", permission)
                .putBoolean("pending_perm_granted", granted)
                .putBoolean("has_pending_perm", true)
                .apply()
        } catch (_: Exception) {}
    }

    suspend fun consumePersistedPermissionDecision(
        customExecutor: (suspend (AgentTask) -> AgentExecutionSummary)? = null
    ): AgentExecutionSummary? {
        val inMem = persistedPendingPermissionDecision
        if (inMem != null) {
            persistedPendingPermissionDecision = null
            return onPermissionDecision(inMem.first, inMem.second, customExecutor)
        }
        return try {
            val prefs = context.getSharedPreferences("nayro_runtime_checkpoint", Context.MODE_PRIVATE)
            if (prefs.getBoolean("has_pending_perm", false)) {
                val perm = prefs.getString("pending_perm_name", "") ?: ""
                val granted = prefs.getBoolean("pending_perm_granted", false)
                prefs.edit().remove("has_pending_perm").apply()
                if (perm.isNotBlank()) {
                    onPermissionDecision(perm, granted, customExecutor)
                } else null
            } else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Targeted single-permission check (Fail-Closed).
     * Never calls PermissionManager.refreshAllPermissions(), permissionSetupManager.refreshReport(),
     * or capabilityManager.evaluateAll().
     * Returns structured [PermissionManager.PermissionVerificationDetail] (GRANTED, DENIED, INCONCLUSIVE, UNAVAILABLE).
     */
    fun verifySinglePermissionDetailed(permission: String): PermissionManager.PermissionVerificationDetail {
        val clean = permission.trim()
        if (clean.isEmpty()) {
            return PermissionManager.PermissionVerificationDetail(
                permission = permission,
                outcome = PermissionManager.PermissionCheckOutcome.UNAVAILABLE,
                reason = "Permission name is blank or unavailable."
            )
        }
        return try {
            permissionManager.checkPermissionFailClosed(clean)
        } catch (e: Exception) {
            PermissionManager.PermissionVerificationDetail(
                permission = clean,
                outcome = PermissionManager.PermissionCheckOutcome.INCONCLUSIVE,
                reason = "Fail-closed: targeted permission verification threw ${e.javaClass.simpleName}."
            )
        }
    }

    /**
     * Targeted single-permission check (Phase 4, strictly FAIL-CLOSED).
     * Never returns true when verification throws an exception or is inconclusive/unavailable.
     */
    fun verifySinglePermissionTargeted(permission: String): Boolean {
        return verifySinglePermissionDetailed(permission).isGranted
    }

    /**
     * Phase 4 — Targeted Permission Decision Callback:
     * Automatically resumes the pending permission-blocked task when the required
     * permission is granted, or transitions it cleanly to `BLOCKED_BY_ANDROID` if denied.
     * Strictly avoids full global permission/capability re-evaluation inside the callback.
     */
    suspend fun onPermissionDecision(
        permission: String,
        granted: Boolean,
        customExecutor: (suspend (AgentTask) -> AgentExecutionSummary)? = null
    ): AgentExecutionSummary? {
        permissionDecisionInvocationCount++
        globalRefreshCalledDuringPermissionDecision = false
        val blockedTask = pendingPermissionBlockedTask
        if (blockedTask == null) {
            persistPendingPermissionDecision(permission, granted)
            return null
        }
        if (!granted) {
            pendingPermissionBlockedTask = null
            pendingRequiredPermission = null
            val reason = "Permission '$permission' was denied by user. Task '${blockedTask.userInstruction}' cannot proceed without '$permission'."
            stateManager.transition(blockedTask, TaskState.BLOCKED_BY_ANDROID, reason)
            _currentState.value = AgentState.Error(reason)
            return AgentExecutionSummary(
                task = blockedTask,
                isSuccess = false,
                message = reason,
                verificationStatus = VerificationStatus.REQUIRES_PERMISSION
            )
        }
        return resumePendingTaskAfterPermissionGrant(customExecutor)
    }

    /**
     * Resumes the pending permission-blocked task from its checkpoint without repeating already
     * completed non-idempotent side-effect steps.
     */
    suspend fun resumePendingTaskAfterPermissionGrant(
        customExecutor: (suspend (AgentTask) -> AgentExecutionSummary)? = null
    ): AgentExecutionSummary? {
        val blockedTask = pendingPermissionBlockedTask ?: return null
        pendingPermissionBlockedTask = null
        pendingRequiredPermission = null
        stateManager.transition(blockedTask, TaskState.READY, "Permission granted; automatically resuming task.")
        if (customExecutor != null) {
            return customExecutor(blockedTask)
        }
        return executePlannedTask(blockedTask, blockedTask.userInstruction)
    }

    /**
     * Phase 2 — Lock-Aware Task Suspension & Automatic Resume:
     * Records a UI-dependent task paused in `WAITING_FOR_DEVICE_UNLOCK` when the device is locked
     * so it can automatically resume from its exact checkpoint once `onDeviceUnlocked()` is triggered.
     */
    fun recordUnlockBlockedTask(task: AgentTask, reason: String) {
        pendingUnlockBlockedTask = task
        pendingUnlockBlockedReason = reason
    }

    suspend fun onDeviceUnlocked(
        customExecutor: (suspend (AgentTask) -> AgentExecutionSummary)? = null
    ): AgentExecutionSummary? {
        operatingStateManager.onDeviceUnlocked()
        val blockedTask = pendingUnlockBlockedTask ?: return null
        pendingUnlockBlockedTask = null
        pendingUnlockBlockedReason = null
        stateManager.transition(blockedTask, TaskState.READY, "Device unlocked; automatically resuming task from checkpoint step ${blockedTask.currentActionIndex + 1}.")
        if (customExecutor != null) {
            return customExecutor(blockedTask)
        }
        return executePlannedTask(blockedTask, blockedTask.userInstruction)
    }

    fun hasSideEffectAlreadyExecuted(taskId: String, actionId: String): Boolean {
        return completedSideEffectSignatures.contains("$taskId:$actionId")
    }

    fun markSideEffectExecuted(taskId: String, actionId: String) {
        completedSideEffectSignatures.add("$taskId:$actionId")
    }

    fun hasUncertainExternalAction(taskId: String, actionId: String): Boolean {
        return uncertainExternalActionSignatures.contains("$taskId:$actionId")
    }

    fun markExternalActionUncertain(taskId: String, actionId: String) {
        uncertainExternalActionSignatures.add("$taskId:$actionId")
    }

    /**
     * Phase 3 — Deterministic Task Checkpoint Serialization & Restoration across Process Recreation.
     * Uses pure Kotlin serialization so it operates identically on Android runtime and JVM unit tests.
     */
    fun exportTaskCheckpointJson(task: AgentTask, completedStepIds: List<String> = emptyList()): String {
        val actionsEncoded = task.actions.joinToString(";;") { act ->
            val paramsEncoded = act.parameters.entries.joinToString(",") { (k, v) ->
                "${k.replace("=", "_").replace(",", "_").replace(";", "_")}=${v.replace(",", "_").replace(";", "_")}"
            }
            listOf(
                act.actionId,
                act.actionType.name,
                act.target.orEmpty().replace("|", "_").replace(";", "_"),
                act.riskLevel.name,
                act.idempotencyClassification,
                paramsEncoded
            ).joinToString("::")
        }
        val completedEncoded = completedStepIds.joinToString(",")
        return listOf(
            "taskId=${task.taskId}",
            "userInstruction=${task.userInstruction.replace("\n", " ").replace("||", " ")}",
            "resolvedIntent=${task.resolvedIntent}",
            "state=${task.state.name}",
            "currentActionIndex=${task.currentActionIndex}",
            "completedSteps=$completedEncoded",
            "actions=$actionsEncoded"
        ).joinToString("||")
    }

    fun restoreTaskFromCheckpointJson(checkpoint: String): AgentTask? {
        if (checkpoint.isBlank() || !checkpoint.contains("taskId=")) return null
        val fields = checkpoint.split("||").associate { part ->
            val idx = part.indexOf('=')
            if (idx > 0) part.substring(0, idx) to part.substring(idx + 1) else part to ""
        }
        val taskId = fields["taskId"]?.takeIf { it.isNotBlank() } ?: return null
        val userInstruction = fields["userInstruction"].orEmpty()
        val resolvedIntent = fields["resolvedIntent"].orEmpty().ifBlank { "RECOVERED_TASK" }
        val stateName = fields["state"].orEmpty()
        val restoredState = runCatching { TaskState.valueOf(stateName) }.getOrDefault(TaskState.PAUSED)
        val currentActionIndex = fields["currentActionIndex"]?.toIntOrNull() ?: 0
        val completedSteps = fields["completedSteps"].orEmpty()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val actionsRaw = fields["actions"].orEmpty()
        val actions = if (actionsRaw.isBlank()) {
            emptyList()
        } else {
            actionsRaw.split(";;").mapNotNull { token ->
                val parts = token.split("::")
                if (parts.size < 5) return@mapNotNull null
                val actId = parts[0]
                val actType = runCatching { ActionType.valueOf(parts[1]) }.getOrNull() ?: return@mapNotNull null
                val target = parts[2].ifBlank { null }
                val risk = runCatching { RiskLevel.valueOf(parts[3]) }.getOrDefault(RiskLevel.R0_LOCAL_READONLY)
                val idempotency = parts[4].ifBlank { "IDEMPOTENT" }
                val paramsMap = mutableMapOf<String, String>()
                if (parts.size >= 6 && parts[5].isNotBlank()) {
                    parts[5].split(",").forEach { kv ->
                        val eqIdx = kv.indexOf('=')
                        if (eqIdx > 0) {
                            paramsMap[kv.substring(0, eqIdx)] = kv.substring(eqIdx + 1)
                        }
                    }
                }
                AgentAction(
                    actionId = actId,
                    taskId = taskId,
                    actionType = actType,
                    target = target,
                    parameters = paramsMap,
                    riskLevel = risk,
                    idempotencyClassification = idempotency
                )
            }
        }

        for (stepId in completedSteps) {
            val matchedAction = actions.firstOrNull { it.actionId == stepId }
            if (matchedAction == null || matchedAction.idempotencyClassification == "NON_IDEMPOTENT") {
                markSideEffectExecuted(taskId, stepId)
            } else {
                markSideEffectExecuted(taskId, stepId)
            }
        }

        return AgentTask(
            taskId = taskId,
            userInstruction = userInstruction,
            resolvedIntent = resolvedIntent,
            actions = actions,
            state = restoredState,
            currentActionIndex = currentActionIndex
        )
    }

    fun cancelCurrentTask() {
        isTaskCancelled = true
        isTaskPaused = false
        pendingPermissionBlockedTask = null
        pendingRequiredPermission = null
        pendingUnlockBlockedTask = null
        pendingUnlockBlockedReason = null
        agentBrain.cancelActiveLoop()
        workflowEngine.cancelAllActiveWorkflows()
        confirmationManager.clear()
        continuityManager.clearPendingSlot()
        _currentTask.value?.let { task ->
            stateManager.transition(task, TaskState.CANCELLED, "User cancelled task.")
        }
        _currentState.value = AgentState.Cancelled("Task stopped by user.")
    }

    fun triggerEmergencyStop() {
        autonomyPolicyManager.triggerEmergencyStop()
        cancelCurrentTask()
    }

    /**
     * Startup Recovery (Section 17 & Phase 3): Inspects incomplete tasks from previous sessions, restores
     * completed step signatures so non-idempotent side-effect actions are never repeated, and
     * records safe recovery audit state.
     */
    suspend fun recoverInterruptedTasksOnBoot() {
        try {
            automationEngine.rescheduleAllEnabledRules()
            val incomplete = repository.getIncompleteTasks()
            for (entity in incomplete) {
                val cleanedSteps = entity.completedStepsJson
                    .removePrefix("[")
                    .removeSuffix("]")
                    .split(",")
                    .map { it.trim().removeSurrounding("\"") }
                    .filter { it.isNotEmpty() }
                for (stepId in cleanedSteps) {
                    completedSideEffectSignatures.add("${entity.taskId}:$stepId")
                }

                repository.recordTaskExecution(
                    entity.copy(
                        status = TaskState.FAILED.name,
                        resultSummary = "Task interrupted by application shutdown at step ${entity.currentStepIndex + 1}/${entity.stepsCount}. Safe recovery halted automatic re-execution of non-idempotent actions."
                    )
                )
                repository.logAuditAction(
                    actionType = "TASK_CONTINUITY_RECOVERY",
                    target = entity.resolvedIntent,
                    riskLevel = "R0",
                    outcome = "INTERRUPTED_RECOVERED",
                    details = "Cleaned up incomplete task #${entity.id} (${entity.taskId}) on boot."
                )
            }
        } catch (_: Exception) {}
    }

    /**
     * Main entry point for all user instructions (Typed Chat, Voice ASR, Floating Assistant, Wake Word).
     * Returns the user-facing response text after executing through the unified AgentBrain + AgentRuntime pipeline.
     */
    suspend fun executeUserInstruction(userPrompt: String): String {
        val response = executeUserInstructionDetailed(userPrompt).message
        val lastContinuityTurn = continuityManager.getRecentTurns(1).lastOrNull()
        if (lastContinuityTurn?.role != "assistant" || lastContinuityTurn.content != response) {
            continuityManager.recordConversationTurn("assistant", response)
        }
        val lastProviderTurn = conversationalEngine.getRecentHistory(1).lastOrNull()
        if (lastProviderTurn?.role != "assistant" || lastProviderTurn.content != response) {
            conversationalEngine.recordTurn("assistant", response)
        }
        return response
    }

    /**
     * Detailed execution entry point returning structured [AgentExecutionSummary] with
     * exact [TaskState], `isSuccess`, and [VerificationStatus].
     */
    suspend fun executeUserInstructionDetailed(userPrompt: String): AgentExecutionSummary {
        recordHeartbeat()
        val nowMs = System.currentTimeMillis()
        val trimmedPrompt = userPrompt.trim()
        // Room is the durable source of conversational continuity. UI callers persist the current
        // user turn before entering this method, so exclude that matching last row and add it once
        // below after resolving references.
        try {
            val storedTurns = repository.conversations.firstOrNull().orEmpty().takeLast(20)
            val lastStored = storedTurns.lastOrNull()
            val priorTurns = if (
                lastStored?.sender.equals("user", ignoreCase = true) &&
                lastStored?.content?.trim() == trimmedPrompt
            ) storedTurns.dropLast(1) else storedTurns
            continuityManager.restoreRecentTurnsFromStorage(priorTurns)
            conversationalEngine.restoreHistoryFromStorage(priorTurns)
        } catch (_: Exception) {}
        val activeTask = _currentTask.value
        // Duplicate task dispatch prevention (Section 3B & Section 10)
        if (trimmedPrompt.isNotEmpty() &&
            trimmedPrompt.equals(lastDispatchedInstruction, ignoreCase = true) &&
            (nowMs - lastDispatchedAtMs) < 1_500L &&
            activeTask != null &&
            activeTask.state == TaskState.EXECUTING
        ) {
            return AgentExecutionSummary(
                task = activeTask,
                isSuccess = false,
                message = "Duplicate task dispatch prevented: '${activeTask.userInstruction}' is already executing.",
                verificationStatus = VerificationStatus.INCONCLUSIVE
            )
        }
        lastDispatchedInstruction = trimmedPrompt
        lastDispatchedAtMs = nowMs

        isTaskCancelled = false
        _currentState.value = AgentState.Thinking

        // Handle pure wake greeting using dynamic configured identity & wake phrases (no hardcoded lock-in)
        val normalizedGreeting = trimmedPrompt.lowercase().removeSuffix(".").removeSuffix("!").trim()
        val lowerPersona = assistantName.lowercase().trim()
        val configuredWakeGreetings = com.example.core.identity.AssistantIdentityStore.sharedInstance
            ?.getWakePhrases()
            ?.map { it.lowercase().trim() }
            .orEmpty()
        val dynamicGreetings = buildSet {
            add("hi $lowerPersona")
            add("hey $lowerPersona")
            add("hello $lowerPersona")
            add(lowerPersona)
            add("hi nayro")
            add("hey nayro")
            add("hello nayro")
            add("nayro")
            addAll(configuredWakeGreetings)
        }
        if (normalizedGreeting in dynamicGreetings) {
            val activationCue = com.example.core.identity.AssistantIdentityStore.sharedInstance
                ?.getCurrentProfile()?.activationResponse?.takeIf { it.isNotBlank() }
                ?: "Yes, I'm listening."
            val greetingReply = "$activationCue ($assistantName)"
            continuityManager.recordConversationTurn("user", trimmedPrompt)
            continuityManager.recordConversationTurn("assistant", greetingReply)
            conversationalEngine.recordTurn("user", trimmedPrompt)
            conversationalEngine.recordTurn("assistant", greetingReply)
            val greetingTask = AgentTask(
                userInstruction = userPrompt,
                resolvedIntent = "WAKE_GREETING",
                actions = emptyList(),
                state = TaskState.COMPLETED,
                finalResult = greetingReply
            )
            _currentTask.value = greetingTask
            _currentState.value = AgentState.Idle
            return AgentExecutionSummary(
                task = greetingTask,
                isSuccess = true,
                message = greetingReply,
                verificationStatus = VerificationStatus.VERIFIED
            )
        }

        // 0. Resolve pronouns ("him", "usko", "that file", "video about it", "open the second result") and check multi-turn continuation (Part 9)
        val resolvedPrompt = continuityManager.resolveReferencesInPrompt(userPrompt)
        continuityManager.recordConversationTurn("user", resolvedPrompt)
        conversationalEngine.recordTurn("user", resolvedPrompt)
        try {
            memoryManager.processPromptForMemory(resolvedPrompt)
        } catch (_: Exception) {}
        val hasPendingConf = confirmationManager.pendingConfirmation.value != null
        val contResolution = continuityManager.evaluateIncomingTurn(
            userTurn = resolvedPrompt,
            hasActivePendingConfirmation = hasPendingConf
        )

        val task: AgentTask = when (contResolution) {
            is ContinuationResolution.ConfirmationReply -> {
                val activeConf = confirmationManager.pendingConfirmation.value
                if (contResolution.approved && activeConf != null) {
                    confirmationManager.approve(activeConf.actionId, activeConf.parameters)
                    val dummyTask = _currentTask.value ?: AgentTask(
                        userInstruction = userPrompt,
                        resolvedIntent = "CONFIRMATION_REPLY",
                        actions = emptyList(),
                        state = TaskState.COMPLETED
                    )
                    return AgentExecutionSummary(
                        task = dummyTask,
                        isSuccess = true,
                        message = "Confirmed: proceeding with action.",
                        verificationStatus = VerificationStatus.VERIFIED
                    )
                } else {
                    if (activeConf != null) {
                        confirmationManager.reject(activeConf.actionId)
                    }
                    val cancelledTask = _currentTask.value ?: AgentTask(
                        userInstruction = userPrompt,
                        resolvedIntent = "CONFIRMATION_REPLY",
                        actions = emptyList(),
                        state = TaskState.CANCELLED
                    )
                    return AgentExecutionSummary(
                        task = cancelledTask,
                        isSuccess = false,
                        message = "Action cancelled by user.",
                        verificationStatus = VerificationStatus.CANCELLED
                    )
                }
            }

            is ContinuationResolution.NeedsClarification -> {
                val waitingTask = AgentTask(
                    taskId = contResolution.taskId,
                    userInstruction = userPrompt,
                    resolvedIntent = "SLOT_FILLING_CLARIFICATION",
                    actions = emptyList(),
                    state = TaskState.CREATED
                )
                stateManager.transition(waitingTask, TaskState.UNDERSTANDING)
                stateManager.transition(waitingTask, TaskState.WAITING_FOR_USER_INPUT, contResolution.question)
                _currentTask.value = waitingTask
                return AgentExecutionSummary(
                    task = waitingTask,
                    isSuccess = false,
                    message = contResolution.question,
                    verificationStatus = VerificationStatus.INCONCLUSIVE
                )
            }

            is ContinuationResolution.SlotFilledResumeIntent -> {
                continuityManager.recordEntityContext(contact = contResolution.resolvedIntent.recipient)
                TaskPlanner.planTask(
                    intent = contResolution.resolvedIntent,
                    existingTaskId = contResolution.taskId
                )
            }

            is ContinuationResolution.NoPendingContinuation -> {
                val classified = TaskClassifier.classify(resolvedPrompt)
                if (classified.type == UserIntentType.APP_LAUNCH && !classified.targetAppOrEntity.isNullOrBlank()) {
                    continuityManager.recordEntityContext(app = classified.targetAppOrEntity)
                }
                if (classified.type == UserIntentType.MESSAGE_DRAFT) {
                    val planned = TaskPlanner.planTask(classified)
                    val recipient = classified.recipient
                    val body = classified.searchQueryOrContent.orEmpty()
                    if (!recipient.isNullOrBlank() && !recipient.equals("Contact", ignoreCase = true)) {
                        continuityManager.recordEntityContext(contact = recipient)
                    }
                    if (recipient.isNullOrBlank() || recipient.equals("Contact", ignoreCase = true)) {
                        val question = "Kisko message bhejna hai?"
                        continuityManager.registerPendingSlot(
                            PendingSlotContinuation(
                                taskId = planned.taskId,
                                intentType = UserIntentType.MESSAGE_DRAFT,
                                missingSlotName = "recipient",
                                promptToUser = question,
                                collectedEntities = if (body.isNotBlank()) mapOf("message" to body) else emptyMap()
                            )
                        )
                        stateManager.transition(planned, TaskState.UNDERSTANDING)
                        stateManager.transition(planned, TaskState.WAITING_FOR_USER_INPUT, question)
                        _currentTask.value = planned
                        return AgentExecutionSummary(
                            task = planned,
                            isSuccess = false,
                            message = question,
                            verificationStatus = VerificationStatus.INCONCLUSIVE
                        )
                    } else if (body.isBlank()) {
                        val question = "Kya message bhejna hai $recipient ko?"
                        continuityManager.registerPendingSlot(
                            PendingSlotContinuation(
                                taskId = planned.taskId,
                                intentType = UserIntentType.MESSAGE_DRAFT,
                                missingSlotName = "message",
                                promptToUser = question,
                                collectedEntities = mapOf("recipient" to recipient)
                            )
                        )
                        stateManager.transition(planned, TaskState.UNDERSTANDING)
                        stateManager.transition(planned, TaskState.WAITING_FOR_USER_INPUT, question)
                        _currentTask.value = planned
                        return AgentExecutionSummary(
                            task = planned,
                            isSuccess = false,
                            message = question,
                            verificationStatus = VerificationStatus.INCONCLUSIVE
                        )
                    }
                    planned
                } else {
                    agentBrain.planDynamicTaskWithModel(resolvedPrompt)
                }
            }
        }

        return executePlannedTask(task, resolvedPrompt)
    }

    /**
     * Executes a planned [AgentTask] through validation, step execution, verification, and audit logging.
     * Supports automatic resume from `WAITING_FOR_PERMISSION` checkpoints without re-executing completed
     * `NON_IDEMPOTENT` side-effect actions.
     */
    suspend fun executePlannedTask(
        task: AgentTask,
        resolvedPrompt: String = task.userInstruction
    ): AgentExecutionSummary {
        continuityManager.recordEntityContext(executedInstruction = resolvedPrompt)
        _currentTask.value = task
        AgentEventBus.emit(AgentEvent.TaskCreated(task))

        stateManager.transition(task, TaskState.UNDERSTANDING)
        stateManager.transition(task, TaskState.PLANNING)
        _currentState.value = AgentState.PlanningTask(task)

        // 2. Validate Plan Schema, Permissions & Capabilities
        stateManager.transition(task, TaskState.VALIDATING)
        when (val validation = planValidator.validatePlan(task)) {
            is ValidationResult.MissingCapability -> {
                recordPermissionBlockedTask(task, validation.capabilityId.name)
                stateManager.transition(task, TaskState.WAITING_FOR_PERMISSION, validation.reason)
                _currentState.value = AgentState.WaitingForPermission(validation.capabilityId.name, validation.reason)
                logAndPersistTask(task, "Blocked: ${validation.reason}")
                return AgentExecutionSummary(
                    task = task,
                    isSuccess = false,
                    message = validation.reason,
                    verificationStatus = VerificationStatus.REQUIRES_PERMISSION
                )
            }
            is ValidationResult.MissingPermission -> {
                recordPermissionBlockedTask(task, validation.permission)
                stateManager.transition(task, TaskState.WAITING_FOR_PERMISSION, validation.reason)
                _currentState.value = AgentState.WaitingForPermission(validation.permission, validation.reason)
                logAndPersistTask(task, "Blocked: ${validation.reason}")
                return AgentExecutionSummary(
                    task = task,
                    isSuccess = false,
                    message = validation.reason,
                    verificationStatus = VerificationStatus.REQUIRES_PERMISSION
                )
            }
            is ValidationResult.InvalidPlan -> {
                stateManager.transition(task, TaskState.FAILED, validation.reason)
                _currentState.value = AgentState.Error(validation.reason)
                logAndPersistTask(task, "Failed: ${validation.reason}")
                return AgentExecutionSummary(
                    task = task,
                    isSuccess = false,
                    message = validation.reason,
                    verificationStatus = VerificationStatus.FAILED
                )
            }
            is ValidationResult.Valid -> {
                stateManager.transition(task, TaskState.READY)
            }
        }

        // 3. Multi-Step Execution Loop with Context Carryover, Memory Injection, Lock-Screen Policy, Autonomy Policy, Risk Guard, Timeout & Strict Verification
        val stepOutputs = mutableListOf<String>()
        val brainTraceSteps = mutableListOf<BrainReasoningStep>()
        val stepContext = mutableMapOf<String, String>()
        val completedStepIds = mutableListOf<String>()
        var verifiedStepCount = 0

        // Retrieve relevant long-term memory and inject into reasoning context (Section 4 & Section 9)
        try {
            val memories = repository.memories.firstOrNull().orEmpty()
            val memorySnippet = memoryManager.buildRelevantContext(resolvedPrompt, memories)
            if (memorySnippet.isNotBlank()) {
                stepContext["memoryContext"] = memorySnippet
            }
            val recentTurns = repository.conversations.firstOrNull().orEmpty().takeLast(6)
            if (recentTurns.isNotEmpty()) {
                val compressedHistory = recentTurns.joinToString(" | ") { "${it.sender}: ${it.content.take(80)}" }
                stepContext["conversationHistory"] = compressedHistory
            }
        } catch (_: Exception) {}

        val resumeStartStepIndex = task.currentActionIndex.coerceAtLeast(0)
        val actionQueue = ArrayDeque<AgentAction>(task.actions)
        val executedHistory = mutableListOf<AgentAction>()
        var dynamicStepIndex = 0
        var replanCount = 0

        while (actionQueue.isNotEmpty() && dynamicStepIndex < AgentBrain.MAX_ITERATIVE_STEPS) {
            val rawAction = actionQueue.removeFirst()
            val index = dynamicStepIndex
            dynamicStepIndex++

            while (isTaskPaused && !isTaskCancelled) {
                delay(150)
            }
            if (isTaskCancelled) {
                stateManager.transition(task, TaskState.CANCELLED, "Cancelled during dynamic execution loop.")
                logAndPersistTask(task, "Cancelled by user at step ${index + 1}.", completedStepIds, stepContext)
                return AgentExecutionSummary(
                    task = task,
                    isSuccess = false,
                    message = "Task cancelled by user.",
                    verificationStatus = VerificationStatus.CANCELLED
                )
            }

            // Skip steps already completed prior to lock/permission/process-recreation resume checkpoint
            if (index < resumeStartStepIndex ||
                (rawAction.idempotencyClassification == "NON_IDEMPOTENT" &&
                    hasSideEffectAlreadyExecuted(task.taskId, rawAction.actionId))
            ) {
                val skipMsg = "Skipped completed checkpoint step ${rawAction.actionType.name} (${rawAction.actionId})."
                verifiedStepCount++
                completedStepIds.add(rawAction.actionId)
                stepOutputs.add(skipMsg)
                continue
            }

            // Prevent repeated identical tool-call loops during dynamic replanning
            if (agentBrain.hasRepeatedToolCallLoop(executedHistory, rawAction, maxConsecutiveRepeats = 2)) {
                val loopReason = "Aborted repeated identical action (${rawAction.actionType.name}) to prevent an infinite execution loop."
                stateManager.transition(task, TaskState.FAILED, loopReason)
                _currentState.value = AgentState.Error(loopReason)
                logAndPersistTask(task, loopReason, completedStepIds, stepContext)
                return AgentExecutionSummary(
                    task = task,
                    isSuccess = false,
                    message = loopReason,
                    verificationStatus = VerificationStatus.FAILED
                )
            }

            task.currentActionIndex = index

            // Phase 3 Guard: Never automatically re-execute an uncertain external action without explicit user confirmation
            if (hasUncertainExternalAction(task.taskId, rawAction.actionId)) {
                val uncertainReason = "Uncertain external action (${rawAction.actionType.name}) requires explicit user confirmation before retrying to prevent duplicate message/call execution."
                stateManager.transition(task, TaskState.WAITING_FOR_USER_CONFIRMATION, uncertainReason)
                _currentState.value = AgentState.Error(uncertainReason)
                logAndPersistTask(task, uncertainReason, completedStepIds, stepContext)
                return AgentExecutionSummary(
                    task = task,
                    isSuccess = false,
                    message = uncertainReason,
                    verificationStatus = VerificationStatus.REQUIRES_CONFIRMATION
                )
            }

            val enrichedParams = rawAction.parameters.toMutableMap()
            stepContext["previousStepOutput"]?.let { prev ->
                enrichedParams.putIfAbsent("previousStepOutput", prev)
            }
            if (rawAction.actionType == ActionType.QUERY_LLM) {
                stepContext["memoryContext"]?.let { mem ->
                    enrichedParams.putIfAbsent("memoryContext", mem)
                }
                stepContext["conversationHistory"]?.let { hist ->
                    enrichedParams.putIfAbsent("conversationHistory", hist)
                }
                val openEndedSummary = continuityManager.buildOpenEndedContextSummary()
                if (openEndedSummary.isNotBlank()) {
                    enrichedParams.putIfAbsent("openEndedContext", openEndedSummary)
                }
            }
            val action = rawAction.copy(parameters = enrichedParams)

            // Check Lock Screen Policy (Section 3 & Phase 2: WAITING_FOR_DEVICE_UNLOCK with checkpoint preservation)
            val lockDecision = operatingStateManager.evaluateLockScreenPolicy(action)
            if (lockDecision is LockScreenPolicyDecision.RequireDeviceUnlock) {
                recordUnlockBlockedTask(task, lockDecision.reason)
                stateManager.transition(task, TaskState.WAITING_FOR_DEVICE_UNLOCK, lockDecision.reason)
                _currentState.value = AgentState.WaitingForDeviceUnlock(task.taskId, lockDecision.reason)
                logAndPersistTask(task, "Paused waiting for device unlock: ${lockDecision.reason}", completedStepIds, stepContext)
                return AgentExecutionSummary(
                    task = task,
                    isSuccess = false,
                    message = lockDecision.reason,
                    verificationStatus = VerificationStatus.WAITING_FOR_UNLOCK
                )
            }

            // Check Autonomy Policy Manager (Part 11)
            val autonomyDecision = autonomyPolicyManager.evaluateAction(action)
            if (autonomyDecision is AutonomyDecision.DenyExecution) {
                stateManager.transition(task, TaskState.FAILED, autonomyDecision.reason)
                _currentState.value = AgentState.Error(autonomyDecision.reason)
                logAndPersistTask(task, "Blocked by Autonomy Policy: ${autonomyDecision.reason}", completedStepIds, stepContext)
                return AgentExecutionSummary(
                    task = task,
                    isSuccess = false,
                    message = autonomyDecision.reason,
                    verificationStatus = VerificationStatus.FAILED
                )
            }

            var activeConfirmation: PendingConfirmation? = null

            // Handle Confirmation Gate before executing step
            if (autonomyDecision is AutonomyDecision.RequireUserConfirmation ||
                action.riskLevel == RiskLevel.R3_EXTERNAL_COMMUNICATION ||
                action.actionType == ActionType.ASK_USER_CONFIRMATION
            ) {
                stateManager.transition(task, TaskState.WAITING_FOR_CONFIRMATION)
                val rawTarget = (action.target ?: action.parameters["recipient"] ?: action.parameters["number"]).orEmpty().trim()
                val isDirectDigits = rawTarget.any { it.isDigit() } && rawTarget.none { it.isLetter() }
                var resolvedName: String? = null
                var resolvedPhone: String? = if (isDirectDigits) rawTarget else null
                if (!isDirectDigits && rawTarget.isNotBlank()) {
                    val match = callIntelligenceManager.resolveContactsByName(rawTarget)
                    if (match is com.example.core.telephony.ContactResolutionResult.SingleMatch) {
                        resolvedName = match.contact.displayName
                        resolvedPhone = match.contact.phoneNumber
                    }
                } else if (isDirectDigits && rawTarget.isNotBlank()) {
                    resolvedName = callIntelligenceManager.resolveContactByPhoneNumber(rawTarget)
                }

                val pendingConf = ConfirmationSchemaBuilder.buildConfirmationFromAction(
                    taskId = task.taskId,
                    action = action,
                    resolvedContactName = resolvedName,
                    resolvedPhoneNumber = resolvedPhone
                )
                _currentState.value = AgentState.WaitingForConfirmation(pendingConf)

                val confStatus = confirmationManager.awaitConfirmation(pendingConf, timeoutMs = 60_000L)
                when (confStatus) {
                    ConfirmationStatus.CONFIRMED -> {
                        activeConfirmation = pendingConf
                        stateManager.transition(task, TaskState.READY)
                    }
                    ConfirmationStatus.EXPIRED -> {
                        val reason = "Confirmation request expired after 60s. Action was not executed."
                        stateManager.transition(task, TaskState.TIMED_OUT, reason)
                        _currentState.value = AgentState.Cancelled(reason)
                        logAndPersistTask(task, reason, completedStepIds, stepContext)
                        return AgentExecutionSummary(
                            task = task,
                            isSuccess = false,
                            message = reason,
                            verificationStatus = VerificationStatus.TIMED_OUT
                        )
                    }
                    else -> {
                        val reason = "Action rejected or cancelled by user."
                        stateManager.transition(task, TaskState.CANCELLED, reason)
                        _currentState.value = AgentState.Cancelled(reason)
                        logAndPersistTask(task, reason, completedStepIds, stepContext)
                        return AgentExecutionSummary(
                            task = task,
                            isSuccess = false,
                            message = reason,
                            verificationStatus = VerificationStatus.CANCELLED
                        )
                    }
                }
            }

            // Runtime Guard Validation — Fail-Closed capability & targeted permission verification
            val realCapabilityAvailable = when (action.requiredCapability) {
                com.example.core.capability.CapabilityId.ACCESSIBILITY -> {
                    val accState = try {
                        com.example.core.accessibility.AccessibilityConnectionManager.checkStatus(context)
                    } catch (_: Exception) {
                        com.example.core.accessibility.AccessibilityConnectionState.DISCONNECTED
                    }
                    accState == com.example.core.accessibility.AccessibilityConnectionState.OPERATIONAL ||
                        accState == com.example.core.accessibility.AccessibilityConnectionState.CONNECTED ||
                        com.example.core.accessibility.NayroAccessibilityService.isConnected()
                }
                else -> true
            }
            val realPermissionGranted = action.requiredPermission?.let { perm ->
                verifySinglePermissionTargeted(perm)
            } ?: true

            val guardCheck = ActionExecutionGuard.validateExecution(
                action = action,
                isCapabilityAvailable = realCapabilityAvailable,
                isPermissionGranted = realPermissionGranted,
                confirmation = activeConfirmation
            )
            if (guardCheck is GuardEvaluation.Blocked) {
                stateManager.transition(task, TaskState.FAILED, guardCheck.reason)
                _currentState.value = AgentState.Error(guardCheck.reason)
                logAndPersistTask(task, "Blocked by Guard: ${guardCheck.reason}", completedStepIds, stepContext)
                return AgentExecutionSummary(
                    task = task,
                    isSuccess = false,
                    message = "Execution blocked for safety: ${guardCheck.reason}",
                    verificationStatus = if (!realPermissionGranted) VerificationStatus.REQUIRES_PERMISSION else VerificationStatus.FAILED
                )
            }

            // Transition to EXECUTING
            executedHistory.add(action)
            val totalKnownSteps = index + 1 + actionQueue.size
            stateManager.transition(task, TaskState.EXECUTING)
            _currentState.value = AgentState.ExecutingTask(
                task = task,
                currentAction = action,
                step = index + 1,
                totalSteps = totalKnownSteps
            )
            AgentEventBus.emit(AgentEvent.ActionStarted(task.taskId, action, index + 1, totalKnownSteps))

            val preState = actionVerifier.capturePreState()
            val resolvedPkg = if (action.actionType == ActionType.LAUNCH_APP && action.target != null) {
                AppNavigationManager.resolvePackage(context, action.target)
            } else null

            val isConfirmedFlag = activeConfirmation?.status == ConfirmationStatus.CONFIRMED

            val execResult = withTimeoutOrNull(action.timeoutMs) {
                com.example.core.agent.tools.CommandDispatcher.dispatch(action, actionExecutor, isConfirmed = isConfirmedFlag)
            } ?: ActionExecutionResult(
                isSuccess = false,
                outputMessage = "Step ${index + 1} (${action.actionType.name}) timed out after ${action.timeoutMs}ms.",
                status = VerificationStatus.TIMED_OUT
            )

            // Transition to OBSERVING & VERIFYING
            stateManager.transition(task, TaskState.OBSERVING)
            if (action.actionType == ActionType.LAUNCH_APP || action.actionType == ActionType.OPEN_SETTINGS_SECTION || action.actionType == ActionType.CLICK_ELEMENT) {
                delay(350)
            }

            val observedSnapshot = if (com.example.core.accessibility.NayroAccessibilityService.isConnected()) {
                screenIntelligenceManager.captureCurrentSnapshot()
            } else null
            val observedSemantics = if (observedSnapshot != null) {
                screenIntelligenceManager.analyzeScreenSemantics(observedSnapshot)
            } else null

            stateManager.transition(task, TaskState.VERIFYING)
            val verification = actionVerifier.verifyAction(
                action = action,
                executionResult = execResult,
                preStateSummary = preState,
                resolvedTargetPackage = resolvedPkg
            )

            if (verification.status == VerificationStatus.VERIFIED) {
                AgentEventBus.emit(AgentEvent.ActionCompleted(task.taskId, action.actionId, verification.details))
            } else {
                AgentEventBus.emit(AgentEvent.ActionFailed(task.taskId, action.actionId, verification.details))
            }

            repository.logAuditAction(
                actionType = action.actionType.name,
                target = action.target ?: "local",
                riskLevel = action.riskLevel.code,
                outcome = verification.status.name,
                details = verification.details
            )

            brainTraceSteps.add(
                BrainReasoningStep(
                    stepIndex = index + 1,
                    thought = "Step ${index + 1}/$totalKnownSteps: ${action.actionType.name} -> ${verification.status.name}",
                    selectedAction = action.actionType,
                    target = action.target,
                    executionOutput = verification.details,
                    verificationStatus = verification.status,
                    isComplete = actionQueue.isEmpty() && verification.status == VerificationStatus.VERIFIED
                )
            )
            agentBrain.recordTrace(brainTraceSteps)

            // Section 1 — Dynamic Observe -> Verify -> Reason -> Replan / Recovery Decision
            if (verification.status == VerificationStatus.FAILED ||
                (observedSemantics?.hasDialogOrPopup == true && actionQueue.isNotEmpty())
            ) {
                val dynamicDecision = agentBrain.observeAndDecideNextStep(
                    DynamicObservationContext(
                        goal = task.userInstruction,
                        lastExecutedAction = action,
                        lastExecutionResult = execResult,
                        lastVerification = verification,
                        screenSnapshot = observedSnapshot,
                        screenSemantics = observedSemantics,
                        memoryContext = stepContext["memoryContext"],
                        completedStepOutputs = stepOutputs,
                        remainingPlannedActions = actionQueue.toList(),
                        executedHistory = executedHistory,
                        replanAttemptsUsed = replanCount
                    )
                )

                when (dynamicDecision) {
                    is DynamicReasoningDecision.InjectRecoveryActions -> {
                        replanCount++
                        stateManager.transition(task, TaskState.REPLANNING, dynamicDecision.thought)
                        if (verification.status == VerificationStatus.VERIFIED) {
                            verifiedStepCount++
                            completedStepIds.add(action.actionId)
                            stepOutputs.add(verification.details)
                            stepContext["previousStepOutput"] = verification.details
                        }
                        for (recoveryAct in dynamicDecision.actions.asReversed()) {
                            actionQueue.addFirst(recoveryAct)
                        }
                        logAndPersistTask(task, dynamicDecision.thought, completedStepIds, stepContext)
                        continue
                    }
                    is DynamicReasoningDecision.ReplanRemaining -> {
                        replanCount++
                        stateManager.transition(task, TaskState.REPLANNING, dynamicDecision.thought)
                        actionQueue.clear()
                        actionQueue.addAll(dynamicDecision.newActions)
                        logAndPersistTask(task, dynamicDecision.thought, completedStepIds, stepContext)
                        continue
                    }
                    else -> {
                        // Fall through to strict verification outcome handling below
                    }
                }
            }

            // Strict Verification Handling (BUG 05 & BUG 06 Fixed: NOT_SUPPORTED and INCONCLUSIVE are NEVER grouped with VERIFIED)
            when (verification.status) {
                VerificationStatus.VERIFIED -> {
                    verifiedStepCount++
                    completedStepIds.add(action.actionId)
                    if (action.idempotencyClassification == "NON_IDEMPOTENT") {
                        markSideEffectExecuted(task.taskId, action.actionId)
                    }
                    if (action.actionType == ActionType.CREATE_CALENDAR_EVENT) {
                        val eventTitle = (action.target ?: action.parameters["title"])?.trim()
                        if (!eventTitle.isNullOrBlank()) {
                            continuityManager.recordEntityContext(calendarEventTitle = eventTitle)
                        }
                    }
                    stepOutputs.add(verification.details)
                    stepContext["previousStepOutput"] = verification.details
                }

                VerificationStatus.REQUIRES_PERMISSION -> {
                    val reqPerm = action.requiredPermission ?: "ANDROID_PERMISSION"
                    recordPermissionBlockedTask(task, reqPerm)
                    stateManager.transition(task, TaskState.WAITING_FOR_PERMISSION, verification.details)
                    _currentState.value = AgentState.WaitingForPermission(
                        reqPerm,
                        verification.details
                    )
                    logAndPersistTask(task, verification.details, completedStepIds, stepContext)
                    return AgentExecutionSummary(
                        task = task,
                        isSuccess = false,
                        message = verification.details,
                        verificationStatus = VerificationStatus.REQUIRES_PERMISSION
                    )
                }

                VerificationStatus.WAITING_FOR_UNLOCK -> {
                    stateManager.transition(task, TaskState.WAITING_FOR_DEVICE_UNLOCK, verification.details)
                    _currentState.value = AgentState.WaitingForDeviceUnlock(task.taskId, verification.details)
                    logAndPersistTask(task, verification.details, completedStepIds, stepContext)
                    return AgentExecutionSummary(
                        task = task,
                        isSuccess = false,
                        message = verification.details,
                        verificationStatus = VerificationStatus.WAITING_FOR_UNLOCK
                    )
                }

                VerificationStatus.REQUIRES_CONFIRMATION -> {
                    stateManager.transition(task, TaskState.WAITING_FOR_CONFIRMATION, verification.details)
                    _currentState.value = AgentState.Error(verification.details)
                    logAndPersistTask(task, verification.details, completedStepIds, stepContext)
                    return AgentExecutionSummary(
                        task = task,
                        isSuccess = false,
                        message = verification.details,
                        verificationStatus = VerificationStatus.REQUIRES_CONFIRMATION
                    )
                }

                VerificationStatus.NOT_SUPPORTED -> {
                    val targetState = if (verifiedStepCount > 0) TaskState.PARTIALLY_COMPLETED else TaskState.NOT_SUPPORTED
                    val finalVerStatus = if (verifiedStepCount > 0) VerificationStatus.PARTIALLY_COMPLETED else VerificationStatus.NOT_SUPPORTED
                    stateManager.transition(task, targetState, verification.details)
                    val fullMsg = if (stepOutputs.isNotEmpty()) {
                        (stepOutputs + "Step ${index + 1} not supported: ${verification.details}").joinToString("\n")
                    } else {
                        verification.details
                    }
                    task.finalResult = fullMsg
                    _currentState.value = AgentState.Error(fullMsg)
                    logAndPersistTask(task, fullMsg, completedStepIds, stepContext)
                    return AgentExecutionSummary(
                        task = task,
                        isSuccess = false,
                        message = fullMsg,
                        verificationStatus = finalVerStatus
                    )
                }

                VerificationStatus.INCONCLUSIVE -> {
                    // BUG 05 & Phase 3 Fix: Never transition INCONCLUSIVE to COMPLETED or return isSuccess = true;
                    // mark external side-effect actions as uncertain so they are never automatically resent without confirmation.
                    if (action.idempotencyClassification == "NON_IDEMPOTENT" ||
                        action.actionType in setOf(
                            ActionType.MESSAGE_DRAFT_SEND,
                            ActionType.SEND_SMS,
                            ActionType.MAKE_PHONE_CALL
                        )
                    ) {
                        markExternalActionUncertain(task.taskId, action.actionId)
                    }
                    val targetState = if (verifiedStepCount > 0) TaskState.PARTIALLY_COMPLETED else TaskState.INCONCLUSIVE
                    val finalVerStatus = if (verifiedStepCount > 0) VerificationStatus.PARTIALLY_COMPLETED else VerificationStatus.INCONCLUSIVE
                    stateManager.transition(task, targetState, verification.details)
                    val fullMsg = if (stepOutputs.isNotEmpty()) {
                        (stepOutputs + "Step ${index + 1} inconclusive: ${verification.details}").joinToString("\n")
                    } else {
                        verification.details
                    }
                    task.finalResult = fullMsg
                    _currentState.value = AgentState.Error(fullMsg)
                    logAndPersistTask(task, fullMsg, completedStepIds, stepContext)
                    return AgentExecutionSummary(
                        task = task,
                        isSuccess = false,
                        message = fullMsg,
                        verificationStatus = finalVerStatus
                    )
                }

                VerificationStatus.TIMED_OUT -> {
                    val targetState = if (verifiedStepCount > 0) TaskState.PARTIALLY_COMPLETED else TaskState.TIMED_OUT
                    val finalVerStatus = if (verifiedStepCount > 0) VerificationStatus.PARTIALLY_COMPLETED else VerificationStatus.TIMED_OUT
                    stateManager.transition(task, targetState, verification.details)
                    val fullMsg = if (stepOutputs.isNotEmpty()) {
                        (stepOutputs + verification.details).joinToString("\n")
                    } else {
                        verification.details
                    }
                    task.finalResult = fullMsg
                    _currentState.value = AgentState.Error(fullMsg)
                    logAndPersistTask(task, fullMsg, completedStepIds, stepContext)
                    return AgentExecutionSummary(
                        task = task,
                        isSuccess = false,
                        message = fullMsg,
                        verificationStatus = finalVerStatus
                    )
                }

                VerificationStatus.CANCELLED -> {
                    stateManager.transition(task, TaskState.CANCELLED, verification.details)
                    _currentState.value = AgentState.Cancelled(verification.details)
                    logAndPersistTask(task, verification.details, completedStepIds, stepContext)
                    return AgentExecutionSummary(
                        task = task,
                        isSuccess = false,
                        message = verification.details,
                        verificationStatus = VerificationStatus.CANCELLED
                    )
                }

                VerificationStatus.FAILED, VerificationStatus.PARTIALLY_COMPLETED -> {
                    val targetState = if (verifiedStepCount > 0) TaskState.PARTIALLY_COMPLETED else TaskState.FAILED
                    val finalVerStatus = if (verifiedStepCount > 0) VerificationStatus.PARTIALLY_COMPLETED else VerificationStatus.FAILED
                    stateManager.transition(task, targetState, verification.details)
                    val fullMsg = if (stepOutputs.isNotEmpty()) {
                        (stepOutputs + "Step ${index + 1} failed: ${verification.details}").joinToString("\n")
                    } else {
                        verification.details
                    }
                    task.finalResult = fullMsg
                    _currentState.value = AgentState.Error(fullMsg)
                    logAndPersistTask(task, fullMsg, completedStepIds, stepContext)
                    return AgentExecutionSummary(
                        task = task,
                        isSuccess = false,
                        message = fullMsg,
                        verificationStatus = finalVerStatus
                    )
                }
            }
        }

        // Only reaches COMPLETED if every action in task.actions was VERIFIED
        stateManager.transition(task, TaskState.COMPLETED)
        val finalResponse = stepOutputs.joinToString("\n")
        task.finalResult = finalResponse
        _currentState.value = AgentState.Completed(finalResponse)
        AgentEventBus.emit(AgentEvent.TaskFinished(task.taskId, true, finalResponse))
        logAndPersistTask(task, finalResponse, completedStepIds, stepContext)

        return AgentExecutionSummary(
            task = task,
            isSuccess = true,
            message = finalResponse,
            verificationStatus = VerificationStatus.VERIFIED
        )
    }

    private suspend fun logAndPersistTask(
        task: AgentTask,
        summary: String,
        completedStepIds: List<String> = emptyList(),
        stepContext: Map<String, String> = emptyMap()
    ) {
        val completedJson = completedStepIds.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
        val pendingStepIds = task.actions
            .map { it.actionId }
            .filter { it !in completedStepIds }
        val pendingJson = pendingStepIds.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
        val contextJson = stepContext.entries.joinToString(prefix = "{", postfix = "}") { (k, v) ->
            "\"${k.replace("\"", "'")}\":\"${v.replace("\"", "'")}\""
        }
        val toolNamesList = task.actions.joinToString(",") { it.actionType.name }
        val checkpointPlanJson = exportTaskCheckpointJson(task, completedStepIds)
        val allIdempotent = task.actions.all { it.idempotencyClassification == "IDEMPOTENT" }

        repository.recordTaskExecution(
            TaskHistoryEntity(
                taskId = task.taskId,
                userInstruction = task.userInstruction,
                resolvedIntent = task.resolvedIntent,
                status = task.state.name,
                stepsCount = task.actions.size,
                currentStepIndex = task.currentActionIndex,
                planJson = checkpointPlanJson,
                completedStepsJson = completedJson,
                pendingStepsJson = pendingJson,
                toolNames = toolNamesList,
                conversationContext = contextJson,
                recoveryMetadata = if (allIdempotent) "IDEMPOTENT_SAFE" else "CONTAINS_SIDE_EFFECTS",
                resultSummary = summary,
                durationMs = System.currentTimeMillis() - task.createdAt
            )
        )
    }

    /**
     * Section 16 — Classifies execution/verification failures into structured error categories.
     */
    fun classifyErrorCategory(
        details: String,
        verificationStatus: VerificationStatus = VerificationStatus.FAILED
    ): AgentErrorCategory {
        val lower = details.lowercase()
        return when {
            verificationStatus == VerificationStatus.INCONCLUSIVE || lower.contains("inconclusive") ->
                AgentErrorCategory.ACTION_INCONCLUSIVE
            lower.contains("permanently denied") ->
                AgentErrorCategory.PERMISSION_PERMANENTLY_DENIED
            verificationStatus == VerificationStatus.REQUIRES_PERMISSION || lower.contains("permission") ->
                AgentErrorCategory.PERMISSION_MISSING
            lower.contains("accessibility") && (lower.contains("disabled") || lower.contains("not enabled") || lower.contains("inactive")) ->
                AgentErrorCategory.ACCESSIBILITY_SERVICE_DISABLED
            lower.contains("device is locked") || lower.contains("keyguard") || lower.contains("unlock required") ->
                AgentErrorCategory.DEVICE_LOCKED
            lower.contains("blocked for safety") || lower.contains("prohibited") || lower.contains("security policy") ->
                AgentErrorCategory.SECURITY_POLICY_BLOCKED
            lower.contains("not installed") ->
                AgentErrorCategory.TARGET_APP_NOT_INSTALLED
            lower.contains("multiple contacts") || lower.contains("ambiguous contact") ->
                AgentErrorCategory.AMBIGUOUS_CONTACT
            lower.contains("contact not found") || lower.contains("no contact found") ->
                AgentErrorCategory.CONTACT_NOT_FOUND
            lower.contains("element not found") || lower.contains("could not find element") || lower.contains("target not found") ->
                AgentErrorCategory.SCREEN_ELEMENT_NOT_FOUND
            lower.contains("quota") || lower.contains("rate limit") || lower.contains("429") ->
                AgentErrorCategory.GEMINI_QUOTA_EXCEEDED
            lower.contains("invalid api key") || lower.contains("api key") || lower.contains("401") || lower.contains("403") ->
                AgentErrorCategory.INVALID_API_KEY
            verificationStatus == VerificationStatus.TIMED_OUT || lower.contains("timed out") || lower.contains("timeout") ->
                AgentErrorCategory.GEMINI_API_TIMEOUT
            lower.contains("network") || lower.contains("offline") || lower.contains("unreachable") ->
                AgentErrorCategory.NETWORK_UNAVAILABLE
            else -> AgentErrorCategory.ACTION_INCONCLUSIVE
        }
    }

    /**
     * Section 16 — Determines whether an action with [errorCategory] is safe for bounded automatic retry.
     * Non-idempotent actions (messages, calls, SMS, calendar/contact creation) are NEVER auto-retried blindly.
     */
    fun isSafeToRetryAutomatically(action: AgentAction, errorCategory: AgentErrorCategory): Boolean {
        if (action.idempotencyClassification != "IDEMPOTENT") return false
        if (action.actionType in setOf(
                ActionType.MESSAGE_DRAFT_SEND,
                ActionType.SEND_SMS,
                ActionType.MAKE_PHONE_CALL
            )
        ) {
            return false
        }
        return errorCategory in setOf(
            AgentErrorCategory.GEMINI_API_TIMEOUT,
            AgentErrorCategory.NETWORK_UNAVAILABLE,
            AgentErrorCategory.SCREEN_ELEMENT_NOT_FOUND
        )
    }

    /**
     * Section 14 — Builds an evidence-backed, redacted Agent Diagnostics Report (`NAYRO AGENT STATUS`).
     */
    fun getAgentDiagnosticsReport(
        voiceStateLabel: String = "STANDBY",
        wakeStateLabel: String = "LISTENING_FOR_WAKE",
        micOwnerLabel: String = "WAKE_WORD_ENGINE",
        serviceStateLabel: String = "RUNNING"
    ): AgentDiagnosticsReport {
        val activeTask = _currentTask.value
        val a11yConnected = com.example.core.accessibility.NayroAccessibilityService.instance != null
        val provider = try { providerManager.activeProvider.value } catch (_: Exception) { null }
        val geminiStatus = if (provider != null && provider.hasKeySaved) "READY" else "NOT_CONFIGURED"

        val stepSummary = activeTask?.actions?.mapIndexed { idx, act ->
            val stateTag = when {
                idx < activeTask.currentActionIndex -> "[x]"
                idx == activeTask.currentActionIndex && activeTask.state == TaskState.EXECUTING -> "[>]"
                activeTask.state == TaskState.COMPLETED -> "[x]"
                else -> "[ ]"
            }
            "$stateTag ${act.actionType.name}${act.target?.let { " ($it)" } ?: ""}"
        }.orEmpty()

        return AgentDiagnosticsReport(
            agentStatus = if (_currentState.value is AgentState.Error) "DEGRADED" else "ACTIVE",
            voiceStatus = voiceStateLabel,
            wakeWordStatus = wakeStateLabel,
            microphoneOwner = micOwnerLabel,
            accessibilityStatus = if (a11yConnected) "CONNECTED" else "DISCONNECTED",
            serviceStatus = serviceStateLabel,
            geminiStatus = geminiStatus,
            currentTaskTitle = activeTask?.userInstruction?.let { com.example.core.memory.MemoryPrivacyGuard.sanitizeContent(it) } ?: "Idle",
            currentTaskState = activeTask?.state?.name ?: "IDLE",
            stepChecklist = stepSummary,
            heartbeatHealthy = isHeartbeatHealthy()
        )
    }

    /**
     * Core execution loop for intent understanding, Gemini API dynamic tool selection,
     * tool execution, and result verification.
     */
    suspend fun executeCoreGeminiLoop(
        userInstruction: String,
        currentScreenSummary: String? = null
    ): AgentExecutionSummary {
        recordHeartbeat()
        val resolvedInstruction = continuityManager.resolveReferencesInPrompt(userInstruction)
        val enrichedPrompt = if (!currentScreenSummary.isNullOrBlank()) {
            "$resolvedInstruction\n[Active Screen Context: $currentScreenSummary]"
        } else {
            resolvedInstruction
        }
        val plannedTask = agentBrain.planDynamicTaskWithModel(enrichedPrompt)
        return executePlannedTask(plannedTask, resolvedInstruction)
    }
}

enum class AgentErrorCategory {
    NETWORK_UNAVAILABLE,
    GEMINI_API_TIMEOUT,
    GEMINI_QUOTA_EXCEEDED,
    INVALID_API_KEY,
    PERMISSION_MISSING,
    PERMISSION_PERMANENTLY_DENIED,
    ACCESSIBILITY_SERVICE_DISABLED,
    TARGET_APP_NOT_INSTALLED,
    CONTACT_NOT_FOUND,
    AMBIGUOUS_CONTACT,
    SCREEN_ELEMENT_NOT_FOUND,
    DEVICE_LOCKED,
    SECURITY_POLICY_BLOCKED,
    ACTION_INCONCLUSIVE
}

data class AgentDiagnosticsReport(
    val agentStatus: String,
    val voiceStatus: String,
    val wakeWordStatus: String,
    val microphoneOwner: String,
    val accessibilityStatus: String,
    val serviceStatus: String,
    val geminiStatus: String,
    val currentTaskTitle: String,
    val currentTaskState: String,
    val stepChecklist: List<String>,
    val heartbeatHealthy: Boolean
) {
    fun formatAsNayroStatusBlock(): String = buildString {
        appendLine("NAYRO AGENT STATUS")
        appendLine("Agent: $agentStatus")
        appendLine("Voice: $voiceStatus")
        appendLine("Wake Word: $wakeWordStatus")
        appendLine("Microphone: $microphoneOwner")
        appendLine("Accessibility: $accessibilityStatus")
        appendLine("Service: $serviceStatus")
        appendLine("Gemini: $geminiStatus")
        appendLine("Current Task: $currentTaskTitle ($currentTaskState)")
        if (stepChecklist.isNotEmpty()) {
            stepChecklist.forEach { appendLine(it) }
        }
    }.trim()
}

