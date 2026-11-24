package com.example.core.screen

import android.content.Context
import com.example.core.accessibility.AccessibilityActionController
import com.example.core.accessibility.AccessibilityConnectionManager
import com.example.core.accessibility.NayroAccessibilityService
import com.example.core.agent.ActionExecutionResult
import com.example.core.agent.VerificationStatus
import com.example.core.provider.AIProviderManager
import com.example.core.provider.model.AIResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PerceptionSource {
    ACCESSIBILITY_TREE,
    ACCESSIBILITY_SCREENSHOT,
    MEDIA_PROJECTION_CAPTURE,
    ON_DEVICE_CV,
    MULTIMODAL_VISION_AI
}

enum class ScreenAppCategory {
    GAME,
    BROWSER,
    SETTINGS,
    SOCIAL_MEDIA,
    MESSAGING,
    VIDEO_ENTERTAINMENT,
    SHOPPING,
    FILE_MANAGER,
    SYSTEM_LAUNCHER,
    GENERAL_APP
}

data class FusedScreenPerception(
    val packageName: String?,
    val appCategory: ScreenAppCategory,
    val accessibilitySnapshot: ScreenSnapshot?,
    val visualObservation: VisualScreenObservation?,
    val screenshotBase64: String? = null,
    val screenWidth: Int = 1080,
    val screenHeight: Int = 2400,
    val perceptionSources: Set<PerceptionSource> = emptySet(),
    val restrictedStatus: RestrictedScreenStatus = RestrictedScreenStatus(false, RestrictedScreenType.NONE, "Accessible"),
    val timestampMs: Long = System.currentTimeMillis()
) {
    val hasAccessibilityNodes: Boolean
        get() = accessibilitySnapshot != null &&
            (accessibilitySnapshot.visibleTexts.isNotEmpty() || accessibilitySnapshot.interactiveElements.isNotEmpty())

    val hasVisualFrame: Boolean
        get() = !screenshotBase64.isNullOrBlank() || visualObservation != null

    /**
     * Builds a concise, grounded prompt summary combining Accessibility UI hierarchy,
     * interactive node bounds, and on-device computer-vision salient regions for the AI operator.
     */
    fun toOperatorPromptContext(): String = buildString {
        appendLine("Active App Package: ${packageName ?: "Unknown"} (Category: ${appCategory.name})")
        appendLine("Screen Dimensions: ${screenWidth}x${screenHeight} px")
        appendLine("Perception Sources: ${perceptionSources.joinToString(", ") { it.name }}")
        val snap = accessibilitySnapshot
        if (snap != null && (snap.visibleTexts.isNotEmpty() || snap.interactiveElements.isNotEmpty())) {
            appendLine("Visible Accessibility Text (${snap.visibleTexts.size}): ${snap.visibleTexts.distinct().take(25).joinToString(" | ")}")
            val elements = snap.interactiveElements.take(25).mapIndexed { idx, node ->
                val nx = if (screenWidth > 0) ((node.centerX * 1000) / screenWidth).coerceIn(0, 1000) else 500
                val ny = if (screenHeight > 0) ((node.centerY * 1000) / screenHeight).coerceIn(0, 1000) else 500
                val flags = buildList {
                    if (node.isClickable) add("clickable")
                    if (node.isEditable) add("editable")
                    if (node.isScrollable) add("scrollable")
                }.joinToString(",")
                "#${idx + 1} '${node.displayLabel}' [$flags] norm=($nx,$ny) px=(${node.centerX},${node.centerY})"
            }
            appendLine("Interactive UI Nodes:")
            elements.forEach { appendLine("  $it") }
        } else {
            appendLine("Accessibility UI Nodes: NONE (likely a game, Canvas/OpenGL/Unity/WebView, or custom surface — rely on screenshot vision and coordinate gestures).")
        }
        visualObservation?.let { obs ->
            appendLine("On-Device CV Observation: ${obs.detectedRegionsSummary}")
            if (obs.salientRegions.isNotEmpty()) {
                val regionsDesc = obs.salientRegions.take(6).joinToString("; ") { r ->
                    val nx = if (screenWidth > 0) ((r.centerX * 1000) / screenWidth).coerceIn(0, 1000) else 500
                    val ny = if (screenHeight > 0) ((r.centerY * 1000) / screenHeight).coerceIn(0, 1000) else 500
                    "${r.zoneLabel}@norm($nx,$ny)"
                }
                appendLine("Salient Visual Control Regions: $regionsDesc")
            }
        }
    }.trim()
}

data class ContinuousScreenContext(
    val activePackage: String? = null,
    val appCategory: ScreenAppCategory = ScreenAppCategory.GENERAL_APP,
    val lastScreenSummary: String = "",
    val lastVisibleLabels: List<String> = emptyList(),
    val lastPerceptualHash: Long? = null,
    val recentScreenActions: List<String> = emptyList(),
    val activeGoalThread: String? = null,
    val updatedAtMs: Long = 0L
)

/**
 * Maintains continuous multi-turn awareness of the currently visible app/game and recent screen
 * interactions so follow-up commands ("Open its settings", "Turn on sound", "Go back",
 * "Open the third option") execute seamlessly without restarting the workflow.
 */
class ContinuousScreenContextManager {
    private val _context = MutableStateFlow(ContinuousScreenContext())
    val context: StateFlow<ContinuousScreenContext> = _context.asStateFlow()

    fun updateFromPerception(
        perception: FusedScreenPerception,
        summaryNote: String? = null,
        executedActionSummary: String? = null,
        goalThread: String? = null
    ) {
        val current = _context.value
        val labels = perception.accessibilitySnapshot?.interactiveElements
            ?.map { it.displayLabel }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?.take(12)
            ?: current.lastVisibleLabels
        val updatedHistory = if (!executedActionSummary.isNullOrBlank()) {
            (current.recentScreenActions + executedActionSummary).takeLast(8)
        } else {
            current.recentScreenActions
        }
        _context.value = ContinuousScreenContext(
            activePackage = perception.packageName ?: current.activePackage,
            appCategory = perception.appCategory,
            lastScreenSummary = summaryNote?.takeIf { it.isNotBlank() }
                ?: perception.visualObservation?.detectedRegionsSummary
                ?: current.lastScreenSummary,
            lastVisibleLabels = labels,
            lastPerceptualHash = perception.visualObservation?.visualPerceptualHash ?: current.lastPerceptualHash,
            recentScreenActions = updatedHistory,
            activeGoalThread = goalThread ?: current.activeGoalThread,
            updatedAtMs = System.currentTimeMillis()
        )
    }

    fun recordLaunchedApp(appNameOrPkg: String, category: ScreenAppCategory = ScreenAppCategory.GENERAL_APP) {
        val current = _context.value
        _context.value = current.copy(
            activePackage = appNameOrPkg,
            appCategory = category,
            recentScreenActions = (current.recentScreenActions + "Opened $appNameOrPkg").takeLast(8),
            activeGoalThread = "Operating $appNameOrPkg",
            updatedAtMs = System.currentTimeMillis()
        )
    }

    fun resolveFollowUpPrompt(rawPrompt: String): String {
        val ctx = _context.value
        val activePkg = ctx.activePackage
        val isRecent = ctx.updatedAtMs > 0L && (System.currentTimeMillis() - ctx.updatedAtMs) < 10 * 60_000L
        if (activePkg.isNullOrBlank() || !isRecent) return rawPrompt

        val lower = rawPrompt.trim().lowercase()
        val hasPronounOrFollowUp = listOf(
            "its ", "this ", "that ", "is screen", "iss screen", "isme ", "usme ", "iske ",
            "turn on ", "turn off ", "enable ", "disable ", "sound", "music", "audio", "settings",
            "go back", "wapas", "peeche", "third option", "second option", "first option",
            "teesra", "dusra", "pehla", "press this", "click that", "next"
        ).any { lower.contains(it) }

        return if (hasPronounOrFollowUp) {
            "$rawPrompt [Current Screen Context: app=$activePkg (${ctx.appCategory.name}); recent=${ctx.recentScreenActions.takeLast(3).joinToString(" -> ")}]"
        } else {
            rawPrompt
        }
    }

    fun buildPromptMemorySnippet(): String {
        val ctx = _context.value
        if (ctx.activePackage.isNullOrBlank() && ctx.recentScreenActions.isEmpty()) return ""
        return buildString {
            appendLine("Continuous Screen Context:")
            ctx.activePackage?.let { appendLine("  Current App/Game: $it (${ctx.appCategory.name})") }
            if (ctx.lastScreenSummary.isNotBlank()) appendLine("  Last Screen Summary: ${ctx.lastScreenSummary.take(240)}")
            if (ctx.recentScreenActions.isNotEmpty()) {
                appendLine("  Recent Screen Steps: ${ctx.recentScreenActions.takeLast(5).joinToString(" -> ")}")
            }
        }.trim()
    }

    fun clear() {
        _context.value = ContinuousScreenContext()
    }
}

enum class ScreenOperatorActionType {
    TAP,
    DOUBLE_TAP,
    LONG_PRESS,
    TYPE,
    SCROLL_DOWN,
    SCROLL_UP,
    SCROLL_LEFT,
    SCROLL_RIGHT,
    SWIPE,
    DRAG,
    BACK,
    HOME,
    RECENTS,
    WAIT,
    DONE,
    CANNOT_AUTOMATE
}

data class ScreenOperatorDecision(
    val action: ScreenOperatorActionType,
    val targetLabel: String? = null,
    val normX: Int? = null,
    val normY: Int? = null,
    val normEndX: Int? = null,
    val normEndY: Int? = null,
    val textToType: String? = null,
    val submitAfterType: Boolean = false,
    val durationMs: Long = 350L,
    val reason: String = "",
    val screenSummary: String = ""
)

data class ScreenOperationStepTrace(
    val stepIndex: Int,
    val decision: ScreenOperatorDecision,
    val executedOk: Boolean,
    val verifiedChanged: Boolean,
    val detail: String
)

data class ScreenOperationOutcome(
    val isSuccess: Boolean,
    val verificationStatus: VerificationStatus,
    val finalMessage: String,
    val steps: List<ScreenOperationStepTrace> = emptyList(),
    val lastPerception: FusedScreenPerception? = null
)

/**
 * Injectable multi-method screen perception interface so production runs real Android
 * AccessibilityService + MediaProjection + CV, while unit tests can inject deterministic frames.
 */
interface ScreenPerceptionProvider {
    suspend fun perceiveCurrentScreen(captureScreenshot: Boolean = true): FusedScreenPerception
}

/**
 * Injectable gesture/interaction execution interface supporting semantic UI nodes AND raw coordinate gestures.
 */
interface ScreenGestureDispatcher {
    fun isInteractionAvailable(): Boolean
    suspend fun clickLabel(label: String): Pair<Boolean, String>
    suspend fun tapCoordinates(x: Float, y: Float): Pair<Boolean, String>
    suspend fun doubleTapCoordinates(x: Float, y: Float): Pair<Boolean, String>
    suspend fun longPressLabelOrCoordinates(label: String?, x: Float?, y: Float?): Pair<Boolean, String>
    suspend fun setText(text: String, targetLabel: String?, x: Float?, y: Float?, submit: Boolean): Pair<Boolean, String>
    suspend fun scrollDirection(direction: String): Pair<Boolean, String>
    suspend fun swipeCoordinates(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Pair<Boolean, String>
    suspend fun dragCoordinates(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Pair<Boolean, String>
    fun navigateBack(): Pair<Boolean, String>
    fun navigateHome(): Pair<Boolean, String>
    fun openRecents(): Pair<Boolean, String>
}

class AndroidMultiMethodPerceptionProvider(
    private val context: Context,
    private val screenIntelligenceManager: ScreenIntelligenceManager
) : ScreenPerceptionProvider {

    override suspend fun perceiveCurrentScreen(captureScreenshot: Boolean): FusedScreenPerception {
        val sources = linkedSetOf<PerceptionSource>()
        val dm = try { context.resources.displayMetrics } catch (_: Throwable) { null }
        var screenW = dm?.widthPixels?.takeIf { it > 0 } ?: 1080
        var screenH = dm?.heightPixels?.takeIf { it > 0 } ?: 2400

        // 1. Accessibility UI Hierarchy
        val snapshot = screenIntelligenceManager.captureCurrentSnapshot()
        if (snapshot != null) {
            sources.add(PerceptionSource.ACCESSIBILITY_TREE)
        }

        // 2. Multi-Method Screen Capture: MediaProjection (if authorized) OR AccessibilityService.takeScreenshot (API 30+)
        var visualObs: VisualScreenObservation? = null
        var screenshotB64: String? = null
        if (captureScreenshot) {
            var bitmap: android.graphics.Bitmap? = null
            if (ScreenRecordingController.hasActiveProjection()) {
                bitmap = ScreenRecordingController.captureSingleFrameBitmap(context)
                if (bitmap != null) sources.add(PerceptionSource.MEDIA_PROJECTION_CAPTURE)
            }
            if (bitmap == null) {
                bitmap = NayroAccessibilityService.instance?.captureRealScreenshotBitmap()
                if (bitmap != null) sources.add(PerceptionSource.ACCESSIBILITY_SCREENSHOT)
            }
            if (bitmap != null) {
                try {
                    screenW = bitmap.width.coerceAtLeast(1)
                    screenH = bitmap.height.coerceAtLeast(1)
                    visualObs = ScreenIntelligenceManager.analyzeBitmap(bitmap, snapshot)
                    sources.add(PerceptionSource.ON_DEVICE_CV)
                    if (!visualObs.isLikelyProtectedBlackFrame) {
                        screenshotB64 = ScreenIntelligenceManager.encodeBitmapToBase64Jpeg(bitmap)
                    }
                } finally {
                    try { bitmap.recycle() } catch (_: Throwable) {}
                }
            }
        }

        val activePkg = snapshot?.packageName ?: AccessibilityConnectionManager.lastActivePackage.value
        val restricted = ScreenIntelligenceManager.detectRestrictedScreen(snapshot, visualObs)
        val category = UniversalScreenOperator.classifyAppCategory(activePkg, snapshot, visualObs)

        return FusedScreenPerception(
            packageName = activePkg,
            appCategory = category,
            accessibilitySnapshot = snapshot,
            visualObservation = visualObs,
            screenshotBase64 = screenshotB64,
            screenWidth = screenW,
            screenHeight = screenH,
            perceptionSources = sources,
            restrictedStatus = restricted
        )
    }
}

class AndroidScreenGestureDispatcher : ScreenGestureDispatcher {

    override fun isInteractionAvailable(): Boolean = NayroAccessibilityService.isConnected()

    override suspend fun clickLabel(label: String): Pair<Boolean, String> {
        val ok = AccessibilityActionController.performClickWithCoordinateFallback(targetText = label)
        return ok to if (ok) "Clicked '$label'." else "Could not find UI element '$label'."
    }

    override suspend fun tapCoordinates(x: Float, y: Float): Pair<Boolean, String> {
        val svc = NayroAccessibilityService.instance
            ?: return false to "Accessibility Service is not connected for coordinate tap."
        return svc.dispatchTapGesture(x, y)
    }

    override suspend fun doubleTapCoordinates(x: Float, y: Float): Pair<Boolean, String> {
        val svc = NayroAccessibilityService.instance
            ?: return false to "Accessibility Service is not connected for double-tap."
        return svc.dispatchDoubleTapGesture(x, y)
    }

    override suspend fun longPressLabelOrCoordinates(label: String?, x: Float?, y: Float?): Pair<Boolean, String> {
        if (!label.isNullOrBlank()) {
            if (AccessibilityActionController.performLongClick(targetText = label)) {
                return true to "Long-pressed '$label'."
            }
            val bounds = AccessibilityActionController.findTargetBoundsInScreen(targetText = label)
            if (bounds != null) {
                val svc = NayroAccessibilityService.instance
                if (svc != null) {
                    return svc.dispatchLongPressGesture(bounds.exactCenterX(), bounds.exactCenterY())
                }
            }
        }
        if (x != null && y != null) {
            val svc = NayroAccessibilityService.instance
                ?: return false to "Accessibility Service is not connected for long-press gesture."
            return svc.dispatchLongPressGesture(x, y)
        }
        return false to "Could not locate target for long-press."
    }

    override suspend fun setText(
        text: String,
        targetLabel: String?,
        x: Float?,
        y: Float?,
        submit: Boolean
    ): Pair<Boolean, String> {
        if (x != null && y != null) {
            NayroAccessibilityService.instance?.dispatchTapGesture(x, y)
            delay(200L)
        } else if (!targetLabel.isNullOrBlank()) {
            AccessibilityActionController.performClickWithCoordinateFallback(targetText = targetLabel)
            delay(180L)
        }
        val typed = AccessibilityActionController.performSetText(text = text, targetText = targetLabel)
        if (typed && submit) {
            AccessibilityActionController.performSubmitAction()
        }
        return typed to if (typed) "Entered text '$text'." else "Could not find an editable input field to type '$text'."
    }

    override suspend fun scrollDirection(direction: String): Pair<Boolean, String> {
        val dir = direction.trim().lowercase()
        val semanticOk = when (dir) {
            "down", "forward" -> AccessibilityActionController.performScrollForward()
            "up", "backward" -> AccessibilityActionController.performScrollBackward()
            else -> false
        }
        if (semanticOk) {
            return true to "Scrolled $dir via Accessibility container."
        }
        val svc = NayroAccessibilityService.instance
            ?: return false to "Accessibility Service is not connected for scrolling."
        return svc.dispatchDirectionalScroll(dir)
    }

    override suspend fun swipeCoordinates(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long
    ): Pair<Boolean, String> {
        val svc = NayroAccessibilityService.instance
            ?: return false to "Accessibility Service is not connected for swipe gesture."
        return svc.dispatchSwipeGesture(x1, y1, x2, y2, durationMs)
    }

    override suspend fun dragCoordinates(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long
    ): Pair<Boolean, String> {
        val svc = NayroAccessibilityService.instance
            ?: return false to "Accessibility Service is not connected for drag gesture."
        return svc.dispatchDragGesture(x1, y1, x2, y2, holdMs = 350L, dragDurationMs = durationMs)
    }

    override fun navigateBack(): Pair<Boolean, String> {
        val ok = AccessibilityActionController.performGlobalBack()
        return ok to if (ok) "Navigated back." else "Accessibility Service unavailable for Back navigation."
    }

    override fun navigateHome(): Pair<Boolean, String> {
        val ok = AccessibilityActionController.performGlobalHome()
        return ok to if (ok) "Navigated to Home screen." else "Accessibility Service unavailable for Home navigation."
    }

    override fun openRecents(): Pair<Boolean, String> {
        val ok = AccessibilityActionController.performGlobalRecents()
        return ok to if (ok) "Opened Recent Apps switcher." else "Accessibility Service unavailable for Recent Apps."
    }
}

/**
 * Universal AI-Powered Android Screen Operator.
 *
 * Implements the full Observe -> Perceive (Accessibility + MediaProjection/Screenshot + On-Device CV +
 * Multimodal Vision AI OCR/grounding) -> Decide -> Execute (Node or Gesture) -> Re-Observe & Verify loop
 * across apps, games, browsers, system settings, and custom interfaces with continuous screen context.
 */
class UniversalScreenOperator(
    private val providerManager: AIProviderManager,
    private val perceptionProvider: ScreenPerceptionProvider,
    private val gestureDispatcher: ScreenGestureDispatcher,
    val contextManager: ContinuousScreenContextManager = ContinuousScreenContextManager()
) {

    companion object {
        const val DEFAULT_MAX_STEPS = 6

        fun mapNormalizedToPixels(normX: Int, normY: Int, screenWidth: Int, screenHeight: Int): Pair<Float, Float> {
            val safeW = screenWidth.coerceAtLeast(1)
            val safeH = screenHeight.coerceAtLeast(1)
            val px = (normX.coerceIn(0, 1000) / 1000f) * safeW
            val py = (normY.coerceIn(0, 1000) / 1000f) * safeH
            return px to py
        }

        fun classifyAppCategory(
            packageName: String?,
            snapshot: ScreenSnapshot?,
            visualObservation: VisualScreenObservation?
        ): ScreenAppCategory {
            val pkg = packageName?.lowercase().orEmpty()
            return when {
                pkg.contains("settings") -> ScreenAppCategory.SETTINGS
                pkg.contains("chrome") || pkg.contains("browser") || pkg.contains("firefox") || pkg.contains("edge") || pkg.contains("opera") || pkg.contains("brave") ->
                    ScreenAppCategory.BROWSER
                pkg.contains("whatsapp") || pkg.contains("telegram") || pkg.contains("messaging") || pkg.contains("mms") || pkg.contains("signal") || pkg.contains("discord") ->
                    ScreenAppCategory.MESSAGING
                pkg.contains("instagram") || pkg.contains("facebook") || pkg.contains("twitter") || pkg.contains("x.android") || pkg.contains("snapchat") || pkg.contains("linkedin") || pkg.contains("reddit") ->
                    ScreenAppCategory.SOCIAL_MEDIA
                pkg.contains("youtube") || pkg.contains("netflix") || pkg.contains("primevideo") || pkg.contains("hotstar") || pkg.contains("spotify") || pkg.contains("jio") ->
                    ScreenAppCategory.VIDEO_ENTERTAINMENT
                pkg.contains("amazon") || pkg.contains("flipkart") || pkg.contains("myntra") || pkg.contains("meesho") || pkg.contains("blinkit") || pkg.contains("zomato") || pkg.contains("swiggy") ->
                    ScreenAppCategory.SHOPPING
                pkg.contains("files") || pkg.contains("documentsui") || pkg.contains("filemanager") ->
                    ScreenAppCategory.FILE_MANAGER
                pkg.contains("launcher") || pkg.contains("trebuchet") || pkg.contains("nexuslauncher") ->
                    ScreenAppCategory.SYSTEM_LAUNCHER
                listOf("game", "unity", "supercell", "roblox", "pubg", "bgmi", "freefire", "garena", "ludo", "minecraft", "chess", "candycrush", "subwaysurf", "ea.", "gameloft", "activision", "miniclip").any { pkg.contains(it) } ->
                    ScreenAppCategory.GAME
                // Non-empty rich visual frame with zero accessibility nodes strongly indicates a game/OpenGL/Canvas surface
                (snapshot == null || (snapshot.visibleTexts.isEmpty() && snapshot.interactiveElements.isEmpty())) &&
                    visualObservation != null && !visualObservation.isLikelyProtectedBlackFrame && visualObservation.salientRegions.isNotEmpty() ->
                    ScreenAppCategory.GAME
                else -> ScreenAppCategory.GENERAL_APP
            }
        }

        /**
         * Pure-Kotlin parser for structured AI screen-operator JSON decisions.
         * Resilient to markdown code fences and works identically on Android runtime and pure-JVM tests.
         */
        fun parseOperatorDecision(rawResponse: String): ScreenOperatorDecision? {
            val cleaned = rawResponse
                .replace("```json", "", ignoreCase = true)
                .replace("```", "")
                .trim()
            val start = cleaned.indexOf('{')
            val end = cleaned.lastIndexOf('}')
            if (start == -1 || end <= start) return null
            val json = cleaned.substring(start, end + 1)

            val actionStr = extractJsonString(json, "action")?.trim()?.uppercase() ?: return null
            val actionType = when (actionStr) {
                "TAP", "CLICK" -> ScreenOperatorActionType.TAP
                "DOUBLE_TAP", "DOUBLETAP" -> ScreenOperatorActionType.DOUBLE_TAP
                "LONG_PRESS", "LONG_CLICK" -> ScreenOperatorActionType.LONG_PRESS
                "TYPE", "SET_TEXT", "INPUT" -> ScreenOperatorActionType.TYPE
                "SCROLL_DOWN" -> ScreenOperatorActionType.SCROLL_DOWN
                "SCROLL_UP" -> ScreenOperatorActionType.SCROLL_UP
                "SCROLL_LEFT" -> ScreenOperatorActionType.SCROLL_LEFT
                "SCROLL_RIGHT" -> ScreenOperatorActionType.SCROLL_RIGHT
                "SWIPE" -> ScreenOperatorActionType.SWIPE
                "DRAG" -> ScreenOperatorActionType.DRAG
                "BACK", "NAVIGATE_BACK" -> ScreenOperatorActionType.BACK
                "HOME", "NAVIGATE_HOME" -> ScreenOperatorActionType.HOME
                "RECENTS", "RECENT_APPS" -> ScreenOperatorActionType.RECENTS
                "WAIT" -> ScreenOperatorActionType.WAIT
                "DONE", "COMPLETE", "COMPLETED" -> ScreenOperatorActionType.DONE
                "CANNOT_AUTOMATE", "BLOCKED", "FAIL" -> ScreenOperatorActionType.CANNOT_AUTOMATE
                else -> return null
            }

            return ScreenOperatorDecision(
                action = actionType,
                targetLabel = extractJsonString(json, "target"),
                normX = extractJsonInt(json, "x") ?: extractJsonInt(json, "startX"),
                normY = extractJsonInt(json, "y") ?: extractJsonInt(json, "startY"),
                normEndX = extractJsonInt(json, "endX"),
                normEndY = extractJsonInt(json, "endY"),
                textToType = extractJsonString(json, "text"),
                submitAfterType = extractJsonBoolean(json, "submit") ?: false,
                durationMs = (extractJsonInt(json, "durationMs") ?: 350).toLong().coerceIn(100L, 3000L),
                reason = extractJsonString(json, "reason").orEmpty(),
                screenSummary = extractJsonString(json, "screenSummary").orEmpty()
            )
        }

        private fun extractJsonString(json: String, key: String): String? {
            val regex = Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
            val raw = regex.find(json)?.groupValues?.getOrNull(1) ?: return null
            return raw.replace("\\\"", "\"").replace("\\n", "\n").replace("\\\\", "\\")
        }

        private fun extractJsonInt(json: String, key: String): Int? {
            val regex = Regex("\"$key\"\\s*:\\s*(-?\\d+)")
            return regex.find(json)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }

        private fun extractJsonBoolean(json: String, key: String): Boolean? {
            val regex = Regex("\"$key\"\\s*:\\s*(true|false)", RegexOption.IGNORE_CASE)
            return regex.find(json)?.groupValues?.getOrNull(1)?.lowercase()?.toBooleanStrictOrNull()
        }

        /**
         * Verifies whether an interaction actually changed the device screen using multi-method evidence:
         * 1. Accessibility hierarchy diff (package change, new visible texts, node count shift), OR
         * 2. Visual perceptual dHash distance >= 4 bits (essential for Unity/OpenGL games & custom Canvas views).
         */
        fun verifyScreenTransition(
            before: FusedScreenPerception,
            after: FusedScreenPerception
        ): Pair<Boolean, String> {
            val a11yChange = ScreenChangeDetector.detectChange(before.accessibilitySnapshot, after.accessibilitySnapshot)
            if (a11yChange.hasChanged) {
                return true to "Verified via Accessibility: ${a11yChange.description}"
            }
            val beforeHash = before.visualObservation?.visualPerceptualHash
            val afterHash = after.visualObservation?.visualPerceptualHash
            if (beforeHash != null && afterHash != null) {
                val hamming = ScreenIntelligenceManager.computePerceptualHashDistance(beforeHash, afterHash)
                if (hamming >= 4) {
                    return true to "Verified via Visual Screen Diff (perceptual dHash shifted by $hamming bits)."
                }
            }
            if (before.packageName != null && after.packageName != null && before.packageName != after.packageName) {
                return true to "Verified app transition: ${before.packageName} -> ${after.packageName}."
            }
            return false to "No Accessibility or visual screen change was detected after the interaction."
        }
    }

    /**
     * Answers screen questions ("What is on my screen?", "Read this page", "Explain this screen")
     * using fused Accessibility hierarchy + live screenshot Vision AI OCR/understanding.
     */
    suspend fun describeOrAnswerScreen(
        userQuestion: String = "What is on my screen? Read visible text and explain key options."
    ): ActionExecutionResult {
        val perception = perceptionProvider.perceiveCurrentScreen(captureScreenshot = true)

        if (perception.restrictedStatus.isRestricted) {
            return ActionExecutionResult(
                isSuccess = false,
                outputMessage = perception.restrictedStatus.reason,
                status = VerificationStatus.FAILED
            )
        }

        if (!perception.hasAccessibilityNodes && !perception.hasVisualFrame) {
            return ActionExecutionResult(
                isSuccess = false,
                outputMessage = "Cannot inspect the screen yet: please enable NAYRO Accessibility Service or authorize MediaProjection screen capture in Permissions.",
                status = VerificationStatus.REQUIRES_PERMISSION
            )
        }

        val groundedContext = perception.toOperatorPromptContext()
        val systemPrompt = buildString {
            appendLine("You are NAYRO's Universal Android Screen Reader & Visual Interpreter.")
            appendLine("Answer the user's question about the currently visible Android screen accurately in their language (Hindi, English, or Hinglish).")
            appendLine("Read visible text (OCR), describe icons, buttons, game menus/HUDs, and interactive options you actually observe.")
            appendLine("NEVER invent or hallucinate elements that are not visible in the screenshot or Accessibility context.")
        }

        val promptWithContext = buildString {
            appendLine("User Question: $userQuestion")
            appendLine()
            appendLine(groundedContext)
        }

        val aiResult = if (!perception.screenshotBase64.isNullOrBlank()) {
            providerManager.sendVisionRequest(
                userPrompt = promptWithContext,
                imageBase64 = perception.screenshotBase64,
                mimeType = "image/jpeg",
                systemPrompt = systemPrompt
            )
        } else {
            providerManager.sendRequest(
                userPrompt = promptWithContext,
                systemPrompt = systemPrompt
            )
        }

        return when (aiResult) {
            is AIResult.Success -> {
                contextManager.updateFromPerception(
                    perception = perception,
                    summaryNote = aiResult.response.content.take(300),
                    executedActionSummary = "Inspected screen (${perception.packageName ?: "active app"})"
                )
                ActionExecutionResult(
                    isSuccess = true,
                    outputMessage = aiResult.response.content,
                    status = VerificationStatus.VERIFIED
                )
            }
            is AIResult.Error -> {
                // Honest local fallback when offline or AI key missing: only report actually captured Accessibility/CV data
                val snap = perception.accessibilitySnapshot
                val localDescription = if (snap != null && snap.visibleTexts.isNotEmpty()) {
                    val bullets = snap.visibleTexts.distinct().take(15).joinToString("\n• ", prefix = "• ")
                    "On screen (${snap.packageName ?: "Active App"}):\n$bullets"
                } else if (perception.visualObservation != null) {
                    "Visual screen frame captured (${perception.visualObservation.detectedRegionsSummary}), but online Vision AI could not be reached (${aiResult.message}) to run OCR/explanation."
                } else {
                    "Could not explain screen: ${aiResult.message}"
                }
                contextManager.updateFromPerception(perception, summaryNote = localDescription.take(240))
                val ok = snap != null && snap.visibleTexts.isNotEmpty()
                ActionExecutionResult(
                    isSuccess = ok,
                    outputMessage = localDescription,
                    status = if (ok) VerificationStatus.VERIFIED else VerificationStatus.INCONCLUSIVE
                )
            }
        }
    }

    /**
     * Executes an end-to-end Observe -> Perceive -> Decide -> Execute -> Verify loop to accomplish
     * any on-screen user goal across apps, games, browsers, and Android settings.
     */
    suspend fun operateGoal(
        rawGoal: String,
        maxSteps: Int = DEFAULT_MAX_STEPS,
        postActionDelayMs: Long = 450L
    ): ScreenOperationOutcome {
        val resolvedGoal = contextManager.resolveFollowUpPrompt(rawGoal)
        val traces = mutableListOf<ScreenOperationStepTrace>()
        var lastPerception: FusedScreenPerception? = null
        var anyVerifiedChange = false

        for (step in 1..maxSteps.coerceIn(1, 10)) {
            val before = perceptionProvider.perceiveCurrentScreen(captureScreenshot = true)
            lastPerception = before

            if (before.restrictedStatus.isRestricted) {
                return ScreenOperationOutcome(
                    isSuccess = false,
                    verificationStatus = VerificationStatus.FAILED,
                    finalMessage = before.restrictedStatus.reason,
                    steps = traces,
                    lastPerception = before
                )
            }

            if (!gestureDispatcher.isInteractionAvailable()) {
                return ScreenOperationOutcome(
                    isSuccess = false,
                    verificationStatus = VerificationStatus.REQUIRES_PERMISSION,
                    finalMessage = "Cannot operate the screen: NAYRO Accessibility Service is not enabled. Enable it in Settings to allow tapping, swiping, and navigation.",
                    steps = traces,
                    lastPerception = before
                )
            }

            val decision = decideNextStep(resolvedGoal, rawGoal, before, traces)
                ?: return ScreenOperationOutcome(
                    isSuccess = false,
                    verificationStatus = VerificationStatus.FAILED,
                    finalMessage = "Could not determine a valid on-screen action for '$rawGoal'. Ensure an online Vision AI provider is configured or the target element is visible.",
                    steps = traces,
                    lastPerception = before
                )

            if (decision.action == ScreenOperatorActionType.DONE) {
                val summary = decision.reason.ifBlank { decision.screenSummary.ifBlank { "Completed '$rawGoal'." } }
                contextManager.updateFromPerception(before, summaryNote = summary, goalThread = rawGoal)
                val verified = anyVerifiedChange || traces.any { it.verifiedChanged }
                return ScreenOperationOutcome(
                    isSuccess = verified || traces.isEmpty(),
                    verificationStatus = if (verified) VerificationStatus.VERIFIED else VerificationStatus.INCONCLUSIVE,
                    finalMessage = summary,
                    steps = traces,
                    lastPerception = before
                )
            }

            if (decision.action == ScreenOperatorActionType.CANNOT_AUTOMATE) {
                return ScreenOperationOutcome(
                    isSuccess = false,
                    verificationStatus = VerificationStatus.FAILED,
                    finalMessage = decision.reason.ifBlank { "This interface cannot be automated safely right now." },
                    steps = traces,
                    lastPerception = before
                )
            }

            val (execOk, execDetail) = executeDecision(decision, before)
            if (postActionDelayMs > 0L) delay(postActionDelayMs)
            val after = perceptionProvider.perceiveCurrentScreen(captureScreenshot = true)
            lastPerception = after

            val (changed, verifyMsg) = verifyScreenTransition(before, after)
            if (changed) anyVerifiedChange = true

            val trace = ScreenOperationStepTrace(
                stepIndex = step,
                decision = decision,
                executedOk = execOk,
                verifiedChanged = changed,
                detail = "$execDetail | $verifyMsg"
            )
            traces.add(trace)

            contextManager.updateFromPerception(
                perception = after,
                summaryNote = decision.screenSummary.ifBlank { verifyMsg },
                executedActionSummary = "${decision.action.name}${decision.targetLabel?.let { "($it)" } ?: ""}",
                goalThread = rawGoal
            )

            // Single-step deterministic commands complete immediately once executed and verified
            if (isSingleStepDirectCommand(rawGoal) && execOk) {
                return ScreenOperationOutcome(
                    isSuccess = changed,
                    verificationStatus = if (changed) VerificationStatus.VERIFIED else VerificationStatus.INCONCLUSIVE,
                    finalMessage = if (changed) {
                        "$execDetail $verifyMsg"
                    } else {
                        "$execDetail However, no screen change could be verified — the element may not have responded."
                    },
                    steps = traces,
                    lastPerception = after
                )
            }

            if (!execOk && step >= maxSteps) {
                return ScreenOperationOutcome(
                    isSuccess = false,
                    verificationStatus = VerificationStatus.FAILED,
                    finalMessage = "Screen interaction failed: $execDetail",
                    steps = traces,
                    lastPerception = after
                )
            }
        }

        val finalStatus = if (anyVerifiedChange) VerificationStatus.INCONCLUSIVE else VerificationStatus.FAILED
        return ScreenOperationOutcome(
            isSuccess = false,
            verificationStatus = finalStatus,
            finalMessage = "Reached maximum screen-operation steps ($maxSteps) for '$rawGoal'. Last step: ${traces.lastOrNull()?.detail ?: "none"}.",
            steps = traces,
            lastPerception = lastPerception
        )
    }

    private fun isSingleStepDirectCommand(rawGoal: String): Boolean {
        val lower = rawGoal.trim().lowercase()
        return lower in setOf(
            "go back", "back", "wapas jao", "peeche jao",
            "go home", "home", "open recent apps", "recent apps", "recents",
            "scroll down", "scroll up", "scroll left", "scroll right",
            "neeche scroll karo", "upar scroll karo", "swipe left", "swipe right", "swipe up", "swipe down"
        ) || Regex("^(?i)(click|tap|press|open|select|long press)\\s+").containsMatchIn(lower)
    }

    private suspend fun decideNextStep(
        resolvedGoal: String,
        rawGoal: String,
        perception: FusedScreenPerception,
        history: List<ScreenOperationStepTrace>
    ): ScreenOperatorDecision? {
        // 1. Fast deterministic path for explicit navigation, scrolling, ordinal selection, or exact Accessibility match on Step 1
        if (history.isEmpty()) {
            tryDeterministicDecision(rawGoal, perception)?.let { return it }
        }

        // 2. Online Multimodal Vision AI (or Text UI Hierarchy AI) decision step
        val historySnippet = if (history.isEmpty()) {
            "None yet."
        } else {
            history.joinToString("\n") { s ->
                "Step ${s.stepIndex}: ${s.decision.action.name} target=${s.decision.targetLabel} norm=(${s.decision.normX},${s.decision.normY}) -> ok=${s.executedOk}, verifiedChanged=${s.verifiedChanged} (${s.detail})"
            }
        }

        val systemPrompt = """
            You are NAYRO's Universal Android Screen Operator. You operate ANY Android app, game, browser, or system settings screen.
            Analyze the attached screenshot (if provided) AND the Accessibility/CV context below.
            Return ONLY ONE JSON object (no markdown prose) with the next single action to make progress toward the user's goal:
            {"action":"TAP|DOUBLE_TAP|LONG_PRESS|TYPE|SCROLL_DOWN|SCROLL_UP|SCROLL_LEFT|SCROLL_RIGHT|SWIPE|DRAG|BACK|HOME|RECENTS|WAIT|DONE|CANNOT_AUTOMATE","target":"optional visible label","x":0-1000,"y":0-1000,"endX":0-1000,"endY":0-1000,"text":"text if TYPE","submit":false,"reason":"short reason","screenSummary":"what you see now"}
            Rules:
            - Coordinates x, y, endX, endY MUST be normalized 0..1000 (0=left/top, 1000=right/bottom).
            - For games or icon-only controls without Accessibility labels, ALWAYS provide accurate visual x, y coordinates!
            - Never automate banking/UPI payments, password entry, or destructive deletions; return CANNOT_AUTOMATE for those.
            - Return DONE ONLY when you visibly verify on the current screen that the user's goal is already achieved.
        """.trimIndent()

        val userPrompt = buildString {
            appendLine("Goal: $resolvedGoal")
            val ctxSnippet = contextManager.buildPromptMemorySnippet()
            if (ctxSnippet.isNotBlank()) {
                appendLine(ctxSnippet)
            }
            appendLine("Previous Steps in This Goal:")
            appendLine(historySnippet)
            appendLine()
            appendLine("Current Screen Observation:")
            appendLine(perception.toOperatorPromptContext())
        }

        val aiRes = if (!perception.screenshotBase64.isNullOrBlank()) {
            providerManager.sendVisionRequest(
                userPrompt = userPrompt,
                imageBase64 = perception.screenshotBase64,
                mimeType = "image/jpeg",
                systemPrompt = systemPrompt
            )
        } else {
            providerManager.sendRequest(
                userPrompt = userPrompt,
                systemPrompt = systemPrompt
            )
        }

        return when (aiRes) {
            is AIResult.Success -> parseOperatorDecision(aiRes.response.content)
            is AIResult.Error -> null
        }
    }

    private fun tryDeterministicDecision(
        rawGoal: String,
        perception: FusedScreenPerception
    ): ScreenOperatorDecision? {
        val lower = rawGoal.trim().lowercase()
        return when {
            lower == "go back" || lower == "back" || lower == "wapas jao" || lower == "peeche jao" ->
                ScreenOperatorDecision(ScreenOperatorActionType.BACK, reason = "User requested Back navigation.")
            lower == "go home" || lower == "home" || lower == "home screen" ->
                ScreenOperatorDecision(ScreenOperatorActionType.HOME, reason = "User requested Home screen.")
            lower.contains("recent app") || lower == "recents" || lower == "open recents" ->
                ScreenOperatorDecision(ScreenOperatorActionType.RECENTS, reason = "User requested Recent Apps switcher.")
            lower == "scroll down" || lower == "neeche scroll karo" || lower == "swipe up" ->
                ScreenOperatorDecision(ScreenOperatorActionType.SCROLL_DOWN, reason = "User requested scroll down.")
            lower == "scroll up" || lower == "upar scroll karo" || lower == "swipe down" ->
                ScreenOperatorDecision(ScreenOperatorActionType.SCROLL_UP, reason = "User requested scroll up.")
            lower == "scroll left" || lower == "swipe left" ->
                ScreenOperatorDecision(ScreenOperatorActionType.SCROLL_LEFT, reason = "User requested scroll left.")
            lower == "scroll right" || lower == "swipe right" ->
                ScreenOperatorDecision(ScreenOperatorActionType.SCROLL_RIGHT, reason = "User requested scroll right.")
            else -> {
                val ordinal = ScreenIntelligenceManager.parseOrdinalIndex(lower)
                if (ordinal != null && perception.accessibilitySnapshot != null) {
                    val match = ScreenIntelligenceManager.locateOrdinalTargetInSnapshot(ordinal, perception.accessibilitySnapshot)
                    if (match != null) {
                        val nx = ((match.centerX * 1000) / perception.screenWidth.coerceAtLeast(1)).coerceIn(0, 1000)
                        val ny = ((match.centerY * 1000) / perception.screenHeight.coerceAtLeast(1)).coerceIn(0, 1000)
                        return ScreenOperatorDecision(
                            action = ScreenOperatorActionType.TAP,
                            targetLabel = match.label,
                            normX = nx,
                            normY = ny,
                            reason = "Matched ordinal #$ordinal element '${match.label}'."
                        )
                    }
                }
                val clickMatch = Regex("^(?i)(click|tap|press|select|long press)\\s+(?:the\\s+|on\\s+)?(.+?)(?:\\s+button|\\s+icon|\\s+tab|\\s+option)?$").find(rawGoal.trim())
                if (clickMatch != null && perception.accessibilitySnapshot != null) {
                    val verb = clickMatch.groupValues[1].lowercase()
                    val candidateTarget = clickMatch.groupValues[2].trim()
                    val located = ScreenIntelligenceManager.locateTargetInSnapshot(candidateTarget, perception.accessibilitySnapshot)
                    if (located != null && located.confidence >= 0.80f) {
                        val nx = ((located.centerX * 1000) / perception.screenWidth.coerceAtLeast(1)).coerceIn(0, 1000)
                        val ny = ((located.centerY * 1000) / perception.screenHeight.coerceAtLeast(1)).coerceIn(0, 1000)
                        return ScreenOperatorDecision(
                            action = if (verb == "long press") ScreenOperatorActionType.LONG_PRESS else ScreenOperatorActionType.TAP,
                            targetLabel = located.label,
                            normX = nx,
                            normY = ny,
                            reason = "Matched visible UI element '${located.label}'."
                        )
                    }
                }
                null
            }
        }
    }

    private suspend fun executeDecision(
        decision: ScreenOperatorDecision,
        perception: FusedScreenPerception
    ): Pair<Boolean, String> {
        val w = perception.screenWidth
        val h = perception.screenHeight
        return when (decision.action) {
            ScreenOperatorActionType.TAP -> {
                if (!decision.targetLabel.isNullOrBlank() && perception.hasAccessibilityNodes) {
                    val semantic = gestureDispatcher.clickLabel(decision.targetLabel)
                    if (semantic.first) return semantic
                }
                if (decision.normX != null && decision.normY != null) {
                    val (px, py) = mapNormalizedToPixels(decision.normX, decision.normY, w, h)
                    gestureDispatcher.tapCoordinates(px, py)
                } else {
                    false to "Missing coordinates or clickable Accessibility target for TAP."
                }
            }
            ScreenOperatorActionType.DOUBLE_TAP -> {
                if (decision.normX != null && decision.normY != null) {
                    val (px, py) = mapNormalizedToPixels(decision.normX, decision.normY, w, h)
                    gestureDispatcher.doubleTapCoordinates(px, py)
                } else {
                    false to "Missing coordinates for DOUBLE_TAP."
                }
            }
            ScreenOperatorActionType.LONG_PRESS -> {
                val coords = if (decision.normX != null && decision.normY != null) {
                    mapNormalizedToPixels(decision.normX, decision.normY, w, h)
                } else null
                gestureDispatcher.longPressLabelOrCoordinates(decision.targetLabel, coords?.first, coords?.second)
            }
            ScreenOperatorActionType.TYPE -> {
                val txt = decision.textToType.orEmpty()
                if (txt.isBlank()) return false to "Missing text to enter."
                val coords = if (decision.normX != null && decision.normY != null) {
                    mapNormalizedToPixels(decision.normX, decision.normY, w, h)
                } else null
                gestureDispatcher.setText(txt, decision.targetLabel, coords?.first, coords?.second, decision.submitAfterType)
            }
            ScreenOperatorActionType.SCROLL_DOWN -> gestureDispatcher.scrollDirection("down")
            ScreenOperatorActionType.SCROLL_UP -> gestureDispatcher.scrollDirection("up")
            ScreenOperatorActionType.SCROLL_LEFT -> gestureDispatcher.scrollDirection("left")
            ScreenOperatorActionType.SCROLL_RIGHT -> gestureDispatcher.scrollDirection("right")
            ScreenOperatorActionType.SWIPE -> {
                if (decision.normX != null && decision.normY != null && decision.normEndX != null && decision.normEndY != null) {
                    val (x1, y1) = mapNormalizedToPixels(decision.normX, decision.normY, w, h)
                    val (x2, y2) = mapNormalizedToPixels(decision.normEndX, decision.normEndY, w, h)
                    gestureDispatcher.swipeCoordinates(x1, y1, x2, y2, decision.durationMs)
                } else {
                    false to "Missing start/end coordinates for SWIPE."
                }
            }
            ScreenOperatorActionType.DRAG -> {
                if (decision.normX != null && decision.normY != null && decision.normEndX != null && decision.normEndY != null) {
                    val (x1, y1) = mapNormalizedToPixels(decision.normX, decision.normY, w, h)
                    val (x2, y2) = mapNormalizedToPixels(decision.normEndX, decision.normEndY, w, h)
                    gestureDispatcher.dragCoordinates(x1, y1, x2, y2, decision.durationMs)
                } else {
                    false to "Missing start/end coordinates for DRAG."
                }
            }
            ScreenOperatorActionType.BACK -> gestureDispatcher.navigateBack()
            ScreenOperatorActionType.HOME -> gestureDispatcher.navigateHome()
            ScreenOperatorActionType.RECENTS -> gestureDispatcher.openRecents()
            ScreenOperatorActionType.WAIT -> {
                delay(decision.durationMs.coerceIn(100L, 2000L))
                true to "Waited ${decision.durationMs}ms for interface transition."
            }
            ScreenOperatorActionType.DONE, ScreenOperatorActionType.CANNOT_AUTOMATE -> true to decision.reason
        }
    }
}
