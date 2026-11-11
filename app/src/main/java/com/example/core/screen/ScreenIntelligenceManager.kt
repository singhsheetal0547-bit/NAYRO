package com.example.core.screen

import android.content.Context
import com.example.core.accessibility.AccessibilityConnectionManager
import com.example.core.accessibility.NayroAccessibilityService
import kotlinx.coroutines.delay

/**
 * High-level Screen Intelligence Manager.
 * Reads live Android UI hierarchy, summarizes visible text, and evaluates screen changes.
 */
class ScreenIntelligenceManager(private val context: Context) {

    fun isAccessibilityOperational(): Boolean {
        return NayroAccessibilityService.isConnected()
    }

    fun captureCurrentSnapshot(): ScreenSnapshot? {
        val service = NayroAccessibilityService.instance ?: return null
        val rootNode = service.getSafeRootInActiveWindow() ?: return null

        val parsedTree = AccessibilityTreeReader.readTree(rootNode)
        val texts = AccessibilityTreeReader.flattenAllTexts(parsedTree)
        val interactive = AccessibilityTreeReader.extractInteractiveElements(parsedTree)

        return ScreenSnapshot(
            packageName = rootNode.packageName?.toString() ?: AccessibilityConnectionManager.lastActivePackage.value,
            visibleTexts = texts,
            interactiveElements = interactive,
            totalNodeCount = texts.size + interactive.size
        )
    }

    fun getScreenContext(): ScreenContext {
        val snapshot = captureCurrentSnapshot()
        if (snapshot == null) {
            val isConn = isAccessibilityOperational()
            return ScreenContext(
                packageName = AccessibilityConnectionManager.lastActivePackage.value,
                screenSummary = if (isConn) "Screen content currently inaccessible or loading." else "Accessibility Service is not enabled or connected.",
                visibleTextPreview = "None available.",
                interactiveCount = 0,
                isAccessibilityAvailable = isConn
            )
        }

        val textPreview = snapshot.visibleTexts.take(8).joinToString(" | ")
        val summary = "App: ${snapshot.packageName ?: "Unknown"}, ${snapshot.visibleTexts.size} text elements, ${snapshot.interactiveElements.size} interactive controls."

        return ScreenContext(
            packageName = snapshot.packageName,
            screenSummary = summary,
            visibleTextPreview = textPreview.ifBlank { "No text labels visible" },
            interactiveCount = snapshot.interactiveElements.size,
            isAccessibilityAvailable = true
        )
    }

    /**
     * Answers natural language questions like "Babu, screen par kya likha hai?"
     */
    fun describeScreenContent(): String {
        val snapshot = captureCurrentSnapshot()
        if (snapshot == null) {
            return if (!isAccessibilityOperational()) {
                "Screen reading requires NAYRO Accessibility permission. Please enable it in Android Accessibility Settings."
            } else {
                "I couldn't read the screen right now. The active app may be protected or transitioning."
            }
        }

        if (snapshot.visibleTexts.isEmpty()) {
            return "The active screen (${snapshot.packageName ?: "current app"}) does not show any readable text right now."
        }

        val preview = snapshot.visibleTexts.distinct().take(12).joinToString("\n• ", prefix = "• ")
        return "Screen par yeh likha hai (${snapshot.packageName ?: "Current App"}):\n$preview"
    }

    suspend fun verifyScreenChange(before: ScreenSnapshot?, waitMs: Long = 600L): ScreenChangeResult {
        delay(waitMs)
        val after = captureCurrentSnapshot()
        return ScreenChangeDetector.detectChange(before, after)
    }

    /**
     * Phase 4 — Semantic Screen Analysis: detects permission/system dialogs, loading spinners,
     * and error/offline screens from the current [ScreenSnapshot].
     */
    fun analyzeScreenSemantics(snapshot: ScreenSnapshot? = captureCurrentSnapshot()): ScreenSemanticSignals {
        if (snapshot == null) {
            return ScreenSemanticSignals(
                hasDialogOrPopup = false,
                isLoadingState = false,
                isErrorScreen = false
            )
        }
        val combinedLower = snapshot.visibleTexts.joinToString(" ").lowercase()
        val hasDialog = combinedLower.contains("while using the app") ||
            combinedLower.contains("only this time") ||
            (combinedLower.contains("allow") && combinedLower.contains("deny")) ||
            (combinedLower.contains("ok") && combinedLower.contains("cancel"))
        val isLoading = combinedLower.contains("loading...") ||
            combinedLower.contains("please wait") ||
            combinedLower.contains("buffering") ||
            combinedLower.contains("connecting...")
        val isError = combinedLower.contains("something went wrong") ||
            combinedLower.contains("no internet") ||
            combinedLower.contains("connection failed") ||
            combinedLower.contains("try again")

        return ScreenSemanticSignals(
            hasDialogOrPopup = hasDialog,
            isLoadingState = isLoading,
            isErrorScreen = isError
        )
    }

    /**
     * Section 6 — Visual & Semantic Target Identification:
     * Resolves a user query (label, contentDescription, or viewId) against the captured [ScreenSnapshot]
     * and returns its screen coordinates `(centerX, centerY)`, confidence score, and targeting strategy.
     */
    fun locateVisualTarget(query: String, snapshot: ScreenSnapshot? = captureCurrentSnapshot()): VisualTargetMatch? {
        return locateTargetInSnapshot(query, snapshot)
    }

    companion object {
        fun locateTargetInSnapshot(query: String, snapshot: ScreenSnapshot?): VisualTargetMatch? {
            val cleanQuery = query.trim()
            if (cleanQuery.isEmpty() || snapshot == null) return null

            // 1. Exact text or contentDescription match
            snapshot.interactiveElements.firstOrNull { node ->
                node.text.equals(cleanQuery, ignoreCase = true) ||
                    node.contentDescription.equals(cleanQuery, ignoreCase = true)
            }?.let { matched ->
                return VisualTargetMatch(
                    label = matched.displayLabel,
                    centerX = matched.centerX,
                    centerY = matched.centerY,
                    confidence = 0.96f,
                    strategy = if (matched.isClickable) "SEMANTIC_EXACT_NODE" else "COORDINATE_BOUNDS_EXACT"
                )
            }

            // 2. Partial text or contentDescription match
            snapshot.interactiveElements.firstOrNull { node ->
                node.text?.contains(cleanQuery, ignoreCase = true) == true ||
                    node.contentDescription?.contains(cleanQuery, ignoreCase = true) == true
            }?.let { matched ->
                return VisualTargetMatch(
                    label = matched.displayLabel,
                    centerX = matched.centerX,
                    centerY = matched.centerY,
                    confidence = 0.82f,
                    strategy = if (matched.isClickable) "SEMANTIC_PARTIAL_NODE" else "COORDINATE_BOUNDS_PARTIAL"
                )
            }

            // 3. View ID resource name match
            snapshot.interactiveElements.firstOrNull { node ->
                node.viewId?.contains(cleanQuery, ignoreCase = true) == true
            }?.let { matched ->
                return VisualTargetMatch(
                    label = matched.displayLabel,
                    centerX = matched.centerX,
                    centerY = matched.centerY,
                    confidence = 0.74f,
                    strategy = "VIEW_ID_MATCH"
                )
            }

            return null
        }
    }
}

data class ScreenSemanticSignals(
    val hasDialogOrPopup: Boolean,
    val isLoadingState: Boolean,
    val isErrorScreen: Boolean
)


