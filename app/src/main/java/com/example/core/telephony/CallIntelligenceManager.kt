package com.example.core.telephony

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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
 * Sections 3 & 6 — Complete Phone, Call History & Contact Intelligence Manager for NAYRO (Babu).
 *
 * Capabilities:
 * - Queries active call state via `TelephonyManager` and `TelecomManager` without fabricating caller identities.
 * - Resolves contacts by name or phone number via `ContactsContract`, detecting single, ambiguous, or missing matches.
 * - Manages incoming/active calls (`ANSWER`, `REJECT`, `END`) through `TelecomManager` (when `ANSWER_PHONE_CALLS` or
 *   Default Dialer role is granted) or `NayroAccessibilityService` In-Call UI actions, honestly reporting OS limits.
 * - Reads permitted call history (`CallLog.Calls` when `READ_CALL_LOG` is granted, or missed/incoming call notifications
 *   from `NotificationIntelligenceRepository`).
 * - Creates, updates, or deletes contacts with explicit user confirmation.
 */
class CallIntelligenceManager(
    private val context: Context?,
    private val contactDirectoryProvider: (() -> List<ResolvedContactItem>)? = null
) {

    companion object {
        @Volatile
        var lastOutgoingCallAnswered: Boolean = false

        @Volatile
        var lastDialedRecipient: String? = null
    }

    var testOverrideCallState: CallStateSnapshot? = null
    var testOverrideLastOutgoingCallAnswered: Boolean? = null
    var testOverrideCallHistory: List<CallHistoryEntry>? = null
    val testSavedContacts: MutableList<ResolvedContactItem> = mutableListOf()

    fun hasPhoneStatePermission(): Boolean {
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

    /**
     * Queries active call state safely without returning any fabricated caller identity.
     * Note: On Android 9+, incoming call numbers require default dialer / CallScreeningService role;
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
                resolveContactByPhoneNumber(observedCallerNumber) ?: observedCallerNumber
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
        } catch (e: SecurityException) {
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
     * Section 3B — Answers, rejects, or ends an incoming/active phone call using Android TelecomManager
     * (when ANSWER_PHONE_CALLS or Default Dialer role is granted) or AccessibilityService In-Call UI controls.
     */
    @Suppress("DEPRECATION", "MissingPermission")
    fun manageIncomingOrActiveCall(
        operation: String,
        isConfirmed: Boolean = true
    ): ActionExecutionResult {
        val op = operation.trim().uppercase()
        if ((op == "REJECT" || op == "END") && !isConfirmed) {
            return ActionExecutionResult(
                false,
                "User confirmation is required before executing call action '$op'.",
                VerificationStatus.REQUIRES_CONFIRMATION
            )
        }

        testOverrideCallState?.let { simulatedState ->
            return when (op) {
                "ANSWER" -> {
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
                "REJECT", "DECLINE", "END", "HANGUP" -> {
                    if (!simulatedState.isRinging && !simulatedState.isOffhook) {
                        ActionExecutionResult(false, "No ringing or active call to end/reject.", VerificationStatus.FAILED)
                    } else {
                        testOverrideCallState = simulatedState.copy(
                            isRinging = false,
                            isOffhook = false,
                            statusSummary = "Phone is idle. Call ended."
                        )
                        ActionExecutionResult(true, "Call ${if (op == "END" || op == "HANGUP") "ended" else "rejected"} (${simulatedState.callerDisplayName}).", VerificationStatus.VERIFIED)
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

        val telecom = ctx.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        if (telecom != null && hasAnswerPhoneCallsPermission()) {
            try {
                when (op) {
                    "ANSWER" -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            telecom.acceptRingingCall()
                            return ActionExecutionResult(
                                true,
                                "Accepted incoming ringing call via Android TelecomManager.",
                                VerificationStatus.VERIFIED
                            )
                        }
                    }
                    "REJECT", "DECLINE", "END", "HANGUP" -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            val ended = telecom.endCall()
                            if (ended) {
                                return ActionExecutionResult(
                                    true,
                                    "Ended/rejected call via Android TelecomManager.",
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
                else -> listOf("Decline", "Reject", "End call", "Hang up", "Dismiss")
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
        if (cleanPhone.isBlank()) {
            return ActionExecutionResult(
                false,
                "Cannot save contact '$cleanName': phone number is missing.",
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
}
