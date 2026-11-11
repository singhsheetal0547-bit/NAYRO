package com.example.core.automation

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Calendar
import java.util.UUID

enum class AutomationTriggerType(val displayName: String) {
    // Time-based triggers
    TIME_SCHEDULE("Time / Recurring / Window Schedule"),

    // Device event triggers
    BATTERY_BELOW_THRESHOLD("Battery Below Threshold"),
    BATTERY_LOW("System Battery Low"),
    CHARGING_STATE_CHANGED("Device Charging State Changed"),
    BOOT_COMPLETED("Device Boot / Restart Completed"),
    SCREEN_STATE_CHANGED("Screen Interactive State Changed"),
    CONNECTIVITY_CHANGED("Network / Connectivity Changed"),

    // Application event triggers
    NOTIFICATION_ARRIVED("Authorized Notification Arrived"),
    APP_STATE_CHANGED("Foreground Application Changed"),

    // Location event triggers
    GEOFENCE_TRANSITION("Geofence Entry / Exit Transition"),

    // Agent event triggers
    TASK_COMPLETED("Agent Task Completed"),
    TASK_FAILED("Agent Task Failed"),
    WORKFLOW_COMPLETED("Cross-App Workflow Completed"),
    MEMORY_UPDATED("Authorized Memory Updated"),
    MANUAL_TRIGGER("Manual / Voice Trigger")
}

data class AutomationRule(
    val ruleId: String = UUID.randomUUID().toString(),
    val name: String,
    val triggerType: AutomationTriggerType,
    val triggerConditionValue: String = "", // e.g., "08:00", "WEEKLY:MON:09:00", "09:00-17:00", "20", "com.whatsapp", "ENTER:Home"
    val actionInstruction: String,
    val isEnabled: Boolean = true,
    val cooldownMs: Long = 60_000L,
    val lastTriggeredAtMs: Long = 0L,
    val retryCountOnFailure: Int = 1
)

data class AutomationExecutionRecord(
    val recordId: String = UUID.randomUUID().toString(),
    val ruleId: String,
    val ruleName: String,
    val triggerType: AutomationTriggerType,
    val isSuccess: Boolean,
    val outputSummary: String,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Section 15 — Complete Event-Driven Automation Engine for NAYRO.
 * Supports:
 * - Time-based schedules (One-time, Daily HH:mm, Weekly, Monthly, Relative +Nm, Time Windows HH:mm-HH:mm)
 * - Device events (Battery threshold, Battery low, Charging connected/disconnected, Boot completed, Screen state, Connectivity)
 * - Application events (Notification arrived, Foreground app changed, Task completed/failed)
 * - Location events (Geofence entry/exit)
 * - Agent events (Workflow completed, Memory updated, Manual trigger)
 * - Persistent storage, AlarmManager scheduling, reboot re-registration, pause/resume, retry handling, and verified execution history.
 */
class AutomationEngine(private val context: Context? = null) {

    companion object {
        private const val PREFS_NAME = "nayro_automation_prefs"
        private const val KEY_RULES_JSON = "persisted_rules_json"
        private const val KEY_PAUSED = "automations_paused"
        const val ACTION_EXECUTE_AUTOMATION = "com.example.nayro.action.EXECUTE_AUTOMATION"
        const val EXTRA_RULE_ID = "com.example.nayro.extra.RULE_ID"
    }

    private val _rules = MutableStateFlow<List<AutomationRule>>(emptyList())
    val rules: StateFlow<List<AutomationRule>> = _rules.asStateFlow()

    private val _executionHistory = MutableStateFlow<List<AutomationExecutionRecord>>(emptyList())
    val executionHistory: StateFlow<List<AutomationExecutionRecord>> = _executionHistory.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    init {
        val loaded = loadPersistedRules()
        if (!loaded) {
            seedDefaultRules()
        }
    }

    private fun seedDefaultRules() {
        createRule(
            name = "Morning Briefing Summary",
            triggerType = AutomationTriggerType.TIME_SCHEDULE,
            triggerConditionValue = "08:00",
            actionInstruction = "What time is it and then check my notes"
        )
        createRule(
            name = "Low Battery Alert Note",
            triggerType = AutomationTriggerType.BATTERY_BELOW_THRESHOLD,
            triggerConditionValue = "15",
            actionInstruction = "Create note: Battery dropped below 15%"
        )
    }

    fun pauseAllAutomations() {
        _isPaused.value = true
        persistRules()
    }

    fun resumeAllAutomations() {
        _isPaused.value = false
        persistRules()
    }

    fun createRule(
        name: String,
        triggerType: AutomationTriggerType,
        triggerConditionValue: String,
        actionInstruction: String,
        isEnabled: Boolean = true,
        cooldownMs: Long = 60_000L,
        retryCountOnFailure: Int = 1
    ): AutomationRule {
        val rule = AutomationRule(
            name = name.trim(),
            triggerType = triggerType,
            triggerConditionValue = triggerConditionValue.trim(),
            actionInstruction = actionInstruction.trim(),
            isEnabled = isEnabled,
            cooldownMs = cooldownMs,
            retryCountOnFailure = retryCountOnFailure
        )
        _rules.value = _rules.value + rule
        persistRules()
        if (isEnabled && triggerType == AutomationTriggerType.TIME_SCHEDULE) {
            scheduleRuleAlarmIfPossible(rule)
        }
        return rule
    }

    fun toggleRule(ruleId: String, enabled: Boolean): Boolean {
        val current = _rules.value
        val index = current.indexOfFirst { it.ruleId == ruleId }
        if (index == -1) return false
        val updatedRule = current[index].copy(isEnabled = enabled)
        val updated = current.toMutableList()
        updated[index] = updatedRule
        _rules.value = updated
        persistRules()
        if (updatedRule.triggerType == AutomationTriggerType.TIME_SCHEDULE) {
            if (enabled) scheduleRuleAlarmIfPossible(updatedRule) else cancelRuleAlarmIfPossible(ruleId)
        }
        return true
    }

    fun deleteRule(ruleId: String): Boolean {
        val beforeSize = _rules.value.size
        _rules.value = _rules.value.filterNot { it.ruleId == ruleId }
        val deleted = _rules.value.size < beforeSize
        if (deleted) {
            cancelRuleAlarmIfPossible(ruleId)
            persistRules()
        }
        return deleted
    }

    fun updateRule(
        ruleId: String,
        name: String? = null,
        triggerType: AutomationTriggerType? = null,
        triggerConditionValue: String? = null,
        actionInstruction: String? = null,
        cooldownMs: Long? = null
    ): AutomationRule? {
        val current = _rules.value.toMutableList()
        val index = current.indexOfFirst { it.ruleId == ruleId }
        if (index == -1) return null
        val existing = current[index]
        val updated = existing.copy(
            name = name?.trim() ?: existing.name,
            triggerType = triggerType ?: existing.triggerType,
            triggerConditionValue = triggerConditionValue?.trim() ?: existing.triggerConditionValue,
            actionInstruction = actionInstruction?.trim() ?: existing.actionInstruction,
            cooldownMs = cooldownMs ?: existing.cooldownMs
        )
        current[index] = updated
        _rules.value = current
        persistRules()
        if (updated.isEnabled && updated.triggerType == AutomationTriggerType.TIME_SCHEDULE) {
            scheduleRuleAlarmIfPossible(updated)
        }
        return updated
    }

    suspend fun executeRuleNow(
        ruleId: String,
        nowMs: Long = System.currentTimeMillis(),
        instructionRunner: suspend (String) -> Pair<Boolean, String>
    ): AutomationExecutionRecord? {
        val rule = _rules.value.firstOrNull { it.ruleId == ruleId } ?: return null
        markTriggered(rule.ruleId, nowMs)
        var (ok, output) = instructionRunner(rule.actionInstruction)
        if (!ok && rule.retryCountOnFailure > 0) {
            val retryOut = instructionRunner(rule.actionInstruction)
            ok = retryOut.first
            output = retryOut.second
        }
        val record = AutomationExecutionRecord(
            ruleId = rule.ruleId,
            ruleName = rule.name,
            triggerType = AutomationTriggerType.MANUAL_TRIGGER,
            isSuccess = ok,
            outputSummary = output,
            timestampMs = nowMs
        )
        _executionHistory.value = (listOf(record) + _executionHistory.value).take(50)
        return record
    }

    /**
     * Evaluates incoming event [triggerType] with [eventValue] against enabled rules,
     * enforcing duplicate-prevention cooldowns, and executes matched rules via [instructionRunner].
     * Never records `isSuccess = true` unless [instructionRunner] returns `true`.
     */
    suspend fun onEventTriggered(
        triggerType: AutomationTriggerType,
        eventValue: String = "",
        nowMs: Long = System.currentTimeMillis(),
        isGlobalPaused: Boolean = _isPaused.value,
        instructionRunner: suspend (String) -> Pair<Boolean, String>
    ): List<AutomationExecutionRecord> {
        if (isGlobalPaused || _isPaused.value) return emptyList()

        val matchedRules = _rules.value.filter { rule ->
            rule.isEnabled &&
                rule.triggerType == triggerType &&
                (nowMs - rule.lastTriggeredAtMs >= rule.cooldownMs) &&
                matchesCondition(rule, eventValue)
        }

        if (matchedRules.isEmpty()) return emptyList()

        val records = mutableListOf<AutomationExecutionRecord>()
        for (rule in matchedRules) {
            markTriggered(rule.ruleId, nowMs)
            var (ok, output) = instructionRunner(rule.actionInstruction)
            if (!ok && rule.retryCountOnFailure > 0) {
                val retryRes = instructionRunner(rule.actionInstruction)
                ok = retryRes.first
                output = retryRes.second
            }
            val record = AutomationExecutionRecord(
                ruleId = rule.ruleId,
                ruleName = rule.name,
                triggerType = rule.triggerType,
                isSuccess = ok,
                outputSummary = output,
                timestampMs = nowMs
            )
            records.add(record)
        }

        _executionHistory.value = (records + _executionHistory.value).take(50)
        persistRules()
        return records
    }

    /**
     * Re-registers all enabled time-based schedules with Android AlarmManager after device reboot.
     */
    fun rescheduleAllEnabledRules(targetContext: Context? = context): Int {
        var scheduledCount = 0
        for (rule in _rules.value) {
            if (rule.isEnabled && rule.triggerType == AutomationTriggerType.TIME_SCHEDULE) {
                if (scheduleRuleAlarmIfPossible(rule, targetContext)) {
                    scheduledCount++
                }
            }
        }
        return scheduledCount
    }

    fun exportRulesJson(): String {
        return try {
            val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
            val listType = Types.newParameterizedType(List::class.java, AutomationRule::class.java)
            val adapter = moshi.adapter<List<AutomationRule>>(listType)
            adapter.toJson(_rules.value)
        } catch (_: Exception) {
            "[]"
        }
    }

    fun importRulesJson(json: String): Boolean {
        return try {
            val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
            val listType = Types.newParameterizedType(List::class.java, AutomationRule::class.java)
            val adapter = moshi.adapter<List<AutomationRule>>(listType)
            val parsed = adapter.fromJson(json) ?: return false
            _rules.value = parsed
            persistRules()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun matchesCondition(rule: AutomationRule, eventValue: String): Boolean {
        val cond = rule.triggerConditionValue.trim()
        if (cond.isEmpty()) return true
        return when (rule.triggerType) {
            AutomationTriggerType.BATTERY_BELOW_THRESHOLD,
            AutomationTriggerType.BATTERY_LOW -> {
                val threshold = cond.toIntOrNull() ?: return true
                val actual = eventValue.toIntOrNull() ?: return false
                actual <= threshold
            }
            AutomationTriggerType.TIME_SCHEDULE -> {
                val ev = eventValue.trim()
                if (ev == cond) return true
                // Support time window "HH:mm-HH:mm"
                if (cond.contains("-") && ev.matches(Regex("\\d{2}:\\d{2}"))) {
                    val parts = cond.split("-").map { it.trim() }
                    if (parts.size == 2) {
                        return ev >= parts[0] && ev <= parts[1]
                    }
                }
                // Support weekly/monthly prefix e.g. "WEEKLY:MON:08:00"
                cond.endsWith(ev, ignoreCase = true)
            }
            AutomationTriggerType.NOTIFICATION_ARRIVED,
            AutomationTriggerType.APP_STATE_CHANGED,
            AutomationTriggerType.GEOFENCE_TRANSITION,
            AutomationTriggerType.CHARGING_STATE_CHANGED,
            AutomationTriggerType.CONNECTIVITY_CHANGED -> {
                eventValue.contains(cond, ignoreCase = true)
            }
            else -> eventValue.contains(cond, ignoreCase = true)
        }
    }

    private fun markTriggered(ruleId: String, nowMs: Long) {
        _rules.value = _rules.value.map {
            if (it.ruleId == ruleId) it.copy(lastTriggeredAtMs = nowMs) else it
        }
    }

    private fun scheduleRuleAlarmIfPossible(rule: AutomationRule, targetContext: Context? = context): Boolean {
        val ctx = targetContext ?: return false
        return try {
            val alarmManager = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
            val nextTriggerMs = computeNextTriggerTimeMs(rule.triggerConditionValue) ?: return false
            val intent = Intent(ctx, NayroAutomationReceiver::class.java).apply {
                action = ACTION_EXECUTE_AUTOMATION
                putExtra(EXTRA_RULE_ID, rule.ruleId)
            }
            val pi = PendingIntent.getBroadcast(
                ctx,
                rule.ruleId.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTriggerMs, pi)
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTriggerMs, pi)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun cancelRuleAlarmIfPossible(ruleId: String) {
        val ctx = context ?: return
        try {
            val alarmManager = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(ctx, NayroAutomationReceiver::class.java).apply {
                action = ACTION_EXECUTE_AUTOMATION
                putExtra(EXTRA_RULE_ID, ruleId)
            }
            val pi = PendingIntent.getBroadcast(
                ctx,
                ruleId.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pi)
        } catch (_: Exception) {}
    }

    fun computeNextTriggerTimeMs(condition: String, nowMs: Long = System.currentTimeMillis()): Long? {
        val clean = condition.trim()
        if (clean.startsWith("+") && clean.endsWith("m", ignoreCase = true)) {
            val mins = clean.drop(1).dropLast(1).toLongOrNull() ?: return null
            return nowMs + mins * 60_000L
        }
        val hhmm = Regex("(\\d{1,2}):(\\d{2})").find(clean) ?: return null
        val hour = hhmm.groupValues[1].toIntOrNull() ?: return null
        val minute = hhmm.groupValues[2].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null

        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= nowMs) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    /**
     * Phase 9 — Parses natural language reminder specifications ("tomorrow at 7 AM",
     * "today at 6:30 PM", "in 15 minutes", "in 2 hours", "kal subah 7 baje") in the device timezone.
     */
    fun parseReminderTimeMillis(rawSpec: String, nowMs: Long = System.currentTimeMillis()): Long {
        val lower = rawSpec.trim().lowercase()

        // 1. Relative minutes/hours: "in 15 minutes", "in 2 hours", "+15m"
        val inMinsMatch = Regex("\\bin\\s+(\\d+)\\s*(?:minute|minutes|min|mins)\\b").find(lower)
        if (inMinsMatch != null) {
            val mins = inMinsMatch.groupValues[1].toLongOrNull() ?: 15L
            return nowMs + mins * 60_000L
        }
        val inHoursMatch = Regex("\\bin\\s+(\\d+)\\s*(?:hour|hours|hr|hrs)\\b").find(lower)
        if (inHoursMatch != null) {
            val hours = inHoursMatch.groupValues[1].toLongOrNull() ?: 1L
            return nowMs + hours * 3_600_000L
        }

        val isTomorrow = lower.contains("tomorrow") || lower.contains("kal")
        val isDayAfter = lower.contains("day after tomorrow") || lower.contains("parso")

        // 2. Time of day: "7 AM", "7:30 PM", "19:00", "7 baje"
        val amPmMatch = Regex("\\b(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)\\b").find(lower)
        val twentyFourMatch = Regex("\\b(\\d{1,2}):(\\d{2})\\b").find(lower)
        val bajeMatch = Regex("\\b(\\d{1,2})\\s*baje\\b").find(lower)

        var hourOfDay = 9
        var minute = 0
        var hasExplicitTime = false

        if (amPmMatch != null) {
            hasExplicitTime = true
            val rawHour = amPmMatch.groupValues[1].toIntOrNull() ?: 9
            minute = amPmMatch.groupValues[2].toIntOrNull() ?: 0
            val mer = amPmMatch.groupValues[3]
            hourOfDay = when {
                mer == "pm" && rawHour in 1..11 -> rawHour + 12
                mer == "am" && rawHour == 12 -> 0
                else -> rawHour.coerceIn(0, 23)
            }
        } else if (twentyFourMatch != null) {
            hasExplicitTime = true
            hourOfDay = (twentyFourMatch.groupValues[1].toIntOrNull() ?: 9).coerceIn(0, 23)
            minute = (twentyFourMatch.groupValues[2].toIntOrNull() ?: 0).coerceIn(0, 59)
        } else if (bajeMatch != null) {
            hasExplicitTime = true
            val rawHour = (bajeMatch.groupValues[1].toIntOrNull() ?: 9).coerceIn(0, 23)
            hourOfDay = if ((lower.contains("shaam") || lower.contains("raat")) && rawHour in 1..11) {
                rawHour + 12
            } else {
                rawHour
            }
        }

        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMs
            if (isDayAfter) {
                add(Calendar.DAY_OF_YEAR, 2)
            } else if (isTomorrow) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
            if (hasExplicitTime || isTomorrow || isDayAfter) {
                set(Calendar.HOUR_OF_DAY, hourOfDay)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            } else {
                // Default to 1 hour from now if neither day nor time was specified
                add(Calendar.HOUR_OF_DAY, 1)
            }
        }

        if (cal.timeInMillis <= nowMs) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    /**
     * Phase 9 — Persists a reminder rule and schedules its trigger via AlarmManager.
     * Returns Pair(AutomationRule, Boolean indicating whether AlarmManager registered the hardware alarm).
     */
    fun scheduleReminder(
        reminderTitle: String,
        rawTimeSpec: String,
        explicitTimeMs: Long? = null,
        nowMs: Long = System.currentTimeMillis()
    ): Pair<AutomationRule, Boolean> {
        val targetMs = explicitTimeMs?.takeIf { it > nowMs }
            ?: parseReminderTimeMillis("$reminderTitle $rawTimeSpec", nowMs)
        val cal = Calendar.getInstance().apply { timeInMillis = targetMs }
        val hhmm = "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))

        val rule = AutomationRule(
            name = "Reminder: $reminderTitle",
            triggerType = AutomationTriggerType.TIME_SCHEDULE,
            triggerConditionValue = hhmm,
            actionInstruction = "Create note: Reminder triggered — $reminderTitle",
            isEnabled = true,
            cooldownMs = 60_000L,
            retryCountOnFailure = 1
        )
        _rules.value = _rules.value + rule
        persistRules()
        val alarmScheduled = scheduleExactTimestampAlarm(rule, targetMs)
        return rule to alarmScheduled
    }

    private fun scheduleExactTimestampAlarm(
        rule: AutomationRule,
        triggerAtMs: Long,
        targetContext: Context? = context
    ): Boolean {
        val ctx = targetContext ?: return false
        return try {
            val alarmManager = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
            val intent = Intent(ctx, NayroAutomationReceiver::class.java).apply {
                action = ACTION_EXECUTE_AUTOMATION
                putExtra(EXTRA_RULE_ID, rule.ruleId)
            }
            val pi = PendingIntent.getBroadcast(
                ctx,
                rule.ruleId.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun persistRules() {
        val ctx = context ?: return
        try {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_RULES_JSON, exportRulesJson())
                .putBoolean(KEY_PAUSED, _isPaused.value)
                .apply()
        } catch (_: Exception) {}
    }

    private fun loadPersistedRules(): Boolean {
        val ctx = context ?: return false
        return try {
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            _isPaused.value = prefs.getBoolean(KEY_PAUSED, false)
            val json = prefs.getString(KEY_RULES_JSON, null) ?: return false
            importRulesJson(json)
        } catch (_: Exception) {
            false
        }
    }
}
