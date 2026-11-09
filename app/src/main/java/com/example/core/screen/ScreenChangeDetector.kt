package com.example.core.screen

data class ScreenChangeResult(
    val hasChanged: Boolean,
    val packageChanged: Boolean,
    val previousPackage: String?,
    val currentPackage: String?,
    val newTextsCount: Int,
    val description: String
)

object ScreenChangeDetector {

    fun detectChange(before: ScreenSnapshot?, after: ScreenSnapshot?): ScreenChangeResult {
        if (before == null || after == null) {
            return ScreenChangeResult(
                hasChanged = false,
                packageChanged = false,
                previousPackage = before?.packageName,
                currentPackage = after?.packageName,
                newTextsCount = 0,
                description = "Insufficient snapshot data to detect transition."
            )
        }

        val packageChanged = before.packageName != after.packageName
        val beforeSet = before.visibleTexts.toSet()
        val afterSet = after.visibleTexts.toSet()

        val newTexts = afterSet - beforeSet
        val nodeCountDiff = kotlin.math.abs(after.totalNodeCount - before.totalNodeCount)

        val hasChanged = packageChanged || newTexts.isNotEmpty() || nodeCountDiff > 3

        val desc = when {
            packageChanged -> "Active package changed from ${before.packageName} to ${after.packageName}"
            newTexts.isNotEmpty() -> "Screen updated with ${newTexts.size} new text elements (e.g. '${newTexts.firstOrNull()?.take(20)}')"
            nodeCountDiff > 3 -> "UI layout changed (node count shifted by $nodeCountDiff)"
            else -> "No significant UI change observed"
        }

        return ScreenChangeResult(
            hasChanged = hasChanged,
            packageChanged = packageChanged,
            previousPackage = before.packageName,
            currentPackage = after.packageName,
            newTextsCount = newTexts.size,
            description = desc
        )
    }
}
