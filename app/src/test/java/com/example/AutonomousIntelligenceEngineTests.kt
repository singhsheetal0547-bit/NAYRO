package com.example

import com.example.core.agent.ActionExecutionResult
import com.example.core.agent.ActionExecutionState
import com.example.core.agent.ActionExecutor
import com.example.core.agent.ActionType
import com.example.core.agent.AgentAction
import com.example.core.agent.AgentBrain
import com.example.core.agent.AgentExecutionSummary
import com.example.core.agent.AgentRuntime
import com.example.core.agent.AgentTask
import com.example.core.agent.DynamicObservationContext
import com.example.core.agent.DynamicReasoningDecision
import com.example.core.agent.RiskLevel
import com.example.core.agent.TaskClassifier
import com.example.core.agent.TaskPlanner
import com.example.core.agent.TaskState
import com.example.core.agent.TaskStateManager
import com.example.core.agent.UserIntentType
import com.example.core.agent.VerificationResult
import com.example.core.agent.VerificationStatus
import com.example.core.identity.AssistantIdentityStore
import com.example.core.media.MediaControlManager
import com.example.core.media.MediaPlaybackVerificationState
import com.example.core.memory.MemoryManager
import com.example.core.permission.PermissionManager
import com.example.core.provider.AIProviderManager
import com.example.core.screen.ScreenIntelligenceManager
import com.example.core.screen.ScreenNodeInfo
import com.example.core.screen.ScreenSemanticSignals
import com.example.core.screen.ScreenSnapshot
import com.example.core.service.DeviceOperatingStateManager
import com.example.core.skills.SkillManager
import com.example.core.subagent.SubAgentManager
import com.example.core.voice.VoiceManager
import com.example.core.voice.engine.LocalWakeWordEngine
import com.example.core.voice.engine.WakeAssetFormat
import com.example.core.voice.engine.WakePhraseSupportStatus
import com.example.core.workspace.WorkspaceManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Comprehensive JVM verification suite for NAYRO's Advanced Autonomous Intelligence Engine:
 * - Dynamic observe -> verify -> reason -> replan reasoning loop
 * - Strict fail-closed permission verification
 * - Honest JSON MFCC+DTW wake-word engine, configurable wake phrases, and false-positive rejection
 * - Two-stage wake word & follow-up voice-to-AgentRuntime routing
 * - End-to-end YouTube search -> observe -> rank/select -> playback verification pipeline
 * - Unified AssistantIdentityStore without hardcoded persona lock-in
 * - Consistent ActionExecutionState reporting, duplicate action prevention, and checkpoint recovery
 */
class AutonomousIntelligenceEngineTests {

    private fun allocateWithoutInit(clazz: Class<*>): Any {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null)
        val allocateInstance = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocateInstance.invoke(unsafe, clazz)
    }

    private fun createHeadlessBrain(): AgentBrain {
        val dummyExecutor = allocateWithoutInit(ActionExecutor::class.java) as ActionExecutor
        val dummyProvider = allocateWithoutInit(AIProviderManager::class.java) as AIProviderManager
        val dummyWorkspace = allocateWithoutInit(WorkspaceManager::class.java) as WorkspaceManager
        return AgentBrain(
            providerManager = dummyProvider,
            actionExecutor = dummyExecutor,
            workspaceManager = dummyWorkspace,
            skillManager = SkillManager(),
            subAgentManager = SubAgentManager()
        )
    }

    private fun createHeadlessRuntime(): AgentRuntime {
        val instance = allocateWithoutInit(AgentRuntime::class.java) as AgentRuntime
        fun setField(name: String, value: Any?) {
            val f = AgentRuntime::class.java.getDeclaredField(name)
            f.isAccessible = true
            f.set(instance, value)
        }
        setField("stateManager", TaskStateManager())
        setField("_currentState", kotlinx.coroutines.flow.MutableStateFlow<com.example.core.agent.AgentState>(com.example.core.agent.AgentState.Idle))
        setField("_currentTask", kotlinx.coroutines.flow.MutableStateFlow<AgentTask?>(null))
        setField("completedSideEffectSignatures", java.util.Collections.synchronizedSet(mutableSetOf<String>()))
        setField("uncertainExternalActionSignatures", java.util.Collections.synchronizedSet(mutableSetOf<String>()))
        setField("assistantName", "NAYRO")
        setField("operatingStateManager", DeviceOperatingStateManager(null))
        return instance
    }

    // -----------------------------------------------------------------------
    // 1. DYNAMIC REASONING LOOP TESTS (Observe -> Verify -> Reason -> Replan)
    // -----------------------------------------------------------------------

    @Test
    fun testDynamicReasoningLoopDismissesUnexpectedModalAndCompletes() = runBlocking {
        val brain = createHeadlessBrain()
        val task = AgentTask(
            taskId = "dyn-modal-1",
            userInstruction = "Tap Subscribe button",
            resolvedIntent = "DYNAMIC_TEST",
            actions = listOf(
                AgentAction(
                    taskId = "dyn-modal-1",
                    actionType = ActionType.CLICK_ELEMENT,
                    target = "Subscribe",
                    riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE
                )
            )
        )

        var modalVisible = true
        val executedTargets = mutableListOf<String>()

        val outcome = brain.executeDynamicReasoningLoop(
            task = task,
            screenSnapshotProvider = {
                if (modalVisible) {
                    ScreenSnapshot(
                        packageName = "com.google.android.youtube",
                        visibleTexts = listOf("Try YouTube Premium", "Not now", "OK", "Cancel"),
                        interactiveElements = listOf(
                            ScreenNodeInfo(text = "Not now", contentDescription = null, viewId = null, className = "android.widget.Button", isClickable = true, isEditable = false, isScrollable = false, boundsLeft = 100, boundsTop = 1000, boundsRight = 400, boundsBottom = 1120),
                            ScreenNodeInfo(text = "Subscribe", contentDescription = null, viewId = null, className = "android.widget.Button", isClickable = true, isEditable = false, isScrollable = false, boundsLeft = 500, boundsTop = 1000, boundsRight = 800, boundsBottom = 1120)
                        ),
                        totalNodeCount = 4
                    )
                } else {
                    ScreenSnapshot(
                        packageName = "com.google.android.youtube",
                        visibleTexts = listOf("Channel Page", "Subscribe"),
                        interactiveElements = listOf(
                            ScreenNodeInfo(text = "Subscribe", contentDescription = null, viewId = null, className = "android.widget.Button", isClickable = true, isEditable = false, isScrollable = false, boundsLeft = 500, boundsTop = 1000, boundsRight = 800, boundsBottom = 1120)
                        ),
                        totalNodeCount = 2
                    )
                }
            },
            screenSemanticsProvider = { snap ->
                ScreenSemanticSignals(
                    hasDialogOrPopup = snap?.visibleTexts?.contains("Not now") == true,
                    isLoadingState = false,
                    isErrorScreen = false
                )
            },
            customStepExecutor = { action, _ ->
                executedTargets.add("${action.actionType.name}:${action.target}")
                if (action.target == "Not now") {
                    modalVisible = false
                }
                ActionExecutionResult(true, "Clicked ${action.target}", VerificationStatus.VERIFIED) to
                    VerificationResult(VerificationStatus.VERIFIED, "Verified click on ${action.target}")
            }
        )

        assertTrue(outcome.isFullyVerified)
        assertEquals(VerificationStatus.VERIFIED, outcome.overallStatus)
        assertEquals(listOf("CLICK_ELEMENT:Not now", "CLICK_ELEMENT:Subscribe"), executedTargets)
    }

    @Test
    fun testDynamicReasoningLoopRecoversFromFailedClickByScrollingAndRetrying() = runBlocking {
        val brain = createHeadlessBrain()
        val task = AgentTask(
            taskId = "dyn-scroll-1",
            userInstruction = "Click Download button",
            resolvedIntent = "DYNAMIC_TEST",
            actions = listOf(
                AgentAction(
                    taskId = "dyn-scroll-1",
                    actionType = ActionType.CLICK_ELEMENT,
                    target = "Download",
                    riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE
                )
            )
        )

        var scrolled = false
        val executedActions = mutableListOf<ActionType>()

        val outcome = brain.executeDynamicReasoningLoop(
            task = task,
            customStepExecutor = { action, _ ->
                executedActions.add(action.actionType)
                when (action.actionType) {
                    ActionType.SCROLL_FORWARD -> {
                        scrolled = true
                        ActionExecutionResult(true, "Scrolled down", VerificationStatus.VERIFIED) to
                            VerificationResult(VerificationStatus.VERIFIED, "Viewport scrolled forward")
                    }
                    ActionType.CLICK_ELEMENT -> {
                        if (!scrolled) {
                            ActionExecutionResult(false, "Download button not visible", VerificationStatus.FAILED) to
                                VerificationResult(VerificationStatus.FAILED, "Download button not visible on screen")
                        } else {
                            ActionExecutionResult(true, "Clicked Download", VerificationStatus.VERIFIED) to
                                VerificationResult(VerificationStatus.VERIFIED, "Clicked Download button successfully")
                        }
                    }
                    else -> ActionExecutionResult(false, "Unexpected", VerificationStatus.FAILED) to
                        VerificationResult(VerificationStatus.FAILED, "Unexpected")
                }
            }
        )

        assertTrue(outcome.isFullyVerified)
        assertEquals(
            listOf(ActionType.CLICK_ELEMENT, ActionType.SCROLL_FORWARD, ActionType.CLICK_ELEMENT),
            executedActions
        )
    }

    @Test
    fun testDynamicReasoningLoopProtectsSecureAuthenticationScreen() = runBlocking {
        val brain = createHeadlessBrain()
        val secureSnapshot = ScreenSnapshot(
            packageName = "com.bank.app",
            visibleTexts = listOf("Enter UPI PIN to continue"),
            interactiveElements = listOf(
                ScreenNodeInfo(text = "PIN", contentDescription = null, viewId = null, className = "android.widget.EditText", isClickable = true, isEditable = true, isScrollable = false, isPassword = true, boundsLeft = 100, boundsTop = 400, boundsRight = 600, boundsBottom = 520)
            ),
            totalNodeCount = 2
        )
        val decision = brain.observeAndDecideNextStep(
            DynamicObservationContext(
                goal = "Confirm transaction",
                lastExecutedAction = null,
                lastExecutionResult = null,
                lastVerification = null,
                screenSnapshot = secureSnapshot,
                screenSemantics = ScreenSemanticSignals(false, false, false),
                remainingPlannedActions = listOf(
                    AgentAction(actionType = ActionType.CLICK_ELEMENT, target = "Submit")
                )
            )
        )

        assertTrue(decision is DynamicReasoningDecision.AbortWithState)
        assertEquals(
            VerificationStatus.REQUIRES_CONFIRMATION,
            (decision as DynamicReasoningDecision.AbortWithState).status
        )
    }

    @Test
    fun testDynamicReasoningLoopPreventsRepeatedIdenticalToolCallLoop() = runBlocking {
        val brain = createHeadlessBrain()
        val repeated = AgentAction(taskId = "loop-1", actionType = ActionType.SCROLL_FORWARD, target = "Feed")
        val task = AgentTask(
            taskId = "loop-1",
            userInstruction = "Scroll repeatedly",
            resolvedIntent = "LOOP_TEST",
            actions = listOf(repeated, repeated, repeated)
        )

        val outcome = brain.executeDynamicReasoningLoop(
            task = task,
            customStepExecutor = { _, _ ->
                ActionExecutionResult(true, "Scrolled", VerificationStatus.VERIFIED) to
                    VerificationResult(VerificationStatus.VERIFIED, "Scrolled")
            }
        )

        assertFalse(outcome.isFullyVerified)
        assertEquals(VerificationStatus.FAILED, outcome.overallStatus)
        assertTrue(outcome.finalResponse.contains("infinite loop", ignoreCase = true))
    }

    // -----------------------------------------------------------------------
    // 2. FAIL-CLOSED PERMISSION VERIFICATION TESTS (CRITICAL BUG FIX)
    // -----------------------------------------------------------------------

    @Test
    fun testPermissionVerificationIsStrictlyFailClosedOnExceptionOrBlank() {
        val runtime = createHeadlessRuntime()
        // permissionManager is null on headless runtime -> previously caught Exception and returned TRUE (fail-open bug!)
        assertFalse(runtime.verifySinglePermissionTargeted(android.Manifest.permission.CALL_PHONE))

        val detail = runtime.verifySinglePermissionDetailed(android.Manifest.permission.CALL_PHONE)
        assertEquals(PermissionManager.PermissionCheckOutcome.INCONCLUSIVE, detail.outcome)
        assertFalse(detail.isGranted)

        val blankDetail = runtime.verifySinglePermissionDetailed("   ")
        assertEquals(PermissionManager.PermissionCheckOutcome.UNAVAILABLE, blankDetail.outcome)
        assertFalse(blankDetail.isGranted)
    }

    @Test
    fun testPermissionDecisionCallbackHandlesGrantAndDenyWithoutGlobalRefresh() = runBlocking {
        val runtime = createHeadlessRuntime()
        val task = AgentTask(
            taskId = "perm-cb-1",
            userInstruction = "Call Rahul",
            resolvedIntent = "PHONE_CALL",
            state = TaskState.WAITING_FOR_PERMISSION,
            actions = listOf(
                AgentAction(
                    taskId = "perm-cb-1",
                    actionType = ActionType.MAKE_PHONE_CALL,
                    target = "Rahul",
                    requiredPermission = android.Manifest.permission.CALL_PHONE
                )
            )
        )

        // Case 1: Permission Denied -> BLOCKED_BY_ANDROID, no global refresh
        runtime.recordPermissionBlockedTask(task, android.Manifest.permission.CALL_PHONE)
        val deniedSummary = runtime.onPermissionDecision(android.Manifest.permission.CALL_PHONE, granted = false)
        assertNotNull(deniedSummary)
        assertFalse(deniedSummary!!.isSuccess)
        assertEquals(VerificationStatus.REQUIRES_PERMISSION, deniedSummary.verificationStatus)
        assertEquals(ActionExecutionState.WAITING_FOR_PERMISSION, deniedSummary.executionState)
        assertEquals(TaskState.BLOCKED_BY_ANDROID, task.state)
        assertFalse(runtime.globalRefreshCalledDuringPermissionDecision)

        // Case 2: Permission Granted -> resumes task via customExecutor without global refresh
        val task2 = task.copy(taskId = "perm-cb-2", state = TaskState.WAITING_FOR_PERMISSION)
        runtime.recordPermissionBlockedTask(task2, android.Manifest.permission.CALL_PHONE)
        var resumedTaskId: String? = null
        val grantedSummary = runtime.onPermissionDecision(
            permission = android.Manifest.permission.CALL_PHONE,
            granted = true,
            customExecutor = { resumed ->
                resumedTaskId = resumed.taskId
                AgentExecutionSummary(resumed, true, "Call placed after permission granted", VerificationStatus.VERIFIED)
            }
        )
        assertEquals("perm-cb-2", resumedTaskId)
        assertTrue(grantedSummary?.isSuccess == true)
        assertEquals(ActionExecutionState.SUCCESS, grantedSummary?.executionState)
        assertFalse(runtime.globalRefreshCalledDuringPermissionDecision)
    }

    // -----------------------------------------------------------------------
    // 3. WAKE-WORD ENGINE & TWO-STAGE VOICE ACTIVATION TESTS
    // -----------------------------------------------------------------------

    @Test
    fun testWakeWordEngineUsesHonestJsonTemplatesAssetAndSupportsConfigurablePhrases() {
        val templatesFile = File("src/main/assets/nayro_kws_templates.json")
            .takeIf { it.exists() }
            ?: File("app/src/main/assets/nayro_kws_templates.json")
        assertTrue("Expected honestly named nayro_kws_templates.json asset", templatesFile.exists())

        val legacyFakeTflite = File("src/main/assets/nayro_kws_model.tflite")
        val legacyFakeTfliteApp = File("app/src/main/assets/nayro_kws_model.tflite")
        assertFalse("Misleading nayro_kws_model.tflite file must not exist", legacyFakeTflite.exists() || legacyFakeTfliteApp.exists())

        val engine = LocalWakeWordEngine(null)
        assertTrue(engine.loadAcousticModel(allowEmbeddedFallbackInTests = true))
        assertEquals(WakeAssetFormat.JSON_ACOUSTIC_TEMPLATES, engine.inspectBundledAssetFormat(allowEmbeddedFallbackInTests = true))

        // Verify configurable example wake phrases are supported out-of-the-box
        assertEquals(WakePhraseSupportStatus.READY_BUNDLED_ACOUSTIC_TEMPLATE, engine.getWakePhraseSupportStatus("Hi Nero"))
        assertEquals(WakePhraseSupportStatus.READY_BUNDLED_ACOUSTIC_TEMPLATE, engine.getWakePhraseSupportStatus("Hi Babu"))
        assertEquals(WakePhraseSupportStatus.READY_BUNDLED_ACOUSTIC_TEMPLATE, engine.getWakePhraseSupportStatus("Hi Qki"))
        assertEquals(WakePhraseSupportStatus.READY_BUNDLED_ACOUSTIC_TEMPLATE, engine.getWakePhraseSupportStatus("Hi Nayro"))

        // Arbitrary custom user phrase reports honest REQUIRES_VOICE_ENROLLMENT until enrolled
        assertEquals(WakePhraseSupportStatus.REQUIRES_VOICE_ENROLLMENT, engine.getWakePhraseSupportStatus("Hi Jarvis"))

        // False-positive rejection gate rejects silence, short impulsive clicks, and white-noise high ZCR
        val silence = ShortArray(1600) { 0 }
        assertFalse(engine.evaluateFalsePositiveGate(silence).first)

        val shortClick = ShortArray(200) { 8000 }
        assertFalse(engine.evaluateFalsePositiveGate(shortClick).first)

        val highFreqStatic = ShortArray(1600) { i -> if (i % 2 == 0) 3000 else -3000 }
        assertFalse(engine.evaluateFalsePositiveGate(highFreqStatic).first)

        // Valid speech-like 240Hz voiced waveform passes false-positive gate
        val cleanSpeech = ShortArray(1600) { i ->
            val v = kotlin.math.sin(2.0 * Math.PI * 240.0 * i / 16000.0) * 3200.0
            v.toInt().toShort()
        }
        assertTrue(engine.evaluateFalsePositiveGate(cleanSpeech, noiseFloorRms = 220.0).first)
    }

    @Test
    fun testTwoStageVoiceActivationAndFollowUpWithoutRepeatingWakePhrase() {
        val identityStore = AssistantIdentityStore(null)
        identityStore.updateDisplayName("Nero", autoSyncWakePhrases = true)
        identityStore.addWakePhrase("Hi Qki")

        val voiceManager = VoiceManager(context = null)
        voiceManager.bindIdentityStore(identityStore)

        val dispatchedCommands = mutableListOf<String>()
        var activationResponseSpoken: String? = null

        // Stage A: Wake phrase only -> activates and responds with activation cue
        val stageA = voiceManager.processTwoStageVoiceUtterance(
            rawUtterance = "Hi Nero",
            onActivationCue = { cue -> activationResponseSpoken = cue },
            onCommandReady = { cmd -> dispatchedCommands.add(cmd) }
        )
        assertEquals(VoiceManager.TwoStageWakeOutcome.WAKE_ONLY_ACTIVATED, stageA)
        assertNotNull(activationResponseSpoken)
        assertTrue(voiceManager.isFollowUpSessionActive.value)
        assertTrue(dispatchedCommands.isEmpty())

        // Stage B follow-up turn WITHOUT repeating wake phrase
        val followUp = voiceManager.processTwoStageVoiceUtterance(
            rawUtterance = "battery check karo",
            onCommandReady = { cmd -> dispatchedCommands.add(cmd) }
        )
        assertEquals(VoiceManager.TwoStageWakeOutcome.FOLLOW_UP_COMMAND_DISPATCHED, followUp)
        assertEquals(listOf("battery check karo"), dispatchedCommands)

        // Inline wake phrase + command ("Hi Qki, open YouTube") -> strips wake phrase cleanly
        val inline = voiceManager.processTwoStageVoiceUtterance(
            rawUtterance = "Hi Qki, open YouTube",
            onCommandReady = { cmd -> dispatchedCommands.add(cmd) }
        )
        assertEquals(VoiceManager.TwoStageWakeOutcome.WAKE_AND_COMMAND_DISPATCHED, inline)
        assertEquals("open YouTube", dispatchedCommands.last())

        // Voice exit command terminates follow-up session
        val stopOutcome = voiceManager.processTwoStageVoiceUtterance("Stop Nero")
        assertEquals(VoiceManager.TwoStageWakeOutcome.VOICE_EXIT_STOPPED, stopOutcome)
        assertFalse(voiceManager.isFollowUpSessionActive.value)
    }

    // -----------------------------------------------------------------------
    // 4. COMPLETE YOUTUBE AUTOMATION PIPELINE TESTS
    // -----------------------------------------------------------------------

    @Test
    fun testYouTubeTaskPlanHasFullSearchObserveSelectVerifySteps() {
        val intent = TaskClassifier.classify("YouTube kholo aur Kotlin Coroutines tutorial video search karo")
        assertEquals(UserIntentType.YOUTUBE_SEARCH, intent.type)

        val plan = TaskPlanner.planTask(intent)
        assertEquals(5, plan.actions.size)
        assertEquals(ActionType.LAUNCH_APP, plan.actions[0].actionType)
        assertEquals("com.google.android.youtube", plan.actions[0].target)
        assertEquals(ActionType.CLICK_ELEMENT, plan.actions[1].actionType)
        assertEquals("Search", plan.actions[1].target)
        assertEquals(ActionType.SET_TEXT, plan.actions[2].actionType)
        assertEquals("true", plan.actions[2].parameters["submit"])
        assertEquals(ActionType.READ_SCREEN, plan.actions[3].actionType)
        assertEquals(ActionType.CONTROL_MEDIA, plan.actions[4].actionType)
        assertEquals("SELECT_AND_VERIFY_YOUTUBE", plan.actions[4].parameters["operation"])
    }

    @Test
    fun testYouTubeWorkflowHandlesLoadingDialogRanksResultAndVerifiesPlayback() = runBlocking {
        val dummyContext = allocateWithoutInit(android.content.ContextWrapper::class.java) as android.content.Context
        val screenMgr = ScreenIntelligenceManager(dummyContext)
        val dummyMem = allocateWithoutInit(MemoryManager::class.java) as MemoryManager
        val mediaMgr = MediaControlManager(dummyContext, screenMgr, dummyMem)

        var snapshotCalls = 0
        var tappedLabel: String? = null
        var audioPlaying = false

        val loadingSnapshot = ScreenSnapshot(
            packageName = "com.google.android.youtube",
            visibleTexts = listOf("Loading..."),
            interactiveElements = emptyList(),
            totalNodeCount = 1
        )
        val resultsSnapshot = ScreenSnapshot(
            packageName = "com.google.android.youtube",
            visibleTexts = listOf("Search", "Sponsored Ad", "Kotlin Coroutines Full Course Official Video", "Unrelated Cooking Vlog"),
            interactiveElements = listOf(
                ScreenNodeInfo(text = "Search", contentDescription = "Search", viewId = "com.google.android.youtube:id/search_edit_text", className = "android.widget.EditText", isClickable = true, isEditable = true, isScrollable = false, boundsLeft = 100, boundsTop = 100, boundsRight = 800, boundsBottom = 180),
                ScreenNodeInfo(text = "Sponsored · Best Deals", contentDescription = null, viewId = null, className = "android.view.ViewGroup", isClickable = true, isEditable = false, isScrollable = false, boundsLeft = 50, boundsTop = 220, boundsRight = 1000, boundsBottom = 400),
                ScreenNodeInfo(text = "Unrelated Cooking Vlog 10 minutes", contentDescription = null, viewId = null, className = "android.view.ViewGroup", isClickable = true, isEditable = false, isScrollable = false, boundsLeft = 50, boundsTop = 420, boundsRight = 1000, boundsBottom = 620),
                ScreenNodeInfo(text = "Kotlin Coroutines Full Course Official Video 15 minutes", contentDescription = null, viewId = null, className = "android.view.ViewGroup", isClickable = true, isEditable = false, isScrollable = false, boundsLeft = 50, boundsTop = 640, boundsRight = 1000, boundsBottom = 860)
            ),
            totalNodeCount = 4
        )

        val result = mediaMgr.runYouTubeObservationWorkflow(
            query = "Kotlin Coroutines tutorial",
            snapshotProvider = {
                snapshotCalls++
                if (snapshotCalls == 1) loadingSnapshot else resultsSnapshot
            },
            clickHandler = { label, _, _ ->
                tappedLabel = label
                if (label.contains("Kotlin Coroutines")) {
                    audioPlaying = true
                    true
                } else false
            },
            scrollHandler = { false },
            playbackVerifier = { audioPlaying },
            stepDelayMs = 0L
        )

        assertTrue(result.isSuccess)
        assertEquals(VerificationStatus.VERIFIED, result.status)
        assertTrue(result.outputMessage.contains("PLAYBACK_VERIFIED"))
        assertTrue(tappedLabel?.contains("Kotlin Coroutines") == true)
        assertEquals(MediaPlaybackVerificationState.PLAYBACK_VERIFIED, mediaMgr.getSessionState().lastVerificationState)
    }

    @Test
    fun testYouTubeWorkflowNeverClaimsVerifiedWhenPlaybackIsNotActive() = runBlocking {
        val dummyContext = allocateWithoutInit(android.content.ContextWrapper::class.java) as android.content.Context
        val screenMgr = ScreenIntelligenceManager(dummyContext)
        val dummyMem = allocateWithoutInit(MemoryManager::class.java) as MemoryManager
        val mediaMgr = MediaControlManager(dummyContext, screenMgr, dummyMem)

        val resultsSnapshot = ScreenSnapshot(
            packageName = "com.google.android.youtube",
            visibleTexts = listOf("Arijit Singh Kesariya Official Video"),
            interactiveElements = listOf(
                ScreenNodeInfo(text = "Arijit Singh Kesariya Official Video", contentDescription = null, viewId = null, className = "android.view.ViewGroup", isClickable = true, isEditable = false, isScrollable = false, boundsLeft = 50, boundsTop = 400, boundsRight = 1000, boundsBottom = 650)
            ),
            totalNodeCount = 1
        )

        val result = mediaMgr.runYouTubeObservationWorkflow(
            query = "Arijit Singh Kesariya",
            snapshotProvider = { resultsSnapshot },
            clickHandler = { _, _, _ -> true },
            scrollHandler = { false },
            playbackVerifier = { false }, // Playback stays inactive!
            stepDelayMs = 0L
        )

        assertFalse(result.isSuccess)
        assertEquals(VerificationStatus.INCONCLUSIVE, result.status)
        assertEquals(ActionExecutionState.INCONCLUSIVE, result.status?.toActionExecutionState())
        assertTrue(result.outputMessage.contains("RESULT_SELECTED_PLAYBACK_UNVERIFIED"))
    }

    @Test
    fun testYouTubeWorkflowFailsHonestlyOnErrorScreen() = runBlocking {
        val dummyContext = allocateWithoutInit(android.content.ContextWrapper::class.java) as android.content.Context
        val screenMgr = ScreenIntelligenceManager(dummyContext)
        val dummyMem = allocateWithoutInit(MemoryManager::class.java) as MemoryManager
        val mediaMgr = MediaControlManager(dummyContext, screenMgr, dummyMem)

        val errorSnapshot = ScreenSnapshot(
            packageName = "com.google.android.youtube",
            visibleTexts = listOf("No internet connection", "Try again"),
            interactiveElements = emptyList(),
            totalNodeCount = 2
        )

        val result = mediaMgr.runYouTubeObservationWorkflow(
            query = "Android AI",
            snapshotProvider = { errorSnapshot },
            clickHandler = { _, _, _ -> true },
            scrollHandler = { false },
            playbackVerifier = { false },
            stepDelayMs = 0L
        )

        assertFalse(result.isSuccess)
        assertEquals(VerificationStatus.FAILED, result.status)
        assertEquals(MediaPlaybackVerificationState.FAILED, mediaMgr.getSessionState().lastVerificationState)
    }

    // -----------------------------------------------------------------------
    // 5. UNIFIED IDENTITY, EXECUTION STATES & DUPLICATE ACTION PROTECTION
    // -----------------------------------------------------------------------

    @Test
    fun testUnifiedAssistantIdentityStoreSupportsCustomNamesWithoutHardcoding() {
        val store = AssistantIdentityStore(null)
        store.updateDisplayName("Qki", autoSyncWakePhrases = true)
        assertEquals("Qki", store.getDisplayName())
        assertTrue(store.getWakePhrases().any { it.equals("Hi Qki", ignoreCase = true) })
        assertTrue(store.getWakePhrases().any { it.equals("Hey Qki", ignoreCase = true) })
        assertEquals("open calendar", store.stripWakePrefix("Hi Qki, open calendar"))

        val engine = LocalWakeWordEngine(null)
        engine.syncFromIdentityStore(store)
        assertEquals("Qki", engine.getConfiguredAssistantName())
        assertTrue(engine.getConfiguredWakePhrases().any { it.equals("Hi Qki", ignoreCase = true) })
    }

    @Test
    fun testExecutionStatesAndDuplicateSideEffectCheckpointGuards() {
        val runtime = createHeadlessRuntime()
        val dummyTask = AgentTask(taskId = "state-1", userInstruction = "Test", resolvedIntent = "TEST", actions = emptyList())

        assertEquals(ActionExecutionState.SUCCESS, AgentExecutionSummary(dummyTask, true, "ok", VerificationStatus.VERIFIED).executionState)
        assertEquals(ActionExecutionState.FAILED, AgentExecutionSummary(dummyTask, false, "err", VerificationStatus.FAILED).executionState)
        assertEquals(ActionExecutionState.INCONCLUSIVE, AgentExecutionSummary(dummyTask, false, "inc", VerificationStatus.INCONCLUSIVE).executionState)
        assertEquals(ActionExecutionState.WAITING_FOR_PERMISSION, AgentExecutionSummary(dummyTask, false, "perm", VerificationStatus.REQUIRES_PERMISSION).executionState)
        assertEquals(ActionExecutionState.WAITING_FOR_UNLOCK, AgentExecutionSummary(dummyTask, false, "lock", VerificationStatus.WAITING_FOR_UNLOCK).executionState)
        assertEquals(ActionExecutionState.WAITING_FOR_CONFIRMATION, AgentExecutionSummary(dummyTask, false, "conf", VerificationStatus.REQUIRES_CONFIRMATION).executionState)

        // Duplicate non-idempotent side-effect guard & uncertain external action guard
        assertFalse(runtime.hasSideEffectAlreadyExecuted("task-dup-1", "action-sms-1"))
        runtime.markSideEffectExecuted("task-dup-1", "action-sms-1")
        assertTrue(runtime.hasSideEffectAlreadyExecuted("task-dup-1", "action-sms-1"))

        assertFalse(runtime.hasUncertainExternalAction("task-dup-1", "action-wa-1"))
        runtime.markExternalActionUncertain("task-dup-1", "action-wa-1")
        assertTrue(runtime.hasUncertainExternalAction("task-dup-1", "action-wa-1"))
    }
}
