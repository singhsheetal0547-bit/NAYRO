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
                                buffer.close()
                                if (width > 0 && height > 0) {
                                    if (cont.isActive) {
                                        cont.resume(
                                            true to "Verified screenshot captured (${width}x${height} px, timestamp=$timestamp)."
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
}
