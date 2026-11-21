package com.example.core.screen

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ScreenRecordingState {
    IDLE,
    AWAITING_USER_CONSENT,
    RECORDING,
    STOPPED,
    UNAVAILABLE
}

data class ScreenRecordingStatus(
    val state: ScreenRecordingState = ScreenRecordingState.IDLE,
    val detail: String = "Screen recording is idle.",
    val outputPath: String? = null,
    val updatedAtMs: Long = System.currentTimeMillis()
)

/**
 * Policy-compliant Android [MediaProjection] Screen Recording Controller for NAYRO's
 * Autonomous Device Agent.
 *
 * Security & Privacy Guarantees:
 * - Screen recording NEVER starts silently in the background.
 * - Requires explicit per-session Android system consent via [createUserConsentIntent].
 * - Always stops and releases [MediaProjection] resources cleanly on [stopRecording].
 */
object ScreenRecordingController {

    private val _status = MutableStateFlow(ScreenRecordingStatus())
    val status: StateFlow<ScreenRecordingStatus> = _status.asStateFlow()

    @Volatile
    private var activeProjection: MediaProjection? = null

    /**
     * Builds the official Android system MediaProjection user-consent dialog Intent.
     * Transitions state to [ScreenRecordingState.AWAITING_USER_CONSENT] so the caller can
     * launch it through an Activity result launcher.
     */
    fun createUserConsentIntent(context: Context?): Intent? {
        val ctx = context ?: run {
            _status.value = ScreenRecordingStatus(
                state = ScreenRecordingState.UNAVAILABLE,
                detail = "Screen recording requires an active Android Activity context for MediaProjection user consent."
            )
            return null
        }
        return try {
            val mgr = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            if (mgr == null) {
                _status.value = ScreenRecordingStatus(
                    state = ScreenRecordingState.UNAVAILABLE,
                    detail = "Android MediaProjectionManager is unavailable on this device."
                )
                return null
            }
            _status.value = ScreenRecordingStatus(
                state = ScreenRecordingState.AWAITING_USER_CONSENT,
                detail = "Awaiting explicit user approval in Android's screen capture consent dialog."
            )
            mgr.createScreenCaptureIntent()
        } catch (e: Throwable) {
            _status.value = ScreenRecordingStatus(
                state = ScreenRecordingState.UNAVAILABLE,
                detail = "Unable to request MediaProjection consent: ${e.localizedMessage ?: e.javaClass.simpleName}"
            )
            null
        }
    }

    /**
     * Called after the user responds to Android's MediaProjection system consent dialog.
     * Refuses to enter [ScreenRecordingState.RECORDING] unless `resultCode == Activity.RESULT_OK` and `data != null`.
     */
    fun onConsentResult(context: Context?, resultCode: Int, data: Intent?, outputPath: String? = null): Boolean {
        if (resultCode != Activity.RESULT_OK || data == null) {
            _status.value = ScreenRecordingStatus(
                state = ScreenRecordingState.IDLE,
                detail = "User denied screen recording consent. Recording was not started."
            )
            return false
        }
        val ctx = context ?: return false
        return try {
            val mgr = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
                ?: return false
            val projection = mgr.getMediaProjection(resultCode, data) ?: return false
            activeProjection = projection
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopRecording("MediaProjection session stopped by Android system.")
                }
            }, null)
            _status.value = ScreenRecordingStatus(
                state = ScreenRecordingState.RECORDING,
                detail = "Screen recording session authorized and active.",
                outputPath = outputPath
            )
            true
        } catch (e: Throwable) {
            _status.value = ScreenRecordingStatus(
                state = ScreenRecordingState.UNAVAILABLE,
                detail = "Failed to start MediaProjection session: ${e.localizedMessage ?: e.javaClass.simpleName}"
            )
            false
        }
    }

    fun stopRecording(reason: String = "Screen recording stopped by user."): Boolean {
        val wasRecording = _status.value.state == ScreenRecordingState.RECORDING || activeProjection != null
        try {
            activeProjection?.stop()
        } catch (_: Throwable) {}
        activeProjection = null
        _status.value = ScreenRecordingStatus(
            state = if (wasRecording) ScreenRecordingState.STOPPED else ScreenRecordingState.IDLE,
            detail = reason
        )
        return wasRecording
    }

    fun resetForTesting() {
        activeProjection = null
        _status.value = ScreenRecordingStatus()
    }
}
