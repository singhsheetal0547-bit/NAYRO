package com.example

import com.example.core.agent.ActionExecutionResult
import com.example.core.agent.ActionType
import com.example.core.agent.AgentAction
import com.example.core.agent.AgentTask
import com.example.core.agent.RiskLevel
import com.example.core.agent.SelfImprovementEngine
import com.example.core.agent.TaskClassifier
import com.example.core.agent.TaskPlanner
import com.example.core.agent.UserFeedbackSignal
import com.example.core.agent.UserIntentType
import com.example.core.agent.VerificationStatus
import com.example.core.agent.tools.DynamicToolPlugin
import com.example.core.agent.tools.SandboxedScriptRunner
import com.example.core.agent.tools.StructuredDataToolkit
import com.example.core.agent.tools.ToolRegistryManager
import com.example.core.memory.MemoryCategory
import com.example.core.memory.SemanticMemoryEngine
import com.example.core.memory.SemanticMemoryTier
import com.example.core.provider.AIProviderManager
import com.example.core.provider.ProviderCapability
import com.example.core.provider.ProviderConfig
import com.example.core.provider.ProviderType
import com.example.core.provider.TaskRoutingHint
import com.example.core.provider.adapter.AIProviderAdapter
import com.example.core.provider.model.AIRequest
import com.example.core.provider.model.AIResponse
import com.example.core.provider.model.AIResult
import com.example.data.local.entities.MemoryEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Section 15 — Automated JVM Unit Test Suite for NAYRO MAHA AGENT:
 * 1. Context-aware multi-provider routing, vision selection, 429 cooldown backoff & transient retry
 * 2. Persistent semantic memory (cross-lingual retrieval, consolidation, summarization, workflow memory, privacy)
 * 3. Self-improving feedback & execution analytics engine (honest non-retraining metrics + adaptive lessons)
 * 4. Play-policy compliant sandboxed code runner (Python/JS, infinite-loop cap, forbidden OS call block)
 * 5. Structured CSV/JSON data analysis toolkit
 * 6. Extensible runtime DynamicToolPlugin registration & execution
 * 7. Emotional Hinglish mood-aware YouTube assistant flow classification & planning
 */
class MahaAgentComprehensiveTests {

    @After
    fun tearDown() {
        ToolRegistryManager.resetToDefaults()
    }

    // =========================================================================
    // 1. CONTEXT-AWARE MULTI-PROVIDER ROUTING & RATE-LIMIT RESILIENCE
    // =========================================================================

    @Test
    fun testContextAwareVisionRoutingPrefersVisionCapableProvider() {
        val manager = AIProviderManager(encryptedStorageManager = null, connectivityMonitor = null)
        // Configure Priority 1 as text-only DeepSeek and Priority 2 as Vision-capable Gemini
        manager.updateProvider(
            ProviderConfig(
                id = "deepseek_custom",
                type = ProviderType.DEEPSEEK,
                name = "DeepSeek Text",
                baseUrl = "https://api.deepseek.com/v1",
                modelId = "deepseek-chat",
                priority = 1,
                isEnabled = true,
                hasKeySaved = true,
                capabilities = setOf(ProviderCapability.TEXT_GENERATION, ProviderCapability.CODING)
            )
        )
        manager.updateProvider(
            ProviderConfig(
                id = "gemini_default",
                type = ProviderType.GEMINI,
                name = "Gemini Vision",
                baseUrl = "https://generativelanguage.googleapis.com",
                modelId = "gemini-2.5-flash",
                priority = 2,
                isEnabled = true,
                hasKeySaved = true
            )
        )
        // In unit test without Android KeyStore, simulate saved keys via custom LOCAL_MODEL-like test hook:
        // updateProvider triggers refreshActiveProvider; let's test with LOCAL_MODEL types which don't require Keystore
        manager.updateProvider(
            ProviderConfig(
                id = "text_only_local",
                type = ProviderType.LOCAL_MODEL,
                name = "Text Only Model",
                baseUrl = "http://127.0.0.1:11434/v1",
                modelId = "llama3",
                priority = 1,
                isEnabled = true,
                hasKeySaved = true,
                capabilities = setOf(ProviderCapability.TEXT_GENERATION, ProviderCapability.FAST_RESPONSE)
            )
        )
        manager.updateProvider(
            ProviderConfig(
                id = "vision_local",
                type = ProviderType.LOCAL_MODEL,
                name = "Vision Multimodal Model",
                baseUrl = "http://127.0.0.1:11434/v1",
                modelId = "llava-vision",
                priority = 2,
                isEnabled = true,
                hasKeySaved = true,
                capabilities = setOf(ProviderCapability.TEXT_GENERATION, ProviderCapability.VISION)
            )
        )

        val visionCandidates = manager.selectOrderedCandidatesForTask(
            hint = TaskRoutingHint.VISION,
            requiresVision = true
        )
        assertTrue("Vision candidates must not be empty", visionCandidates.isNotEmpty())
        assertEquals("Vision request must select vision-capable provider first", "vision_local", visionCandidates.first().id)

        val fastCandidates = manager.selectOrderedCandidatesForTask(
            hint = TaskRoutingHint.FAST_VOICE,
            requiresVision = false
        )
        assertEquals("Fast non-vision request honors priority 1 fast provider", "text_only_local", fastCandidates.first().id)
    }

    @Test
    fun testRateLimit429TriggersCooldownAndAutomaticFailover() = runBlocking {
        var fakeClockMs = 1_000_000L
        val manager = AIProviderManager(encryptedStorageManager = null, connectivityMonitor = null)
        manager.currentTimeProvider = { fakeClockMs }
        manager.maxTransientRetriesPerProvider = 0

        manager.updateProvider(
            ProviderConfig(
                id = "primary_local",
                type = ProviderType.LOCAL_MODEL,
                name = "Primary Provider",
                baseUrl = "http://127.0.0.1:11434/v1",
                modelId = "primary-v1",
                priority = 1,
                isEnabled = true,
                hasKeySaved = true
            )
        )
        manager.updateProvider(
            ProviderConfig(
                id = "backup_local",
                type = ProviderType.LOCAL_MODEL,
                name = "Backup Provider",
                baseUrl = "http://127.0.0.1:11435/v1",
                modelId = "backup-v1",
                priority = 2,
                isEnabled = true,
                hasKeySaved = true
            )
        )

        val callOrder = mutableListOf<String>()
        manager.customAdapterFactory = { cfg ->
            object : AIProviderAdapter {
                override val providerConfig: ProviderConfig = cfg
                override suspend fun generateResponse(request: AIRequest, apiKey: String): AIResult {
                    callOrder.add(cfg.id)
                    return if (cfg.id == "primary_local") {
                        AIResult.Error(429, "Rate limit exceeded", isRetryable = true)
                    } else {
                        AIResult.Success(AIResponse("Backup succeeded", cfg.modelId, cfg.name))
                    }
                }
            }
        }

        // First call hits primary -> 429 -> seamlessly fails over to backup
        val firstRes = manager.sendTaskAwareRequest("Hello world", TaskRoutingHint.GENERAL)
        assertTrue(firstRes is AIResult.Success)
        assertEquals("Backup succeeded", (firstRes as AIResult.Success).response.content)
        assertEquals(listOf("primary_local", "backup_local"), callOrder)

        // Health telemetry must record rate limit hit & active cooldown on primary
        val primaryHealth = manager.getProviderHealthSnapshots()["primary_local"]
        assertNotNull(primaryHealth)
        assertEquals(1, primaryHealth!!.rateLimitHits)
        assertTrue(primaryHealth.isInCooldown(fakeClockMs))

        // Second call during cooldown goes DIRECTLY to backup without wasting time on rate-limited primary
        callOrder.clear()
        val secondRes = manager.sendTaskAwareRequest("Second request", TaskRoutingHint.GENERAL)
        assertTrue(secondRes is AIResult.Success)
        assertEquals(listOf("backup_local"), callOrder)

        // Advance clock past cooldown (60s) -> primary is eligible again
        fakeClockMs += 60_000L
        assertFalse(primaryHealth.isInCooldown(fakeClockMs))
    }

    @Test
    fun testTransient503RetryRecoversBeforeFailover() = runBlocking {
        val manager = AIProviderManager(encryptedStorageManager = null, connectivityMonitor = null)
        manager.maxTransientRetriesPerProvider = 1
        manager.updateProvider(
            ProviderConfig(
                id = "flaky_local",
                type = ProviderType.LOCAL_MODEL,
                name = "Flaky Primary",
                baseUrl = "http://127.0.0.1:11434/v1",
                modelId = "flaky-v1",
                priority = 1,
                isEnabled = true,
                hasKeySaved = true
            )
        )

        var attempts = 0
        manager.customAdapterFactory = { cfg ->
            object : AIProviderAdapter {
                override val providerConfig: ProviderConfig = cfg
                override suspend fun generateResponse(request: AIRequest, apiKey: String): AIResult {
                    attempts++
                    return if (attempts == 1) {
                        AIResult.Error(503, "Temporary service unavailable", isRetryable = true)
                    } else {
                        AIResult.Success(AIResponse("Recovered on retry 2", cfg.modelId, cfg.name))
                    }
                }
            }
        }

        val res = manager.sendTaskAwareRequest("Check status", TaskRoutingHint.GENERAL)
        assertTrue(res is AIResult.Success)
        assertEquals(2, attempts)
        assertEquals("Recovered on retry 2", (res as AIResult.Success).response.content)
    }

    // =========================================================================
    // 2. SEMANTIC LONG-TERM MEMORY, CONSOLIDATION, SUMMARIZATION & WORKFLOWS
    // =========================================================================

    @Test
    fun testCrossLingualSemanticRetrievalMatchesHinglishMoodQueryToEnglishMusicMemory() {
        val engine = SemanticMemoryEngine(encryptedStorageManager = null)
        engine.upsertRecord(
            tier = SemanticMemoryTier.LONG_TERM,
            category = MemoryCategory.USER_PREFERENCE.name,
            key = "favourite_song",
            content = "Arijit Singh Tum Hi Ho romantic melody",
            tags = setOf("music", "song")
        )
        engine.upsertRecord(
            tier = SemanticMemoryTier.LONG_TERM,
            category = MemoryCategory.USER_PREFERENCE.name,
            key = "office_address",
            content = "Cyber City Gurgaon Sector 24",
            tags = setOf("work", "location")
        )

        val results = engine.semanticSearch("aaj mera mood off hai koi achha gaana sunao")
        assertTrue("Semantic search must retrieve music memory for Hinglish mood/gaana query", results.isNotEmpty())
        assertEquals("favourite_song", results.first().record.key)
    }

    @Test
    fun testShortTermMemoryExpirationPromotionConsolidationAndUserControls() {
        var clockMs = 10_000L
        val engine = SemanticMemoryEngine(encryptedStorageManager = null)
        engine.currentTimeProvider = { clockMs }

        // 1. Short-term entry expires after TTL
        engine.upsertRecord(
            tier = SemanticMemoryTier.SHORT_TERM,
            category = "SHORT_TERM_MEMORY",
            key = "temp_wifi_note",
            content = "Connected to cafe wifi",
            ttlMs = 5_000L
        )
        // 2. Repeated short-term entry gets promoted to LONG_TERM automatically
        engine.upsertRecord(
            tier = SemanticMemoryTier.SHORT_TERM,
            category = "SHORT_TERM_MEMORY",
            key = "preferred_language",
            content = "User prefers Hinglish replies",
            ttlMs = 5_000L
        )
        val promoted = engine.upsertRecord(
            tier = SemanticMemoryTier.SHORT_TERM,
            category = "SHORT_TERM_MEMORY",
            key = "preferred_language",
            content = "User prefers Hinglish replies",
            ttlMs = 5_000L
        )
        assertEquals(SemanticMemoryTier.LONG_TERM, promoted?.tier)

        // Advance time past short-term TTL and consolidate
        clockMs += 10_000L
        val report = engine.consolidateMemory()
        assertEquals(1, report.expiredPurged)
        assertTrue(engine.getAllRecords().none { it.key == "temp_wifi_note" })
        assertTrue(engine.getAllRecords().any { it.key == "preferred_language" && it.tier == SemanticMemoryTier.LONG_TERM })

        // User update & delete controls
        assertTrue(engine.updateRecord("preferred_language", "User prefers concise Hinglish replies"))
        assertEquals(
            "User prefers concise Hinglish replies",
            engine.getAllRecords().first { it.key == "preferred_language" }.content
        )
        assertTrue(engine.deleteRecord("preferred_language"))
        assertTrue(engine.getAllRecords().isEmpty())
    }

    @Test
    fun testConversationSummarizationAndWorkflowMemory() {
        val engine = SemanticMemoryEngine(encryptedStorageManager = null)
        val summary = engine.summarizeAndPersistConversation(
            turns = listOf(
                "user" to "Can you check my calendar meetings tomorrow?",
                "assistant" to "You have Design Review at 10 AM.",
                "user" to "Remind me 15 minutes before the design review.",
                "assistant" to "Reminder scheduled for 9:45 AM."
            ),
            sessionLabel = "morning_plan"
        )
        assertNotNull(summary)
        assertEquals(SemanticMemoryTier.CONVERSATION_SUMMARY, summary!!.tier)
        assertTrue(summary.content.contains("4 turns"))

        val workflow = engine.recordWorkflowOutcome(
            goal = "Play favourite song on YouTube",
            actionSequence = listOf("CONTROL_MEDIA"),
            succeeded = true,
            details = "Playback verified active"
        )
        assertNotNull(workflow)
        assertEquals(SemanticMemoryTier.WORKFLOW_SUCCESS, workflow!!.tier)

        // Room MemoryEntity fallback integration
        val roomFallback = listOf(
            MemoryEntity(category = "USER_PREFERENCE", keyName = "coffee_pref", memoryValue = "Black coffee no sugar")
        )
        val coffeeHits = engine.semanticSearch("make me some coffee", additionalRoomMemories = roomFallback)
        assertTrue(coffeeHits.any { it.record.key == "coffee_pref" })
    }

    // =========================================================================
    // 3. SELF-IMPROVING FEEDBACK-DRIVEN INTELLIGENCE ENGINE
    // =========================================================================

    @Test
    fun testSelfImprovementEngineTracksMetricsRecurringFailuresAndHonestDisclaimer() {
        val engine = SelfImprovementEngine(encryptedStorageManager = null)

        // Record successes for calculator
        engine.recordStepOutcome(ActionType.CALCULATE_LOCAL, VerificationStatus.VERIFIED, 12L, "42")
        engine.recordStepOutcome(ActionType.CALCULATE_LOCAL, VerificationStatus.VERIFIED, 18L, "99")
        assertEquals(1.0f, engine.getToolReliabilityScore(ActionType.CALCULATE_LOCAL), 0.01f)

        // Record recurring permission failures for READ_SMS -> should synthesize a [CAUTION] lesson
        engine.recordStepOutcome(ActionType.READ_SMS, VerificationStatus.FAILED, 25L, "READ_SMS permission denied by user")
        engine.recordStepOutcome(ActionType.READ_SMS, VerificationStatus.FAILED, 30L, "READ_SMS permission denied by user")
        assertTrue(engine.getToolReliabilityScore(ActionType.READ_SMS) < 0.2f)

        // Record user correction
        engine.recordUserFeedback(
            taskId = "task-1",
            userPrompt = "play a song for my mood",
            signal = UserFeedbackSignal.CORRECTION,
            correctionNote = "Prefer acoustic Arijit Singh songs when mood is low"
        )

        val guidance = engine.buildAdaptivePlanningGuidance("mood song read_sms")
        assertTrue("Adaptive guidance should include recurring failure warning", guidance.contains("[CAUTION]"))
        assertTrue("Adaptive guidance should include user correction", guidance.contains("acoustic Arijit Singh"))

        // Task outcome analytics & honest transparency notice
        val sampleTask = AgentTask(
            userInstruction = "Calculate 2 + 2",
            resolvedIntent = "LOCAL_CALCULATOR",
            actions = listOf(AgentAction(actionType = ActionType.CALCULATE_LOCAL))
        )
        engine.recordTaskOutcome(sampleTask, VerificationStatus.VERIFIED, "4")

        val report = engine.getPerformanceReport()
        assertEquals(1, report.totalTasksObserved)
        assertEquals(1.0f, report.verifiedTaskSuccessRate, 0.01f)
        assertTrue(
            "Report must transparently disclaim LLM weight retraining",
            report.transparencyNotice.contains("NOT retrained", ignoreCase = true)
        )
    }

    // =========================================================================
    // 4. SANDBOXED CODE RUNNER (PLAY-POLICY COMPLIANT), DATA TOOLKIT & PLUGINS
    // =========================================================================

    @Test
    fun testSandboxedPythonAndJavaScriptExecutionAndSecurityGuards() {
        // 1. Safe Python computation with loop + conditional + print
        val pyRes = SandboxedScriptRunner.execute(
            code = """
                total = 0
                for i in range(1, 6):
                    if i % 2 == 1:
                        total += i
                print("odd_sum =", total)
            """.trimIndent(),
            language = "python"
        )
        assertTrue("Python sandbox script should succeed: ${pyRes.errorMessage}", pyRes.isSuccess)
        assertEquals("odd_sum = 9", pyRes.stdout)
        assertEquals("9", pyRes.variables["total"])

        // 2. Safe JavaScript computation
        val jsRes = SandboxedScriptRunner.execute(
            code = """
                let nums = [10, 20, 30];
                let avg = sum(nums) / len(nums);
                console.log("avg:", avg);
            """.trimIndent(),
            language = "javascript"
        )
        assertTrue("JS sandbox script should succeed: ${jsRes.errorMessage}", jsRes.isSuccess)
        assertEquals("avg: 20", jsRes.stdout)

        // 3. Forbidden OS / Shell / File access MUST be blocked
        val blockedOs = SandboxedScriptRunner.execute("import os\nprint(os.listdir('/'))", "python")
        assertFalse(blockedOs.isSuccess)
        assertTrue(blockedOs.errorMessage.orEmpty().contains("blocked", ignoreCase = true))

        val blockedShell = SandboxedScriptRunner.execute("Runtime.getRuntime().exec('ls')", "javascript")
        assertFalse(blockedShell.isSuccess)

        // 4. Infinite loop MUST terminate cleanly at step limit
        val infiniteLoop = SandboxedScriptRunner.execute(
            code = """
                x = 0
                while True:
                    x += 1
            """.trimIndent(),
            language = "python",
            maxSteps = 120
        )
        assertFalse(infiniteLoop.isSuccess)
        assertTrue(infiniteLoop.errorMessage.orEmpty().contains("step limit", ignoreCase = true))
    }

    @Test
    fun testStructuredDataToolkitParsesCsvAndJsonWithFilteringAndStats() {
        val csv = """
            item,category,amount
            Coffee,Food,150
            Lunch,Food,350
            Cab,Travel,500
        """.trimIndent()

        val fullAnalysis = StructuredDataToolkit.analyzeText(csv, formatHint = "csv")
        assertTrue(fullAnalysis.isSuccess)
        assertEquals(3, fullAnalysis.rowCount)
        val amountStats = fullAnalysis.stats.first { it.columnName == "amount" }
        assertEquals(1000.0, amountStats.sum!!, 0.01)
        assertEquals(150.0, amountStats.min!!, 0.01)
        assertEquals(500.0, amountStats.max!!, 0.01)
        assertTrue(fullAnalysis.reportMarkdown.contains("| amount |"))

        // Filtered analysis
        val foodOnly = StructuredDataToolkit.analyzeText(csv, formatHint = "csv", filterExpr = "category=Food")
        assertTrue(foodOnly.isSuccess)
        assertEquals(2, foodOnly.rowCount)
        assertEquals(500.0, foodOnly.stats.first { it.columnName == "amount" }.sum!!, 0.01)

        // JSON array analysis
        val json = """[{"name":"Alpha","score":80},{"name":"Beta","score":100}]"""
        val jsonAnalysis = StructuredDataToolkit.analyzeText(json, formatHint = "json")
        assertTrue(jsonAnalysis.isSuccess)
        assertEquals(90.0, jsonAnalysis.stats.first { it.columnName == "score" }.avg!!, 0.01)
    }

    @Test
    fun testDynamicToolPluginRegistrationAndExecutionWithoutRebuildingEnum() = runBlocking {
        ToolRegistryManager.registerPlugin(
            DynamicToolPlugin(
                pluginId = "unit_converter",
                displayName = "Unit Converter Plugin",
                description = "Converts kilometers to miles dynamically.",
                parametersSchema = mapOf("km" to "Double - Distance in kilometers"),
                requiredParams = setOf("km"),
                riskLevel = RiskLevel.R0_LOCAL_READONLY
            ) { params ->
                val km = params["km"]?.toDoubleOrNull()
                    ?: return@DynamicToolPlugin ActionExecutionResult(false, "Invalid km value", VerificationStatus.FAILED)
                val miles = km * 0.621371
                ActionExecutionResult(true, String.format(java.util.Locale.US, "%.2f km = %.2f miles", km, miles), VerificationStatus.VERIFIED)
            }
        )

        assertTrue(ToolRegistryManager.generateLlmToolPromptSummary().contains("unit_converter"))

        val okRes = ToolRegistryManager.executePlugin("unit_converter", mapOf("km" to "10"))
        assertTrue(okRes.isSuccess)
        assertTrue(okRes.outputMessage.contains("6.21 miles"))

        val missingParamRes = ToolRegistryManager.executePlugin("unit_converter", emptyMap())
        assertFalse(missingParamRes.isSuccess)
        assertEquals(VerificationStatus.FAILED, missingParamRes.status)

        assertTrue(ToolRegistryManager.unregisterPlugin("unit_converter"))
        val unknownRes = ToolRegistryManager.executePlugin("unit_converter", mapOf("km" to "10"))
        assertEquals(VerificationStatus.NOT_SUPPORTED, unknownRes.status)
    }

    // =========================================================================
    // 5. NATURAL EMOTIONAL HINGLISH MOOD-AWARE YOUTUBE FLOW (SECTION 8)
    // =========================================================================

    @Test
    fun testEmotionalHinglishMoodYouTubeRequestClassifiesAndPlansProperly() {
        val prompt = "Hey Babu, aaj mera mood off hai. Mere liye YouTube par koi achha gaana chala do."
        val resolved = TaskClassifier.classify(prompt)

        assertEquals(UserIntentType.MEDIA_CONTROL, resolved.type)
        assertEquals("com.google.android.youtube", resolved.targetAppOrEntity)
        assertEquals("PLAY_BY_GENRE_OR_MOOD", resolved.section)
        assertTrue(
            "Mood-off generic request should produce uplifting music query, got: ${resolved.searchQueryOrContent}",
            resolved.searchQueryOrContent.orEmpty().contains("uplifting", ignoreCase = true) ||
                resolved.searchQueryOrContent.orEmpty().contains("feel good", ignoreCase = true)
        )

        val plannedTask = TaskPlanner.planTask(resolved)
        assertEquals(1, plannedTask.actions.size)
        assertEquals(ActionType.CONTROL_MEDIA, plannedTask.actions.first().actionType)
        assertEquals("PLAY_BY_GENRE_OR_MOOD", plannedTask.actions.first().parameters["operation"])
        assertEquals("com.google.android.youtube", plannedTask.actions.first().parameters["app"])
    }
}
