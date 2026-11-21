package com.example.core.voice.assistant

import android.Manifest
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.NayroApplication
import com.example.core.identity.AssistantIdentityProfile
import com.example.core.voice.VoiceState
import com.example.data.local.entities.ConversationEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Honest classification of NAYRO's Screen-Off Wake Word & System Assistant runtime mode.
 * Never claims hardware/DSP always-on hotword detection works when Android restricts
 * privileged hotword permissions to pre-installed system assistants.
 */
enum class ScreenOffWakeMode(val label: String) {
    HARDWARE_DSP_HOTWORD("Hardware DSP Hotword Active"),
    LOCAL_FALLBACK_ACTIVE("Local On-Device Wake Fallback Active"),
    ASSISTANT_ROLE_READY_MANUAL("System Assistant Ready (Manual / Gesture)"),
    UNSUPPORTED_OR_BLOCKED("Screen-Off Wake Unavailable / Blocked")
}

/**
 * Structured diagnostic report for Android 10–16 Screen-On / Screen-Off Wake,
 * VoiceInteractionService, System Assistant role, and OEM power-management compatibility (Phases 2–4).
 */
data class ScreenOffWakeCapabilityReport(
    val mode: ScreenOffWakeMode,
    val androidApiLevel: Int,
    val manufacturer: String,
    val isDefaultAssistantRoleHeld: Boolean,
    val isVoiceInteractionServiceActive: Boolean,
    val isHardwareDspHotwordSupported: Boolean,
    val isLocalFallbackListeningActive: Boolean,
    val supportsLockScreenVoiceAssist: Boolean,
    val isMicrophonePermissionGranted: Boolean,
    val isBatteryOptimizationExempt: Boolean,
    val honestStatusSummary: String,
    val oemCompatibilityNote: String,
    val limitations: List<String>,
    val isScreenOnWakeReady: Boolean = isLocalFallbackListeningActive,
    val isScreenOffWakeVerified: Boolean = isHardwareDspHotwordSupported && isVoiceInteractionServiceActive,
    val isNotificationPermissionGranted: Boolean = true,
    val androidVersionCompatibilityNote: String = "",
    val recoverySteps: List<String> = emptyList()
)

enum class VoiceInteractionServiceLifecycleState {
    INACTIVE,
    READY_SYSTEM_ASSIST_ONLY,
    READY_HARDWARE_DSP_HOTWORD,
    SHUTDOWN
}

/**
 * Evaluates Android VoiceInteractionService, Digital Assistant role (`RoleManager.ROLE_ASSISTANT`),
 * hardware DSP hotword privilege availability, and Android 10–16 / OEM compatibility honestly.
 */
object ScreenOffWakeCapabilityManager {

    private val _voiceInteractionServiceActive = MutableStateFlow(false)
    val voiceInteractionServiceActive: StateFlow<Boolean> = _voiceInteractionServiceActive.asStateFlow()

    private val _serviceLifecycleState = MutableStateFlow(VoiceInteractionServiceLifecycleState.INACTIVE)
    val serviceLifecycleState: StateFlow<VoiceInteractionServiceLifecycleState> = _serviceLifecycleState.asStateFlow()

    private val _hardwareDspStatusNote = MutableStateFlow(
        "Hardware DSP AlwaysOnHotwordDetector requires privileged system signature permissions on Android."
    )
    val hardwareDspStatusNote: StateFlow<String> = _hardwareDspStatusNote.asStateFlow()

    private val _lastSessionStatus = MutableStateFlow("Idle")
    val lastSessionStatus: StateFlow<String> = _lastSessionStatus.asStateFlow()

    internal fun setServiceActive(active: Boolean, dspNote: String? = null, hasHardwareDsp: Boolean = false) {
        _voiceInteractionServiceActive.value = active
        _serviceLifecycleState.value = when {
            !active -> VoiceInteractionServiceLifecycleState.SHUTDOWN
            hasHardwareDsp -> VoiceInteractionServiceLifecycleState.READY_HARDWARE_DSP_HOTWORD
            else -> VoiceInteractionServiceLifecycleState.READY_SYSTEM_ASSIST_ONLY
        }
        if (!dspNote.isNullOrBlank()) {
            _hardwareDspStatusNote.value = dspNote
        }
    }

    internal fun updateSessionStatus(status: String) {
        _lastSessionStatus.value = status
    }

    /**
     * Checks whether the current app package actually holds Android's privileged hotword permissions
     * required for hardware DSP `AlwaysOnHotwordDetector`. Standard third-party installs will return `false`.
     */
    fun hasPrivilegedHardwareHotwordAccess(context: Context?): Boolean {
        val ctx = context ?: return false
        return try {
            val captureHotword = ContextCompat.checkSelfPermission(
                ctx,
                "android.permission.CAPTURE_AUDIO_HOTWORD"
            ) == PackageManager.PERMISSION_GRANTED
            val manageKeyphrases = ContextCompat.checkSelfPermission(
                ctx,
                "android.permission.MANAGE_VOICE_KEYPHRASES"
            ) == PackageManager.PERMISSION_GRANTED
            captureHotword && manageKeyphrases
        } catch (_: Throwable) {
            false
        }
    }

    fun isDefaultAssistantRoleHeld(context: Context?): Boolean {
        val ctx = context ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = ctx.getSystemService(RoleManager::class.java)
                roleManager?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
            } else {
                isVoiceInteractionServiceActive(ctx)
            }
        } catch (_: Throwable) {
            false
        }
    }

    fun isVoiceInteractionServiceActive(context: Context?): Boolean {
        val ctx = context ?: return _voiceInteractionServiceActive.value
        return try {
            val component = ComponentName(ctx, NayroVoiceInteractionService::class.java)
            VoiceInteractionService.isActiveService(ctx, component) || _voiceInteractionServiceActive.value
        } catch (_: Throwable) {
            _voiceInteractionServiceActive.value
        }
    }

    /**
     * Produces an honest, deterministic capability report across Android 10–16 (API 29–36) and OEM ROMs
     * (Realme/ColorOS, OPPO/OnePlus, Xiaomi/MIUI/HyperOS, Samsung One UI, Vivo/Funtouch, and Pixel/AOSP).
     *
     * CRITICAL HONESTY INVARIANT (Phase 3):
     * Never claims screen-off wake works solely because a foreground service is running.
     * Requires verified microphone permission + verified local audio capture + valid templates
     * (or privileged hardware DSP + active VoiceInteractionService).
     */
    fun evaluateCapability(
        context: Context? = null,
        isLocalWakeListening: Boolean = false,
        sdkInt: Int = Build.VERSION.SDK_INT,
        manufacturer: String = Build.MANUFACTURER ?: "Unknown",
        micGrantedOverride: Boolean? = null,
        roleHeldOverride: Boolean? = null,
        serviceActiveOverride: Boolean? = null,
        hardwareDspSupportedOverride: Boolean? = null,
        batteryExemptOverride: Boolean? = null,
        notificationPermissionOverride: Boolean? = null,
        isWakeTemplatesReady: Boolean = true,
        isForceStoppedOrDisabled: Boolean = false
    ): ScreenOffWakeCapabilityReport {
        val micGranted = micGrantedOverride ?: (context != null && try {
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        })
        val notifGranted = notificationPermissionOverride ?: if (sdkInt >= 33 && context != null) {
            try {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } catch (_: Throwable) {
                false
            }
        } else {
            true
        }
        val roleHeld = roleHeldOverride ?: isDefaultAssistantRoleHeld(context)
        val serviceActive = serviceActiveOverride ?: isVoiceInteractionServiceActive(context)
        val hardwareDsp = hardwareDspSupportedOverride ?: hasPrivilegedHardwareHotwordAccess(context)
        val batteryExempt = batteryExemptOverride ?: (context != null && try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        } catch (_: Throwable) {
            false
        })

        val limitations = mutableListOf<String>()
        val recoverySteps = mutableListOf<String>()

        if (isForceStoppedOrDisabled) {
            limitations.add("NAYRO was force-stopped or background services are disabled; Android blocks all background wake until the user opens the app.")
            recoverySteps.add("Open NAYRO and re-enable Background Voice Listening.")
        }
        if (!hardwareDsp) {
            limitations.add(
                "Hardware/DSP AlwaysOnHotwordDetector is restricted by Android to pre-installed privileged system assistants. Standard third-party installs use local on-device MFCC+DTW wake detection as an honest fallback."
            )
        }
        val androidVersionNote = buildAndroidVersionCompatibilityNote(sdkInt)
        limitations.add(androidVersionNote)

        if (!micGranted) {
            limitations.add("RECORD_AUDIO permission is not granted; voice wake and command capture are blocked.")
            recoverySteps.add("Grant Microphone (RECORD_AUDIO) permission in NAYRO Permissions or Android App Info.")
        }
        if (!isWakeTemplatesReady) {
            limitations.add("No validated local wake-word acoustic templates or enrolled phrases are ready.")
            recoverySteps.add("Enroll your custom wake phrase (e.g., 'Hi Nayro' or 'Hi Babu') in Wake-Word Settings.")
        }
        if (sdkInt >= 33 && !notifGranted) {
            limitations.add("Android 13+ POST_NOTIFICATIONS permission is not granted; foreground service status notification may be hidden.")
            recoverySteps.add("Grant Notifications permission so NAYRO's active microphone status remains visible.")
        }
        if (!batteryExempt) {
            limitations.add("Battery optimization is active; Android Doze and OEM power managers may suspend local screen-off audio capture.")
            recoverySteps.add("Set NAYRO Battery Usage to 'Unrestricted' in Android App Info.")
        }
        if (!roleHeld && !serviceActive) {
            limitations.add("NAYRO is not set as the device's Default Digital Assistant; system assistant gesture and lock-screen voice assist are inactive.")
            recoverySteps.add("Optional: Select NAYRO as your Default Digital Assistant in Android Settings for VoiceInteractionService assist.")
        }

        val oemNote = buildOemCompatibilityGuidance(manufacturer, sdkInt, batteryExempt)
        val lockScreenAssistSupported = roleHeld || serviceActive
        val verifiedLocalCapture = isLocalWakeListening && micGranted && isWakeTemplatesReady && !isForceStoppedOrDisabled
        val verifiedHardwareDsp = hardwareDsp && serviceActive && micGranted && !isForceStoppedOrDisabled

        val mode = when {
            isForceStoppedOrDisabled || !micGranted -> ScreenOffWakeMode.UNSUPPORTED_OR_BLOCKED
            verifiedHardwareDsp -> ScreenOffWakeMode.HARDWARE_DSP_HOTWORD
            verifiedLocalCapture -> ScreenOffWakeMode.LOCAL_FALLBACK_ACTIVE
            lockScreenAssistSupported -> ScreenOffWakeMode.ASSISTANT_ROLE_READY_MANUAL
            else -> ScreenOffWakeMode.UNSUPPORTED_OR_BLOCKED
        }

        val summary = when (mode) {
            ScreenOffWakeMode.HARDWARE_DSP_HOTWORD ->
                "Hardware DSP hotword and Android VoiceInteractionService are active for screen-off wake."
            ScreenOffWakeMode.LOCAL_FALLBACK_ACTIVE ->
                if (lockScreenAssistSupported) {
                    "Local on-device MFCC+DTW wake detection is verified active (zero cloud standby streaming) and NAYRO holds Default Assistant role. Hardware DSP hotword is unavailable on third-party installs; screen-off survival depends on Android/OEM Doze."
                } else {
                    "Local on-device MFCC+DTW wake detection is verified active in Foreground Service (zero cloud standby streaming). Hardware DSP hotword is unavailable on third-party installs; screen-off reliability is best-effort and requires Unrestricted battery."
                }
            ScreenOffWakeMode.ASSISTANT_ROLE_READY_MANUAL ->
                "NAYRO is registered as Default Digital Assistant for gesture/lock-screen voice assist. Local wake capture is not currently active."
            ScreenOffWakeMode.UNSUPPORTED_OR_BLOCKED ->
                when {
                    isForceStoppedOrDisabled ->
                        "Background voice is stopped or force-stopped. Open NAYRO to reactivate."
                    !micGranted ->
                        "Microphone permission (RECORD_AUDIO) is required before Screen-ON or Screen-OFF wake listening can run."
                    !isWakeTemplatesReady ->
                        "Wake-word acoustic templates are not ready. Enroll a wake phrase or verify bundled templates first."
                    else ->
                        "Screen-off wake is currently inactive. Enable Background Voice Listening for verified local MFCC+DTW fallback, or use the Floating Assistant bubble."
                }
        }

        return ScreenOffWakeCapabilityReport(
            mode = mode,
            androidApiLevel = sdkInt,
            manufacturer = manufacturer,
            isDefaultAssistantRoleHeld = roleHeld,
            isVoiceInteractionServiceActive = serviceActive,
            isHardwareDspHotwordSupported = hardwareDsp,
            isLocalFallbackListeningActive = verifiedLocalCapture,
            supportsLockScreenVoiceAssist = lockScreenAssistSupported,
            isMicrophonePermissionGranted = micGranted,
            isBatteryOptimizationExempt = batteryExempt,
            honestStatusSummary = summary,
            oemCompatibilityNote = oemNote,
            limitations = limitations,
            isScreenOnWakeReady = verifiedLocalCapture || verifiedHardwareDsp,
            isScreenOffWakeVerified = verifiedHardwareDsp || (verifiedLocalCapture && batteryExempt),
            isNotificationPermissionGranted = notifGranted,
            androidVersionCompatibilityNote = androidVersionNote,
            recoverySteps = recoverySteps
        )
    }

    /**
     * Explicit Android 10–16 (API 29–36) compatibility guidance (Phase 4).
     */
    fun buildAndroidVersionCompatibilityNote(sdkInt: Int): String {
        return when {
            sdkInt >= 36 ->
                "Android 16 (API $sdkInt): Enforces strict foregroundServiceType='microphone' while-in-use launch eligibility, mandatory RECORD_AUDIO + FOREGROUND_SERVICE_MICROPHONE, active mic privacy indicator, and adaptive Doze throttling."
            sdkInt == 35 ->
                "Android 15 (API 35): Enforces strict foregroundServiceType='microphone' eligibility, background launch restrictions, while-in-use mic controls, and FGS timeout policies."
            sdkInt == 34 ->
                "Android 14 (API 34): Requires foregroundServiceType='microphone', FOREGROUND_SERVICE_MICROPHONE permission, and granted RECORD_AUDIO before startForeground(); while-in-use mic access must originate from foreground interaction."
            sdkInt == 33 ->
                "Android 13 (API 33): Requires POST_NOTIFICATIONS runtime permission for visible FGS status notification and foreground microphone service rules."
            sdkInt in 31..32 ->
                "Android 12/12L (API $sdkInt): Enforces ForegroundServiceStartNotAllowedException on background FGS starts and system microphone privacy indicator/toggle."
            sdkInt == 30 ->
                "Android 11 (API 30): Requires foregroundServiceType='microphone' with while-in-use microphone permission for active background capture."
            else ->
                "Android 10 (API $sdkInt): Supports RoleManager.ROLE_ASSISTANT and foreground microphone service with active notification."
        }
    }

    fun buildOemCompatibilityGuidance(
        manufacturer: String,
        sdkInt: Int,
        isBatteryExempt: Boolean
    ): String {
        val m = manufacturer.lowercase()
        val androidLabel = "Android API $sdkInt"
        return when {
            m.contains("realme") ->
                "$androidLabel (ColorOS/Realme UI): Enable 'Allow background activity', 'Allow auto-launch', disable 'Pause app activity if unused', and lock NAYRO in Recents. Realme aggressively kills screen-off microphone FGS under default battery settings."
            m.contains("oppo") || m.contains("oneplus") ->
                "$androidLabel (ColorOS/OxygenOS): Enable 'Allow background activity' and 'Auto-launch' in App Info -> Battery Usage, and disable app freezing/sleep."
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") ->
                "$androidLabel (MIUI/HyperOS): Enable 'Autostart', set Battery Saver to 'No restrictions', and lock NAYRO in Recents to prevent HyperOS memory reclamation."
            m.contains("samsung") ->
                "$androidLabel (Samsung One UI): Set Battery to 'Unrestricted' and add NAYRO to 'Never auto sleeping apps' under Device Care -> Battery -> Background usage limits."
            m.contains("vivo") || m.contains("iqoo") ->
                "$androidLabel (Vivo FuntouchOS / OriginOS): Enable 'High background power consumption' and 'Autostart' for NAYRO in iManager / App Info."
            m.contains("google") || m.contains("pixel") ->
                if (isBatteryExempt) {
                    "$androidLabel (Pixel / Stock Android): Unrestricted battery granted. Local wake fallback operates within standard Android foreground-service and Doze policies."
                } else {
                    "$androidLabel (Pixel / Stock Android): Set App Battery Usage to 'Unrestricted' to minimize Doze throttling during screen-off local listening."
                }
            else ->
                if (isBatteryExempt) {
                    "$androidLabel: Battery optimization exemption granted. Local wake fallback operates within standard Android foreground-service rules."
                } else {
                    "$androidLabel: Grant Unrestricted battery usage in Android App Info to reduce Doze interruptions during screen-off local listening."
                }
        }
    }

    /**
     * Builds the official Android system Intent to set NAYRO as the Default Digital Assistant
     * (`RoleManager.ROLE_ASSISTANT` on Android 10+, falling back to Voice Input / Digital Assistant settings).
     */
    fun createDefaultAssistantSettingsIntent(context: Context): Intent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val roleManager = context.getSystemService(RoleManager::class.java)
                if (roleManager != null &&
                    roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT) &&
                    !roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
                ) {
                    return roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
                }
            } catch (_: Throwable) {}
        }
        return Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}

/**
 * Android System [VoiceInteractionService] for NAYRO (Mode B — Screen-Off & System Assistant).
 *
 * - Activates when the user selects NAYRO as their Default Digital Assistant in Android settings.
 * - Syncs custom wake phrases (`Hi Nayro`, `Hey Babu`, user-defined) from [AssistantIdentityStore].
 * - Probes hardware DSP hotword capability honestly without crashing or falsely claiming support
 *   when Android restricts privileged hotword APIs on third-party installations.
 */
class NayroVoiceInteractionService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        val hasPrivilegedDsp = ScreenOffWakeCapabilityManager.hasPrivilegedHardwareHotwordAccess(this)
        val dspNote = if (hasPrivilegedDsp) {
            "Privileged hotword permissions present; VoiceInteractionService ready."
        } else {
            "VoiceInteractionService active for system/lock-screen assist. Hardware DSP hotword requires privileged system permissions; using local MFCC+DTW fallback when Background Listening is enabled."
        }
        ScreenOffWakeCapabilityManager.setServiceActive(
            active = true,
            dspNote = dspNote,
            hasHardwareDsp = hasPrivilegedDsp
        )

        try {
            val app = NayroApplication.instance
            app.voiceManager.bindIdentityStore(app.assistantIdentityStore)
        } catch (_: Throwable) {}
    }

    override fun onShutdown() {
        super.onShutdown()
        ScreenOffWakeCapabilityManager.setServiceActive(false)
    }
}

/**
 * Android [VoiceInteractionSessionService] factory that spawns [NayroVoiceInteractionSession]
 * whenever the user triggers the system assistant gesture or lock-screen voice assist.
 */
class NayroVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return NayroVoiceInteractionSession(this)
    }
}

/**
 * Android [VoiceInteractionSession] connected directly to the single application-scoped
 * [NayroApplication.voiceManager] and [NayroApplication.agentRuntime].
 * Never instantiates a duplicate execution engine or bypasses Android lock-screen security.
 */
class NayroVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var stateCollectorJob: Job? = null

    private var statusTextView: TextView? = null
    private var transcriptTextView: TextView? = null
    private var micToggleButton: Button? = null

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    override fun onCreateContentView(): View {
        val assistantName = try {
            NayroApplication.instance.assistantIdentityStore.getDisplayName()
        } catch (_: Throwable) {
            AssistantIdentityProfile.DEFAULT_DISPLAY_NAME
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(18)
            setPadding(pad, pad, pad, pad)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(Color.parseColor("#F6F2E8"))
                setStroke(dp(2), Color.parseColor("#153B35"))
            }
        }

        val title = TextView(context).apply {
            text = "NAYRO • $assistantName"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#153B35"))
        }
        container.addView(title)

        val status = TextView(context).apply {
            text = "System Voice Session Ready"
            textSize = 12f
            setTextColor(Color.parseColor("#D56A50"))
            setPadding(0, dp(4), 0, dp(8))
        }
        statusTextView = status
        container.addView(status)

        val transcript = TextView(context).apply {
            text = "Listening for your command..."
            textSize = 14f
            setTextColor(Color.parseColor("#1E2926"))
            val p = dp(12)
            setPadding(p, p, p, p)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), Color.parseColor("#DCE7DE"))
            }
        }
        transcriptTextView = transcript
        container.addView(transcript)

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(12), 0, 0)
        }

        val micBtn = Button(context).apply {
            text = "Listen"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#153B35"))
            setOnClickListener {
                startVoiceCaptureFromSession()
            }
        }
        micToggleButton = micBtn
        btnRow.addView(micBtn)

        val stopBtn = Button(context).apply {
            text = "Stop"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#B9423A"))
            setOnClickListener {
                stopAndReleaseVoiceSession()
                hide()
            }
        }
        btnRow.addView(stopBtn)

        val openAppBtn = Button(context).apply {
            text = "Open NAYRO"
            setTextColor(Color.parseColor("#153B35"))
            setBackgroundColor(Color.parseColor("#DCE7DE"))
            setOnClickListener {
                val intent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                try {
                    startAssistantActivity(intent)
                } catch (_: Throwable) {
                    try {
                        context.startActivity(intent)
                    } catch (_: Throwable) {}
                }
                hide()
            }
        }
        btnRow.addView(openAppBtn)

        container.addView(btnRow)
        return container
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        ScreenOffWakeCapabilityManager.updateSessionStatus("Active VoiceInteractionSession")
        observeSessionTelemetry()
        startVoiceCaptureFromSession()
    }

    override fun onHide() {
        super.onHide()
        stopAndReleaseVoiceSession()
        ScreenOffWakeCapabilityManager.updateSessionStatus("Standby")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAndReleaseVoiceSession()
        sessionScope.cancel()
    }

    private fun startVoiceCaptureFromSession() {
        val app = try {
            NayroApplication.instance
        } catch (_: Throwable) {
            return
        }
        if (!app.permissionManager.isMicrophoneGranted()) {
            statusTextView?.text = "Microphone permission required"
            transcriptTextView?.text = "Please grant Microphone permission inside NAYRO to use voice commands."
            return
        }
        // P0-4 / Phase 2: Use the isolated VoiceInteractionSession listener slot
        // so NayroFloatingService's active listener is NEVER overwritten or detached.
        app.voiceManager.attachVoiceInteractionListener { recognized ->
            dispatchSessionCommand(recognized)
        }
        app.voiceManager.startListening()
        statusTextView?.text = "Listening..."
    }

    private fun dispatchSessionCommand(rawText: String) {
        val app = try {
            NayroApplication.instance
        } catch (_: Throwable) {
            return
        }
        val cleanText = app.voiceManager.normalizeVoiceInvocationCommand(rawText)
        if (cleanText.isEmpty()) return

        if (app.voiceManager.isConversationExitPhrase(cleanText) ||
            app.voiceManager.wakeWordEngine.isVoiceExitCommand(cleanText)
        ) {
            stopAndReleaseVoiceSession()
            hide()
            return
        }

        statusTextView?.text = "Executing..."
        transcriptTextView?.text = "You: \"$cleanText\"\nWorking on it..."

        sessionScope.launch(Dispatchers.Default) {
            try {
                app.repository.addConversationMessage(
                    ConversationEntity(sender = "user", content = cleanText)
                )
                val response = app.agentRuntime.executeUserInstruction(cleanText)
                app.repository.addConversationMessage(
                    ConversationEntity(sender = "assistant", content = response)
                )
                withContext(Dispatchers.Main) {
                    statusTextView?.text = "Responded"
                    transcriptTextView?.text = response
                }
                if (app.voiceManager.configuration.value.isTtsEnabled) {
                    app.voiceManager.speak(
                        text = response,
                        expectFollowUp = app.voiceManager.configuration.value.isContinuousConversationEnabled
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusTextView?.text = "Error"
                    transcriptTextView?.text = "Execution error: ${e.localizedMessage ?: e.javaClass.simpleName}"
                }
            }
        }
    }

    private fun observeSessionTelemetry() {
        stateCollectorJob?.cancel()
        stateCollectorJob = sessionScope.launch {
            val app = try {
                NayroApplication.instance
            } catch (_: Throwable) {
                return@launch
            }
            launch {
                app.voiceManager.voiceState.collect { vState ->
                    val label = when (vState) {
                        VoiceState.LISTENING, VoiceState.FOLLOW_UP_LISTENING -> "Listening..."
                        VoiceState.TRANSCRIBING -> "Transcribing..."
                        VoiceState.THINKING -> "Thinking..."
                        VoiceState.EXECUTING -> "Executing..."
                        VoiceState.SPEAKING -> "Speaking..."
                        VoiceState.ERROR -> "Voice Error"
                        else -> "Ready"
                    }
                    statusTextView?.text = label
                    micToggleButton?.text = if (vState == VoiceState.LISTENING || vState == VoiceState.FOLLOW_UP_LISTENING) {
                        "Listening"
                    } else {
                        "Listen"
                    }
                }
            }
            launch {
                app.voiceManager.partialTranscript.collect { partial ->
                    if (partial.isNotBlank() &&
                        (app.voiceManager.voiceState.value == VoiceState.LISTENING ||
                            app.voiceManager.voiceState.value == VoiceState.FOLLOW_UP_LISTENING)
                    ) {
                        transcriptTextView?.text = "\"$partial\""
                    }
                }
            }
        }
    }

    private fun stopAndReleaseVoiceSession() {
        stateCollectorJob?.cancel()
        stateCollectorJob = null
        try {
            val app = NayroApplication.instance
            app.voiceManager.detachVoiceInteractionListener()
            app.voiceManager.endConversationSession(
                returnToStandby = app.backgroundVoiceController.state.value.isEnabled
            )
        } catch (_: Throwable) {}
    }
}
