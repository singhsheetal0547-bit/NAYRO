package com.example.core.telephony

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.example.core.accessibility.AccessibilityActionController
import com.example.core.accessibility.NayroAccessibilityService
import com.example.core.agent.ActionExecutionResult
import com.example.core.agent.VerificationStatus
import com.example.core.notification.NotificationIntelligenceRepository

enum class PhoneCallLifecycleState {
    DIALER_OPENED,
    CALL_INITIATED,
    CALL_RINGING,
    CALL_ANSWERED,
    CALL_ENDED,
    CALL_FAILED,
    CALL_INCONCLUSIVE
}

data class CallStateSnapshot(
    val isRinging: Boolean,
    val isOffhook: Boolean,
    val callerDisplayName: String,
    val statusSummary: String,
    val permissionRequired: Boolean = false
)

data class ResolvedContactItem(
    val displayName: String,
    val phoneNumber: String
)

data class CallHistoryEntry(
    val callerNameOrNumber: String,
    val phoneNumber: String,
    val callType: String, // INCOMING, OUTGOING, MISSED, REJECTED
    val timestampMs: Long,
    val durationSeconds: Long
)

sealed class ContactResolutionResult {
    data class SingleMatch(val contact: ResolvedContactItem) : ContactResolutionResult()
    data class Ambiguous(val query: String, val candidates: List<ResolvedContactItem>) : ContactResolutionResult()
    data class NotFound(val query: String) : ContactResolutionResult()
    data class PermissionRequired(val permission: String = Manifest.permission.READ_CONTACTS) : ContactResolutionResult()
}

/**
 * Sections 1, 4 & 6 — Complete Phone, Outgoing Call, Incoming Call Management, Call History & Contact Manager for NAYRO (Babu).
 *
 * Critical Acceptance Guarantees:
 * - Opening the dialer (`OPEN_PHONE_DIALER` / `ACTION_DIAL`) is NEVER reported as a completed or initiated phone call.
 * - `MAKE_PHONE_CALL` requires `CALL_PHONE` permission, contact resolution, phone number validation, SIM readiness,
 *   and explicit user confirmation before initiating a real call via `Intent.ACTION_CALL` / `TelecomManager.placeCall`.
 * - A call being initiated (`CALL_STATE_OFFHOOK`) is NOT treated as proof that the recipient answered unless
 *   verified by connected call duration or explicit call-answer evidence.
 */
class CallIntelligenceManager(
    private val context: Context?,
    private val contactDirectoryProvider: (() -> List<ResolvedContactItem>)? = null
) {

    companion object {
        @Volatile
        var lastOutgoingCallInitiated: Boolean = false

        @Volatile
        var lastOutgoingCallAnswered: Boolean = false

        @Volatile
        var lastCallLifecycleState: PhoneCallLifecycleState = PhoneCallLifecycleState.CALL_INCONCLUSIVE

        @Volatile
        var lastDialedRecipient: String? = null

        @Volatile
        private var lastCallTimestampMs: Long = 0L

        @Volatile
        private var lastCalledNormalizedNumber: String = ""

        /**
         * Validates that [rawPhone] is a real dialable phone number (3..15 digits, optional leading '+',
         * no alphabetic characters).
         */
        fun isValidPhoneNumber(rawPhone: String): Boolean {
            val trimmed = rawPhone.trim()
            if (trimmed.isEmpty()) return false
            if (trimmed.any { it.isLetter() }) return false
            val allowedChars = trimmed.all {
                it.isDigit() || it == '+' || it == '-' || it == ' ' || it == '(' || it == ')' || it == '*' || it == '#'
            }
            if (!allowedChars) return false
            val digitsOnly = trimmed.filter { it.isDigit() }
            return digitsOnly.length in 3..15
        }

        fun normalizePhoneDigits(rawPhone: String): String {
            return rawPhone.filter { it.isDigit() || it == '+' }
        }
    }

    var testOverrideCallState: CallStateSnapshot? = null
    var testOverrideLastOutgoingCallAnswered: Boolean? = null
    var testOverrideCallInitiated: Boolean? = null
    var testOverrideCallPhonePermission: Boolean? = null
    var testOverridePhoneStatePermission: Boolean? = null
    var testOverrideAnswerCallsPermission: Boolean? = null
    var testOverrideContactsPermission: Boolean? = null
    var testOverrideCallLogPermission: Boolean? = null
    var testOverrideDefaultDialerRole: Boolean? = null
    var testOverrideSimReady: Boolean? = null
    var testOverrideDialerAvailable: Boolean? = null
    var testOverrideCallHistory: List<CallHistoryEntry>? = null
    val testSavedContacts: MutableList<ResolvedContactItem> = mutableListOf()

    fun resetDuplicateCallGuard() {
        lastCallTimestampMs = 0L
        lastCalledNormalizedNumber = ""
    }

    fun hasCallPhonePermission(): Boolean {
        testOverrideCallPhonePermission?.let { return it }
        val ctx = context ?: return false
        return try {
            ContextCompat.checkSelfPermission(
                ctx,
                Manifest.permission.CALL_PHONE
            ) == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    fun hasPhoneStatePermission(): Boolean {
        testOverridePhoneStatePermission?.let { return it }
        val ctx = context ?: return false
        return try {
            ContextCompat.checkSelfPermission(
                ctx,
                Manifest.permission.READ_PHONE_STATE
            ) == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    fun hasAnswerPhoneCallsPermission(): Boolean {
        testOverrideAnswerCallsPermission?.let { return it }
        val ctx = context ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.checkSelfPermission(
                    ctx,
                    Manifest.permission.ANSWER_PHONE_CALLS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    fun hasContactsPermission(): Boolean {
        testOverrideContactsPermission?.let { return it }
        val ctx = context ?: return false
        return try {
            ContextCompat.checkSelfPermission(
                ctx,
                Manifest.permission.READ_CONTACTS
            ) == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    fun hasCallLogPermission(): Boolean {
        testOverrideCallLogPermission?.let { return it }
        val ctx = context ?: return false
        return try {
            ContextCompat.checkSelfPermission(
                ctx,
                Manifest.permission.READ_CALL_LOG
            ) == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    fun isDefaultDialerRoleHeld(): Boolean {
        testOverrideDefaultDialerRole?.let { return it }
        val ctx = context ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = ctx.getSystemService(RoleManager::class.java)
                roleManager?.isRoleHeld(RoleManager.ROLE_DIALER) == true
            } else {
                val telecom = ctx.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                telecom?.defaultDialerPackage == ctx.packageName
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Creates a user-facing Android system Intent to request `RoleManager.ROLE_DIALER` when needed.
     */
    fun createDefaultDialerRoleRequestIntent(): Intent? {
        val ctx = context ?: return null
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = ctx.getSystemService(RoleManager::class.java)
                if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                    roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
                } else null
            } else {
                Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
                    putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, ctx.packageName)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Checks whether a SIM card and cellular telephony radio are ready for placing calls.
     */
    fun isSimAndTelephonyReady(): Boolean {
        testOverrideSimReady?.let { return it }
        val ctx = context ?: return true
        return try {
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return false
            val simState = tm.simState
            simState != TelephonyManager.SIM_STATE_ABSENT &&
                simState != TelephonyManager.SIM_STATE_NOT_READY &&
                simState != TelephonyManager.SIM_STATE_PERM_DISABLED &&
                simState != TelephonyManager.SIM_STATE_CARD_IO_ERROR
        } catch (_: Exception) {
            true
        }
    }

    /**
     * Section 1A — `OPEN_PHONE_DIALER`:
     * Opens the Android Phone Dialer (`Intent.ACTION_DIAL`) with a resolved contact or phone number
     * pre-populated WITHOUT placing an outgoing phone call.
     * Never claims that a phone call was placed or answered.
     */
    fun openPhoneDialer(rawTarget: String): ActionExecutionResult {
        val cleanTarget = rawTarget.trim()
        var displayLabel = cleanTarget.ifBlank { "Dialer" }
        var phoneToPopulate = ""

        if (cleanTarget.isNotEmpty()) {
            val isDirectNumber = cleanTarget.any { it.isDigit() } && cleanTarget.none { it.isLetter() }
            if (isDirectNumber) {
                if (!isValidPhoneNumber(cleanTarget)) {
                    return ActionExecutionResult(
                        false,
                        "Cannot open phone dialer: '$cleanTarget' is not a valid phone number.",
                        VerificationStatus.FAILED
                    )
                }
                phoneToPopulate = cleanTarget
                val contactName = resolveContactByPhoneNumber(cleanTarget)
                if (!contactName.isNullOrBlank()) {
                    displayLabel = "$contactName ($cleanTarget)"
                }
            } else {
                when (val res = resolveContactsByName(cleanTarget)) {
                    is ContactResolutionResult.SingleMatch -> {
                        displayLabel = res.contact.displayName
                        phoneToPopulate = res.contact.phoneNumber
                    }
                    is ContactResolutionResult.Ambiguous -> {
                        val candidates = res.candidates.joinToString(", ") { "${it.displayName} (${it.phoneNumber})" }
                        return ActionExecutionResult(
                            false,
                            "Ambiguous contact '$cleanTarget': multiple matches found ($candidates). Please specify which contact to open in the dialer.",
                            VerificationStatus.FAILED
                        )
                    }
                    is ContactResolutionResult.NotFound -> {
                        return ActionExecutionResult(
                            false,
                            "Contact '$cleanTarget' was not found in device contacts.",
                            VerificationStatus.FAILED
                        )
                    }
                    is ContactResolutionResult.PermissionRequired -> {
                        return ActionExecutionResult(
                            false,
                            "Permission required: READ_CONTACTS is needed to resolve '$cleanTarget' before opening the dialer.",
                            VerificationStatus.REQUIRES_PERMISSION
                        )
                    }
                }
            }
        }

        if (testOverrideDialerAvailable != null || context == null) {
            val available = testOverrideDialerAvailable ?: true
            return if (available) {
                lastCallLifecycleState = PhoneCallLifecycleState.DIALER_OPENED
                ActionExecutionResult(
                    true,
                    "Opened phone dialer for $displayLabel${if (phoneToPopulate.isNotBlank()) " ($phoneToPopulate)" else ""}. No call was placed automatically [DIALER_OPENED_ONLY=true] [STATE=${lastCallLifecycleState.name}].",
                    VerificationStatus.VERIFIED
                )
            } else {
                lastCallLifecycleState = PhoneCallLifecycleState.CALL_FAILED
                ActionExecutionResult(
                    false,
                    "No dialer application is available on this device.",
                    VerificationStatus.NOT_SUPPORTED
                )
            }
        }

        return try {
            val uri = if (phoneToPopulate.isNotBlank()) {
                Uri.parse("tel:${Uri.encode(phoneToPopulate)}")
            } else {
                Uri.parse("tel:")
            }
            val dialIntent = Intent(Intent.ACTION_DIAL, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(dialIntent)
            lastCallLifecycleState = PhoneCallLifecycleState.DIALER_OPENED
            ActionExecutionResult(
                true,
                "Opened phone dialer for $displayLabel${if (phoneToPopulate.isNotBlank()) " ($phoneToPopulate)" else ""}. No call was placed automatically [DIALER_OPENED_ONLY=true] [STATE=${lastCallLifecycleState.name}].",
                VerificationStatus.VERIFIED
            )
        } catch (e: Exception) {
            lastCallLifecycleState = PhoneCallLifecycleState.CALL_FAILED
            ActionExecutionResult(
                false,
                "Failed to open phone dialer for '$displayLabel': ${e.localizedMessage}",
                VerificationStatus.FAILED
            )
        }
    }

    /**
     * Section 1B — `MAKE_PHONE_CALL`:
     * Places an ACTUAL outgoing phone call after:
     * 1. Resolving the contact (if a contact name was provided).
     * 2. Validating the phone number format.
     * 3. Checking SIM / cellular readiness.
     * 4. Checking `CALL_PHONE` permission (returns `REQUIRES_PERMISSION` if missing — never silently opens `ACTION_DIAL` and claims a call succeeded).
     * 5. Verifying explicit user confirmation (`isConfirmed == true`).
     * 6. Preventing duplicate call initiation.
     * 7. Initiating the call via `Intent.ACTION_CALL` / `TelecomManager.placeCall`.
     * 8. Observing actual call state and distinguishing call initiation (`[CALL_INITIATED=true]`) from whether the recipient answered (`[CALL_ANSWERED=true/false]`).
     */
    @Suppress("MissingPermission")
    fun initiateOutgoingCall(
        rawTarget: String,
        isConfirmed: Boolean,
        observeCallState: Boolean = false
    ): ActionExecutionResult {
        val cleanTarget = rawTarget.trim()
        if (cleanTarget.isEmpty() || cleanTarget.equals("Contact", ignoreCase = true)) {
            return ActionExecutionResult(
                false,
                "Cannot place phone call: target contact name or phone number is missing.",
                VerificationStatus.FAILED
            )
        }

        // 1. Resolve contact or validate direct phone number
        val hasLetters = cleanTarget.any { it.isLetter() }
        val looksLikeRawNumberAttempt = !hasLetters && cleanTarget.none { it.isDigit() }
        if (looksLikeRawNumberAttempt) {
            return ActionExecutionResult(
                false,
                "Invalid phone number '$cleanTarget'. Please provide a valid phone number or saved contact name.",
                VerificationStatus.FAILED
            )
        }

        val isDirectNumber = !hasLetters && cleanTarget.any { it.isDigit() }
        var resolvedDisplayName = cleanTarget
        val phoneToCall: String = if (isDirectNumber) {
            if (!isValidPhoneNumber(cleanTarget)) {
                return ActionExecutionResult(
                    false,
                    "Invalid phone number '$cleanTarget'. Phone numbers must contain 3 to 15 digits.",
                    VerificationStatus.FAILED
                )
            }
            resolveContactByPhoneNumber(cleanTarget)?.let { resolvedDisplayName = it }
            cleanTarget
        } else {
            when (val res = resolveContactsByName(cleanTarget)) {
                is ContactResolutionResult.SingleMatch -> {
                    resolvedDisplayName = res.contact.displayName
                    res.contact.phoneNumber
                }
                is ContactResolutionResult.Ambiguous -> {
                    val candidates = res.candidates.joinToString(", ") { "${it.displayName} (${it.phoneNumber})" }
                    return ActionExecutionResult(
                        false,
                        "Ambiguous contact '$cleanTarget': multiple matches found ($candidates). Please specify the exact contact or phone number.",
                        VerificationStatus.FAILED
                    )
                }
                is ContactResolutionResult.PermissionRequired -> {
                    return ActionExecutionResult(
                        false,
                        "Permission required: READ_CONTACTS is needed to resolve contact '$cleanTarget'.",
                        VerificationStatus.REQUIRES_PERMISSION
                    )
                }
                is ContactResolutionResult.NotFound -> {
                    return ActionExecutionResult(
                        false,
                        "Contact '$cleanTarget' was not found in device contacts.",
                        VerificationStatus.FAILED
                    )
                }
            }
        }

        // 2. Validate resolved phone number
        if (!isValidPhoneNumber(phoneToCall)) {
            return ActionExecutionResult(
                false,
                "Invalid phone number '$phoneToCall' for contact '$resolvedDisplayName'.",
                VerificationStatus.FAILED
            )
        }

        // 3. Check SIM / telephony readiness
        if (!isSimAndTelephonyReady()) {
            return ActionExecutionResult(
                false,
                "Cannot place phone call to $resolvedDisplayName ($phoneToCall): SIM card is missing or cellular service is unavailable.",
                VerificationStatus.FAILED
            )
        }

        // 4. Check CALL_PHONE permission before confirmation/execution
        if (!hasCallPhonePermission()) {
            return ActionExecutionResult(
                false,
                "Permission required: CALL_PHONE permission is required to place an actual call to $resolvedDisplayName ($phoneToCall). Grant CALL_PHONE or use OPEN_PHONE_DIALER.",
                VerificationStatus.REQUIRES_PERMISSION
            )
        }

        // 5. Require explicit user confirmation
        if (!isConfirmed) {
            return ActionExecutionResult(
                false,
                "Confirmation required before calling $resolvedDisplayName ($phoneToCall).",
                VerificationStatus.REQUIRES_CONFIRMATION
            )
        }

        // 6. Duplicate call guard (prevent accidental double-dialing within 4 seconds)
        val normDigits = normalizePhoneDigits(phoneToCall)
        val nowMs = System.currentTimeMillis()
        if (normDigits == lastCalledNormalizedNumber && (nowMs - lastCallTimestampMs) < 4_000L) {
            return ActionExecutionResult(
                false,
                "Duplicate call prevented: a call to $resolvedDisplayName ($phoneToCall) was just initiated.",
                VerificationStatus.FAILED
            )
        }
        lastCalledNormalizedNumber = normDigits
        lastCallTimestampMs = nowMs
        lastDialedRecipient = resolvedDisplayName

        // 7. Handle deterministic test / simulation overrides when running in JVM unit tests
        if (testOverrideLastOutgoingCallAnswered != null || testOverrideCallInitiated != null || context == null) {
            val initiated = testOverrideCallInitiated ?: true
            lastOutgoingCallInitiated = initiated
            if (!initiated) {
                lastOutgoingCallAnswered = false
                lastCallLifecycleState = PhoneCallLifecycleState.CALL_FAILED
                return ActionExecutionResult(
                    false,
                    "Failed to initiate outgoing call to $resolvedDisplayName ($phoneToCall).",
                    VerificationStatus.FAILED
                )
            }

            val explicitAnswered = testOverrideLastOutgoingCallAnswered
            return if (explicitAnswered != null) {
                lastOutgoingCallAnswered = explicitAnswered
                lastCallLifecycleState = if (explicitAnswered) PhoneCallLifecycleState.CALL_ANSWERED else PhoneCallLifecycleState.CALL_INITIATED
                val answerMsg = if (explicitAnswered) {
                    "Outgoing call to $resolvedDisplayName ($phoneToCall) initiated and verified answered [CALL_INITIATED=true] [CALL_ANSWERED=true] [STATE=${lastCallLifecycleState.name}]."
                } else {
                    "Outgoing call to $resolvedDisplayName ($phoneToCall) initiated, but recipient did not answer [CALL_INITIATED=true] [CALL_ANSWERED=false] [STATE=${lastCallLifecycleState.name}]."
                }
                ActionExecutionResult(
                    true,
                    answerMsg,
                    VerificationStatus.VERIFIED
                )
            } else {
                lastOutgoingCallAnswered = false
                lastCallLifecycleState = PhoneCallLifecycleState.CALL_INITIATED
                val obsSuffix = if (observeCallState) " [CALL_ANSWERED=false]" else ""
                ActionExecutionResult(
                    true,
                    "Initiated outgoing phone call to $resolvedDisplayName ($phoneToCall) via ACTION_CALL [CALL_INITIATED=true] [STATE=${lastCallLifecycleState.name}]. Recipient answer is not claimed without connected call duration evidence.$obsSuffix",
                    VerificationStatus.VERIFIED
                )
            }
        }

        // 8. Real Android Call Initiation via ACTION_CALL or TelecomManager.placeCall
        return try {
            val callUri = Uri.fromParts("tel", phoneToCall, null)
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            var callPlacedViaSystem = false
            if (telecom != null) {
                try {
                    telecom.placeCall(callUri, Bundle())
                    callPlacedViaSystem = true
                } catch (_: Exception) {
                    callPlacedViaSystem = false
                }
            }
            if (!callPlacedViaSystem) {
                val callIntent = Intent(Intent.ACTION_CALL, callUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(callIntent)
            }

            lastOutgoingCallInitiated = true
            val postSnapshot = queryActiveCallState(phoneToCall)
            // Note: Offhook immediately after ACTION_CALL only proves dialing/call setup started, NOT that the recipient answered.
            // Only connected CallLog duration > 0s proves the remote party answered.
            val verifiedAnsweredInCallLog = if (observeCallState && hasCallLogPermission()) {
                val recentOutgoing = loadCallHistoryEntries(limit = 3).first.firstOrNull {
                    it.callType == "OUTGOING" && normalizePhoneDigits(it.phoneNumber).endsWith(normDigits.takeLast(8)) &&
                        (System.currentTimeMillis() - it.timestampMs) < 120_000L
                }
                recentOutgoing != null && recentOutgoing.durationSeconds > 0L
            } else {
                false
            }
            lastOutgoingCallAnswered = verifiedAnsweredInCallLog

            val stateEvidence = if (postSnapshot.isOffhook) {
                "Telephony state transitioned to OFFHOOK (dialing/active)."
            } else {
                "Call request dispatched via Android telephony."
            }
            val answerTag = if (observeCallState) {
                if (verifiedAnsweredInCallLog) " [CALL_ANSWERED=true]" else " [CALL_ANSWERED=false]"
            } else ""

            ActionExecutionResult(
                true,
                "Initiated outgoing phone call to $resolvedDisplayName ($phoneToCall) [CALL_INITIATED=true]. $stateEvidence$answerTag",
                VerificationStatus.VERIFIED
            )
        } catch (e: SecurityException) {
            lastOutgoingCallInitiated = false
            lastOutgoingCallAnswered = false
            ActionExecutionResult(
                false,
                "Permission denied while placing call to $resolvedDisplayName ($phoneToCall): ${e.localizedMessage}",
                VerificationStatus.REQUIRES_PERMISSION
            )
        } catch (e: Exception) {
            lastOutgoingCallInitiated = false
            lastOutgoingCallAnswered = false
            ActionExecutionResult(
                false,
                "Failed to initiate phone call to $resolvedDisplayName ($phoneToCall): ${e.localizedMessage}",
                VerificationStatus.FAILED
            )
        }
    }

    /**
     * Queries active call state safely without returning any fabricated caller identity.
     * Note: On Android 9+, incoming call numbers require default dialer / CallScreeningService / READ_CALL_LOG;
     * when caller number is not exposed by the OS, returns "Caller identity is unavailable." honestly.
     */
    fun queryActiveCallState(observedCallerNumber: String? = null): CallStateSnapshot {
        testOverrideCallState?.let { return it }

        val ctx = context ?: return CallStateSnapshot(
            isRinging = false,
            isOffhook = false,
            callerDisplayName = "Caller identity is unavailable.",
            statusSummary = "Caller identity is unavailable.",
            permissionRequired = true
        )

        if (!hasPhoneStatePermission()) {
            return CallStateSnapshot(
                isRinging = false,
                isOffhook = false,
                callerDisplayName = "Caller identity is unavailable.",
                statusSummary = "Permission required: READ_PHONE_STATE is not granted. Caller identity is unavailable.",
                permissionRequired = true
            )
        }

        return try {
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            @Suppress("DEPRECATION")
            val state = tm?.callState ?: TelephonyManager.CALL_STATE_IDLE
            val isRinging = state == TelephonyManager.CALL_STATE_RINGING
            val isOffhook = state == TelephonyManager.CALL_STATE_OFFHOOK

            val callerIdentity = if (!observedCallerNumber.isNullOrBlank()) {
                val name = resolveContactByPhoneNumber(observedCallerNumber)
                if (!name.isNullOrBlank()) "$name ($observedCallerNumber)" else observedCallerNumber
            } else {
                "Caller identity is unavailable."
            }

            val summary = when {
                isRinging -> "Incoming call detected. $callerIdentity"
                isOffhook -> "Active call in progress. $callerIdentity"
                else -> "Phone is idle. No active incoming or outgoing call."
            }

            CallStateSnapshot(
                isRinging = isRinging,
                isOffhook = isOffhook,
                callerDisplayName = callerIdentity,
                statusSummary = summary,
                permissionRequired = false
            )
        } catch (_: SecurityException) {
            CallStateSnapshot(
                isRinging = false,
                isOffhook = false,
                callerDisplayName = "Caller identity is unavailable.",
                statusSummary = "Permission required: READ_PHONE_STATE denied. Caller identity is unavailable.",
                permissionRequired = true
            )
        } catch (e: Exception) {
            CallStateSnapshot(
                isRinging = false,
                isOffhook = false,
                callerDisplayName = "Caller identity is unavailable.",
                statusSummary = "Caller identity is unavailable (${e.localizedMessage ?: "Telephony error"}).",
                permissionRequired = false
            )
        }
    }

    /**
     * Section 4 — Answers, rejects, or ends an incoming/active phone call using Android TelecomManager
     * (when ANSWER_PHONE_CALLS or Default Dialer role is granted) or AccessibilityService In-Call UI controls.
     */
    @Suppress("DEPRECATION", "MissingPermission")
    fun manageIncomingOrActiveCall(
        operation: String,
        isConfirmed: Boolean = true
    ): ActionExecutionResult {
        val op = operation.trim().uppercase()
        if ((op == "REJECT" || op == "DECLINE" || op == "END" || op == "HANGUP") && !isConfirmed) {
            return ActionExecutionResult(
                false,
                "User confirmation is required before executing call action '$op'.",
                VerificationStatus.REQUIRES_CONFIRMATION
            )
        }

        testOverrideCallState?.let { simulatedState ->
            return when (op) {
                "IDENTIFY", "STATUS" -> {
                    if (simulatedState.permissionRequired) {
                        ActionExecutionResult(false, simulatedState.statusSummary, VerificationStatus.REQUIRES_PERMISSION)
                    } else {
                        ActionExecutionResult(true, simulatedState.statusSummary, VerificationStatus.VERIFIED)
                    }
                }
                "ANSWER" -> {
                    if (testOverrideAnswerCallsPermission == false && testOverrideDefaultDialerRole != true) {
                        return ActionExecutionResult(
                            false,
                            "Permission required: ANSWER_PHONE_CALLS or Default Dialer role (RoleManager.ROLE_DIALER) is required to answer incoming calls.",
                            VerificationStatus.REQUIRES_PERMISSION
                        )
                    }
                    if (!simulatedState.isRinging) {
                        ActionExecutionResult(false, "Cannot answer call: phone is not currently ringing.", VerificationStatus.FAILED)
                    } else {
                        testOverrideCallState = simulatedState.copy(
                            isRinging = false,
                            isOffhook = true,
                            statusSummary = "Active call in progress. ${simulatedState.callerDisplayName}"
                        )
                        ActionExecutionResult(true, "Answered incoming call (${simulatedState.callerDisplayName}).", VerificationStatus.VERIFIED)
                    }
                }
                "REJECT", "DECLINE" -> {
                    if (testOverrideAnswerCallsPermission == false && testOverrideDefaultDialerRole != true) {
                        return ActionExecutionResult(
                            false,
                            "Permission required: ANSWER_PHONE_CALLS or Default Dialer role is required to reject incoming calls.",
                            VerificationStatus.REQUIRES_PERMISSION
                        )
                    }
                    if (!simulatedState.isRinging) {
                        ActionExecutionResult(false, "Cannot reject call: phone is not currently ringing.", VerificationStatus.FAILED)
                    } else {
                        testOverrideCallState = simulatedState.copy(
                            isRinging = false,
                            isOffhook = false,
                            statusSummary = "Phone is idle. Incoming call rejected."
                        )
                        ActionExecutionResult(true, "Call rejected (${simulatedState.callerDisplayName}).", VerificationStatus.VERIFIED)
                    }
                }
                "END", "HANGUP" -> {
                    if (testOverrideAnswerCallsPermission == false && testOverrideDefaultDialerRole != true) {
                        return ActionExecutionResult(
                            false,
                            "Permission required: ANSWER_PHONE_CALLS or Default Dialer role is required to end active calls.",
                            VerificationStatus.REQUIRES_PERMISSION
                        )
                    }
                    if (!simulatedState.isRinging && !simulatedState.isOffhook) {
                        ActionExecutionResult(false, "No ringing or active call to end.", VerificationStatus.FAILED)
                    } else {
                        testOverrideCallState = simulatedState.copy(
                            isRinging = false,
                            isOffhook = false,
                            statusSummary = "Phone is idle. Call ended."
                        )
                        ActionExecutionResult(true, "Call ended (${simulatedState.callerDisplayName}).", VerificationStatus.VERIFIED)
                    }
                }
                else -> ActionExecutionResult(true, simulatedState.statusSummary, VerificationStatus.VERIFIED)
            }
        }

        val ctx = context ?: return ActionExecutionResult(
            false,
            "Android Context is unavailable for TelecomManager call control.",
            VerificationStatus.REQUIRES_PERMISSION
        )

        if (op == "IDENTIFY" || op == "STATUS") {
            val state = queryActiveCallState()
            return if (state.permissionRequired) {
                ActionExecutionResult(false, state.statusSummary, VerificationStatus.REQUIRES_PERMISSION)
            } else {
                ActionExecutionResult(true, state.statusSummary, VerificationStatus.VERIFIED)
            }
        }

        // Verify current call state before attempting ANSWER / REJECT / END
        if (hasPhoneStatePermission()) {
            val current = queryActiveCallState()
            if (op == "ANSWER" && !current.isRinging) {
                return ActionExecutionResult(
                    false,
                    "Cannot answer call: phone is not currently ringing.",
                    VerificationStatus.FAILED
                )
            }
            if ((op == "REJECT" || op == "DECLINE") && !current.isRinging) {
                return ActionExecutionResult(
                    false,
                    "Cannot reject call: phone is not currently ringing.",
                    VerificationStatus.FAILED
                )
            }
            if ((op == "END" || op == "HANGUP") && !current.isRinging && !current.isOffhook) {
                return ActionExecutionResult(
                    false,
                    "Cannot end call: there is no active or ringing phone call.",
                    VerificationStatus.FAILED
                )
            }
        }

        val telecom = ctx.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        if (telecom != null && (hasAnswerPhoneCallsPermission() || isDefaultDialerRoleHeld())) {
            try {
                when (op) {
                    "ANSWER" -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            telecom.acceptRingingCall()
                            return ActionExecutionResult(
                                true,
                                "Answered incoming ringing call via Android TelecomManager.",
                                VerificationStatus.VERIFIED
                            )
                        }
                    }
                    "REJECT", "DECLINE", "END", "HANGUP" -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            val ended = telecom.endCall()
                            if (ended) {
                                val verb = if (op == "END" || op == "HANGUP") "Ended active call" else "Rejected incoming call"
                                return ActionExecutionResult(
                                    true,
                                    "$verb via Android TelecomManager.",
                                    VerificationStatus.VERIFIED
                                )
                            }
                        }
                    }
                }
            } catch (_: SecurityException) {
                // Fall through to Accessibility In-Call UI fallback
            } catch (_: Exception) {
                // Fall through to Accessibility In-Call UI fallback
            }
        }

        if (NayroAccessibilityService.isConnected()) {
            val buttonCandidates = when (op) {
                "ANSWER" -> listOf("Answer", "Accept")
                "REJECT", "DECLINE" -> listOf("Decline", "Reject", "Dismiss")
                else -> listOf("End call", "Hang up", "Decline", "Reject")
            }
            for (label in buttonCandidates) {
                if (AccessibilityActionController.performClick(targetText = label)) {
                    return ActionExecutionResult(
                        true,
                        "Executed '$op' call action by clicking '$label' in the active In-Call screen via Accessibility Service.",
                        VerificationStatus.VERIFIED
                    )
                }
            }
        }

        return ActionExecutionResult(
            false,
            "Restricted by Android: Call control ('$op') requires ANSWER_PHONE_CALLS permission, Default Dialer role (RoleManager.ROLE_DIALER), or an active In-Call UI accessible via NAYRO Accessibility Service.",
            VerificationStatus.REQUIRES_PERMISSION
        )
    }

    /**
     * Section 3C — Reads permitted call history (missed, incoming, outgoing, or filtered by contact/number).
     */
    fun queryCallHistory(
        filterType: String = "all",
        contactOrNumberQuery: String? = null,
        limit: Int = 10
    ): ActionExecutionResult {
        val entries = loadCallHistoryEntries(limit = limit.coerceIn(1, 50))
        if (entries.first.isEmpty() && entries.second == "PERMISSION_REQUIRED") {
            return ActionExecutionResult(
                false,
                "Permission required: READ_CALL_LOG (or Notification Access for missed call alerts) is not granted.",
                VerificationStatus.REQUIRES_PERMISSION
            )
        }

        val cleanFilter = filterType.trim().lowercase()
        val cleanQuery = contactOrNumberQuery?.trim()?.takeIf { it.isNotEmpty() }

        val filtered = entries.first.filter { item ->
            val matchesType = when (cleanFilter) {
                "missed" -> item.callType.equals("MISSED", ignoreCase = true)
                "incoming" -> item.callType.equals("INCOMING", ignoreCase = true)
                "outgoing" -> item.callType.equals("OUTGOING", ignoreCase = true)
                else -> true
            }
            val matchesQuery = if (cleanQuery != null) {
                item.callerNameOrNumber.contains(cleanQuery, ignoreCase = true) ||
                    item.phoneNumber.contains(cleanQuery, ignoreCase = true)
            } else {
                true
            }
            matchesType && matchesQuery
        }.take(limit)

        if (filtered.isEmpty()) {
            return ActionExecutionResult(
                true,
                "No call history entries matched filter='$cleanFilter'${if (cleanQuery != null) ", query='$cleanQuery'" else ""} (${entries.second}).",
                VerificationStatus.VERIFIED
            )
        }

        val summaryLines = filtered.mapIndexed { idx, call ->
            "${idx + 1}. [${call.callType}] ${call.callerNameOrNumber} (${call.phoneNumber}) — ${call.durationSeconds}s"
        }
        return ActionExecutionResult(
            true,
            "Call History (${entries.second}, ${filtered.size} record(s)):\n" + summaryLines.joinToString("\n"),
            VerificationStatus.VERIFIED
        )
    }

    private fun loadCallHistoryEntries(limit: Int): Pair<List<CallHistoryEntry>, String> {
        testOverrideCallHistory?.let { return it.take(limit) to "Simulated/Test Call History" }

        val ctx = context
        if (ctx != null && hasCallLogPermission()) {
            try {
                val list = mutableListOf<CallHistoryEntry>()
                val projection = arrayOf(
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.CACHED_NAME,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DATE,
                    CallLog.Calls.DURATION
                )
                ctx.contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    projection,
                    null,
                    null,
                    "${CallLog.Calls.DATE} DESC"
                )?.use { cursor ->
                    val numIdx = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                    val nameIdx = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)
                    val typeIdx = cursor.getColumnIndex(CallLog.Calls.TYPE)
                    val dateIdx = cursor.getColumnIndex(CallLog.Calls.DATE)
                    val durIdx = cursor.getColumnIndex(CallLog.Calls.DURATION)
                    while (cursor.moveToNext() && list.size < limit) {
                        val number = if (numIdx >= 0) cursor.getString(numIdx).orEmpty() else "Unknown"
                        val cachedName = if (nameIdx >= 0) cursor.getString(nameIdx) else null
                        val typeCode = if (typeIdx >= 0) cursor.getInt(typeIdx) else CallLog.Calls.INCOMING_TYPE
                        val dateMs = if (dateIdx >= 0) cursor.getLong(dateIdx) else System.currentTimeMillis()
                        val durationSec = if (durIdx >= 0) cursor.getLong(durIdx) else 0L
                        val typeStr = when (typeCode) {
                            CallLog.Calls.MISSED_TYPE -> "MISSED"
                            CallLog.Calls.OUTGOING_TYPE -> "OUTGOING"
                            CallLog.Calls.REJECTED_TYPE -> "REJECTED"
                            else -> "INCOMING"
                        }
                        val display = cachedName?.takeIf { it.isNotBlank() }
                            ?: resolveContactByPhoneNumber(number)
                            ?: number
                        list.add(
                            CallHistoryEntry(
                                callerNameOrNumber = display,
                                phoneNumber = number,
                                callType = typeStr,
                                timestampMs = dateMs,
                                durationSeconds = durationSec
                            )
                        )
                    }
                }
                return list to "Android CallLog Provider"
            } catch (_: SecurityException) {
                // Fall through to notification buffer
            } catch (_: Exception) {
                // Fall through to notification buffer
            }
        }

        val callNotifs = NotificationIntelligenceRepository.getFilteredNotifications()
            .filter { notif ->
                val text = "${notif.title} ${notif.text}".lowercase()
                text.contains("missed call") || text.contains("incoming call") || notif.packageName.contains("dialer") || notif.packageName.contains("telecom")
            }
            .take(limit)

        if (callNotifs.isNotEmpty()) {
            val records = callNotifs.map { notif ->
                CallHistoryEntry(
                    callerNameOrNumber = notif.title.ifBlank { "Unknown Caller" },
                    phoneNumber = notif.title.ifBlank { "Unknown" },
                    callType = if ("${notif.title} ${notif.text}".contains("missed", ignoreCase = true)) "MISSED" else "INCOMING",
                    timestampMs = notif.postTime,
                    durationSeconds = 0L
                )
            }
            return records to "Authorized Notification Listener Call Buffer"
        }

        return emptyList<CallHistoryEntry>() to "PERMISSION_REQUIRED"
    }

    /**
     * Checks whether [phoneNumber] already exists in the user's contacts directory.
     */
    fun isPhoneNumberInContacts(phoneNumber: String): Boolean {
        return resolveContactByPhoneNumber(phoneNumber) != null
    }

    /**
     * Resolves a phone number to a contact display name if READ_CONTACTS is granted or in-memory contacts exist.
     */
    fun resolveContactByPhoneNumber(phoneNumber: String): String? {
        val normalizedTarget = phoneNumber.filter { it.isDigit() || it == '+' }
        if (normalizedTarget.isEmpty()) return null

        val allTestContacts = (contactDirectoryProvider?.invoke().orEmpty() + testSavedContacts)
        for (item in allTestContacts) {
            val normItem = item.phoneNumber.filter { it.isDigit() || it == '+' }
            if (normItem.isNotEmpty() &&
                (normItem.endsWith(normalizedTarget.takeLast(10)) || normalizedTarget.endsWith(normItem.takeLast(10)))
            ) {
                return item.displayName
            }
        }

        val ctx = context ?: return null
        if (phoneNumber.isBlank() || !hasContactsPermission()) return null

        return try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )

            ctx.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext()) {
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx) else null
                    val num = if (numIdx >= 0) cursor.getString(numIdx) else null
                    val normalizedNum = num?.filter { it.isDigit() || it == '+' }.orEmpty()
                    if (!name.isNullOrBlank() && normalizedNum.isNotEmpty() &&
                        (normalizedNum.endsWith(normalizedTarget.takeLast(10)) || normalizedTarget.endsWith(normalizedNum.takeLast(10)))
                    ) {
                        return name
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Section 6 — Saves or updates a contact after user confirmation.
     */
    fun saveOrUpdateContact(
        displayName: String,
        phoneNumber: String,
        isConfirmed: Boolean,
        onlyIfNewNumber: Boolean = false
    ): ActionExecutionResult {
        val cleanName = displayName.trim().ifBlank { "New Contact ($phoneNumber)" }
        val cleanPhone = phoneNumber.trim()
        if (cleanPhone.isBlank() || !isValidPhoneNumber(cleanPhone)) {
            return ActionExecutionResult(
                false,
                "Cannot save contact '$cleanName': phone number '$cleanPhone' is missing or invalid.",
                VerificationStatus.FAILED
            )
        }

        if (onlyIfNewNumber && isPhoneNumberInContacts(cleanPhone)) {
            val existingName = resolveContactByPhoneNumber(cleanPhone) ?: cleanName
            return ActionExecutionResult(
                true,
                "Number $cleanPhone is already saved in contacts as '$existingName'; skipped duplicate contact creation.",
                VerificationStatus.VERIFIED
            )
        }

        if (!isConfirmed) {
            return ActionExecutionResult(
                false,
                "Confirmation required before saving new contact '$cleanName' ($cleanPhone).",
                VerificationStatus.REQUIRES_CONFIRMATION
            )
        }

        // If running in headless unit tests or custom contact directory mode
        if (context == null || contactDirectoryProvider != null) {
            testSavedContacts.removeAll { it.phoneNumber == cleanPhone }
            testSavedContacts.add(ResolvedContactItem(cleanName, cleanPhone))
            return ActionExecutionResult(
                true,
                "Saved contact '$cleanName' ($cleanPhone) to contacts directory.",
                VerificationStatus.VERIFIED
            )
        }

        return try {
            val insertIntent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
                type = ContactsContract.RawContacts.CONTENT_TYPE
                putExtra(ContactsContract.Intents.Insert.NAME, cleanName)
                putExtra(ContactsContract.Intents.Insert.PHONE, cleanPhone)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(insertIntent)
            testSavedContacts.add(ResolvedContactItem(cleanName, cleanPhone))
            ActionExecutionResult(
                true,
                "Opened Android Contacts editor to save '$cleanName' ($cleanPhone).",
                VerificationStatus.VERIFIED
            )
        } catch (e: Exception) {
            ActionExecutionResult(
                false,
                "Failed to save contact '$cleanName' ($cleanPhone): ${e.localizedMessage}",
                VerificationStatus.FAILED
            )
        }
    }

    /**
     * Resolves a contact name query against ContactsContract, detecting missing permission,
     * zero matches, single match, or ambiguous multiple matches.
     */
    fun resolveContactsByName(nameQuery: String): ContactResolutionResult {
        val trimmed = nameQuery.trim()
        if (trimmed.isEmpty()) {
            return ContactResolutionResult.NotFound("")
        }
        if (testOverrideContactsPermission == false) {
            return ContactResolutionResult.PermissionRequired()
        }
        val combinedTestContacts = contactDirectoryProvider?.invoke().orEmpty() + testSavedContacts
        if (contactDirectoryProvider != null || testSavedContacts.isNotEmpty()) {
            val matches = combinedTestContacts.filter {
                it.displayName.contains(trimmed, ignoreCase = true)
            }
            if (matches.isNotEmpty() || contactDirectoryProvider != null) {
                return when {
                    matches.isEmpty() -> ContactResolutionResult.NotFound(trimmed)
                    matches.size == 1 -> ContactResolutionResult.SingleMatch(matches.first())
                    else -> ContactResolutionResult.Ambiguous(trimmed, matches)
                }
            }
        }
        val ctx = context ?: return ContactResolutionResult.PermissionRequired()
        if (!hasContactsPermission()) {
            return ContactResolutionResult.PermissionRequired()
        }

        val matches = mutableListOf<ResolvedContactItem>()
        return try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%$trimmed%")

            ctx.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val seen = mutableSetOf<String>()
                while (cursor.moveToNext()) {
                    val displayName = (if (nameIdx >= 0) cursor.getString(nameIdx) else null)?.trim().orEmpty()
                    val number = (if (numIdx >= 0) cursor.getString(numIdx) else null)?.trim().orEmpty()
                    if (displayName.isNotEmpty() && number.isNotEmpty()) {
                        val key = "${displayName.lowercase()}|${number.filter { it.isDigit() }}"
                        if (seen.add(key)) {
                            matches.add(ResolvedContactItem(displayName, number))
                        }
                    }
                }
            }

            when {
                matches.isEmpty() -> ContactResolutionResult.NotFound(trimmed)
                matches.size == 1 -> ContactResolutionResult.SingleMatch(matches.first())
                else -> {
                    val distinctNames = matches.map { it.displayName.lowercase() }.distinct()
                    if (distinctNames.size == 1 && matches.map { it.phoneNumber.filter { c -> c.isDigit() }.takeLast(10) }.distinct().size == 1) {
                        ContactResolutionResult.SingleMatch(matches.first())
                    } else {
                        ContactResolutionResult.Ambiguous(trimmed, matches)
                    }
                }
            }
        } catch (_: SecurityException) {
            ContactResolutionResult.PermissionRequired()
        } catch (_: Exception) {
            ContactResolutionResult.NotFound(trimmed)
        }
    }

    /**
     * Resolves a single contact display name and phone summary string, or null if not found.
     */
    fun resolveContactDisplayName(nameQuery: String): String? {
        return when (val res = resolveContactsByName(nameQuery)) {
            is ContactResolutionResult.SingleMatch -> "${res.contact.displayName} (${res.contact.phoneNumber})"
            else -> null
        }
    }

    /**
     * Section 5 — Incoming Call Caller Identification & Voice Announcement Builder:
     * Resolves [callerNumber] against Contacts and produces Babu's natural Hinglish announcement:
     * - Known contact: `"Babu: <ContactName> ka phone aa raha hai."`
     * - Unknown / unsaved / hidden caller: `"Babu: Ek unknown number se call aa rahi hai."`
     */
    fun buildIncomingCallerAnnouncement(callerNumber: String? = null): String {
        val numberCandidate = callerNumber?.trim()?.takeIf { it.isNotEmpty() }
        val resolvedName = if (numberCandidate != null) {
            resolveContactByPhoneNumber(numberCandidate)
        } else {
            val activeLabel = testOverrideCallState?.callerDisplayName?.trim().orEmpty()
            activeLabel.substringBefore("(").trim().takeIf {
                it.isNotEmpty() &&
                    !it.contains("unavailable", ignoreCase = true) &&
                    !it.contains("unknown", ignoreCase = true) &&
                    it.any { ch -> ch.isLetter() }
            }
        }

        return if (!resolvedName.isNullOrBlank()) {
            "Babu: $resolvedName ka phone aa raha hai."
        } else {
            "Babu: Ek unknown number se call aa rahi hai."
        }
    }

    /**
     * Section 5 — Announces an incoming call via Babu's voice/TTS callback when permitted.
     */
    fun announceIncomingCallIfPermitted(
        callerNumber: String?,
        voiceSpeaker: ((String) -> Unit)? = null
    ): String {
        val announcement = buildIncomingCallerAnnouncement(callerNumber)
        voiceSpeaker?.invoke(announcement)
        return announcement
    }

    /**
     * Section 7 — Deletes a contact by name or phone number after explicit user confirmation (`isConfirmed == true`).
     */
    fun deleteContactByNameOrNumber(
        query: String,
        isConfirmed: Boolean
    ): ActionExecutionResult {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) {
            return ActionExecutionResult(
                false,
                "Cannot delete contact: contact name or phone number query is empty.",
                VerificationStatus.FAILED
            )
        }
        if (!isConfirmed) {
            return ActionExecutionResult(
                false,
                "Confirmation required before deleting contact '$cleanQuery'.",
                VerificationStatus.REQUIRES_CONFIRMATION
            )
        }

        val normQueryDigits = normalizePhoneDigits(cleanQuery)
        val removedFromTest = testSavedContacts.removeAll { item ->
            item.displayName.equals(cleanQuery, ignoreCase = true) ||
                item.displayName.contains(cleanQuery, ignoreCase = true) ||
                (normQueryDigits.isNotEmpty() && normalizePhoneDigits(item.phoneNumber).endsWith(normQueryDigits.takeLast(10)))
        }

        if (context == null || contactDirectoryProvider != null) {
            return if (removedFromTest) {
                ActionExecutionResult(
                    true,
                    "Deleted contact matching '$cleanQuery' from contacts directory.",
                    VerificationStatus.VERIFIED
                )
            } else {
                ActionExecutionResult(
                    false,
                    "Contact '$cleanQuery' was not found in contacts directory to delete.",
                    VerificationStatus.FAILED
                )
            }
        }

        if (!hasContactsPermission()) {
            return ActionExecutionResult(
                false,
                "Permission required: READ_CONTACTS / WRITE_CONTACTS is required to delete contact '$cleanQuery'.",
                VerificationStatus.REQUIRES_PERMISSION
            )
        }

        return try {
            val deletedRows = context.contentResolver.delete(
                ContactsContract.RawContacts.CONTENT_URI,
                "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} = ?",
                arrayOf(cleanQuery)
            )
            if (deletedRows > 0 || removedFromTest) {
                ActionExecutionResult(
                    true,
                    "Deleted contact '$cleanQuery' ($deletedRows record(s) removed).",
                    VerificationStatus.VERIFIED
                )
            } else {
                ActionExecutionResult(
                    false,
                    "No contact matching '$cleanQuery' was found to delete.",
                    VerificationStatus.FAILED
                )
            }
        } catch (e: SecurityException) {
            ActionExecutionResult(
                false,
                "Permission required: WRITE_CONTACTS is needed to delete contact '$cleanQuery' (${e.localizedMessage}).",
                VerificationStatus.REQUIRES_PERMISSION
            )
        } catch (e: Exception) {
            ActionExecutionResult(
                false,
                "Failed to delete contact '$cleanQuery': ${e.localizedMessage}",
                VerificationStatus.FAILED
            )
        }
    }

    /**
     * Section 7 — Detects duplicate contacts (same normalized 10-digit phone number or identical display name).
     */
    fun findDuplicateContacts(): List<String> {
        val allContacts = (contactDirectoryProvider?.invoke().orEmpty() + testSavedContacts)
        val duplicates = mutableListOf<String>()

        val byPhone = allContacts.groupBy { normalizePhoneDigits(it.phoneNumber).takeLast(10) }
            .filter { (key, list) -> key.length >= 7 && list.size > 1 }
        for ((digits, items) in byPhone) {
            val names = items.joinToString(", ") { "${it.displayName} (${it.phoneNumber})" }
            duplicates.add("Duplicate number (*$digits): $names")
        }

        val byName = allContacts.groupBy { it.displayName.trim().lowercase() }
            .filter { (name, list) -> name.isNotEmpty() && list.map { normalizePhoneDigits(it.phoneNumber) }.distinct().size > 1 }
        for ((_, items) in byName) {
            val entries = items.joinToString(", ") { "${it.displayName} (${it.phoneNumber})" }
            duplicates.add("Duplicate name '${items.first().displayName}': $entries")
        }

        return duplicates
    }
}
