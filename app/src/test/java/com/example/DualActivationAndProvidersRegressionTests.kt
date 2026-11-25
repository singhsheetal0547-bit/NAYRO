package com.example

import com.example.core.agent.ActionType
import com.example.core.agent.ActionVerifier
import com.example.core.agent.AgentAction
import com.example.core.agent.RiskLevel
import com.example.core.agent.TaskPlanner
import com.example.core.agent.VerificationStatus
import com.example.core.identity.AssistantIdentityStore
import com.example.core.provider.AIProviderManager
import com.example.core.provider.model.AIResult
import com.example.core.security.EncryptedStorageManager
import com.example.core.voice.AudioSessionOwner
import com.example.core.voice.BackgroundVoiceController
import com.example.core.voice.DeepgramSpeechRecognitionProvider
import com.example.core.voice.SpeechRecognitionEngineType
import com.example.core.voice.VoiceManager
import com.example.core.voice.VoiceProviderRouter
import com.example.core.voice.VoiceState
import com.example.core.voice.assistant.ScreenOffWakeCapabilityManager
import com.example.core.voice.assistant.ScreenOffWakeMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Regression suite for:
 * 1. Dual Voice Activation (Mode A Floating Auto-Mic + Mode B Screen-Off VoiceInteractionService)
 * 2. Honest Hardware DSP vs Local Fallback capability reporting across Android 11-14+ & OEMs (Realme/ColorOS, etc.)
 * 3. Deepgram Nova-2 Cloud STT + Android SpeechRecognizer automatic fallback & WAV/JSON pipelines
 * 4. Multi-Provider AI & Voice API key CRUD (Add, Edit, Delete, Select Primary, Failover)
 * 5. Cloud-independent Local Wake-Word Detection with custom wake names (Hi Nayro, Hey Babu, user-defined)
 * 6. Microphone ownership arbitration, phone-call interruption, permission revocation, and WhatsApp/Call/SMS/Media verification
 */
class DualActivationAndProvidersRegressionTests {

    // ------------------------------------------------------------------------
    // 1. SCREEN-OFF WAKE & ANDROID 11-14+ / OEM COMPATIBILITY HONESTY
    // ------------------------------------------------------------------------

    @Test
    fun testScreenOffWakeHonestlyReportsLocalFallbackWhenDspPrivilegesUnavailable() {
        val report = ScreenOffWakeCapabilityManager.evaluateCapability(
            context = null,
            isLocalWakeListening = true,
            sdkInt = 34, // Android 14
            manufacturer = "realme",
            micGrantedOverride = true,
            roleHeldOverride = false,
            serviceActiveOverride = false,
            hardwareDspSupportedOverride = false,
            batteryExemptOverride = false
        )

        assertEquals(ScreenOffWakeMode.LOCAL_FALLBACK_ACTIVE, report.mode)
        assertFalse("Must never falsely claim DSP hardware hotword on third-party install", report.isHardwareDspHotwordSupported)
        assertTrue(report.isLocalFallbackListeningActive)
        assertTrue(
            "Realme/ColorOS guidance must mention background activity / auto-launch",
            report.oemCompatibilityNote.contains("ColorOS/Realme", ignoreCase = true)
        )
        assertTrue(
            "Android 14+ limitations must mention foregroundServiceType='microphone'",
            report.limitations.any { it.contains("foregroundServiceType='microphone'", ignoreCase = true) }
        )
    }

    @Test
    fun testScreenOffWakeReportsAssistantRoleReadyManualWhenRoleHeldAndLocalWakeOff() {
        val report = ScreenOffWakeCapabilityManager.evaluateCapability(
            context = null,
            isLocalWakeListening = false,
            sdkInt = 33, // Android 13
            manufacturer = "Samsung",
            micGrantedOverride = true,
            roleHeldOverride = true,
            serviceActiveOverride = true,
            hardwareDspSupportedOverride = false,
            batteryExemptOverride = true
        )

        assertEquals(ScreenOffWakeMode.ASSISTANT_ROLE_READY_MANUAL, report.mode)
        assertTrue(report.isDefaultAssistantRoleHeld)
        assertTrue(report.supportsLockScreenVoiceAssist)
        assertFalse(report.isHardwareDspHotwordSupported)
        assertTrue(report.oemCompatibilityNote.contains("One UI", ignoreCase = true))
    }

    @Test
    fun testScreenOffWakeReportsBlockedWhenMicrophonePermissionDenied() {
        val report = ScreenOffWakeCapabilityManager.evaluateCapability(
            context = null,
            isLocalWakeListening = true,
            sdkInt = 30, // Android 11
            manufacturer = "Xiaomi",
            micGrantedOverride = false,
            roleHeldOverride = true,
            serviceActiveOverride = true,
            hardwareDspSupportedOverride = false,
            batteryExemptOverride = false
        )

        assertEquals(ScreenOffWakeMode.UNSUPPORTED_OR_BLOCKED, report.mode)
        assertFalse(report.isLocalFallbackListeningActive)
        assertTrue(report.honestStatusSummary.contains("Microphone permission", ignoreCase = true))
        assertTrue(report.oemCompatibilityNote.contains("MIUI/HyperOS", ignoreCase = true))
    }

    @Test
    fun testScreenOffWakeReportsHardwareDspOnlyWhenPrivilegedAndServiceActive() {
        val report = ScreenOffWakeCapabilityManager.evaluateCapability(
            context = null,
            isLocalWakeListening = false,
            sdkInt = 35, // Android 15
            manufacturer = "Google",
            micGrantedOverride = true,
            roleHeldOverride = true,
            serviceActiveOverride = true,
            hardwareDspSupportedOverride = true,
            batteryExemptOverride = true
        )

        assertEquals(ScreenOffWakeMode.HARDWARE_DSP_HOTWORD, report.mode)
        assertTrue(report.isHardwareDspHotwordSupported)
        assertTrue(report.isVoiceInteractionServiceActive)
    }

    // ------------------------------------------------------------------------
    // 2. DEEPGRAM CLOUD STT PROVIDER & VOICE PROVIDER ROUTER FAILOVER
    // ------------------------------------------------------------------------

    @Test
    fun testDeepgramWavHeaderEncodingIsValidRiffPcm16() {
        val pcmSamples = ByteArray(640) { (it % 127).toByte() }
        val wav = DeepgramSpeechRecognitionProvider.encodePcm16ToWav(pcmSamples, sampleRateHz = 16000)

        assertEquals(44 + pcmSamples.size, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(wav, 12, 4, Charsets.US_ASCII))
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))

        val bb = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(36 + pcmSamples.size, bb.getInt(4))
        assertEquals(16000, bb.getInt(24))
        assertEquals(pcmSamples.size, bb.getInt(40))
    }

    @Test
    fun testDeepgramJsonTranscriptParsingAndLanguageMapping() {
        val sampleJson = """
            {
              "results": {
                "channels": [
                  {
                    "alternatives": [
                      { "transcript": "Call Prakash on WhatsApp", "confidence": 0.964 },
                      { "transcript": "Call Parkash on WhatsApp", "confidence": 0.71 }
                    ]
                  }
                ]
              }
            }
        """.trimIndent()

        val parsed = DeepgramSpeechRecognitionProvider.parseDeepgramTranscriptJson(sampleJson, "en-IN")
        assertNotNull(parsed)
        assertEquals("Call Prakash on WhatsApp", parsed!!.primaryText)
        assertEquals(listOf("Call Parkash on WhatsApp"), parsed.alternatives)
        assertEquals(0.964f, parsed.confidenceScore, 0.001f)
        assertEquals("en-IN", parsed.languageTag)

        assertEquals("hi", DeepgramSpeechRecognitionProvider.mapLanguageTagForDeepgram("hi-IN"))
        assertEquals("multi", DeepgramSpeechRecognitionProvider.mapLanguageTagForDeepgram("en-IN"))
        assertEquals("en", DeepgramSpeechRecognitionProvider.mapLanguageTagForDeepgram("en-US"))
    }

    @Test
    fun testVoiceProviderRouterKeepsAndroidSystemSttEvenIfLegacyDeepgramKeyExists() = runBlocking {
        val storage = EncryptedStorageManager(context = null)
        val router = VoiceProviderRouter(context = null, encryptedStorageManager = storage)

        assertEquals(SpeechRecognitionEngineType.ANDROID_SPEECH_RECOGNIZER, router.status.value.effectiveSttEngine)
        assertTrue(router.status.value.localWakeWordCloudIndependent)

        // Legacy Deepgram selections are migrated to Android system recognition.
        val selectedStatus = router.selectSttEngine(SpeechRecognitionEngineType.DEEPGRAM_CLOUD)
        assertEquals(SpeechRecognitionEngineType.ANDROID_SPEECH_RECOGNIZER, selectedStatus.selectedSttEngine)
        assertEquals(SpeechRecognitionEngineType.ANDROID_SPEECH_RECOGNIZER, selectedStatus.effectiveSttEngine)
        assertNull(selectedStatus.fallbackActiveReason)
        assertTrue(router.resolveActiveSttProvider() === router.androidSttProvider)

        // The dormant legacy key management path must never route microphone audio to Deepgram.
        val (noKeyOk, noKeyMsg) = router.testDeepgramConnection()
        assertFalse(noKeyOk)
        assertTrue(noKeyMsg.contains("No Deepgram API key", ignoreCase = true))

        val savedStatus = router.saveDeepgramApiKey("dg_test_key_12345")
        assertTrue(savedStatus.hasDeepgramKey)
        assertEquals(SpeechRecognitionEngineType.ANDROID_SPEECH_RECOGNIZER, savedStatus.selectedSttEngine)
        assertEquals(SpeechRecognitionEngineType.ANDROID_SPEECH_RECOGNIZER, savedStatus.effectiveSttEngine)
        assertTrue(router.resolveActiveSttProvider() === router.androidSttProvider)

        router.saveDeepgramApiKey("dg_updated_key_99999")
        assertEquals("dg_updated_key_99999", storage.getApiKey(DeepgramSpeechRecognitionProvider.PROVIDER_ID))

        val deletedStatus = router.deleteDeepgramApiKey()
        assertFalse(deletedStatus.hasDeepgramKey)
        assertEquals(SpeechRecognitionEngineType.ANDROID_SPEECH_RECOGNIZER, deletedStatus.effectiveSttEngine)
        assertTrue(router.resolveActiveSttProvider() === router.androidSttProvider)
    }

    // ------------------------------------------------------------------------
    // 3. MULTI-PROVIDER AI KEY CRUD (ADD, EDIT, DELETE, SELECT PRIMARY, FAILOVER)
    // ------------------------------------------------------------------------

    @Test
    fun testAiProviderManagerKeyCrudSelectionAndFailover() = runBlocking {
        val storage = EncryptedStorageManager(context = null)
        val manager = AIProviderManager(encryptedStorageManager = storage)

        // Initially no cloud provider active
        assertNull(manager.activeProvider.value)
        val emptyRes = manager.sendRequest("Hello NAYRO")
        assertTrue(emptyRes is AIResult.Error)

        // Add Gemini & OpenAI keys -> Gemini is priority 1
        manager.saveApiKey("gemini_default", "AIzaSyTestGeminiKey1")
        manager.saveApiKey("openai_custom", "sk-test-openai-key-1")
        assertEquals("gemini_default", manager.activeProvider.value?.id)

        // Edit Gemini key & model
        manager.saveApiKey("gemini_default", "AIzaSyTestGeminiKey2")
        assertEquals("AIzaSyTestGeminiKey2", storage.getApiKey("gemini_default"))
        assertTrue(manager.updateProviderModel("gemini_default", "gemini-2.5-pro"))
        assertEquals("gemini-2.5-pro", manager.configuredProviders.value.first { it.id == "gemini_default" }.modelId)

        // Switch primary provider to OpenAI
        assertTrue(manager.selectPreferredProvider("openai_custom"))
        assertEquals("openai_custom", manager.activeProvider.value?.id)

        // Delete OpenAI key -> automatic failover back to Gemini
        manager.deleteApiKey("openai_custom")
        assertFalse(storage.hasApiKey("openai_custom"))
        assertEquals("gemini_default", manager.activeProvider.value?.id)

        // Delete Gemini key -> activeProvider becomes null and testProviderConnection reports missing key
        manager.deleteApiKey("gemini_default")
        assertNull(manager.activeProvider.value)
        val missingTest = manager.testProviderConnection("gemini_default")
        assertTrue(missingTest is AIResult.Error)
        assertEquals(401, (missingTest as AIResult.Error).code)
    }

    // ------------------------------------------------------------------------
    // 4. CLOUD-INDEPENDENT CUSTOM WAKE PHRASES & FLOATING CONTINUOUS SESSION
    // ------------------------------------------------------------------------

    @Test
    fun testLocalWakeWordRemainsIndependentOfCloudApiKeysWithCustomNames() {
        val storage = EncryptedStorageManager(context = null)
        assertFalse(storage.hasApiKey("gemini_default"))
        assertFalse(storage.hasApiKey(DeepgramSpeechRecognitionProvider.PROVIDER_ID))

        val identityStore = AssistantIdentityStore(context = null)
        identityStore.updateDisplayName("Babu")
        identityStore.addWakePhrase("Hi Nayro")
        identityStore.addWakePhrase("Hey Cutie")

        val voiceManager = VoiceManager(context = null, encryptedStorageManager = storage)
        voiceManager.bindIdentityStore(identityStore)

        val phrases = voiceManager.wakeWordEngine.getConfiguredWakePhrases().map { it.lowercase() }
        assertTrue(phrases.contains("hi nayro"))
        assertTrue(phrases.contains("hey babu"))
        assertTrue(phrases.contains("hey cutie"))

        // Two-stage wake activation works locally with zero cloud API keys configured
        val dispatched = mutableListOf<String>()
        val outcome = voiceManager.processTwoStageVoiceUtterance(
            rawUtterance = "Hi Nayro call Prakash",
            onCommandReady = { dispatched.add(it) }
        )
        assertEquals(VoiceManager.TwoStageWakeOutcome.WAKE_AND_COMMAND_DISPATCHED, outcome)
        assertEquals(listOf("call Prakash"), dispatched)
    }

    @Test
    fun testFloatingSessionContinuousConversationAndImmediateStopMicRelease() {
        val voiceManager = VoiceManager(context = null)
        val receivedFromFloating = mutableListOf<String>()
        voiceManager.attachFloatingListener { cmd -> receivedFromFloating.add(cmd) }

        // Turn 1: Floating icon tap starts voice session -> continuous follow-up active
        val states = voiceManager.simulateVoiceConversationTurnForTest(
            spokenCommand = "Open YouTube and search relaxing music",
            assistantReply = "Opening YouTube for relaxing music.",
            enableFollowUp = true
        )
        assertTrue(states.contains(VoiceState.LISTENING))
        assertEquals(VoiceState.FOLLOW_UP_LISTENING, voiceManager.voiceState.value)
        assertTrue(voiceManager.isFollowUpSessionActive.value)

        // Follow-up command without repeating wake word routes to floating listener
        voiceManager.dispatchRecognizedSpeech("pause it")
        assertEquals(listOf("pause it"), receivedFromFloating)

        // Pressing Stop immediately releases microphone resources
        voiceManager.endConversationSession(returnToStandby = false)
        assertFalse(voiceManager.isFollowUpSessionActive.value)
        assertEquals(AudioSessionOwner.NONE, voiceManager.audioSessionOwner.value)
        assertEquals(VoiceState.DISABLED, voiceManager.voiceState.value)
    }

    @Test
    fun testMicrophoneOwnershipPhoneCallInterruptionAndPermissionRevocation() {
        val voiceManager = VoiceManager(context = null)
        val identityStore = AssistantIdentityStore(context = null)
        val bgController = BackgroundVoiceController(
            context = null,
            identityStore = identityStore,
            permissionManager = null,
            voiceManager = voiceManager,
            micPermissionCheckOverride = { true }
        )

        val (enabled, _) = bgController.enableBackgroundListening()
        assertTrue(enabled)

        // Incoming phone call interrupts audio cleanly
        voiceManager.onExternalAudioInterruption("Incoming telephony call")
        assertEquals(AudioSessionOwner.PHONE_CALL_INTERRUPTION, voiceManager.audioSessionOwner.value)
        assertEquals(VoiceState.PAUSED, voiceManager.voiceState.value)

        // Revoking microphone permission stops background listening cleanly
        bgController.onMicrophonePermissionRevoked()
        assertFalse(bgController.state.value.isEnabled)
    }

    // ------------------------------------------------------------------------
    // 5. WHATSAPP, CALLS, SMS & YOUTUBE MEDIA PLANNING + HONEST VERIFICATION
    // ------------------------------------------------------------------------

    @Test
    fun testWhatsAppCallSmsAndMediaPlansEnforceRiskAndVerificationHonesty() {
        // 1. WhatsApp message plans full multi-step interaction (open -> click contact -> draft -> R3 confirmation)
        val waIntent = com.example.core.agent.TaskClassifier.classify("Send WhatsApp message to Prakash saying I will reach in 10 minutes")
        val waTask = TaskPlanner.planTask(waIntent)
        assertEquals(4, waTask.actions.size)
        assertEquals(ActionType.LAUNCH_APP, waTask.actions[0].actionType)
        assertEquals(ActionType.CLICK_ELEMENT, waTask.actions[1].actionType)
        assertEquals("Prakash", waTask.actions[1].target)
        assertEquals(ActionType.SET_TEXT, waTask.actions[2].actionType)
        val waConfirmAction = waTask.actions.firstOrNull { it.actionType == ActionType.ASK_USER_CONFIRMATION }
        assertNotNull("Expected ASK_USER_CONFIRMATION step for WhatsApp send", waConfirmAction)
        assertTrue("WhatsApp sending must require user confirmation", waConfirmAction!!.riskLevel.requiresConfirmation)
        assertEquals(RiskLevel.R3_EXTERNAL_COMMUNICATION, waConfirmAction.riskLevel)

        // 2. Direct phone call requires explicit confirmation (R3)
        val callIntent = com.example.core.agent.TaskClassifier.classify("Call Prakash")
        val callTask = TaskPlanner.planTask(callIntent)
        val callAction = callTask.actions.firstOrNull { it.actionType == ActionType.MAKE_PHONE_CALL }
        assertNotNull("Expected MAKE_PHONE_CALL plan for 'Call Prakash'", callAction)
        assertTrue(callAction!!.riskLevel.requiresConfirmation)
        assertEquals(RiskLevel.R3_EXTERNAL_COMMUNICATION, callAction.riskLevel)

        // 3. SMS requires explicit confirmation (R3)
        val smsIntent = com.example.core.agent.TaskClassifier.classify("Send SMS to 9876543210 saying Hello from NAYRO")
        val smsTask = TaskPlanner.planTask(smsIntent)
        val smsAction = smsTask.actions.firstOrNull { it.actionType == ActionType.SEND_SMS }
        assertNotNull("Expected SEND_SMS plan", smsAction)
        assertTrue(smsAction!!.riskLevel.requiresConfirmation)

        // 4a. Direct YouTube music play command routes to verified CONTROL_MEDIA (not just LAUNCH_APP)
        val playMusicIntent = com.example.core.agent.TaskClassifier.classify("Play relaxing flute music on YouTube")
        val playMusicTask = TaskPlanner.planTask(playMusicIntent)
        val mediaAction = playMusicTask.actions.firstOrNull { it.actionType == ActionType.CONTROL_MEDIA }
        assertNotNull("Expected CONTROL_MEDIA action for music playback", mediaAction)
        assertEquals("com.google.android.youtube", mediaAction!!.parameters["app"])
        assertEquals("relaxing flute music", mediaAction.target)

        // 4b. YouTube search + play command plans multi-step navigation, search input, and playback selection
        val ytSearchIntent = com.example.core.agent.TaskClassifier.classify("Open YouTube and search relaxing flute video")
        val ytSearchTask = TaskPlanner.planTask(ytSearchIntent)
        assertTrue("YouTube search+video task must plan multi-step search & playback, not just app launch", ytSearchTask.actions.size >= 4)
        assertEquals(ActionType.LAUNCH_APP, ytSearchTask.actions.first().actionType)

        // 5. ActionVerifier never marks inconclusive external actions as VERIFIED
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafeField = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = unsafeField.get(null)
        val allocMethod = unsafeClass.getMethod("allocateInstance", Class::class.java)
        val screenIntel = allocMethod.invoke(
            unsafe,
            com.example.core.screen.ScreenIntelligenceManager::class.java
        ) as com.example.core.screen.ScreenIntelligenceManager
        val verifier = ActionVerifier(screenIntel)
        val unverifiedWhatsApp = verifier.verifyAction(
            action = AgentAction(
                actionType = ActionType.MESSAGE_DRAFT_SEND,
                target = "Prakash",
                parameters = mapOf("message" to "Hi"),
                riskLevel = RiskLevel.R3_EXTERNAL_COMMUNICATION
            ),
            executionResult = com.example.core.agent.ActionExecutionResult(
                isSuccess = false,
                outputMessage = "WhatsApp send button was not found; draft opened only.",
                status = VerificationStatus.INCONCLUSIVE
            ),
            preStateSummary = "Home Screen"
        )
        assertFalse("Must never falsely mark inconclusive WhatsApp draft as verified", unverifiedWhatsApp.isVerified)
        assertEquals(VerificationStatus.INCONCLUSIVE, unverifiedWhatsApp.status)
    }
}
