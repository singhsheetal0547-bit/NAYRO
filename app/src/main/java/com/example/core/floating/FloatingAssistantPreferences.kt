package com.example.core.floating

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages persisting and retrieving floating assistant bubble coordinate positions on screen.
 */
class FloatingAssistantPreferences(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("nayro_floating_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_POS_X = "floating_pos_x"
        private const val KEY_POS_Y = "floating_pos_y"
        private const val KEY_IS_ENABLED = "floating_is_enabled"
    }

    fun savePosition(x: Int, y: Int) {
        prefs.edit()
            .putInt(KEY_POS_X, x)
            .putInt(KEY_POS_Y, y)
            .apply()
    }

    fun getPosition(): Pair<Int, Int> {
        val x = prefs.getInt(KEY_POS_X, 20)
        val y = prefs.getInt(KEY_POS_Y, 200)
        return Pair(x, y)
    }

    fun setFloatingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_IS_ENABLED, enabled).apply()
    }

    fun isFloatingEnabled(): Boolean {
        return prefs.getBoolean(KEY_IS_ENABLED, false)
    }
}
