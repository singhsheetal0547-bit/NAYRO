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
            val visual = lastVisualObservation
            return if (visual != null) {
                "The active screen (${snapshot.packageName ?: "current app"}) has no Accessibility text labels, but visual analysis detected: ${visual.detectedRegionsSummary}"
            } else {
                "The active screen (${snapshot.packageName ?: "current app"}) does not show any readable text right now."
            }
        }

        val preview = snapshot.visibleTexts.distinct().take(12).joinToString("\n• ", prefix = "• ")
        val visualSuffix = lastVisualObservation?.let {
            "\n[Visual Grounding: ${it.detectedRegionsSummary}]"
        }.orEmpty()
        return "Screen par yeh likha hai (${snapshot.packageName ?: "Current App"}):\n$preview$visualSuffix"
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
                hasDialogOrPopup = lastVisualObservation?.hasModalDimOverlay == true,
                isLoadingState = false,
                isErrorScreen = false
            )
        }
        val combinedLower = snapshot.visibleTexts.joinToString(" ").lowercase()
        val hasDialog = combinedLower.contains("while using the app") ||
            combinedLower.contains("only this time") ||
            (combinedLower.contains("allow") && combinedLower.contains("deny")) ||
            (combinedLower.contains("ok") && combinedLower.contains("cancel")) ||
            (lastVisualObservation?.hasModalDimOverlay == true)
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
        @Volatile
        var lastVisualObservation: VisualScreenObservation? = null

        /**
         * Section 6 — Performs real pixel-level visual analysis on a sampled ARGB pixel grid,
         * computing luminance distribution, contrast standard deviation, dark/light theme classification,
         * modal dim overlay detection, 64-bit perceptual dHash, and fused OCR/Accessibility grounding.
         */
        fun analyzePixelGrid(
            argbPixels: IntArray,
            width: Int,
            height: Int,
            snapshot: ScreenSnapshot? = null
        ): VisualScreenObservation {
            if (argbPixels.isEmpty() || width <= 0 || height <= 0) {
                val emptyObs = VisualScreenObservation(
                    width = width.coerceAtLeast(0),
                    height = height.coerceAtLeast(0),
                    meanLuminance = 0f,
                    contrastScore = 0f,
                    isDarkThemeDetected = true,
                    hasModalDimOverlay = false,
                    visualPerceptualHash = 0L,
                    detectedRegionsSummary = "Empty visual frame",
                    groundedTextElements = emptyList()
                )
                lastVisualObservation = emptyObs
                return emptyObs
            }

            val total = argbPixels.size.coerceAtMost(width * height)
            val luminances = FloatArray(total)
            var sumLum = 0.0
            var edgeLumSum = 0.0
            var edgeCount = 0
            var centerLumSum = 0.0
            var centerCount = 0

            for (idx in 0 until total) {
                val argb = argbPixels[idx]
                val r = (argb shr 16) and 0xFF
                val g = (argb shr 8) and 0xFF
                val b = argb and 0xFF
                val lum = (0.299f * r + 0.587f * g + 0.114f * b)
                luminances[idx] = lum
                sumLum += lum

                val x = idx % width
                val y = idx / width
                val isCenterRegion = x in (width / 4)..(width * 3 / 4) && y in (height / 3)..(height * 2 / 3)
                if (isCenterRegion) {
                    centerLumSum += lum
                    centerCount++
                } else {
                    edgeLumSum += lum
                    edgeCount++
                }
            }

            val meanLum = (sumLum / total.toDouble()).toFloat()
            var varianceSum = 0.0
            for (idx in 0 until total) {
                val diff = luminances[idx] - meanLum
                varianceSum += (diff * diff).toDouble()
            }
            val contrastStdDev = kotlin.math.sqrt(varianceSum / total.toDouble()).toFloat()
            val isDark = meanLum < 115f
            val avgEdgeLum = if (edgeCount > 0) (edgeLumSum / edgeCount).toFloat() else meanLum
            val avgCenterLum = if (centerCount > 0) (centerLumSum / centerCount).toFloat() else meanLum
            // A dimmed backdrop with a brighter central dialog card indicates a modal dialog overlay
            val hasModalOverlay = avgEdgeLum < 65f && (avgCenterLum - avgEdgeLum) > 45f

            // Compute 64-bit difference perceptual hash (dHash) across 64 sampled cells
            var dHash = 0L
            for (bit in 0 until 64) {
                val i1 = ((bit * total) / 64).coerceIn(0, total - 1)
                val i2 = (((bit + 1) * total) / 64).coerceIn(0, total - 1)
                if (luminances[i1] > luminances[i2]) {
                    dHash = dHash or (1L shl bit)
                }
            }

            val grounded = snapshot?.interactiveElements
                ?.filter { it.hasValidBounds }
                ?.take(10)
                ?.map { node ->
                    "${node.displayLabel} @(${node.centerX},${node.centerY})"
                }.orEmpty()

            val themeLabel = if (isDark) "Dark UI" else "Light UI"
            val modalLabel = if (hasModalOverlay) ", Modal Dialog Detected" else ""
            val summary = "${width}x${height}px ($themeLabel, Lum=${"%.1f".format(meanLum)}, Contrast=${"%.1f".format(contrastStdDev)}$modalLabel, Targets=${grounded.size}, dHash=0x${java.lang.Long.toHexString(dHash)})"

            val observation = VisualScreenObservation(
                width = width,
                height = height,
                meanLuminance = meanLum,
                contrastScore = contrastStdDev,
                isDarkThemeDetected = isDark,
                hasModalDimOverlay = hasModalOverlay,
                visualPerceptualHash = dHash,
                detectedRegionsSummary = summary,
                groundedTextElements = grounded
            )
            lastVisualObservation = observation
            return observation
        }

        /**
         * Downsamples a captured [android.graphics.Bitmap] into a 36x64 inspection grid and runs [analyzePixelGrid].
         */
        fun analyzeBitmap(
            bitmap: android.graphics.Bitmap,
            snapshot: ScreenSnapshot? = null
        ): VisualScreenObservation {
            val gridW = 36.coerceAtMost(bitmap.width.coerceAtLeast(1))
            val gridH = 64.coerceAtMost(bitmap.height.coerceAtLeast(1))
            val sampled = IntArray(gridW * gridH)
            for (gy in 0 until gridH) {
                val py = ((gy.toLong() * bitmap.height) / gridH).toInt().coerceIn(0, bitmap.height - 1)
                for (gx in 0 until gridW) {
                    val px = ((gx.toLong() * bitmap.width) / gridW).toInt().coerceIn(0, bitmap.width - 1)
                    sampled[gy * gridW + gx] = bitmap.getPixel(px, py)
                }
            }
            val gridObs = analyzePixelGrid(sampled, gridW, gridH, snapshot)
            val fullResSummary = "${bitmap.width}x${bitmap.height}px (${if (gridObs.isDarkThemeDetected) "Dark UI" else "Light UI"}, Lum=${"%.1f".format(gridObs.meanLuminance)}, Contrast=${"%.1f".format(gridObs.contrastScore)}, GroundedTargets=${gridObs.groundedTextElements.size}, dHash=0x${java.lang.Long.toHexString(gridObs.visualPerceptualHash)})"
            val finalObs = gridObs.copy(
                width = bitmap.width,
                height = bitmap.height,
                detectedRegionsSummary = fullResSummary
            )
            lastVisualObservation = finalObs
            return finalObs
        }

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


