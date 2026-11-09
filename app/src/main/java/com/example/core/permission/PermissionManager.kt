package com.example.core.permission

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Centralized Permission Management System for NAYRO.
 * Manages runtime permissions, special system settings intents, and state detection.
 */
class PermissionManager(private val context: Context) {

    private val _permissionStates = MutableStateFlow<Map<PermissionType, PermissionState>>(emptyMap())
    val permissionStates: StateFlow<Map<PermissionType, PermissionState>> = _permissionStates

    init {
        refreshAllPermissions()
    }

    fun refreshAllPermissions() {
        val map = mutableMapOf<PermissionType, PermissionState>()
        PermissionType.values().forEach { type ->
            map[type] = checkPermissionStatus(type)
        }
        _permissionStates.value = map
    }

    fun checkPermissionStatus(type: PermissionType): PermissionState {
        return when (type) {
            PermissionType.MICROPHONE -> checkStandard(android.Manifest.permission.RECORD_AUDIO)
            PermissionType.NOTIFICATIONS -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    checkStandard(android.Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    PermissionState.Granted
                }
            }
            PermissionType.FOREGROUND_SERVICE -> PermissionState.Granted // Manifest declared
            PermissionType.OVERLAY -> {
                if (Settings.canDrawOverlays(context)) PermissionState.Granted else PermissionState.NeedsSystemSettings
            }
            PermissionType.BATTERY_OPTIMIZATION -> {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                if (powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true) {
                    PermissionState.Granted
                } else {
                    PermissionState.NeedsSystemSettings
                }
            }
            PermissionType.ACCESSIBILITY -> {
                val enabled = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: ""
                if (enabled.contains(context.packageName)) PermissionState.Granted else PermissionState.NeedsSystemSettings
            }
            PermissionType.SCREEN_CAPTURE -> PermissionState.NotRequested
        }
    }

    private fun checkStandard(permission: String): PermissionState {
        val result = ContextCompat.checkSelfPermission(context, permission)
        return if (result == PackageManager.PERMISSION_GRANTED) {
            PermissionState.Granted
        } else {
            PermissionState.Denied
        }
    }

    fun createSettingsIntent(type: PermissionType): Intent? {
        return when (type) {
            PermissionType.OVERLAY -> Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            PermissionType.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            PermissionType.BATTERY_OPTIMIZATION -> Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}")
            )
            PermissionType.MICROPHONE, PermissionType.NOTIFICATIONS -> Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            )
            else -> null
        }
    }
}
