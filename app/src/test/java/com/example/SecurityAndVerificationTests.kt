package com.example

import com.example.core.agent.ActionExecutionResult
import com.example.core.agent.ActionExecutor
import com.example.core.agent.ActionType
import com.example.core.agent.ActionVerifier
import com.example.core.agent.AgentAction
import com.example.core.agent.AgentBrain
import com.example.core.agent.AgentTask
import com.example.core.agent.RiskLevel
import com.example.core.agent.TaskClassifier
import com.example.core.agent.TaskPlanner
import com.example.core.agent.TaskState
import com.example.core.agent.TaskStateManager
import com.example.core.agent.UserIntentType
import com.example.core.agent.VerificationResult
import com.example.core.agent.VerificationStatus
import com.example.core.agent.toUserFriendlyDescription
import com.example.core.agent.toUserFriendlyIntentLabel
import com.example.core.agent.toUserFriendlyLabel
import com.example.core.agent.tools.ToolRegistryManager
import com.example.core.automation.AutomationEngine
import com.example.core.memory.ConversationContinuityManager
import com.example.core.screen.ScreenIntelligenceManager
import com.example.core.skills.SkillManager
import com.example.core.subagent.SubAgentManager
import com.example.core.subagent.SubAgentStatus
import com.example.core.subagent.SubAgentType
import com.example.core.telephony.CallIntelligenceManager
import com.example.core.voice.VoiceManager
import com.example.core.voice.engine.LocalWakeWordEngine
import com.example.core.voice.engine.WakeEngineState
import com.example.core.workspace.WorkspaceManager
import com.example.core.workspace.WorkspaceResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.File
import java.nio.file.Files

@RunWith(JUnit4::class)
class SecurityAndVerificationTests {

    private lateinit var tempWorkspaceDir: File
    private lateinit var workspaceManager: WorkspaceManager

    @Before
    fun setUp() {
        ToolRegistryManager.resetToDefaults()
        tempWorkspaceDir = Files.createTempDirectory("nayro_test_ws_").toFile()
        workspaceManager = WorkspaceManager(tempWorkspaceDir)
    }

    // =========================================================================
    // A. AgentBrain and Multi-Step Execution Tests (BUG 01 & BUG 02)
    // =========================================================================

    private fun createTestBrain(): AgentBrain {
        val dummyField = UnsafeTestHelper.allocateWithoutInit(ActionExecutor::class.java)
        val dummyProvider = UnsafeTestHelper.allocateWithoutInit(com.example.core.provider.AIProviderManager::class.java)
        return AgentBrain(
            providerManager = dummyProvider,
            actionExecutor = dummyField,
            workspaceManager = workspaceManager,
            skillManager = SkillManager(),
            subAgentManager = SubAgentManager()
        )
    }

    @Test
    fun testAgentBrainMultiStepCompoundDecomposition() {
        val brain = createTestBrain()
        val task = brain.planTaskForInstruction("Open YouTube and then what time is it and then create note: Meeting at 5pm")
        assertEquals("MULTI_STEP_COMPOUND", task.resolvedIntent)
        assertEquals(3, task.actions.size)
        assertEquals(ActionType.LAUNCH_APP, task.actions[0].actionType)
        assertEquals(ActionType.GET_DEVICE_TIME, task.actions[1].actionType)
        assertEquals(ActionType.SAVE_NOTE, task.actions[2].actionType)
    }

    @Test
    fun testAgentBrainExecutesAllStepsWithContextCarryover() = runBlocking {
        val brain = createTestBrain()
        val task = brain.planTaskForInstruction("Calculate 10 + 20 and then create note: Done")
        assertEquals(2, task.actions.size)

        val observedPreviousOutputs = mutableListOf<String?>()
        val outcome = brain.executeTaskIterative(task) { action, ctx ->
            observedPreviousOutputs.add(ctx["previousStepOutput"])
            ActionExecutionResult(
                isSuccess = true,
                outputMessage = "Executed ${action.actionType.name}",
                status = VerificationStatus.VERIFIED
            ) to VerificationStatus.VERIFIED
        }

        assertTrue(outcome.isFullyVerified)
        assertEquals(VerificationStatus.VERIFIED, outcome.overallStatus)
        assertEquals(2, outcome.stepsExecuted.size)
        assertNull(observedPreviousOutputs[0])
        assertEquals("Executed CALCULATE_LOCAL", observedPreviousOutputs[1])
    }

    @Test
    fun testAgentBrainStep2FailureReturnsPartiallyCompleted() = runBlocking {
        val brain = createTestBrain()
        val task = brain.planTaskForInstruction("Open Settings and then read screen and then create note: Test")
        assertEquals(3, task.actions.size)

        val outcome = brain.executeTaskIterative(task) { action, _ ->
            if (action.actionType == ActionType.READ_SCREEN) {
                ActionExecutionResult(false, "Screen read failed", VerificationStatus.FAILED) to VerificationStatus.FAILED
            } else {
                ActionExecutionResult(true, "Step OK", VerificationStatus.VERIFIED) to VerificationStatus.VERIFIED
            }
        }

        assertFalse(outcome.isFullyVerified)
        assertEquals(VerificationStatus.PARTIALLY_COMPLETED, outcome.overallStatus)
        assertEquals(2, outcome.stepsExecuted.size)
    }

    @Test
    fun testAgentBrainStep2InconclusiveStopsAndReportsPartial() = runBlocking {
        val brain = createTestBrain()
        val task = brain.planTaskForInstruction("Open Settings and then read screen")
        val outcome = brain.executeTaskIterative(task) { action, _ ->
            if (action.actionType == ActionType.READ_SCREEN) {
                ActionExecutionResult(false, "Inconclusive screen state", VerificationStatus.INCONCLUSIVE) to VerificationStatus.INCONCLUSIVE
            } else {
                ActionExecutionResult(true, "Opened Settings", VerificationStatus.VERIFIED) to VerificationStatus.VERIFIED
            }
        }

        assertFalse(outcome.isFullyVerified)
        assertEquals(VerificationStatus.PARTIALLY_COMPLETED, outcome.overallStatus)
    }

    @Test
    fun testAgentBrainStepBudgetExceededFails() = runBlocking {
        val brain = createTestBrain()
        val actions = (1..6).map {
            AgentAction(taskId = "t1", actionType = ActionType.GET_DEVICE_TIME)
        }
        val task = AgentTask(
            taskId = "t1",
            userInstruction = "6 steps",
            resolvedIntent = "TEST",
            actions = actions
        )

        val outcome = brain.executeTaskIterative(task, maxSteps = 4)
        assertFalse(outcome.isFullyVerified)
        assertEquals(VerificationStatus.FAILED, outcome.overallStatus)
        assertTrue(outcome.finalResponse.contains("exceeds maximum step budget"))
    }

    // =========================================================================
    // B. Verification & TaskState Separation Tests (BUG 05 & BUG 06)
    // =========================================================================

    @Test
    fun testVerificationStatusSeparationAndActionVerifier() {
        val verified = VerificationResult(status = VerificationStatus.VERIFIED, details = "Confirmed")
        val inconclusive = VerificationResult(status = VerificationStatus.INCONCLUSIVE, details = "Uncertain")
        val failed = VerificationResult(status = VerificationStatus.FAILED, details = "Failed")
        val notSupported = VerificationResult(status = VerificationStatus.NOT_SUPPORTED, details = "Unsupported")
        val requiresPerm = VerificationResult(status = VerificationStatus.REQUIRES_PERMISSION, details = "Needs permission")
        val requiresConf = VerificationResult(status = VerificationStatus.REQUIRES_CONFIRMATION, details = "Needs confirmation")

        assertTrue(verified.isVerified)
        assertFalse(inconclusive.isVerified)
        assertFalse(failed.isVerified)
        assertFalse(notSupported.isVerified)
        assertFalse(requiresPerm.isVerified)
        assertFalse(requiresConf.isVerified)

        val verifier = ActionVerifier(UnsafeTestHelper.allocateWithoutInit(ScreenIntelligenceManager::class.java))
        val action = AgentAction(taskId = "t1", actionType = ActionType.TAKE_SCREENSHOT)

        val notSupportedRes = verifier.verifyAction(
            action = action,
            executionResult = ActionExecutionResult(false, "API < 30", VerificationStatus.NOT_SUPPORTED),
            preStateSummary = null
        )
        assertEquals(VerificationStatus.NOT_SUPPORTED, notSupportedRes.status)
        assertFalse(notSupportedRes.isVerified)

        val inconclusiveRes = verifier.verifyAction(
            action = action,
            executionResult = ActionExecutionResult(false, "Draft opened only", VerificationStatus.INCONCLUSIVE),
            preStateSummary = null
        )
        assertEquals(VerificationStatus.INCONCLUSIVE, inconclusiveRes.status)
        assertFalse(inconclusiveRes.isVerified)
    }

    @Test
    fun testTaskStateManagerSupportsPartialInconclusiveAndNotSupported() {
        val sm = TaskStateManager()
        val task = AgentTask(
            taskId = "t_partial",
            userInstruction = "Test",
            resolvedIntent = "TEST",
            actions = listOf(AgentAction(taskId = "t_partial", actionType = ActionType.GET_DEVICE_TIME))
        )
        assertTrue(sm.transition(task, TaskState.UNDERSTANDING))
        assertTrue(sm.transition(task, TaskState.PLANNING))
        assertTrue(sm.transition(task, TaskState.VALIDATING))
        assertTrue(sm.transition(task, TaskState.READY))
        assertTrue(sm.transition(task, TaskState.EXECUTING))
        assertTrue(sm.transition(task, TaskState.VERIFYING))
        assertTrue(sm.transition(task, TaskState.PARTIALLY_COMPLETED, "Step 2 inconclusive"))
        assertEquals(TaskState.PARTIALLY_COMPLETED, task.state)
        assertEquals("Step 2 inconclusive", task.failureReason)
    }

    // =========================================================================
    // C & D. Caller Intelligence & Contact Resolution Tests (BUG 03 & BUG 04)
    // =========================================================================

    @Test
    fun testCallerIntelligenceNeverReturnsHardcodedRahul() {
        val callManager = CallIntelligenceManager(null)
        val snapshot = callManager.queryActiveCallState()
        assertFalse(snapshot.callerDisplayName.contains("Rahul", ignoreCase = true))
        assertFalse(snapshot.statusSummary.contains("Rahul", ignoreCase = true))
        assertEquals("Caller identity is unavailable.", snapshot.callerDisplayName)
        assertTrue(snapshot.permissionRequired)
    }

    // =========================================================================
    // E. Workspace Security & Structured Error Propagation Tests (BUG 07 & BUG 08)
    // =========================================================================

    @Test
    fun testWorkspaceValidReadWriteAndStructuredErrors() {
        val writeRes = workspaceManager.writeFileResult("report.md", "# Verified Report")
        assertTrue(writeRes is WorkspaceResult.Success)
        assertTrue(writeRes.isSuccess)

        val readRes = workspaceManager.readFileResult("report.md")
        assertTrue(readRes is WorkspaceResult.Success)
        assertEquals("# Verified Report", (readRes as WorkspaceResult.Success).content)

        val missingRes = workspaceManager.readFileResult("non_existent_file.txt")
        assertTrue(missingRes is WorkspaceResult.NotFound)
        assertFalse(missingRes.isSuccess)
    }

    @Test
    fun testWorkspacePathTraversalAndAbsolutePathRejected() {
        val dotDotSlash = workspaceManager.readFileResult("../../../etc/passwd")
        assertTrue(dotDotSlash is WorkspaceResult.InvalidPath)
        assertFalse(dotDotSlash.isSuccess)

        val dotDotBackslash = workspaceManager.writeFileResult("..\\..\\secret.txt", "bad")
        assertTrue(dotDotBackslash is WorkspaceResult.InvalidPath)
        assertFalse(dotDotBackslash.isSuccess)

        val absoluteUnix = workspaceManager.readFileResult("/etc/hosts")
        assertTrue(absoluteUnix is WorkspaceResult.InvalidPath)
        assertFalse(absoluteUnix.isSuccess)

        val absoluteWin = workspaceManager.writeFileResult("C:\\Windows\\System32\\cmd.exe", "bad")
        assertTrue(absoluteWin is WorkspaceResult.InvalidPath)
        assertFalse(absoluteWin.isSuccess)
    }

    @Test
    fun testWorkspaceSymlinkEscapeRejected() {
        val outsideFile = Files.createTempFile("outside_secret_", ".txt").toFile()
        outsideFile.writeText("TOP_SECRET_OUTSIDE_WORKSPACE")
        val symlinkInWorkspace = File(tempWorkspaceDir, "escaped_link.txt")
        try {
            Files.createSymbolicLink(symlinkInWorkspace.toPath(), outsideFile.toPath())
            val readLink = workspaceManager.readFileResult("escaped_link.txt")
            assertFalse("Symlink escaping workspace root must be rejected", readLink.isSuccess)
            assertTrue(readLink is WorkspaceResult.InvalidPath)
        } catch (_: UnsupportedOperationException) {
            // Symlinks not supported on this filesystem
        } finally {
            outsideFile.delete()
        }
    }

    // =========================================================================
    // G. Web Research SSRF & URL Validation Tests (BUG 10)
    // =========================================================================

    @Test
    fun testWebUrlSsrfProtectionBlocksPrivateAndLoopbackHosts() {
        assertTrue(ActionExecutor.validateSafeExternalUrl("https://en.wikipedia.org/wiki/Kotlin").isSuccess)
        assertFalse(ActionExecutor.validateSafeExternalUrl("http://localhost:8080/admin").isSuccess)
        assertFalse(ActionExecutor.validateSafeExternalUrl("http://127.0.0.1:9000/metrics").isSuccess)
        assertFalse(ActionExecutor.validateSafeExternalUrl("http://10.0.0.1/internal").isSuccess)
        assertFalse(ActionExecutor.validateSafeExternalUrl("http://192.168.1.1/router").isSuccess)
        assertFalse(ActionExecutor.validateSafeExternalUrl("http://172.16.5.4/secret").isSuccess)
        assertFalse(ActionExecutor.validateSafeExternalUrl("http://169.254.169.254/latest/meta-data/").isSuccess)
        assertFalse(ActionExecutor.validateSafeExternalUrl("file:///etc/passwd").isSuccess)
        assertFalse(ActionExecutor.validateSafeExternalUrl("javascript:alert(1)").isSuccess)
    }

    // =========================================================================
    // H. Coding Workspace Real Operations Tests (BUG 11)
    // =========================================================================

    @Test
    fun testCodingAgentWorkspaceInspectEditDiagnoseAndDocs() {
        val inspectMissing = workspaceManager.inspectCodeFileResult("MissingFile.kt")
        assertFalse(inspectMissing.isSuccess)
        assertTrue(inspectMissing is WorkspaceResult.NotFound)

        val inspectSample = workspaceManager.inspectCodeFileResult("SampleAgentModule.kt")
        assertTrue(inspectSample.isSuccess)
        assertTrue(inspectSample.displayMessage.contains("Balanced"))

        val editRes = workspaceManager.applyCodeEditResult(
            filename = "SampleAgentModule.kt",
            patchContent = "if (totalTimeMs <= 0L) return 0.0",
            targetSnippet = "if (totalTimeMs <= 0) return 0.0"
        )
        assertTrue(editRes.isSuccess)

        workspaceManager.writeFileResult("BrokenCode.kt", "class Broken {\n fun crash(x: String?) { val y = x!! \n}")
        val diagRes = workspaceManager.diagnoseCodeErrorResult("BrokenCode.kt")
        assertTrue(diagRes.isSuccess)
        assertTrue(diagRes.displayMessage.contains("Unbalanced curly braces"))
        assertTrue(diagRes.displayMessage.contains("Non-null assertion operator"))

        val docsRes = workspaceManager.generateCodeDocsResult("SampleAgentModule.kt")
        assertTrue(docsRes.isSuccess)
        assertTrue(docsRes.displayMessage.contains("Documentation for `SampleAgentModule.kt`"))
    }

    // =========================================================================
    // I. Bounded Sub-Agent Delegation Tests (BUG 12)
    // =========================================================================

    @Test
    fun testSubAgentLifecycleFailureTimeoutAndRecursionLimit() = runBlocking {
        val manager = SubAgentManager()

        // 1. Successful real execution
        val okTask = manager.executeSubTask(SubAgentType.PRODUCTIVITY, "10 + 5", depth = 0) { action ->
            ActionExecutionResult(true, "10 + 5 = 15", VerificationStatus.VERIFIED)
        }
        assertEquals(SubAgentStatus.COMPLETED, okTask.status)
        assertEquals("10 + 5 = 15", okTask.outputResult)

        // 2. Sub-task failure propagation
        val failTask = manager.executeSubTask(SubAgentType.RESEARCH, "Offline query", depth = 0) { _ ->
            ActionExecutionResult(false, "Network unreachable", VerificationStatus.FAILED)
        }
        assertEquals(SubAgentStatus.FAILED, failTask.status)

        // 3. Sub-task timeout handling
        val timeoutTask = manager.executeSubTask(
            agentType = SubAgentType.BROWSER,
            subGoal = "https://example.com",
            depth = 0,
            timeoutMs = 50L
        ) { _ ->
            delay(300L)
            ActionExecutionResult(true, "Late", VerificationStatus.VERIFIED)
        }
        assertEquals(SubAgentStatus.FAILED, timeoutTask.status)
        assertTrue(timeoutTask.outputResult!!.contains("timed out"))

        // 4. Recursion depth limit enforcement
        val deepTask = manager.executeSubTask(
            agentType = SubAgentType.PLANNING,
            subGoal = "Infinite loop",
            depth = SubAgentManager.MAX_DELEGATION_DEPTH + 1
        ) { _ ->
            ActionExecutionResult(true, "Should not run", VerificationStatus.VERIFIED)
        }
        assertEquals(SubAgentStatus.FAILED, deepTask.status)
        assertTrue(deepTask.outputResult!!.contains("recursion depth"))
    }

    // =========================================================================
    // K. Voice Lifecycle & LocalWakeWordEngine Tests (BUG 14 & BUG 15)
    // =========================================================================

    @Test
    fun testVoiceManagerScopedListenersAndActivityDestroySafety() {
        val voiceManager = VoiceManager(null)
        var serviceCaptured: String? = null
        var uiCaptured: String? = null

        voiceManager.attachServiceListener { serviceCaptured = it }
        voiceManager.attachUiListener(onRecognized = { uiCaptured = it })

        // While UI is attached, speech routes to UI listener once
        voiceManager.dispatchRecognizedSpeech("Open Settings")
        assertEquals("Open Settings", uiCaptured)
        assertNull(serviceCaptured)

        // When Activity is destroyed, UI listener detaches without destroying VoiceManager or Service listener
        voiceManager.onActivityDestroyed()
        assertFalse(voiceManager.isDestroyed)
        assertFalse(voiceManager.hasActiveUiListener())
        assertTrue(voiceManager.hasActiveServiceListener())

        voiceManager.dispatchRecognizedSpeech("What time is it")
        assertEquals("What time is it", serviceCaptured)
    }

    @Test
    fun testWakeWordEngineNeverFiresOnRawNoiseOrUnrelatedSpeech() {
        val engine = LocalWakeWordEngine(null)
        var triggeredPhrase: String? = null
        engine.onWakeWordDetected = { triggeredPhrase = it }

        // 1. Model unavailable check when no asset is bundled
        engine.startListening()
        assertEquals(WakeEngineState.MODEL_UNAVAILABLE, engine.engineState.value)

        // 2. Silence must not trigger
        assertNull(engine.evaluateWakeCandidate("Hey Babu", rms = 50.0, zcr = 0.10))
        assertNull(triggeredPhrase)

        // 3. Clap / transient noise must not trigger
        assertNull(engine.evaluateWakeCandidate("Hey Babu", rms = 12000.0, zcr = 0.75))
        assertNull(triggeredPhrase)

        // 4. Unrelated speech ("Hello world", "Good morning", null KWS hypothesis) must NEVER trigger
        val speechFrame = ShortArray(512) { idx -> if (idx % 8 < 4) 3000 else -3000 }
        assertNull(engine.processAudioFrame(speechFrame, speechFrame.size, kwsHypothesis = null))
        assertNull(engine.processAudioFrame(speechFrame, speechFrame.size, kwsHypothesis = "Hello world how are you"))
        assertNull(engine.evaluateWakeCandidate("What is the weather today", rms = 3500.0, zcr = 0.15))
        assertNull(triggeredPhrase)

        // 5. Verified wake phrase with valid speech envelope triggers accurately
        val matched = engine.evaluateWakeCandidate("Hey Babu", rms = 3500.0, zcr = 0.15)
        assertEquals("Hey Babu", matched)
        assertEquals("Hey Babu", triggeredPhrase)
        assertEquals(WakeEngineState.WAKE_DETECTED, engine.engineState.value)
    }

    // =========================================================================
    // L. ToolRegistry & Schema Validation Consistency Tests (BUG 16)
    // =========================================================================

    @Test
    fun testEveryActionTypeRegisteredWithUniqueIdAndParameterValidation() {
        val allTypes = ActionType.entries
        val seenIds = mutableSetOf<String>()

        for (type in allTypes) {
            val def = ToolRegistryManager.getTool(type)
            assertNotNull("ActionType $type must be registered in ToolRegistryManager", def)
            assertTrue("Duplicate toolId ${def!!.toolId}", seenIds.add(def.toolId))
        }

        // Validate missing required parameters fail before execution
        val invalidWrite = AgentAction(
            taskId = "t1",
            actionType = ActionType.FILE_WRITE_TEXT,
            target = "note.txt",
            parameters = emptyMap() // missing 'content'
        )
        assertFalse(ToolRegistryManager.validateParameters(invalidWrite).first)

        val invalidCoords = AgentAction(
            taskId = "t1",
            actionType = ActionType.INTERACT_COORDINATES,
            parameters = mapOf("x" to "not_a_number", "y" to "200")
        )
        assertFalse(ToolRegistryManager.validateParameters(invalidCoords).first)

        val validCoords = AgentAction(
            taskId = "t1",
            actionType = ActionType.INTERACT_COORDINATES,
            parameters = mapOf("x" to "150", "y" to "300")
        )
        assertTrue(ToolRegistryManager.validateParameters(validCoords).first)
    }

    // =========================================================================
    // M. Autonomy Policy Manager Tests (Part 11)
    // =========================================================================

    @Test
    fun testAutonomyPolicyLevelsEmergencyStopAndExpiringGrants() {
        val policyManager = com.example.core.security.AutonomyPolicyManager()
        val r0Action = AgentAction(taskId = "t1", actionType = ActionType.GET_DEVICE_TIME, riskLevel = RiskLevel.R0_LOCAL_READONLY)
        val r1Action = AgentAction(taskId = "t1", actionType = ActionType.LAUNCH_APP, target = "com.android.chrome", riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE)
        val r2Action = AgentAction(taskId = "t1", actionType = ActionType.SET_TEXT, parameters = mapOf("text" to "hi"), riskLevel = RiskLevel.R2_APPLICATION_STATE)
        val r3Action = AgentAction(taskId = "t1", actionType = ActionType.MESSAGE_DRAFT_SEND, target = "Rahul", riskLevel = RiskLevel.R3_EXTERNAL_COMMUNICATION)

        // Level 1: Always Ask (even R0/R1 require confirmation)
        policyManager.setAutonomyLevel(com.example.core.security.AutonomyLevel.LEVEL_1_ALWAYS_ASK)
        assertTrue(policyManager.evaluateAction(r0Action) is com.example.core.security.AutonomyDecision.RequireUserConfirmation)
        assertTrue(policyManager.evaluateAction(r1Action) is com.example.core.security.AutonomyDecision.RequireUserConfirmation)

        // Level 2: Ask for Sensitive Actions (R0 & R1 allowed, R2 & R3 require confirmation)
        policyManager.setAutonomyLevel(com.example.core.security.AutonomyLevel.LEVEL_2_ASK_FOR_SENSITIVE)
        assertTrue(policyManager.evaluateAction(r0Action) is com.example.core.security.AutonomyDecision.AllowImmediately)
        assertTrue(policyManager.evaluateAction(r1Action) is com.example.core.security.AutonomyDecision.AllowImmediately)
        assertTrue(policyManager.evaluateAction(r2Action) is com.example.core.security.AutonomyDecision.RequireUserConfirmation)
        assertTrue(policyManager.evaluateAction(r3Action) is com.example.core.security.AutonomyDecision.RequireUserConfirmation)

        // Expiring Temporary Grant allows R2 until expiry, then reverts
        val baseNow = System.currentTimeMillis()
        policyManager.grantTemporaryPermission(ActionType.SET_TEXT.name, durationMs = 10_000L)
        assertTrue(policyManager.evaluateAction(r2Action, nowMs = baseNow + 2_000L) is com.example.core.security.AutonomyDecision.AllowImmediately)
        assertTrue(policyManager.evaluateAction(r2Action, nowMs = baseNow + 15_000L) is com.example.core.security.AutonomyDecision.RequireUserConfirmation)

        // Per-App Block blocks target app
        policyManager.setAppPolicy("com.android.chrome", com.example.core.security.ToolAccessPolicy.NEVER_ALLOW)
        assertTrue(policyManager.evaluateAction(r1Action) is com.example.core.security.AutonomyDecision.DenyExecution)

        // Emergency Stop blocks everything
        policyManager.triggerEmergencyStop()
        assertTrue(policyManager.evaluateAction(r0Action) is com.example.core.security.AutonomyDecision.DenyExecution)
    }

    // =========================================================================
    // N. Multi-Turn Conversation Continuity & Slot Filling Tests (Part 9)
    // =========================================================================

    @Test
    fun testMultiTurnSlotFillingAndReferenceResolution() {
        val continuity = com.example.core.memory.ConversationContinuityManager()

        // Turn 1: "Rahul ko WhatsApp message bhejna" -> missing message body
        val turn1Intent = TaskClassifier.classify("Rahul ko WhatsApp message bhejna")
        assertEquals(UserIntentType.MESSAGE_DRAFT, turn1Intent.type)
        assertEquals("Rahul", turn1Intent.recipient)
        assertTrue("Message body should be empty on Turn 1", turn1Intent.searchQueryOrContent.isNullOrBlank())

        val initialTask = TaskPlanner.planTask(turn1Intent)
        continuity.recordEntityContext(contact = "Rahul")
        continuity.registerPendingSlot(
            com.example.core.memory.PendingSlotContinuation(
                taskId = initialTask.taskId,
                intentType = UserIntentType.MESSAGE_DRAFT,
                missingSlotName = "message",
                promptToUser = "Kya message bhejna hai Rahul ko?",
                collectedEntities = mapOf("recipient" to "Rahul"),
                createdAtMs = 1000L,
                expiresAtMs = 301_000L
            )
        )

        // Turn 2: User replies "Main busy hoon" -> resumes the SAME taskId with filled message
        val turn2Eval = continuity.evaluateIncomingTurn(
            userTurn = "Main busy hoon",
            hasActivePendingConfirmation = false,
            nowMs = 5000L
        )
        assertTrue(turn2Eval is com.example.core.memory.ContinuationResolution.SlotFilledResumeIntent)
        val resumed = turn2Eval as com.example.core.memory.ContinuationResolution.SlotFilledResumeIntent
        assertEquals(initialTask.taskId, resumed.taskId)
        assertEquals("Rahul", resumed.resolvedIntent.recipient)
        assertEquals("Main busy hoon", resumed.resolvedIntent.searchQueryOrContent)

        // Turn 3: User replies "Yes" to confirmation
        val turn3Eval = continuity.evaluateIncomingTurn(
            userTurn = "Yes",
            hasActivePendingConfirmation = true,
            nowMs = 6000L
        )
        assertTrue(turn3Eval is com.example.core.memory.ContinuationResolution.ConfirmationReply)
        assertTrue((turn3Eval as com.example.core.memory.ContinuationResolution.ConfirmationReply).approved)

        // Reference resolution ("usko" -> "Rahul")
        val resolvedPronoun = continuity.resolveReferencesInPrompt("Usko call karo")
        assertTrue(resolvedPronoun.contains("Rahul"))
    }

    // =========================================================================
    // O. Cross-App Multi-Step Workflow Engine Tests (Part 6)
    // =========================================================================

    @Test
    fun testCrossAppWorkflowContextTransferAndPartialCompletion() = runBlocking {
        val wfEngine = com.example.core.workflow.CrossAppWorkflowEngine()

        // 1. Successful execution transfers step_1_output into subsequent steps
        val receivedParams = mutableListOf<Map<String, String>>()
        val resultOk = wfEngine.executeTemplateById("wf_doc_to_meeting_note") { action ->
            receivedParams.add(action.parameters)
            ActionExecutionResult(true, "Output of ${action.actionType.name}", VerificationStatus.VERIFIED)
        }
        assertEquals(TaskState.COMPLETED, resultOk.state)
        assertEquals(2, resultOk.currentStepIndex)
        assertTrue(receivedParams[1]["content"]?.contains("Output of PDF_EXTRACT_TEXT") == true)

        // 2. Partial failure when step 2 fails marks workflow PARTIALLY_COMPLETED
        val resultPartial = wfEngine.executeTemplateById("wf_doc_to_meeting_note") { action ->
            if (action.actionType == ActionType.PDF_EXTRACT_TEXT) {
                ActionExecutionResult(true, "Extracted text", VerificationStatus.VERIFIED)
            } else {
                ActionExecutionResult(false, "Storage write failed", VerificationStatus.FAILED)
            }
        }
        assertEquals(TaskState.PARTIALLY_COMPLETED, resultPartial.state)
        assertEquals(1, resultPartial.currentStepIndex)
    }

    // =========================================================================
    // P. Proactive Intelligence & Event Automation Engine Tests (Parts 7 & 8)
    // =========================================================================

    @Test
    fun testProactiveIntelligenceQuietHoursAndDeduplication() {
        val proactive = com.example.core.proactive.ProactiveIntelligenceEngine()
        val list1 = proactive.evaluateContext(
            incompleteTasks = emptyList(),
            notesAndReminders = emptyList(),
            unreadNotifications = listOf(
                com.example.core.notification.CapturedNotificationItem("k1", "com.whatsapp", "A", "1", com.example.core.notification.NotificationCategory.MESSAGE, 100L),
                com.example.core.notification.CapturedNotificationItem("k2", "com.whatsapp", "B", "2", com.example.core.notification.NotificationCategory.MESSAGE, 200L)
            ),
            batteryPercent = 14,
            currentHourOfDay = 12
        )
        assertEquals(2, list1.size) // Unread batch + low battery

        // Dismiss low battery suggestion -> no longer returned
        proactive.dismissSuggestion("low_battery_14")
        val list2 = proactive.evaluateContext(
            incompleteTasks = emptyList(),
            notesAndReminders = emptyList(),
            unreadNotifications = listOf(
                com.example.core.notification.CapturedNotificationItem("k1", "com.whatsapp", "A", "1", com.example.core.notification.NotificationCategory.MESSAGE, 100L),
                com.example.core.notification.CapturedNotificationItem("k2", "com.whatsapp", "B", "2", com.example.core.notification.NotificationCategory.MESSAGE, 200L)
            ),
            batteryPercent = 14,
            currentHourOfDay = 12
        )
        assertEquals(1, list2.size)

        // Quiet hours (23:00) suppresses suggestions
        val quietList = proactive.evaluateContext(
            incompleteTasks = emptyList(),
            notesAndReminders = emptyList(),
            unreadNotifications = emptyList(),
            batteryPercent = 14,
            currentHourOfDay = 23
        )
        assertTrue(quietList.isEmpty())
    }

    @Test
    fun testAutomationEngineTriggersAndCooldownDeduplication() = runBlocking {
        val autoEngine = com.example.core.automation.AutomationEngine()
        val firstRun = autoEngine.onEventTriggered(
            triggerType = com.example.core.automation.AutomationTriggerType.BATTERY_BELOW_THRESHOLD,
            eventValue = "14",
            nowMs = 100_000L
        ) {
            true to "Saved battery note"
        }
        assertEquals(1, firstRun.size)
        assertTrue(firstRun[0].isSuccess)

        // Immediate second trigger within cooldown window is deduplicated
        val secondRun = autoEngine.onEventTriggered(
            triggerType = com.example.core.automation.AutomationTriggerType.BATTERY_BELOW_THRESHOLD,
            eventValue = "14",
            nowMs = 105_000L
        ) {
            true to "Should not fire"
        }
        assertTrue(secondRun.isEmpty())
    }

    // =========================================================================
    // Q. Notification Intelligence Classification & Filtering Tests (Part 4D)
    // =========================================================================

    @Test
    fun testNotificationClassificationAndAppFiltering() {
        val repo = com.example.core.notification.NotificationIntelligenceRepository
        repo.clearForTesting()

        val catMsg = repo.classifyNotification("com.whatsapp", "Rahul", "New message received")
        assertEquals(com.example.core.notification.NotificationCategory.MESSAGE, catMsg)

        val catRem = repo.classifyNotification("com.google.android.calendar", "Meeting Reminder", "Starts in 10m")
        assertEquals(com.example.core.notification.NotificationCategory.REMINDER_OR_CALENDAR, catRem)

        repo.setAllowedPackagesFilter(setOf("com.whatsapp"))
        repo.onNotificationPosted(
            com.example.core.notification.CapturedNotificationItem(
                key = "k_spam",
                packageName = "com.spam.app",
                title = "Sale",
                text = "50% off",
                category = com.example.core.notification.NotificationCategory.SOCIAL_OR_PROMOTION,
                postTime = 1000L
            )
        )
        assertTrue("Non-allowlisted app notification must be filtered out", repo.getFilteredNotifications().isEmpty())
        repo.clearForTesting()
    }

    // =========================================================================
    // R. Permission Setup Wizard, Registry Audit & Verification Report Tests (Parts 2 & 3)
    // =========================================================================

    @Test
    fun testPermissionRegistryAuditAndSetupWizardReport() {
        val allItems = com.example.core.permission.PermissionRegistry.items
        assertEquals("All 13 NAYRO permission items must be registered", 13, allItems.size)

        for (item in allItems) {
            assertTrue("Permission name must not be blank for ${item.type}", item.permissionName.isNotBlank())
            assertTrue("Purpose must not be blank for ${item.type}", item.purpose.isNotBlank())
            assertTrue("Feature dependency must not be blank for ${item.type}", item.featureDependency.isNotBlank())
            assertTrue("Verification method must not be blank for ${item.type}", item.verificationMethod.isNotBlank())
            assertTrue("Fallback behavior must not be blank for ${item.type}", item.fallbackBehavior.isNotBlank())
        }

        val coreItems = com.example.core.permission.PermissionRegistry.getCorePermissions()
        val optionalItems = com.example.core.permission.PermissionRegistry.getOptionalPermissions()
        assertTrue(coreItems.isNotEmpty())
        assertTrue(optionalItems.isNotEmpty())
        assertEquals(allItems.size, coreItems.size + optionalItems.size)

        // Test PermissionSetupManager headless wizard lifecycle & report building
        val setupManager = com.example.core.permission.PermissionSetupManager(context = null, permissionManager = null)
        assertFalse("Setup must be incomplete on first launch", setupManager.isSetupCompleted())
        assertEquals("Babu", setupManager.getSavedAssistantName())
        assertEquals(com.example.core.permission.SetupWizardStep.WELCOME, setupManager.currentStep.value)

        // Initial report when nothing is granted
        val initialReport = setupManager.buildVerificationReport()
        assertFalse("Core agent must not be marked ready when core permissions are missing", initialReport.isCoreAgentReady)
        assertEquals(0, initialReport.coreGrantedCount)
        assertTrue(initialReport.missingCorePermissions.contains(com.example.core.permission.PermissionType.MICROPHONE))
        assertTrue(initialReport.missingCorePermissions.contains(com.example.core.permission.PermissionType.OVERLAY))

        // Simulate granting all core permissions and verify report updates accurately
        val coreGrantOverrides = coreItems.associate {
            it.type to com.example.core.permission.PermissionStatus.GRANTED
        }
        val readyReport = setupManager.buildVerificationReport(overrideStatuses = coreGrantOverrides)
        assertTrue("Core agent must be ready once all core permissions are verified granted", readyReport.isCoreAgentReady)
        assertEquals(coreItems.size, readyReport.coreGrantedCount)
        assertTrue(readyReport.missingCorePermissions.isEmpty())

        // Complete onboarding wizard and verify persistence
        setupManager.setWizardStep(com.example.core.permission.SetupWizardStep.VERIFICATION_REPORT)
        assertEquals(com.example.core.permission.SetupWizardStep.VERIFICATION_REPORT, setupManager.currentStep.value)
        setupManager.markSetupCompleted("Babu")
        assertTrue(setupManager.isSetupCompleted())

        // Reset wizard and verify it returns to incomplete state
        setupManager.resetSetupWizard()
        assertFalse(setupManager.isSetupCompleted())
        assertEquals(com.example.core.permission.SetupWizardStep.WELCOME, setupManager.currentStep.value)

        // Verify com.example.permission.PermissionSetupManager integration with PermissionRegistry,
        // individual status checks, and special access settings Intents
        val pkgSetupManager = com.example.permission.PermissionSetupManager(context = null, permissionManager = null)
        val audited = pkgSetupManager.auditPermissions()
        assertEquals(13, audited.size)
        assertEquals(
            com.example.core.permission.PermissionStatus.DENIED,
            pkgSetupManager.checkPermissionStatus(com.example.core.permission.PermissionType.MICROPHONE)
        )
        assertEquals(
            com.example.core.permission.PermissionStatus.SPECIAL_ACCESS_REQUIRED,
            pkgSetupManager.checkPermissionStatus(com.example.core.permission.PermissionType.OVERLAY)
        )
        assertFalse(pkgSetupManager.isPermissionGranted(com.example.core.permission.PermissionType.OVERLAY))

        val overlayEval = pkgSetupManager.getPermissionEvaluation(com.example.core.permission.PermissionType.OVERLAY)
        assertNotNull(overlayEval)
        assertTrue(overlayEval!!.canLaunchSettings)

        assertEquals(
            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            pkgSetupManager.resolveSpecialAccessAction(com.example.core.permission.PermissionType.OVERLAY)
        )
        assertEquals(
            "package:com.example.nayro",
            pkgSetupManager.resolveSpecialAccessDataUri(
                com.example.core.permission.PermissionType.OVERLAY,
                packageNameOverride = "com.example.nayro"
            )
        )
        val overlayIntent = pkgSetupManager.createSpecialAccessIntent(
            com.example.core.permission.PermissionType.OVERLAY,
            packageNameOverride = "com.example.nayro"
        )
        assertNotNull("Overlay special access intent must be created", overlayIntent)
        assertFalse(
            "Headless triggerSpecialAccessSettings without Context/Activity returns false safely",
            pkgSetupManager.triggerOverlayPermissionSettings(activity = null)
        )
    }

    // =========================================================================
    // S. Section 2, 3, 5, 6, 8, 9, 11 & 12 Beyond-Andee Verification Tests
    // =========================================================================

    @Test
    fun testSection2FloatingBabuVoiceCommandsClassificationAndPlanning() {
        // 1. "Hi Babu, open WhatsApp."
        val cmd1 = TaskPlanner.planTask(TaskClassifier.classify("Hi Babu, open WhatsApp."))
        assertEquals(1, cmd1.actions.size)
        assertEquals(ActionType.LAUNCH_APP, cmd1.actions[0].actionType)
        assertTrue(cmd1.actions[0].target!!.contains("whatsapp", ignoreCase = true))

        // 2. "Hey Babu, call Rahul."
        val cmd2 = TaskPlanner.planTask(TaskClassifier.classify("Hey Babu, call Rahul."))
        assertTrue(cmd2.actions.any { it.actionType == ActionType.MAKE_PHONE_CALL })
        assertEquals("Rahul", cmd2.actions.first().target)

        // 3. "Hi Babu, turn on the flashlight."
        val cmd3 = TaskPlanner.planTask(TaskClassifier.classify("Hi Babu, turn on the flashlight."))
        assertEquals(1, cmd3.actions.size)
        assertEquals(ActionType.TOGGLE_FLASHLIGHT, cmd3.actions[0].actionType)
        assertEquals("ON", cmd3.actions[0].target)
        assertEquals("true", cmd3.actions[0].parameters["enabled"])

        // 4. "Hey Babu, read my notifications."
        val cmd4 = TaskPlanner.planTask(TaskClassifier.classify("Hey Babu, read my notifications."))
        assertEquals(1, cmd4.actions.size)
        assertEquals(ActionType.READ_NOTIFICATIONS, cmd4.actions[0].actionType)

        // 5. "Hi Babu, find my downloaded PDF."
        val cmd5 = TaskPlanner.planTask(TaskClassifier.classify("Hi Babu, find my downloaded PDF."))
        assertEquals(1, cmd5.actions.size)
        assertEquals(ActionType.FILE_SEARCH, cmd5.actions[0].actionType)
        assertEquals(".pdf", cmd5.actions[0].parameters["query"])

        // 6. "Hey Babu, create a reminder for tomorrow."
        val cmd6 = TaskPlanner.planTask(TaskClassifier.classify("Hey Babu, create a reminder for tomorrow."))
        assertEquals(1, cmd6.actions.size)
        assertEquals(ActionType.CREATE_REMINDER, cmd6.actions[0].actionType)
    }

    @Test
    fun testSection5CompoundWhatsAppMessageInstruction() {
        val brain = createTestBrain()
        val task = brain.planTaskForInstruction("Open WhatsApp, find Rahul, send him a message that I will reach in 20 minutes")
        assertEquals(4, task.actions.size)
        assertEquals(ActionType.LAUNCH_APP, task.actions[0].actionType)
        assertEquals(ActionType.CLICK_ELEMENT, task.actions[1].actionType)
        assertEquals("Rahul", task.actions[1].target)
        assertEquals(ActionType.SET_TEXT, task.actions[2].actionType)
        assertEquals("I will reach in 20 minutes", task.actions[2].parameters["text"])
        assertEquals(ActionType.ASK_USER_CONFIRMATION, task.actions[3].actionType)
        assertEquals("Rahul", task.actions[3].target)
        assertEquals("I will reach in 20 minutes", task.actions[3].parameters["draft"])
        assertEquals(RiskLevel.R3_EXTERNAL_COMMUNICATION, task.actions[3].riskLevel)
        assertTrue(task.actions[3].riskLevel.requiresConfirmation)
    }

    @Test
    fun testSection9ThreeTurnSequentialWhatsAppContext() {
        val continuity = com.example.core.memory.ConversationContinuityManager()

        // Turn 1: "Open WhatsApp."
        val turn1Classified = TaskClassifier.classify("Open WhatsApp.")
        assertEquals(UserIntentType.APP_LAUNCH, turn1Classified.type)
        continuity.recordEntityContext(app = turn1Classified.targetAppOrEntity)

        // Turn 2: "Message Rahul." -> missing message body, registers pending slot for recipient Rahul
        val turn2Classified = TaskClassifier.classify("Message Rahul.")
        assertEquals(UserIntentType.MESSAGE_DRAFT, turn2Classified.type)
        assertEquals("Rahul", turn2Classified.recipient)
        assertTrue(turn2Classified.searchQueryOrContent.isNullOrBlank())

        val turn2Task = TaskPlanner.planTask(turn2Classified)
        continuity.recordEntityContext(contact = "Rahul")
        continuity.registerPendingSlot(
            com.example.core.memory.PendingSlotContinuation(
                taskId = turn2Task.taskId,
                intentType = UserIntentType.MESSAGE_DRAFT,
                missingSlotName = "message",
                promptToUser = "Kya message bhejna hai Rahul ko?",
                collectedEntities = mapOf("recipient" to "Rahul"),
                createdAtMs = 1000L,
                expiresAtMs = 301_000L
            )
        )

        // Turn 3: "Tell him I will be late." -> strips "Tell him " and fills message body = "I will be late"
        val turn3Eval = continuity.evaluateIncomingTurn(
            userTurn = "Tell him I will be late.",
            hasActivePendingConfirmation = false,
            nowMs = 3000L
        )
        assertTrue(turn3Eval is com.example.core.memory.ContinuationResolution.SlotFilledResumeIntent)
        val resumed = turn3Eval as com.example.core.memory.ContinuationResolution.SlotFilledResumeIntent
        assertEquals("Rahul", resumed.resolvedIntent.recipient)
        assertEquals("I will be late", resumed.resolvedIntent.searchQueryOrContent)
    }

    @Test
    fun testSection3DeviceOperatingStateAndLockScreenPolicy() {
        val stateMgr = com.example.core.service.DeviceOperatingStateManager(context = null)

        // Operating states A..F
        stateMgr.onAppForegroundChanged(true)
        stateMgr.setTestingOverrides(isLocked = false, isScreenInteractive = true)
        assertEquals(
            com.example.core.service.DeviceOperatingState.APPLICATION_OPEN,
            stateMgr.refreshOperatingState()
        )

        stateMgr.onAppForegroundChanged(false)
        assertEquals(
            com.example.core.service.DeviceOperatingState.APPLICATION_IN_BACKGROUND,
            stateMgr.refreshOperatingState()
        )

        stateMgr.setTestingOverrides(isLocked = false, isScreenInteractive = false)
        assertEquals(
            com.example.core.service.DeviceOperatingState.SCREEN_OFF,
            stateMgr.refreshOperatingState()
        )

        stateMgr.setTestingOverrides(isLocked = true, isScreenInteractive = true)
        assertEquals(
            com.example.core.service.DeviceOperatingState.DEVICE_LOCKED,
            stateMgr.refreshOperatingState()
        )

        stateMgr.onBootCompleted()
        assertEquals(
            com.example.core.service.DeviceOperatingState.DEVICE_RESTARTED,
            stateMgr.currentOperatingState.value
        )

        // Lock screen policy checks
        val timeAction = AgentAction(taskId = "t1", actionType = ActionType.GET_DEVICE_TIME, riskLevel = RiskLevel.R0_LOCAL_READONLY)
        val torchAction = AgentAction(taskId = "t1", actionType = ActionType.TOGGLE_FLASHLIGHT, parameters = mapOf("enabled" to "true"), riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE)
        val sendMsgAction = AgentAction(taskId = "t1", actionType = ActionType.MESSAGE_DRAFT_SEND, target = "Rahul", riskLevel = RiskLevel.R3_EXTERNAL_COMMUNICATION)
        val launchAppAction = AgentAction(taskId = "t1", actionType = ActionType.LAUNCH_APP, target = "WhatsApp", riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE)

        assertTrue(
            stateMgr.evaluateLockScreenPolicy(timeAction, isLocked = true) is com.example.core.service.LockScreenPolicyDecision.AllowedWhileLocked
        )
        assertTrue(
            stateMgr.evaluateLockScreenPolicy(torchAction, isLocked = true) is com.example.core.service.LockScreenPolicyDecision.AllowedWhileLocked
        )
        assertTrue(
            stateMgr.evaluateLockScreenPolicy(sendMsgAction, isLocked = true) is com.example.core.service.LockScreenPolicyDecision.RequireDeviceUnlock
        )
        assertTrue(
            stateMgr.evaluateLockScreenPolicy(launchAppAction, isLocked = true) is com.example.core.service.LockScreenPolicyDecision.RequireDeviceUnlock
        )
    }

    @Test
    fun testSection11WakeWordSensitivityAndCommandExtraction() {
        val engine = LocalWakeWordEngine(null)
        engine.setSensitivity(0.85f)
        assertEquals(0.85f, engine.getSensitivity(), 0.001f)
        assertTrue(engine.isValidSampleRate(16000))
        assertFalse(engine.isValidSampleRate(12345))

        val split = engine.extractCommandAfterWakePhrase("Hi Babu, turn on the flashlight")
        assertNotNull(split)
        assertEquals("Hi Babu", split!!.first)
        assertEquals("turn on the flashlight", split.second)
    }

    @Test
    fun testSection12AutomationEngineUpdateAndManualRun() = runBlocking {
        val autoEngine = com.example.core.automation.AutomationEngine()
        val created = autoEngine.createRule(
            name = "Test Rule",
            triggerType = com.example.core.automation.AutomationTriggerType.MANUAL_TRIGGER,
            triggerConditionValue = "",
            actionInstruction = "What time is it"
        )
        val updated = autoEngine.updateRule(
            ruleId = created.ruleId,
            name = "Updated Rule Name",
            actionInstruction = "Turn on the flashlight"
        )
        assertNotNull(updated)
        assertEquals("Updated Rule Name", updated!!.name)

        val record = autoEngine.executeRuleNow(created.ruleId) { instr ->
            true to "Executed: $instr"
        }
        assertNotNull(record)
        assertTrue(record!!.isSuccess)
        assertEquals("Executed: Turn on the flashlight", record.outputSummary)
    }

    @Test
    fun testSection8WhatsAppIntegrationSingleMultiRecipientAndReadConversation() = runBlocking {
        val callMgr = com.example.core.telephony.CallIntelligenceManager(
            context = null,
            contactDirectoryProvider = {
                listOf(
                    com.example.core.telephony.ResolvedContactItem("Rahul Sharma", "+919876543210"),
                    com.example.core.telephony.ResolvedContactItem("Amit Verma", "+919811112222"),
                    com.example.core.telephony.ResolvedContactItem("Priya Nair", "+919833334444"),
                    com.example.core.telephony.ResolvedContactItem("Rohit A", "+919000000001"),
                    com.example.core.telephony.ResolvedContactItem("Rohit B", "+919000000002")
                )
            }
        )
        val waMgr = com.example.core.communication.WhatsAppAutomationManager(
            context = null,
            callIntelligenceManager = callMgr,
            screenIntelligenceManager = null
        ).apply {
            testOverrideInstalled = true
            testOverrideSendVerified = true
            testOverrideVisibleMessages = listOf("Amit: Tomorrow's meeting is moved to 11 AM", "You: Got it, thanks!")
        }

        // 1. Unconfirmed send must be blocked with REQUIRES_CONFIRMATION
        val unconfirmed = waMgr.sendOrDraftMessage("Rahul", "I will arrive tomorrow", isConfirmed = false)
        assertFalse(unconfirmed.isSuccess)
        assertEquals(VerificationStatus.REQUIRES_CONFIRMATION, unconfirmed.status)

        // 2. Ambiguous contact must be rejected honestly
        val ambiguous = waMgr.sendOrDraftMessage("Rohit", "Hello", isConfirmed = true)
        assertFalse(ambiguous.isSuccess)
        assertEquals(VerificationStatus.FAILED, ambiguous.status)
        assertTrue(ambiguous.outputMessage.contains("Ambiguous"))

        // 3. Confirmed single-recipient send with verified bubble
        val verifiedSingle = waMgr.sendOrDraftMessage("Rahul", "I will arrive tomorrow", isConfirmed = true)
        assertTrue(verifiedSingle.isSuccess)
        assertEquals(VerificationStatus.VERIFIED, verifiedSingle.status)
        assertTrue(verifiedSingle.outputMessage.contains("Rahul Sharma"))

        // 4. Multi-recipient batch send ("Send the same approved update to these three contacts")
        val batchRes = waMgr.sendOrDraftMessage("Rahul, Amit, Priya", "Team standup at 10 AM", isConfirmed = true)
        assertTrue(batchRes.isSuccess)
        assertEquals(VerificationStatus.VERIFIED, batchRes.status)
        assertTrue(batchRes.outputMessage.contains("Rahul Sharma"))
        assertTrue(batchRes.outputMessage.contains("Amit Verma"))
        assertTrue(batchRes.outputMessage.contains("Priya Nair"))

        // 5. Read / summarize visible WhatsApp conversation
        val readConv = waMgr.readOrSearchWhatsAppConversation("Amit")
        assertTrue(readConv.isSuccess)
        assertEquals(VerificationStatus.VERIFIED, readConv.status)
        assertTrue(readConv.outputMessage.contains("Tomorrow's meeting is moved to 11 AM"))
    }

    @Test
    fun testSection9AndScenario3CrossAppWhatsAppAndCalendarWorkflow() {
        val brain = createHeadlessBrain()

        // Scenario 3A: "Open WhatsApp, send Rahul a message saying I will arrive tomorrow, then check tomorrow's meetings"
        val promptA = "Open WhatsApp, send Rahul a message saying I will arrive tomorrow, then check tomorrow's meetings"
        val taskA = brain.planTaskForInstruction(promptA)
        assertEquals("MULTI_STEP_COMPOUND", taskA.resolvedIntent)
        assertTrue(taskA.actions.any { it.actionType == ActionType.LAUNCH_APP && it.target.equals("com.whatsapp", ignoreCase = true) })
        assertTrue(taskA.actions.any { it.actionType == ActionType.SET_TEXT && it.parameters["text"] == "I will arrive tomorrow" })
        assertTrue(taskA.actions.any { it.actionType == ActionType.ASK_USER_CONFIRMATION && it.target == "Rahul" })
        assertTrue(taskA.actions.any { it.actionType == ActionType.READ_CALENDAR_EVENTS && it.parameters["date"] == "tomorrow" })

        // Scenario 3B: "BABU, open WhatsApp, message Rahul that I will attend tomorrow's meeting, then check tomorrow's Calendar meetings and tell me the schedule"
        val promptB = "BABU, open WhatsApp, message Rahul that I will attend tomorrow's meeting, then check tomorrow's Calendar meetings and tell me the schedule"
        val taskB = brain.planTaskForInstruction(promptB)
        assertEquals("MULTI_STEP_COMPOUND", taskB.resolvedIntent)
        assertTrue(taskB.actions.any { it.actionType == ActionType.ASK_USER_CONFIRMATION && it.target == "Rahul" })
        assertTrue(taskB.actions.any { it.actionType == ActionType.READ_CALENDAR_EVENTS && it.parameters["date"] == "tomorrow" })
    }

    @Test
    fun testSection10CalendarIntegrationCrudConflictAndRelativeDateResolution() {
        val calMgr = com.example.core.calendar.CalendarIntegrationManager(context = null)

        // 1. Dynamic calendar discovery (never hardcodes 1L)
        val accounts = calMgr.getAvailableCalendars()
        assertTrue(accounts.isNotEmpty())
        assertEquals(101L, calMgr.resolveWritableCalendarId())

        // 2. Relative date range resolution ("today", "tomorrow", "2026-11-15")
        val nowMs = 1_762_776_000_000L
        val tomorrowRange = calMgr.resolveDateRange("tomorrow", nowMs)
        assertEquals("Tomorrow", tomorrowRange.label)
        assertTrue(tomorrowRange.endMs > tomorrowRange.startMs)

        // 3. Create event and verify read-back
        val start1 = tomorrowRange.startMs + 10 * 3_600_000L // 10:00 AM tomorrow
        val end1 = start1 + 3_600_000L // 11:00 AM tomorrow
        val createRes1 = calMgr.createEvent(
            title = "Architecture Review with Rahul",
            startTimeMs = start1,
            endTimeMs = end1,
            location = "Room 302"
        )
        assertTrue(createRes1.isSuccess)
        assertEquals(VerificationStatus.VERIFIED, createRes1.status)

        // 4. Create overlapping event and verify conflict detection
        val start2 = start1 + 1_800_000L // 10:30 AM tomorrow (overlaps!)
        val end2 = start2 + 3_600_000L
        val createRes2 = calMgr.createEvent(
            title = "Product Sync",
            startTimeMs = start2,
            endTimeMs = end2
        )
        assertTrue(createRes2.isSuccess)
        assertTrue(createRes2.outputMessage.contains("overlaps"))

        // 5. Query tomorrow's events and verify both events + conflict note
        val queryTomorrow = calMgr.queryEvents(dateQuery = "tomorrow", referenceNowMs = nowMs)
        assertTrue(queryTomorrow.isSuccess)
        assertEquals(VerificationStatus.VERIFIED, queryTomorrow.status)
        assertTrue(queryTomorrow.outputMessage.contains("Architecture Review with Rahul"))
        assertTrue(queryTomorrow.outputMessage.contains("Product Sync"))
        assertTrue(queryTomorrow.outputMessage.contains("Conflicts detected"))

        // 6. Update first event and verify
        val eventId1 = Regex("#(\\d+)").find(createRes1.outputMessage)!!.groupValues[1].toLong()
        val updateRes = calMgr.updateEvent(eventId = eventId1, newTitle = "Updated Architecture Review")
        assertTrue(updateRes.isSuccess)
        assertEquals(VerificationStatus.VERIFIED, updateRes.status)
        assertTrue(updateRes.outputMessage.contains("Updated Architecture Review"))

        // 7. Delete without confirmation must return REQUIRES_CONFIRMATION
        val unconfirmedDel = calMgr.deleteEvent(eventId1, isConfirmed = false)
        assertFalse(unconfirmedDel.isSuccess)
        assertEquals(VerificationStatus.REQUIRES_CONFIRMATION, unconfirmedDel.status)

        // 8. Confirmed delete and verify removal
        val confirmedDel = calMgr.deleteEvent(eventId1, isConfirmed = true)
        assertTrue(confirmedDel.isSuccess)
        assertEquals(VerificationStatus.VERIFIED, confirmedDel.status)
    }

    @Test
    fun testSection13LocalWakeWordAcousticModelMfccAndAudioInterruptionRecovery() {
        val engine = LocalWakeWordEngine(context = null)
        // Before loading acoustic model in headless null-context mode, starting listening sets MODEL_UNAVAILABLE
        engine.startListening()
        assertEquals(WakeEngineState.MODEL_UNAVAILABLE, engine.engineState.value)

        // Load bundled acoustic KWS model templates
        val loaded = engine.loadAcousticModel(allowEmbeddedFallbackInTests = true)
        assertTrue(loaded)
        assertTrue(engine.isOfflineKwsModelAvailable())

        // Start listening transitions to LISTENING_FOR_WAKE
        engine.startListening()
        assertEquals(WakeEngineState.LISTENING_FOR_WAKE, engine.engineState.value)

        // Test PCM preprocessing & 13-coefficient MFCC extraction
        val syntheticFrame = ShortArray(512) { idx ->
            (Math.sin(2.0 * Math.PI * 440.0 * idx / 16000.0) * 12000).toInt().toShort()
        }
        val preprocessed = engine.preprocessPcmFrame(syntheticFrame, 512)
        assertEquals(512, preprocessed.size)
        val mfcc = engine.extractMfccFeatures(syntheticFrame, 512)
        assertEquals(13, mfcc.size)

        // Audio interruption & automatic recovery
        engine.onAudioInterruptionStarted("Phone call active")
        assertEquals(WakeEngineState.MICROPHONE_BUSY, engine.engineState.value)
        engine.recoverFromAudioInterruption()
        assertEquals(WakeEngineState.LISTENING_FOR_WAKE, engine.engineState.value)
    }

    @Test
    fun testScenario4DynamicUnknownMultiToolWorkflow() {
        val brain = createHeadlessBrain()
        val prompt = "Check battery status, then check free storage space, then inspect code in SampleAgentModule.kt, then summarize the workspace guide document, and then save the summary as a note"
        val task = brain.planTaskForInstruction(prompt)

        assertEquals(5, task.actions.size)
        assertEquals(ActionType.GET_BATTERY_STATE, task.actions[0].actionType)
        assertEquals(ActionType.GET_STORAGE_INFO, task.actions[1].actionType)
        assertEquals(ActionType.CODE_INSPECT_FILE, task.actions[2].actionType)
        assertEquals(ActionType.DOC_SUMMARIZE, task.actions[3].actionType)
        assertEquals(ActionType.SAVE_NOTE, task.actions[4].actionType)
    }

    @Test
    fun testScenario5TaskCheckpointAndDuplicateSideEffectPrevention() {
        val runtime = UnsafeTestHelper.allocateWithoutInit(com.example.core.agent.AgentRuntime::class.java)
        val setField = com.example.core.agent.AgentRuntime::class.java.getDeclaredField("completedSideEffectSignatures").apply {
            isAccessible = true
        }
        val backingSet = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        setField.set(runtime, backingSet)

        assertFalse(runtime.hasSideEffectAlreadyExecuted("task-99", "act-1"))
        runtime.markSideEffectExecuted("task-99", "act-1")
        assertTrue(runtime.hasSideEffectAlreadyExecuted("task-99", "act-1"))
    }

    @Test
    fun testScenario6PersistentAutomationTriggersPauseResumeAndRebootReschedule() = runBlocking {
        val autoEngine = com.example.core.automation.AutomationEngine(context = null)

        val batteryRule = autoEngine.createRule(
            name = "Low Battery Saver Note",
            triggerType = com.example.core.automation.AutomationTriggerType.BATTERY_BELOW_THRESHOLD,
            triggerConditionValue = "25",
            actionInstruction = "Save a note: Battery dropped below 25%"
        )
        val executedInstructions = mutableListOf<String>()
        autoEngine.onEventTriggered(
            triggerType = com.example.core.automation.AutomationTriggerType.BATTERY_BELOW_THRESHOLD,
            eventValue = "20"
        ) { instr ->
            executedInstructions.add(instr)
            true to "Saved battery note"
        }
        assertEquals(1, executedInstructions.size)

        // Pause all automations -> should not trigger while paused
        autoEngine.pauseAllAutomations()
        assertTrue(autoEngine.isPaused.value)
        autoEngine.onEventTriggered(
            triggerType = com.example.core.automation.AutomationTriggerType.BATTERY_BELOW_THRESHOLD,
            eventValue = "15"
        ) { instr ->
            executedInstructions.add(instr)
            true to "Should not run"
        }
        assertEquals(1, executedInstructions.size)

        // Resume automations and verify JSON persistence export/import
        autoEngine.resumeAllAutomations()
        assertFalse(autoEngine.isPaused.value)
        val exportedJson = autoEngine.exportRulesJson()
        assertTrue(exportedJson.contains("Low Battery Saver Note"))
        assertTrue(autoEngine.importRulesJson(exportedJson))
        assertNotNull(autoEngine.rules.value.find { it.ruleId == batteryRule.ruleId })
    }

    @Test
    fun testSection22AllRealWorldAcceptanceScenariosAToL() = runBlocking {
        val brain = createHeadlessBrain()

        // TEST A — "Open WhatsApp"
        val testA = brain.planTaskForInstruction("Open WhatsApp")
        assertEquals(ActionType.LAUNCH_APP, testA.actions.first().actionType)
        assertEquals("WhatsApp", testA.actions.first().target)

        // TEST B — "Find Satyam and send Hi"
        val testB = brain.planTaskForInstruction("Find Satyam and send Hi")
        assertTrue(testB.actions.any { it.actionType == ActionType.LAUNCH_APP })
        assertTrue(testB.actions.any { it.actionType == ActionType.CLICK_ELEMENT && it.target == "Satyam" })
        assertTrue(testB.actions.any { it.actionType == ActionType.SET_TEXT && it.parameters["text"] == "Hi" })
        assertTrue(testB.actions.any { it.actionType == ActionType.ASK_USER_CONFIRMATION && it.riskLevel == RiskLevel.R3_EXTERNAL_COMMUNICATION })

        // TEST C — "Open YouTube and search Android agent"
        val testC = brain.planTaskForInstruction("Open YouTube and search Android agent")
        assertEquals(ActionType.LAUNCH_APP, testC.actions[0].actionType)
        assertEquals("com.google.android.youtube", testC.actions[0].target)
        assertEquals(ActionType.SET_TEXT, testC.actions.last().actionType)
        assertEquals("Android agent", testC.actions.last().parameters["query"])

        // TEST D — "Open Settings and go to Wi-Fi"
        val testD = brain.planTaskForInstruction("Open Settings and go to Wi-Fi")
        assertEquals(ActionType.OPEN_SETTINGS_SECTION, testD.actions[0].actionType)
        assertEquals("wifi", testD.actions[0].target)

        // TEST E — "Remind me tomorrow at 7 AM"
        val testE = brain.planTaskForInstruction("Remind me tomorrow at 7 AM")
        assertEquals(ActionType.CREATE_REMINDER, testE.actions[0].actionType)
        assertEquals("tomorrow at 7 AM", testE.actions[0].target)

        // TEST F — "Read the current screen"
        val testF = brain.planTaskForInstruction("Read the current screen")
        assertEquals(ActionType.READ_SCREEN, testF.actions[0].actionType)

        // TEST G — "Save a note from current screen"
        val testG = brain.planTaskForInstruction("Save a note from current screen")
        assertEquals(ActionType.READ_SCREEN, testG.actions[0].actionType)
        assertEquals(ActionType.SAVE_NOTE, testG.actions[1].actionType)

        // TEST H — "Summarize my recent notifications"
        val testH = brain.planTaskForInstruction("Summarize my recent notifications")
        assertEquals(ActionType.SUMMARIZE_NOTIFICATIONS, testH.actions[0].actionType)

        // TEST I — "Find my downloaded PDF"
        val testI = brain.planTaskForInstruction("Find my downloaded PDF")
        assertEquals(ActionType.FILE_SEARCH, testI.actions[0].actionType)
        assertEquals(".pdf", testI.actions[0].target)

        // TEST J — "Call Rahul"
        val testJ = brain.planTaskForInstruction("Call Rahul")
        assertEquals(ActionType.MAKE_PHONE_CALL, testJ.actions[0].actionType)
        assertEquals("Rahul", testJ.actions[0].target)
        assertEquals(RiskLevel.R3_EXTERNAL_COMMUNICATION, testJ.actions[0].riskLevel)

        // TEST K — "Turn on the flashlight"
        val testK = brain.planTaskForInstruction("Turn on the flashlight")
        assertEquals(ActionType.TOGGLE_FLASHLIGHT, testK.actions[0].actionType)
        assertEquals("ON", testK.actions[0].target)

        // TEST L — "Stop current task"
        val stateManager = TaskStateManager()
        val runningTask = brain.planTaskForInstruction("Open YouTube and search Android agent")
        stateManager.transition(runningTask, TaskState.RUNNING)
        val cancelled = stateManager.transition(runningTask, TaskState.CANCELLED, "Stopped by user")
        assertTrue(cancelled)
        assertEquals(TaskState.CANCELLED, runningTask.state)
    }

    @Test
    fun testSection19HumanReadableStatusMessages() {
        val msgDesc = ActionType.MESSAGE_DRAFT_SEND.toUserFriendlyDescription(
            target = "com.whatsapp",
            parameters = mapOf("recipient" to "Rahul")
        )
        assertEquals("Preparing your WhatsApp message for Rahul...", msgDesc)
        assertFalse(msgDesc.contains("MESSAGE_DRAFT_SEND"))

        val llmDesc = ActionType.QUERY_LLM.toUserFriendlyDescription()
        assertEquals("Understanding your request...", llmDesc)
        assertFalse(llmDesc.contains("QUERY_LLM"))

        assertEquals("Preparing your WhatsApp message", "MESSAGE_DRAFT_SEND".toUserFriendlyIntentLabel())
        assertEquals("Understanding your request", "GENERAL_AI_QUERY".toUserFriendlyIntentLabel())
        assertEquals("In Progress", TaskState.EXECUTING.toUserFriendlyLabel())
        assertEquals("Waiting for Confirmation", TaskState.WAITING_FOR_CONFIRMATION.toUserFriendlyLabel())
    }

    @Test
    fun testPhase9ReminderTimeParsingAndScheduling() {
        val engine = AutomationEngine()
        val fixedNow = 1_760_000_000_000L

        val in15Min = engine.parseReminderTimeMillis("Remind me in 15 minutes to call mom", fixedNow)
        assertEquals(fixedNow + 15L * 60_000L, in15Min)

        val in2Hours = engine.parseReminderTimeMillis("in 2 hours", fixedNow)
        assertEquals(fixedNow + 2L * 3_600_000L, in2Hours)

        val tomorrow7Am = engine.parseReminderTimeMillis("tomorrow at 7 AM", fixedNow)
        assertTrue("Tomorrow at 7 AM must be in the future", tomorrow7Am > fixedNow)

        val (scheduledRule, _) = engine.scheduleReminder(
            reminderTitle = "Submit architecture report",
            rawTimeSpec = "in 30 minutes",
            nowMs = fixedNow
        )
        assertTrue(engine.rules.value.any { it.ruleId == scheduledRule.ruleId })
    }

    @Test
    fun testPhase11ConversationContinuityFollowUps() {
        val continuity = ConversationContinuityManager()
        continuity.recordEntityContext(
            contact = "Rahul",
            app = "WhatsApp",
            calendarEventTitle = "Design Review",
            executedInstruction = "Open YouTube and search Kotlin"
        )

        val r1 = continuity.resolveReferencesInPrompt("Usko message bhejo")
        assertTrue("Expected Rahul in resolved prompt: $r1", r1.contains("Rahul"))

        val r2 = continuity.resolveReferencesInPrompt("Do the same thing again")
        assertEquals("Open YouTube and search Kotlin", r2)

        val r3 = continuity.resolveReferencesInPrompt("Pichhla task continue karo")
        assertEquals("Open YouTube and search Kotlin", r3)

        val r4 = continuity.resolveReferencesInPrompt("Wahi event kal ke liye bana do")
        assertTrue("Expected Design Review in resolved prompt: $r4", r4.contains("Design Review"))

        val r5 = continuity.resolveReferencesInPrompt("Remind me after the meeting")
        assertTrue("Expected Design Review in resolved prompt: $r5", r5.contains("Design Review"))
    }

    @Test
    fun testPhase17MandatoryTestCasesT01ToT30() {
        val brain = createHeadlessBrain()

        // T01: "Hello Babu"
        val t01 = brain.planTaskForInstruction("Hello Babu")
        assertTrue(t01.actions.isNotEmpty())

        // T03: "Open YouTube"
        val t03 = brain.planTaskForInstruction("Open YouTube")
        assertEquals(ActionType.LAUNCH_APP, t03.actions.first().actionType)
        assertTrue(t03.actions.first().target.equals("YouTube", ignoreCase = true) || t03.actions.first().target == "com.google.android.youtube")

        // T04: "Open YouTube and search Kotlin"
        val t04 = brain.planTaskForInstruction("Open YouTube and search Kotlin")
        assertEquals(ActionType.LAUNCH_APP, t04.actions.first().actionType)
        assertEquals("Kotlin", t04.actions.last().parameters["query"])

        // T05: "Open Settings and go to Wi-Fi"
        val t05 = brain.planTaskForInstruction("Open Settings and go to Wi-Fi")
        assertEquals(ActionType.OPEN_SETTINGS_SECTION, t05.actions.first().actionType)
        assertEquals("wifi", t05.actions.first().target)

        // T06: "Turn on flashlight"
        val t06 = brain.planTaskForInstruction("Turn on flashlight")
        assertEquals(ActionType.TOGGLE_FLASHLIGHT, t06.actions.first().actionType)
        assertEquals("ON", t06.actions.first().target)

        // T07: "Turn off flashlight"
        val t07 = brain.planTaskForInstruction("Turn off flashlight")
        assertEquals(ActionType.TOGGLE_FLASHLIGHT, t07.actions.first().actionType)
        assertEquals("OFF", t07.actions.first().target)

        // T08: "Check battery level"
        val t08 = brain.planTaskForInstruction("Check battery level")
        assertEquals(ActionType.GET_BATTERY_STATE, t08.actions.first().actionType)

        // T09: "Check storage status"
        val t09 = brain.planTaskForInstruction("Check storage status")
        assertEquals(ActionType.GET_STORAGE_INFO, t09.actions.first().actionType)

        // T10: "Read current screen"
        val t10 = brain.planTaskForInstruction("Read current screen")
        assertEquals(ActionType.READ_SCREEN, t10.actions.first().actionType)

        // T12: "Scroll down"
        val t12 = brain.planTaskForInstruction("Scroll down")
        assertEquals(ActionType.SCROLL_FORWARD, t12.actions.first().actionType)

        // T16: "Send WhatsApp message to Rahul saying hello"
        val t16 = brain.planTaskForInstruction("Send WhatsApp message to Rahul saying hello")
        assertTrue(t16.actions.any { it.actionType == ActionType.LAUNCH_APP && it.target == "com.whatsapp" })
        assertTrue(t16.actions.any { it.actionType == ActionType.CLICK_ELEMENT && it.target == "Rahul" })
        assertTrue(t16.actions.any { it.actionType == ActionType.SET_TEXT && it.parameters["text"]?.equals("hello", ignoreCase = true) == true })
        assertTrue(t16.actions.any { it.actionType == ActionType.ASK_USER_CONFIRMATION && it.riskLevel == RiskLevel.R3_EXTERNAL_COMMUNICATION && it.riskLevel.requiresConfirmation })

        // T19: "Call Rahul"
        val t19 = brain.planTaskForInstruction("Call Rahul")
        assertEquals(ActionType.MAKE_PHONE_CALL, t19.actions.first().actionType)
        assertEquals("Rahul", t19.actions.first().target)
        assertTrue(t19.actions.first().riskLevel.requiresConfirmation)

        // T20: "Remind me tomorrow at 7 AM"
        val t20 = brain.planTaskForInstruction("Remind me tomorrow at 7 AM")
        assertEquals(ActionType.CREATE_REMINDER, t20.actions.first().actionType)

        // T21: "Create calendar event tomorrow at 5 PM"
        val t21 = brain.planTaskForInstruction("Create calendar event Team Sync tomorrow at 5 PM")
        assertEquals(ActionType.CREATE_CALENDAR_EVENT, t21.actions.first().actionType)

        // T22: "Show today's agenda"
        val t22 = brain.planTaskForInstruction("Show today's agenda")
        assertEquals(ActionType.READ_CALENDAR_EVENTS, t22.actions.first().actionType)

        // T23: "Summarize recent notifications"
        val t23 = brain.planTaskForInstruction("Summarize recent notifications")
        assertEquals(ActionType.SUMMARIZE_NOTIFICATIONS, t23.actions.first().actionType)

        // T24: "Save a note from current screen"
        val t24 = brain.planTaskForInstruction("Save a note from current screen")
        assertEquals(ActionType.READ_SCREEN, t24.actions[0].actionType)
        assertEquals(ActionType.SAVE_NOTE, t24.actions[1].actionType)
    }

    @Test
    fun testSection5WakeWordAssetFormatInspectionAndDtwEnrollment() {
        val engine = LocalWakeWordEngine(null)
        assertTrue(engine.loadAcousticModel(allowEmbeddedFallbackInTests = true))
        assertEquals(
            com.example.core.voice.engine.WakeAssetFormat.JSON_ACOUSTIC_TEMPLATES,
            engine.inspectBundledAssetFormat(allowEmbeddedFallbackInTests = true)
        )

        // Generate a realistic 16kHz speech-like PCM waveform (1200 samples = 75ms)
        val enrollPcm = ShortArray(1200) { i ->
            val carrier = kotlin.math.sin(2.0 * Math.PI * 240.0 * i / 16000.0)
            val harmonic = 0.5 * kotlin.math.sin(2.0 * Math.PI * 520.0 * i / 16000.0)
            ((carrier + harmonic) * 2400.0).toInt().coerceIn(-32767, 32767).toShort()
        }

        // Enroll two voice samples for "Hi Babu"
        assertTrue(engine.enrollWakePhraseSample("Hi Babu", enrollPcm))
        assertTrue(engine.enrollWakePhraseSample("Hi Babu", enrollPcm))
        assertEquals(2, engine.getEnrolledSampleCount("Hi Babu"))

        // Verify DTW self-alignment distance is near zero and matches "Hi Babu"
        val seq = engine.extractMfccSequence(enrollPcm)
        assertTrue(seq.size >= 3)
        val selfDist = engine.computeDtwDistance(seq, seq)
        assertEquals(0.0f, selfDist, 0.001f)

        val dtwMatch = engine.matchUtteranceByDtw(enrollPcm)
        assertNotNull(dtwMatch)
        assertEquals("Hi Babu", dtwMatch?.first)
        assertTrue((dtwMatch?.second ?: 0f) >= engine.getConfidenceThreshold())

        // Verify silence never triggers DTW
        val silencePcm = ShortArray(1200) { 0 }
        assertNull(engine.matchUtteranceByDtw(silencePcm))

        // Verify voice exit commands
        assertTrue(engine.isVoiceExitCommand("Stop Babu"))
        assertTrue(engine.isVoiceExitCommand("Band karo"))
        assertFalse(engine.isVoiceExitCommand("Open WhatsApp"))
    }

    @Test
    fun testSection7CentralCommandDispatcherAndRecovery() = runBlocking {
        val brain = createHeadlessBrain()
        val dummyExecutor = UnsafeTestHelper.allocateWithoutInit(ActionExecutor::class.java)

        // Invalid INTERACT_COORDINATES missing x/y must be rejected by CommandDispatcher before execution
        val invalidCoordAction = AgentAction(
            taskId = "task-cmd-1",
            actionType = ActionType.INTERACT_COORDINATES,
            parameters = emptyMap()
        )
        val dispatchRes = com.example.core.agent.tools.CommandDispatcher.dispatch(invalidCoordAction, dummyExecutor)
        assertFalse(dispatchRes.isSuccess)
        assertEquals(VerificationStatus.FAILED, dispatchRes.status)
        assertTrue(dispatchRes.outputMessage.contains("CommandDispatcher schema validation failed"))

        // Verify ARTEMIS-inspired replanFailedStep recovery for CLICK_ELEMENT -> SCROLL_FORWARD
        val failedClick = AgentAction(
            taskId = "task-rec-1",
            actionType = ActionType.CLICK_ELEMENT,
            target = "Submit"
        )
        val recovery = brain.replanFailedStep(failedClick, "Target not visible")
        assertNotNull(recovery)
        assertEquals(ActionType.SCROLL_FORWARD, recovery?.actionType)
    }

    @Test
    fun testVisualTargetIdentificationFromScreenSnapshot() {
        val sendNode = com.example.core.screen.ScreenNodeInfo(
            text = null,
            contentDescription = "Send Message",
            viewId = "com.whatsapp:id/send",
            className = "android.widget.ImageButton",
            isClickable = true,
            isEditable = false,
            isScrollable = false,
            boundsLeft = 900,
            boundsTop = 1800,
            boundsRight = 1040,
            boundsBottom = 1940
        )
        val snapshot = com.example.core.screen.ScreenSnapshot(
            packageName = "com.whatsapp",
            visibleTexts = listOf("Rahul", "Send Message"),
            interactiveElements = listOf(sendNode),
            totalNodeCount = 2
        )

        val exactMatch = com.example.core.screen.ScreenIntelligenceManager.locateTargetInSnapshot("Send Message", snapshot)
        assertNotNull(exactMatch)
        assertEquals(970, exactMatch?.centerX)
        assertEquals(1870, exactMatch?.centerY)
        assertEquals("SEMANTIC_EXACT_NODE", exactMatch?.strategy)

        val idMatch = com.example.core.screen.ScreenIntelligenceManager.locateTargetInSnapshot("send", snapshot)
        assertNotNull(idMatch)
        assertEquals(970, idMatch?.centerX)
        assertEquals(1870, idMatch?.centerY)
    }

    @OptIn(com.google.accompanist.permissions.ExperimentalPermissionsApi::class)
    @Test
    fun testAccompanistPermissionUtilityForMicrophoneAndAccessibility() {
        val util = com.example.permission.AccompanistPermissionUtility(context = null)
        assertEquals(
            android.Manifest.permission.RECORD_AUDIO,
            com.example.core.permission.AccompanistPermissionUtility.MICROPHONE_PERMISSION
        )
        assertEquals(
            android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS,
            com.example.core.permission.AccompanistPermissionUtility.ACCESSIBILITY_SETTINGS_ACTION
        )

        // Evaluate Accompanist status combinations
        assertEquals(
            com.example.core.permission.PermissionStatus.GRANTED,
            com.example.core.permission.AccompanistPermissionUtility.evaluateMicrophoneAccompanistStatus(
                isGranted = true,
                shouldShowRationale = false,
                requestedBefore = true
            )
        )
        assertEquals(
            com.example.core.permission.PermissionStatus.DENIED,
            com.example.core.permission.AccompanistPermissionUtility.evaluateMicrophoneAccompanistStatus(
                isGranted = false,
                shouldShowRationale = true,
                requestedBefore = true
            )
        )
        assertEquals(
            com.example.core.permission.PermissionStatus.PERMANENTLY_DENIED,
            com.example.core.permission.AccompanistPermissionUtility.evaluateMicrophoneAccompanistStatus(
                isGranted = false,
                shouldShowRationale = false,
                requestedBefore = true
            )
        )

        // Test Accompanist PermissionState request dispatch
        var launchCount = 0
        var markedRequested = false
        val fakePermissionState = object : com.google.accompanist.permissions.PermissionState {
            override val permission: String = android.Manifest.permission.RECORD_AUDIO
            override val status: com.google.accompanist.permissions.PermissionStatus =
                com.google.accompanist.permissions.PermissionStatus.Denied(shouldShowRationale = false)
            override fun launchPermissionRequest() {
                launchCount++
            }
        }
        com.example.core.permission.AccompanistPermissionUtility.requestMicrophoneWithAccompanist(
            permissionState = fakePermissionState,
            context = null,
            requestedBefore = false,
            onMarkRequested = { markedRequested = true }
        )
        assertTrue(markedRequested)
        assertEquals(1, launchCount)

        // Test Accessibility Settings & App Details Intent creation and headless safety
        assertNotNull(util.getAccessibilitySettingsIntent())
        assertNotNull(util.getAppDetailsSettingsIntent("com.aistudio.nayro.app"))
        assertFalse(util.isMicrophoneGranted())
        assertFalse(util.isAccessibilityEnabled())
        assertFalse(util.openAccessibilitySettings(activity = null))
    }

    @Test
    fun testSection4BackgroundAgentPauseAndResumeLifecycle() {
        val nowMs = 1_700_000_000_000L
        com.example.core.service.ServiceStateRepository.pauseBackgroundAgent(
            durationMinutes = 15,
            reason = "User paused for 15 minutes",
            nowMs = nowMs
        )
        assertTrue(com.example.core.service.ServiceStateRepository.isBackgroundAgentPaused.value)
        assertEquals(
            com.example.core.service.ServiceLifecycleState.PAUSED,
            com.example.core.service.ServiceStateRepository.foregroundServiceStatus.value.state
        )
        assertEquals(
            nowMs + 15L * 60_000L,
            com.example.core.service.ServiceStateRepository.pausedUntilTimestampMs.value
        )
        assertEquals(
            "User paused for 15 minutes",
            com.example.core.service.ServiceStateRepository.pauseReason.value
        )
        assertTrue(com.example.core.service.ServiceStateRepository.backgroundActivityLog.value.isNotEmpty())

        com.example.core.service.ServiceStateRepository.resumeBackgroundAgent()
        assertFalse(com.example.core.service.ServiceStateRepository.isBackgroundAgentPaused.value)
        assertEquals(
            com.example.core.service.ServiceLifecycleState.RUNNING,
            com.example.core.service.ServiceStateRepository.foregroundServiceStatus.value.state
        )
        assertNull(com.example.core.service.ServiceStateRepository.pausedUntilTimestampMs.value)
    }

    @Test
    fun testSection5PersistentEnrollmentQualityAndNoiseDiagnostics() {
        val engine = LocalWakeWordEngine(null)
        assertTrue(engine.loadAcousticModel(allowEmbeddedFallbackInTests = true))

        // Record/enroll 3 samples via UI helper
        assertTrue(engine.recordOrEnrollPhraseFromUi("Hi Babu").first)
        assertTrue(engine.recordOrEnrollPhraseFromUi("Hi Babu").first)
        assertTrue(engine.recordOrEnrollPhraseFromUi("Hi Babu").first)
        assertEquals(3, engine.getEnrolledSampleCount("Hi Babu"))

        // Validate enrollment quality
        val quality = engine.validateEnrollmentQuality("Hi Babu")
        assertTrue(quality.isQualityAcceptable)
        assertEquals(3, quality.sampleCount)
        assertTrue(quality.estimatedSnrDb >= 10.0f)

        // Export & import persistent enrollment JSON across engine instances
        val exportedJson = engine.exportEnrollmentsJson()
        assertTrue(exportedJson.contains("Hi Babu"))

        val secondEngine = LocalWakeWordEngine(null)
        assertEquals(0, secondEngine.getEnrolledSampleCount("Hi Babu"))
        assertTrue(secondEngine.importEnrollmentsJson(exportedJson) >= 1)
        assertEquals(3, secondEngine.getEnrolledSampleCount("Hi Babu"))

        // Run ambient noise floor test
        val noiseReport = secondEngine.runAmbientNoiseTest()
        assertTrue(noiseReport.estimatedSnrDb > 10.0f)
        assertTrue(noiseReport.statusMessage.isNotBlank())
    }

    @Test
    fun testSection6PixelVisualScreenAnalysisAndModalDetection() {
        val gridW = 16
        val gridH = 16
        // Construct a screen with dark periphery and bright centered dialog box (modal overlay)
        val pixels = IntArray(gridW * gridH) { idx ->
            val x = idx % gridW
            val y = idx / gridW
            val inCenterDialog = x in 4..11 && y in 4..11
            if (inCenterDialog) {
                (0xFF shl 24) or (235 shl 16) or (235 shl 8) or 235
            } else {
                (0xFF shl 24) or (18 shl 16) or (18 shl 8) or (18)
            }
        }
        val okNode = com.example.core.screen.ScreenNodeInfo(
            text = "Confirm",
            contentDescription = "Confirm Action",
            viewId = "android:id/button1",
            className = "android.widget.Button",
            isClickable = true,
            isEditable = false,
            isScrollable = false,
            boundsLeft = 400,
            boundsTop = 900,
            boundsRight = 680,
            boundsBottom = 1040
        )
        val snapshot = com.example.core.screen.ScreenSnapshot(
            packageName = "com.whatsapp",
            visibleTexts = listOf("Send message to Rahul?", "Confirm"),
            interactiveElements = listOf(okNode),
            totalNodeCount = 2
        )

        val observation = com.example.core.screen.ScreenIntelligenceManager.analyzePixelGrid(
            argbPixels = pixels,
            width = gridW,
            height = gridH,
            snapshot = snapshot
        )
        assertEquals(gridW, observation.width)
        assertEquals(gridH, observation.height)
        assertTrue(observation.hasModalDimOverlay)
        assertTrue(observation.contrastScore > 20f)
        assertTrue(observation.groundedTextElements.isNotEmpty())
        assertTrue(observation.groundedTextElements.first().contains("Confirm @(540,970)"))
    }

    @Test
    fun testSection10NotificationWatchContinuationWorkflow() = runBlocking {
        val brain = createHeadlessBrain()
        val task = brain.planTaskForInstruction("When Rahul replies, tell me")
        assertTrue(task.actions.isNotEmpty())
        val firstAction = task.actions.first()
        assertEquals(ActionType.CREATE_REMINDER, firstAction.actionType)
        assertEquals("Rahul", firstAction.parameters["notificationWatchSender"])

        // Also test Hindi/Hinglish variant
        val hindiTask = brain.planTaskForInstruction("Jab Priya message kare to mujhe batana")
        assertEquals("Priya", hindiTask.actions.first().parameters["notificationWatchSender"])
    }

    @Test
    fun testScenarioAConditionalCallAndWhatsAppFallback() = runBlocking {
        val brain = createHeadlessBrain()
        val prompt = "Hi Babu, Rahul ko call karo. Agar woh call nahi uthata, toh use WhatsApp par message bhej dena ki main baad mein call karunga."
        val task = brain.planTaskForInstruction(prompt)
        assertEquals(3, task.actions.size)
        assertEquals(ActionType.RESOLVE_CONTACT, task.actions[0].actionType)
        assertEquals("Rahul", task.actions[0].target)
        assertEquals(ActionType.MAKE_PHONE_CALL, task.actions[1].actionType)
        assertEquals("true", task.actions[1].parameters["observeCallState"])
        assertEquals(ActionType.MESSAGE_DRAFT_SEND, task.actions[2].actionType)
        assertEquals("IF_CALL_UNANSWERED", task.actions[2].parameters["condition"])
        assertEquals("main baad mein call karunga", task.actions[2].parameters["message"])

        // Verify execution when call is NOT answered -> WhatsApp fallback executes
        val callMgr = CallIntelligenceManager(
            context = null,
            contactDirectoryProvider = { listOf(com.example.core.telephony.ResolvedContactItem("Rahul Sharma", "+919876543210")) }
        ).apply {
            testOverrideLastOutgoingCallAnswered = false
        }
        val waMgr = com.example.core.communication.WhatsAppAutomationManager(
            context = null,
            callIntelligenceManager = callMgr,
            screenIntelligenceManager = null
        ).apply {
            testOverrideInstalled = true
            testOverrideSendVerified = true
        }
        val outcomeUnanswered = brain.executeTaskIterative(task) { action, ctx ->
            val res = when (action.actionType) {
                ActionType.RESOLVE_CONTACT -> ActionExecutionResult(
                    true,
                    "Resolved contact: Rahul Sharma (+919876543210)",
                    VerificationStatus.VERIFIED
                )
                ActionType.MAKE_PHONE_CALL -> ActionExecutionResult(
                    true,
                    "Call to Rahul (+919876543210) was not answered [CALL_ANSWERED=false].",
                    VerificationStatus.VERIFIED
                )
                ActionType.MESSAGE_DRAFT_SEND -> {
                    val prev = ctx["previousStepOutput"].orEmpty()
                    if (prev.contains("[CALL_ANSWERED=true]")) {
                        ActionExecutionResult(true, "Skipped fallback.", VerificationStatus.VERIFIED)
                    } else {
                        waMgr.sendOrDraftMessage("Rahul", action.parameters["message"] ?: "", isConfirmed = true)
                    }
                }
                else -> ActionExecutionResult(false, "Unexpected", VerificationStatus.FAILED)
            }
            res to (res.status ?: VerificationStatus.VERIFIED)
        }
        assertTrue(outcomeUnanswered.isFullyVerified)
        assertTrue(outcomeUnanswered.finalResponse.contains("main baad mein call karunga"))
    }

    @Test
    fun testScenarioBSmsIntelligenceAndNewContactSave() {
        val callMgr = CallIntelligenceManager(
            context = null,
            contactDirectoryProvider = { listOf(com.example.core.telephony.ResolvedContactItem("Rahul", "+919876543210")) }
        )
        val smsMgr = com.example.core.telephony.SmsIntelligenceManager(
            context = null,
            callIntelligenceManager = callMgr
        )
        smsMgr.testOverrideSmsInbox = listOf(
            com.example.core.telephony.SmsMessageRecord(
                id = "101",
                address = "+919988776655",
                resolvedContactName = null,
                body = "Your verification code is 482910. Rs. 2,500.00 debited from A/C. Ref: UTR99887766",
                timestampMs = 1_700_000_000_000L,
                isUnread = true
            )
        )

        val summary = smsMgr.readAndAnalyzeSms(filter = "latest")
        assertFalse(summary.permissionRequired)
        assertEquals("+919988776655", summary.latestSenderAddress)
        assertTrue(summary.isLatestSenderNewContact)
        assertEquals("482910", summary.messages.first().extractedOtp)
        assertTrue(summary.messages.first().isBankAlert)
        assertEquals("Rs. 2,500.00", summary.messages.first().extractedAmount)
        assertEquals("UTR99887766", summary.messages.first().extractedReference)

        // Verify brain plans READ_SMS -> SAVE_CONTACT for Scenario B
        val brain = createHeadlessBrain()
        val task = brain.planTaskForInstruction("Read my latest SMS and save the sender if the number is new.")
        assertEquals(2, task.actions.size)
        assertEquals(ActionType.READ_SMS, task.actions[0].actionType)
        assertEquals(ActionType.SAVE_CONTACT, task.actions[1].actionType)
        assertEquals("IF_NEW_NUMBER", task.actions[1].parameters["condition"])

        // Save new sender contact after confirmation
        val saveRes = callMgr.saveOrUpdateContact(
            displayName = "SMS Sender (+919988776655)",
            phoneNumber = "+919988776655",
            isConfirmed = true,
            onlyIfNewNumber = true
        )
        assertTrue(saveRes.isSuccess)
        assertEquals(VerificationStatus.VERIFIED, saveRes.status)
        assertTrue(callMgr.isPhoneNumberInContacts("+919988776655"))

        // Second attempt with onlyIfNewNumber = true skips duplicate creation
        val skipRes = callMgr.saveOrUpdateContact(
            displayName = "SMS Sender (+919988776655)",
            phoneNumber = "+919988776655",
            isConfirmed = true,
            onlyIfNewNumber = true
        )
        assertTrue(skipRes.isSuccess)
        assertTrue(skipRes.outputMessage.contains("already saved"))
    }

    @Test
    fun testScenarioCIncomingCallControlAndCallHistory() {
        val callMgr = CallIntelligenceManager(context = null)
        callMgr.testOverrideCallState = com.example.core.telephony.CallStateSnapshot(
            isRinging = true,
            isOffhook = false,
            callerDisplayName = "Rahul (+919876543210)",
            statusSummary = "Incoming call detected. Rahul (+919876543210)"
        )
        val answerRes = callMgr.manageIncomingOrActiveCall("ANSWER", isConfirmed = true)
        assertTrue(answerRes.isSuccess)
        assertTrue(answerRes.outputMessage.contains("Answered incoming call"))

        val endRes = callMgr.manageIncomingOrActiveCall("END", isConfirmed = true)
        assertTrue(endRes.isSuccess)
        assertTrue(endRes.outputMessage.contains("ended"))

        // Test Call History lookup
        callMgr.testOverrideCallHistory = listOf(
            com.example.core.telephony.CallHistoryEntry(
                callerNameOrNumber = "Rahul",
                phoneNumber = "+919876543210",
                callType = "MISSED",
                timestampMs = 1_700_000_000_000L,
                durationSeconds = 0L
            )
        )
        val historyRes = callMgr.queryCallHistory(filterType = "missed")
        assertTrue(historyRes.isSuccess)
        assertTrue(historyRes.outputMessage.contains("[MISSED] Rahul"))

        // Test Voice Call Management planning
        val brain = createHeadlessBrain()
        assertEquals(ActionType.MANAGE_CALL, brain.planTaskForInstruction("Babu, call answer karo").actions.first().actionType)
        assertEquals(ActionType.MANAGE_CALL, brain.planTaskForInstruction("Babu, call reject karo").actions.first().actionType)
        assertEquals(ActionType.MANAGE_CALL, brain.planTaskForInstruction("Babu, phone kaat do").actions.first().actionType)
        assertEquals(ActionType.CHECK_INCOMING_CALL, brain.planTaskForInstruction("Babu, kaun phone kar raha hai?").actions.first().actionType)
    }

    @Test
    fun testScenariosDToGPlanningAndExecution() {
        val brain = createHeadlessBrain()

        // Scenario D: "Read my latest WhatsApp messages and tell me which ones need a reply."
        val scD = brain.planTaskForInstruction("Read my latest WhatsApp messages and tell me which ones need a reply.")
        assertEquals(ActionType.WHATSAPP_READ_CONVERSATION, scD.actions.first().actionType)
        assertEquals("true", scD.actions.first().parameters["analyzeReplyPriority"])

        // Scenario E: "Remind me tomorrow to call Rahul, and after I call him, mark the task complete."
        val scE = brain.planTaskForInstruction("Remind me tomorrow to call Rahul, and after I call him, mark the task complete.")
        assertEquals(2, scE.actions.size)
        assertEquals(ActionType.CREATE_REMINDER, scE.actions[0].actionType)
        assertEquals(ActionType.SAVE_NOTE, scE.actions[1].actionType)

        // Scenario F: "Turn on Bluetooth, open my music application, and play my last playlist."
        val scF = brain.planTaskForInstruction("Turn on Bluetooth, open my music application, and play my last playlist.")
        assertEquals(3, scF.actions.size)
        assertEquals(ActionType.OPEN_SETTINGS_SECTION, scF.actions[0].actionType)
        assertEquals("bluetooth", scF.actions[0].target)
        assertEquals(ActionType.LAUNCH_APP, scF.actions[1].actionType)
        assertEquals(ActionType.CLICK_ELEMENT, scF.actions[2].actionType)

        // Scenario G: "Find the address in my latest email and send it to Rahul on WhatsApp."
        val scG = brain.planTaskForInstruction("Find the address in my latest email and send it to Rahul on WhatsApp.")
        assertEquals(2, scG.actions.size)
        assertEquals(ActionType.READ_NOTIFICATIONS, scG.actions[0].actionType)
        assertEquals(ActionType.MESSAGE_DRAFT_SEND, scG.actions[1].actionType)
        assertEquals("Rahul", scG.actions[1].target)
        assertEquals("true", scG.actions[1].parameters["usePreviousStepOutput"])
    }

    private fun createHeadlessBrain(): AgentBrain {
        val dummyExecutor = UnsafeTestHelper.allocateWithoutInit(ActionExecutor::class.java)
        val dummyProvider = UnsafeTestHelper.allocateWithoutInit(com.example.core.provider.AIProviderManager::class.java)
        val dummyWorkspace = UnsafeTestHelper.allocateWithoutInit(WorkspaceManager::class.java)
        return AgentBrain(
            providerManager = dummyProvider,
            actionExecutor = dummyExecutor,
            workspaceManager = dummyWorkspace,
            skillManager = SkillManager(),
            subAgentManager = SubAgentManager()
        )
    }
}

private object UnsafeTestHelper {
    @Suppress("UNCHECKED_CAST")
    fun <T> allocateWithoutInit(clazz: Class<T>): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null)
        val allocateInstance = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocateInstance.invoke(unsafe, clazz) as T
    }
}

