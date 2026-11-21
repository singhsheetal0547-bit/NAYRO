package com.example

import com.example.core.agent.ActionExecutionResult
import com.example.core.agent.ActionExecutor
import com.example.core.agent.ActionType
import com.example.core.agent.ActionVerifier
import com.example.core.agent.AgentAction
import com.example.core.agent.AgentBrain
import com.example.core.agent.ConversationLanguage
import com.example.core.agent.ConversationalIntelligenceEngine
import com.example.core.agent.ExecutionDomain
import com.example.core.agent.RiskLevel
import com.example.core.agent.VerificationStatus
import com.example.core.agent.tools.ToolRegistryManager
import com.example.core.communication.EmailDraftRequest
import com.example.core.communication.EmailOperationManager
import com.example.core.communication.EmailOperationState
import com.example.core.memory.MemoryManager
import com.example.core.memory.NotebookManager
import com.example.core.provider.AIProviderManager
import com.example.core.provider.ProviderType
import com.example.core.provider.adapter.OpenAICompatibleAdapter
import com.example.core.screen.ScreenIntelligenceManager
import com.example.core.screen.ScreenRecordingController
import com.example.core.screen.ScreenRecordingState
import com.example.core.security.EncryptedStorageManager
import com.example.core.skills.SkillManager
import com.example.core.subagent.SubAgentManager
import com.example.core.telephony.CallIntelligenceManager
import com.example.core.telephony.CommunicationSpamDetector
import com.example.core.telephony.SpamRiskLevel
import com.example.core.telephony.TelecomCallControlBridge
import com.example.core.voice.AndroidSpeechSynthesisProvider
import com.example.core.voice.AudioSessionOwner
import com.example.core.voice.VoiceConfiguration
import com.example.core.voice.VoiceManager
import com.example.core.voice.VoiceState
import com.example.core.workspace.WorkspaceManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Comprehensive verification suite for NAYRO's ONE Unified Autonomous Agent across all THREE
 * Execution Domains (Device, Voice, Communication), Conversational AI (Hindi/English/Hinglish),
 * Multi-Provider AI (Gemini, xAI Grok, OpenRouter, OpenAI, DeepSeek, Ollama),
 * Advanced Voice & CRITICAL CALLING RULE (zero AI speech during phone calls),
 * Spam Detection, Email Operations, and Policy-Compliant Screen Recording.
 */
class UnifiedAutonomousAgentTests {

    @Before
    fun setUp() {
        TelecomCallControlBridge.resetForTesting()
        ToolRegistryManager.resetToDefaults()
    }

    // ========================================================================
    // 1. UNIFIED 3-DOMAIN ARCHITECTURE & TOOL REGISTRY
    // ========================================================================

    @Test
    fun testToolRegistryCoversDeviceAndCommunicationDomainsWithNewTools() {
        assertTrue(ToolRegistryManager.isToolAvailable(ActionType.MANAGE_SCREEN_RECORDING))
        assertTrue(ToolRegistryManager.isToolAvailable(ActionType.COMPOSE_EMAIL))
        assertTrue(ToolRegistryManager.isToolAvailable(ActionType.READ_EMAIL_NOTIFICATIONS))
        assertTrue(ToolRegistryManager.isToolAvailable(ActionType.CHECK_COMMUNICATION_SPAM))

        val deviceTools = ToolRegistryManager.getToolsByDomain(ExecutionDomain.DEVICE)
        val commTools = ToolRegistryManager.getToolsByDomain(ExecutionDomain.COMMUNICATION)
        val summary = ToolRegistryManager.getDomainSummary()

        assertTrue("Device domain must have rich native tools", deviceTools.size >= 25)
        assertTrue("Communication domain must include Call/SMS/WhatsApp/Email/Spam tools", commTools.size >= 15)
        assertEquals(deviceTools.size, summary[ExecutionDomain.DEVICE])
        assertEquals(commTools.size, summary[ExecutionDomain.COMMUNICATION])
    }

    // ========================================================================
    // 2. CROSS-DOMAIN MULTI-STEP PLANNING & PRONOUN CARRY-OVER
    // ========================================================================

    @Test
    fun testCrossDomainHinglishWhatsAppAndCallCarriesEntityRahulAcrossClauses() {
        val brain = createTestAgentBrain()

        val task = brain.planTaskForInstruction(
            "Babu, Rahul ko WhatsApp message bhejo aur phir use call karo."
        )

        assertEquals("MULTI_STEP_COMPOUND", task.resolvedIntent)
        val whatsAppStep = task.actions.firstOrNull {
            it.actionType == ActionType.MESSAGE_DRAFT_SEND || it.actionType == ActionType.ASK_USER_CONFIRMATION
        }
        val callStep = task.actions.firstOrNull { it.actionType == ActionType.MAKE_PHONE_CALL }

        assertNotNull("Expected WhatsApp message action in compound plan", whatsAppStep)
        assertNotNull("Expected outgoing phone call action after WhatsApp step", callStep)

        val whatsAppRecipient = (whatsAppStep?.parameters?.get("recipient") ?: whatsAppStep?.target).orEmpty()
        val callTarget = (callStep?.parameters?.get("target") ?: callStep?.target).orEmpty()
        assertTrue("WhatsApp step should target Rahul, got: $whatsAppRecipient", whatsAppRecipient.contains("Rahul", ignoreCase = true))
        assertTrue("Call step should carry forward Rahul from 'use call karo', got: $callTarget", callTarget.contains("Rahul", ignoreCase = true))

        // Verify WhatsApp comes BEFORE phone call in the unified task queue
        val waIndex = task.actions.indexOf(whatsAppStep)
        val callIndex = task.actions.indexOf(callStep)
        assertTrue("WhatsApp step must precede call step", waIndex < callIndex)
    }

    @Test
    fun testCrossDomainEnglishMessageAndCallCarriesEntityAcrossClauses() {
        val brain = createTestAgentBrain()

        val task = brain.planTaskForInstruction(
            "Send Priya a WhatsApp message saying I am on the way and then call her"
        )

        val waStep = task.actions.firstOrNull {
            it.actionType == ActionType.MESSAGE_DRAFT_SEND || it.actionType == ActionType.ASK_USER_CONFIRMATION
        }
        val callStep = task.actions.firstOrNull { it.actionType == ActionType.MAKE_PHONE_CALL }
        assertNotNull(waStep)
        assertNotNull(callStep)
        val callTarget = (callStep?.parameters?.get("target") ?: callStep?.target).orEmpty()
        assertTrue("Expected Priya carried forward to 'call her', got: $callTarget", callTarget.contains("Priya", ignoreCase = true))
    }

    // ========================================================================
    // 3. CRITICAL CALLING RULE — AI MUST NEVER SPEAK ON PHONE CALLS
    // ========================================================================

    @Test
    fun testCriticalCallingRuleHardBlocksAiSpeechDuringActiveOrRingingPhoneCall() {
        val voiceManager = VoiceManager(context = null)
        voiceManager.updateConfiguration(VoiceConfiguration(isTtsEnabled = true, isWakeWordEnabled = false))

        // Simulate active phone call
        voiceManager.setPhoneCallActiveCheckerForTesting { true }
        assertTrue(voiceManager.isSpeechBlockedByPhoneCall())

        val spoke = voiceManager.speak("Hello Rahul, this is AI speaking")
        assertFalse("CRITICAL CALLING RULE: AI must NEVER speak during an active/ringing phone call", spoke)
        assertEquals(VoiceState.PAUSED, voiceManager.voiceState.value)
        assertNotNull(voiceManager.lastSpeechBlockedReason.value)
        assertTrue(voiceManager.lastSpeechBlockedReason.value!!.contains("phone call", ignoreCase = true))

        // Also verify blocking when PHONE_CALL_INTERRUPTION owns microphone
        voiceManager.setPhoneCallActiveCheckerForTesting { false }
        voiceManager.onExternalAudioInterruption("Incoming phone call ringing")
        assertEquals(AudioSessionOwner.PHONE_CALL_INTERRUPTION, voiceManager.audioSessionOwner.value)
        assertTrue(voiceManager.isSpeechBlockedByPhoneCall())
        assertFalse(voiceManager.speak("Should remain muted while call interruption holds audio"))

        voiceManager.onExternalAudioInterruptionEnded()
        assertFalse(voiceManager.isSpeechBlockedByPhoneCall())
    }

    // ========================================================================
    // 4. CONVERSATIONAL AI INTELLIGENCE (HINDI, ENGLISH, HINGLISH + DAILY PLAN)
    // ========================================================================

    @Test
    fun testConversationalLanguageDetectionAndDynamicSwitching() {
        val engine = ConversationalIntelligenceEngine()

        assertEquals(ConversationLanguage.ENGLISH, ConversationalIntelligenceEngine.detectLanguage("Hi, how can I help you today?"))
        assertEquals(ConversationLanguage.HINDI, ConversationalIntelligenceEngine.detectLanguage("नमस्ते, आज मुझे कुछ ज़रूरी काम करने हैं"))
        assertEquals(ConversationLanguage.HINGLISH, ConversationalIntelligenceEngine.detectLanguage("Babu, aaj mujhe kuch important kaam karne hain"))

        // Turn 1: English greeting
        val a1 = engine.analyzeUserMessage("Hi")
        assertTrue(a1.isGreeting)
        assertEquals(ConversationLanguage.ENGLISH, a1.language)
        val r1 = engine.buildLocalConversationalReply("Babu", a1, "Hi")
        assertEquals("Hi, how can I help you today?", r1)
        engine.recordTurn("user", "Hi", a1.language)
        engine.recordTurn("assistant", r1!!, a1.language)

        // Turn 2: Switch dynamically to Hinglish daily planning
        val a2 = engine.analyzeUserMessage("Babu, aaj mujhe kuch important kaam karne hain")
        assertEquals(ConversationLanguage.HINGLISH, a2.language)
        assertTrue(a2.isDailyPlanningDiscussion)
        assertTrue(engine.isDailyPlanningSessionActive)
        val r2 = engine.buildLocalConversationalReply("Babu", a2, "Babu, aaj mujhe kuch important kaam karne hain")
        assertNotNull(r2)
        assertTrue("Expected natural Hinglish planning follow-up", r2!!.contains("important kaam", ignoreCase = true))

        // Turn 3: Provide inline tasks -> structured daily plan extraction + completion
        val a3 = engine.analyzeUserMessage("aaj ka plan: Rahul ko call karna, urgent email bhejna, aur gym jana")
        assertEquals(3, a3.extractedPlanItems.size)
        val plan = engine.getDailyPlan()
        assertEquals(3, plan.size)
        assertEquals(ExecutionDomain.COMMUNICATION, plan[0].domain)
        assertEquals("HIGH", plan[1].priority)
        assertTrue(engine.markPlanItemCompleted("Rahul"))
        assertTrue(engine.getDailyPlan().first().isCompleted)

        // Turn 4: Switch to Hindi (Devanagari) and verify system prompt includes today's plan & Hindi instruction
        val a4 = engine.analyzeUserMessage("नमस्ते")
        assertEquals(ConversationLanguage.HINDI, a4.language)
        val prompt = engine.buildConversationalSystemPrompt("Babu", ConversationLanguage.HINDI, "User prefers short answers")
        assertTrue(prompt.contains("Hindi (Devanagari script)"))
        assertTrue(prompt.contains("Active Today's Plan"))
        assertTrue(prompt.contains("CRITICAL CALLING RULE"))
    }

    // ========================================================================
    // 5. MULTI-PROVIDER AI (GEMINI, XAI GROK, OPENROUTER, OPENAI, DEEPSEEK, OLLAMA)
    // ========================================================================

    @Test
    fun testMultiProviderDefaultsOpenRouterHeadersAndFailover() {
        val storage = EncryptedStorageManager(context = null)
        val manager = AIProviderManager(encryptedStorageManager = storage)

        val providers = manager.configuredProviders.value
        val gemini = providers.first { it.id == "gemini_default" }
        val grok = providers.first { it.id == "grok_custom" }
        val openRouter = providers.first { it.id == "openrouter_custom" }

        assertEquals(ProviderType.GEMINI, gemini.type)
        assertEquals("grok-2-latest", grok.modelId)
        assertEquals(ProviderType.OPENROUTER, openRouter.type)

        // Verify OpenRouter headers include HTTP-Referer and X-Title
        val orHeaders = OpenAICompatibleAdapter.buildRequestHeaders(openRouter, "sk-or-test-123")
        assertEquals("Bearer sk-or-test-123", orHeaders["Authorization"])
        assertEquals(OpenAICompatibleAdapter.OPENROUTER_REFERER, orHeaders["HTTP-Referer"])
        assertEquals(OpenAICompatibleAdapter.OPENROUTER_TITLE, orHeaders["X-Title"])

        // Grok headers should include Bearer auth without OpenRouter-only headers
        val grokHeaders = OpenAICompatibleAdapter.buildRequestHeaders(grok, "xai-test-456")
        assertEquals("Bearer xai-test-456", grokHeaders["Authorization"])
        assertNull(grokHeaders["HTTP-Referer"])

        // Test user key management, preferred provider switching, and failover on deletion
        manager.saveApiKey("gemini_default", "gemini-key-abc")
        manager.saveApiKey("openrouter_custom", "sk-or-key-xyz")
        assertTrue(manager.selectPreferredProvider("openrouter_custom"))
        assertEquals("openrouter_custom", manager.activeProvider.value?.id)

        assertTrue(manager.updateProviderModel("openrouter_custom", "anthropic/claude-3.5-sonnet"))
        assertEquals("anthropic/claude-3.5-sonnet", manager.activeProvider.value?.modelId)

        // Deleting OpenRouter key seamlessly fails back to Gemini
        manager.deleteApiKey("openrouter_custom")
        assertEquals("gemini_default", manager.activeProvider.value?.id)
    }

    // ========================================================================
    // 6. ADVANCED VOICE SYSTEM (TTS PROVIDER LOCALES & BARGE-IN)
    // ========================================================================

    @Test
    fun testSpeechSynthesisProviderAutoResolvesHindiHinglishEnglishLocales() {
        val ttsProvider = AndroidSpeechSynthesisProvider(context = null)

        val hindiLocale = ttsProvider.resolveLocaleForText("नमस्ते, मैं आपकी कैसे मदद कर सकता हूँ?", "en-IN")
        val hinglishLocale = ttsProvider.resolveLocaleForText("Babu, aaj mujhe kuch important kaam karne hain", "en-IN")
        val englishLocale = ttsProvider.resolveLocaleForText("Hi, how can I help you today?", "en-IN")

        assertEquals("hi", hindiLocale.language)
        assertEquals("hi", hinglishLocale.language)
        assertEquals("en", englishLocale.language)
    }

    @Test
    fun testVoiceManagerExposesTtsProviderAndSupportsBargeIn() {
        val voiceManager = VoiceManager(context = null)
        assertNotNull(voiceManager.speechSynthesisProvider)
        assertEquals(voiceManager.voiceProviderRouter.resolveActiveTtsProvider(), voiceManager.speechSynthesisProvider)

        voiceManager.setMicPermissionCheckerForTesting { false }
        voiceManager.microphoneOwnership.enterResponsePlayback("Simulated speaking")
        voiceManager.interruptSpeechForUserBargeIn()
        // Releases TTS_PLAYBACK immediately (and transitions cleanly to ERROR since mic perm is false in test)
        assertTrue(voiceManager.audioSessionOwner.value != AudioSessionOwner.TTS_PLAYBACK)
    }

    // ========================================================================
    // 7. AUTONOMOUS COMMUNICATION AGENT (SPAM, CALL SCREENING, EMAIL)
    // ========================================================================

    @Test
    fun testCommunicationSpamDetectorIdentifiesTelemarketersPhishingAndSafeContacts() {
        val telemarketer = CommunicationSpamDetector.assessIncomingCall(
            phoneNumber = "+911409876543",
            isKnownContact = false
        )
        assertTrue(telemarketer.isSuspiciousOrSpam)
        assertTrue(telemarketer.score >= 55)

        val knownCaller = CommunicationSpamDetector.assessIncomingCall(
            phoneNumber = "+919876543210",
            isKnownContact = true,
            isVerifiedStirShakenPass = true
        )
        assertEquals(SpamRiskLevel.SAFE, knownCaller.riskLevel)
        assertFalse(knownCaller.isSuspiciousOrSpam)

        val phishingSms = CommunicationSpamDetector.assessMessage(
            sender = "VM-ALERT",
            body = "URGENT: Your bank KYC expired! Share OTP immediately and click bit.ly/verify-kyc-now or account blocked."
        )
        assertEquals(SpamRiskLevel.HIGH_RISK_SPAM, phishingSms.riskLevel)
        assertTrue(phishingSms.isSuspiciousOrSpam)

        val normalSms = CommunicationSpamDetector.assessMessage(
            sender = "Rahul",
            body = "Hey, let's meet for coffee at 5 PM today.",
            isKnownContact = true
        )
        assertEquals(SpamRiskLevel.SAFE, normalSms.riskLevel)
    }

    @Test
    fun testCallScreeningBridgeRecordsSuspiciousIncomingCallerHonestly() {
        val assessment = CommunicationSpamDetector.assessIncomingCall("+911401234567", isKnownContact = false)
        TelecomCallControlBridge.recordScreenedIncomingCall(
            phoneNumber = "+911401234567",
            resolvedContactName = null,
            spamAssessment = assessment
        )

        val info = TelecomCallControlBridge.activeCallInfo.value
        assertNotNull(info)
        assertEquals("RINGING", info?.stateLabel)
        assertEquals("+911401234567", info?.callerNumber)
        assertTrue(info?.spamAssessment?.isSuspiciousOrSpam == true)
        assertTrue(TelecomCallControlBridge.isCallScreeningActive.value)
    }

    @Test
    fun testEmailOperationManagerValidatesInputAndReportsHonestStatus() {
        val emailManager = EmailOperationManager(context = null)

        val invalid = emailManager.openEmailComposer(EmailDraftRequest("not-an-email", "Hello", "Test body"))
        assertEquals(VerificationStatus.FAILED, invalid.status)
        assertFalse(invalid.isSuccess)

        val noClient = emailManager.openEmailComposer(EmailDraftRequest("rahul@example.com", "Meeting", "See you at 4 PM"))
        assertEquals(VerificationStatus.NOT_SUPPORTED, noClient.status)
        assertFalse(noClient.isSuccess)

        // When client is available, composer opens with honest INCONCLUSIVE status (never claims email sent)
        emailManager.testOverrideEmailClientAvailable = true
        val composerOpened = emailManager.openEmailComposer(EmailDraftRequest("rahul@example.com", "Meeting", "See you at 4 PM"))
        assertEquals(VerificationStatus.INCONCLUSIVE, composerOpened.status)
        assertTrue(composerOpened.outputMessage.contains(EmailOperationState.EMAIL_COMPOSER_OPENED.name))

        val previews = emailManager.readEmailNotificationPreviews()
        assertEquals(VerificationStatus.REQUIRES_PERMISSION, previews.status)
        assertTrue(previews.outputMessage.contains("Notification Listener access is required", ignoreCase = true))

        // Verify ActionVerifier never marks EMAIL_COMPOSER_OPENED (INCONCLUSIVE) as VERIFIED
        val verifier = ActionVerifier(allocateWithoutInit(ScreenIntelligenceManager::class.java))
        val composeVerification = verifier.verifyAction(
            action = AgentAction(actionType = ActionType.COMPOSE_EMAIL, target = "rahul@example.com"),
            executionResult = composerOpened,
            preStateSummary = null
        )
        assertEquals(VerificationStatus.INCONCLUSIVE, composeVerification.status)
    }

    @Test
    fun testAgentBrainDynamicallyPlansEmailSpamAndScreenRecordingActions() {
        val brain = createTestAgentBrain()

        val emailTask = brain.planTaskForInstruction("Send email to rahul@example.com subject Project Update saying Demo is ready")
        assertEquals(ActionType.COMPOSE_EMAIL, emailTask.actions.first().actionType)
        assertEquals("rahul@example.com", emailTask.actions.first().parameters["recipient"])

        val readEmailTask = brain.planTaskForInstruction("Check my latest email")
        assertEquals(ActionType.READ_EMAIL_NOTIFICATIONS, readEmailTask.actions.first().actionType)

        val spamTask = brain.planTaskForInstruction("Check if +911409876543 is spam")
        assertEquals(ActionType.CHECK_COMMUNICATION_SPAM, spamTask.actions.first().actionType)
        assertEquals("+911409876543", spamTask.actions.first().parameters["phoneNumber"])

        val recordStart = brain.planTaskForInstruction("Start screen recording")
        assertEquals(ActionType.MANAGE_SCREEN_RECORDING, recordStart.actions.first().actionType)
        assertEquals("START", recordStart.actions.first().parameters["operation"])

        val recordStop = brain.planTaskForInstruction("Stop screen recording")
        assertEquals(ActionType.MANAGE_SCREEN_RECORDING, recordStop.actions.first().actionType)
        assertEquals("STOP", recordStop.actions.first().parameters["operation"])
    }

    // ========================================================================
    // 8. AUTONOMOUS DEVICE AGENT — POLICY-COMPLIANT SCREEN RECORDING
    // ========================================================================

    @Test
    fun testScreenRecordingControllerRefusesSilentCaptureWithoutExplicitConsent() {
        ScreenRecordingController.resetForTesting()
        assertEquals(ScreenRecordingState.IDLE, ScreenRecordingController.status.value.state)

        // Without Android MediaProjection consent intent (or denied resultCode), never enters RECORDING
        assertNull(ScreenRecordingController.createUserConsentIntent(context = null))
        assertEquals(ScreenRecordingState.UNAVAILABLE, ScreenRecordingController.status.value.state)

        val started = ScreenRecordingController.onConsentResult(context = null, resultCode = 0, data = null)
        assertFalse(started)
        assertEquals(ScreenRecordingState.IDLE, ScreenRecordingController.status.value.state)
        assertTrue(ScreenRecordingController.status.value.detail.contains("denied", ignoreCase = true))

        val wasRecording = ScreenRecordingController.stopRecording()
        assertFalse(wasRecording)
        assertEquals(ScreenRecordingState.IDLE, ScreenRecordingController.status.value.state)
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

    private fun createTestAgentBrain(): AgentBrain {
        val storage = EncryptedStorageManager(context = null)
        val providerManager = AIProviderManager(encryptedStorageManager = storage)
        val workspaceManager = allocateWithoutInit(WorkspaceManager::class.java)
        val skillManager = SkillManager()
        val subAgentManager = SubAgentManager()
        val stubExecutor = allocateWithoutInit(ActionExecutor::class.java)
        return AgentBrain(
            providerManager = providerManager,
            actionExecutor = stubExecutor,
            workspaceManager = workspaceManager,
            skillManager = skillManager,
            subAgentManager = subAgentManager
        )
    }
}
