package com.example.core.voice

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.example.core.identity.AssistantIdentityStore
import com.example.core.permission.PermissionManager
import com.example.core.service.NayroForegroundService
import com.example.core.service.ServiceLifecycleState
import com.example.core.service.ServiceStateRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BackgroundListeningState(
    val isEnabled: Boolean = false,
    val isServiceActive: Boolean = false,
    val isWakeEngineListening: Boolean = false,
    val hasMicrophonePermission: Boolean = false,
    val isBatteryOptimizationIgnored: Boolean = false,
    val statusSummary: String = "Background Voice Listening is OFF.",
    val oemGuidance: String = "",
    val pipelineState: com.example.core.service.BackgroundVoicePipelineState =
        com.example.core.service.BackgroundVoicePipelineState.STOPPED
)

/**
 * Single authoritative controller for user-controlled Background Voice Listening (ON / OFF).
 *
 * - When OFF: immediately stops wake-word detection, releases AudioRecord resources,
 *   cancels active speech capture, ends follow-up sessions, persists preference as OFF,
 *   and stops NayroForegroundService while keeping in-app features completely usable.
 * - When ON: verifies RECORD_AUDIO permission, syncs custom assistant identity & wake phrases,
 *   persists preference as ON, and starts NayroForegroundService (microphone FGS) for local
 *   on-device wake detection while the user is in other apps or screen is off (subject to
 *   Android 10–16 and OEM battery restrictions).
 */
class BackgroundVoiceController(
    private val context: Context?,
    private val identityStore: AssistantIdentityStore,
    private val permissionManager: PermissionManager?,
    private val voiceManager: VoiceManager,
    private val micPermissionCheckOverride: (() -> Boolean)? = null
) {

    private fun checkMicrophonePermission(): Boolean {
        return micPermissionCheckOverride?.invoke()
            ?: (permissionManager?.isMicrophoneGranted() == true || voiceManager.isMicrophonePermissionGranted())
    }

    private val _state = MutableStateFlow(buildSnapshot(isEnabledOverride = identityStore.getCurrentProfile().isWakeWordEnabled))
    val state: StateFlow<BackgroundListeningState> = _state.asStateFlow()

    /**
     * Enables Background Voice Listening if RECORD_AUDIO is granted.
     * Returns `(true, message)` on activation, or `(false, reason)` if blocked by missing permission.
     */
    fun enableBackgroundListening(activityContext: Context? = context): Pair<Boolean, String> {
        val hasMic = checkMicrophonePermission()
        if (!hasMic) {
            val msg = "Microphone permission (RECORD_AUDIO) is required before enabling Background Voice Listening."
            _state.value = buildSnapshot(isEnabledOverride = false, customSummary = msg)
            ServiceStateRepository.appendActivityLog(msg)
            return false to msg
        }

        identityStore.updateVoiceAndLanguage(isWakeWordEnabled = true)
        voiceManager.bindIdentityStore(identityStore)
        val updatedConfig = voiceManager.configuration.value.copy(isWakeWordEnabled = true)
        voiceManager.updateConfiguration(updatedConfig)

        val ctx = activityContext ?: context
        if (ctx != null) {
            NayroForegroundService.startService(ctx)
        } else {
            ServiceStateRepository.updateForegroundStatus(ServiceLifecycleState.RUNNING)
            val wakeListening = voiceManager.wakeWordEngine.engineState.value ==
                com.example.core.voice.engine.WakeEngineState.LISTENING_FOR_WAKE
            ServiceStateRepository.updateBackgroundVoicePipeline(
                state = if (wakeListening) {
                    com.example.core.service.BackgroundVoicePipelineState.BACKGROUND_VOICE_READY
                } else {
                    com.example.core.service.BackgroundVoicePipelineState.DEGRADED
                },
                detail = if (wakeListening) {
                    "Headless background listening verified active."
                } else {
                    voiceManager.wakeWordEngine.statusMessage.value
                },
                isMicrophoneVerified = hasMic,
                isWakeEngineVerified = voiceManager.wakeWordEngine.hasUsableAcousticTemplates(),
                isAudioCaptureVerified = wakeListening
            )
        }

        val phrases = identityStore.getWakePhrases().take(3).joinToString(", ")
        val msg = "Background Listening ON — initializing local wake detection for: $phrases."
        ServiceStateRepository.appendActivityLog(msg)
        _state.value = buildSnapshot(isEnabledOverride = true, customSummary = msg)
        return true to msg
    }

    /**
     * Immediately disables Background Voice Listening:
     * stops wake-word AudioRecord capture, cancels active speech recognition, ends follow-up session,
     * persists preference as OFF, and stops the microphone foreground service.
     */
    fun disableBackgroundListening(activityContext: Context? = context): Pair<Boolean, String> {
        identityStore.updateVoiceAndLanguage(isWakeWordEnabled = false)
        voiceManager.endConversationSession(returnToStandby = false)
        voiceManager.stopListening()
        val updatedConfig = voiceManager.configuration.value.copy(isWakeWordEnabled = false)
        voiceManager.updateConfiguration(updatedConfig)
        voiceManager.wakeWordEngine.stopListening()
        voiceManager.detachServiceListener()

        val ctx = activityContext ?: context
        if (ctx != null) {
            NayroForegroundService.stopService(ctx)
        } else {
            ServiceStateRepository.updateForegroundStatus(ServiceLifecycleState.STOPPED)
        }

        val msg = "Background Listening OFF — microphone capture stopped and AudioRecord released."
        ServiceStateRepository.appendActivityLog(msg)
        _state.value = buildSnapshot(isEnabledOverride = false, customSummary = msg)
        return true to msg
    }

    fun setBackgroundListeningEnabled(enabled: Boolean, activityContext: Context? = context): Pair<Boolean, String> {
        return if (enabled) {
            enableBackgroundListening(activityContext)
        } else {
            disableBackgroundListening(activityContext)
        }
    }

    /**
     * Called when RECORD_AUDIO permission is revoked while background listening was active.
     */
    fun onMicrophonePermissionRevoked(activityContext: Context? = context) {
        disableBackgroundListening(activityContext)
        val msg = "Microphone permission was revoked. Background listening has been stopped."
        ServiceStateRepository.appendActivityLog(msg)
        _state.value = buildSnapshot(isEnabledOverride = false, customSummary = msg)
    }

    fun refreshState(): BackgroundListeningState {
        val snapshot = buildSnapshot()
        _state.value = snapshot
        return snapshot
    }

    private fun buildSnapshot(
        isEnabledOverride: Boolean? = null,
        customSummary: String? = null
    ): BackgroundListeningState {
        val enabled = isEnabledOverride ?: identityStore.getCurrentProfile().isWakeWordEnabled
        val hasMic = checkMicrophonePermission()
        val fgRunning = ServiceStateRepository.foregroundServiceStatus.value.state.isActiveOrRunning
        val pipelineStatus = ServiceStateRepository.backgroundVoicePipelineStatus.value
        val wakeListening = voiceManager.wakeWordEngine.engineState.value == com.example.core.voice.engine.WakeEngineState.LISTENING_FOR_WAKE
        val batteryExempt = isBatteryOptimizationIgnored()
        val manufacturer = try {
            Build.MANUFACTURER.orEmpty()
        } catch (_: Throwable) {
            ""
        }
        val sdkInt = try {
            Build.VERSION.SDK_INT
        } catch (_: Throwable) {
            30
        }
        val guidance = buildOemBackgroundGuidance(manufacturer, sdkInt, batteryExempt)
        val summary = customSummary ?: when {
            !enabled -> "Background Voice Listening is OFF. Microphone is released."
            !hasMic -> "Background Listening error: Microphone permission is not granted."
            pipelineStatus.state == com.example.core.service.BackgroundVoicePipelineState.ERROR ->
                "Background Voice Error: ${pipelineStatus.detail}"
            pipelineStatus.state == com.example.core.service.BackgroundVoicePipelineState.DEGRADED ->
                "Background Voice Degraded: ${pipelineStatus.detail}"
            pipelineStatus.state == com.example.core.service.BackgroundVoicePipelineState.BACKGROUND_VOICE_READY ->
                "Background Voice Ready — local on-device wake detection active."
            fgRunning && wakeListening -> "Background Listening ON (local on-device wake detection active)."
            fgRunning -> "Background service active; wake detector initializing or awaiting templates."
            else -> "Background Listening enabled; open NAYRO to start the foreground service."
        }
        return BackgroundListeningState(
            isEnabled = enabled,
            isServiceActive = fgRunning,
            isWakeEngineListening = wakeListening,
            hasMicrophonePermission = hasMic,
            isBatteryOptimizationIgnored = batteryExempt,
            statusSummary = summary,
            oemGuidance = guidance,
            pipelineState = pipelineStatus.state
        )
    }

    private fun isBatteryOptimizationIgnored(): Boolean {
        val ctx = context ?: return false
        return try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true
        } catch (_: Throwable) {
            false
        }
    }

    companion object {
        /**
         * Builds honest, platform-accurate background listening guidance covering Android 11–14+
         * microphone FGS restrictions and OEM power managers (including Realme/ColorOS).
         */
        fun buildOemBackgroundGuidance(
            manufacturer: String,
            sdkInt: Int,
            isBatteryOptimizationIgnored: Boolean
        ): String {
            val m = manufacturer.trim().lowercase()
            val oemNote = when {
                m.contains("realme") || m.contains("oppo") || m.contains("oneplus") ->
                    "Realme / ColorOS / OxygenOS detected: Allow Background Activity & Auto-Launch in App Info -> Battery Usage, and lock NAYRO in Recents to reduce OS background suspension."
                m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") ->
                    "MIUI / HyperOS detected: Enable Autostart and set Battery Saver to 'No restrictions' for NAYRO."
                m.contains("samsung") ->
                    "Samsung OneUI detected: Set NAYRO Battery to 'Unrestricted' and exclude it from Deep Sleeping Apps."
                m.contains("vivo") || m.contains("iqoo") ->
                    "Vivo / FunTouchOS detected: Enable High Background Power Consumption for NAYRO."
                else ->
                    "Set NAYRO Battery usage to 'Unrestricted' if Android Doze pauses wake-word standby."
            }
            val apiNote = when {
                sdkInt >= 34 -> "Android 14+ requires starting microphone listening from the foreground with RECORD_AUDIO granted."
                sdkInt >= 31 -> "Android 12+ restricts background foreground-service restarts after force-stop or deep Doze."
                else -> "Android 11 supports user-initiated foreground microphone listening with an active notification."
            }
            val batteryStatus = if (isBatteryOptimizationIgnored) {
                "Battery optimization: Unrestricted."
            } else {
                "Battery optimization: Restricted (OEM may pause standby)."
            }
            return "$batteryStatus $apiNote $oemNote"
        }
    }
}
