package com.advanceagent.core.audio

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Provides distinct audio tones and subtle haptic feedback on:
 * - Wake-word detection
 * - Listening start
 * - Action completion
 * - Error or complete shutdown
 * Safe in headless JVM tests when [context] is null.
 */
class SoundEffectsManager(private val context: Context? = null) {

    var isEnabled: Boolean = true

    fun onWakeWordDetected() {
        if (!isEnabled) return
        playTone(ToneGenerator.TONE_PROP_BEEP2, durationMs = 110)
        vibrate(durationMs = 45)
    }

    fun onListeningStarted() {
        if (!isEnabled) return
        playTone(ToneGenerator.TONE_PROP_BEEP, durationMs = 70)
        vibrate(durationMs = 25)
    }

    fun onActionCompleted() {
        if (!isEnabled) return
        playTone(ToneGenerator.TONE_PROP_ACK, durationMs = 95)
        vibrate(durationMs = 30)
    }

    fun onErrorOrShutdown() {
        if (!isEnabled) return
        playTone(ToneGenerator.TONE_PROP_NACK, durationMs = 130)
        vibrate(durationMs = 60)
    }

    private fun playTone(toneType: Int, durationMs: Int) {
        if (context == null) return
        try {
            val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 65)
            tg.startTone(toneType, durationMs)
        } catch (_: Throwable) {
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrate(durationMs: Long) {
        val ctx = context ?: return
        try {
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator?.hasVibrator() == true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(
                        VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
                    )
                } else {
                    vibrator.vibrate(durationMs)
                }
            }
        } catch (_: Throwable) {
        }
    }
}
