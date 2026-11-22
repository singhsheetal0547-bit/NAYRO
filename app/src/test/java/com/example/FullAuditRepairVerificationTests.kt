package com.example

import com.example.core.agent.ActionType
import com.example.core.agent.AgentBrain
import com.example.core.agent.ActionExecutor
import com.example.core.agent.ConversationLanguage
import com.example.core.agent.ConversationalIntelligenceEngine
import com.example.core.agent.TaskClassifier
import com.example.core.agent.UserIntentType
import com.example.core.provider.AIProviderManager
import com.example.core.security.EncryptedStorageManager
import com.example.core.service.BackgroundVoicePipelineState
import com.example.core.service.ServiceStateRepository
import com.example.core.skills.SkillManager
import com.example.core.subagent.SubAgentManager
import com.example.core.voice.AudioSessionOwner
import com.example.core.voice.VoiceConfiguration
import com.example.core.voice.VoiceManager
import com.example.core.voice.VoiceState
import com.example.core.voice.engine.LocalWakeWordEngine
import com.example.core.voice.engine.WakeAudioCaptureFactory
import com.example.core.voice.engine.WakeAudioCaptureSession
import com.example.core.voice.engine.WakeEngineState
import com.example.core.workspace.WorkspaceManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Comprehensive End-to-End Audit & Root-Cause Repair Verification Suite for NAYRO.
 * Executes real component pipelines (no class-presence stubs) covering:
 * 1. Background voice routing when Activity moves to background (ON_STOP) and returns (ON_START).
 * 2. MainActivity.onDestroy() preserving active resident NayroForegroundService wake capture.
 * 3. Post-command microphone ownership recovery (completeCommandTurn) even when TTS is OFF.
 * 4. Android 11-16 / OEM silenced all-zero PCM frame detection & honest DEGRADED reporting.
 * 5. Synchronous AudioRecord stop/release during pauseForAsr() before SpeechRecognizer handoff.
 * 6. Exact Hindi, English & mixed Hinglish user prompts + multi-turn language switching.
 * 7. AI provider config & active-provider persistence across simulated app restarts.
 * 8. CRITICAL CALLING RULE enforcement & honest permission revocation pipeline sync.
 */
class FullAuditRepairVerificationTests {

    private class ControllableCaptureSession(
        private val emitAllZeroFrames: Boolean = false
    ) : WakeAudioCaptureSession {
        @Volatile override var isInitialized: Boolean = true
        @Volatile override var isRecording: Boolean = false
        val stopped = AtomicBoolean(false)
        val released = AtomicBoolean(false)
        private val readCount = AtomicInteger(0)

        override fun startRecording() {
            isRecording = true
        }

        override fun read(buffer: ShortArray, offset: Int, size: Int): Int {
            if (!isRecording || stopped.get()) return -1
            readCount.incrementAndGet()
            if (emitAllZeroFrames) {
                for (i in offset until (offset + size).coerceAtMost(buffer.size)) {
                    buffer[i] = 0
                }
            } else {
                // Realistic quiet ambient floor (~140 RMS) so mic is clearly alive and unmuted
                for (i in offset until (offset + size).coerceAtMost(buffer.size)) {
                    buffer[i] = if (i % 2 == 0) 140 else -140
                }
            }
            try {
                Thread.sleep(4L)
            } catch (_: InterruptedException) {
                return -1
            }
            return size
        }

        override fun stop() {
            isRecording = false
            stopped.set(true)
        }

        override fun release() {
            isRecording = false
            released.set(true)
        }
    }

    private fun createCaptureFactory(
        emitAllZeroFrames: Boolean = false,
        onCreated: ((ControllableCaptureSession) -> Unit)? = null
    ): WakeAudioCaptureFactory {
        return object : WakeAudioCaptureFactory {
            override fun getMinBufferSize(sampleRateHz: Int, channelConfig: Int, audioFormat: Int): Int = 2048
            override fun createSession(
                audioSource: Int,
                sampleRateHz: Int,
                channelConfig: Int,
                audioFormat: Int,
                bufferSizeInBytes: Int
            ): WakeAudioCaptureSession {
                return ControllableCaptureSession(emitAllZeroFrames).also { onCreated?.invoke(it) }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> allocateWithoutInit(clazz: Class<T>): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null)
        val allocMethod = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocMethod.invoke(unsafe, clazz) as T
    }

    private fun createAgentBrain(): AgentBrain {
        val storage = EncryptedStorageManager(null)
        val providerManager = AIProviderManager(storage)
        val workspaceManager = allocateWithoutInit(WorkspaceManager::class.java)
        val skillManager = SkillManager()
        val subAgentManager = SubAgentManager()
        val actionExecutor = allocateWithoutInit(ActionExecutor::class.java)
        return AgentBrain(
            providerManager = providerManager,
            actionExecutor = actionExecutor,
            workspaceManager = workspaceManager,
            skillManager = skillManager,
            subAgentManager = subAgentManager
        )
    }

    @Test
    fun testBackgroundVoiceRoutesToForegroundServiceWhenActivityDetachesOnStop() {
        val voiceManager = VoiceManager(context = null)
        val uiReceived = mutableListOf<String>()
        val serviceReceived = mutableListOf<String>()

        voiceManager.attachServiceListener { serviceReceived.add(it) }
        voiceManager.attachUiListener(onRecognized = { uiReceived.add(it) })

        // 1. While MainActivity is in foreground, commands go to UI listener
        voiceManager.resetVoiceCommandDeduplication()
        voiceManager.dispatchRecognizedSpeech("Open WhatsApp")
        assertEquals(listOf("Open WhatsApp"), uiReceived)
        assertTrue(serviceReceived.isEmpty())

        // 2. When MainActivity moves to background (ON_STOP), UI listener detaches -> background service receives wake commands
        voiceManager.detachUiListener()
        voiceManager.resetVoiceCommandDeduplication()
        voiceManager.dispatchRecognizedSpeech("Call Rahul")
        assertEquals(listOf("Call Rahul"), serviceReceived)
        assertEquals(1, uiReceived.size)

        // 3. When MainActivity returns to foreground (ON_START), UI listener reattaches cleanly
        voiceManager.attachUiListener(onRecognized = { uiReceived.add(it) })
        voiceManager.resetVoiceCommandDeduplication()
        voiceManager.dispatchRecognizedSpeech("Read notifications")
        assertEquals(listOf("Open WhatsApp", "Read notifications"), uiReceived)
        assertEquals(1, serviceReceived.size)

        voiceManager.release()
    }

    @Test
    fun testActivityDestroyDoesNotKillActiveBackgroundWakeCapture() {
        val voiceManager = VoiceManager(context = null)
        voiceManager.setMicPermissionCheckerForTesting { true }
        voiceManager.setPhoneCallActiveCheckerForTesting { false }
        assertTrue(voiceManager.wakeWordEngine.loadAcousticModel(allowEmbeddedFallbackInTests = true))

        var sessionRef: ControllableCaptureSession? = null
        voiceManager.wakeWordEngine.setAudioCaptureFactoryForTesting(
            createCaptureFactory(emitAllZeroFrames = false) { sessionRef = it }
        )

        voiceManager.attachServiceListener { /* resident NayroForegroundService active */ }
        voiceManager.attachUiListener(onRecognized = {})

        voiceManager.updateConfiguration(VoiceConfiguration(isWakeWordEnabled = true, isTtsEnabled = false))
        assertEquals(WakeEngineState.LISTENING_FOR_WAKE, voiceManager.wakeWordEngine.engineState.value)
        assertEquals(AudioSessionOwner.WAKE_WORD_ENGINE, voiceManager.audioSessionOwner.value)
        assertTrue(voiceManager.wakeWordEngine.isAudioCaptureActive.value)

        // Simulate MainActivity destroyed while NayroForegroundService remains active
        voiceManager.onActivityDestroyed()

        assertFalse("UI listener must be detached on destroy", voiceManager.hasActiveUiListener())
        assertTrue("Service listener must remain attached", voiceManager.hasActiveServiceListener())
        assertEquals(
            "Background wake-word capture must remain active after Activity destroy",
            WakeEngineState.LISTENING_FOR_WAKE,
            voiceManager.wakeWordEngine.engineState.value
        )
        assertEquals(AudioSessionOwner.WAKE_WORD_ENGINE, voiceManager.audioSessionOwner.value)
        assertFalse("Hardware AudioRecord session must not be released while resident service runs", sessionRef?.released?.get() == true)

        voiceManager.release()
    }

    @Test
    fun testCompleteCommandTurnReturnsToWakeStandbyEvenWhenTtsDisabled() {
        val voiceManager = VoiceManager(context = null)
        voiceManager.setMicPermissionCheckerForTesting { true }
        voiceManager.setPhoneCallActiveCheckerForTesting { false }
        assertTrue(voiceManager.wakeWordEngine.loadAcousticModel(allowEmbeddedFallbackInTests = true))
        voiceManager.wakeWordEngine.setAudioCaptureFactoryForTesting(createCaptureFactory(emitAllZeroFrames = false))

        voiceManager.updateConfiguration(
            VoiceConfiguration(
                isWakeWordEnabled = true,
                isTtsEnabled = false,
                isContinuousConversationEnabled = false
            )
        )
        // Enter THINKING / PROCESSING state as happens after ASR completes
        voiceManager.wakeWordEngine.pauseForAsr()
        voiceManager.markAgentThinking()
        assertEquals(VoiceState.THINKING, voiceManager.voiceState.value)

        // Finish command turn with TTS OFF -> must NOT stay stuck in THINKING!
        voiceManager.completeCommandTurn(
            responseText = "Done.",
            shouldSpeak = false,
            expectFollowUp = false
        )

        assertEquals(VoiceState.STANDBY, voiceManager.voiceState.value)
        assertEquals(AudioSessionOwner.WAKE_WORD_ENGINE, voiceManager.audioSessionOwner.value)
        assertEquals(WakeEngineState.LISTENING_FOR_WAKE, voiceManager.wakeWordEngine.engineState.value)

        voiceManager.release()
    }

    @Test
    fun testSilencedAllZeroBackgroundMicTriggersHonestDegradedState() {
        val engine = LocalWakeWordEngine(context = null)
        engine.setMicPermissionCheckerForTesting { true }
        engine.setSilentMicFrameThresholdForTesting(5)
        assertTrue(engine.loadAcousticModel(allowEmbeddedFallbackInTests = true))

        val errorLatch = CountDownLatch(1)
        var capturedReason: String? = null
        engine.onCaptureError = { reason ->
            capturedReason = reason
            errorLatch.countDown()
        }

        engine.setAudioCaptureFactoryForTesting(createCaptureFactory(emitAllZeroFrames = true))
        assertTrue(engine.startListening())

        assertTrue(
            "Silenced all-zero PCM stream must trigger onCaptureError within 2 seconds",
            errorLatch.await(2, TimeUnit.SECONDS)
        )
        assertTrue(engine.isMicrophoneSilenced.value)
        assertEquals(WakeEngineState.DEGRADED, engine.engineState.value)
        assertFalse(engine.isAudioCaptureActive.value)
        assertTrue(capturedReason.orEmpty().contains("silenced", ignoreCase = true))

        engine.stopListening()
    }

    @Test
    fun testPauseForAsrSynchronouslyStopsAndReleasesAudioRecord() {
        val engine = LocalWakeWordEngine(context = null)
        engine.setMicPermissionCheckerForTesting { true }
        assertTrue(engine.loadAcousticModel(allowEmbeddedFallbackInTests = true))

        var sessionRef: ControllableCaptureSession? = null
        engine.setAudioCaptureFactoryForTesting(createCaptureFactory(emitAllZeroFrames = false) { sessionRef = it })
        assertTrue(engine.startListening())
        assertNotNull(sessionRef)
        assertTrue(sessionRef!!.isRecording)

        engine.pauseForAsr()

        assertTrue("AudioRecord.stop() must complete before pauseForAsr returns", sessionRef!!.stopped.get())
        assertTrue("AudioRecord.release() must complete before pauseForAsr returns", sessionRef!!.released.get())
        assertEquals(WakeEngineState.MICROPHONE_BUSY, engine.engineState.value)
        assertFalse(engine.isAudioCaptureActive.value)
    }

    @Test
    fun testExactHindiAndMixedHinglishUserExamples() {
        val brain = createAgentBrain()

        // Example 1: "आज मुझे Rahul को कॉल करना है और फिर WhatsApp पर message भेजना है।"
        val task1 = brain.planTaskForInstruction("आज मुझे Rahul को कॉल करना है और फिर WhatsApp पर message भेजना है।")
        assertEquals("MULTI_STEP_COMPOUND", task1.resolvedIntent)
        val types1 = task1.actions.map { it.actionType }
        assertTrue("Expected MAKE_PHONE_CALL in $types1", ActionType.MAKE_PHONE_CALL in types1)
        val msgAction = task1.actions.firstOrNull {
            it.actionType == ActionType.MESSAGE_DRAFT_SEND || it.actionType == ActionType.ASK_USER_CONFIRMATION
        }
        assertNotNull("Expected WhatsApp message/confirmation action in $types1", msgAction)
        val callAction = task1.actions.first { it.actionType == ActionType.MAKE_PHONE_CALL }
        assertEquals("Rahul", callAction.parameters["recipient"] ?: callAction.target)
        assertEquals("Rahul", msgAction!!.parameters["recipient"] ?: msgAction.target)

        // Example 2: "Open WhatsApp और Amit को message भेज दो।"
        val task2 = brain.planTaskForInstruction("Open WhatsApp और Amit को message भेज दो।")
        assertEquals("MULTI_STEP_COMPOUND", task2.resolvedIntent)
        val types2 = task2.actions.map { it.actionType }
        assertTrue("Expected LAUNCH_APP in $types2", ActionType.LAUNCH_APP in types2)
        val amitMsgAction = task2.actions.firstOrNull {
            it.actionType == ActionType.MESSAGE_DRAFT_SEND || it.actionType == ActionType.ASK_USER_CONFIRMATION
        }
        assertNotNull("Expected WhatsApp message/confirmation action in $types2", amitMsgAction)
        val launchAction = task2.actions.first { it.actionType == ActionType.LAUNCH_APP }
        assertEquals("WhatsApp", launchAction.target)
        assertEquals("Amit", amitMsgAction!!.parameters["recipient"] ?: amitMsgAction.target)

        // Example 3: "मुझे आज के सारे important काम organize करने हैं।"
        val convEngine = ConversationalIntelligenceEngine()
        val analysis3 = convEngine.analyzeUserMessage("मुझे आज के सारे important काम organize करने हैं।")
        assertEquals(ConversationLanguage.HINDI, analysis3.language)
        assertTrue("Must recognize Hindi+English mixed daily planning intent", analysis3.isDailyPlanningDiscussion)
        assertTrue("Daily planning session should activate", convEngine.isDailyPlanningSessionActive)
        val reply3 = convEngine.buildLocalConversationalReply("Babu", analysis3, "मुझे आज के सारे important काम organize करने हैं।")
        assertNotNull(reply3)
        assertTrue("Reply should be natural Hindi asking about tasks/priorities", reply3!!.contains("काम") && reply3.contains("प्राथमिकता"))
    }

    @Test
    fun testDevanagariSingleIntentsAndMidConversationLanguageSwitching() {
        val callIntent = TaskClassifier.classify("अमित को कॉल करो")
        assertEquals(UserIntentType.PHONE_CALL, callIntent.type)
        assertEquals("अमित", callIntent.recipient)

        val appIntent = TaskClassifier.classify("व्हाट्सएप खोलो")
        assertEquals(UserIntentType.APP_LAUNCH, appIntent.type)
        assertEquals("WhatsApp", appIntent.targetAppOrEntity)

        val conv = ConversationalIntelligenceEngine()
        val t1 = conv.analyzeUserMessage("नमस्ते")
        assertEquals(ConversationLanguage.HINDI, t1.language)
        conv.recordTurn("user", "नमस्ते", t1.language)

        val t2 = conv.analyzeUserMessage("Switch to English and plan my day")
        assertEquals(ConversationLanguage.ENGLISH, t2.language)
        assertTrue(t2.isDailyPlanningDiscussion)
        conv.recordTurn("user", "Switch to English and plan my day", t2.language)

        val t3 = conv.analyzeUserMessage("Aaj mujhe kuch important kaam karne hain: 1. Rahul ko call 2. Report bhejna")
        assertEquals(ConversationLanguage.HINGLISH, t3.language)
        assertEquals(2, t3.activeDailyPlan.size)
        assertEquals(3, conv.getRecentHistory(10).size + 1)
    }

    @Test
    fun testProviderConfigurationPersistsAcrossRestartAndHonorsKeyDeletion() {
        val sharedStorage = EncryptedStorageManager(null)
        val managerBeforeRestart = AIProviderManager(sharedStorage)

        managerBeforeRestart.saveApiKey("grok_custom", "xai-test-key-1234567890")
        managerBeforeRestart.updateProviderModel("grok_custom", "grok-2-latest")
        assertTrue(managerBeforeRestart.selectPreferredProvider("grok_custom"))
        assertEquals("grok_custom", managerBeforeRestart.activeProvider.value?.id)

        // Simulate full app process restart with new AIProviderManager reading same storage
        val managerAfterRestart = AIProviderManager(sharedStorage)
        val activeAfterRestart = managerAfterRestart.activeProvider.value
        assertNotNull("Preferred provider with saved key must remain active after restart", activeAfterRestart)
        assertEquals("grok_custom", activeAfterRestart?.id)
        assertEquals("grok-2-latest", activeAfterRestart?.modelId)
        assertTrue(activeAfterRestart?.isEnabled == true)

        // Delete key -> active provider must clear honestly
        managerAfterRestart.deleteApiKey("grok_custom")
        assertEquals(null, managerAfterRestart.activeProvider.value)
    }

    @Test
    fun testCriticalCallingRuleAndHonestPermissionRevocationSync() {
        val voiceManager = VoiceManager(context = null)
        voiceManager.setPhoneCallActiveCheckerForTesting { true }
        val spokeDuringCall = voiceManager.speak("Hello caller, this is AI speaking")
        assertFalse("CRITICAL CALLING RULE: AI must NEVER speak during an active phone call", spokeDuringCall)
        assertNotNull(voiceManager.lastSpeechBlockedReason.value)

        // Revoke mic permission -> pipeline status must immediately sync to ERROR (never stale READY)
        voiceManager.setPhoneCallActiveCheckerForTesting { false }
        voiceManager.onMicrophonePermissionRevoked()
        assertEquals(VoiceState.ERROR, voiceManager.voiceState.value)
        assertEquals(
            BackgroundVoicePipelineState.ERROR,
            ServiceStateRepository.backgroundVoicePipelineStatus.value.state
        )
        assertFalse(ServiceStateRepository.backgroundVoicePipelineStatus.value.isAudioCaptureVerified)

        voiceManager.release()
    }

    @Test
    fun testConversationContinuityRehydratesPersistedRoomHistoryAfterProcessRestart() {
        val persistedTurns = listOf(
            com.example.data.local.entities.ConversationEntity(
                sender = "user",
                content = "What is artificial intelligence?",
                timestamp = 100L
            ),
            com.example.data.local.entities.ConversationEntity(
                sender = "assistant",
                content = "Artificial intelligence is a field of computer science.",
                timestamp = 200L
            )
        )

        val continuity = com.example.core.memory.ConversationContinuityManager()
        continuity.restoreRecentTurnsFromStorage(persistedTurns)
        val summary = continuity.buildOpenEndedContextSummary()
        assertTrue(summary.contains("artificial intelligence", ignoreCase = true))
        val resolvedFollowUp = continuity.resolveReferencesInPrompt("Find a video about it")
        assertTrue(resolvedFollowUp.contains("artificial intelligence", ignoreCase = true))

        val conversational = com.example.core.agent.ConversationalIntelligenceEngine()
        conversational.restoreHistoryFromStorage(persistedTurns)
        val providerMessages = conversational.buildProviderMessages("Find a video about it")
        assertTrue(providerMessages.any {
            it.role == "user" && it.content.contains("artificial intelligence", ignoreCase = true)
        })
        assertTrue(providerMessages.any {
            it.role == "assistant" && it.content.contains("field of computer science", ignoreCase = true)
        })
    }
}
