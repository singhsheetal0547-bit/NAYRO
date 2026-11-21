package com.example.core.floating

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages persisting, restoring, edge-snapping, and inset-safe clamping of the floating
 * NAYRO agent icon coordinates on screen.
 * Safe in headless JVM unit tests when [context] is null.
 */
class FloatingAssistantPreferences(context: Context? = null) {

    private val prefs: SharedPreferences? = try {
        context?.getSharedPreferences("nayro_floating_prefs", Context.MODE_PRIVATE)
    } catch (_: Throwable) {
        null
    }

    private var memoryX: Int = DEFAULT_X
    private var memoryY: Int = DEFAULT_Y
    private var memoryEnabled: Boolean = false

    companion object {
        private const val KEY_POS_X = "floating_pos_x"
        private const val KEY_POS_Y = "floating_pos_y"
        private const val KEY_IS_ENABLED = "floating_is_enabled"
        const val DEFAULT_X = 24
        const val DEFAULT_Y = 220

        /**
         * Clamps floating bubble coordinates inside visible screen bounds while respecting
         * safe top (status bar / cutout), bottom (navigation bar / gesture pill), and edge margins.
         */
        fun clampToSafeBounds(
            x: Int,
            y: Int,
            screenWidth: Int,
            screenHeight: Int,
            bubbleSizePx: Int,
            edgeMarginPx: Int,
            topInsetPx: Int = 0,
            bottomInsetPx: Int = 0
        ): Pair<Int, Int> {
            val minX = edgeMarginPx.coerceAtLeast(0)
            val maxX = (screenWidth - bubbleSizePx - edgeMarginPx).coerceAtLeast(minX)
            val minY = (topInsetPx + edgeMarginPx).coerceAtLeast(0)
            val maxY = (screenHeight - bubbleSizePx - bottomInsetPx - edgeMarginPx).coerceAtLeast(minY)
            return x.coerceIn(minX, maxX) to y.coerceIn(minY, maxY)
        }

        /**
         * Snaps the floating icon horizontally to the nearest left or right safe screen edge
         * while keeping Y clamped within top/bottom safe insets.
         */
        fun snapToNearestHorizontalEdge(
            x: Int,
            y: Int,
            screenWidth: Int,
            screenHeight: Int,
            bubbleSizePx: Int,
            edgeMarginPx: Int,
            topInsetPx: Int = 0,
            bottomInsetPx: Int = 0
        ): Pair<Int, Int> {
            val (_, safeY) = clampToSafeBounds(
                x = x,
                y = y,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                bubbleSizePx = bubbleSizePx,
                edgeMarginPx = edgeMarginPx,
                topInsetPx = topInsetPx,
                bottomInsetPx = bottomInsetPx
            )
            val snappedX = if (x + bubbleSizePx / 2 < screenWidth / 2) {
                edgeMarginPx
            } else {
                (screenWidth - bubbleSizePx - edgeMarginPx).coerceAtLeast(edgeMarginPx)
            }
            return snappedX to safeY
        }
    }

    fun savePosition(x: Int, y: Int) {
        memoryX = x
        memoryY = y
        try {
            prefs?.edit()
                ?.putInt(KEY_POS_X, x)
                ?.putInt(KEY_POS_Y, y)
                ?.apply()
        } catch (_: Throwable) {}
    }

    fun getPosition(): Pair<Int, Int> {
        val x = try {
            prefs?.getInt(KEY_POS_X, memoryX) ?: memoryX
        } catch (_: Throwable) {
            memoryX
        }
        val y = try {
            prefs?.getInt(KEY_POS_Y, memoryY) ?: memoryY
        } catch (_: Throwable) {
            memoryY
        }
        return Pair(x, y)
    }

    fun setFloatingEnabled(enabled: Boolean) {
        memoryEnabled = enabled
        try {
            prefs?.edit()?.putBoolean(KEY_IS_ENABLED, enabled)?.apply()
        } catch (_: Throwable) {}
    }

    fun isFloatingEnabled(): Boolean {
        return try {
            prefs?.getBoolean(KEY_IS_ENABLED, memoryEnabled) ?: memoryEnabled
        } catch (_: Throwable) {
            memoryEnabled
        }
    }
}
