package com.advanceagent.core.vision

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import com.advanceagent.core.service.WakeWordForegroundService
import com.advanceagent.core.tools.AgentAccessibilityService
import com.example.core.accessibility.NayroAccessibilityService

/**
 * Orchestrates Screen-Vision Automation (Section 6):
 * - Wires [UiGroundingEngine] (Accessibility tree extraction) and [ScreenCaptureManager]
 *   (on-demand MediaProjection JPEG snapshot) together.
 * - Supports the 'Dekho' vision command ([executeDekhoVisionCommand] / [isDekhoCommand])
 *   for Hindi/Hinglish/English multimodal screen inspection.
 * - Computes screen signature & detects screen transitions after UI actions.
 * - Performs grounded taps by text, coordinates, typing into focused fields, and 4-way scrolling.
 */
class VisualTaskOrchestrator(private val context: Context?) {

    private var lastScreenSignature: String = ""

    companion object {
        private val DEKHO_KEYWORDS = listOf(
            "dekho",
            "screen dekho",
            "yeh dekho",
            "isko dekho",
            "babu dekho",
            "nayro dekho",
            "देखो",
            "स्क्रीन देखो",
            "यह देखो"
        )

        fun isDekhoVisionCommand(rawCommand: String): Boolean {
            val lower = rawCommand.trim().lowercase()
            if (lower.isEmpty()) return false
            return DEKHO_KEYWORDS.any { kw ->
                lower == kw || lower.startsWith("$kw ") || lower.endsWith(" $kw") || lower.contains(kw)
            }
        }
    }

    private fun getActiveRoot(): AccessibilityNodeInfo? {
        return AgentAccessibilityService.instance?.rootInActiveWindow
            ?: NayroAccessibilityService.instance?.getSafeRootInActiveWindow()
    }

    fun computeScreenSignature(elements: List<UiElementInfo>): String {
        if (elements.isEmpty()) return "empty_screen"
        return elements.take(25).joinToString("|") { el ->
            "${el.className}:${el.text}:${el.contentDescription}:${el.viewId}:${el.centerX},${el.centerY}"
        }.hashCode().toString()
    }

    /**
     * Executes the 'Dekho' multimodal vision command by combining:
     * 1. On-demand [MediaProjection] JPEG frame capture from [ScreenCaptureManager].
     * 2. Live Accessibility hierarchy extraction from [UiGroundingEngine].
     * 3. Optional live streaming of the captured JPEG frame to the active Gemini Live session.
     */
    fun executeDekhoVisionCommand(
        userPrompt: String = "Dekho",
        preCapturedElements: List<UiElementInfo>? = null,
        preCapturedJpegBase64: String? = null
    ): Map<String, Any> {
        val activeRoot = getActiveRoot()
        val elements = preCapturedElements
            ?: if (activeRoot != null) UiGroundingEngine.extractScreenElements(activeRoot) else emptyList()

        val jpegBase64 = preCapturedJpegBase64 ?: if (context != null && ScreenCaptureManager.isReady()) {
            ScreenCaptureManager.captureScreenJpegBase64(context, quality = 70)
        } else {
            null
        }

        val streamedToLive = if (!jpegBase64.isNullOrBlank()) {
            WakeWordForegroundService.streamCameraFrameToLiveSession(jpegBase64)
        } else {
            false
        }

        val currentSignature = computeScreenSignature(elements)
        val screenChanged = lastScreenSignature.isNotEmpty() && lastScreenSignature != currentSignature
        lastScreenSignature = currentSignature

        val elementSummaries = elements.mapIndexed { idx, el ->
            val label = el.text.ifEmpty { el.contentDescription.ifEmpty { el.viewId } }
            mapOf(
                "index" to idx,
                "label" to label,
                "type" to el.className,
                "clickable" to el.clickable,
                "editable" to el.editable,
                "scrollable" to el.scrollable,
                "center" to "${el.centerX},${el.centerY}"
            )
        }

        val readableLabels = elements
            .map { it.text.ifEmpty { it.contentDescription } }
            .filter { it.isNotBlank() }
            .distinct()
            .take(10)

        val hasVisionData = elements.isNotEmpty() || !jpegBase64.isNullOrBlank()
        val summaryText = when {
            elements.isNotEmpty() && !jpegBase64.isNullOrBlank() ->
                "Dekho Vision Grounded: captured MediaProjection JPEG snapshot (${jpegBase64.length} chars) + ${elements.size} UI elements (${readableLabels.joinToString(" | ")})."
            elements.isNotEmpty() ->
                "Dekho Accessibility Grounded: ${elements.size} UI elements (${readableLabels.joinToString(" | ")})."
            !jpegBase64.isNullOrBlank() ->
                "Dekho MediaProjection Snapshot Captured (${jpegBase64.length} base64 chars)."
            else ->
                "Dekho vision command requires Accessibility Service or MediaProjection Screen Capture permission."
        }

        return mapOf(
            "status" to if (hasVisionData) "ok" else "requires_permission",
            "command" to "DEKHO",
            "prompt" to userPrompt,
            "screen_capture_ready" to (ScreenCaptureManager.isReady() || !jpegBase64.isNullOrBlank()),
            "has_jpeg_snapshot" to !jpegBase64.isNullOrBlank(),
            "jpeg_base64_length" to (jpegBase64?.length ?: 0),
            "streamed_to_gemini_live" to streamedToLive,
            "screen_signature" to currentSignature,
            "screen_transition_detected" to screenChanged,
            "element_count" to elementSummaries.size,
            "elements" to elementSummaries,
            "summary" to summaryText
        )
    }

    fun readScreenContext(): Map<String, Any> {
        val activeRoot = getActiveRoot()
        val jpegBase64 = if (context != null && ScreenCaptureManager.isReady()) {
            ScreenCaptureManager.captureScreenJpegBase64(context, quality = 65)
        } else {
            null
        }
        if (activeRoot == null && jpegBase64.isNullOrBlank()) {
            return mapOf("status" to "error", "message" to "Accessibility Service is not enabled.")
        }

        val elements = if (activeRoot != null) {
            UiGroundingEngine.extractScreenElements(activeRoot)
        } else {
            emptyList()
        }
        val currentSignature = computeScreenSignature(elements)
        val screenChanged = lastScreenSignature.isNotEmpty() && lastScreenSignature != currentSignature
        lastScreenSignature = currentSignature

        val summary = elements.mapIndexed { idx, el ->
            val label = el.text.ifEmpty { el.contentDescription.ifEmpty { el.viewId } }
            mapOf(
                "index" to idx,
                "label" to label,
                "type" to el.className,
                "clickable" to el.clickable,
                "editable" to el.editable,
                "scrollable" to el.scrollable,
                "center" to "${el.centerX},${el.centerY}"
            )
        }
        return mapOf(
            "status" to "ok",
            "screen_capture_ready" to ScreenCaptureManager.isReady(),
            "has_jpeg_snapshot" to !jpegBase64.isNullOrBlank(),
            "screen_signature" to currentSignature,
            "screen_transition_detected" to screenChanged,
            "element_count" to summary.size,
            "elements" to summary
        )
    }

    fun tapElementByText(query: String): Map<String, Any> {
        val beforeRoot = getActiveRoot()
            ?: return mapOf("status" to "error", "message" to "Accessibility Service is not enabled.")
        val beforeSig = computeScreenSignature(UiGroundingEngine.extractScreenElements(beforeRoot))

        val agentService = AgentAccessibilityService.instance
        val ok = if (agentService != null) {
            agentService.clickNodeByText(query)
        } else {
            AgentAccessibilityService.clickNodeInRoot(beforeRoot, query)
        }

        val afterSig = getActiveRoot()?.let {
            computeScreenSignature(UiGroundingEngine.extractScreenElements(it))
        } ?: beforeSig

        return mapOf(
            "status" to if (ok) "ok" else "not_found",
            "target" to query,
            "verified_click_dispatched" to ok,
            "screen_transition_detected" to (beforeSig != afterSig)
        )
    }

    fun tapCoordinates(x: Float, y: Float): Map<String, Any> {
        if (x < 0f || y < 0f) {
            return mapOf("status" to "error", "message" to "Coordinates must be non-negative.")
        }
        val agentService = AgentAccessibilityService.instance
        val ok = if (agentService != null) {
            agentService.tapAt(x, y)
        } else {
            if (NayroAccessibilityService.instance == null) {
                return mapOf("status" to "error", "message" to "Accessibility Service is not enabled.")
            }
            AgentAccessibilityService.dispatchTapOnActiveService(x, y)
        }
        return mapOf("status" to if (ok) "ok" else "failed", "x" to x, "y" to y)
    }

    fun typeText(text: String): Map<String, Any> {
        val agentService = AgentAccessibilityService.instance
        if (agentService != null) {
            val ok = agentService.inputTextIntoFocusedNode(text)
            return mapOf("status" to if (ok) "ok" else "failed", "text" to text)
        }
        val nayroService = NayroAccessibilityService.instance
            ?: return mapOf("status" to "error", "message" to "Accessibility Service is not enabled.")
        val ok = nayroService.performSetTextInField(text)
        return mapOf("status" to if (ok) "ok" else "failed", "text" to text)
    }

    fun scrollScreen(direction: String): Map<String, Any> {
        val normDir = direction.trim().uppercase().ifEmpty { "DOWN" }
        val agentService = AgentAccessibilityService.instance
        if (agentService != null) {
            val ok = agentService.scrollDirection(normDir)
            return mapOf("status" to if (ok) "ok" else "failed", "direction" to normDir)
        }
        val nayroService = NayroAccessibilityService.instance
            ?: return mapOf("status" to "error", "message" to "Accessibility Service is not enabled.")
        val ok = nayroService.performScroll(forward = normDir in setOf("DOWN", "RIGHT"))
        return mapOf("status" to if (ok) "ok" else "failed", "direction" to normDir)
    }
}
