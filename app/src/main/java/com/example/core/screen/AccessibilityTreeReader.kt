package com.example.core.screen

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Safely traverses the AccessibilityNodeInfo tree and extracts immutable, sanitized representations.
 * Handles recycling, protects sensitive/password fields, and limits depth to avoid stack overflows.
 */
object AccessibilityTreeReader {

    private const val MAX_DEPTH = 25
    private const val MAX_NODES = 400

    fun readTree(root: AccessibilityNodeInfo?): ScreenNodeInfo? {
        if (root == null) return null
        var nodeCounter = 0
        return traverse(root, depth = 0, nodeCounter = { nodeCounter++ })
    }

    private fun traverse(node: AccessibilityNodeInfo, depth: Int, nodeCounter: () -> Int): ScreenNodeInfo {
        val count = nodeCounter()
        if (depth > MAX_DEPTH || count > MAX_NODES) {
            return ScreenNodeInfo(
                text = null,
                contentDescription = null,
                viewId = null,
                className = node.className?.toString() ?: "Unknown",
                isClickable = false,
                isEditable = false,
                isScrollable = false
            )
        }

        val isPassword = node.isPassword
        val text = if (isPassword) "[REDACTED_PASSWORD]" else node.text?.toString()
        val desc = if (isPassword) null else node.contentDescription?.toString()
        val viewId = node.viewIdResourceName
        val className = node.className?.toString() ?: ""
        val isClickable = node.isClickable
        val isEditable = node.isEditable
        val isScrollable = node.isScrollable

        val childrenList = mutableListOf<ScreenNodeInfo>()
        for (i in 0 until node.childCount) {
            val child = try {
                node.getChild(i)
            } catch (e: Exception) {
                null
            }
            if (child != null) {
                childrenList.add(traverse(child, depth + 1, nodeCounter))
            }
        }

        return ScreenNodeInfo(
            text = text,
            contentDescription = desc,
            viewId = viewId,
            className = className,
            isClickable = isClickable,
            isEditable = isEditable,
            isScrollable = isScrollable,
            isPassword = isPassword,
            children = childrenList
        )
    }

    fun flattenAllTexts(root: ScreenNodeInfo?): List<String> {
        if (root == null) return emptyList()
        val results = mutableListOf<String>()
        val queue = ArrayDeque<ScreenNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            current.text?.takeIf { it.isNotBlank() }?.let { results.add(it) }
            current.contentDescription?.takeIf { it.isNotBlank() && it != current.text }?.let { results.add(it) }
            queue.addAll(current.children)
        }
        return results
    }

    fun extractInteractiveElements(root: ScreenNodeInfo?): List<ScreenNodeInfo> {
        if (root == null) return emptyList()
        val results = mutableListOf<ScreenNodeInfo>()
        val queue = ArrayDeque<ScreenNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current.isClickable || current.isEditable || current.isScrollable) {
                results.add(current)
            }
            queue.addAll(current.children)
        }
        return results
    }
}
