package com.example.core.screen

data class ScreenNodeInfo(
    val text: String?,
    val contentDescription: String?,
    val viewId: String?,
    val className: String,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean,
    val isPassword: Boolean = false,
    val children: List<ScreenNodeInfo> = emptyList()
) {
    val displayLabel: String
        get() = text?.takeIf { it.isNotBlank() }
            ?: contentDescription?.takeIf { it.isNotBlank() }
            ?: viewId?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: className.substringAfterLast('.')
}

data class ScreenSnapshot(
    val packageName: String?,
    val timestamp: Long = System.currentTimeMillis(),
    val visibleTexts: List<String>,
    val interactiveElements: List<ScreenNodeInfo>,
    val totalNodeCount: Int
)

data class ScreenContext(
    val packageName: String?,
    val screenSummary: String,
    val visibleTextPreview: String,
    val interactiveCount: Int,
    val isAccessibilityAvailable: Boolean
)
