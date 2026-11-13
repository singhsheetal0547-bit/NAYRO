package com.example.core.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Controller for executing semantic AccessibilityNodeInfo actions.
 * Safely traverses the UI tree, identifies matching elements, and invokes standard actions.
 */
object AccessibilityActionController {

    private const val TAG = "NayroActionCtrl"

    fun isReady(): Boolean = NayroAccessibilityService.isConnected()

    fun performClick(targetText: String? = null, viewId: String? = null): Boolean {
        val service = NayroAccessibilityService.instance ?: return false
        val root = service.getSafeRootInActiveWindow() ?: return false

        try {
            val targetNode = findClickableTarget(root, targetText, viewId)
            if (targetNode != null) {
                val success = targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, "performClick on '${targetNode.text ?: targetNode.viewIdResourceName}': $success")
                return success
            }
            Log.w(TAG, "performClick: Target node not found for text='$targetText', id='$viewId'")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "performClick error: ${e.message}")
            return false
        }
    }

    fun performLongClick(targetText: String? = null, viewId: String? = null): Boolean {
        val service = NayroAccessibilityService.instance ?: return false
        val root = service.getSafeRootInActiveWindow() ?: return false

        try {
            val targetNode = findClickableTarget(root, targetText, viewId)
            if (targetNode != null) {
                return targetNode.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            }
            return false
        } catch (e: Exception) {
            Log.e(TAG, "performLongClick error: ${e.message}")
            return false
        }
    }

    fun performSetText(text: String, viewId: String? = null, targetText: String? = null): Boolean {
        val service = NayroAccessibilityService.instance ?: return false
        val root = service.getSafeRootInActiveWindow() ?: return false

        try {
            val targetNode = findEditableTarget(root, viewId, targetText)
            if (targetNode != null) {
                targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val arguments = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                val success = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                Log.d(TAG, "performSetText '$text' success=$success")
                return success
            }
            Log.w(TAG, "performSetText: Editable node not found")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "performSetText error: ${e.message}")
            return false
        }
    }

    fun performScrollForward(viewId: String? = null): Boolean {
        val service = NayroAccessibilityService.instance ?: return false
        val root = service.getSafeRootInActiveWindow() ?: return false

        try {
            val scrollNode = findScrollableTarget(root, viewId)
            return scrollNode?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "performScrollForward error: ${e.message}")
            return false
        }
    }

    fun performScrollBackward(viewId: String? = null): Boolean {
        val service = NayroAccessibilityService.instance ?: return false
        val root = service.getSafeRootInActiveWindow() ?: return false

        try {
            val scrollNode = findScrollableTarget(root, viewId)
            return scrollNode?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "performScrollBackward error: ${e.message}")
            return false
        }
    }

    fun performGlobalBack(): Boolean {
        val service = NayroAccessibilityService.instance ?: return false
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    fun performGlobalHome(): Boolean {
        val service = NayroAccessibilityService.instance ?: return false
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    }

    fun performGlobalRecents(): Boolean {
        val service = NayroAccessibilityService.instance ?: return false
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
    }

    /**
     * Phase 5 — Detects and dismisses an unexpected modal dialog ("Cancel", "Not now", "Dismiss", "Close", or Back).
     */
    fun dismissDialogIfPresent(): Boolean {
        for (dismissLabel in listOf("Not now", "Dismiss", "Close", "Cancel", "Later", "No thanks")) {
            if (performClick(targetText = dismissLabel)) {
                return true
            }
        }
        return performGlobalBack()
    }

    /**
     * Section 6 — Semantic-first click with coordinate-tap fallback when `ACTION_CLICK` returns false
     * or when a matching visible label is rendered inside a custom non-clickable container.
     */
    suspend fun performClickWithCoordinateFallback(targetText: String? = null, viewId: String? = null): Boolean {
        if (performClick(targetText, viewId)) {
            return true
        }
        val service = NayroAccessibilityService.instance ?: return false
        val bounds = findTargetBoundsInScreen(targetText, viewId) ?: return false
        if (bounds.width() <= 0 || bounds.height() <= 0) return false
        val (ok, _) = service.dispatchTapGesture(bounds.exactCenterX(), bounds.exactCenterY())
        return ok
    }

    /**
     * Locates the screen bounding box [android.graphics.Rect] for a UI element matching [targetText] or [viewId].
     */
    fun findTargetBoundsInScreen(targetText: String? = null, viewId: String? = null): android.graphics.Rect? {
        val service = NayroAccessibilityService.instance ?: return null
        val root = service.getSafeRootInActiveWindow() ?: return null
        try {
            val node = findClickableTarget(root, targetText, viewId)
                ?: if (!targetText.isNullOrBlank()) {
                    root.findAccessibilityNodeInfosByText(targetText).firstOrNull()
                } else null
            if (node != null) {
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)
                if (!rect.isEmpty) return rect
            }
        } catch (e: Exception) {
            Log.e(TAG, "findTargetBoundsInScreen error: ${e.message}")
        }
        return null
    }

    /**
     * Performs an IME Submit / Enter action on the focused editable field (API 30+)
     * or clicks a visible Submit/Search/Send/Go control.
     */
    fun performSubmitAction(): Boolean {
        val service = NayroAccessibilityService.instance ?: return false
        val root = service.getSafeRootInActiveWindow() ?: return false
        try {
            val editable = findEditableTarget(root, null, null)
            if (editable != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                if (editable.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) {
                    return true
                }
            }
            for (label in listOf("Send", "Search", "Submit", "Go", "Done")) {
                if (performClick(targetText = label)) return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "performSubmitAction error: ${e.message}")
        }
        return false
    }

    private fun findClickableTarget(
        root: AccessibilityNodeInfo,
        targetText: String?,
        viewId: String?
    ): AccessibilityNodeInfo? {
        if (!viewId.isNullOrBlank()) {
            val byId = root.findAccessibilityNodeInfosByViewId(viewId)
            byId.firstOrNull { it.isClickable || it.parent?.isClickable == true }?.let {
                return if (it.isClickable) it else it.parent
            }
        }

        if (!targetText.isNullOrBlank()) {
            val byText = root.findAccessibilityNodeInfosByText(targetText)
            for (node in byText) {
                if (node.isClickable) return node
                var parent = node.parent
                while (parent != null) {
                    if (parent.isClickable) return parent
                    parent = parent.parent
                }
            }
        }

        return null
    }

    private fun findEditableTarget(
        root: AccessibilityNodeInfo,
        viewId: String?,
        targetText: String?
    ): AccessibilityNodeInfo? {
        if (!viewId.isNullOrBlank()) {
            val byId = root.findAccessibilityNodeInfosByViewId(viewId)
            byId.firstOrNull { it.isEditable }?.let { return it }
        }

        // BFS traversal for any editable node
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current.isEditable) {
                return current
            }
            for (i in 0 until current.childCount) {
                current.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun findScrollableTarget(root: AccessibilityNodeInfo, viewId: String?): AccessibilityNodeInfo? {
        if (!viewId.isNullOrBlank()) {
            val byId = root.findAccessibilityNodeInfosByViewId(viewId)
            byId.firstOrNull { it.isScrollable }?.let { return it }
        }

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current.isScrollable) return current
            for (i in 0 until current.childCount) {
                current.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }
}
