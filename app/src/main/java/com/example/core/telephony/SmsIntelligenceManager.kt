package com.example.core.telephony

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.example.core.notification.NotificationIntelligenceRepository

data class SmsMessageRecord(
    val id: String,
    val address: String,
    val resolvedContactName: String?,
    val body: String,
    val timestampMs: Long,
    val isUnread: Boolean,
    val isIncoming: Boolean = true,
    val extractedOtp: String? = null,
    val isBankAlert: Boolean = false,
    val extractedAmount: String? = null,
    val extractedReference: String? = null
)

data class SmsIntelligenceSummary(
    val messages: List<SmsMessageRecord>,
    val sourceDescription: String,
    val latestSenderAddress: String?,
    val latestSenderContactName: String?,
    val isLatestSenderNewContact: Boolean,
    val formattedReport: String,
    val permissionRequired: Boolean = false
)

/**
 * Section 4 — Complete SMS Agent & Conversation Intelligence Manager.
 *
 * Capabilities:
 * - Reads permitted SMS messages from `Telephony.Sms.CONTENT_URI` when `READ_SMS` is granted.
 * - Falls back transparently to SMS/messaging notifications captured by `NotificationIntelligenceRepository`
 *   when `READ_SMS` is not granted (respecting Android / Google Play role & permission policies).
 * - Extracts OTP verification codes (4–8 digits), bank transaction amounts (INR/Rs/₹/$), and reference numbers.
 * - Identifies whether the sender number is already saved in Android Contacts (`CallIntelligenceManager`)
 *   to power multi-step workflows like Scenario B: "Read my latest SMS and save the sender if the number is new."
 */
class SmsIntelligenceManager(
    private val context: Context?,
    private val callIntelligenceManager: CallIntelligenceManager
) {

    companion object {
        private val OTP_KEYWORD_REGEX = Regex("(?i)\\b(otp|verification code|security code|login code|one time password|passcode)\\b")
        private val OTP_DIGITS_REGEX = Regex("\\b(\\d{4,8})\\b")
        private val BANK_KEYWORD_REGEX = Regex("(?i)\\b(debited|credited|a/c|acct|bank|upi|imps|neft|txn|transaction|avl bal|available balance)\\b")
        private val AMOUNT_REGEX = Regex("(?i)(?:rs\\.?|inr|₹|\\$)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)")
        private val REF_NUMBER_REGEX = Regex("(?i)(?:ref(?:\\s*no)?|utr|txn(?:\\s*id)?)[:\\s#-]*([A-Z0-9]{6,18})")

        @Volatile
        var lastInspectedSms: SmsMessageRecord? = null

        @Volatile
        var lastInspectedSenderIsNew: Boolean = false

        fun extractOtpFromBody(body: String): String? {
            if (!OTP_KEYWORD_REGEX.containsMatchIn(body)) return null
            return OTP_DIGITS_REGEX.find(body)?.groupValues?.getOrNull(1)
        }

        fun isBankMessage(body: String): Boolean {
            return BANK_KEYWORD_REGEX.containsMatchIn(body)
        }

        fun extractAmountFromBody(body: String): String? {
            val match = AMOUNT_REGEX.find(body) ?: return null
            return match.value.trim()
        }

        fun extractReferenceFromBody(body: String): String? {
            val match = REF_NUMBER_REGEX.find(body) ?: return null
            return match.groupValues.getOrNull(1)?.trim()
        }

        fun enrichRecord(
            id: String,
            address: String,
            resolvedContactName: String?,
            body: String,
            timestampMs: Long,
            isUnread: Boolean,
            isIncoming: Boolean = true
        ): SmsMessageRecord {
            return SmsMessageRecord(
                id = id,
                address = address,
                resolvedContactName = resolvedContactName,
                body = body,
                timestampMs = timestampMs,
                isUnread = isUnread,
                isIncoming = isIncoming,
                extractedOtp = extractOtpFromBody(body),
                isBankAlert = isBankMessage(body),
                extractedAmount = extractAmountFromBody(body),
                extractedReference = extractReferenceFromBody(body)
            )
        }
    }

    var testOverrideSmsInbox: List<SmsMessageRecord>? = null

    fun hasReadSmsPermission(): Boolean {
        val ctx = context ?: return false
        return try {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Reads and analyzes recent SMS messages matching [filter] ("latest", "unread", "otp", "bank", or contact/number query).
     */
    fun readAndAnalyzeSms(
        filter: String = "latest",
        query: String? = null,
        limit: Int = 10
    ): SmsIntelligenceSummary {
        val rawRecords = loadRawSmsRecords(limit = limit.coerceIn(1, 50))
        if (rawRecords.first.isEmpty() && rawRecords.second == "PERMISSION_REQUIRED") {
            return SmsIntelligenceSummary(
                messages = emptyList(),
                sourceDescription = "Permission Required",
                latestSenderAddress = null,
                latestSenderContactName = null,
                isLatestSenderNewContact = false,
                formattedReport = "Permission required: READ_SMS (or Notification Access for incoming SMS alerts) is not granted.",
                permissionRequired = true
            )
        }

        val cleanFilter = filter.trim().lowercase()
        val cleanQuery = query?.trim()?.takeIf { it.isNotEmpty() }

        val filtered = rawRecords.first.filter { msg ->
            val matchesFilter = when (cleanFilter) {
                "otp" -> msg.extractedOtp != null || msg.body.contains("otp", ignoreCase = true)
                "bank" -> msg.isBankAlert
                "unread" -> msg.isUnread
                else -> true
            }
            val matchesQuery = if (cleanQuery != null) {
                msg.address.contains(cleanQuery, ignoreCase = true) ||
                    (msg.resolvedContactName?.contains(cleanQuery, ignoreCase = true) == true) ||
                    msg.body.contains(cleanQuery, ignoreCase = true)
            } else {
                true
            }
            matchesFilter && matchesQuery
        }.let { list ->
            if (cleanFilter == "latest") list.take(1) else list.take(limit)
        }

        if (filtered.isEmpty()) {
            return SmsIntelligenceSummary(
                messages = emptyList(),
                sourceDescription = rawRecords.second,
                latestSenderAddress = null,
                latestSenderContactName = null,
                isLatestSenderNewContact = false,
                formattedReport = "No matching SMS messages found (filter='$cleanFilter'${if (cleanQuery != null) ", query='$cleanQuery'" else ""})."
            )
        }

        val latest = filtered.first()
        val isNewContact = latest.resolvedContactName.isNullOrBlank() &&
            !callIntelligenceManager.isPhoneNumberInContacts(latest.address)
        lastInspectedSms = latest
        lastInspectedSenderIsNew = isNewContact

        val reportLines = filtered.mapIndexed { idx, msg ->
            val senderLabel = if (!msg.resolvedContactName.isNullOrBlank()) {
                "${msg.resolvedContactName} (${msg.address})"
            } else {
                "${msg.address} [New/Unsaved Number]"
            }
            val tags = buildList {
                if (msg.extractedOtp != null) add("OTP: ${msg.extractedOtp}")
                if (msg.isBankAlert) add("Bank Alert")
                if (msg.extractedAmount != null) add("Amount: ${msg.extractedAmount}")
                if (msg.extractedReference != null) add("Ref: ${msg.extractedReference}")
            }.joinToString(", ")
            val tagSuffix = if (tags.isNotEmpty()) " [$tags]" else ""
            "${idx + 1}. From $senderLabel: \"${msg.body}\"$tagSuffix"
        }

        val header = "SMS Intelligence (${rawRecords.second}, ${filtered.size} message(s)):"
        return SmsIntelligenceSummary(
            messages = filtered,
            sourceDescription = rawRecords.second,
            latestSenderAddress = latest.address,
            latestSenderContactName = latest.resolvedContactName,
            isLatestSenderNewContact = isNewContact,
            formattedReport = "$header\n" + reportLines.joinToString("\n")
        )
    }

    private fun loadRawSmsRecords(limit: Int): Pair<List<SmsMessageRecord>, String> {
        testOverrideSmsInbox?.let { mockList ->
            val enriched = mockList.map { item ->
                val resolvedName = item.resolvedContactName
                    ?: callIntelligenceManager.resolveContactByPhoneNumber(item.address)
                enrichRecord(
                    id = item.id,
                    address = item.address,
                    resolvedContactName = resolvedName,
                    body = item.body,
                    timestampMs = item.timestampMs,
                    isUnread = item.isUnread,
                    isIncoming = item.isIncoming
                )
            }
            return enriched to "Simulated/Test SMS Inbox"
        }

        val ctx = context
        if (ctx != null && hasReadSmsPermission()) {
            try {
                val list = mutableListOf<SmsMessageRecord>()
                val uri: Uri = Telephony.Sms.Inbox.CONTENT_URI
                val projection = arrayOf(
                    Telephony.Sms._ID,
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.DATE,
                    Telephony.Sms.READ
                )
                ctx.contentResolver.query(
                    uri,
                    projection,
                    null,
                    null,
                    "${Telephony.Sms.DATE} DESC"
                )?.use { cursor ->
                    val idIdx = cursor.getColumnIndex(Telephony.Sms._ID)
                    val addrIdx = cursor.getColumnIndex(Telephony.Sms.ADDRESS)
                    val bodyIdx = cursor.getColumnIndex(Telephony.Sms.BODY)
                    val dateIdx = cursor.getColumnIndex(Telephony.Sms.DATE)
                    val readIdx = cursor.getColumnIndex(Telephony.Sms.READ)
                    while (cursor.moveToNext() && list.size < limit) {
                        val id = if (idIdx >= 0) cursor.getString(idIdx).orEmpty() else ""
                        val address = if (addrIdx >= 0) cursor.getString(addrIdx).orEmpty() else "Unknown"
                        val body = if (bodyIdx >= 0) cursor.getString(bodyIdx).orEmpty() else ""
                        val dateMs = if (dateIdx >= 0) cursor.getLong(dateIdx) else System.currentTimeMillis()
                        val isRead = if (readIdx >= 0) cursor.getInt(readIdx) == 1 else true
                        val contactName = callIntelligenceManager.resolveContactByPhoneNumber(address)
                        list.add(
                            enrichRecord(
                                id = id,
                                address = address,
                                resolvedContactName = contactName,
                                body = body,
                                timestampMs = dateMs,
                                isUnread = !isRead
                            )
                        )
                    }
                }
                return list to "Android Telephony.Sms Provider"
            } catch (_: SecurityException) {
                // Fall through to notification intelligence buffer
            } catch (_: Exception) {
                // Fall through to notification intelligence buffer
            }
        }

        // Fallback: inspect SMS notifications captured by NotificationIntelligenceRepository
        val smsNotifications = NotificationIntelligenceRepository.getFilteredNotifications()
            .filter { notif ->
                val pkg = notif.packageName.lowercase()
                pkg.contains("mms") || pkg.contains("messaging") || pkg.contains("sms") ||
                    notif.title.any { it.isDigit() }
            }
            .take(limit)

        if (smsNotifications.isNotEmpty()) {
            val records = smsNotifications.mapIndexed { idx, notif ->
                val sender = notif.title.ifBlank { "Unknown" }
                val contactName = callIntelligenceManager.resolveContactByPhoneNumber(sender)
                enrichRecord(
                    id = "notif_sms_$idx",
                    address = sender,
                    resolvedContactName = contactName,
                    body = notif.text,
                    timestampMs = notif.postTime,
                    isUnread = true
                )
            }
            return records to "Authorized Notification Listener SMS Buffer"
        }

        return emptyList<SmsMessageRecord>() to "PERMISSION_REQUIRED"
    }
}
