package com.example.core.accessibility

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * NAYRO Accessibility Service: Real Android Accessibility engine for screen understanding,
 * node traversal, and user-authorized UI automation actions.
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
     * Note: Callers should be aware that nodes can be recycled.
     */
    fun getSafeRootInActiveWindow(): AccessibilityNodeInfo? {
        return try {
            rootInActiveWindow
        } catch (e: Exception) {
            Log.e(TAG, "Error obtaining root node: ${e.message}")
            null
        }
    }
}
