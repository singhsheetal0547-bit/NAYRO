package com.example.core.screen

data class ParsedUIElements(
    val buttons: List<ScreenNodeInfo>,
    val textInputs: List<ScreenNodeInfo>,
    val scrollContainers: List<ScreenNodeInfo>,
    val textLabels: List<ScreenNodeInfo>
)

object UIElementParser {

    fun parse(root: ScreenNodeInfo?): ParsedUIElements {
        if (root == null) return ParsedUIElements(emptyList(), emptyList(), emptyList(), emptyList())

        val buttons = mutableListOf<ScreenNodeInfo>()
        val textInputs = mutableListOf<ScreenNodeInfo>()
        val scrollContainers = mutableListOf<ScreenNodeInfo>()
        val textLabels = mutableListOf<ScreenNodeInfo>()

        val queue = ArrayDeque<ScreenNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()

            if (current.isEditable || current.className.contains("EditText", ignoreCase = true)) {
                textInputs.add(current)
            } else if (current.isClickable || current.className.contains("Button", ignoreCase = true)) {
                buttons.add(current)
            } else if (current.isScrollable || current.className.contains("Scroll", ignoreCase = true) || current.className.contains("Recycler", ignoreCase = true)) {
                scrollContainers.add(current)
            } else if (!current.text.isNullOrBlank()) {
                textLabels.add(current)
            }

            queue.addAll(current.children)
        }

        return ParsedUIElements(
            buttons = buttons,
            textInputs = textInputs,
            scrollContainers = scrollContainers,
            textLabels = textLabels
        )
    }
}
