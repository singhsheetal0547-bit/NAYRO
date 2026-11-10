package com.example.core.service

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import com.example.core.accessibility.AccessibilityConnectionManager
import com.example.core.agent.ActionType
import com.example.core.agent.AgentAction
import com.example.core.agent.RiskLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Section 3 — Distinct Android Operating States supported by NAYRO:
 * A. APPLICATION_OPEN
 * B. APPLICATION_IN_BACKGROUND
 * C. ANOTHER_APPLICATION_IN_FOREGROUND
 * D. SCREEN_OFF
 * E. DEVICE_LOCKED
 * F. DEVICE_RESTARTED
 * G. DEVICE_POWERED_OFF (documented state; restored into DEVICE_RESTARTED upon boot)
 */
enum class DeviceOperatingState(val code: String, val displayName: String) {
    APPLICATION_OPEN("A", "NAYRO Application Open"),
    APPLICATION_IN_BACKGROUND("B", "NAYRO in Background"),
    ANOTHER_APPLICATION_IN_FOREGROUND("C", "Another App in Foreground"),
    SCREEN_OFF("D", "Screen Off (Standby)"),
    DEVICE_LOCKED("E", "Device Locked (Keyguard Active)"),
    DEVICE_RESTARTED("F", "Device Restarted (Boot Recovery)"),
    DEVICE_POWERED_OFF("G", "Device Powered Off (Inactive until Boot)")
}

sealed class LockScreenPolicyDecision {
    object AllowedWhileLocked : LockScreenPolicyDecision()
    data class RequireDeviceUnlock(val reason: String) : LockScreenPolicyDecision()
}

/**
 * Manages real Android operating states (Screen Off, Keyguard Locked, App Foreground/Background, Boot Restart)
 * and enforces Lock-Screen execution security so NAYRO never bypasses Android lock-screen protection
 * or accesses protected user content while the device is locked.
 */
class DeviceOperatingStateManager(private val context: Context?) {

    private val _currentOperatingState = MutableStateFlow(DeviceOperatingState.APPLICATION_OPEN)
    val currentOperatingState: StateFlow<DeviceOperatingState> = _currentOperatingState.asStateFlow()

    @Volatile
    private var isAppInForeground: Boolean = true

    @Volatile
    private var lockStateOverrideForTesting: Boolean? = null

    @Volatile
    private var screenInteractiveOverrideForTesting: Boolean? = null

    companion object {
        /**
         * Actions safe to execute on the lock screen or while the screen is off without unlocking the device.
         */
        val LOCK_SCREEN_SAFE_ACTIONS: Set<ActionType> = setOf(
            ActionType.GET_DEVICE_TIME,
            ActionType.GET_DEVICE_INFO,
            ActionType.GET_BATTERY_STATE,
            ActionType.GET_NETWORK_STATUS,
            ActionType.GET_STORAGE_INFO,
            ActionType.CALCULATE_LOCAL,
            ActionType.TOGGLE_FLASHLIGHT,
            ActionType.SCHEDULE_ALARM,
            ActionType.CREATE_REMINDER,
            ActionType.QUERY_LLM
        )
    }

    fun onAppForegroundChanged(inForeground: Boolean) {
        isAppInForeground = inForeground
        refreshOperatingState()
    }

    fun onBootCompleted() {
        _currentOperatingState.value = DeviceOperatingState.DEVICE_RESTARTED
    }

    fun setTestingOverrides(isLocked: Boolean?, isScreenInteractive: Boolean?) {
        lockStateOverrideForTesting = isLocked
        screenInteractiveOverrideForTesting = isScreenInteractive
        refreshOperatingState()
    }

    fun isScreenInteractive(): Boolean {
        screenInteractiveOverrideForTesting?.let { return it }
        val pm = context?.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return pm?.isInteractive ?: true
    }

    fun isDeviceLocked(): Boolean {
        lockStateOverrideForTesting?.let { return it }
        val km = context?.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return km?.isKeyguardLocked ?: false
    }

    fun refreshOperatingState(): DeviceOperatingState {
        val screenOn = isScreenInteractive()
        val locked = isDeviceLocked()
        val activePkg = AccessibilityConnectionManager.lastActivePackage.value
        val ownPkg = context?.packageName

        val resolved = when {
            !screenOn -> DeviceOperatingState.SCREEN_OFF
            locked -> DeviceOperatingState.DEVICE_LOCKED
            isAppInForeground -> DeviceOperatingState.APPLICATION_OPEN
            !activePkg.isNullOrBlank() && ownPkg != null && activePkg != ownPkg ->
                DeviceOperatingState.ANOTHER_APPLICATION_IN_FOREGROUND
            else -> DeviceOperatingState.APPLICATION_IN_BACKGROUND
        }
        _currentOperatingState.value = resolved
        return resolved
    }

    /**
     * Evaluates whether [action] is permitted when the device is currently locked.
     * Protected actions (UI automation, reading notifications/files/contacts/calendar,
     * sending messages, placing calls, R2/R3/R4 risk levels) require unlocking the device first.
     */
    fun evaluateLockScreenPolicy(
        action: AgentAction,
        isLocked: Boolean = isDeviceLocked()
    ): LockScreenPolicyDecision {
        if (!isLocked) {
            return LockScreenPolicyDecision.AllowedWhileLocked
        }

        if (action.actionType in LOCK_SCREEN_SAFE_ACTIONS &&
            action.riskLevel != RiskLevel.R3_EXTERNAL_COMMUNICATION &&
            action.riskLevel != RiskLevel.R4_FINANCIAL_PROHIBITED
        ) {
            return LockScreenPolicyDecision.AllowedWhileLocked
        }

        return LockScreenPolicyDecision.RequireDeviceUnlock(
            "Device is currently locked. Please unlock your Android device to execute '${action.actionType.name}' safely."
        )
    }
}
