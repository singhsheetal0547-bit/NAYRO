package com.example

import com.example.core.agent.AgentRuntime
import com.example.core.agent.AgentState
import com.example.core.agent.AgentTask
import com.example.core.agent.TaskStateManager
import com.example.core.floating.FloatingAssistantPreferences
import com.example.core.identity.AssistantIdentityStore
import com.example.core.memory.ConversationContinuityManager
import com.example.core.service.DeviceOperatingStateManager
import com.example.core.service.ServiceLifecycleState
import com.example.core.service.ServiceStateRepository
import com.example.core.voice.AndroidSpeechRecognitionProvider
import com.example.core.voice.BackgroundVoiceController
import com.example.core.voice.VoiceManager
import com.example.core.voice.VoiceState
import com.example.core.voice.engine.LocalWakeWordEngine
import com.example.core.voice.engine.WakeEngineState
import com.example.core.voice.engine.WakePhraseSupportStatus
import com.example.ui.components.VisualizerVisualState
import com.example.ui.components.computeVisualizerBarHeights
import com.example.ui.components.resolveVisualizerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * Comprehensive verification suite covering:
 * - Custom wake word identity, 1.2s silence-trimmed DTW enrollment, false-activation rejection & calibration diagnostics
 * - Voice recognition error mapping, continuous multi-turn follow-up sessions & isolated floating/UI/service listeners
 * - Background Voice Listening ON/OFF controller, immediate stop/release, permission fail-closed & Realme/ColorOS OEM guidance
 * - Floating UI inset-safe clamping, edge snapping & position persistence
 * - Animated Audio-Reactive Visualizer state resolution (all 9 states) & real RMS telemetry proportionality
 * - Open-ended autonomous agent multi-turn topic & search continuity
 */
class AutonomousAgentPhase3Tests {

    private fun generateVoicedSpeechPcm(
        durationSamples: Int = 4800,
        f0Hz: Double = 185.0,
        f1Hz: Double = 680.0,
        amplitude: Double = 5500.0,
        leadingSilenceSamples: Int = 0,
        trailingSilenceSamples: Int = 0
    ): ShortArray {
        val total = leadingSilenceSamples + durationSamples + trailingSilenceSamples
        return ShortArray(total) { i ->
            if (i < leadingSilenceSamples || i >= leadingSilenceSamples + durationSamples) {
                0
            } else {
                val t = (i - leadingSilenceSamples).toDouble() / 16000.0
                val env = 0.65 + 0.35 * sin(2.0 * PI * 3.5 * t)
                val wave = 0.62 * sin(2.0 * PI * f0Hz * t) +
                    0.26 * sin(2.0 * PI * f1Hz * t) +
                    0.12 * sin(2.0 * PI * (f1Hz * 1.9) * t)
                (amplitude * env * wave).toInt().coerceIn(-32000, 32000).toShort()
            }
        }
    }

    private fun createHeadlessRuntimeForIdentity(identityStore: AssistantIdentityStore): AgentRuntime {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null)
        val allocateInstance = unsafeClass.getMethod("allocateInstance", Class::class.java)
        val instance = allocateInstance.invoke(unsafe, AgentRuntime::class.java) as AgentRuntime

        fun setField(name: String, value: Any?) {
            val f = AgentRuntime::class.java.getDeclaredField(name)
            f.isAccessible = true
            f.set(instance, value)
        }
        setField("stateManager", TaskStateManager())
        setField("_currentState", MutableStateFlow<AgentState>(AgentState.Idle))
        setField("_currentTask", MutableStateFlow<AgentTask?>(null))
        setField("completedSideEffectSignatures", java.util.Collections.synchronizedSet(mutableSetOf<String>()))
        setField("uncertainExternalActionSignatures", java.util.Collections.synchronizedSet(mutableSetOf<String>()))
        setField("assistantName", identityStore.getDisplayName())
        setField("continuityManager", ConversationContinuityManager())
        setField("conversationalEngine", com.example.core.agent.ConversationalIntelligenceEngine())
        setField("operatingStateManager", DeviceOperatingStateManager(null))
        return instance
    }

    // ------------------------------------------------------------------------
    // 1. WAKE WORD ENGINE: CUSTOM IDENTITY, SILENCE TRIM, DTW ENROLLMENT & CALIBRATION
    // ------------------------------------------------------------------------

    @Test
    fun testCustomWakePhraseEnrollmentSilenceTrimmingAndCalibrationDiagnostics() {
        val engine = LocalWakeWordEngine(context = null)
        engine.loadAcousticModel(allowEmbeddedFallbackInTests = true)

        val customPhrase = "Hi Jarvis"
        assertEquals(
            WakePhraseSupportStatus.REQUIRES_VOICE_ENROLLMENT,
            engine.getWakePhraseSupportStatus(customPhrase)
        )

        // Build a voiced speech sample surrounded by leading & trailing silence
        val paddedPcm = generateVoicedSpeechPcm(
            durationSamples = 4800,
            f0Hz = 190.0,
            f1Hz = 720.0,
            leadingSilenceSamples = 1600,
            trailingSilenceSamples = 1600
        )
        val trimmed = engine.trimSilenceEdges(paddedPcm)
        assertTrue(
            "Silence trimming must shrink leading/trailing quiet edges while keeping voiced core",
            trimmed.size < paddedPcm.size && trimmed.size >= 3200
        )

        // Enroll two real-shaped samples for "Hi Jarvis"
        val (ok1, msg1) = engine.recordOrEnrollPhraseFromUi(customPhrase, recordedPcmOverride = paddedPcm)
        val (ok2, _) = engine.recordOrEnrollPhraseFromUi(customPhrase, recordedPcmOverride = trimmed)
        assertTrue("First enrollment must succeed: $msg1", ok1)
        assertTrue("Second enrollment must succeed", ok2)
        assertEquals(2, engine.getEnrolledSampleCount(customPhrase))
        assertEquals(
            WakePhraseSupportStatus.READY_USER_ENROLLED_DTW,
            engine.getWakePhraseSupportStatus(customPhrase)
        )

        // Calibration test with matching acoustic signature must pass with high confidence & low DTW
        val calPass = engine.testWakePhraseCalibration(trimmed, expectedPhrase = customPhrase)
        assertTrue("Expected calibration match: ${calPass.diagnosticMessage}", calPass.matched)
        assertEquals(customPhrase, calPass.detectedPhrase)
        assertTrue(calPass.confidence >= 0.72f)
        assertTrue(calPass.dtwDistance < 3.5f)
        assertTrue(calPass.snrDb > 0.0)

        // Silence / noise must be rejected cleanly by calibration gate
        val silentPcm = ShortArray(3200) { 12 }
        val calReject = engine.testWakePhraseCalibration(silentPcm, expectedPhrase = customPhrase)
        assertFalse("Silent audio must never pass calibration", calReject.matched)
        assertNull(calReject.detectedPhrase)
    }

    @Test
    fun testWakeWordExitCommandDoesNotHardcodeBabu() {
        val store = AssistantIdentityStore(context = null)
        store.updateDisplayName("Jarvis")
        store.setWakePhrases(listOf("Hi Jarvis", "Hey Jarvis", "Hi Nayro"))

        val engine = LocalWakeWordEngine(context = null)
        engine.setConfiguredWakePhrases(store.getWakePhrases())

        assertTrue(engine.isVoiceExitCommand("Goodbye Jarvis", assistantName = "Jarvis"))
        assertTrue(engine.isVoiceExitCommand("stop listening", assistantName = "Jarvis"))
        assertFalse(
            "Should not match hardcoded Babu exit phrase when configured assistant is Jarvis",
            engine.isVoiceExitCommand("bye babu", assistantName = "Jarvis")
        )
    }

    // ------------------------------------------------------------------------
    // 2. VOICE PIPELINE: ERROR MAPPING, ISOLATED LISTENERS & CONTINUOUS SESSION
    // ------------------------------------------------------------------------

    @Test
    fun testSpeechRecognitionErrorCodesMapToActionableMessages() {
        val provider = AndroidSpeechRecognitionProvider(context = null)
        val noMatch = provider.mapSpeechRecognizerError(7) // ERROR_NO_MATCH
        val timeout = provider.mapSpeechRecognizerError(6) // ERROR_SPEECH_TIMEOUT
        val audio = provider.mapSpeechRecognizerError(3) // ERROR_AUDIO
        val busy = provider.mapSpeechRecognizerError(8) // ERROR_RECOGNIZER_BUSY
        val network = provider.mapSpeechRecognizerError(2) // ERROR_NETWORK

        assertTrue(noMatch.isNotBlank())
        assertTrue(timeout.isNotBlank())
        assertTrue(audio.contains("audio", ignoreCase = true) || audio.contains("mic", ignoreCase = true))
        assertTrue(busy.contains("busy", ignoreCase = true) || busy.contains("wait", ignoreCase = true))
        assertTrue(network.contains("network", ignoreCase = true) || network.contains("internet", ignoreCase = true))
    }

    @Test
    fun testFloatingListenerIsIsolatedFromMainUiListenerAndFollowUpSessionEndsCleanly() {
        val store = AssistantIdentityStore(context = null)
        store.updateDisplayName("Jarvis")

        val voiceManager = VoiceManager(context = null)
        voiceManager.bindIdentityStore(store)

        val uiReceived = mutableListOf<String>()
        val floatingReceived = mutableListOf<String>()

        voiceManager.attachUiListener(onRecognized = { uiReceived.add(it) })
        voiceManager.attachFloatingListener { floatingReceived.add(it) }

        // Floating listener takes precedence while floating panel is expanded without erasing UI listener
        voiceManager.dispatchRecognizedSpeech("Hi Jarvis, what is quantum computing?")
        assertEquals(listOf("what is quantum computing?"), floatingReceived)
        assertTrue("UI listener should not double-execute while floating listener handles capture", uiReceived.isEmpty())

        // Detaching floating listener restores UI listener cleanly
        voiceManager.detachFloatingListener()
        voiceManager.dispatchRecognizedSpeech("Explain it with an example")
        assertEquals(listOf("Explain it with an example"), uiReceived)

        // Ending conversation session exits cleanly & resets follow-up state
        assertTrue(voiceManager.isConversationExitPhrase("goodbye"))
        assertTrue(voiceManager.isConversationExitPhrase("bas karo"))
        voiceManager.endConversationSession(returnToStandby = false)
        assertFalse(voiceManager.isFollowUpSessionActive.value)
        assertEquals(VoiceState.DISABLED, voiceManager.voiceState.value)
    }

    // ------------------------------------------------------------------------
    // 3. BACKGROUND VOICE LISTENING ON/OFF CONTROLLER & OEM GUIDANCE
    // ------------------------------------------------------------------------

    @Test
    fun testBackgroundVoiceControllerFailClosedWithoutMicAndCleanStopWhenOff() {
        val store = AssistantIdentityStore(context = null)
        var micGranted = false
        val voiceManager = VoiceManager(context = null)
        voiceManager.bindIdentityStore(store)

        val controller = BackgroundVoiceController(
            context = null,
            identityStore = store,
            permissionManager = null,
            voiceManager = voiceManager,
            micPermissionCheckOverride = { micGranted }
        )

        // Without RECORD_AUDIO permission, enabling must fail closed honestly
        val (enabledWithoutPerm, denyMsg) = controller.enableBackgroundListening(activityContext = null)
        assertFalse(enabledWithoutPerm)
        assertTrue(denyMsg.contains("Microphone permission", ignoreCase = true))
        assertFalse(controller.state.value.isEnabled)

        // Grant RECORD_AUDIO, then enable & disable
        micGranted = true
        val (enabledWithPerm, onMsg) = controller.enableBackgroundListening(activityContext = null)
        assertTrue("Should enable when RECORD_AUDIO is granted: $onMsg", enabledWithPerm)
        assertTrue(controller.state.value.isEnabled)
        assertTrue(store.getCurrentProfile().isWakeWordEnabled)
        assertEquals(ServiceLifecycleState.RUNNING, ServiceStateRepository.foregroundServiceStatus.value.state)

        // Disabling must immediately turn OFF wake word, release engine, and stop foreground service
        val (disabledOk, offMsg) = controller.disableBackgroundListening(activityContext = null)
        assertTrue(disabledOk)
        assertTrue(offMsg.contains("OFF", ignoreCase = true))
        assertFalse(controller.state.value.isEnabled)
        assertFalse(store.getCurrentProfile().isWakeWordEnabled)
        assertEquals(WakeEngineState.STOPPED, voiceManager.wakeWordEngine.engineState.value)
        assertEquals(ServiceLifecycleState.STOPPED, ServiceStateRepository.foregroundServiceStatus.value.state)
        assertEquals(0f, voiceManager.standbyAudioLevel.value, 0.0001f)
    }

    @Test
    fun testBackgroundVoiceControllerRevocationAndRealmeOemGuidance() {
        val store = AssistantIdentityStore(context = null)
        var micGranted = true
        val voiceManager = VoiceManager(context = null)
        val controller = BackgroundVoiceController(
            context = null,
            identityStore = store,
            permissionManager = null,
            voiceManager = voiceManager,
            micPermissionCheckOverride = { micGranted }
        )

        controller.enableBackgroundListening(null)
        assertTrue(controller.state.value.isEnabled)

        // Revoking microphone permission must immediately shut down background listening
        micGranted = false
        controller.onMicrophonePermissionRevoked(null)
        assertFalse(controller.state.value.isEnabled)
        assertTrue(controller.state.value.statusSummary.contains("revoked", ignoreCase = true))

        // Verify honest OEM guidance for Realme / ColorOS + Android 14 (API 34) & Android 11 (API 30)
        val realmeApi34 = BackgroundVoiceController.buildOemBackgroundGuidance(
            manufacturer = "realme",
            sdkInt = 34,
            isBatteryOptimizationIgnored = false
        )
        assertTrue(realmeApi34.contains("Realme", ignoreCase = true))
        assertTrue(realmeApi34.contains("ColorOS", ignoreCase = true))
        assertTrue(realmeApi34.contains("Android 14+", ignoreCase = true))

        val genericApi30 = BackgroundVoiceController.buildOemBackgroundGuidance(
            manufacturer = "Google",
            sdkInt = 30,
            isBatteryOptimizationIgnored = true
        )
        assertTrue(genericApi30.contains("Unrestricted", ignoreCase = true))
        assertTrue(genericApi30.contains("Android 11", ignoreCase = true))
    }

    // ------------------------------------------------------------------------
    // 4. FLOATING UI: INSET-SAFE CLAMPING, EDGE SNAPPING & PERSISTENCE
    // ------------------------------------------------------------------------

    @Test
    fun testFloatingBubbleInsetSafeClampingEdgeSnappingAndPersistence() {
        // Screen 1080x2400, bubble 168px, margin 24px, topInset 96px, bottomInset 128px
        val (clampedX, clampedY) = FloatingAssistantPreferences.clampToSafeBounds(
            x = -500,
            y = 9999,
            screenWidth = 1080,
            screenHeight = 2400,
            bubbleSizePx = 168,
            edgeMarginPx = 24,
            topInsetPx = 96,
            bottomInsetPx = 128
        )
        assertEquals(24, clampedX)
        assertEquals(2400 - 168 - 128 - 24, clampedY)

        // Top inset clamp prevents overlap with status bar / display cutout
        val (_, topClampedY) = FloatingAssistantPreferences.clampToSafeBounds(
            x = 200,
            y = 10,
            screenWidth = 1080,
            screenHeight = 2400,
            bubbleSizePx = 168,
            edgeMarginPx = 24,
            topInsetPx = 96,
            bottomInsetPx = 128
        )
        assertEquals(96 + 24, topClampedY)

        // Snap left when released on left half; snap right when released on right half
        val (leftX, _) = FloatingAssistantPreferences.snapToNearestHorizontalEdge(
            x = 200,
            y = 500,
            screenWidth = 1080,
            screenHeight = 2400,
            bubbleSizePx = 168,
            edgeMarginPx = 24
        )
        assertEquals(24, leftX)

        val (rightX, _) = FloatingAssistantPreferences.snapToNearestHorizontalEdge(
            x = 700,
            y = 500,
            screenWidth = 1080,
            screenHeight = 2400,
            bubbleSizePx = 168,
            edgeMarginPx = 24
        )
        assertEquals(1080 - 168 - 24, rightX)

        // Position & enabled persistence in headless preferences
        val prefs = FloatingAssistantPreferences(context = null)
        prefs.savePosition(rightX, 640)
        prefs.setFloatingEnabled(true)
        assertEquals(Pair(rightX, 640), prefs.getPosition())
        assertTrue(prefs.isFloatingEnabled())
    }

    // ------------------------------------------------------------------------
    // 5. ANIMATED AUDIO-REACTIVE VISUALIZER: ALL 9 STATES & REAL RMS TELEMETRY
    // ------------------------------------------------------------------------

    @Test
    fun testAudioVisualizerResolvesAllNineStatesAndReactsStrictlyToRealRms() {
        assertEquals(VisualizerVisualState.IDLE, resolveVisualizerState(VoiceState.IDLE, AgentState.Idle))
        assertEquals(VisualizerVisualState.STANDBY, resolveVisualizerState(VoiceState.STANDBY, AgentState.Idle))
        assertEquals(VisualizerVisualState.WAKE_DETECTED, resolveVisualizerState(VoiceState.WAKE_DETECTED, AgentState.Idle))
        assertEquals(VisualizerVisualState.LISTENING, resolveVisualizerState(VoiceState.LISTENING, AgentState.Idle))
        assertEquals(VisualizerVisualState.TRANSCRIBING, resolveVisualizerState(VoiceState.TRANSCRIBING, AgentState.Idle))
        assertEquals(VisualizerVisualState.THINKING, resolveVisualizerState(VoiceState.IDLE, AgentState.Thinking))
        assertEquals(VisualizerVisualState.EXECUTING, resolveVisualizerState(VoiceState.IDLE, AgentState.ExecutingAction("search")))
        assertEquals(VisualizerVisualState.SPEAKING, resolveVisualizerState(VoiceState.SPEAKING, AgentState.Speaking("Hello")))
        assertEquals(VisualizerVisualState.ERROR, resolveVisualizerState(VoiceState.ERROR, AgentState.Idle))

        // Real RMS telemetry check: zero audio level produces flat quiet baseline (zero fake spikes)
        val quietBars = computeVisualizerBarHeights(
            state = VisualizerVisualState.LISTENING,
            realAudioLevel = 0f,
            phase = 0.5f,
            barCount = 16
        )
        val loudBars = computeVisualizerBarHeights(
            state = VisualizerVisualState.LISTENING,
            realAudioLevel = 0.9f,
            phase = 0.5f,
            barCount = 16
        )
        assertEquals(16, quietBars.size)
        assertEquals(16, loudBars.size)
        assertTrue("Quiet listening must stay at baseline <= 0.15f", quietBars.all { it <= 0.15f })
        assertTrue("Loud audio must produce significantly taller center bars", loudBars[8] > quietBars[8] + 0.45f)

        // All 9 states must produce finite bounded heights in 0.05..1.0
        for (state in VisualizerVisualState.values()) {
            val heights = computeVisualizerBarHeights(state, realAudioLevel = 0.5f, phase = 0.35f, barCount = 12)
            assertEquals(12, heights.size)
            assertTrue("All bars finite & bounded for $state", heights.all { it.isFinite() && it in 0.05f..1.0f })
        }
    }

    // ------------------------------------------------------------------------
    // 6. OPEN-ENDED MULTI-TURN CONVERSATION & DYNAMIC IDENTITY CONTINUITY
    // ------------------------------------------------------------------------

    @Test
    fun testOpenEndedMultiTurnConversationContinuityAndDynamicWakeGreeting() = runBlocking {
        val continuity = ConversationContinuityManager()

        // Turn 1: General open-ended question
        continuity.recordConversationTurn("user", "What is artificial intelligence?")
        continuity.recordConversationTurn(
            "assistant",
            "Artificial intelligence is the simulation of human intelligence processes by computer systems."
        )

        // Turn 2: Follow-up referring to previous topic
        val turn2Context = continuity.buildOpenEndedContextSummary()
        assertTrue(turn2Context.contains("artificial intelligence", ignoreCase = true))
        assertEquals(2, continuity.getRecentTurns().size)

        // Turn 3: Action follow-up "Now search for a good video about it"
        val resolvedVideoSearch = continuity.resolveReferencesInPrompt("Now search for a good video about it")
        assertTrue(
            "Expected YouTube search resolved with topic 'artificial intelligence', got: $resolvedVideoSearch",
            resolvedVideoSearch.contains("YouTube", ignoreCase = true) &&
                resolvedVideoSearch.contains("artificial intelligence", ignoreCase = true)
        )

        // Turn 4: Ordinal screen selection "Open the second result"
        val resolvedSecondResult = continuity.resolveReferencesInPrompt("Open the second result")
        assertTrue(
            "Expected ordinal result resolution for index 2, got: $resolvedSecondResult",
            resolvedSecondResult.contains("2") &&
                resolvedSecondResult.contains("artificial intelligence", ignoreCase = true)
        )

        // Verify AgentRuntime responds to dynamic custom wake phrase ("Hi Jarvis") without hardcoded Babu
        val store = AssistantIdentityStore(context = null)
        store.updateDisplayName("Jarvis")
        val runtime = createHeadlessRuntimeForIdentity(store)
        val greeting = runtime.executeUserInstruction("Hi Jarvis")
        assertNotNull(greeting)
        assertTrue("Greeting should use dynamic assistant name 'Jarvis', got: $greeting", greeting.contains("Jarvis", ignoreCase = true))
    }
}
