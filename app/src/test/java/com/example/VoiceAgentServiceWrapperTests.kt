package com.example

import com.example.core.identity.AssistantIdentityStore
import com.example.core.permission.PermissionManager
import com.example.core.service.BackgroundVoicePipelineState
import com.example.core.service.PendingMicPermissionAction
import com.example.core.service.ServiceLifecycleState
import com.example.core.service.ServiceStateRepository
import com.example.core.service.VoiceAgentServiceWrapper
import com.example.core.voice.BackgroundVoiceController
import com.example.core.voice.VoiceManager
import com.example.core.voice.VoiceState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pure-JVM unit tests verifying [VoiceAgentServiceWrapper]:
 * - Android RECORD_AUDIO microphone permission gating, deferred resumption, and revocation teardown
 * - Bound/started AI Voice Agent running service lifecycle (Start -> Pause -> Resume -> Stop)
 * - End-to-end AI Speech-to-Text capture -> AgentRuntime command execution -> truthful snapshot state
 * - Hindi/English/Hinglish recognition language switching and unavailable-STT handling
 */
class VoiceAgentServiceWrapperTests {

    @Before
    fun setUp() {
        ServiceStateRepository.clearForTesting()
    }

    @After
    fun tearDown() {
        ServiceStateRepository.clearForTesting()
    }

    private fun buildTestHarness(
        initialMicGranted: Boolean = false,
        sttAvailable: Boolean = true,
        aiExecutor: (suspend (String) -> String)? = null
    ): TestHarness {
        var micGranted = initialMicGranted
        val identityStore = AssistantIdentityStore(context = null)
        val voiceManager = VoiceManager(context = null).apply {
            bindIdentityStore(identityStore)
            setMicPermissionCheckerForTesting { micGranted }
        }

        var serviceStartCalls = 0
        var serviceStopCalls = 0
        var servicePauseCalls = 0
        var serviceResumeCalls = 0
        var sttStartCalls = 0
        var sttStopCalls = 0

        val wrapper = VoiceAgentServiceWrapper(
            appContext = null,
            permissionManager = null,
            voiceManager = voiceManager,
            backgroundVoiceController = null,
            identityStore = identityStore,
            permissionCheckerOverride = { micGranted },
            speechAvailabilityOverride = { sttAvailable },
            serviceStartDelegate = {
                serviceStartCalls++
                ServiceStateRepository.updateForegroundStatus(ServiceLifecycleState.RUNNING)
            },
            serviceStopDelegate = {
                serviceStopCalls++
                ServiceStateRepository.updateForegroundStatus(ServiceLifecycleState.STOPPED)
            },
            servicePauseDelegate = { _, durationMinutes, reason ->
                servicePauseCalls++
                ServiceStateRepository.pauseBackgroundAgent(durationMinutes, reason)
            },
            serviceResumeDelegate = {
                serviceResumeCalls++
                ServiceStateRepository.resumeBackgroundAgent()
            },
            sttStartDelegate = {
                sttStartCalls++
                true
            },
            sttStopDelegate = {
                sttStopCalls++
                voiceManager.stopListening()
            },
            aiCommandExecutorOverride = aiExecutor
        )

        return TestHarness(
            wrapper = wrapper,
            voiceManager = voiceManager,
            setMicGranted = { granted -> micGranted = granted },
            getServiceStartCalls = { serviceStartCalls },
            getServiceStopCalls = { serviceStopCalls },
            getServicePauseCalls = { servicePauseCalls },
            getServiceResumeCalls = { serviceResumeCalls },
            getSttStartCalls = { sttStartCalls },
            getSttStopCalls = { sttStopCalls }
        )
    }

    private data class TestHarness(
        val wrapper: VoiceAgentServiceWrapper,
        val voiceManager: VoiceManager,
        val setMicGranted: (Boolean) -> Unit,
        val getServiceStartCalls: () -> Int,
        val getServiceStopCalls: () -> Int,
        val getServicePauseCalls: () -> Int,
        val getServiceResumeCalls: () -> Int,
        val getSttStartCalls: () -> Int,
        val getSttStopCalls: () -> Int
    )

    @Test
    fun testVoiceCaptureRequestsMicPermissionAndAutoResumesOnGrant() {
        val h = buildTestHarness(initialMicGranted = false, sttAvailable = true)
        var permissionPromptCount = 0

        val startedBeforeGrant = h.wrapper.requestVoiceCapture(
            onRequestMicrophonePermission = { permissionPromptCount++ }
        )

        assertFalse(startedBeforeGrant)
        assertEquals(1, permissionPromptCount)
        assertEquals(0, h.getSttStartCalls())
        assertEquals(PendingMicPermissionAction.START_VOICE_CAPTURE, h.wrapper.snapshot.value.pendingPermissionAction)
        assertFalse(h.wrapper.snapshot.value.hasMicrophonePermission)

        // Simulate user granting RECORD_AUDIO in Android runtime permission dialog
        h.setMicGranted(true)
        h.wrapper.onMicrophonePermissionResult(granted = true)

        assertEquals(1, h.getSttStartCalls())
        assertEquals(PendingMicPermissionAction.NONE, h.wrapper.snapshot.value.pendingPermissionAction)
        assertTrue(h.wrapper.snapshot.value.hasMicrophonePermission)
        assertNull(h.wrapper.snapshot.value.errorMessage)
    }

    @Test
    fun testAgentServiceStartRequiresMicPermissionAndResumesWithAutoCaptureOnGrant() {
        val h = buildTestHarness(initialMicGranted = false, sttAvailable = true)
        var permissionPromptCount = 0

        val started = h.wrapper.requestStartAgentService(
            autoCaptureSpeech = true,
            onRequestMicrophonePermission = { permissionPromptCount++ }
        )

        assertFalse(started)
        assertEquals(1, permissionPromptCount)
        assertEquals(0, h.getServiceStartCalls())
        assertEquals(0, h.getSttStartCalls())
        assertEquals(ServiceLifecycleState.PERMISSION_DENIED, ServiceStateRepository.foregroundServiceStatus.value.state)
        assertEquals(PendingMicPermissionAction.START_SERVICE_AND_CAPTURE, h.wrapper.snapshot.value.pendingPermissionAction)

        // Grant permission -> wrapper automatically starts the running agent service and opens STT capture
        h.setMicGranted(true)
        h.wrapper.onMicrophonePermissionResult(granted = true)

        assertEquals(1, h.getServiceStartCalls())
        assertEquals(1, h.getSttStartCalls())
        assertEquals(ServiceLifecycleState.RUNNING, h.wrapper.snapshot.value.serviceLifecycleState)
        assertTrue(h.wrapper.snapshot.value.isServiceRunning)
        assertEquals(PendingMicPermissionAction.NONE, h.wrapper.snapshot.value.pendingPermissionAction)
    }

    @Test
    fun testMicPermissionDenialOrRevocationTearsDownRunningServiceHonestly() {
        val h = buildTestHarness(initialMicGranted = true, sttAvailable = true)

        assertTrue(h.wrapper.requestStartAgentService())
        assertEquals(ServiceLifecycleState.RUNNING, h.wrapper.snapshot.value.serviceLifecycleState)

        // Simulate RECORD_AUDIO permission revoked by user
        h.setMicGranted(false)
        h.wrapper.onMicrophonePermissionResult(granted = false)

        assertEquals(1, h.getServiceStopCalls())
        assertFalse(h.wrapper.snapshot.value.hasMicrophonePermission)
        assertEquals(ServiceLifecycleState.STOPPED, h.wrapper.snapshot.value.serviceLifecycleState)
        assertTrue(h.wrapper.snapshot.value.errorMessage?.contains("denied", ignoreCase = true) == true)
    }

    @Test
    fun testAgentRunningServiceLifecycleStartPauseResumeStop() {
        val h = buildTestHarness(initialMicGranted = true, sttAvailable = true)

        assertEquals(ServiceLifecycleState.STOPPED, h.wrapper.snapshot.value.serviceLifecycleState)
        assertFalse(h.wrapper.snapshot.value.isServiceRunning)

        // Start
        assertTrue(h.wrapper.requestStartAgentService())
        assertEquals(ServiceLifecycleState.RUNNING, h.wrapper.snapshot.value.serviceLifecycleState)
        assertTrue(h.wrapper.snapshot.value.isServiceRunning)
        assertTrue(h.wrapper.snapshot.value.isServiceActiveAndUnpaused)

        // Pause
        h.wrapper.pauseAgentService(durationMinutes = 15, reason = "User meeting")
        assertEquals(1, h.getServicePauseCalls())
        assertTrue(h.wrapper.snapshot.value.isPaused)
        assertEquals(ServiceLifecycleState.PAUSED, h.wrapper.snapshot.value.serviceLifecycleState)
        assertTrue(h.wrapper.snapshot.value.isServiceRunning)
        assertFalse(h.wrapper.snapshot.value.isServiceActiveAndUnpaused)

        // Resume
        assertTrue(h.wrapper.resumeAgentService())
        assertEquals(1, h.getServiceResumeCalls())
        assertFalse(h.wrapper.snapshot.value.isPaused)
        assertEquals(ServiceLifecycleState.RUNNING, h.wrapper.snapshot.value.serviceLifecycleState)
        assertTrue(h.wrapper.snapshot.value.isServiceActiveAndUnpaused)

        // Stop
        h.wrapper.stopAgentService()
        assertEquals(1, h.getServiceStopCalls())
        assertEquals(1, h.getSttStopCalls())
        assertEquals(ServiceLifecycleState.STOPPED, h.wrapper.snapshot.value.serviceLifecycleState)
        assertFalse(h.wrapper.snapshot.value.isServiceRunning)
    }

    @Test
    fun testEndToEndSpeechToTextCommandExecutionThroughServiceWrapper() = runBlocking {
        val executedPrompts = mutableListOf<String>()
        val h = buildTestHarness(
            initialMicGranted = true,
            sttAvailable = true,
            aiExecutor = { prompt ->
                executedPrompts.add(prompt)
                "Settings opened successfully."
            }
        )

        assertTrue(h.wrapper.requestStartAgentService())
        h.wrapper.setRecognitionLanguage("hi-IN")
        assertEquals("hi-IN", h.wrapper.snapshot.value.selectedLanguageTag)

        // Simulate Android SpeechRecognizer streaming partial + final transcript
        val sttResult = h.voiceManager.speechRecognitionProvider.simulateSpeechRecognitionLifecycleForTest(
            partialHypotheses = listOf("Hi Babu", "Hi Babu open Settings"),
            finalTranscript = "Hi Babu open Settings",
            languageTag = "hi-IN",
            confidence = 0.95f
        )

        val reply = h.wrapper.executeRecognizedSpeechCommand(
            rawTranscript = sttResult.primaryText,
            fromVoice = true
        )

        assertEquals(listOf("open Settings"), executedPrompts)
        assertEquals("Settings opened successfully.", reply)
        assertEquals("Settings opened successfully.", h.wrapper.snapshot.value.lastAgentReply)
        assertEquals(ServiceLifecycleState.RUNNING, h.wrapper.snapshot.value.serviceLifecycleState)
    }

    @Test
    fun testSpeechRecognitionUnavailableReportsHonestErrorWithoutStartingCapture() {
        val h = buildTestHarness(initialMicGranted = true, sttAvailable = false)

        val started = h.wrapper.requestVoiceCapture()

        assertFalse(started)
        assertEquals(0, h.getSttStartCalls())
        assertFalse(h.wrapper.snapshot.value.isSpeechRecognitionAvailable)
        assertNotNull(h.wrapper.snapshot.value.errorMessage)
        assertTrue(
            h.wrapper.snapshot.value.errorMessage!!.contains("Speech Recognition", ignoreCase = true)
        )
    }
}
