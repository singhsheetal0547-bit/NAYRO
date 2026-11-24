package com.example.core.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * NAYRO Accessibility Service: Real Android Accessibility engine for screen understanding,
 * node traversal, screenshot capture (API 30+), and user-authorized UI automation actions.
 */
class NayroAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "NayroAccessibility"

        @Volatile
        var instance: NayroAccessibilityService? = null
            private set

        fun isConnected(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "NayroAccessibilityService connected and ready")
        AccessibilityConnectionManager.onServiceConnected(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val pkgName = event.packageName?.toString()
        AccessibilityConnectionManager.updateActivePackage(pkgName)

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                Log.d(TAG, "Window state changed: pkg=$pkgName, class=${event.className}")
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // UI tree modified
            }
            else -> {}
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "NayroAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.i(TAG, "NayroAccessibilityService destroyed")
        AccessibilityConnectionManager.onServiceDisconnected()
    }

    /**
     * Safely retrieves the active root in active window.
     */
    fun getSafeRootInActiveWindow(): AccessibilityNodeInfo? {
        return try {
            rootInActiveWindow
        } catch (e: Exception) {
            Log.e(TAG, "Error obtaining root node: ${e.message}")
            null
        }
    }

    /**
     * Reads and parses the current live UI tree into an immutable [com.example.core.screen.ScreenSnapshot]
     * containing visible text labels and interactive elements (buttons, text fields, scrollable containers).
     */
    fun readCurrentUiTree(): com.example.core.screen.ScreenSnapshot? {
        val rootNode = getSafeRootInActiveWindow() ?: return null
        val tree = com.example.core.screen.AccessibilityTreeReader.readTree(rootNode)
        val texts = com.example.core.screen.AccessibilityTreeReader.flattenAllTexts(tree)
        val interactive = com.example.core.screen.AccessibilityTreeReader.extractInteractiveElements(tree)
        return com.example.core.screen.ScreenSnapshot(
            packageName = rootNode.packageName?.toString() ?: AccessibilityConnectionManager.lastActivePackage.value,
            visibleTexts = texts,
            interactiveElements = interactive,
            totalNodeCount = texts.size + interactive.size
        )
    }

    /**
     * Performs a semantic node tap (or coordinate fallback tap) on a UI element matching [targetText] or [viewId].
     */
    suspend fun performTapOnTarget(targetText: String? = null, viewId: String? = null): Boolean {
        return AccessibilityActionController.performClickWithCoordinateFallback(
            targetText = targetText,
            viewId = viewId
        )
    }

    /**
     * Performs a scroll interaction (forward or backward) on the active scrollable container in the UI tree.
     */
    fun performScroll(forward: Boolean = true, viewId: String? = null): Boolean {
        return if (forward) {
            AccessibilityActionController.performScrollForward(viewId)
        } else {
            AccessibilityActionController.performScrollBackward(viewId)
        }
    }

    /**
     * Enters text into an editable field in the active UI tree.
     */
    fun performSetTextInField(text: String, viewId: String? = null, targetText: String? = null): Boolean {
        return AccessibilityActionController.performSetText(
            text = text,
            viewId = viewId,
            targetText = targetText
        )
    }

    /**
     * Captures a real screenshot using Android 11+ (API 30+) AccessibilityService.takeScreenshot.
     * Never returns a fake success string when screenshot capture is unsupported or fails.
     */
    suspend fun captureRealScreenshot(): Pair<Boolean, String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return false to "NOT_SUPPORTED: AccessibilityService.takeScreenshot requires Android 11 (API 30+)."
        }

        val outcome = withTimeoutOrNull(4000L) {
            suspendCancellableCoroutine<Pair<Boolean, String>> { cont ->
                try {
                    takeScreenshot(
                        Display.DEFAULT_DISPLAY,
                        mainExecutor,
                        object : TakeScreenshotCallback {
                            override fun onSuccess(screenshot: ScreenshotResult) {
                                val buffer = screenshot.hardwareBuffer
                                val width = buffer.width
                                val height = buffer.height
                                val timestamp = screenshot.timestamp
                                var visualSummary = ""
                                try {
                                    val hwBitmap = android.graphics.Bitmap.wrapHardwareBuffer(
                                        buffer,
                                        screenshot.colorSpace
                                    )
                                    val swBitmap = hwBitmap?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                                    hwBitmap?.recycle()
                                    if (swBitmap != null) {
                                        val rootNode = getSafeRootInActiveWindow()
                                        val snapshot = if (rootNode != null) {
                                            val tree = com.example.core.screen.AccessibilityTreeReader.readTree(rootNode)
                                            val texts = com.example.core.screen.AccessibilityTreeReader.flattenAllTexts(tree)
                                            val interactive = com.example.core.screen.AccessibilityTreeReader.extractInteractiveElements(tree)
                                            com.example.core.screen.ScreenSnapshot(
                                                packageName = rootNode.packageName?.toString(),
                                                visibleTexts = texts,
                                                interactiveElements = interactive,
                                                totalNodeCount = texts.size + interactive.size
                                            )
                                        } else null
                                        val obs = com.example.core.screen.ScreenIntelligenceManager.analyzeBitmap(swBitmap, snapshot)
                                        visualSummary = " Visual Analysis: ${obs.detectedRegionsSummary}"
                                        swBitmap.recycle()
                                    }
                                } catch (_: Exception) {
                                    // HardwareBuffer wrapping may not be supported on all GPU drivers
                                } finally {
                                    buffer.close()
                                }
                                if (width > 0 && height > 0) {
                                    if (cont.isActive) {
                                        cont.resume(
                                            true to "Verified screenshot captured (${width}x${height} px, timestamp=$timestamp).$visualSummary"
                                        )
                                    }
                                } else {
                                    if (cont.isActive) {
                                        cont.resume(false to "Screenshot buffer had invalid dimensions (${width}x${height}).")
                                    }
                                }
                            }

                            override fun onFailure(errorCode: Int) {
                                if (cont.isActive) {
                                    cont.resume(false to "Accessibility screenshot failed with errorCode=$errorCode.")
                                }
                            }
                        }
                    )
                } catch (e: Exception) {
                    if (cont.isActive) {
                        cont.resume(false to "Screenshot exception: ${e.localizedMessage ?: "Unknown error"}")
                    }
                }
            }
        }

        return outcome ?: (false to "Screenshot capture timed out after 4000ms.")
    }

    /**
     * Dispatches a real tap gesture at (x, y) using AccessibilityService.dispatchGesture.
     */
    suspend fun dispatchTapGesture(x: Float, y: Float): Pair<Boolean, String> {
        val displayMetrics = resources.displayMetrics
        val maxW = displayMetrics.widthPixels.toFloat().coerceAtLeast(1080f)
        val maxH = displayMetrics.heightPixels.toFloat().coerceAtLeast(2400f)

        if (x < 0f || y < 0f || x > maxW || y > maxH) {
            return false to "Invalid tap coordinates ($x, $y): outside screen bounds (0..$maxW, 0..$maxH)."
        }

        val outcome = withTimeoutOrNull(3000L) {
            suspendCancellableCoroutine<Pair<Boolean, String>> { cont ->
                try {
                    val path = Path().apply { moveTo(x, y) }
                    val stroke = GestureDescription.StrokeDescription(path, 0L, 100L)
                    val gesture = GestureDescription.Builder().addStroke(stroke).build()
                    val dispatched = dispatchGesture(
                        gesture,
                        object : GestureResultCallback() {
                            override fun onCompleted(gestureDescription: GestureDescription?) {
                                if (cont.isActive) {
                                    cont.resume(true to "Dispatched tap gesture at ($x, $y).")
                                }
                            }

                            override fun onCancelled(gestureDescription: GestureDescription?) {
                                if (cont.isActive) {
                                    cont.resume(false to "Tap gesture at ($x, $y) was cancelled by the system.")
                                }
                            }
                        },
                        null
                    )
                    if (!dispatched && cont.isActive) {
                        cont.resume(false to "System rejected tap gesture dispatch at ($x, $y).")
                    }
                } catch (e: Exception) {
                    if (cont.isActive) {
                        cont.resume(false to "Failed to dispatch tap gesture: ${e.localizedMessage}")
                    }
                }
            }
        }

        return outcome ?: (false to "Tap gesture at ($x, $y) timed out.")
    }

    /**
     * Dispatches a real double-tap gesture at (x, y) using two 60ms strokes separated by 120ms.
     */
    suspend fun dispatchDoubleTapGesture(x: Float, y: Float): Pair<Boolean, String> {
        val displayMetrics = resources.displayMetrics
        val maxW = displayMetrics.widthPixels.toFloat().coerceAtLeast(1080f)
        val maxH = displayMetrics.heightPixels.toFloat().coerceAtLeast(2400f)

        if (x < 0f || y < 0f || x > maxW || y > maxH) {
            return false to "Invalid double-tap coordinates ($x, $y): outside screen bounds (0..$maxW, 0..$maxH)."
        }

        val outcome = withTimeoutOrNull(3000L) {
            suspendCancellableCoroutine<Pair<Boolean, String>> { cont ->
                try {
                    val path1 = Path().apply { moveTo(x, y) }
                    val path2 = Path().apply { moveTo(x, y) }
                    val stroke1 = GestureDescription.StrokeDescription(path1, 0L, 60L)
                    val stroke2 = GestureDescription.StrokeDescription(path2, 180L, 60L)
                    val gesture = GestureDescription.Builder()
                        .addStroke(stroke1)
                        .addStroke(stroke2)
                        .build()
                    val dispatched = dispatchGesture(
                        gesture,
                        object : GestureResultCallback() {
                            override fun onCompleted(gestureDescription: GestureDescription?) {
                                if (cont.isActive) {
                                    cont.resume(true to "Dispatched double-tap gesture at ($x, $y).")
                                }
                            }

                            override fun onCancelled(gestureDescription: GestureDescription?) {
                                if (cont.isActive) {
                                    cont.resume(false to "Double-tap gesture at ($x, $y) was cancelled by the system.")
                                }
                            }
                        },
                        null
                    )
                    if (!dispatched && cont.isActive) {
                        cont.resume(false to "System rejected double-tap gesture dispatch at ($x, $y).")
                    }
                } catch (e: Exception) {
                    if (cont.isActive) {
                        cont.resume(false to "Failed to dispatch double-tap gesture: ${e.localizedMessage}")
                    }
                }
            }
        }

        return outcome ?: (false to "Double-tap gesture at ($x, $y) timed out.")
    }

    /**
     * Dispatches a real directional or coordinate swipe gesture via AccessibilityService.dispatchGesture.
     */
    suspend fun dispatchSwipeGesture(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 300L
    ): Pair<Boolean, String> {
        val displayMetrics = resources.displayMetrics
        val maxW = displayMetrics.widthPixels.toFloat().coerceAtLeast(1080f)
        val maxH = displayMetrics.heightPixels.toFloat().coerceAtLeast(2400f)

        if (startX < 0f || startY < 0f || endX < 0f || endY < 0f ||
            startX > maxW || endX > maxW || startY > maxH || endY > maxH
        ) {
            return false to "Invalid swipe coordinates ($startX,$startY -> $endX,$endY): outside screen bounds."
        }

        val safeDuration = durationMs.coerceIn(100L, 2000L)
        val outcome = withTimeoutOrNull(safeDuration + 2500L) {
            suspendCancellableCoroutine<Pair<Boolean, String>> { cont ->
                try {
                    val path = Path().apply {
                        moveTo(startX, startY)
                        lineTo(endX, endY)
                    }
                    val stroke = GestureDescription.StrokeDescription(path, 0L, safeDuration)
                    val gesture = GestureDescription.Builder().addStroke(stroke).build()
                    val dispatched = dispatchGesture(
                        gesture,
                        object : GestureResultCallback() {
                            override fun onCompleted(gestureDescription: GestureDescription?) {
                                if (cont.isActive) {
                                    cont.resume(true to "Dispatched swipe gesture from ($startX, $startY) to ($endX, $endY).")
                                }
                            }

                            override fun onCancelled(gestureDescription: GestureDescription?) {
                                if (cont.isActive) {
                                    cont.resume(false to "Swipe gesture was cancelled by the system.")
                                }
                            }
                        },
                        null
                    )
                    if (!dispatched && cont.isActive) {
                        cont.resume(false to "System rejected swipe gesture dispatch.")
                    }
                } catch (e: Exception) {
                    if (cont.isActive) {
                        cont.resume(false to "Failed to dispatch swipe gesture: ${e.localizedMessage}")
                    }
                }
            }
        }

        return outcome ?: (false to "Swipe gesture timed out.")
    }

    /**
     * Captures a live software [android.graphics.Bitmap] on Android 11+ (API 30+) for
     * multimodal Vision AI OCR, game/HUD perception, and pixel-diff verification.
     * Caller owns recycling the returned Bitmap.
     */
    suspend fun captureRealScreenshotBitmap(): android.graphics.Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return withTimeoutOrNull(4000L) {
            suspendCancellableCoroutine<android.graphics.Bitmap?> { cont ->
                try {
                    takeScreenshot(
                        Display.DEFAULT_DISPLAY,
                        mainExecutor,
                        object : TakeScreenshotCallback {
                            override fun onSuccess(screenshot: ScreenshotResult) {
                                val buffer = screenshot.hardwareBuffer
                                var swBitmap: android.graphics.Bitmap? = null
                                try {
                                    val hwBitmap = android.graphics.Bitmap.wrapHardwareBuffer(
                                        buffer,
                                        screenshot.colorSpace
                                    )
                                    swBitmap = hwBitmap?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                                    hwBitmap?.recycle()
                                } catch (_: Throwable) {
                                    swBitmap = null
                                } finally {
                                    buffer.close()
                                }
                                if (cont.isActive) cont.resume(swBitmap)
                            }

                            override fun onFailure(errorCode: Int) {
                                if (cont.isActive) cont.resume(null)
                            }
                        }
                    )
                } catch (_: Throwable) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    /**
     * Dispatches a real long-press gesture at (x, y) for [durationMs] (default 650ms).
     */
    suspend fun dispatchLongPressGesture(x: Float, y: Float, durationMs: Long = 650L): Pair<Boolean, String> {
        val displayMetrics = resources.displayMetrics
        val maxW = displayMetrics.widthPixels.toFloat().coerceAtLeast(1080f)
        val maxH = displayMetrics.heightPixels.toFloat().coerceAtLeast(2400f)

        if (x < 0f || y < 0f || x > maxW || y > maxH) {
            return false to "Invalid long-press coordinates ($x, $y): outside screen bounds (0..$maxW, 0..$maxH)."
        }

        val safeDuration = durationMs.coerceIn(450L, 2500L)
        val outcome = withTimeoutOrNull(safeDuration + 2500L) {
            suspendCancellableCoroutine<Pair<Boolean, String>> { cont ->
                try {
                    val path = Path().apply { moveTo(x, y) }
                    val stroke = GestureDescription.StrokeDescription(path, 0L, safeDuration)
                    val gesture = GestureDescription.Builder().addStroke(stroke).build()
                    val dispatched = dispatchGesture(
                        gesture,
                        object : GestureResultCallback() {
                            override fun onCompleted(gestureDescription: GestureDescription?) {
                                if (cont.isActive) cont.resume(true to "Dispatched long-press gesture at ($x, $y) for ${safeDuration}ms.")
                            }

                            override fun onCancelled(gestureDescription: GestureDescription?) {
                                if (cont.isActive) cont.resume(false to "Long-press gesture at ($x, $y) was cancelled by the system.")
                            }
                        },
                        null
                    )
                    if (!dispatched && cont.isActive) {
                        cont.resume(false to "System rejected long-press gesture at ($x, $y).")
                    }
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(false to "Failed to dispatch long-press gesture: ${e.localizedMessage}")
                }
            }
        }
        return outcome ?: (false to "Long-press gesture at ($x, $y) timed out.")
    }

    /**
     * Dispatches a real press-and-drag gesture (hold at start, then drag smoothly to end)
     * for sliders, drag-and-drop interfaces, maps, and game controls.
     */
    suspend fun dispatchDragGesture(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        holdMs: Long = 350L,
        dragDurationMs: Long = 500L
    ): Pair<Boolean, String> {
        val displayMetrics = resources.displayMetrics
        val maxW = displayMetrics.widthPixels.toFloat().coerceAtLeast(1080f)
        val maxH = displayMetrics.heightPixels.toFloat().coerceAtLeast(2400f)

        if (startX < 0f || startY < 0f || endX < 0f || endY < 0f ||
            startX > maxW || endX > maxW || startY > maxH || endY > maxH
        ) {
            return false to "Invalid drag coordinates ($startX,$startY -> $endX,$endY): outside screen bounds."
        }

        val safeHold = holdMs.coerceIn(100L, 1200L)
        val safeDrag = dragDurationMs.coerceIn(150L, 2000L)
        val outcome = withTimeoutOrNull(safeHold + safeDrag + 2500L) {
            suspendCancellableCoroutine<Pair<Boolean, String>> { cont ->
                try {
                    val holdPath = Path().apply { moveTo(startX, startY) }
                    val dragPath = Path().apply {
                        moveTo(startX, startY)
                        lineTo(endX, endY)
                    }
                    val holdStroke = GestureDescription.StrokeDescription(holdPath, 0L, safeHold, true)
                    val dragStroke = holdStroke.continueStroke(dragPath, 0L, safeDrag, false)
                    val gesture = GestureDescription.Builder().addStroke(dragStroke).build()
                    val dispatched = dispatchGesture(
                        gesture,
                        object : GestureResultCallback() {
                            override fun onCompleted(gestureDescription: GestureDescription?) {
                                if (cont.isActive) {
                                    cont.resume(true to "Dispatched drag gesture from ($startX, $startY) to ($endX, $endY).")
                                }
                            }

                            override fun onCancelled(gestureDescription: GestureDescription?) {
                                if (cont.isActive) cont.resume(false to "Drag gesture was cancelled by the system.")
                            }
                        },
                        null
                    )
                    if (!dispatched && cont.isActive) {
                        cont.resume(false to "System rejected drag gesture dispatch.")
                    }
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(false to "Failed to dispatch drag gesture: ${e.localizedMessage}")
                }
            }
        }
        return outcome ?: (false to "Drag gesture timed out.")
    }

    /**
     * Dispatches a screen-adaptive directional scroll/swipe gesture using live display dimensions.
     * Note: "down" scrolls content downward (finger drags from bottom toward top);
     * "up" scrolls content upward (finger drags from top toward bottom).
     */
    suspend fun dispatchDirectionalScroll(
        direction: String,
        distanceFraction: Float = 0.55f,
        durationMs: Long = 320L
    ): Pair<Boolean, String> {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat().coerceAtLeast(720f)
        val h = dm.heightPixels.toFloat().coerceAtLeast(1280f)
        val frac = distanceFraction.coerceIn(0.2f, 0.75f)
        val cx = w * 0.5f
        val cy = h * 0.5f
        val halfV = h * (frac / 2f)
        val halfH = w * (frac / 2f)

        return when (direction.trim().lowercase()) {
            "down", "scroll_down", "forward" -> dispatchSwipeGesture(cx, cy + halfV, cx, cy - halfV, durationMs)
            "up", "scroll_up", "backward" -> dispatchSwipeGesture(cx, cy - halfV, cx, cy + halfV, durationMs)
            "left", "scroll_left" -> dispatchSwipeGesture(cx + halfH, cy, cx - halfH, cy, durationMs)
            "right", "scroll_right" -> dispatchSwipeGesture(cx - halfH, cy, cx + halfH, cy, durationMs)
            else -> false to "Unknown scroll direction '$direction'."
        }
    }
}
