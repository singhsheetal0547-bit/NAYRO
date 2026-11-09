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
