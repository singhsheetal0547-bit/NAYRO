package com.example.core.notification

import android.app.Notification
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class NotificationCategory {
    MESSAGE,
    REMINDER_OR_CALENDAR,
    SYSTEM_ALERT,
    SOCIAL_OR_PROMOTION,
    GENERAL
}

data class CapturedNotificationItem(
    val key: String,
    val packageName: String,
    val title: String,
    val text: String,
    val category: NotificationCategory,
    val postTime: Long
)

/**
 * Thread-safe repository holding real authorized notifications captured by [NayroNotificationListenerService].
 * Never fabricates notifications when permission is missing or the tray is empty (Part 15).
 */
object NotificationIntelligenceRepository {

    private val _notifications = MutableStateFlow<List<CapturedNotificationItem>>(emptyList())
    val notifications: StateFlow<List<CapturedNotificationItem>> = _notifications.asStateFlow()

    private val _isListenerConnected = MutableStateFlow(false)
    val isListenerConnected: StateFlow<Boolean> = _isListenerConnected.asStateFlow()

    private val allowedPackagesFilter = mutableSetOf<String>()

    var onNotificationCapturedTrigger: ((CapturedNotificationItem) -> Unit)? = null

    fun setListenerConnected(connected: Boolean) {
        _isListenerConnected.value = connected
    }

    fun isNotificationAccessEnabled(context: Context?): Boolean {
        if (_isListenerConnected.value) return true
        val ctx = context ?: return false
        return try {
            val enabled = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners").orEmpty()
            enabled.contains(ctx.packageName)
        } catch (_: Exception) {
            false
        }
    }

    fun setAllowedPackagesFilter(packages: Set<String>) {
        synchronized(allowedPackagesFilter) {
            allowedPackagesFilter.clear()
            allowedPackagesFilter.addAll(packages.map { it.lowercase().trim() }.filter { it.isNotEmpty() })
        }
    }

    fun classifyNotification(packageName: String, title: String, text: String): NotificationCategory {
        val pkg = packageName.lowercase()
        val combined = "$title $text".lowercase()
        return when {
            pkg.contains("whatsapp") || pkg.contains("messaging") || pkg.contains("telegram") ||
                combined.contains("message") || combined.contains("replied") -> NotificationCategory.MESSAGE
            pkg.contains("calendar") || pkg.contains("deskclock") || combined.contains("reminder") ||
                combined.contains("meeting") || combined.contains("alarm") -> NotificationCategory.REMINDER_OR_CALENDAR
            pkg.contains("android") || combined.contains("battery") || combined.contains("security") ||
                combined.contains("warning") -> NotificationCategory.SYSTEM_ALERT
            pkg.contains("instagram") || pkg.contains("youtube") || combined.contains("sale") ||
                combined.contains("offer") -> NotificationCategory.SOCIAL_OR_PROMOTION
            else -> NotificationCategory.GENERAL
        }
    }

    fun onNotificationPosted(item: CapturedNotificationItem) {
        synchronized(allowedPackagesFilter) {
            if (allowedPackagesFilter.isNotEmpty() && item.packageName.lowercase() !in allowedPackagesFilter) {
                return
            }
        }
        val updated = (_notifications.value.filterNot { it.key == item.key } + item)
            .sortedByDescending { it.postTime }
            .take(50)
        _notifications.value = updated
        onNotificationCapturedTrigger?.invoke(item)
    }

    fun onNotificationRemoved(key: String) {
        _notifications.value = _notifications.value.filterNot { it.key == key }
    }

    fun getFilteredNotifications(packageFilter: String? = null): List<CapturedNotificationItem> {
        val current = _notifications.value
        if (packageFilter.isNullOrBlank()) return current
        return current.filter { it.packageName.contains(packageFilter.trim(), ignoreCase = true) }
    }

    fun summarizeNotifications(packageFilter: String? = null): String {
        val list = getFilteredNotifications(packageFilter)
        if (list.isEmpty()) {
            return "No active notifications in the authorized buffer."
        }
        val byCat = list.groupBy { it.category }
        return buildString {
            appendLine("Active Notifications (${list.size}):")
            byCat.forEach { (cat, items) ->
                appendLine("- ${cat.name} (${items.size}): " + items.take(3).joinToString("; ") { "${it.title}: ${it.text}" })
            }
        }.trim()
    }

    fun clearForTesting() {
        _notifications.value = emptyList()
        _isListenerConnected.value = false
        synchronized(allowedPackagesFilter) {
            allowedPackagesFilter.clear()
        }
    }
}

/**
 * Genuine Android NotificationListenerService for NAYRO (Part 15).
 */
class NayroNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        NotificationIntelligenceRepository.setListenerConnected(true)
        try {
            activeNotifications?.forEach { sbn ->
                extractItem(sbn)?.let { NotificationIntelligenceRepository.onNotificationPosted(it) }
            }
        } catch (_: Exception) {}
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        NotificationIntelligenceRepository.setListenerConnected(false)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (sbn.packageName == packageName) return // Ignore NAYRO's own foreground notification
        extractItem(sbn)?.let { NotificationIntelligenceRepository.onNotificationPosted(it) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val key = sbn?.key ?: return
        NotificationIntelligenceRepository.onNotificationRemoved(key)
    }

    private fun extractItem(sbn: StatusBarNotification): CapturedNotificationItem? {
        val extras = sbn.notification?.extras ?: return null
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return null

        val category = NotificationIntelligenceRepository.classifyNotification(
            packageName = sbn.packageName.orEmpty(),
            title = title,
            text = text
        )
        return CapturedNotificationItem(
            key = sbn.key ?: "${sbn.packageName}_${sbn.postTime}",
            packageName = sbn.packageName.orEmpty(),
            title = title.ifEmpty { sbn.packageName.orEmpty() },
            text = text,
            category = category,
            postTime = sbn.postTime
        )
    }
}
