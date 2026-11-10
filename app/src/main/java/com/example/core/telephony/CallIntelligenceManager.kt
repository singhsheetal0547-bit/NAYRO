package com.example.core.telephony

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

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

sealed class ContactResolutionResult {
    data class SingleMatch(val contact: ResolvedContactItem) : ContactResolutionResult()
    data class Ambiguous(val query: String, val candidates: List<ResolvedContactItem>) : ContactResolutionResult()
    data class NotFound(val query: String) : ContactResolutionResult()
    data class PermissionRequired(val permission: String = Manifest.permission.READ_CONTACTS) : ContactResolutionResult()
}

/**
 * Call & Contact Intelligence Manager (BUG 03 & BUG 04 Remediation).
 * Never returns fake caller names ("Rahul") or fake incoming call strings.
 * Performs real Android TelephonyManager state checks and ContactsContract resolution.
 */
class CallIntelligenceManager(private val context: Context?) {

    fun hasPhoneStatePermission(): Boolean {
        val ctx = context ?: return false
        return ContextCompat.checkSelfPermission(
            ctx,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasContactsPermission(): Boolean {
        val ctx = context ?: return false
        return ContextCompat.checkSelfPermission(
            ctx,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Queries active call state safely without returning any fabricated caller identity.
     * Note: On Android 9+, incoming call numbers require default dialer / CallScreeningService role;
     * when caller number is not exposed by the OS, returns "Caller identity is unavailable." honestly.
     */
    fun queryActiveCallState(observedCallerNumber: String? = null): CallStateSnapshot {
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
     * Resolves a phone number to a contact display name if READ_CONTACTS is granted.
     */
    fun resolveContactByPhoneNumber(phoneNumber: String): String? {
        val ctx = context ?: return null
        if (phoneNumber.isBlank() || !hasContactsPermission()) return null

        return try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            val normalizedTarget = phoneNumber.filter { it.isDigit() || it == '+' }
            if (normalizedTarget.isEmpty()) return null

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
     * Resolves a contact name query against ContactsContract, detecting missing permission,
     * zero matches, single match, or ambiguous multiple matches.
     */
    fun resolveContactsByName(nameQuery: String): ContactResolutionResult {
        val trimmed = nameQuery.trim()
        if (trimmed.isEmpty()) {
            return ContactResolutionResult.NotFound("")
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
