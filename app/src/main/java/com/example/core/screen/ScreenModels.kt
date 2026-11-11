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
    val boundsLeft: Int = 0,
    val boundsTop: Int = 0,
    val boundsRight: Int = 0,
    val boundsBottom: Int = 0,
    val children: List<ScreenNodeInfo> = emptyList()
) {
    val displayLabel: String
        get() = text?.takeIf { it.isNotBlank() }
            ?: contentDescription?.takeIf { it.isNotBlank() }
            ?: viewId?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: className.substringAfterLast('.')

    val centerX: Int
        get() = (boundsLeft + boundsRight) / 2

    val centerY: Int
        get() = (boundsTop + boundsBottom) / 2

    val hasValidBounds: Boolean
        get() = boundsRight > boundsLeft && boundsBottom > boundsTop
}

data class VisualTargetMatch(
    val label: String,
    val centerX: Int,
    val centerY: Int,
    val confidence: Float,
    val strategy: String
)

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

