package com.advanceagent.core.tools

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.core.accessibility.NayroAccessibilityService

class AgentAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance == this) instance = null
        super.onDestroy()
    }

    fun performNavigation(actionName: String): Boolean {
        return when (actionName.uppercase()) {
            "HOME" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "BACK" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "RECENTS" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "NOTIFICATIONS" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            else -> false
        }
    }

    fun clickNodeByText(text: String): Boolean {
        return clickNodeInRoot(rootInActiveWindow, text)
    }

    fun tapAt(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun scrollDirection(direction: String): Boolean {
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels.toFloat().coerceAtLeast(360f)
        val h = metrics.heightPixels.toFloat().coerceAtLeast(640f)
        val cx = w / 2f
        val cy = h / 2f

        val path = Path().apply {
            when (direction.trim().uppercase()) {
                "UP" -> {
                    moveTo(cx, h * 0.28f)
                    lineTo(cx, h * 0.72f)
                }
                "LEFT" -> {
                    moveTo(w * 0.80f, cy)
                    lineTo(w * 0.20f, cy)
                }
                "RIGHT" -> {
                    moveTo(w * 0.20f, cy)
                    lineTo(w * 0.80f, cy)
                }
                else -> { // DOWN
                    moveTo(cx, h * 0.72f)
                    lineTo(cx, h * 0.28f)
                }
            }
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 260))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun inputTextIntoFocusedNode(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        return inputTextInRoot(root, text)
    }

    companion object {
        @Volatile
        var instance: AgentAccessibilityService? = null
            private set

        fun clickNodeInRoot(root: AccessibilityNodeInfo?, text: String): Boolean {
            val activeRoot = root ?: return false
            val matches = activeRoot.findAccessibilityNodeInfosByText(text)
            for (node in matches) {
                var current: AccessibilityNodeInfo? = node
                while (current != null) {
                    if (current.isClickable) {
                        return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                    current = current.parent
                }
            }
            return false
        }

        fun inputTextInRoot(root: AccessibilityNodeInfo?, text: String): Boolean {
            val activeRoot = root ?: return false
            val focused = activeRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }

        fun dispatchTapOnActiveService(x: Float, y: Float): Boolean {
            val active: AccessibilityService = instance ?: NayroAccessibilityService.instance ?: return false
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
                .build()
            return active.dispatchGesture(gesture, null, null)
        }
    }
}
