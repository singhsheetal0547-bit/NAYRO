package com.advanceagent.core.vision

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Extracts a structured list of interactive & visible UI elements from the active screen.
 */
object UiGroundingEngine {

    fun extractScreenElements(root: AccessibilityNodeInfo?, maxNodes: Int = 60): List<UiElementInfo> {
        if (root == null) return emptyList()
        val elements = mutableListOf<UiElementInfo>()
        traverse(root, elements, maxNodes)
        return elements
    }

    private fun traverse(
        node: AccessibilityNodeInfo,
        out: MutableList<UiElementInfo>,
        maxNodes: Int
    ) {
        if (out.size >= maxNodes) return

        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val viewId = node.viewIdResourceName?.substringAfterLast("/")?.trim().orEmpty()

        if (node.isClickable || node.isEditable || node.isScrollable || text.isNotEmpty() || desc.isNotEmpty()) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.width() > 0 && rect.height() > 0) {
                out.add(
                    UiElementInfo(
                        text = text,
                        contentDescription = desc,
                        viewId = viewId,
                        className = node.className?.toString()?.substringAfterLast(".").orEmpty(),
                        clickable = node.isClickable,
                        editable = node.isEditable,
                        scrollable = node.isScrollable,
                        left = rect.left,
                        top = rect.top,
                        right = rect.right,
                        bottom = rect.bottom
                    )
                )
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverse(child, out, maxNodes)
        }
    }
}
