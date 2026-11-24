package com.example

import com.example.core.agent.ActionType
import com.example.core.agent.TaskClassifier
import com.example.core.agent.TaskPlanner
import com.example.core.agent.UserIntentType
import com.example.core.agent.VerificationStatus
import com.example.core.agent.tools.ToolRegistryManager
import com.example.core.network.NetworkConnectivityMonitor
import com.example.core.network.NetworkStatus
import com.example.core.network.NetworkTransportType
import com.example.core.provider.AIProviderManager
import com.example.core.provider.ProviderConfig
import com.example.core.provider.adapter.AIProviderAdapter
import com.example.core.provider.adapter.GeminiProviderAdapter
import com.example.core.provider.adapter.OpenAICompatibleAdapter
import com.example.core.provider.model.AIChatMessage
import com.example.core.provider.model.AIImageAttachment
import com.example.core.provider.model.AIRequest
import com.example.core.provider.model.AIResponse
import com.example.core.provider.model.AIResult
import com.example.core.screen.ContinuousScreenContextManager
import com.example.core.screen.FusedScreenPerception
import com.example.core.screen.PerceptionSource
import com.example.core.screen.RestrictedScreenStatus
import com.example.core.screen.RestrictedScreenType
import com.example.core.screen.ScreenAppCategory
import com.example.core.screen.ScreenGestureDispatcher
import com.example.core.screen.ScreenIntelligenceManager
import com.example.core.screen.ScreenNodeInfo
import com.example.core.screen.ScreenOperatorActionType
import com.example.core.screen.ScreenPerceptionProvider
import com.example.core.screen.ScreenSnapshot
import com.example.core.screen.UniversalScreenOperator
import com.example.core.screen.VisualScreenObservation
import com.example.core.security.EncryptedStorageManager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Comprehensive verification suite for NAYRO's Universal AI-Powered Android Screen Operator:
 * - Multimodal Vision AI payload & routing (Gemini + OpenAI-compatible)
 * - Multi-method perception (Accessibility + Screenshot + On-Device CV + Vision AI)
 * - On-device salient visual region detection, dHash transition verification, and FLAG_SECURE/banking protection
 * - Game & zero-Accessibility-node visual coordinate interaction + multi-turn Continuous Screen Context
 * - Screen reading & explanation ("What is on my screen?", "Read this page", "Explain this screen")
 * - Voice/text screen command classification & planning across all universal controls
 * - Honest verification (never claiming success without observed Accessibility or Visual screen change)
 */
class UniversalScreenOperatorTests {

    private lateinit var storage: EncryptedStorageManager

    @Before
    fun setUp() {
        storage = EncryptedStorageManager(context = null)
    }

    @Test
    fun testMultimodalVisionPayloadSummariesAndProviderRouting() = runTest {
        val req = AIRequest(
            messages = listOf(AIChatMessage("user", "What is on this game screen?")),
            images = listOf(AIImageAttachment(base64Data = "/9j/4AAQSkZJRg==", mimeType = "image/jpeg"))
        )
        val (turns, imgCount) = GeminiProviderAdapter.describeMultimodalPayloadSummary(req)
        assertEquals(1, turns)
        assertEquals(1, imgCount)

        val dataUri = OpenAICompatibleAdapter.buildImageDataUri("image/jpeg", "/9j/4AAQ==")
        assertEquals("data:image/jpeg;base64,/9j/4AAQ==", dataUri)

        val monitor = NetworkConnectivityMonitor(context = null).apply {
            testOverrideStatus = NetworkStatus(true, true, NetworkTransportType.WIFI)
        }
        val manager = AIProviderManager(storage, monitor)
        manager.saveApiKey("gemini_default", "vision-test-key")

        var capturedImageCount = 0
        var capturedMime = ""
        manager.customAdapterFactory = { cfg ->
            object : AIProviderAdapter {
                override val providerConfig: ProviderConfig = cfg
                override suspend fun generateResponse(request: AIRequest, apiKey: String): AIResult {
                    capturedImageCount = request.images.size
                    capturedMime = request.images.firstOrNull()?.mimeType.orEmpty()
                    return AIResult.Success(AIResponse("I see a Settings gear icon at top right.", cfg.id, cfg.modelId))
                }
                override suspend fun testConnection(apiKey: String): AIResult =
                    AIResult.Success(AIResponse("OK", cfg.id, cfg.modelId))
            }
        }

        val res = manager.sendVisionRequest(
            userPrompt = "Describe this screen",
            imageBase64 = "/9j/fakeBase64ScreenshotData==",
            mimeType = "image/jpeg"
        )
        assertTrue(res is AIResult.Success)
        assertEquals(1, capturedImageCount)
        assertEquals("image/jpeg", capturedMime)
    }

    @Test
    fun testOnDeviceVisualCvSalientRegionsBlackFrameAndRestrictedScreens() {
        // 1. Simulated rich game screen with high-contrast HUD buttons (checkerboard/button edges)
        val w = 16
        val h = 24
        val gamePixels = IntArray(w * h) { idx ->
            val x = idx % w
            val y = idx / w
            if ((x in 11..14 && y in 1..3) || (x in 5..10 && y in 10..13)) {
                0xFFFFFFFF.toInt() // Bright white HUD / Play button
            } else {
                0xFF152030.toInt() // Dark game background
            }
        }
        val gameObs = ScreenIntelligenceManager.analyzePixelGrid(gamePixels, w, h, snapshot = null)
        assertFalse(gameObs.isLikelyProtectedBlackFrame)
        assertTrue("Expected salient CV visual regions for HUD/button controls", gameObs.salientRegions.isNotEmpty())
        assertEquals(
            ScreenAppCategory.GAME,
            UniversalScreenOperator.classifyAppCategory("com.unknown.engine", snapshot = null, visualObservation = gameObs)
        )

        // 2. Simulated FLAG_SECURE all-black capture frame
        val blackPixels = IntArray(w * h) { 0xFF000000.toInt() }
        val blackObs = ScreenIntelligenceManager.analyzePixelGrid(blackPixels, w, h, snapshot = null)
        assertTrue(blackObs.isLikelyProtectedBlackFrame)
        val flagSecureStatus = ScreenIntelligenceManager.detectRestrictedScreen(null, blackObs)
        assertTrue(flagSecureStatus.isRestricted)
        assertEquals(RestrictedScreenType.FLAG_SECURE_PROTECTED, flagSecureStatus.type)

        // 3. Banking / UPI payment screen protection
        val upiSnapshot = ScreenSnapshot(
            packageName = "com.phonepe.app",
            visibleTexts = listOf("Enter UPI PIN", "Confirm Payment ₹2500"),
            interactiveElements = listOf(
                ScreenNodeInfo("PIN", null, "pin_input", "EditText", true, true, false, isPassword = true)
            ),
            totalNodeCount = 3
        )
        val bankingStatus = ScreenIntelligenceManager.detectRestrictedScreen(upiSnapshot, gameObs)
        assertTrue(bankingStatus.isRestricted)
        assertEquals(RestrictedScreenType.BANKING_OR_PAYMENT, bankingStatus.type)

        // 4. Perceptual dHash distance distinguishes unchanged vs shifted screens
        val sameDist = ScreenIntelligenceManager.computePerceptualHashDistance(0x0F0F0F0F0F0F0F0FL, 0x0F0F0F0F0F0F0F0FL)
        val changedDist = ScreenIntelligenceManager.computePerceptualHashDistance(0x0F0F0F0F0F0F0F0FL, 0x70F00F0FF0F00F0FL)
        assertEquals(0, sameDist)
        assertTrue(changedDist >= 16)
    }

    @Test
    fun testDecisionJsonParserAndCoordinateMapping() {
        val rawFenced = """
            ```json
            {"action":"DRAG","x":200,"y":500,"endX":800,"endY":500,"durationMs":600,"reason":"Drag volume slider right","screenSummary":"Game audio settings"}
            ```
        """.trimIndent()
        val parsed = UniversalScreenOperator.parseOperatorDecision(rawFenced)
        assertNotNull(parsed)
        assertEquals(ScreenOperatorActionType.DRAG, parsed!!.action)
        assertEquals(200, parsed.normX)
        assertEquals(500, parsed.normY)
        assertEquals(800, parsed.normEndX)
        assertEquals(500, parsed.normEndY)
        assertEquals(600L, parsed.durationMs)

        val (px, py) = UniversalScreenOperator.mapNormalizedToPixels(500, 250, 1080, 2400)
        assertEquals(540f, px, 0.5f)
        assertEquals(600f, py, 0.5f)
    }

    @Test
    fun testGameMultiTurnContinuousContextOpenSettingsTurnOnSoundAndGoBack() = runTest {
        val monitor = NetworkConnectivityMonitor(context = null).apply {
            testOverrideStatus = NetworkStatus(true, true, NetworkTransportType.WIFI)
        }
        val providerManager = AIProviderManager(storage, monitor)
        providerManager.saveApiKey("gemini_default", "vision-key")

        // Simulate a Unity game with ZERO Accessibility nodes (pure visual perception + coordinate gestures!)
        var currentGameState = "MAIN_MENU" // MAIN_MENU -> SETTINGS_SOUND_OFF -> SETTINGS_SOUND_ON -> MAIN_MENU
        val hashByState = mapOf(
            "MAIN_MENU" to 0x1111111111111111L,
            "SETTINGS_SOUND_OFF" to 0x55555555AAAAAAAAL,
            "SETTINGS_SOUND_ON" to 0x7FFF0000FFFF0000L
        )
        val recordedGestures = mutableListOf<String>()

        val fakePerceptor = object : ScreenPerceptionProvider {
            override suspend fun perceiveCurrentScreen(captureScreenshot: Boolean): FusedScreenPerception {
                val hash = hashByState[currentGameState] ?: 0L
                return FusedScreenPerception(
                    packageName = "com.unity.racinggame",
                    appCategory = ScreenAppCategory.GAME,
                    accessibilitySnapshot = null, // Zero accessibility nodes (real game behavior!)
                    visualObservation = VisualScreenObservation(
                        width = 1080,
                        height = 2400,
                        meanLuminance = 95f,
                        contrastScore = 48f,
                        isDarkThemeDetected = true,
                        hasModalDimOverlay = false,
                        visualPerceptualHash = hash,
                        detectedRegionsSummary = "Game screen state=$currentGameState",
                        groundedTextElements = emptyList()
                    ),
                    screenshotBase64 = "fakeJpegFrame_$currentGameState",
                    screenWidth = 1080,
                    screenHeight = 2400,
                    perceptionSources = setOf(PerceptionSource.MEDIA_PROJECTION_CAPTURE, PerceptionSource.ON_DEVICE_CV)
                )
            }
        }

        val fakeDispatcher = object : ScreenGestureDispatcher {
            override fun isInteractionAvailable(): Boolean = true
            override suspend fun clickLabel(label: String) = false to "No Accessibility nodes in game"
            override suspend fun tapCoordinates(x: Float, y: Float): Pair<Boolean, String> {
                recordedGestures.add("TAP(${x.toInt()},${y.toInt()})")
                if (currentGameState == "MAIN_MENU" && x > 850f && y < 300f) {
                    currentGameState = "SETTINGS_SOUND_OFF"
                } else if (currentGameState == "SETTINGS_SOUND_OFF" && y in 900f..1300f) {
                    currentGameState = "SETTINGS_SOUND_ON"
                }
                return true to "Tapped game coordinates (${x.toInt()}, ${y.toInt()})."
            }
            override suspend fun doubleTapCoordinates(x: Float, y: Float) = true to "Double-tapped"
            override suspend fun longPressLabelOrCoordinates(label: String?, x: Float?, y: Float?) = true to "Long-pressed"
            override suspend fun setText(text: String, targetLabel: String?, x: Float?, y: Float?, submit: Boolean) = true to "Typed"
            override suspend fun scrollDirection(direction: String) = true to "Scrolled $direction"
            override suspend fun swipeCoordinates(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) = true to "Swiped"
            override suspend fun dragCoordinates(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) = true to "Dragged"
            override fun navigateBack(): Pair<Boolean, String> {
                recordedGestures.add("BACK")
                if (currentGameState != "MAIN_MENU") currentGameState = "MAIN_MENU"
                return true to "Navigated back."
            }
            override fun navigateHome() = true to "Home"
            override fun openRecents() = true to "Recents"
        }

        // Mock Vision AI brain inspecting the captured screenshot frame at each step
        providerManager.customAdapterFactory = { cfg ->
            object : AIProviderAdapter {
                override val providerConfig: ProviderConfig = cfg
                override suspend fun generateResponse(request: AIRequest, apiKey: String): AIResult {
                    val frame = request.images.firstOrNull()?.base64Data.orEmpty()
                    val prompt = request.messages.lastOrNull()?.content.orEmpty().lowercase()
                    val reply = when {
                        prompt.contains("open its settings") && frame.endsWith("MAIN_MENU") ->
                            """{"action":"TAP","x":900,"y":80,"reason":"Tap top-right Settings gear icon in game HUD","screenSummary":"Game main menu"}"""
                        prompt.contains("open its settings") && frame.endsWith("SETTINGS_SOUND_OFF") ->
                            """{"action":"DONE","reason":"Game Settings menu is now open.","screenSummary":"Game settings menu"}"""
                        prompt.contains("turn on sound") && frame.endsWith("SETTINGS_SOUND_OFF") ->
                            """{"action":"TAP","x":700,"y":460,"reason":"Tap Sound toggle switch to enable audio","screenSummary":"Game settings with Sound OFF"}"""
                        prompt.contains("turn on sound") && frame.endsWith("SETTINGS_SOUND_ON") ->
                            """{"action":"DONE","reason":"Game sound has been turned ON and verified visually.","screenSummary":"Game settings with Sound ON"}"""
                        else ->
                            """{"action":"CANNOT_AUTOMATE","reason":"Unexpected test screen state: $frame"}"""
                    }
                    return AIResult.Success(AIResponse(reply, cfg.id, cfg.modelId))
                }
                override suspend fun testConnection(apiKey: String): AIResult =
                    AIResult.Success(AIResponse("OK", cfg.id, cfg.modelId))
            }
        }

        val ctxMgr = ContinuousScreenContextManager()
        val operator = UniversalScreenOperator(providerManager, fakePerceptor, fakeDispatcher, ctxMgr)

        // Turn 1: App launched
        ctxMgr.recordLaunchedApp("com.unity.racinggame", ScreenAppCategory.GAME)

        // Turn 2: Follow-up "Open its settings"
        val resSettings = operator.operateGoal("Open its settings", maxSteps = 4, postActionDelayMs = 0L)
        assertTrue("Expected game settings to open visually, got: ${resSettings.finalMessage}", resSettings.isSuccess)
        assertEquals(VerificationStatus.VERIFIED, resSettings.verificationStatus)
        assertEquals("SETTINGS_SOUND_OFF", currentGameState)

        // Turn 3: Follow-up "Turn on sound"
        val resSound = operator.operateGoal("Turn on sound", maxSteps = 4, postActionDelayMs = 0L)
        assertTrue("Expected sound to turn ON, got: ${resSound.finalMessage}", resSound.isSuccess)
        assertEquals(VerificationStatus.VERIFIED, resSound.verificationStatus)
        assertEquals("SETTINGS_SOUND_ON", currentGameState)

        // Turn 4: Follow-up "Go back"
        val resBack = operator.operateGoal("Go back", maxSteps = 2, postActionDelayMs = 0L)
        assertTrue("Expected back navigation to verify screen return, got: ${resBack.finalMessage}", resBack.isSuccess)
        assertEquals(VerificationStatus.VERIFIED, resBack.verificationStatus)
        assertEquals("MAIN_MENU", currentGameState)
        assertTrue(recordedGestures.contains("BACK"))
        assertTrue(ctxMgr.context.value.recentScreenActions.size >= 3)
    }

    @Test
    fun testHonestVerificationRefusesFakeSuccessWhenScreenDoesNotChangeAndBlocksRestrictedScreens() = runTest {
        val monitor = NetworkConnectivityMonitor(context = null).apply {
            testOverrideStatus = NetworkStatus(true, true, NetworkTransportType.WIFI)
        }
        val providerManager = AIProviderManager(storage, monitor)
        providerManager.saveApiKey("gemini_default", "key")

        // 1. Unresponsive screen: tap succeeds at gesture layer, but neither Accessibility nor dHash changes!
        val staticPerceptor = object : ScreenPerceptionProvider {
            override suspend fun perceiveCurrentScreen(captureScreenshot: Boolean): FusedScreenPerception {
                return FusedScreenPerception(
                    packageName = "com.example.frozenapp",
                    appCategory = ScreenAppCategory.GENERAL_APP,
                    accessibilitySnapshot = ScreenSnapshot(
                        packageName = "com.example.frozenapp",
                        visibleTexts = listOf("Start", "Options"),
                        interactiveElements = listOf(
                            ScreenNodeInfo("Start", null, "btn_start", "Button", true, false, false, false, 100, 500, 400, 600)
                        ),
                        totalNodeCount = 2
                    ),
                    visualObservation = VisualScreenObservation(
                        width = 1080, height = 2400, meanLuminance = 180f, contrastScore = 30f,
                        isDarkThemeDetected = false, hasModalDimOverlay = false,
                        visualPerceptualHash = 0x123456789ABCDEF0L,
                        detectedRegionsSummary = "Static screen", groundedTextElements = listOf("Start @(250,550)")
                    ),
                    screenshotBase64 = "staticFrame"
                )
            }
        }
        val noopDispatcher = object : ScreenGestureDispatcher {
            override fun isInteractionAvailable() = true
            override suspend fun clickLabel(label: String) = true to "Clicked $label"
            override suspend fun tapCoordinates(x: Float, y: Float) = true to "Tapped"
            override suspend fun doubleTapCoordinates(x: Float, y: Float) = true to "Double-tapped"
            override suspend fun longPressLabelOrCoordinates(label: String?, x: Float?, y: Float?) = true to "Long-pressed"
            override suspend fun setText(text: String, targetLabel: String?, x: Float?, y: Float?, submit: Boolean) = true to "Typed"
            override suspend fun scrollDirection(direction: String) = true to "Scrolled"
            override suspend fun swipeCoordinates(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) = true to "Swiped"
            override suspend fun dragCoordinates(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) = true to "Dragged"
            override fun navigateBack() = true to "Back"
            override fun navigateHome() = true to "Home"
            override fun openRecents() = true to "Recents"
        }

        val operator = UniversalScreenOperator(providerManager, staticPerceptor, noopDispatcher)
        val noChangeOutcome = operator.operateGoal("Click Start", maxSteps = 2, postActionDelayMs = 0L)
        assertFalse("Must NOT claim success when screen did not change!", noChangeOutcome.isSuccess)
        assertEquals(VerificationStatus.INCONCLUSIVE, noChangeOutcome.verificationStatus)

        // 2. Restricted FLAG_SECURE screen blocks both reading and operating honestly
        val restrictedPerceptor = object : ScreenPerceptionProvider {
            override suspend fun perceiveCurrentScreen(captureScreenshot: Boolean): FusedScreenPerception {
                return FusedScreenPerception(
                    packageName = "com.bank.secure",
                    appCategory = ScreenAppCategory.GENERAL_APP,
                    accessibilitySnapshot = null,
                    visualObservation = null,
                    restrictedStatus = RestrictedScreenStatus(
                        isRestricted = true,
                        type = RestrictedScreenType.FLAG_SECURE_PROTECTED,
                        reason = "Protected by FLAG_SECURE."
                    )
                )
            }
        }
        val restrictedOperator = UniversalScreenOperator(providerManager, restrictedPerceptor, noopDispatcher)
        val readRestricted = restrictedOperator.describeOrAnswerScreen("What is on my screen?")
        assertFalse(readRestricted.isSuccess)
        assertTrue(readRestricted.outputMessage.contains("FLAG_SECURE"))

        val opRestricted = restrictedOperator.operateGoal("Click Pay Now", maxSteps = 2, postActionDelayMs = 0L)
        assertFalse(opRestricted.isSuccess)
        assertTrue(opRestricted.finalMessage.contains("FLAG_SECURE"))
    }

    @Test
    fun testScreenReadingAndVoiceCommandClassificationAndToolRegistration() {
        // Verify new ActionTypes are registered in ToolRegistryManager
        assertTrue(ToolRegistryManager.isToolAvailable(ActionType.NAVIGATE_RECENTS))
        assertTrue(ToolRegistryManager.isToolAvailable(ActionType.DRAG_SCREEN))
        assertTrue(ToolRegistryManager.isToolAvailable(ActionType.UNIVERSAL_SCREEN_OPERATE))

        // Screen reading / explanation commands
        for (readCmd in listOf("What is on my screen?", "Read this page", "Explain this screen", "स्क्रीन पर क्या है")) {
            val intent = TaskClassifier.classify(readCmd)
            assertEquals("Expected SCREEN_READ for '$readCmd'", UserIntentType.SCREEN_READ, intent.type)
            val task = TaskPlanner.planTask(intent)
            assertEquals(ActionType.READ_SCREEN, task.actions.first().actionType)
        }

        // Universal screen operation commands
        val clickSettings = TaskClassifier.classify("Click the settings icon")
        assertEquals(UserIntentType.SCREEN_OPERATE, clickSettings.type)
        assertEquals(ActionType.UNIVERSAL_SCREEN_OPERATE, TaskPlanner.planTask(clickSettings).actions.first().actionType)

        val thirdOption = TaskClassifier.classify("Open the third option")
        assertEquals(UserIntentType.SCREEN_OPERATE, thirdOption.type)
        val thirdTask = TaskPlanner.planTask(thirdOption)
        assertEquals(ActionType.CLICK_ELEMENT, thirdTask.actions.first().actionType)
        assertEquals("ordinal:3", thirdTask.actions.first().target)

        val scrollDown = TaskClassifier.classify("Scroll down")
        assertEquals(UserIntentType.SCREEN_OPERATE, scrollDown.type)
        assertEquals(ActionType.SCROLL_FORWARD, TaskPlanner.planTask(scrollDown).actions.first().actionType)

        val goBack = TaskClassifier.classify("Go back")
        assertEquals(UserIntentType.SCREEN_OPERATE, goBack.type)
        assertEquals(ActionType.NAVIGATE_BACK, TaskPlanner.planTask(goBack).actions.first().actionType)

        val recents = TaskClassifier.classify("Open recent apps")
        assertEquals(UserIntentType.SCREEN_OPERATE, recents.type)
        assertEquals(ActionType.NAVIGATE_RECENTS, TaskPlanner.planTask(recents).actions.first().actionType)

        val openItsSettings = TaskClassifier.classify("Open its settings")
        assertEquals(UserIntentType.SCREEN_OPERATE, openItsSettings.type)

        val turnOnSound = TaskClassifier.classify("Turn on sound")
        assertEquals(UserIntentType.SCREEN_OPERATE, turnOnSound.type)

        val pressButton = TaskClassifier.classify("Press this button")
        assertEquals(UserIntentType.SCREEN_OPERATE, pressButton.type)

        val findFeature = TaskClassifier.classify("Find the dark mode feature")
        assertEquals(UserIntentType.SCREEN_OPERATE, findFeature.type)

        // Ensure existing Android System Settings & Flashlight intents are NOT hijacked!
        assertEquals(UserIntentType.SETTINGS_NAVIGATE, TaskClassifier.classify("Open Wi-Fi settings").type)
        assertEquals(UserIntentType.FLASHLIGHT_CONTROL, TaskClassifier.classify("Turn on flashlight").type)
    }
}
