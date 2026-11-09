package com.example.core.accessibility

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class AccessibilityConnectionState {
    DECLARED,
    ENABLED,
    CONNECTED,
    OPERATIONAL,
    DISCONNECTED,
    NOT_AVAILABLE
}

/**
 * Monitors and manages live connection state of the NAYRO Accessibility Service.
 */
object AccessibilityConnectionManager {

    private val _connectionState = MutableStateFlow(AccessibilityConnectionState.DECLARED)
    val connectionState: StateFlow<AccessibilityConnectionState> = _connectionState

    private val _lastActivePackage = MutableStateFlow<String?>(null)
    val lastActivePackage: StateFlow<String?> = _lastActivePackage

    fun onServiceConnected(service: NayroAccessibilityService) {
        _connectionState.value = AccessibilityConnectionState.CONNECTED
        _connectionState.value = AccessibilityConnectionState.OPERATIONAL
    }

    fun onServiceDisconnected() {
        _connectionState.value = AccessibilityConnectionState.DISCONNECTED
    }

    fun updateActivePackage(packageName: String?) {
        if (!packageName.isNullOrBlank()) {
            _lastActivePackage.value = packageName
        }
    }

    fun checkStatus(context: Context): AccessibilityConnectionState {
        if (NayroAccessibilityService.instance != null) {
            _connectionState.value = AccessibilityConnectionState.OPERATIONAL
            return AccessibilityConnectionState.OPERATIONAL
        }

        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        if (am == null) {
            _connectionState.value = AccessibilityConnectionState.NOT_AVAILABLE
            return AccessibilityConnectionState.NOT_AVAILABLE
        }

        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""

        val expectedComponent = "${context.packageName}/${NayroAccessibilityService::class.java.canonicalName}"
        val isEnabledInSettings = enabledServices.contains(context.packageName) &&
                (enabledServices.contains(NayroAccessibilityService::class.java.simpleName) || enabledServices.contains(expectedComponent))

        val state = if (isEnabledInSettings) {
            AccessibilityConnectionState.ENABLED
        } else {
            AccessibilityConnectionState.DISCONNECTED
        }
        _connectionState.value = state
        return state
    }

    fun createSettingsIntent(): Intent {
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }
}
