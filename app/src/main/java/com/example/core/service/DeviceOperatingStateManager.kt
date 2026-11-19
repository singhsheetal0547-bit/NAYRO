package com.example.core.service

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.example.core.accessibility.AccessibilityConnectionManager
import com.example.core.agent.ActionType
import com.example.core.agent.AgentAction
import com.example.core.agent.RiskLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Distinct Android Operating States supported by NAYRO (Section 3 & Phase 2 Lock-Aware Lifecycle):
 * - APPLICATION_OPEN / SCREEN_ON_UNLOCKED
 * - APPLICATION_IN_BACKGROUND / APP_BACKGROUND
 * - ANOTHER_APPLICATION_IN_FOREGROUND
 * - SCREEN_OFF
 * - DEVICE_LOCKED
 * - DEVICE_UNLOCKED
 * - PROCESS_RECREATED
 * - DEVICE_RESTARTED / DEVICE_REBOOTED
 * - DEVICE_POWERED_OFF
 */
enum class DeviceOperatingState(val code: String, val displayName: String) {
    APPLICATION_OPEN("A", "NAYRO Application Open"),
    APPLICATION_IN_BACKGROUND("B", "NAYRO in Background"),
    ANOTHER_APPLICATION_IN_FOREGROUND("C", "Another App in Foreground"),
    SCREEN_OFF("D", "Screen Off (Standby)"),
    DEVICE_LOCKED("E", "Device Locked (Keyguard Active)"),
    DEVICE_RESTARTED("F", "Device Restarted (Boot Recovery)"),
    DEVICE_POWERED_OFF("G", "Device Powered Off (Inactive until Boot)"),
    SCREEN_ON_UNLOCKED("H", "Screen On & Unlocked"),
    DEVICE_UNLOCKED("I", "Device Unlocked (User Present)"),
    APP_BACKGROUND("J", "Application in Background"),
    PROCESS_RECREATED("K", "Application Process Recreated"),
    DEVICE_REBOOTED("L", "Device Rebooted")
}

sealed class LockScreenPolicyDecision {
    object AllowedWhileLocked : LockScreenPolicyDecision()
    data class RequireDeviceUnlock(val reason: String) : LockScreenPolicyDecision()
}

/**
 * Manages real Android operating states (Screen Off, Keyguard Locked, Device Unlocked,
 * App Foreground/Background, Process Recreated, Boot Restart) and enforces Lock-Screen
 * execution security so NAYRO never bypasses Android lock-screen protection or collects
 * user PIN/password/biometric credentials.
 */
class DeviceOperatingStateManager(private val context: Context?) {

    private val _currentOperatingState = MutableStateFlow(DeviceOperatingState.APPLICATION_OPEN)
    val currentOperatingState: StateFlow<DeviceOperatingState> = _currentOperatingState.asStateFlow()
    val operatingState: StateFlow<DeviceOperatingState> get() = currentOperatingState

    fun refreshDeviceOperatingState(
        isScreenInteractive: Boolean? = null,
        isKeyguardLocked: Boolean? = null
    ): DeviceOperatingState {
        if (isScreenInteractive != null || isKeyguardLocked != null) {
            screenInteractiveOverrideForTesting = isScreenInteractive
            lockStateOverrideForTesting = isKeyguardLocked
        }
        val screenOn = isScreenInteractive()
        val locked = isDeviceLocked()
        val resolved = when {
            !screenOn -> DeviceOperatingState.SCREEN_OFF
            locked -> DeviceOperatingState.DEVICE_LOCKED
            else -> DeviceOperatingState.SCREEN_ON_UNLOCKED
        }
        _currentOperatingState.value = resolved
        return resolved
    }

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

    fun onDeviceRebooted() {
        _currentOperatingState.value = DeviceOperatingState.DEVICE_REBOOTED
    }

    fun onProcessRecreated() {
        _currentOperatingState.value = DeviceOperatingState.PROCESS_RECREATED
    }

    fun onDeviceUnlocked() {
        lockStateOverrideForTesting = false
        screenInteractiveOverrideForTesting = true
        _currentOperatingState.value = DeviceOperatingState.DEVICE_UNLOCKED
    }

    fun onScreenOnUnlocked() {
        lockStateOverrideForTesting = false
        screenInteractiveOverrideForTesting = true
        _currentOperatingState.value = DeviceOperatingState.SCREEN_ON_UNLOCKED
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

    /**
     * Requests the standard Android system lock screen dismissal via [KeyguardManager.requestDismissKeyguard].
     * NEVER collects, stores, or bypasses user PIN, password, pattern, or biometric credentials.
     */
    fun requestDismissKeyguardIfSupported(
        activity: Activity?,
        onDismissSucceeded: (() -> Unit)? = null,
        onDismissCancelledOrError: (() -> Unit)? = null
    ): Boolean {
        if (activity == null) return false
        val km = activity.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: return false
        if (!km.isKeyguardLocked) {
            onDeviceUnlocked()
            onDismissSucceeded?.invoke()
            return true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            km.requestDismissKeyguard(
                activity,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() {
                        onDeviceUnlocked()
                        onDismissSucceeded?.invoke()
                    }

                    override fun onDismissCancelled() {
                        onDismissCancelledOrError?.invoke()
                    }

                    override fun onDismissError() {
                        onDismissCancelledOrError?.invoke()
                    }
                }
            )
            return true
        }
        return false
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

