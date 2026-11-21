package com.example.core.telephony

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.InCallService
import android.telecom.VideoProfile
import androidx.annotation.RequiresApi
import com.example.NayroApplication
import com.example.core.notification.NotificationIntelligenceRepository
import com.example.core.service.ServiceStateRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ActiveTelecomCallInfo(
    val callerNumber: String?,
    val callerDisplayName: String?,
    val stateLabel: String, // RINGING, DIALING, ACTIVE, HOLDING, DISCONNECTED
    val spamAssessment: SpamAssessmentResult? = null,
    val updatedAtMs: Long = System.currentTimeMillis()
)

/**
 * Shared thread-safe telemetry & explicit-user-command bridge for [NayroCallScreeningService]
 * and [NayroInCallService].
 *
 * CRITICAL CALLING RULE ENFORCEMENT:
 * - NAYRO may identify incoming callers, notify the user, and answer/reject/end calls ONLY on
 *   explicit user instruction.
 * - Whenever a call is RINGING or ACTIVE, NAYRO immediately signals [VoiceManager] to suspend
 *   AI speech & wake capture so AI NEVER speaks to the remote caller.
 * - Zero SIP, VoIP, or AI conversational audio transmission is implemented or permitted.
 */
object TelecomCallControlBridge {

    private val _activeCallInfo = MutableStateFlow<ActiveTelecomCallInfo?>(null)
    val activeCallInfo: StateFlow<ActiveTelecomCallInfo?> = _activeCallInfo.asStateFlow()

    private val _isInCallServiceBound = MutableStateFlow(false)
    val isInCallServiceBound: StateFlow<Boolean> = _isInCallServiceBound.asStateFlow()

    private val _isCallScreeningActive = MutableStateFlow(false)
    val isCallScreeningActive: StateFlow<Boolean> = _isCallScreeningActive.asStateFlow()

    @Volatile
    private var currentCall: Call? = null

    fun setInCallServiceBound(bound: Boolean) {
        _isInCallServiceBound.value = bound
        if (!bound) {
            currentCall = null
            _activeCallInfo.value = null
        }
    }

    fun recordScreenedIncomingCall(
        phoneNumber: String?,
        resolvedContactName: String?,
        spamAssessment: SpamAssessmentResult
    ) {
        _isCallScreeningActive.value = true
        val display = resolvedContactName ?: phoneNumber?.ifBlank { "Unknown Caller" } ?: "Unknown Caller"
        _activeCallInfo.value = ActiveTelecomCallInfo(
            callerNumber = phoneNumber,
            callerDisplayName = display,
            stateLabel = "RINGING",
            spamAssessment = spamAssessment
        )
        CallIntelligenceManager.lastCallLifecycleState = PhoneCallLifecycleState.CALL_RINGING

        val summary = if (spamAssessment.isSuspiciousOrSpam) {
            "Incoming call from $display — ${spamAssessment.riskLevel.label}: ${spamAssessment.reasons.firstOrNull().orEmpty()}"
        } else {
            "Incoming call from $display"
        }
        ServiceStateRepository.appendActivityLog(summary)
        NotificationIntelligenceRepository.onNotificationPosted(
            com.example.core.notification.CapturedNotificationItem(
                key = "nayro_call_screen_${System.currentTimeMillis()}",
                packageName = "com.android.telecom",
                title = if (spamAssessment.isSuspiciousOrSpam) "Incoming Call (${spamAssessment.riskLevel.label})" else "Incoming Call",
                text = summary,
                category = com.example.core.notification.NotificationCategory.SYSTEM_ALERT,
                postTime = System.currentTimeMillis()
            )
        )
    }

    fun attachCall(call: Call, resolvedName: String?, spamAssessment: SpamAssessmentResult?) {
        currentCall = call
        val number = call.details?.handle?.schemeSpecificPart
        val stateLabel = mapCallState(call.state)
        _activeCallInfo.value = ActiveTelecomCallInfo(
            callerNumber = number,
            callerDisplayName = resolvedName ?: number ?: "Unknown Caller",
            stateLabel = stateLabel,
            spamAssessment = spamAssessment
        )
        // CRITICAL CALLING RULE: Ensure AI TTS & wake mic stay completely muted while any call is active
        notifyVoiceManagerCallState(isActiveOrRinging = stateLabel in setOf("RINGING", "DIALING", "ACTIVE"))
    }

    fun updateCallState(call: Call, newState: Int) {
        if (currentCall != null && currentCall !== call) return
        val label = mapCallState(newState)
        val existing = _activeCallInfo.value
        _activeCallInfo.value = (existing ?: ActiveTelecomCallInfo(
            callerNumber = call.details?.handle?.schemeSpecificPart,
            callerDisplayName = call.details?.handle?.schemeSpecificPart ?: "Unknown Caller",
            stateLabel = label
        )).copy(stateLabel = label, updatedAtMs = System.currentTimeMillis())

        when (label) {
            "RINGING" -> {
                CallIntelligenceManager.lastCallLifecycleState = PhoneCallLifecycleState.CALL_RINGING
                notifyVoiceManagerCallState(isActiveOrRinging = true)
            }
            "ACTIVE" -> {
                CallIntelligenceManager.lastCallLifecycleState = PhoneCallLifecycleState.CALL_ANSWERED
                CallIntelligenceManager.lastOutgoingCallAnswered = true
                notifyVoiceManagerCallState(isActiveOrRinging = true)
            }
            "DISCONNECTED" -> {
                CallIntelligenceManager.lastCallLifecycleState = PhoneCallLifecycleState.CALL_ENDED
                currentCall = null
                _activeCallInfo.value = null
                notifyVoiceManagerCallState(isActiveOrRinging = false)
            }
        }
    }

    fun detachCall(call: Call) {
        if (currentCall === call) {
            currentCall = null
            _activeCallInfo.value = null
            CallIntelligenceManager.lastCallLifecycleState = PhoneCallLifecycleState.CALL_ENDED
            notifyVoiceManagerCallState(isActiveOrRinging = false)
        }
    }

    /**
     * Answers the currently ringing call via official Android [Call.answer] when [NayroInCallService] is bound.
     * The user speaks personally; AI speech remains muted throughout the call.
     */
    fun answerActiveRingingCall(): Boolean {
        val call = currentCall ?: return false
        return try {
            notifyVoiceManagerCallState(isActiveOrRinging = true)
            call.answer(VideoProfile.STATE_AUDIO_ONLY)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Rejects a ringing call or disconnects an active call via official Android [Call.reject] / [Call.disconnect].
     */
    fun rejectOrEndCurrentCall(): Boolean {
        val call = currentCall ?: return false
        return try {
            if (call.state == Call.STATE_RINGING) {
                call.reject(false, null)
            } else {
                call.disconnect()
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun hasLiveTelecomCall(): Boolean = currentCall != null

    fun resetForTesting() {
        currentCall = null
        _activeCallInfo.value = null
        _isInCallServiceBound.value = false
        _isCallScreeningActive.value = false
    }

    private fun notifyVoiceManagerCallState(isActiveOrRinging: Boolean) {
        try {
            val vm = NayroApplication.instance.voiceManager
            if (isActiveOrRinging) {
                vm.onExternalAudioInterruption("Phone call active — AI speech & wake muted (user speaks personally)")
            } else {
                vm.onExternalAudioInterruptionEnded()
            }
        } catch (_: Throwable) {}
    }

    private fun mapCallState(state: Int): String = when (state) {
        Call.STATE_RINGING -> "RINGING"
        Call.STATE_DIALING, Call.STATE_CONNECTING -> "DIALING"
        Call.STATE_ACTIVE -> "ACTIVE"
        Call.STATE_HOLDING -> "HOLDING"
        Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> "DISCONNECTED"
        else -> "UNKNOWN"
    }
}

/**
 * Official Android 10+ (API 29+) [CallScreeningService] for NAYRO's Autonomous Communication Agent.
 *
 * Identifies incoming callers, runs on-device [CommunicationSpamDetector], and records caller
 * alerts transparently without silently dropping calls or conducting AI voice conversations.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class NayroCallScreeningService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        val number = callDetails.handle?.schemeSpecificPart
        val resolvedName = try {
            if (!number.isNullOrBlank()) {
                NayroApplication.instance.agentRuntime.callIntelligenceManager
                    .resolveContactByPhoneNumber(number)
            } else null
        } catch (_: Throwable) {
            null
        }

        val isStirPass = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            callDetails.callerNumberVerificationStatus == android.telecom.Connection.VERIFICATION_STATUS_PASSED
        } else {
            false
        }

        val spamAssessment = CommunicationSpamDetector.assessIncomingCall(
            phoneNumber = number,
            isKnownContact = !resolvedName.isNullOrBlank(),
            isVerifiedStirShakenPass = isStirPass
        )

        TelecomCallControlBridge.recordScreenedIncomingCall(
            phoneNumber = number,
            resolvedContactName = resolvedName,
            spamAssessment = spamAssessment
        )

        // Allow the call through with notification so the user remains in full control
        val response = CallResponse.Builder()
            .setDisallowCall(false)
            .setRejectCall(false)
            .setSkipCallLog(false)
            .setSkipNotification(false)
            .build()
        respondToCall(callDetails, response)
    }
}

/**
 * Official Android [InCallService] for NAYRO's Autonomous Communication Agent.
 * Active only when the user explicitly selects NAYRO for the Default Dialer role; otherwise NAYRO
 * falls back cleanly to [android.telecom.TelecomManager] + `ANSWER_PHONE_CALLS`.
 *
 * CRITICAL CALLING RULE: AI NEVER speaks or injects voice into phone calls.
 */
class NayroInCallService : InCallService() {

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            TelecomCallControlBridge.updateCallState(call, state)
        }
    }

    override fun onCreate() {
        super.onCreate()
        TelecomCallControlBridge.setInCallServiceBound(true)
    }

    override fun onDestroy() {
        super.onDestroy()
        TelecomCallControlBridge.setInCallServiceBound(false)
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        call.registerCallback(callCallback)
        val number = call.details?.handle?.schemeSpecificPart
        val resolvedName = try {
            if (!number.isNullOrBlank()) {
                NayroApplication.instance.agentRuntime.callIntelligenceManager
                    .resolveContactByPhoneNumber(number)
            } else null
        } catch (_: Throwable) {
            null
        }
        val spam = CommunicationSpamDetector.assessIncomingCall(
            phoneNumber = number,
            isKnownContact = !resolvedName.isNullOrBlank()
        )
        TelecomCallControlBridge.attachCall(call, resolvedName, spam)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        call.unregisterCallback(callCallback)
        TelecomCallControlBridge.detachCall(call)
    }
}
