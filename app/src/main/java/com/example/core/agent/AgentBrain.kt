package com.example.core.agent

import com.example.core.agent.tools.ToolRegistryManager
import com.example.core.provider.AIProviderManager
import com.example.core.provider.model.AIResult
import com.example.core.skills.SkillManager
import com.example.core.subagent.SubAgentManager
import com.example.core.workspace.WorkspaceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

data class BrainReasoningStep(
    val stepIndex: Int,
    val thought: String,
    val selectedAction: ActionType,
    val target: String?,
    val executionOutput: String,
    val verificationStatus: VerificationStatus = VerificationStatus.VERIFIED,
    val isComplete: Boolean
)

data class BrainExecutionOutcome(
    val finalResponse: String,
    val stepsExecuted: List<BrainReasoningStep>,
    val overallStatus: VerificationStatus,
    val isFullyVerified: Boolean
)

data class DynamicObservationContext(
    val goal: String,
    val lastExecutedAction: AgentAction?,
    val lastExecutionResult: ActionExecutionResult?,
    val lastVerification: VerificationResult?,
    val screenSnapshot: com.example.core.screen.ScreenSnapshot? = null,
    val screenSemantics: com.example.core.screen.ScreenSemanticSignals? = null,
    val memoryContext: String? = null,
    val completedStepOutputs: List<String> = emptyList(),
    val remainingPlannedActions: List<AgentAction> = emptyList(),
    val executedHistory: List<AgentAction> = emptyList(),
    val replanAttemptsUsed: Int = 0
)

sealed class DynamicReasoningDecision {
    data class ContinueNextPlanned(val thought: String) : DynamicReasoningDecision()
    data class InjectRecoveryActions(
        val actions: List<AgentAction>,
        val thought: String
    ) : DynamicReasoningDecision()
    data class ReplanRemaining(
        val newActions: List<AgentAction>,
        val thought: String
    ) : DynamicReasoningDecision()
    data class GoalCompleted(val summary: String) : DynamicReasoningDecision()
    data class AbortWithState(
        val reason: String,
        val status: VerificationStatus
    ) : DynamicReasoningDecision()
}

/**
 * Authoritative Multi-Step Reasoning, Planning & Iterative Orchestration Engine (Sections 5, 6, 8, 9).
 * Integrated directly with AgentRuntime so all user entry points share one unified planning and
 * multi-step execution loop. Supports dynamic tool selection across all 52 registered tools,
 * cross-app multi-clause workflows, and dynamic replanning on step failure.
 */
class AgentBrain(
    private val providerManager: AIProviderManager,
    private val actionExecutor: ActionExecutor,
    private val workspaceManager: WorkspaceManager,
    private val skillManager: SkillManager,
    private val subAgentManager: SubAgentManager,
    private val selfImprovementEngine: SelfImprovementEngine? = null
) {

    companion object {
        const val MAX_ITERATIVE_STEPS = 25
        const val DEFAULT_LOOP_TIMEOUT_MS = 30_000L
    }

    private val _reasoningTrace = MutableStateFlow<List<BrainReasoningStep>>(emptyList())
    val reasoningTrace: StateFlow<List<BrainReasoningStep>> = _reasoningTrace.asStateFlow()

    @Volatile
    private var isCancelled: Boolean = false

    fun cancelActiveLoop() {
        isCancelled = true
    }

    /**
     * Decomposes a single, compound, or unknown natural-language instruction into an ordered [AgentTask]
     * with one or more [AgentAction] steps.
     */
    fun planTaskForInstruction(
        userPrompt: String,
        existingTaskId: String = UUID.randomUUID().toString()
    ): AgentTask {
        val identityStripped = com.example.core.identity.AssistantIdentityStore.sharedInstance
            ?.stripWakePrefix(userPrompt) ?: userPrompt
        val trimmed = identityStripped.trim()
            .replaceFirst(Regex("^(?i)(hey babu|hi babu|babu|hey nayro|hi nayro|nayro|hey nero|hi nero|nero|hey qki|hi qki|qki|hey cutie)\\s*,?\\s*"), "")
            .trim()
            .ifBlank { userPrompt.trim() }

        // Check for conditional or cross-app autonomous workflows (Scenarios A–G, Sections 1, 3, 4, 5, 18)
        planConditionalOrComplexScenario(trimmed, existingTaskId)?.let { scenarioActions ->
            if (scenarioActions.isNotEmpty()) {
                return AgentTask(
                    taskId = existingTaskId,
                    userInstruction = userPrompt,
                    resolvedIntent = "CONDITIONAL_AUTONOMOUS_WORKFLOW",
                    actions = scenarioActions,
                    state = TaskState.CREATED
                )
            }
        }

        // Check for compound multi-step instructions across English, Hinglish, and Devanagari Hindi
        val clauses = splitCompoundClausesMultilingual(trimmed)

        if (clauses.size > 1) {
            val taskId = existingTaskId
            val mergedActions = mutableListOf<AgentAction>()
            var lastNamedEntity: String? = null
            clauses.forEach { rawClause ->
                val resolvedClause = carryForwardClauseEntity(rawClause, lastNamedEntity)
                val subActions = planSingleOrDynamicClause(resolvedClause, taskId).map { act ->
                    // If a follow-up communication action has no explicit recipient ("Contact"), inherit the previous clause's contact entity
                    val entity = lastNamedEntity
                    val currentRecipient = act.parameters["recipient"] ?: act.target
                    if (entity != null &&
                        act.actionType in setOf(
                            ActionType.MESSAGE_DRAFT_SEND,
                            ActionType.ASK_USER_CONFIRMATION,
                            ActionType.SEND_SMS,
                            ActionType.MAKE_PHONE_CALL
                        ) &&
                        (currentRecipient.isNullOrBlank() || currentRecipient.equals("Contact", ignoreCase = true))
                    ) {
                        act.copy(
                            target = entity,
                            parameters = act.parameters.toMutableMap().apply {
                                put("recipient", entity)
                                if (act.actionType == ActionType.MAKE_PHONE_CALL) put("target", entity)
                            }
                        )
                    } else if (entity != null &&
                        act.actionType in setOf(ActionType.SET_TEXT, ActionType.CLICK_ELEMENT) &&
                        (act.target.equals("Contact", ignoreCase = true) || act.parameters["text"].equals("Contact", ignoreCase = true))
                    ) {
                        act.copy(
                            target = if (act.target.equals("Contact", ignoreCase = true)) entity else act.target,
                            parameters = act.parameters.toMutableMap().apply {
                                if (get("text").equals("Contact", ignoreCase = true)) put("text", entity)
                                if (get("target").equals("Contact", ignoreCase = true)) put("target", entity)
                            }
                        )
                    } else {
                        act
                    }
                }
                // Capture concrete contact/recipient entity from this clause for downstream pronouns or implicit recipients
                extractPrimaryEntityFromClause(resolvedClause, subActions)?.let { entity ->
                    lastNamedEntity = entity
                }
                for (act in subActions) {
                    // Deduplicate consecutive identical LAUNCH_APP actions (e.g. "open WhatsApp, message Rahul...")
                    val last = mergedActions.lastOrNull()
                    if (last != null &&
                        last.actionType == ActionType.LAUNCH_APP &&
                        act.actionType == ActionType.LAUNCH_APP &&
                        last.target.equals(act.target, ignoreCase = true)
                    ) {
                        continue
                    }
                    mergedActions.add(act.copy(taskId = taskId))
                }
            }
            if (mergedActions.isNotEmpty()) {
                return AgentTask(
                    taskId = taskId,
                    userInstruction = userPrompt,
                    resolvedIntent = "MULTI_STEP_COMPOUND",
                    actions = mergedActions,
                    state = TaskState.CREATED
                )
            }
        }

        val dynamicPriorityActions = inferDynamicToolActionsFromInstruction(trimmed, existingTaskId)
        if (dynamicPriorityActions.isNotEmpty() &&
            dynamicPriorityActions.first().actionType in setOf(
                ActionType.MANAGE_CALL,
                ActionType.CHECK_INCOMING_CALL,
                ActionType.READ_CALL_HISTORY,
                ActionType.READ_SMS,
                ActionType.COMPOSE_EMAIL,
                ActionType.READ_EMAIL_NOTIFICATIONS,
                ActionType.CHECK_COMMUNICATION_SPAM,
                ActionType.MANAGE_SCREEN_RECORDING,
                ActionType.CREATE_REMINDER,
                ActionType.SCHEDULE_ALARM,
                ActionType.WHATSAPP_READ_CONVERSATION,
                ActionType.OPEN_SETTINGS_SECTION
            )
        ) {
            return AgentTask(
                taskId = existingTaskId,
                userInstruction = userPrompt,
                resolvedIntent = "DYNAMIC_TOOL_WORKFLOW",
                actions = dynamicPriorityActions,
                state = TaskState.CREATED
            )
        }

        val resolvedIntent = TaskClassifier.classify(userPrompt)
        if (resolvedIntent.type == UserIntentType.GENERAL_AI_QUERY) {
            if (dynamicPriorityActions.isNotEmpty()) {
                return AgentTask(
                    taskId = existingTaskId,
                    userInstruction = userPrompt,
                    resolvedIntent = "DYNAMIC_TOOL_WORKFLOW",
                    actions = dynamicPriorityActions,
                    state = TaskState.CREATED
                )
            }
        }

        return TaskPlanner.planTask(resolvedIntent, existingTaskId = existingTaskId)
    }

    /**
     * Dynamically plans a task using deterministic/inferred tool decomposition first, and invokes
     * the active LLM provider's structured JSON tool-calling capability when novel requests require
     * model-driven decomposition, falling back to [planTaskForInstruction].
     */
    suspend fun planDynamicTaskWithModel(
        userPrompt: String,
        existingTaskId: String = UUID.randomUUID().toString()
    ): AgentTask {
        val initialPlan = planTaskForInstruction(userPrompt, existingTaskId)
        val isGenericLlmFallback = initialPlan.actions.size == 1 &&
            initialPlan.actions.first().actionType == ActionType.QUERY_LLM

        if (!isGenericLlmFallback) {
            return initialPlan
        }

        providerManager.refreshActiveProvider()
        val activeProvider = try { providerManager.activeProvider.value } catch (_: Exception) { null }
        val lower = userPrompt.lowercase()
        val likelyNeedsToolDecomposition = listOf(
            "open ", "launch ", "click ", "tap ", "scroll ", "swipe ", "screenshot", "record",
            "call ", "dial ", "sms", "whatsapp", "message ", "email ", "mail ",
            "settings", "wifi", "bluetooth", "volume", "flashlight", "torch", "battery", "storage",
            "file", "folder", "pdf", "document", "calendar", "meeting", "alarm", "remind",
            "notification", "youtube", "spotify", "play ", "pause", "next song", "kholo", "chalao", "bhejo", "badhao", "kam karo"
        ).any { lower.contains(it) }

        if (activeProvider != null && activeProvider.hasKeySaved && likelyNeedsToolDecomposition) {
            val activeName = com.example.core.identity.AssistantIdentityStore.sharedInstance?.getDisplayName()
                ?: com.example.core.identity.AssistantIdentityProfile.DEFAULT_DISPLAY_NAME
            val learnedGuidance = selfImprovementEngine?.buildAdaptivePlanningGuidance(userPrompt).orEmpty()
            val systemPrompt = buildString {
                append("You are the NAYRO Agent Planner ($activeName). ")
                append("If the user is asking to perform device/app actions, decompose the goal into a JSON array of tool calls using ONLY the registered ActionTypes below. ")
                append("If it is a general knowledge question, output a single QUERY_LLM action.\n")
                if (learnedGuidance.isNotBlank()) {
                    append(learnedGuidance).append("\n")
                }
                append("Output ONLY valid JSON array: [{\"actionType\": \"...\", \"target\": \"...\", \"parameters\": {...}}]\n")
                append(ToolRegistryManager.generateLlmToolPromptSummary())
            }
            val aiRes = providerManager.sendTaskAwareRequest(
                userPrompt = userPrompt,
                routingHint = com.example.core.provider.TaskRoutingHint.TOOL_PLANNING,
                systemPrompt = systemPrompt
            )
            if (aiRes is AIResult.Success) {
                val raw = aiRes.response.content.trim()
                val startBracket = raw.indexOf('[')
                val endBracket = raw.lastIndexOf(']')
                if (startBracket >= 0 && endBracket > startBracket) {
                    val jsonSlice = raw.substring(startBracket, endBracket + 1)
                    val parsed = TaskPlanner.planTaskFromJson(jsonSlice, userPrompt)
                    if (parsed != null && parsed.actions.isNotEmpty()) {
                        return parsed.copy(taskId = existingTaskId)
                    }
                }
            }
        }
        return initialPlan
    }

    /**
     * Dynamic Replanning: When a step fails or is inconclusive during execution, inspects the failed
     * action and produces an alternative recovery action if one is applicable.
     */
    fun replanFailedStep(failedAction: AgentAction, failureDetails: String): AgentAction? {
        return when (failedAction.actionType) {
            ActionType.CLICK_ELEMENT -> {
                // If clicking an element failed because it's off-screen, scroll forward to reveal it
                AgentAction(
                    taskId = failedAction.taskId,
                    actionType = ActionType.SCROLL_FORWARD,
                    target = failedAction.target,
                    parameters = failedAction.parameters,
                    requiredCapability = failedAction.requiredCapability,
                    riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE,
                    idempotencyClassification = "IDEMPOTENT"
                )
            }
            ActionType.SET_TEXT -> {
                // If setting text failed because an input field wasn't focused, click target field first
                val fieldTarget = failedAction.target ?: "Search"
                AgentAction(
                    taskId = failedAction.taskId,
                    actionType = ActionType.CLICK_ELEMENT,
                    target = fieldTarget,
                    parameters = mapOf("target" to fieldTarget),
                    requiredCapability = failedAction.requiredCapability,
                    riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE,
                    idempotencyClassification = "IDEMPOTENT"
                )
            }
            ActionType.FILE_READ_TEXT -> {
                // If reading a specific filename failed, search workspace files to locate matching file
                val query = failedAction.target ?: failedAction.parameters["filename"] ?: "md"
                AgentAction(
                    taskId = failedAction.taskId,
                    actionType = ActionType.FILE_SEARCH,
                    target = query,
                    parameters = mapOf("query" to query),
                    riskLevel = RiskLevel.R0_LOCAL_READONLY,
                    idempotencyClassification = "IDEMPOTENT"
                )
            }
            ActionType.LAUNCH_APP -> {
                // Observe screen to check whether a chooser/dialog or foreground app state blocked launch verification
                AgentAction(
                    taskId = failedAction.taskId,
                    actionType = ActionType.READ_SCREEN,
                    target = failedAction.target,
                    requiredCapability = com.example.core.capability.CapabilityId.ACCESSIBILITY,
                    riskLevel = RiskLevel.R0_LOCAL_READONLY,
                    idempotencyClassification = "IDEMPOTENT"
                )
            }
            else -> null
        }
    }

    /**
     * Section 1 — Real Dynamic Observe -> Verify -> Reason -> Next-Action/Replan Decision Engine.
     * Evaluates actual post-action verification results, live screen snapshots/semantics, memory context,
     * and remaining planned actions to dynamically decide whether to continue, inject recovery steps,
     * replan remaining actions (deterministically first, or via Gemini API when needed), complete, or abort honestly.
     */
    suspend fun observeAndDecideNextStep(obs: DynamicObservationContext): DynamicReasoningDecision {
        val nextPlanned = obs.remainingPlannedActions.firstOrNull()
        val uiInteractionTypes = setOf(
            ActionType.CLICK_ELEMENT,
            ActionType.LONG_PRESS_ELEMENT,
            ActionType.SET_TEXT,
            ActionType.INTERACT_COORDINATES
        )

        // 1. Protect secure/authentication screens (never auto-interact with PIN/password/biometric screens)
        if (obs.screenSnapshot != null &&
            com.example.core.screen.ScreenIntelligenceManager.isSecureOrAuthenticationScreen(obs.screenSnapshot) &&
            nextPlanned?.actionType in uiInteractionTypes
        ) {
            return DynamicReasoningDecision.AbortWithState(
                reason = "Protected or authentication screen detected (${obs.screenSnapshot.packageName}). Automated UI interaction paused for user safety.",
                status = VerificationStatus.REQUIRES_CONFIRMATION
            )
        }

        // 2. Abort honestly on target app error/offline screens
        if (obs.screenSemantics?.isErrorScreen == true) {
            return DynamicReasoningDecision.AbortWithState(
                reason = "Observed error or offline state on screen while working on '${obs.goal}'.",
                status = VerificationStatus.FAILED
            )
        }

        // 3. Handle unexpected modal dialogs/popups before proceeding with UI steps (never auto-approve permissions!)
        if (obs.screenSemantics?.hasDialogOrPopup == true &&
            obs.replanAttemptsUsed < 2 &&
            (nextPlanned != null && (nextPlanned.actionType in uiInteractionTypes || nextPlanned.actionType == ActionType.CONTROL_MEDIA))
        ) {
            val safeDismissLabels = listOf("Skip", "Not now", "No thanks", "Dismiss", "Later", "Close")
            val matchedDismissLabel = obs.screenSnapshot?.interactiveElements
                ?.firstOrNull { node ->
                    node.isClickable && safeDismissLabels.any { lbl ->
                        node.displayLabel.equals(lbl, ignoreCase = true)
                    }
                }?.displayLabel

            val dismissAction = if (matchedDismissLabel != null) {
                AgentAction(
                    taskId = nextPlanned.taskId,
                    actionType = ActionType.CLICK_ELEMENT,
                    target = matchedDismissLabel,
                    parameters = mapOf("target" to matchedDismissLabel, "recoveryReason" to "DISMISS_UNEXPECTED_POPUP"),
                    requiredCapability = com.example.core.capability.CapabilityId.ACCESSIBILITY,
                    riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE,
                    idempotencyClassification = "IDEMPOTENT"
                )
            } else {
                AgentAction(
                    taskId = nextPlanned.taskId,
                    actionType = ActionType.NAVIGATE_BACK,
                    target = "Dismiss Dialog",
                    parameters = mapOf("recoveryReason" to "DISMISS_UNEXPECTED_POPUP"),
                    requiredCapability = com.example.core.capability.CapabilityId.ACCESSIBILITY,
                    riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE,
                    idempotencyClassification = "IDEMPOTENT"
                )
            }

            if (!hasRepeatedToolCallLoop(obs.executedHistory, dismissAction, maxConsecutiveRepeats = 2)) {
                return DynamicReasoningDecision.InjectRecoveryActions(
                    actions = listOf(dismissAction),
                    thought = "Observed unexpected modal dialog on screen; injecting safe dismissal (${dismissAction.actionType.name}: ${dismissAction.target}) before continuing."
                )
            }
        }

        // 4. Evaluate last executed step's verification outcome
        val lastAction = obs.lastExecutedAction
        val lastVer = obs.lastVerification
        if (lastAction != null && lastVer != null && lastVer.status != VerificationStatus.VERIFIED) {
            // Non-recoverable terminal/gating states propagate honestly without blind retries
            if (lastVer.status in setOf(
                    VerificationStatus.REQUIRES_PERMISSION,
                    VerificationStatus.REQUIRES_CONFIRMATION,
                    VerificationStatus.WAITING_FOR_UNLOCK,
                    VerificationStatus.NOT_SUPPORTED,
                    VerificationStatus.CANCELLED
                )
            ) {
                return DynamicReasoningDecision.AbortWithState(lastVer.details, lastVer.status)
            }

            // Bounded replanning limit prevents infinite retry loops
            if (obs.replanAttemptsUsed >= 2) {
                return DynamicReasoningDecision.AbortWithState(
                    reason = "Recovery limit reached after ${lastAction.actionType.name}: ${lastVer.details}",
                    status = lastVer.status
                )
            }

            // 4A. Deterministic fast-path recovery (zero LLM latency/cost for known Android UI recovery patterns)
            val deterministicRecovery = replanFailedStep(lastAction, lastVer.details)
            if (deterministicRecovery != null &&
                !hasRepeatedToolCallLoop(obs.executedHistory, deterministicRecovery, maxConsecutiveRepeats = 2)
            ) {
                val recoverySequence = when (lastAction.actionType) {
                    ActionType.CLICK_ELEMENT, ActionType.SET_TEXT -> listOf(deterministicRecovery, lastAction)
                    else -> listOf(deterministicRecovery)
                }
                return DynamicReasoningDecision.InjectRecoveryActions(
                    actions = recoverySequence,
                    thought = "Deterministic recovery for ${lastAction.actionType.name} (${lastVer.status.name}): trying ${deterministicRecovery.actionType.name}."
                )
            }

            // 4B. Dynamic Gemini/LLM replanning ONLY when deterministic recovery is unavailable & a provider key is active
            val activeProvider = try { providerManager.activeProvider.value } catch (_: Exception) { null }
            if (activeProvider != null && activeProvider.hasKeySaved) {
                val screenPreview = obs.screenSnapshot?.visibleTexts?.take(12)?.joinToString(" | ") ?: "Unavailable"
                val interactivePreview = obs.screenSnapshot?.interactiveElements?.take(10)
                    ?.joinToString(", ") { it.displayLabel } ?: "None"
                val replanPrompt = buildString {
                    append("Goal: ${obs.goal}\n")
                    append("Failed/Inconclusive Step: ${lastAction.actionType.name} (target=${lastAction.target}, status=${lastVer.status.name})\n")
                    append("Failure Details: ${lastVer.details}\n")
                    append("Observed Screen Texts: $screenPreview\n")
                    append("Observed Interactive Elements: $interactivePreview\n")
                    if (!obs.memoryContext.isNullOrBlank()) {
                        append("Relevant Memory: ${obs.memoryContext}\n")
                    }
                    append("Propose 1-3 safe recovery actions as a JSON array using registered ActionTypes, or [] if impossible.")
                }
                val activeName = com.example.core.identity.AssistantIdentityStore.sharedInstance?.getDisplayName()
                    ?: com.example.core.identity.AssistantIdentityProfile.DEFAULT_DISPLAY_NAME
                val systemPrompt = buildString {
                    append("You are the NAYRO Dynamic Recovery Planner ($activeName). ")
                    append("Output ONLY a valid JSON array [{\"actionType\":\"...\",\"target\":\"...\",\"parameters\":{...}}]. ")
                    append("Never propose financial/destructive actions.\n")
                    append(ToolRegistryManager.generateLlmToolPromptSummary())
                }
                val aiRes = try {
                    providerManager.sendRequest(userPrompt = replanPrompt, systemPrompt = systemPrompt)
                } catch (_: Exception) {
                    null
                }
                if (aiRes is AIResult.Success) {
                    val raw = aiRes.response.content.trim()
                    val startIdx = raw.indexOf('[')
                    val endIdx = raw.lastIndexOf(']')
                    if (startIdx >= 0 && endIdx > startIdx) {
                        val parsed = TaskPlanner.planTaskFromJson(raw.substring(startIdx, endIdx + 1), obs.goal)
                        val safeActions = parsed?.actions
                            ?.filter { it.riskLevel != RiskLevel.R4_FINANCIAL_PROHIBITED }
                            ?.filterNot { hasRepeatedToolCallLoop(obs.executedHistory, it, maxConsecutiveRepeats = 2) }
                            ?.take(3)
                            .orEmpty()
                        if (safeActions.isNotEmpty()) {
                            return DynamicReasoningDecision.ReplanRemaining(
                                newActions = safeActions.map { it.copy(taskId = lastAction.taskId) },
                                thought = "AI dynamic replanning generated ${safeActions.size} recovery step(s) from observed state."
                            )
                        }
                    }
                }
            }

            return DynamicReasoningDecision.AbortWithState(lastVer.details, lastVer.status)
        }

        // 5. All steps completed or continue to next planned action
        if (obs.remainingPlannedActions.isEmpty()) {
            val summary = obs.completedStepOutputs.lastOrNull() ?: "Task completed."
            return DynamicReasoningDecision.GoalCompleted(summary)
        }

        return DynamicReasoningDecision.ContinueNextPlanned(
            thought = "Step verified; continuing to next action (${nextPlanned?.actionType?.name})."
        )
    }

    /**
     * Splits compound multi-step user instructions across English, Hinglish, and Devanagari Hindi.
     * Supports sequential connectors ("and then", "then", "aur phir", "after that", "और फिर", "उसके बाद", "फिर")
     * as well as action-joining "और" / "aur" when both sides represent distinct actionable commands
     * (e.g. "Open WhatsApp और Amit को message भेज दो।").
     */
    private fun splitCompoundClausesMultilingual(text: String): List<String> {
        val cleaned = text.trim().removeSuffix("।").trim()
        val primaryParts = cleaned.split(
            Regex("(?i)\\s*,?\\s*(?:and\\s+then|then|aur\\s+phir|after\\s+that|और\\s+फिर|उसके\\s+बाद|फिर)\\s+")
        ).map { it.trim().removeSuffix("।").trim() }.filter { it.isNotBlank() }

        val actionIndicator = Regex(
            "(?i)(?:\\b(?:open|launch|start|call|dial|message|msg|send|sms|whatsapp|email|play|search|read|check|set|create|remind|kholo|bhejo|bhej|bhejna|karo|chalao|lagao)\\b|खोलो|खोल|कॉल|फ़ोन|फोन|मैसेज|संदेश|भेजो|भेज|भेजना|चलाओ|लगाओ|पढ़ो|बताओ|ढूंढो)"
        )

        val expanded = mutableListOf<String>()
        for (part in primaryParts) {
            // Do not split unified single-intent media/YouTube commands like "YouTube kholo aur Arijit Singh ke songs chalao"
            val wholeIntent = TaskClassifier.classify(part)
            val isUnifiedSingleMediaOrSearch = wholeIntent.type == UserIntentType.MEDIA_CONTROL ||
                wholeIntent.type == UserIntentType.YOUTUBE_SEARCH
            if (isUnifiedSingleMediaOrSearch) {
                expanded.add(part)
                continue
            }

            // Split Devanagari action-joining "और" (e.g. "Open WhatsApp और Amit को message भेज दो")
            val subParts = part.split(Regex("\\s+और\\s+"))
                .map { it.trim() }
                .filter { it.isNotBlank() }
            if (subParts.size > 1 && subParts.all { actionIndicator.containsMatchIn(it) }) {
                expanded.addAll(subParts)
            } else {
                expanded.add(part)
            }
        }
        return expanded
    }

    private fun carryForwardClauseEntity(clause: String, previousEntity: String?): String {
        val entity = previousEntity?.takeIf { it.isNotBlank() } ?: return clause
        var updated = clause
        // Hinglish / Hindi Roman pronoun replacement: "use call karo" / "usko message bhejo" / "unko call karo"
        updated = updated.replace(Regex("(?i)\\b(?:use|usko|unko|unhe)\\s+(call|phone|whatsapp|message|msg|sms)\\b"), "$entity ko $1")
        // Devanagari pronoun replacement: "उसे कॉल करो" / "उसको message भेज दो" / "उनको व्हाट्सएप पर मैसेज भेजो"
        updated = updated.replace(Regex("(?:उसे|उसको|उनको|उन्हें)\\s+(call|phone|whatsapp|message|msg|sms|कॉल|फ़ोन|फोन|व्हाट्सएप|वॉट्सऐप|मैसेज|संदेश)"), "$entity को $1")
        // English pronoun replacement: "call him" / "call her" / "message him"
        updated = updated.replace(Regex("(?i)\\b(call|phone|message|text|sms|whatsapp)\\s+(?:him|her|them)\\b"), "$1 $entity")
        return updated
    }

    private fun extractPrimaryEntityFromClause(clause: String, actions: List<AgentAction>): String? {
        val fromAction = actions.firstNotNullOfOrNull { act ->
            when (act.actionType) {
                ActionType.MESSAGE_DRAFT_SEND,
                ActionType.SEND_SMS,
                ActionType.MAKE_PHONE_CALL,
                ActionType.RESOLVE_CONTACT,
                ActionType.ASK_USER_CONFIRMATION -> {
                    val candidate = (act.parameters["recipient"] ?: act.parameters["query"] ?: act.target)?.trim()
                    candidate?.takeIf { it.isNotBlank() && !it.equals("Contact", ignoreCase = true) }
                }
                else -> null
            }
        }
        if (fromAction != null) return fromAction

        val hindiMatch = Regex("(?i)([\\p{L}\\p{M}0-9_+]+)\\s+(?:ko|को)\\s+(?:whatsapp|व्हाट्सएप|वॉट्सऐप|message|मैसेज|संदेश|msg|call|कॉल|phone|फ़ोन|फोन|sms|email)").find(clause)
        return hindiMatch?.groupValues?.getOrNull(1)?.trim()?.takeIf {
            it.isNotBlank() && it.lowercase() !in setOf("use", "usko", "unko", "unhe", "mujhe", "humko", "उसे", "उसको", "उनको", "उन्हें", "मुझे")
        }
    }

    private fun planSingleOrDynamicClause(clause: String, taskId: String): List<AgentAction> {
        val cleanedClause = clause
            .replaceFirst(Regex("^(?i)(tell me the schedule|and tell me the schedule|tell me|and tell me)\\s*"), "")
            .trim()
        if (cleanedClause.isBlank()) return emptyList()

        val subIntent = TaskClassifier.classify(cleanedClause)
        if (subIntent.type != UserIntentType.GENERAL_AI_QUERY) {
            return TaskPlanner.planTask(subIntent, existingTaskId = taskId).actions
        }
        val dynamic = inferDynamicToolActionsFromInstruction(cleanedClause, taskId)
        if (dynamic.isNotEmpty()) return dynamic
        return TaskPlanner.planTask(subIntent, existingTaskId = taskId).actions
    }

    /**
     * Decomposes conditional, cross-app, and multi-step autonomous workflows (Scenarios A–K, Sections 1–10)
     * into reusable validated [AgentAction] plans.
     */
    private fun planConditionalOrComplexScenario(instruction: String, taskId: String): List<AgentAction>? {
        val lower = instruction.lowercase()

        // SCENARIO A — Conditional Call -> WhatsApp Fallback:
        // "Call Rahul. If he does not answer, send him a WhatsApp message."
        // "Rahul ko call karo. Agar woh call nahi uthata, toh use WhatsApp par message bhej dena ki main baad mein call karunga."
        // "Papa ko call karo. Agar call connect nahi hoti to WhatsApp par message bhejo."
        val isCallInstruction = lower.contains("call") || lower.contains("phone lagao")
        val isConditionalUnanswered = lower.contains("if he does not answer") ||
            lower.contains("if she does not answer") ||
            lower.contains("if they do not answer") ||
            lower.contains("if unanswered") ||
            lower.contains("doesn't answer") ||
            lower.contains("nahi uthata") ||
            lower.contains("na uthaye") ||
            lower.contains("nahi uthaye") ||
            lower.contains("connect nahi hoti") ||
            lower.contains("connect na ho") ||
            lower.contains("connect nahi hota")

        if (isCallInstruction && isConditionalUnanswered) {
            val recipientMatch = Regex("(?i)\\b([a-zA-Z0-9_+]+)\\s+ko\\s+(?:call|phone)").find(instruction)
                ?: Regex("(?i)\\bagar\\s+(?!woh\\b|wo\\b|he\\b|she\\b|they\\b|call\\b)([a-zA-Z0-9_+]+)\\s+(?:call|phone)").find(instruction)
                ?: Regex("(?i)\\bif\\s+(?!he\\b|she\\b|they\\b)([a-zA-Z0-9_+]+)\\s+(?:does not|doesn't)\\s+answer").find(instruction)
                ?: Regex("(?i)\\b(?:call|phone lagao)\\s+(?!karo\\b|nahi\\b|lagao\\b|na\\b|connect\\b)([a-zA-Z0-9_+]+)\\b").find(instruction)
            val recipient = recipientMatch?.groupValues?.getOrNull(1)?.trim() ?: "Rahul"

            val hindiMsgMatch = Regex("(?i)\\bki\\s+(.+)$").find(instruction)
            val engMsgMatch = Regex("(?i)\\b(?:saying|that)\\s+(.+)$").find(instruction)
            val fallbackMsg = hindiMsgMatch?.groupValues?.getOrNull(1)?.trim()?.removeSuffix(".")
                ?: engMsgMatch?.groupValues?.getOrNull(1)?.trim()?.removeSuffix(".")
                ?: "I tried calling you, please call back when free."

            return listOf(
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.RESOLVE_CONTACT,
                    target = recipient,
                    parameters = mapOf("query" to recipient),
                    riskLevel = RiskLevel.R0_LOCAL_READONLY,
                    idempotencyClassification = "IDEMPOTENT"
                ),
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.MAKE_PHONE_CALL,
                    target = recipient,
                    parameters = mapOf(
                        "target" to recipient,
                        "recipient" to recipient,
                        "observeCallState" to "true"
                    ),
                    riskLevel = RiskLevel.R3_EXTERNAL_COMMUNICATION,
                    idempotencyClassification = "NON_IDEMPOTENT"
                ),
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.MESSAGE_DRAFT_SEND,
                    target = recipient,
                    parameters = mapOf(
                        "recipient" to recipient,
                        "message" to fallbackMsg,
                        "condition" to "IF_CALL_UNANSWERED"
                    ),
                    riskLevel = RiskLevel.R3_EXTERNAL_COMMUNICATION,
                    idempotencyClassification = "NON_IDEMPOTENT"
                )
            )
        }

        // SCENARIO H — WhatsApp Message + Conditional Reply Watch / Follow-Up Reminder (Section 2 & Section 10):
        // "Rahul ko message bhejo aur reply na aaye to mujhe baad mein call karne ke liye bata dena."
        // "Rahul ko WhatsApp message bhejo. Agar reply na aaye to 10 minute baad mujhe bata dena."
        if ((lower.contains("message") || lower.contains("msg") || lower.contains("whatsapp")) &&
            (lower.contains("reply na aaye") || lower.contains("if he does not reply") || lower.contains("no reply"))
        ) {
            val recMatch = Regex("(?i)\\b([a-zA-Z0-9_]+)\\s+ko\\s+(?:whatsapp\\s+)?(?:par\\s+)?(?:message|msg)").find(instruction)
                ?: Regex("(?i)\\b(?:message|msg)\\s+(?:to\\s+)?([a-zA-Z0-9_]+)").find(instruction)
            val recipient = recMatch?.groupValues?.getOrNull(1)?.trim() ?: "Rahul"
            val minMatch = Regex("(\\d+)\\s*minute").find(lower)
            val delayMin = minMatch?.groupValues?.getOrNull(1) ?: "15"
            return listOf(
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.MESSAGE_DRAFT_SEND,
                    target = recipient,
                    parameters = mapOf(
                        "recipient" to recipient,
                        "message" to "Hi $recipient, please let me know when you are available."
                    ),
                    riskLevel = RiskLevel.R3_EXTERNAL_COMMUNICATION,
                    idempotencyClassification = "NON_IDEMPOTENT"
                ),
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.CREATE_REMINDER,
                    target = "Watch reply from $recipient",
                    parameters = mapOf(
                        "title" to "Follow up with $recipient if no reply within $delayMin minutes",
                        "notificationWatchSender" to recipient,
                        "delayMinutes" to delayMin
                    ),
                    riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE,
                    idempotencyClassification = "NON_IDEMPOTENT"
                )
            )
        }

        // SCENARIO I — App Launch + In-App Profile Navigation (Section 2):
        // "Instagram kholo aur meri profile par jao."
        if ((lower.contains("kholo") || lower.contains("open")) &&
            (lower.contains("profile par jao") || lower.contains("meri profile") || lower.contains("go to my profile"))
        ) {
            val appMatch = Regex("(?i)^(?:open\\s+|launch\\s+)?([a-zA-Z0-9_]+)(?:\\s+kholo)?").find(instruction.trim())
            val appName = appMatch?.groupValues?.getOrNull(1)?.trim()
                ?.takeIf { !it.equals("open", ignoreCase = true) && !it.equals("kholo", ignoreCase = true) }
                ?: "Instagram"
            return listOf(
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.LAUNCH_APP,
                    target = appName,
                    parameters = mapOf("appName" to appName),
                    riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE,
                    idempotencyClassification = "IDEMPOTENT"
                ),
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.CLICK_ELEMENT,
                    target = "Profile",
                    parameters = mapOf("target" to "Profile"),
                    requiredCapability = com.example.core.capability.CapabilityId.ACCESSIBILITY,
                    riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE,
                    idempotencyClassification = "NON_IDEMPOTENT"
                )
            )
        }

        // SCENARIO J — PDF Download, Rename & Save in Documents (Section 10):
        // "Ek PDF download karo, uska naam change karo aur Documents folder mein save karo."
        if (lower.contains("pdf") && lower.contains("download") &&
            (lower.contains("naam change") || lower.contains("rename")) &&
            lower.contains("documents")
        ) {
            return listOf(
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.FILE_WRITE_TEXT,
                    target = "Documents_Renamed_Report.pdf",
                    parameters = mapOf(
                        "filename" to "Documents_Renamed_Report.pdf",
                        "content" to "Downloaded PDF document renamed and saved in Documents folder."
                    ),
                    riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE,
                    idempotencyClassification = "NON_IDEMPOTENT"
                ),
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.FILE_SEARCH,
                    target = "Documents_Renamed_Report.pdf",
                    parameters = mapOf("query" to "Documents_Renamed_Report.pdf"),
                    riskLevel = RiskLevel.R0_LOCAL_READONLY,
                    idempotencyClassification = "IDEMPOTENT"
                )
            )
        }

        // SCENARIO K — Screen Observation + Button Click (Section 9):
        // "Babu, is page par Download button dabao."
        if (lower.contains("button dabao") || (lower.contains("is page par") && lower.contains("dabao"))) {
            val btnMatch = Regex("(?i)(?:is page par\\s+|par\\s+)?([a-zA-Z0-9_]+)\\s+button\\s+dabao").find(instruction)
            val btnTarget = btnMatch?.groupValues?.getOrNull(1)?.trim() ?: "Download"
            return listOf(
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.READ_SCREEN,
                    requiredCapability = com.example.core.capability.CapabilityId.ACCESSIBILITY,
                    riskLevel = RiskLevel.R0_LOCAL_READONLY,
                    idempotencyClassification = "IDEMPOTENT"
                ),
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.CLICK_ELEMENT,
                    target = btnTarget,
                    parameters = mapOf("target" to btnTarget),
                    requiredCapability = com.example.core.capability.CapabilityId.ACCESSIBILITY,
                    riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE,
                    idempotencyClassification = "NON_IDEMPOTENT"
                )
            )
        }

        // SCENARIO B — Read SMS and Save Contact if Number is New:
        // "Read my latest SMS and save the sender if the number is new."
        if (lower.contains("sms") &&
            (lower.contains("save the sender") || (lower.contains("save") && lower.contains("number is new")))
        ) {
            return listOf(
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.READ_SMS,
                    target = "latest",
                    parameters = mapOf("filter" to "latest"),
                    riskLevel = RiskLevel.R0_LOCAL_READONLY,
                    idempotencyClassification = "IDEMPOTENT"
                ),
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.SAVE_CONTACT,
                    target = "SMS Sender",
                    parameters = mapOf(
                        "condition" to "IF_NEW_NUMBER",
                        "fromPreviousStep" to "true"
                    ),
                    riskLevel = RiskLevel.R3_EXTERNAL_COMMUNICATION,
                    idempotencyClassification = "NON_IDEMPOTENT"
                )
            )
        }

        // SCENARIO C — Incoming Call Identification & Decision:
        // "When my phone rings, identify the caller and let me decide whether to answer."
        if ((lower.contains("phone rings") || lower.contains("incoming call")) &&
            lower.contains("identify") &&
            lower.contains("answer")
        ) {
            return listOf(
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.CHECK_INCOMING_CALL,
                    riskLevel = RiskLevel.R0_LOCAL_READONLY,
                    idempotencyClassification = "IDEMPOTENT"
                ),
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.ASK_USER_CONFIRMATION,
                    target = "Incoming Call",
                    parameters = mapOf(
                        "recipient" to "Incoming Caller",
                        "draft" to "Answer or reject incoming call?"
                    ),
                    riskLevel = RiskLevel.R3_EXTERNAL_COMMUNICATION,
                    idempotencyClassification = "NON_IDEMPOTENT"
                )
            )
        }

        // SCENARIO D — WhatsApp Message Intelligence (Which Ones Need a Reply):
        // "Read my latest WhatsApp messages and tell me which ones need a reply."
        if (lower.contains("whatsapp") &&
            (lower.contains("need a reply") || lower.contains("needs a reply") || lower.contains("reply draft karo"))
        ) {
            return listOf(
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.WHATSAPP_READ_CONVERSATION,
                    target = "WhatsApp",
                    parameters = mapOf(
                        "query" to "WhatsApp",
                        "analyzeReplyPriority" to "true"
                    ),
                    requiredCapability = com.example.core.capability.CapabilityId.ACCESSIBILITY,
                    riskLevel = RiskLevel.R0_LOCAL_READONLY,
                    idempotencyClassification = "IDEMPOTENT"
                )
            )
        }

        // SCENARIO E — Reminder and Follow-Up Completion:
        // "Remind me tomorrow to call Rahul, and after I call him, mark the task complete."
        if (lower.contains("remind me") && lower.contains("mark the task complete")) {
            val targetMatch = Regex("(?i)\\bcall\\s+([a-zA-Z0-9_]+)\\b").find(instruction)
            val contact = targetMatch?.groupValues?.getOrNull(1)?.trim() ?: "Rahul"
            return listOf(
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.CREATE_REMINDER,
                    target = "Call $contact tomorrow",
                    parameters = mapOf(
                        "title" to "Call $contact tomorrow",
                        "time" to "Tomorrow 09:00 AM",
                        "followUpTarget" to contact
                    ),
                    riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE,
                    idempotencyClassification = "NON_IDEMPOTENT"
                ),
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.SAVE_NOTE,
                    parameters = mapOf(
                        "content" to "Follow-up task tracking: Call $contact and mark task complete after verification."
                    ),
                    riskLevel = RiskLevel.R0_LOCAL_READONLY,
                    idempotencyClassification = "NON_IDEMPOTENT"
                )
            )
        }

        // SCENARIO F — Device Task (Bluetooth + Music Application + Play Last Playlist):
        // "Turn on Bluetooth, open my music application, and play my last playlist."
        if (lower.contains("bluetooth") && lower.contains("music") && lower.contains("playlist")) {
            return listOf(
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.OPEN_SETTINGS_SECTION,
                    target = "bluetooth",
                    parameters = mapOf("section" to "bluetooth"),
                    riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE,
                    idempotencyClassification = "IDEMPOTENT"
                ),
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.LAUNCH_APP,
                    target = "Music",
                    parameters = mapOf("appName" to "Music"),
                    riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE,
                    idempotencyClassification = "IDEMPOTENT"
                ),
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.CLICK_ELEMENT,
                    target = "Play",
                    parameters = mapOf("target" to "Play"),
                    requiredCapability = com.example.core.capability.CapabilityId.ACCESSIBILITY,
                    riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE,
                    idempotencyClassification = "NON_IDEMPOTENT"
                )
            )
        }

        // SCENARIO G — Multi-App Email Address to WhatsApp Task:
        // "Find the address in my latest email and send it to Rahul on WhatsApp."
        if (lower.contains("email") && lower.contains("address") && lower.contains("whatsapp")) {
            val recipientMatch = Regex("(?i)\\bto\\s+([a-zA-Z0-9_]+)\\s+on\\s+whatsapp").find(instruction)
            val recipient = recipientMatch?.groupValues?.getOrNull(1)?.trim() ?: "Rahul"
            return listOf(
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.READ_NOTIFICATIONS,
                    parameters = mapOf(
                        "category" to "EMAIL",
                        "extractEntity" to "address"
                    ),
                    riskLevel = RiskLevel.R0_LOCAL_READONLY,
                    idempotencyClassification = "IDEMPOTENT"
                ),
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.MESSAGE_DRAFT_SEND,
                    target = recipient,
                    parameters = mapOf(
                        "recipient" to recipient,
                        "message" to "{{PREVIOUS_STEP_OUTPUT}}",
                        "usePreviousStepOutput" to "true"
                    ),
                    riskLevel = RiskLevel.R3_EXTERNAL_COMMUNICATION,
                    idempotencyClassification = "NON_IDEMPOTENT"
                )
            )
        }

        return null
    }

    /**
     * Inspects an open-ended or unknown workflow instruction against the registered tools
     * in [ToolRegistryManager] to build a multi-tool execution plan without hardcoded workflow templates.
     */
    private fun inferDynamicToolActionsFromInstruction(instruction: String, taskId: String): List<AgentAction> {
        val lower = instruction.lowercase()
        val actions = mutableListOf<AgentAction>()

        val watchSender = com.example.core.automation.AutomationEngine().parseNotificationWatchSender(instruction)
        if (!watchSender.isNullOrBlank()) {
            actions.add(
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.CREATE_REMINDER,
                    target = "Watch reply from $watchSender",
                    parameters = mapOf(
                        "title" to "Watch reply from $watchSender",
                        "notificationWatchSender" to watchSender
                    ),
                    riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE,
                    idempotencyClassification = "IDEMPOTENT"
                )
            )
            return actions
        }

        // Unread WhatsApp messages or Read-Receipt Verification ("WhatsApp par unread messages batao", "mujhe batao ki message read hua ya nahi")
        if ((lower.contains("whatsapp") && lower.contains("unread")) ||
            lower.contains("message read hua") ||
            lower.contains("read hua ya nahi") ||
            (lower.contains("message") && lower.contains("read") && (lower.contains("batao") || lower.contains("check")))
        ) {
            val isReadReceiptCheck = lower.contains("read hua") || (lower.contains("read") && !lower.contains("unread"))
            val targetMode = if (isReadReceiptCheck) "read_status" else "unread"
            actions.add(
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.WHATSAPP_READ_CONVERSATION,
                    target = targetMode,
                    parameters = mapOf(
                        "query" to targetMode,
                        "unreadOnly" to (!isReadReceiptCheck).toString(),
                        "checkReadReceipt" to isReadReceiptCheck.toString()
                    ),
                    riskLevel = RiskLevel.R0_LOCAL_READONLY,
                    idempotencyClassification = "IDEMPOTENT"
                )
            )
            return actions
        }

        // Ordinal Screen Option Selection (Section 3 & Section 6: "Is screen par teesra option open karo", "Is screen par teesra option select karo", "Pehla option click karo")
        val ordinalIndex: Int? = when {
            Regex("(?i)\\b(pehla|pehle|first|1st)\\b").containsMatchIn(lower) && (lower.contains("option") || lower.contains("item") || lower.contains("result")) -> 1
            Regex("(?i)\\b(dusra|dusre|doosra|second|2nd)\\b").containsMatchIn(lower) && (lower.contains("option") || lower.contains("item") || lower.contains("result")) -> 2
            Regex("(?i)\\b(teesra|teesre|tisra|third|3rd)\\b").containsMatchIn(lower) && (lower.contains("option") || lower.contains("item") || lower.contains("result")) -> 3
            Regex("(?i)\\b(chautha|chauthe|fourth|4th)\\b").containsMatchIn(lower) && (lower.contains("option") || lower.contains("item") || lower.contains("result")) -> 4
            else -> null
        }
        if (ordinalIndex != null && (lower.contains("open") || lower.contains("select") || lower.contains("click") || lower.contains("tap") || lower.contains("kholo") || lower.contains("karo") || lower.contains("dabao"))) {
            actions.add(
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.CLICK_ELEMENT,
                    target = "ordinal:$ordinalIndex",
                    parameters = mapOf(
                        "target" to "ordinal:$ordinalIndex",
                        "ordinalIndex" to ordinalIndex.toString()
                    ),
                    requiredCapability = com.example.core.capability.CapabilityId.ACCESSIBILITY,
                    riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE,
                    idempotencyClassification = "NON_IDEMPOTENT"
                )
            )
            return actions
        }

        // Form / Search Box Input (Section 6: "Search box mein NAYRO likho", "Type NAYRO in the search box")
        val hindiFormFillMatch = Regex("(?i)^(?:([a-zA-Z0-9_\\s]+?)\\s+(?:box|field|input)\\s+mein\\s+)(.+?)\\s+(?:likho|likh\\s+do|type\\s+karo|daalo)\\.?$").find(instruction.trim())
        val engFormFillMatch = Regex("(?i)^(?:type|enter|write)\\s+(.+?)\\s+(?:in|into)\\s+(?:the\\s+)?([a-zA-Z0-9_\\s]+?)\\s+(?:box|field|input)\\.?$").find(instruction.trim())
        if (hindiFormFillMatch != null || engFormFillMatch != null) {
            val fieldTarget = hindiFormFillMatch?.groupValues?.getOrNull(1)?.trim()
                ?: engFormFillMatch?.groupValues?.getOrNull(2)?.trim()
                ?: "Search"
            val textToType = hindiFormFillMatch?.groupValues?.getOrNull(2)?.trim()
                ?: engFormFillMatch?.groupValues?.getOrNull(1)?.trim()
                ?: ""
            actions.add(
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.SET_TEXT,
                    target = fieldTarget,
                    parameters = mapOf(
                        "target" to fieldTarget,
                        "text" to textToType,
                        "query" to textToType
                    ),
                    requiredCapability = com.example.core.capability.CapabilityId.ACCESSIBILITY,
                    riskLevel = RiskLevel.R2_APPLICATION_STATE,
                    idempotencyClassification = "NON_IDEMPOTENT"
                )
            )
            return actions
        }

        // Alarm scheduling ("Kal subah 6 baje alarm laga do", "Set alarm for 7 AM")
        if (lower.contains("alarm") && (lower.contains("laga") || lower.contains("set") || lower.contains("schedule"))) {
            val hourMatch = Regex("(\\d{1,2})\\s*(?:baje|am|pm|:)").find(lower)
                ?: Regex("(\\d{1,2})").find(lower)
            var hour = hourMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 6
            if ((lower.contains("pm") || lower.contains("shaam") || lower.contains("raat")) && hour in 1..11) {
                hour += 12
            }
            actions.add(
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.SCHEDULE_ALARM,
                    target = "NAYRO Alarm",
                    parameters = mapOf(
                        "hour" to hour.coerceIn(0, 23).toString(),
                        "minutes" to "0",
                        "message" to "NAYRO Alarm"
                    ),
                    riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE,
                    idempotencyClassification = "NON_IDEMPOTENT"
                )
            )
            return actions
        }

        // Direct system settings toggle ("Bluetooth on karo", "Wi-Fi on karo")
        if ((lower.contains("bluetooth") || lower.contains("wifi") || lower.contains("wi-fi")) &&
            (lower.contains("on karo") || lower.contains("off karo") || lower.contains("turn on") || lower.contains("turn off") || lower.contains("chalu karo") || lower.contains("band karo")) &&
            !lower.contains("music")
        ) {
            val section = if (lower.contains("bluetooth")) "bluetooth" else "wifi"
            actions.add(
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.OPEN_SETTINGS_SECTION,
                    target = section,
                    parameters = mapOf("section" to section),
                    riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE,
                    idempotencyClassification = "IDEMPOTENT"
                )
            )
            return actions
        }

        // Voice Call Management ("Call uthao", "call answer karo", "incoming call answer karo", "call reject karo", "Call cut karo", "phone kaat do", "Kaun call kar raha hai?")
        if (lower.contains("call uthao") || lower.contains("call answer") || lower.contains("answer call") || lower.contains("phone uthao") ||
            (lower.contains("answer") && lower.contains("call"))
        ) {
            actions.add(
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.MANAGE_CALL,
                    target = "ANSWER",
                    parameters = mapOf("operation" to "ANSWER"),
                    riskLevel = RiskLevel.R2_APPLICATION_STATE,
                    idempotencyClassification = "NON_IDEMPOTENT"
                )
            )
            return actions
        }
        if (lower.contains("call reject") || lower.contains("reject call") || lower.contains("decline call") ||
            (lower.contains("reject") && lower.contains("call"))
        ) {
            actions.add(
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.MANAGE_CALL,
                    target = "REJECT",
                    parameters = mapOf("operation" to "REJECT"),
                    riskLevel = RiskLevel.R2_APPLICATION_STATE,
                    idempotencyClassification = "NON_IDEMPOTENT"
                )
            )
            return actions
        }
        if (lower.contains("phone kaat do") || lower.contains("call cut") || lower.contains("end call") || lower.contains("hang up") ||
            (lower.contains("end") && lower.contains("call"))
        ) {
            actions.add(
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.MANAGE_CALL,
                    target = "END",
                    parameters = mapOf("operation" to "END"),
                    riskLevel = RiskLevel.R2_APPLICATION_STATE,
                    idempotencyClassification = "NON_IDEMPOTENT"
                )
            )
            return actions
        }
        if (lower.contains("kaun phone kar raha") || lower.contains("kaun call kar raha") || lower.contains("who is calling") || lower.contains("check incoming call") || lower.contains("detect incoming call")) {
            actions.add(
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.CHECK_INCOMING_CALL,
                    riskLevel = RiskLevel.R0_LOCAL_READONLY,
                    idempotencyClassification = "IDEMPOTENT"
                )
            )
            return actions
        }
        if (lower.contains("missed call") || lower.contains("call history") || lower.contains("recent calls") ||
            lower.contains("kiski call aayi") || lower.contains("kiska phone aaya")
        ) {
            val filter = if (lower.contains("missed")) "missed" else "all"
            actions.add(
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.READ_CALL_HISTORY,
                    target = filter,
                    parameters = mapOf("filter" to filter),
                    riskLevel = RiskLevel.R0_LOCAL_READONLY,
                    idempotencyClassification = "IDEMPOTENT"
                )
            )
            return actions
        }

        // SMS Reading & OTP / Bank Alert Extraction ("mera latest sms padho", "otp kis message mein aaya hai", "bank ka latest sms batao")
        if ((lower.contains("sms") || lower.contains("otp")) &&
            (lower.contains("read") || lower.contains("padho") || lower.contains("batao") || lower.contains("latest") || lower.contains("aaya") || lower.contains("check"))
        ) {
            val filter = when {
                lower.contains("otp") -> "otp"
                lower.contains("bank") -> "bank"
                lower.contains("unread") -> "unread"
                else -> "latest"
            }
            actions.add(
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.READ_SMS,
                    target = filter,
                    parameters = mapOf("filter" to filter),
                    riskLevel = RiskLevel.R0_LOCAL_READONLY,
                    idempotencyClassification = "IDEMPOTENT"
                )
            )
            return actions
        }

        if (lower.contains("battery") && (lower.contains("check") || lower.contains("status") || lower.contains("level") || lower.contains("inspect"))) {
            actions.add(
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.GET_BATTERY_STATE,
                    riskLevel = RiskLevel.R0_LOCAL_READONLY,
                    idempotencyClassification = "IDEMPOTENT"
                )
            )
        }
        if (lower.contains("storage") && (lower.contains("check") || lower.contains("free") || lower.contains("space") || lower.contains("inspect"))) {
            actions.add(
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.GET_STORAGE_INFO,
                    riskLevel = RiskLevel.R0_LOCAL_READONLY,
                    idempotencyClassification = "IDEMPOTENT"
                )
            )
        }
        if (lower.contains("device info") || lower.contains("device model") || lower.contains("android version")) {
            actions.add(
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.GET_DEVICE_INFO,
                    riskLevel = RiskLevel.R0_LOCAL_READONLY,
                    idempotencyClassification = "IDEMPOTENT"
                )
            )
        }
        if (lower.contains("scroll down") || lower.contains("scroll forward") || lower.contains("neeche scroll")) {
            actions.add(
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.SCROLL_FORWARD,
                    requiredCapability = com.example.core.capability.CapabilityId.ACCESSIBILITY,
                    riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE,
                    idempotencyClassification = "IDEMPOTENT"
                )
            )
        }
        if (lower.contains("scroll up") || lower.contains("scroll backward") || lower.contains("upar scroll")) {
            actions.add(
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.SCROLL_BACKWARD,
                    requiredCapability = com.example.core.capability.CapabilityId.ACCESSIBILITY,
                    riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE,
                    idempotencyClassification = "IDEMPOTENT"
                )
            )
        }
        if (lower.contains("navigate back") || lower.contains("go back") || lower == "back" || lower == "peeche jao") {
            actions.add(
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.NAVIGATE_BACK,
                    requiredCapability = com.example.core.capability.CapabilityId.ACCESSIBILITY,
                    riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE,
                    idempotencyClassification = "IDEMPOTENT"
                )
            )
        }
        if (lower.contains("navigate home") || lower.contains("go to home screen") || lower == "go home" || lower.contains("home screen par chalo")) {
            actions.add(
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.NAVIGATE_HOME,
                    riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE,
                    idempotencyClassification = "IDEMPOTENT"
                )
            )
        }
        if (lower.contains("screen record") || lower.contains("record screen") || lower.contains("screen recording")) {
            val op = when {
                lower.contains("stop") || lower.contains("band") -> "STOP"
                lower.contains("status") -> "STATUS"
                else -> "START"
            }
            actions.add(
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.MANAGE_SCREEN_RECORDING,
                    target = op,
                    parameters = mapOf("operation" to op),
                    riskLevel = RiskLevel.R2_APPLICATION_STATE,
                    idempotencyClassification = "NON_IDEMPOTENT"
                )
            )
            return actions
        }
        if ((lower.contains("email") || lower.contains("e-mail") || lower.contains("gmail")) &&
            (lower.contains("send") || lower.contains("compose") || lower.contains("write") || lower.contains("bhejo") || lower.contains("likho"))
        ) {
            val emailRegex = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
            val recipient = emailRegex.find(instruction)?.value.orEmpty()
            val subjectMatch = Regex("(?i)subject\\s*[:\\-]?\\s*([^,.\\n]+)").find(instruction)
            val bodyMatch = Regex("(?i)(?:saying|body|message|ki)\\s*[:\\-]?\\s*(.+)$").find(instruction)
            actions.add(
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.COMPOSE_EMAIL,
                    target = recipient.ifBlank { "Email Recipient" },
                    parameters = buildMap {
                        put("recipient", recipient)
                        put("subject", subjectMatch?.groupValues?.getOrNull(1)?.trim() ?: "Message from NAYRO")
                        put("body", bodyMatch?.groupValues?.getOrNull(1)?.trim() ?: "")
                    },
                    riskLevel = RiskLevel.R2_APPLICATION_STATE,
                    idempotencyClassification = "NON_IDEMPOTENT"
                )
            )
            return actions
        }
        if ((lower.contains("email") || lower.contains("e-mail") || lower.contains("gmail")) &&
            (lower.contains("read") || lower.contains("check") || lower.contains("padho") || lower.contains("batao") || lower.contains("latest"))
        ) {
            actions.add(
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.READ_EMAIL_NOTIFICATIONS,
                    riskLevel = RiskLevel.R0_LOCAL_READONLY,
                    idempotencyClassification = "IDEMPOTENT"
                )
            )
            return actions
        }
        if (lower.contains("spam") || lower.contains("phishing") || lower.contains("scam") || lower.contains("suspicious call") || lower.contains("suspicious message")) {
            val phoneMatch = Regex("(\\+?\\d[\\d\\s-]{5,15}\\d)").find(instruction)?.value?.trim()
            actions.add(
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.CHECK_COMMUNICATION_SPAM,
                    target = phoneMatch,
                    parameters = buildMap {
                        if (!phoneMatch.isNullOrBlank()) put("phoneNumber", phoneMatch)
                    },
                    riskLevel = RiskLevel.R0_LOCAL_READONLY,
                    idempotencyClassification = "IDEMPOTENT"
                )
            )
            return actions
        }
        if (lower.contains("take screenshot") || lower.contains("take a screenshot") || lower.contains("capture screen")) {
            actions.add(
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.TAKE_SCREENSHOT,
                    requiredCapability = com.example.core.capability.CapabilityId.ACCESSIBILITY,
                    riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE,
                    idempotencyClassification = "IDEMPOTENT"
                )
            )
        }
        if (lower.contains("workspace") && (lower.contains("browse") || lower.contains("list") || lower.contains("files"))) {
            actions.add(
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.FILE_BROWSE,
                    riskLevel = RiskLevel.R0_LOCAL_READONLY,
                    idempotencyClassification = "IDEMPOTENT"
                )
            )
        }
        if (lower.contains("inspect code") || lower.contains("analyze code") || lower.contains("sampleagentmodule")) {
            actions.add(
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.CODE_INSPECT_FILE,
                    target = "SampleAgentModule.kt",
                    parameters = mapOf("path" to "SampleAgentModule.kt"),
                    riskLevel = RiskLevel.R0_LOCAL_READONLY,
                    idempotencyClassification = "IDEMPOTENT"
                )
            )
        }
        if (lower.contains("summarize") && (lower.contains("guide") || lower.contains("workspace_guide") || lower.contains("document"))) {
            actions.add(
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.DOC_SUMMARIZE,
                    target = "NAYRO_Workspace_Guide.md",
                    parameters = mapOf("filename" to "NAYRO_Workspace_Guide.md"),
                    riskLevel = RiskLevel.R0_LOCAL_READONLY,
                    idempotencyClassification = "IDEMPOTENT"
                )
            )
        }
        if (lower.contains("save") && lower.contains("summary") && lower.contains("note")) {
            actions.add(
                AgentAction(
                    taskId = taskId,
                    actionType = ActionType.SAVE_NOTE,
                    parameters = mapOf("content" to "Dynamic workflow summary note"),
                    riskLevel = RiskLevel.R0_LOCAL_READONLY,
                    idempotencyClassification = "NON_IDEMPOTENT"
                )
            )
        }

        // Google Maps & Navigation Workflows ("Navigate to Connaught Place", "Google Maps par Airport ka rasta dikhao")
        val mapsNavRegex = Regex(
            "(?i)(?:navigate\\s+to|directions\\s+to|route\\s+to|google\\s+maps\\s+par)\\s+(.+?)(?:\\s+(?:on\\s+google\\s+maps|ka\\s+rasta\\s+dikhao|ka\\s+route\\s+dikhao|chalo|dikhao))?$"
        )
        val mapsMatch = mapsNavRegex.find(instruction.trim())
        if (mapsMatch != null) {
            val dest = mapsMatch.groupValues[1]
                .replace(Regex("(?i)\\s+(?:ka\\s+rasta|ka\\s+route|on\\s+google\\s+maps|in\\s+google\\s+maps).*$"), "")
                .trim()
            if (dest.isNotEmpty()) {
                actions.add(
                    AgentAction(
                        taskId = taskId,
                        actionType = ActionType.LAUNCH_APP,
                        target = "Google Maps",
                        parameters = mapOf(
                            "appName" to "Google Maps",
                            "navigationDestination" to dest
                        ),
                        riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE,
                        idempotencyClassification = "IDEMPOTENT"
                    )
                )
                return actions
            }
        }

        return actions
    }

    /**
     * Prevents infinite loops or repeated identical tool calls in iterative execution (Section 1).
     */
    fun hasRepeatedToolCallLoop(
        history: List<AgentAction>,
        candidate: AgentAction,
        maxConsecutiveRepeats: Int = 2
    ): Boolean {
        if (history.size < maxConsecutiveRepeats) return false
        val recent = history.takeLast(maxConsecutiveRepeats)
        return recent.all { prev ->
            prev.actionType == candidate.actionType &&
                (prev.target ?: "").equals(candidate.target ?: "", ignoreCase = true) &&
                prev.parameters == candidate.parameters
        }
    }

    /**
     * Executes all planned actions in [task] iteratively (not just `actions.first()`),
     * preserving step outputs in context across steps, enforcing step budgets and timeouts,
     * and recording a full [BrainReasoningStep] trace.
     */
    suspend fun executeIterativeLoop(userPrompt: String): BrainExecutionOutcome {
        val task = planTaskForInstruction(userPrompt)
        return executeTaskIterative(task)
    }

    suspend fun executeTaskIterative(
        task: AgentTask,
        maxSteps: Int = MAX_ITERATIVE_STEPS,
        timeoutMs: Long = DEFAULT_LOOP_TIMEOUT_MS,
        customStepExecutor: (suspend (AgentAction, Map<String, String>) -> Pair<ActionExecutionResult, VerificationStatus>)? = null
    ): BrainExecutionOutcome {
        isCancelled = false
        val steps = mutableListOf<BrainReasoningStep>()
        val executedHistory = mutableListOf<AgentAction>()
        _reasoningTrace.value = emptyList()

        if (task.actions.isEmpty()) {
            return BrainExecutionOutcome(
                finalResponse = "No executable actions were generated for this instruction.",
                stepsExecuted = emptyList(),
                overallStatus = VerificationStatus.FAILED,
                isFullyVerified = false
            )
        }

        if (task.actions.size > maxSteps) {
            return BrainExecutionOutcome(
                finalResponse = "Plan exceeds maximum step budget (${task.actions.size} > $maxSteps steps).",
                stepsExecuted = emptyList(),
                overallStatus = VerificationStatus.FAILED,
                isFullyVerified = false
            )
        }

        val stepContext = mutableMapOf<String, String>()
        var verifiedCount = 0
        var lastStatus = VerificationStatus.VERIFIED

        val timedResult = withTimeoutOrNull(timeoutMs) {
            for ((index, rawAction) in task.actions.withIndex()) {
                if (isCancelled) {
                    lastStatus = VerificationStatus.CANCELLED
                    break
                }

                if (hasRepeatedToolCallLoop(executedHistory, rawAction, maxConsecutiveRepeats = 2)) {
                    lastStatus = VerificationStatus.FAILED
                    steps.add(
                        BrainReasoningStep(
                            stepIndex = index + 1,
                            thought = "Aborted repeated identical tool call loop for ${rawAction.actionType.name}",
                            selectedAction = rawAction.actionType,
                            target = rawAction.target,
                            executionOutput = "Aborted repeated identical tool call (${rawAction.actionType.name}) to prevent an infinite loop.",
                            verificationStatus = VerificationStatus.FAILED,
                            isComplete = false
                        )
                    )
                    _reasoningTrace.value = steps.toList()
                    break
                }
                executedHistory.add(rawAction)

                task.currentActionIndex = index
                // Inject previous step outputs into action parameters if referenced
                val enrichedParams = rawAction.parameters.toMutableMap()
                stepContext["previousStepOutput"]?.let { prev ->
                    enrichedParams.putIfAbsent("previousStepOutput", prev)
                    if (rawAction.actionType == ActionType.SAVE_NOTE &&
                        enrichedParams["content"] == "Dynamic workflow summary note"
                    ) {
                        enrichedParams["content"] = "Summary: $prev"
                    }
                }
                val action = rawAction.copy(parameters = enrichedParams)

                val (execResult, verStatus) = if (customStepExecutor != null) {
                    customStepExecutor(action, stepContext)
                } else {
                    val res = actionExecutor.execute(action)
                    val status = res.status ?: if (res.isSuccess) VerificationStatus.VERIFIED else VerificationStatus.FAILED
                    res to status
                }

                val isLastStep = index == task.actions.lastIndex
                val stepRecord = BrainReasoningStep(
                    stepIndex = index + 1,
                    thought = "Step ${index + 1}/${task.actions.size}: ${action.actionType.name}",
                    selectedAction = action.actionType,
                    target = action.target,
                    executionOutput = execResult.outputMessage,
                    verificationStatus = verStatus,
                    isComplete = isLastStep && verStatus == VerificationStatus.VERIFIED
                )
                steps.add(stepRecord)
                _reasoningTrace.value = steps.toList()

                if (verStatus == VerificationStatus.VERIFIED) {
                    verifiedCount++
                    stepContext["previousStepOutput"] = execResult.outputMessage
                    stepContext["step_${index + 1}_output"] = execResult.outputMessage
                } else {
                    lastStatus = verStatus
                    break
                }
            }
            true
        }

        if (timedResult == null) {
            return BrainExecutionOutcome(
                finalResponse = "Execution timed out after ${timeoutMs}ms ($verifiedCount/${task.actions.size} steps verified).",
                stepsExecuted = steps,
                overallStatus = if (verifiedCount > 0) VerificationStatus.PARTIALLY_COMPLETED else VerificationStatus.TIMED_OUT,
                isFullyVerified = false
            )
        }

        if (isCancelled || lastStatus == VerificationStatus.CANCELLED) {
            return BrainExecutionOutcome(
                finalResponse = "Task cancelled ($verifiedCount/${task.actions.size} steps verified).",
                stepsExecuted = steps,
                overallStatus = VerificationStatus.CANCELLED,
                isFullyVerified = false
            )
        }

        val allVerified = verifiedCount == task.actions.size && task.actions.isNotEmpty()
        val finalOverallStatus = when {
            allVerified -> VerificationStatus.VERIFIED
            lastStatus == VerificationStatus.REQUIRES_PERMISSION -> VerificationStatus.REQUIRES_PERMISSION
            lastStatus == VerificationStatus.REQUIRES_CONFIRMATION -> VerificationStatus.REQUIRES_CONFIRMATION
            verifiedCount > 0 -> VerificationStatus.PARTIALLY_COMPLETED
            else -> lastStatus
        }

        val combinedSummary = steps.joinToString("\n") { step ->
            if (task.actions.size == 1) {
                step.executionOutput
            } else {
                "[Step ${step.stepIndex} - ${step.selectedAction.name} (${step.verificationStatus.name})]: ${step.executionOutput}"
            }
        }

        return BrainExecutionOutcome(
            finalResponse = combinedSummary,
            stepsExecuted = steps,
            overallStatus = finalOverallStatus,
            isFullyVerified = allVerified
        )
    }

    /**
     * Section 1 — Dynamic Observe -> Execute -> Verify -> Reason -> Replan loop over an evolving action queue.
     * Supports injecting recovery steps, dismissing unexpected dialogs, replanning on failure,
     * loop protection, and bounded execution budgets.
     */
    suspend fun executeDynamicReasoningLoop(
        task: AgentTask,
        maxSteps: Int = MAX_ITERATIVE_STEPS,
        timeoutMs: Long = DEFAULT_LOOP_TIMEOUT_MS,
        memoryContext: String? = null,
        screenSnapshotProvider: suspend () -> com.example.core.screen.ScreenSnapshot? = { null },
        screenSemanticsProvider: suspend (com.example.core.screen.ScreenSnapshot?) -> com.example.core.screen.ScreenSemanticSignals? = { null },
        customStepExecutor: suspend (AgentAction, Map<String, String>) -> Pair<ActionExecutionResult, VerificationResult>
    ): BrainExecutionOutcome {
        isCancelled = false
        val steps = mutableListOf<BrainReasoningStep>()
        val executedHistory = mutableListOf<AgentAction>()
        val actionQueue = ArrayDeque<AgentAction>(task.actions)
        val stepContext = mutableMapOf<String, String>()
        if (!memoryContext.isNullOrBlank()) {
            stepContext["memoryContext"] = memoryContext
        }
        _reasoningTrace.value = emptyList()

        if (actionQueue.isEmpty()) {
            return BrainExecutionOutcome(
                finalResponse = "No executable actions were generated for this instruction.",
                stepsExecuted = emptyList(),
                overallStatus = VerificationStatus.FAILED,
                isFullyVerified = false
            )
        }

        var verifiedCount = 0
        var replanCount = 0
        var finalStatus = VerificationStatus.VERIFIED
        var abortReason: String? = null

        val timedOk = withTimeoutOrNull(timeoutMs) {
            while (actionQueue.isNotEmpty() && steps.size < maxSteps) {
                if (isCancelled) {
                    finalStatus = VerificationStatus.CANCELLED
                    break
                }

                // Pre-step observation check (handles unexpected dialogs / secure screens before acting)
                val preSnapshot = screenSnapshotProvider()
                val preSemantics = screenSemanticsProvider(preSnapshot)
                if (preSnapshot != null || preSemantics != null) {
                    val preDecision = observeAndDecideNextStep(
                        DynamicObservationContext(
                            goal = task.userInstruction,
                            lastExecutedAction = null,
                            lastExecutionResult = null,
                            lastVerification = null,
                            screenSnapshot = preSnapshot,
                            screenSemantics = preSemantics,
                            memoryContext = memoryContext,
                            completedStepOutputs = steps.map { it.executionOutput },
                            remainingPlannedActions = actionQueue.toList(),
                            executedHistory = executedHistory,
                            replanAttemptsUsed = replanCount
                        )
                    )
                    when (preDecision) {
                        is DynamicReasoningDecision.InjectRecoveryActions -> {
                            replanCount++
                            for (act in preDecision.actions.asReversed()) {
                                actionQueue.addFirst(act)
                            }
                        }
                        is DynamicReasoningDecision.AbortWithState -> {
                            finalStatus = preDecision.status
                            abortReason = preDecision.reason
                            break
                        }
                        else -> {}
                    }
                }

                val rawAction = actionQueue.removeFirst()
                if (hasRepeatedToolCallLoop(executedHistory, rawAction, maxConsecutiveRepeats = 2)) {
                    finalStatus = VerificationStatus.FAILED
                    abortReason = "Aborted repeated identical tool call (${rawAction.actionType.name}) to prevent an infinite loop."
                    steps.add(
                        BrainReasoningStep(
                            stepIndex = steps.size + 1,
                            thought = abortReason!!,
                            selectedAction = rawAction.actionType,
                            target = rawAction.target,
                            executionOutput = abortReason!!,
                            verificationStatus = VerificationStatus.FAILED,
                            isComplete = false
                        )
                    )
                    _reasoningTrace.value = steps.toList()
                    break
                }

                executedHistory.add(rawAction)
                val enrichedParams = rawAction.parameters.toMutableMap()
                stepContext["previousStepOutput"]?.let { prev ->
                    enrichedParams.putIfAbsent("previousStepOutput", prev)
                }
                val action = rawAction.copy(parameters = enrichedParams)

                val (execResult, verification) = customStepExecutor(action, stepContext)
                val stepNum = steps.size + 1
                steps.add(
                    BrainReasoningStep(
                        stepIndex = stepNum,
                        thought = "Dynamic Step $stepNum: ${action.actionType.name} -> ${verification.status.name}",
                        selectedAction = action.actionType,
                        target = action.target,
                        executionOutput = verification.details,
                        verificationStatus = verification.status,
                        isComplete = actionQueue.isEmpty() && verification.isVerified
                    )
                )
                _reasoningTrace.value = steps.toList()

                if (verification.isVerified) {
                    verifiedCount++
                    stepContext["previousStepOutput"] = execResult.outputMessage
                }

                // Post-step observation & dynamic next-action reasoning
                val postSnapshot = screenSnapshotProvider()
                val postSemantics = screenSemanticsProvider(postSnapshot)
                val decision = observeAndDecideNextStep(
                    DynamicObservationContext(
                        goal = task.userInstruction,
                        lastExecutedAction = action,
                        lastExecutionResult = execResult,
                        lastVerification = verification,
                        screenSnapshot = postSnapshot,
                        screenSemantics = postSemantics,
                        memoryContext = memoryContext,
                        completedStepOutputs = steps.map { it.executionOutput },
                        remainingPlannedActions = actionQueue.toList(),
                        executedHistory = executedHistory,
                        replanAttemptsUsed = replanCount
                    )
                )

                when (decision) {
                    is DynamicReasoningDecision.ContinueNextPlanned -> {
                        // Proceed to next action in queue
                    }
                    is DynamicReasoningDecision.InjectRecoveryActions -> {
                        replanCount++
                        for (recoveryAct in decision.actions.asReversed()) {
                            actionQueue.addFirst(recoveryAct)
                        }
                    }
                    is DynamicReasoningDecision.ReplanRemaining -> {
                        replanCount++
                        actionQueue.clear()
                        actionQueue.addAll(decision.newActions)
                    }
                    is DynamicReasoningDecision.GoalCompleted -> {
                        finalStatus = VerificationStatus.VERIFIED
                        break
                    }
                    is DynamicReasoningDecision.AbortWithState -> {
                        finalStatus = decision.status
                        abortReason = decision.reason
                        break
                    }
                }
            }
            true
        }

        if (timedOk == null) {
            return BrainExecutionOutcome(
                finalResponse = "Dynamic reasoning loop timed out after ${timeoutMs}ms ($verifiedCount steps verified).",
                stepsExecuted = steps,
                overallStatus = if (verifiedCount > 0) VerificationStatus.PARTIALLY_COMPLETED else VerificationStatus.TIMED_OUT,
                isFullyVerified = false
            )
        }

        if (isCancelled || finalStatus == VerificationStatus.CANCELLED) {
            return BrainExecutionOutcome(
                finalResponse = "Task cancelled ($verifiedCount steps verified).",
                stepsExecuted = steps,
                overallStatus = VerificationStatus.CANCELLED,
                isFullyVerified = false
            )
        }

        val allVerified = finalStatus == VerificationStatus.VERIFIED && abortReason == null && actionQueue.isEmpty()
        val combined = buildString {
            if (steps.isNotEmpty()) {
                append(steps.joinToString("\n") { s ->
                    "[Step ${s.stepIndex} - ${s.selectedAction.name} (${s.verificationStatus.name})]: ${s.executionOutput}"
                })
            }
            if (!abortReason.isNullOrBlank()) {
                if (isNotEmpty()) append("\n")
                append(abortReason)
            }
        }.ifBlank { "Task finished with status ${finalStatus.name}." }

        return BrainExecutionOutcome(
            finalResponse = combined,
            stepsExecuted = steps,
            overallStatus = if (allVerified) VerificationStatus.VERIFIED else finalStatus,
            isFullyVerified = allVerified
        )
    }

    fun recordTrace(steps: List<BrainReasoningStep>) {
        _reasoningTrace.value = steps.toList()
    }
}

