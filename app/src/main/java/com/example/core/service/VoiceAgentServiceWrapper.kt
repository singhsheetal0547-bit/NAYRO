package com.example.core.service

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.example.core.agent.AgentRuntime
import com.example.core.agent.AgentState
import com.example.core.identity.AssistantIdentityStore
import com.example.core.permission.PermissionManager
import com.example.core.provider.AIProviderManager
import com.example.core.voice.BackgroundVoiceController
import com.example.core.voice.VoiceManager
import com.example.core.voice.VoiceState
import com.example.data.local.entities.ConversationEntity
import com.example.data.repository.NayroRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Tracks a deferred user action waiting for Android's runtime [Manifest.permission.RECORD_AUDIO]
 * permission dialog so voice-first interaction resumes immediately after the user grants access.
 */
enum class PendingMicPermissionAction {
    NONE,
    START_VOICE_CAPTURE,
    START_AGENT_SERVICE,
    START_SERVICE_AND_CAPTURE
}

/**
 * Unified, honest real-time snapshot exposed by [VoiceAgentServiceWrapper] for Compose UI
 * and diagnostics. Never reports microphone capture or running service unless genuinely verified.
 */
data class VoiceAgentServiceSnapshot(
    val assistantName: String = "Babu",
    val hasMicrophonePermission: Boolean = false,
    val isSpeechRecognitionAvailable: Boolean = false,
    val isServiceBound: Boolean = false,
    val serviceLifecycleState: ServiceLifecycleState = ServiceLifecycleState.STOPPED,
    val pipelineState: BackgroundVoicePipelineState = BackgroundVoicePipelineState.STOPPED,
    val voiceState: VoiceState = VoiceState.DISABLED,
    val isPaused: Boolean = false,
    val isVoiceCapturing: Boolean = false,
    val isAiReady: Boolean = false,
    val selectedLanguageTag: String = "en-IN",
    val liveTranscript: String = "",
    val partialTranscript: String = "",
    val lastAgentReply: String = "",
    val statusSummary: String = "Voice agent service is stopped. Tap Speak or Start Agent Service.",
    val errorMessage: String? = null,
    val pendingPermissionAction: PendingMicPermissionAction = PendingMicPermissionAction.NONE
) {
    val isServiceRunning: Boolean
        get() = serviceLifecycleState.isActiveOrRunning || serviceLifecycleState == ServiceLifecycleState.PAUSED

    val isServiceActiveAndUnpaused: Boolean
        get() = serviceLifecycleState.isActiveOrRunning && !isPaused
}

/**
 * Cohesive Microphone Permission + AI Speech-to-Text + Agent Running Service Wrapper for NAYRO.
 *
 * Bridges:
 * 1. Android runtime [Manifest.permission.RECORD_AUDIO] permission gating and revocation teardown.
 * 2. [NayroForegroundService] started + bound (`LocalBinder`) lifecycle management (`Start`, `Pause`,
 *    `Resume`, `Stop`, and notification/service-triggered Speech-to-Text capture).
 * 3. Android `SpeechRecognizer` / [VoiceManager] Speech-to-Text capture -> [AgentRuntime] AI execution
 *    -> [VoiceManager] TTS response playback without audio-focus or duplicate-dispatch conflicts.
 */
class VoiceAgentServiceWrapper(
    private val appContext: Context?,
    private val permissionManager: PermissionManager? = null,
    private val voiceManager: VoiceManager,
    private val backgroundVoiceController: BackgroundVoiceController? = null,
    private val agentRuntime: AgentRuntime? = null,
    private val repository: NayroRepository? = null,
    private val identityStore: AssistantIdentityStore? = null,
    private val providerManager: AIProviderManager? = null,
    private val permissionCheckerOverride: (() -> Boolean)? = null,
    private val speechAvailabilityOverride: (() -> Boolean)? = null,
    private val serviceStartDelegate: ((Context?) -> Unit)? = null,
    private val serviceStopDelegate: ((Context?) -> Unit)? = null,
    private val servicePauseDelegate: ((Context?, Int?, String) -> Unit)? = null,
    private val serviceResumeDelegate: ((Context?) -> Unit)? = null,
    private val sttStartDelegate: ((Context?) -> Boolean)? = null,
    private val sttStopDelegate: ((Context?) -> Unit)? = null,
    private val aiCommandExecutorOverride: (suspend (String) -> String)? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {

    private var boundService: NayroForegroundService? = null
    private var isBoundToService: Boolean = false
    private var pendingAction: PendingMicPermissionAction = PendingMicPermissionAction.NONE
    private var lastReplyText: String = ""
    private var wrapperErrorMessage: String? = null

    private val _snapshot = MutableStateFlow(buildSnapshot())
    val snapshot: StateFlow<VoiceAgentServiceSnapshot> = _snapshot.asStateFlow()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? NayroForegroundService.LocalBinder
            boundService = binder?.getService()
            isBoundToService = boundService != null
            refreshSnapshot()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            boundService = null
            isBoundToService = false
            refreshSnapshot()
        }
    }

    init {
        observeBackingFlows()
        refreshSnapshot()
    }

    private fun observeBackingFlows() {
        scope.launch {
            ServiceStateRepository.foregroundServiceStatus.collect { refreshSnapshot() }
        }
        scope.launch {
            ServiceStateRepository.backgroundVoicePipelineStatus.collect { refreshSnapshot() }
        }
        scope.launch {
            ServiceStateRepository.isBackgroundAgentPaused.collect { refreshSnapshot() }
        }
        scope.launch {
            voiceManager.voiceState.collect { refreshSnapshot() }
        }
        scope.launch {
            voiceManager.transcribedText.collect { refreshSnapshot() }
        }
        scope.launch {
            voiceManager.partialTranscript.collect { refreshSnapshot() }
        }
        scope.launch {
            voiceManager.errorMessage.collect { refreshSnapshot() }
        }
        scope.launch {
            voiceManager.configuration.collect { refreshSnapshot() }
        }
        identityStore?.let { store ->
            scope.launch {
                store.profile.collect { refreshSnapshot() }
            }
        }
        providerManager?.let { pm ->
            scope.launch {
                pm.activeProvider.collect { refreshSnapshot() }
            }
        }
    }

    fun hasMicrophonePermission(context: Context? = appContext): Boolean {
        permissionCheckerOverride?.let { return it.invoke() }
        if (permissionManager?.isMicrophoneGranted() == true) return true
        val ctx = context ?: return false
        return try {
            ContextCompat.checkSelfPermission(
                ctx,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    fun isSpeechRecognitionAvailable(): Boolean {
        speechAvailabilityOverride?.let { return it.invoke() }
        return voiceManager.speechRecognitionProvider.isRecognitionAvailable()
    }

    /**
     * Binds locally to [NayroForegroundService] (without forcing creation) when the UI enters
     * the foreground so the wrapper receives direct `LocalBinder` access to the running service.
     */
    fun bindToService(context: Context? = appContext): Boolean {
        val ctx = context ?: return false
        if (isBoundToService) return true
        return try {
            val intent = Intent(ctx, NayroForegroundService::class.java)
            val bound = ctx.bindService(intent, serviceConnection, 0)
            if (!bound) {
                isBoundToService = false
            }
            refreshSnapshot()
            bound
        } catch (_: Throwable) {
            isBoundToService = false
            refreshSnapshot()
            false
        }
    }

    /**
     * Cleanly unbinds the local `ServiceConnection` without stopping the running foreground service.
     */
    fun unbindFromService(context: Context? = appContext) {
        val ctx = context ?: return
        if (!isBoundToService) return
        try {
            ctx.unbindService(serviceConnection)
        } catch (_: Throwable) {
            // Safe ignore if service was already unbound by the OS
        } finally {
            boundService = null
            isBoundToService = false
            refreshSnapshot()
        }
    }

    /**
     * Voice-first entry point for Speech-to-Text capture.
     * - If currently capturing, stops cleanly.
     * - If speaking, interrupts TTS and starts listening (barge-in).
     * - If [Manifest.permission.RECORD_AUDIO] is missing, records [PendingMicPermissionAction.START_VOICE_CAPTURE]
     *   and invokes [onRequestMicrophonePermission] instead of failing silently.
     * - If the agent service is running, delegates capture through the running service wrapper;
     *   otherwise runs Android SpeechRecognizer directly via [VoiceManager].
     */
    fun requestVoiceCapture(
        context: Context? = appContext,
        ensureAgentServiceRunning: Boolean = false,
        onRequestMicrophonePermission: () -> Unit = {}
    ): Boolean {
        val currentVoiceState = voiceManager.voiceState.value
        val isCapturing = currentVoiceState == VoiceState.LISTENING ||
            currentVoiceState == VoiceState.FOLLOW_UP_LISTENING ||
            currentVoiceState == VoiceState.TRANSCRIBING

        if (isCapturing) {
            stopVoiceCapture(context)
            return false
        }

        if (!hasMicrophonePermission(context)) {
            pendingAction = if (ensureAgentServiceRunning) {
                PendingMicPermissionAction.START_SERVICE_AND_CAPTURE
            } else {
                PendingMicPermissionAction.START_VOICE_CAPTURE
            }
            wrapperErrorMessage = "Microphone permission (RECORD_AUDIO) is required for AI Speech-to-Text."
            refreshSnapshot()
            onRequestMicrophonePermission()
            return false
        }

        if (!isSpeechRecognitionAvailable()) {
            wrapperErrorMessage = "Android Speech Recognition service is not available on this device."
            refreshSnapshot()
            return false
        }

        wrapperErrorMessage = null
        pendingAction = PendingMicPermissionAction.NONE

        if (ensureAgentServiceRunning && !ServiceStateRepository.foregroundServiceStatus.value.state.isActiveOrRunning) {
            startAgentServiceInternal(context)
        }

        val started = startSpeechCaptureInternal(context)
        refreshSnapshot()
        return started
    }

    /**
     * Starts the persistent [NayroForegroundService] running wrapper after verifying `RECORD_AUDIO`
     * (required on Android 14+ for `FOREGROUND_SERVICE_TYPE_MICROPHONE`).
     */
    fun requestStartAgentService(
        context: Context? = appContext,
        autoCaptureSpeech: Boolean = false,
        onRequestMicrophonePermission: () -> Unit = {}
    ): Boolean {
        if (!hasMicrophonePermission(context)) {
            pendingAction = if (autoCaptureSpeech) {
                PendingMicPermissionAction.START_SERVICE_AND_CAPTURE
            } else {
                PendingMicPermissionAction.START_AGENT_SERVICE
            }
            wrapperErrorMessage = "Grant Microphone permission to start NAYRO's voice-first agent service."
            ServiceStateRepository.updateForegroundStatus(
                ServiceLifecycleState.PERMISSION_DENIED,
                "RECORD_AUDIO permission required for microphone foreground service."
            )
            refreshSnapshot()
            onRequestMicrophonePermission()
            return false
        }

        wrapperErrorMessage = null
        pendingAction = PendingMicPermissionAction.NONE
        startAgentServiceInternal(context)
        if (autoCaptureSpeech && isSpeechRecognitionAvailable()) {
            startSpeechCaptureInternal(context)
        }
        refreshSnapshot()
        return true
    }

    fun pauseAgentService(
        context: Context? = appContext,
        durationMinutes: Int? = null,
        reason: String = "Paused from voice wrapper"
    ) {
        wrapperErrorMessage = null
        val customPause = servicePauseDelegate
        if (customPause != null) {
            customPause.invoke(context, durationMinutes, reason)
        } else {
            val activeBinder = boundService
            if (activeBinder != null && activeBinder.isServiceRunningInForeground()) {
                activeBinder.pauseAgent(durationMinutes, reason)
            } else {
                NayroForegroundService.pauseService(context, durationMinutes, reason)
            }
        }
        refreshSnapshot()
    }

    fun resumeAgentService(
        context: Context? = appContext,
        onRequestMicrophonePermission: () -> Unit = {}
    ): Boolean {
        if (!hasMicrophonePermission(context)) {
            pendingAction = PendingMicPermissionAction.START_AGENT_SERVICE
            wrapperErrorMessage = "Microphone permission is required to resume NAYRO voice agent service."
            refreshSnapshot()
            onRequestMicrophonePermission()
            return false
        }
        wrapperErrorMessage = null
        val customResume = serviceResumeDelegate
        if (customResume != null) {
            customResume.invoke(context)
        } else {
            val activeBinder = boundService
            if (activeBinder != null && activeBinder.isServiceRunningInForeground()) {
                activeBinder.resumeAgent()
            } else {
                NayroForegroundService.resumeService(context)
            }
        }
        refreshSnapshot()
        return true
    }

    fun stopAgentService(context: Context? = appContext) {
        pendingAction = PendingMicPermissionAction.NONE
        wrapperErrorMessage = null
        stopVoiceCapture(context)
        val customStop = serviceStopDelegate
        if (customStop != null) {
            customStop.invoke(context)
        } else if (context != null && backgroundVoiceController != null) {
            backgroundVoiceController.disableBackgroundListening(context)
        } else {
            ServiceStateRepository.updateForegroundStatus(ServiceLifecycleState.STOPPED)
        }
        unbindFromService(context)
        refreshSnapshot()
    }

    fun stopVoiceCapture(context: Context? = appContext) {
        val customStopStt = sttStopDelegate
        if (customStopStt != null) {
            customStopStt.invoke(context)
        } else {
            val activeBinder = boundService
            if (activeBinder != null) {
                activeBinder.stopSpeechToTextCapture()
            } else {
                voiceManager.stopListening()
            }
        }
        refreshSnapshot()
    }

    fun stopSpeaking() {
        voiceManager.stopSpeaking()
        refreshSnapshot()
    }

    fun setRecognitionLanguage(languageTag: String) {
        voiceManager.setRecognitionLanguage(languageTag)
        refreshSnapshot()
    }

    fun clearTranscriptAndError() {
        wrapperErrorMessage = null
        voiceManager.clearTranscriptAndError()
        refreshSnapshot()
    }

    /**
     * Handles the Android runtime [Manifest.permission.RECORD_AUDIO] callback.
     * - When granted: clears errors and fulfills any pending voice capture or service start request.
     * - When denied or revoked: stops active listening and microphone foreground service cleanly.
     */
    fun onMicrophonePermissionResult(
        granted: Boolean,
        context: Context? = appContext
    ) {
        try {
            permissionManager?.evaluateSinglePermissionTargeted(
                Manifest.permission.RECORD_AUDIO,
                context as? android.app.Activity
            )
        } catch (_: Throwable) {}

        if (granted) {
            wrapperErrorMessage = null
            val actionToRun = pendingAction
            pendingAction = PendingMicPermissionAction.NONE
            backgroundVoiceController?.refreshState()

            when (actionToRun) {
                PendingMicPermissionAction.START_VOICE_CAPTURE -> {
                    if (isSpeechRecognitionAvailable()) {
                        startSpeechCaptureInternal(context)
                    } else {
                        wrapperErrorMessage = "Android Speech Recognition service is not available on this device."
                    }
                }
                PendingMicPermissionAction.START_AGENT_SERVICE -> {
                    startAgentServiceInternal(context)
                }
                PendingMicPermissionAction.START_SERVICE_AND_CAPTURE -> {
                    startAgentServiceInternal(context)
                    if (isSpeechRecognitionAvailable()) {
                        startSpeechCaptureInternal(context)
                    }
                }
                PendingMicPermissionAction.NONE -> {
                    // Permission granted passively; just refresh readiness state
                }
            }
        } else {
            pendingAction = PendingMicPermissionAction.NONE
            wrapperErrorMessage = "Microphone permission was denied. Grant RECORD_AUDIO in settings to use voice commands."
            voiceManager.onMicrophonePermissionRevoked()
            val customStop = serviceStopDelegate
            if (customStop != null) {
                customStop.invoke(context)
            } else if (context != null && backgroundVoiceController != null) {
                backgroundVoiceController.onMicrophonePermissionRevoked(context)
            } else {
                ServiceStateRepository.updateForegroundStatus(
                    ServiceLifecycleState.PERMISSION_DENIED,
                    "Microphone permission denied or revoked."
                )
            }
        }
        refreshSnapshot()
    }

    /**
     * Executes a recognized Speech-to-Text transcript (or typed fallback) through NAYRO's
     * AI [AgentRuntime], persists the turn to Room when available, and speaks the reply via TTS.
     */
    suspend fun executeRecognizedSpeechCommand(
        rawTranscript: String,
        fromVoice: Boolean = true
    ): String {
        val normalized = voiceManager.normalizeVoiceInvocationCommand(rawTranscript)
        if (normalized.isBlank()) return ""

        val bgEnabled = backgroundVoiceController?.state?.value?.isEnabled ?: false
        if (fromVoice && voiceManager.isConversationExitPhrase(normalized)) {
            voiceManager.endConversationSession(returnToStandby = bgEnabled)
            agentRuntime?.setState(AgentState.Idle)
            refreshSnapshot()
            return ""
        }

        val wasServiceRunning = ServiceStateRepository.foregroundServiceStatus.value.state == ServiceLifecycleState.RUNNING
        if (wasServiceRunning) {
            ServiceStateRepository.updateForegroundStatus(ServiceLifecycleState.PROCESSING)
        }

        var reply = ""
        try {
            repository?.addConversationMessage(
                ConversationEntity(sender = "user", content = normalized)
            )
            reply = if (aiCommandExecutorOverride != null) {
                aiCommandExecutorOverride.invoke(normalized)
            } else {
                agentRuntime?.executeUserInstruction(normalized)
                    ?: "Agent runtime is not initialized."
            }
            lastReplyText = reply
            repository?.addConversationMessage(
                ConversationEntity(sender = "assistant", content = reply)
            )
            agentRuntime?.setState(AgentState.Speaking(reply))
        } catch (e: Exception) {
            reply = "Execution error: ${e.localizedMessage ?: e.javaClass.simpleName}"
            lastReplyText = reply
            wrapperErrorMessage = reply
            agentRuntime?.setState(AgentState.Error(reply))
        } finally {
            if (wasServiceRunning && !ServiceStateRepository.isBackgroundAgentPaused.value) {
                ServiceStateRepository.updateForegroundStatus(ServiceLifecycleState.RUNNING)
            }
            val voiceConfig = voiceManager.configuration.value
            val shouldSpeak = fromVoice || voiceConfig.isWakeWordEnabled || voiceConfig.isTtsEnabled
            val keepFollowUp = fromVoice && voiceConfig.isContinuousConversationEnabled
            if (fromVoice || shouldSpeak) {
                voiceManager.completeCommandTurn(
                    responseText = reply,
                    shouldSpeak = shouldSpeak && voiceConfig.isTtsEnabled,
                    expectFollowUp = keepFollowUp
                )
            }
            refreshSnapshot()
        }
        return reply
    }

    private fun startAgentServiceInternal(context: Context?) {
        val customStart = serviceStartDelegate
        if (customStart != null) {
            customStart.invoke(context)
            return
        }
        if (context != null && backgroundVoiceController != null) {
            backgroundVoiceController.enableBackgroundListening(context)
            bindToService(context)
        } else {
            ServiceStateRepository.updateForegroundStatus(ServiceLifecycleState.RUNNING)
        }
    }

    private fun startSpeechCaptureInternal(context: Context?): Boolean {
        val customStartStt = sttStartDelegate
        if (customStartStt != null) {
            return customStartStt.invoke(context)
        }
        val activeBinder = boundService
        if (activeBinder != null && activeBinder.isServiceRunningInForeground()) {
            return activeBinder.triggerSpeechToTextCapture()
        }
        if (voiceManager.voiceState.value == VoiceState.SPEAKING) {
            voiceManager.interruptSpeakingAndListen()
        } else {
            voiceManager.startListening()
        }
        return voiceManager.voiceState.value != VoiceState.ERROR
    }

    fun refreshSnapshot(): VoiceAgentServiceSnapshot {
        val updated = buildSnapshot()
        _snapshot.value = updated
        return updated
    }

    private fun buildSnapshot(): VoiceAgentServiceSnapshot {
        val hasMic = hasMicrophonePermission(appContext)
        val sttAvailable = isSpeechRecognitionAvailable()
        val fgStatus = ServiceStateRepository.foregroundServiceStatus.value
        val pipeline = ServiceStateRepository.backgroundVoicePipelineStatus.value
        val isPaused = ServiceStateRepository.isBackgroundAgentPaused.value
        val vState = voiceManager.voiceState.value
        val vConfig = voiceManager.configuration.value
        val isCapturing = vState == VoiceState.LISTENING ||
            vState == VoiceState.FOLLOW_UP_LISTENING ||
            vState == VoiceState.TRANSCRIBING
        val assistantName = identityStore?.getDisplayName()?.takeIf { it.isNotBlank() }
            ?: vConfig.assistantName.ifBlank { "Babu" }
        val isAiReady = providerManager?.activeProvider?.value != null

        val effectiveError = wrapperErrorMessage
            ?: voiceManager.errorMessage.value
            ?: fgStatus.lastError

        val summary = when {
            !hasMic -> "Microphone permission required for voice-first Speech-to-Text and running service."
            !sttAvailable -> "Android Speech Recognition service is unavailable on this device."
            isCapturing -> "Listening via AI Speech-to-Text…"
            vState == VoiceState.THINKING || vState == VoiceState.EXECUTING -> "$assistantName AI agent is processing your voice command…"
            vState == VoiceState.SPEAKING -> "$assistantName is speaking a response…"
            isPaused -> "Agent running service is paused. Resume to restore background listening."
            pipeline.state == BackgroundVoicePipelineState.BACKGROUND_VOICE_READY ->
                "Voice Agent Service running & verified — hands-free wake and Speech-to-Text ready."
            fgStatus.state.isActiveOrRunning ->
                "Voice Agent Service running (${fgStatus.state.name.lowercase()}) — tap Speak for Speech-to-Text."
            else ->
                "Microphone ready for Speech-to-Text. Start Agent Service for background hands-free voice."
        }

        return VoiceAgentServiceSnapshot(
            assistantName = assistantName,
            hasMicrophonePermission = hasMic,
            isSpeechRecognitionAvailable = sttAvailable,
            isServiceBound = isBoundToService,
            serviceLifecycleState = fgStatus.state,
            pipelineState = pipeline.state,
            voiceState = vState,
            isPaused = isPaused,
            isVoiceCapturing = isCapturing,
            isAiReady = isAiReady,
            selectedLanguageTag = vConfig.languageTag,
            liveTranscript = voiceManager.transcribedText.value,
            partialTranscript = voiceManager.partialTranscript.value,
            lastAgentReply = lastReplyText,
            statusSummary = summary,
            errorMessage = effectiveError,
            pendingPermissionAction = pendingAction
        )
    }
}
