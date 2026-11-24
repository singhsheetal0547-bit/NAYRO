package com.example

import android.content.Context
import com.example.core.agent.ActionExecutionResult
import com.example.core.agent.ActionExecutor
import com.example.core.agent.ActionType
import com.example.core.agent.ActionVerifier
import com.example.core.agent.AgentAction
import com.example.core.agent.AgentBrain
import com.example.core.agent.AgentRuntime
import com.example.core.agent.ConversationalIntelligenceEngine
import com.example.core.agent.TaskClassifier
import com.example.core.agent.TaskPlanner
import com.example.core.agent.UserIntentType
import com.example.core.agent.VerificationStatus
import com.example.core.agent.tools.ToolRegistryManager
import com.example.core.capability.CapabilityManager
import com.example.core.memory.ConversationContinuityManager
import com.example.core.memory.MemoryManager
import com.example.core.memory.NotebookManager
import com.example.core.network.NetworkConnectivityMonitor
import com.example.core.network.NetworkStatus
import com.example.core.network.NetworkTransportType
import com.example.core.permission.PermissionManager
import com.example.core.provider.AIProviderManager
import com.example.core.provider.ProviderConfig
import com.example.core.provider.ProviderType
import com.example.core.provider.adapter.AIProviderAdapter
import com.example.core.provider.adapter.GeminiProviderAdapter
import com.example.core.provider.adapter.OpenAICompatibleAdapter
import com.example.core.provider.model.AIChatMessage
import com.example.core.provider.model.AIRequest
import com.example.core.provider.model.AIResponse
import com.example.core.provider.model.AIResult
import com.example.core.screen.ScreenIntelligenceManager
import com.example.core.security.EncryptedStorageManager
import com.example.core.service.DeviceOperatingStateManager
import com.example.core.service.LockScreenPolicyDecision
import com.example.core.skills.SkillManager
import com.example.core.subagent.SubAgentManager
import com.example.core.voice.engine.LocalWakeWordEngine
import com.example.core.voice.engine.VadConfig
import com.example.core.voice.engine.VadState
import com.example.core.voice.engine.VoiceActivityDetector
import com.example.core.workspace.WorkspaceManager
import com.example.data.local.entities.ConversationEntity
import com.example.data.local.entities.NoteEntity
import com.example.data.repository.NayroRepository
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * End-to-End JVM Audit & Verification Suite for NAYRO Online Autonomous Android Agent:
 * 1. Online AI Intelligence: URL normalization, Gemini alternating role merging, OpenRouter/Grok headers,
 *    real Wi-Fi/Mobile Data connectivity detection, offline fast-fail, multi-provider failover,
 *    streaming token callbacks, honest API error reporting, and planner single-round-trip optimization.
 * 2. Independent VAD & Wake-Word Pipeline: Speech start -> active -> hangover -> end-of-utterance,
 *    ambient noise rejection, noise-floor adaptation, and Hi Nayro / Hey Nayro / Hi Babu support.
 * 3. Android Device Control: English/Hinglish/Hindi volume classification, planning,
 *    ToolRegistry validation, ActionVerifier & LockScreen policy verification.
 * 4. Persistent Room Conversation & Note Hydration across simulated app restarts.
 */
class OnlineAutonomousAgentAuditTests {

    private lateinit var storage: EncryptedStorageManager

    @Before
    fun setUp() {
        ToolRegistryManager.resetToDefaults()
        storage = EncryptedStorageManager(context = null)
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

    private fun setField(target: Any, fieldName: String, value: Any?) {
        var clazz: Class<*>? = target.javaClass
        while (clazz != null) {
            try {
                val f = clazz.getDeclaredField(fieldName)
                f.isAccessible = true
                f.set(target, value)
                return
            } catch (_: NoSuchFieldException) {
                clazz = clazz.superclass
            }
        }
        throw IllegalStateException("Field '$fieldName' not found on ${target.javaClass.name}")
    }

    private fun createJvmActionExecutor(providerManager: AIProviderManager): ActionExecutor {
        val fakeContext = allocateWithoutInit(android.content.ContextWrapper::class.java)
        val fakeRepo = allocateWithoutInit(NayroRepository::class.java)
        val executor = allocateWithoutInit(ActionExecutor::class.java)
        setField(executor, "context", fakeContext)
        setField(executor, "screenIntelligenceManager", ScreenIntelligenceManager(fakeContext))
        setField(executor, "memoryManager", MemoryManager(fakeRepo))
        setField(executor, "notebookManager", NotebookManager(fakeRepo))
        setField(executor, "providerManager", providerManager)
        setField(executor, "conversationalEngine", ConversationalIntelligenceEngine())
        return executor
    }

    // -------------------------------------------------------------------------
    // 1. ONLINE AI INTELLIGENCE, CONNECTIVITY, FAILOVER & STREAMING
    // -------------------------------------------------------------------------

    @Test
    fun testGeminiUrlNormalizationAndRoleMerging() {
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent",
            GeminiProviderAdapter.normalizeEndpointUrl(
                "https://generativelanguage.googleapis.com/v1beta/",
                "models/gemini-2.5-flash",
                streaming = false
            )
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse",
            GeminiProviderAdapter.normalizeEndpointUrl(
                "https://generativelanguage.googleapis.com/",
                "gemini-2.5-flash",
                streaming = true
            )
        )

        val turns = GeminiProviderAdapter.normalizeConversationTurns(
            listOf(
                AIChatMessage(role = "assistant", content = "Leading model turn should be dropped"),
                AIChatMessage(role = "user", content = "Hello Babu"),
                AIChatMessage(role = "user", content = "Kaise ho?"),
                AIChatMessage(role = "assistant", content = "Main theek hoon!"),
                AIChatMessage(role = "model", content = "Aap kaise hain?"),
                AIChatMessage(role = "user", content = "Set volume to 50%")
            )
        )
        assertEquals(3, turns.size)
        assertEquals("user", turns[0].first)
        assertEquals("Hello Babu\nKaise ho?", turns[0].second)
        assertEquals("model", turns[1].first)
        assertEquals("Main theek hoon!\nAap kaise hain?", turns[1].second)
        assertEquals("user", turns[2].first)
        assertEquals("Set volume to 50%", turns[2].second)
    }

    @Test
    fun testOpenAICompatibleUrlNormalizationAndOpenRouterHeaders() {
        assertEquals(
            "https://api.x.ai/v1/chat/completions",
            OpenAICompatibleAdapter.normalizeChatCompletionsUrl("https://api.x.ai/v1/")
        )
        assertEquals(
            "https://openrouter.ai/api/v1/chat/completions",
            OpenAICompatibleAdapter.normalizeChatCompletionsUrl("https://openrouter.ai/api/v1/chat/completions")
        )

        val openRouterConfig = ProviderConfig(
            id = "openrouter_custom",
            type = ProviderType.OPENROUTER,
            name = "OpenRouter API",
            baseUrl = "https://openrouter.ai/api/v1",
            modelId = "meta-llama/llama-3.1-8b-instruct",
            priority = 1,
            isEnabled = true,
            hasKeySaved = true
        )
        val headers = OpenAICompatibleAdapter.buildRequestHeaders(openRouterConfig, "sk-or-test-key")
        assertEquals("Bearer sk-or-test-key", headers["Authorization"])
        assertEquals(OpenAICompatibleAdapter.OPENROUTER_REFERER, headers["HTTP-Referer"])
        assertEquals(OpenAICompatibleAdapter.OPENROUTER_TITLE, headers["X-Title"])
    }

    @Test
    fun testAIProviderManagerOfflineFastFailAndLocalModelAllowance() = runTest {
        val monitor = NetworkConnectivityMonitor(context = null)
        monitor.testOverrideStatus = NetworkStatus(
            isConnected = false,
            hasValidatedInternet = false,
            transportType = NetworkTransportType.NONE
        )

        val manager = AIProviderManager(storage, monitor)
        manager.saveApiKey("gemini_default", "fake-gemini-key")

        var adapterInvoked = false
        manager.customAdapterFactory = { cfg ->
            object : AIProviderAdapter {
                override val providerConfig: ProviderConfig = cfg
                override suspend fun generateResponse(request: AIRequest, apiKey: String): AIResult {
                    adapterInvoked = true
                    return AIResult.Success(AIResponse("Local reply", cfg.id, cfg.modelId))
                }
                override suspend fun testConnection(apiKey: String): AIResult =
                    AIResult.Success(AIResponse("OK", cfg.id, cfg.modelId))
            }
        }

        // Cloud provider must fast-fail honestly without calling network adapter when offline
        val offlineCloudResult = manager.sendRequest("What is the weather today?")
        assertTrue(offlineCloudResult is AIResult.Error)
        assertFalse("Cloud adapter must not be invoked when offline", adapterInvoked)
        assertTrue((offlineCloudResult as AIResult.Error).message.contains("No internet", ignoreCase = true))
        assertFalse(manager.onlineStatus.value.isNetworkOnline)

        // Enabling LOCAL_MODEL should still be attempted even when device has no internet
        manager.setProviderEnabled("ollama_local", true)
        val localFallbackResult = manager.sendRequest("Hello local model")
        assertTrue(localFallbackResult is AIResult.Success)
        assertTrue(adapterInvoked)
        assertEquals("ollama_local", (localFallbackResult as AIResult.Success).response.providerId)
    }

    @Test
    fun testAIProviderManagerFailoverAndStreamingTokenDelivery() = runTest {
        val monitor = NetworkConnectivityMonitor(context = null)
        monitor.testOverrideStatus = NetworkStatus(
            isConnected = true,
            hasValidatedInternet = true,
            transportType = NetworkTransportType.WIFI
        )

        val manager = AIProviderManager(storage, monitor)
        manager.saveApiKey("gemini_default", "key-gemini")
        manager.saveApiKey("openrouter_custom", "key-openrouter")
        manager.selectPreferredProvider("gemini_default")

        val attemptedProviders = mutableListOf<String>()
        manager.customAdapterFactory = { cfg ->
            object : AIProviderAdapter {
                override val providerConfig: ProviderConfig = cfg
                override suspend fun generateResponse(request: AIRequest, apiKey: String): AIResult {
                    attemptedProviders.add(cfg.id)
                    return if (cfg.id == "gemini_default") {
                        AIResult.Error(429, "Quota exceeded on Gemini", isRetryable = true)
                    } else {
                        AIResult.Success(AIResponse("OpenRouter backup answer", cfg.id, cfg.modelId))
                    }
                }

                override suspend fun generateStreamingResponse(
                    request: AIRequest,
                    apiKey: String,
                    onToken: (String) -> Unit
                ): AIResult {
                    attemptedProviders.add(cfg.id)
                    return if (cfg.id == "gemini_default") {
                        AIResult.Error(503, "Gemini temporarily unavailable", isRetryable = true)
                    } else {
                        onToken("Namaste ")
                        onToken("dost!")
                        AIResult.Success(AIResponse("Namaste dost!", cfg.id, cfg.modelId))
                    }
                }

                override suspend fun testConnection(apiKey: String): AIResult =
                    AIResult.Success(AIResponse("OK", cfg.id, cfg.modelId))
            }
        }

        val streamedTokens = mutableListOf<String>()
        val result = manager.sendStreamingRequest("Namaste Babu") { token ->
            streamedTokens.add(token)
        }

        assertTrue(result is AIResult.Success)
        val success = result as AIResult.Success
        assertEquals("openrouter_custom", success.response.providerId)
        assertEquals("Namaste dost!", success.response.content)
        assertEquals(listOf("Namaste ", "dost!"), streamedTokens)
        assertEquals(listOf("gemini_default", "openrouter_custom"), attemptedProviders)
        assertTrue(manager.onlineStatus.value.lastCallSucceeded == true)
    }

    @Test
    fun testActionExecutorNetworkStatusAndHonestOnlineQueryFailure() = runTest {
        val monitor = NetworkConnectivityMonitor(context = null)
        val providerManager = AIProviderManager(storage, monitor)
        val executor = createJvmActionExecutor(providerManager)

        // 1. Verify GET_NETWORK_STATUS reports real Wi-Fi & Mobile Data & Offline states
        monitor.testOverrideStatus = NetworkStatus(
            isConnected = true,
            hasValidatedInternet = true,
            transportType = NetworkTransportType.WIFI
        )
        val wifiStatus = executor.execute(AgentAction(actionType = ActionType.GET_NETWORK_STATUS))
        assertTrue(wifiStatus.isSuccess)
        assertTrue(wifiStatus.outputMessage.contains("Wi-Fi", ignoreCase = true))

        monitor.testOverrideStatus = NetworkStatus(
            isConnected = true,
            hasValidatedInternet = true,
            transportType = NetworkTransportType.MOBILE_DATA,
            isMetered = true
        )
        val cellularStatus = executor.execute(AgentAction(actionType = ActionType.GET_NETWORK_STATUS))
        assertTrue(cellularStatus.isSuccess)
        assertTrue(cellularStatus.outputMessage.contains("Mobile Data", ignoreCase = true))

        monitor.testOverrideStatus = NetworkStatus(
            isConnected = false,
            hasValidatedInternet = false,
            transportType = NetworkTransportType.NONE
        )
        val offlineStatus = executor.execute(AgentAction(actionType = ActionType.GET_NETWORK_STATUS))
        assertTrue(offlineStatus.isSuccess)
        assertTrue(offlineStatus.outputMessage.contains("Offline", ignoreCase = true))

        // 2. Verify QUERY_LLM reports honest FAILED status when configured online provider fails
        providerManager.saveApiKey("gemini_default", "key-gemini")
        val queryResult = executor.execute(
            AgentAction(
                actionType = ActionType.QUERY_LLM,
                parameters = mapOf("prompt" to "Explain quantum computing")
            )
        )
        assertFalse("QUERY_LLM must NOT pretend online success when offline/failing", queryResult.isSuccess)
        assertEquals(VerificationStatus.FAILED, queryResult.status)
        assertTrue(
            "Expected honest offline/network failure message, got: ${queryResult.outputMessage}",
            queryResult.outputMessage.contains("Offline", ignoreCase = true) ||
                queryResult.outputMessage.contains("Network Error", ignoreCase = true)
        )
    }

    @Test
    fun testAgentBrainSkipsRedundantPlannerRoundTripForPureConversation() = runTest {
        val monitor = NetworkConnectivityMonitor(context = null)
        monitor.testOverrideStatus = NetworkStatus(
            isConnected = true,
            hasValidatedInternet = true,
            transportType = NetworkTransportType.WIFI
        )
        val providerManager = AIProviderManager(storage, monitor)
        providerManager.saveApiKey("gemini_default", "key-gemini")

        var llmCallCount = 0
        providerManager.customAdapterFactory = { cfg ->
            object : AIProviderAdapter {
                override val providerConfig: ProviderConfig = cfg
                override suspend fun generateResponse(request: AIRequest, apiKey: String): AIResult {
                    llmCallCount++
                    return AIResult.Success(AIResponse("Photosynthesis converts light into energy.", cfg.id, cfg.modelId))
                }
                override suspend fun testConnection(apiKey: String): AIResult =
                    AIResult.Success(AIResponse("OK", cfg.id, cfg.modelId))
            }
        }

        val brain = AgentBrain(
            providerManager = providerManager,
            actionExecutor = createJvmActionExecutor(providerManager),
            workspaceManager = allocateWithoutInit(WorkspaceManager::class.java),
            skillManager = SkillManager(),
            subAgentManager = SubAgentManager()
        )

        val task = brain.planDynamicTaskWithModel("Explain photosynthesis in simple Hinglish")
        assertEquals(0, llmCallCount)
        assertEquals(1, task.actions.size)
        assertEquals(ActionType.QUERY_LLM, task.actions.first().actionType)
    }

    // -------------------------------------------------------------------------
    // 2. INDEPENDENT VAD & WAKE-WORD ENGINE VERIFICATION
    // -------------------------------------------------------------------------

    @Test
    fun testVoiceActivityDetectorLifecycleAndNoiseRejection() {
        val vad = VoiceActivityDetector(
            VadConfig(
                minSpeechRms = 380.0,
                minSnrMarginRatio = 1.45,
                minZcr = 0.02,
                maxZcr = 0.46,
                minVoicedFramesForSpeech = 3,
                silenceHangoverFrames = 4,
                initialNoiseFloorRms = 120.0
            )
        )

        val quietNoiseFrame = ShortArray(320) { i -> if (i % 2 == 0) 90 else -90 }
        val voicedSpeechFrame = ShortArray(320) { i ->
            // ~500 Hz voiced sine wave at 16 kHz => ZCR ~ 0.0625, high RMS ~ 4200
            (6000.0 * sin(2.0 * PI * 500.0 * i / 16000.0)).toInt().toShort()
        }

        // Ambient quiet frames stay SILENCE and adapt noise floor downward
        val initialFloor = vad.noiseFloorRms
        repeat(5) {
            val d = vad.processFrame(quietNoiseFrame)
            assertEquals(VadState.SILENCE, d.state)
            assertFalse(d.isSpeechFrame)
        }
        assertTrue("Noise floor should adapt smoothly toward ambient quiet", vad.noiseFloorRms < initialFloor)

        // Voiced speech frames transition SILENCE -> SPEECH_START -> SPEECH_ACTIVE
        val d1 = vad.processFrame(voicedSpeechFrame)
        assertEquals(VadState.SPEECH_START, d1.state)
        val d2 = vad.processFrame(voicedSpeechFrame)
        assertEquals(VadState.SPEECH_START, d2.state)
        val d3 = vad.processFrame(voicedSpeechFrame)
        assertEquals(VadState.SPEECH_ACTIVE, d3.state)
        assertTrue(d3.isSpeechFrame)

        // Trailing silence transitions SPEECH_ACTIVE -> SPEECH_HANGOVER -> END_OF_UTTERANCE
        val h1 = vad.processFrame(quietNoiseFrame)
        assertEquals(VadState.SPEECH_HANGOVER, h1.state)
        assertFalse(h1.utteranceEnded)
        vad.processFrame(quietNoiseFrame)
        vad.processFrame(quietNoiseFrame)
        val endDecision = vad.processFrame(quietNoiseFrame)
        assertEquals(VadState.END_OF_UTTERANCE, endDecision.state)
        assertTrue(endDecision.utteranceEnded)
        assertEquals(VadState.END_OF_UTTERANCE, vad.vadState.value)
    }

    @Test
    fun testLocalWakeWordEngineExposesIndependentVadAndSupportsRequiredWakePhrases() {
        val engine = LocalWakeWordEngine(context = null)
        assertNotNull(engine.voiceActivityDetector)
        assertEquals(VadState.SILENCE, engine.vadState.value)

        // Default wake configuration includes Hi Nayro, Hey Nayro, and Hi Babu
        val configuredPhrases = engine.getConfiguredWakePhrases().map { it.lowercase() }
        assertTrue(configuredPhrases.any { it.contains("hi nayro") || it.contains("hey nayro") })
        assertTrue(configuredPhrases.any { it.contains("hi babu") || it.contains("hey babu") })
    }

    // -------------------------------------------------------------------------
    // 3. VERIFIED ANDROID DEVICE CONTROL (VOLUME, TOOL REGISTRY & LOCK SCREEN)
    // -------------------------------------------------------------------------

    @Test
    fun testMultilingualVolumeClassificationPlanningVerificationAndLockScreenPolicy() {
        assertEquals(UserIntentType.VOLUME_CONTROL, TaskClassifier.classify("Increase volume").type)
        assertEquals(UserIntentType.VOLUME_CONTROL, TaskClassifier.classify("Volume kam karo").type)
        val setIntent = TaskClassifier.classify("Set media volume to 60%")
        assertEquals(UserIntentType.VOLUME_CONTROL, setIntent.type)
        assertEquals(UserIntentType.VOLUME_CONTROL, TaskClassifier.classify("Mute phone").type)
        assertEquals(UserIntentType.VOLUME_CONTROL, TaskClassifier.classify("आवाज़ बढ़ाओ").type)

        val setTask = TaskPlanner.planTask(setIntent)
        assertEquals(1, setTask.actions.size)
        val action = setTask.actions.first()
        assertEquals(ActionType.CONTROL_VOLUME, action.actionType)
        assertEquals("SET", action.parameters["operation"])
        assertEquals("media", action.parameters["stream"])
        assertEquals("60", action.parameters["levelPercent"])

        // Tool registry & parameter validation
        val toolDef = ToolRegistryManager.getTool(ActionType.CONTROL_VOLUME)
        assertNotNull(toolDef)
        assertEquals("android.control_volume", toolDef?.toolId)
        val (isValid, _) = ToolRegistryManager.validateParameters(action)
        assertTrue(isValid)

        // ActionVerifier confirms verified AudioManager result
        val verifier = ActionVerifier(ScreenIntelligenceManager(allocateWithoutInit(android.content.ContextWrapper::class.java)))
        val verified = verifier.verifyAction(
            action = action,
            executionResult = ActionExecutionResult(
                isSuccess = true,
                outputMessage = "Adjusted media volume (SET): 40% -> 60% (9/15).",
                status = VerificationStatus.VERIFIED
            ),
            preStateSummary = null
        )
        assertEquals(VerificationStatus.VERIFIED, verified.status)

        // Lock screen policy permits safe local volume control
        val stateManager = DeviceOperatingStateManager(context = null)
        stateManager.refreshDeviceOperatingState(isScreenInteractive = false, isKeyguardLocked = true)
        val policy = stateManager.evaluateLockScreenPolicy(action)
        assertEquals(LockScreenPolicyDecision.AllowedWhileLocked, policy)
    }

    // -------------------------------------------------------------------------
    // 4. PERSISTENT ROOM CONVERSATION & NOTE HYDRATION ACROSS RESTART
    // -------------------------------------------------------------------------

    @Test
    fun testAgentRuntimeHydratesConversationAndNotesAcrossRestart() = runTest {
        val fakeRepo = allocateWithoutInit(NayroRepository::class.java)
        setField(
            fakeRepo,
            "conversations",
            flowOf(
                listOf(
                    ConversationEntity(
                        id = 1L,
                        sender = "user",
                        content = "Remind me to buy milk at 6 PM",
                        timestamp = 1_000L
                    ),
                    ConversationEntity(
                        id = 2L,
                        sender = "babu",
                        content = "Sure! I noted buying milk for 6 PM.",
                        timestamp = 2_000L
                    )
                )
            )
        )
        setField(
            fakeRepo,
            "notes",
            flowOf(
                listOf(
                    NoteEntity(
                        id = 1L,
                        title = "Buy milk at 6 PM",
                        content = "2 liters full cream",
                        category = "Errands"
                    )
                )
            )
        )

        // Fresh AgentRuntime simulates cold process restart
        val freshRuntime = allocateWithoutInit(AgentRuntime::class.java)
        setField(freshRuntime, "repository", fakeRepo)
        setField(freshRuntime, "continuityManager", ConversationContinuityManager())
        setField(freshRuntime, "conversationalEngine", ConversationalIntelligenceEngine())

        val restoredCount = freshRuntime.hydrateMemoryAndConversationHistoryFromStorage(maxTurns = 12)
        assertEquals(2, restoredCount)

        val continuityTurns = freshRuntime.continuityManager.getRecentTurns(10)
        assertEquals(2, continuityTurns.size)
        assertEquals("user", continuityTurns[0].role)
        assertEquals("Remind me to buy milk at 6 PM", continuityTurns[0].content)
        assertEquals("assistant", continuityTurns[1].role)

        val engineHistory = freshRuntime.conversationalEngine.getRecentHistory(10)
        assertEquals(2, engineHistory.size)
        assertEquals("Sure! I noted buying milk for 6 PM.", engineHistory[1].content)

        val dailyPlan = freshRuntime.conversationalEngine.getDailyPlan()
        assertTrue(dailyPlan.any { it.title == "Buy milk at 6 PM" })
    }

    // -------------------------------------------------------------------------
    // 5. PERSONAL INTELLIGENCE SYSTEM & NATURAL-LANGUAGE PREFERENCE COMMANDS
    // -------------------------------------------------------------------------

    @Test
    fun testPersonalIntelligenceManagersAndNaturalLanguagePreferenceCommands() {
        val store = com.example.core.identity.AssistantIdentityStore(context = null)
        val hub = com.example.core.identity.PersonalIntelligenceHub(store)

        // 1. Change assistant name via natural language
        val renameRes = hub.preferenceCommandHandler.tryHandleCommand("Change your name to QT")
        assertTrue(renameRes.handled)
        assertTrue(renameRes.requiresVoiceSync)
        assertEquals("QT", hub.identityManager.getAssistantName())
        assertTrue(hub.wakeWordProfileManager.getWakePhrases().any { it.equals("Hi QT", ignoreCase = true) })

        // 2. Change user addressing via natural language
        val addrRes = hub.preferenceCommandHandler.tryHandleCommand("Call me Boss")
        assertTrue(addrRes.handled)
        assertEquals("Boss", hub.userAddressingManager.getUserAddress())

        // 3. Add custom wake word
        val wakeRes = hub.preferenceCommandHandler.tryHandleCommand("Add wake word Hey Wavy")
        assertTrue(wakeRes.handled)
        assertTrue(hub.wakeWordProfileManager.getWakePhrases().any { it.equals("Hey Wavy", ignoreCase = true) })

        // 4. Voice gender, speed & pitch
        val genderRes = hub.preferenceCommandHandler.tryHandleCommand("Switch voice to male")
        assertTrue(genderRes.handled)
        assertEquals(com.example.core.identity.VoiceGender.MALE, store.getCurrentProfile().voiceGender)

        val speedRes = hub.preferenceCommandHandler.tryHandleCommand("Speak faster")
        assertTrue(speedRes.handled)
        assertTrue(store.getCurrentProfile().speechRate > 1.0f)

        val pitchRes = hub.preferenceCommandHandler.tryHandleCommand("Higher pitch")
        assertTrue(pitchRes.handled)
        assertTrue(store.getCurrentProfile().voicePitch > 1.0f)

        // 5. Language, Personality, Response Length & Memory ON/OFF
        assertTrue(hub.preferenceCommandHandler.tryHandleCommand("Talk in Hinglish").handled)
        assertEquals(
            com.example.core.identity.ConversationLanguageMode.HINGLISH,
            store.getCurrentProfile().conversationLanguageMode
        )

        assertTrue(hub.preferenceCommandHandler.tryHandleCommand("Be funny").handled)
        assertEquals(com.example.core.identity.PersonalityMode.FUNNY, store.getCurrentProfile().personalityMode)

        assertTrue(hub.preferenceCommandHandler.tryHandleCommand("Keep answers short").handled)
        assertEquals(com.example.core.identity.ResponseLengthMode.SHORT, store.getCurrentProfile().responseLength)

        assertTrue(hub.preferenceCommandHandler.tryHandleCommand("Turn off memory").handled)
        assertFalse(hub.userPreferenceMemory.isMemoryEnabled())

        // 6. Independent Floating Icon vs Background Voice toggles
        val floatRes = hub.preferenceCommandHandler.tryHandleCommand("Turn on floating icon")
        assertTrue(floatRes.handled)
        assertEquals(true, floatRes.floatingIconToggle)
        assertFalse(store.getCurrentProfile().isWakeWordEnabled)

        // 7. Personalized system prompt reflects active identity, addressing, style & language
        val prompt = hub.adaptiveConversationEngine.buildPersonalizedSystemPrompt("Kya haal hai?")
        assertTrue(prompt.contains("QT"))
        assertTrue(prompt.contains("Boss"))
        assertTrue(prompt.contains("Hinglish", ignoreCase = true))
        assertTrue(prompt.contains("concise", ignoreCase = true))
    }

    // -------------------------------------------------------------------------
    // 6. REAL BROWSER & INTERNET AGENT VERIFICATION
    // -------------------------------------------------------------------------

    @Test
    fun testBrowserAutomationSafetyExtractionParsingAndPlanning() = runTest {
        // SSRF protection blocks local/private/non-HTTP URLs
        assertTrue(com.example.core.browser.BrowserAutomationManager.normalizeAndValidateUrl("file:///etc/passwd").isFailure)
        assertTrue(com.example.core.browser.BrowserAutomationManager.normalizeAndValidateUrl("http://127.0.0.1/admin").isFailure)
        assertTrue(com.example.core.browser.BrowserAutomationManager.normalizeAndValidateUrl("http://192.168.1.1/").isFailure)
        assertEquals(
            "https://wikipedia.org/",
            com.example.core.browser.BrowserAutomationManager.normalizeAndValidateUrl("wikipedia.org").getOrNull()
        )

        // HTML readable text extraction strips scripts & styles
        val (title, text) = com.example.core.browser.BrowserAutomationManager.extractReadableTextFromHtml(
            "<html><head><title>Android AI</title><style>body{color:red}</style></head><body><script>alert(1)</script><p>NAYRO Autonomous Agent works online.</p></body></html>"
        )
        assertEquals("Android AI", title)
        assertTrue(text.contains("NAYRO Autonomous Agent works online."))
        assertFalse(text.contains("alert(1)"))

        // DuckDuckGo HTML hit parser
        val sampleDdg = """
            <a class="result__a" href="https://duckduckgo.com/l/?uddg=https%3A%2F%2Fkotlinlang.org%2F">Kotlin Official</a>
            <a class="result__snippet">Modern concise multiplatform language.</a>
        """.trimIndent()
        val hits = com.example.core.browser.BrowserAutomationManager.parseDuckDuckGoHtmlHits(sampleDdg)
        assertEquals(1, hits.size)
        assertEquals("https://kotlinlang.org/", hits.first().url)
        assertEquals("Kotlin Official", hits.first().title)

        // Browser intent classification, planning & ToolRegistry registration
        val openIntent = TaskClassifier.classify("Open wikipedia.org in Chrome")
        assertEquals(UserIntentType.BROWSER_TASK, openIntent.type)
        val openTask = TaskPlanner.planTask(openIntent)
        assertEquals(ActionType.BROWSER_OPEN_URL, openTask.actions.first().actionType)

        val compareIntent = TaskClassifier.classify("Compare multiple web sources on Kotlin Coroutines")
        assertEquals(UserIntentType.BROWSER_TASK, compareIntent.type)
        assertEquals(ActionType.BROWSER_COMPARE_SOURCES, TaskPlanner.planTask(compareIntent).actions.first().actionType)

        val dlIntent = TaskClassifier.classify("Download https://example.com/report.pdf")
        assertEquals(UserIntentType.BROWSER_TASK, dlIntent.type)
        assertEquals(ActionType.BROWSER_DOWNLOAD_FILE, TaskPlanner.planTask(dlIntent).actions.first().actionType)

        assertNotNull(ToolRegistryManager.getTool(ActionType.BROWSER_OPEN_URL))
        assertNotNull(ToolRegistryManager.getTool(ActionType.BROWSER_COMPARE_SOURCES))
        assertNotNull(ToolRegistryManager.getTool(ActionType.BROWSER_FILL_FORM))
        assertNotNull(ToolRegistryManager.getTool(ActionType.BROWSER_DOWNLOAD_FILE))

        // Browser form filling fails honestly when Accessibility Service is not enabled
        val browserManager = com.example.core.browser.BrowserAutomationManager(context = null)
        val formRes = browserManager.fillWebFormViaAccessibility("Email", "user@example.com")
        assertFalse(formRes.isSuccess)
        assertEquals(VerificationStatus.REQUIRES_PERMISSION, formRes.status)
    }

    // -------------------------------------------------------------------------
    // 7. SELF-RECOVERY ENGINE & ANDROID 10-16 COMPATIBILITY MATRIX
    // -------------------------------------------------------------------------

    @Test
    fun testErrorRecoveryEngineStrategiesAndRetryBounds() {
        val recovery = com.example.core.agent.ErrorRecoveryEngine(maxRecoveryAttemptsPerStep = 2)

        // API quota -> failover when backup provider available
        val apiPlan = recovery.planRecovery(
            action = AgentAction(actionType = ActionType.QUERY_LLM),
            result = ActionExecutionResult(false, "HTTP 429 quota exceeded", VerificationStatus.FAILED),
            attemptNumber = 0,
            hasSecondaryAiProvider = true
        )
        assertEquals(com.example.core.agent.FailureDomain.API_ERROR_OR_QUOTA, apiPlan.failureDomain)
        assertEquals(com.example.core.agent.RecoveryStrategy.FAILOVER_PROVIDER, apiPlan.strategy)
        assertTrue(apiPlan.canRetry)

        // UI element missing -> scroll and retry once
        val uiPlan = recovery.planRecovery(
            action = AgentAction(actionType = ActionType.CLICK_ELEMENT, target = "Submit"),
            result = ActionExecutionResult(false, "Could not locate element Submit", VerificationStatus.FAILED),
            attemptNumber = 0
        )
        assertEquals(com.example.core.agent.RecoveryStrategy.SCROLL_AND_RETRY_UI, uiPlan.strategy)
        assertEquals(ActionType.SCROLL_FORWARD, uiPlan.alternativeAction?.actionType)

        // Retries exhausted -> abort honestly (never loop infinitely or fake success)
        val exhaustedPlan = recovery.planRecovery(
            action = AgentAction(actionType = ActionType.CLICK_ELEMENT, target = "Submit"),
            result = ActionExecutionResult(false, "Could not locate element Submit", VerificationStatus.FAILED),
            attemptNumber = 2
        )
        assertEquals(com.example.core.agent.RecoveryStrategy.ABORT_HONESTLY, exhaustedPlan.strategy)
        assertFalse(exhaustedPlan.canRetry)

        // Browser navigation error -> HTTP search fallback
        val browserPlan = recovery.planRecovery(
            action = AgentAction(actionType = ActionType.BROWSER_OPEN_URL, target = "https://kotlinlang.org"),
            result = ActionExecutionResult(false, "Chrome unavailable", VerificationStatus.FAILED),
            attemptNumber = 0
        )
        assertEquals(com.example.core.agent.RecoveryStrategy.HTTP_FETCH_FALLBACK, browserPlan.strategy)
        assertEquals(ActionType.WEB_SEARCH, browserPlan.alternativeAction?.actionType)
    }

    @Test
    fun testAndroid10Through16CompatibilityMatrix() {
        val android10 = com.example.core.system.AndroidCompatibilityManager.evaluate(sdkInt = 29, manufacturer = "Google")
        assertTrue(android10.isSupportedAndroidVersion)
        assertFalse(android10.requiresPostNotificationsPermission)
        assertFalse(android10.requiresTypedForegroundServicePermissions)
        assertTrue(android10.supportsCallScreeningRole)

        val android14Realme = com.example.core.system.AndroidCompatibilityManager.evaluate(sdkInt = 34, manufacturer = "realme")
        assertTrue(android14Realme.requiresPostNotificationsPermission)
        assertTrue(android14Realme.requiresTypedForegroundServicePermissions)
        assertTrue(android14Realme.isAggressiveBackgroundOem)
        assertTrue(android14Realme.oemGuidance.contains("ColorOS", ignoreCase = true))

        val android16 = com.example.core.system.AndroidCompatibilityManager.evaluate(sdkInt = 36, manufacturer = "Google")
        assertTrue(android16.isSupportedAndroidVersion)
        assertEquals("Android 16 (API 36)", android16.androidVersionLabel)
        assertEquals(2, android16.requiredRuntimePermissionsForCoreVoice.size)
    }
}
