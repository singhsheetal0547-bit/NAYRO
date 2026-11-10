package com.example

import com.example.core.agent.ActionExecutionResult
import com.example.core.agent.ActionExecutor
import com.example.core.agent.ActionType
import com.example.core.agent.ActionVerifier
import com.example.core.agent.AgentAction
import com.example.core.agent.AgentBrain
import com.example.core.agent.AgentTask
import com.example.core.agent.TaskState
import com.example.core.agent.TaskStateManager
import com.example.core.agent.VerificationResult
import com.example.core.agent.VerificationStatus
import com.example.core.agent.tools.ToolRegistryManager
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
