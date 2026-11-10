package com.example.core.proactive

import com.example.core.notification.CapturedNotificationItem
import com.example.data.local.entities.NoteEntity
import com.example.data.local.entities.TaskHistoryEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SuggestionPriority(val weight: Int) {
    HIGH(3),
    MEDIUM(2),
    LOW(1)
}

data class ProactiveSuggestion(
    val suggestionId: String,
    val title: String,
    val description: String,
    val priority: SuggestionPriority,
    val suggestedPrompt: String,
    val sourceType: String,
    val createdAtMs: Long = System.currentTimeMillis()
)

/**
 * Proactive Intelligence Engine (Part 7).
 * Evaluates authorized context (tasks, reminders, notifications, device battery) to surface
 * non-intrusive, prioritized suggestions while respecting quiet hours, duplicate prevention,
 * frequency limits, and user dismissals. Never executes sensitive actions autonomously.
 */
class ProactiveIntelligenceEngine {

    companion object {
        const val MAX_ACTIVE_SUGGESTIONS = 5
    }

    private val _suggestions = MutableStateFlow<List<ProactiveSuggestion>>(emptyList())
    val suggestions: StateFlow<List<ProactiveSuggestion>> = _suggestions.asStateFlow()

    private val _suggestionHistory = MutableStateFlow<List<ProactiveSuggestion>>(emptyList())
    val suggestionHistory: StateFlow<List<ProactiveSuggestion>> = _suggestionHistory.asStateFlow()

    private val dismissedIds = mutableSetOf<String>()
    private var quietHoursStartHour: Int = 22 // 10:00 PM
    private var quietHoursEndHour: Int = 7    // 7:00 AM
    var isProactiveEnabled: Boolean = true

    fun configureQuietHours(startHour: Int, endHour: Int) {
        quietHoursStartHour = startHour.coerceIn(0, 23)
        quietHoursEndHour = endHour.coerceIn(0, 23)
    }

    fun isWithinQuietHours(currentHourOfDay: Int): Boolean {
        return if (quietHoursStartHour > quietHoursEndHour) {
            currentHourOfDay >= quietHoursStartHour || currentHourOfDay < quietHoursEndHour
        } else if (quietHoursStartHour < quietHoursEndHour) {
            currentHourOfDay in quietHoursStartHour until quietHoursEndHour
        } else {
            false
        }
    }

    fun evaluateContext(
        incompleteTasks: List<TaskHistoryEntity>,
        notesAndReminders: List<NoteEntity>,
        unreadNotifications: List<CapturedNotificationItem>,
        batteryPercent: Int,
        currentHourOfDay: Int = 12
    ): List<ProactiveSuggestion> {
        if (!isProactiveEnabled || isWithinQuietHours(currentHourOfDay)) {
            _suggestions.value = emptyList()
            return emptyList()
        }

        val candidates = mutableListOf<ProactiveSuggestion>()

        // 1. Incomplete or partially completed tasks continuation
        incompleteTasks.firstOrNull()?.let { task ->
            val id = "incomplete_task_${task.taskId}"
            if (id !in dismissedIds) {
                candidates.add(
                    ProactiveSuggestion(
                        suggestionId = id,
                        title = "Resume Incomplete Task",
                        description = "Task '${task.userInstruction}' ended in ${task.status}. Tap to review or retry.",
                        priority = SuggestionPriority.HIGH,
                        suggestedPrompt = task.userInstruction,
                        sourceType = "TASK_CONTINUITY"
                    )
                )
            }
        }

        // 2. Active reminders in local notebook
        val reminders = notesAndReminders.filter { it.category.equals("Reminders", ignoreCase = true) || it.title.startsWith("Reminder:") }
        reminders.firstOrNull()?.let { rem ->
            val id = "reminder_note_${rem.id}"
            if (id !in dismissedIds) {
                candidates.add(
                    ProactiveSuggestion(
                        suggestionId = id,
                        title = "Upcoming Reminder",
                        description = rem.content,
                        priority = SuggestionPriority.HIGH,
                        suggestedPrompt = "Check my notes and reminders",
                        sourceType = "REMINDER"
                    )
                )
            }
        }

        // 3. Unread notifications summary opportunity
        if (unreadNotifications.size >= 2) {
            val id = "unread_notif_batch_${unreadNotifications.size}"
            if (id !in dismissedIds) {
                candidates.add(
                    ProactiveSuggestion(
                        suggestionId = id,
                        title = "Summarize ${unreadNotifications.size} Notifications",
                        description = "You have ${unreadNotifications.size} active notifications. Babu can summarize them for you.",
                        priority = SuggestionPriority.MEDIUM,
                        suggestedPrompt = "Summarize my active notifications",
                        sourceType = "NOTIFICATIONS"
                    )
                )
            }
        }

        // 4. Low battery awareness
        if (batteryPercent in 1..20) {
            val id = "low_battery_$batteryPercent"
            if (id !in dismissedIds) {
                candidates.add(
                    ProactiveSuggestion(
                        suggestionId = id,
                        title = "Low Battery ($batteryPercent%)",
                        description = "Battery is at $batteryPercent%. Consider opening Battery Settings to enable Power Saver.",
                        priority = SuggestionPriority.MEDIUM,
                        suggestedPrompt = "Open battery settings",
                        sourceType = "DEVICE_STATE"
                    )
                )
            }
        }

        val prioritized = candidates
            .distinctBy { it.suggestionId }
            .sortedByDescending { it.priority.weight }
            .take(MAX_ACTIVE_SUGGESTIONS)

        _suggestions.value = prioritized
        _suggestionHistory.value = (prioritized + _suggestionHistory.value).distinctBy { it.suggestionId }.take(30)
        return prioritized
    }

    fun dismissSuggestion(suggestionId: String) {
        synchronized(dismissedIds) {
            dismissedIds.add(suggestionId)
        }
        _suggestions.value = _suggestions.value.filterNot { it.suggestionId == suggestionId }
    }

    fun clearDismissed() {
        synchronized(dismissedIds) {
            dismissedIds.clear()
        }
    }
}
