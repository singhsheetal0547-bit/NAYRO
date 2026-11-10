package com.example.core.automation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class AutomationTriggerType(val displayName: String) {
    TIME_SCHEDULE("Time / Daily Schedule"),
    BATTERY_BELOW_THRESHOLD("Battery Below Threshold"),
    CHARGING_STATE_CHANGED("Device Charging Connected"),
    NOTIFICATION_ARRIVED("Authorized Notification Arrived"),
    TASK_COMPLETED("Agent Task Completed"),
    TASK_FAILED("Agent Task Failed"),
    MANUAL_TRIGGER("Manual / Voice Trigger")
}

data class AutomationRule(
    val ruleId: String = UUID.randomUUID().toString(),
    val name: String,
    val triggerType: AutomationTriggerType,
    val triggerConditionValue: String = "", // e.g., "08:00", "20", "com.whatsapp"
    val actionInstruction: String,
    val isEnabled: Boolean = true,
    val cooldownMs: Long = 60_000L,
    val lastTriggeredAtMs: Long = 0L
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
 * Event-Based Automation Engine (Part 8).
 * Supports Android-compatible triggers (Time, Battery, Charging, Notification, Task lifecycle, Manual),
 * conditional rule matching, duplicate-trigger cooldown prevention, and execution history.
 */
class AutomationEngine {

    private val _rules = MutableStateFlow<List<AutomationRule>>(emptyList())
    val rules: StateFlow<List<AutomationRule>> = _rules.asStateFlow()

    private val _executionHistory = MutableStateFlow<List<AutomationExecutionRecord>>(emptyList())
    val executionHistory: StateFlow<List<AutomationExecutionRecord>> = _executionHistory.asStateFlow()

    init {
        seedDefaultRules()
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

    fun createRule(
        name: String,
        triggerType: AutomationTriggerType,
        triggerConditionValue: String,
        actionInstruction: String,
        isEnabled: Boolean = true,
        cooldownMs: Long = 60_000L
    ): AutomationRule {
        val rule = AutomationRule(
            name = name.trim(),
            triggerType = triggerType,
            triggerConditionValue = triggerConditionValue.trim(),
            actionInstruction = actionInstruction.trim(),
            isEnabled = isEnabled,
            cooldownMs = cooldownMs
        )
        _rules.value = _rules.value + rule
        return rule
    }

    fun toggleRule(ruleId: String, enabled: Boolean): Boolean {
        val current = _rules.value
        val index = current.indexOfFirst { it.ruleId == ruleId }
        if (index == -1) return false
        val updated = current.toMutableList()
        updated[index] = updated[index].copy(isEnabled = enabled)
        _rules.value = updated
        return true
    }

    fun deleteRule(ruleId: String): Boolean {
        val beforeSize = _rules.value.size
        _rules.value = _rules.value.filterNot { it.ruleId == ruleId }
        return _rules.value.size < beforeSize
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
        return updated
    }

    suspend fun executeRuleNow(
        ruleId: String,
        nowMs: Long = System.currentTimeMillis(),
        instructionRunner: suspend (String) -> Pair<Boolean, String>
    ): AutomationExecutionRecord? {
        val rule = _rules.value.firstOrNull { it.ruleId == ruleId } ?: return null
        markTriggered(rule.ruleId, nowMs)
        val (ok, output) = instructionRunner(rule.actionInstruction)
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
     */
    suspend fun onEventTriggered(
        triggerType: AutomationTriggerType,
        eventValue: String = "",
        nowMs: Long = System.currentTimeMillis(),
        isGlobalPaused: Boolean = false,
        instructionRunner: suspend (String) -> Pair<Boolean, String>
    ): List<AutomationExecutionRecord> {
        if (isGlobalPaused) return emptyList()

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
            val (ok, output) = instructionRunner(rule.actionInstruction)
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
        return records
    }

    private fun matchesCondition(rule: AutomationRule, eventValue: String): Boolean {
        val cond = rule.triggerConditionValue.trim()
        if (cond.isEmpty()) return true
        return when (rule.triggerType) {
            AutomationTriggerType.BATTERY_BELOW_THRESHOLD -> {
                val threshold = cond.toIntOrNull() ?: return false
                val actual = eventValue.toIntOrNull() ?: return false
                actual <= threshold
            }
            AutomationTriggerType.TIME_SCHEDULE -> {
                eventValue.trim() == cond
            }
            AutomationTriggerType.NOTIFICATION_ARRIVED -> {
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
}
