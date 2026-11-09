package com.example.core.permission

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * System A — Centralized Permission Management for NAYRO.
 * Accurately tracks Runtime Permissions, Special App Ops (SYSTEM_ALERT_WINDOW),
 * Accessibility Services, and Battery Optimization Exemptions.
 */
class PermissionManager(private val context: Context) {

    private val _evaluations = MutableStateFlow<Map<PermissionType, PermissionEvaluation>>(emptyMap())
    val evaluations: StateFlow<Map<PermissionType, PermissionEvaluation>> = _evaluations.asStateFlow()

    // Backwards compatibility for Step 01 callers
    private val _permissionStates = MutableStateFlow<Map<PermissionType, PermissionState>>(emptyMap())
    val permissionStates: StateFlow<Map<PermissionType, PermissionState>> = _permissionStates.asStateFlow()

    init {
        refreshAllPermissions()
    }

    /**
     * Evaluates all registered permissions against Android System APIs.
     */
    fun refreshAllPermissions(activity: Activity? = null) {
        val evalMap = mutableMapOf<PermissionType, PermissionEvaluation>()
        val stateMap = mutableMapOf<PermissionType, PermissionState>()

        for (item in PermissionRegistry.items) {
            val eval = evaluatePermission(item, activity)
            evalMap[item.type] = eval

            stateMap[item.type] = when (eval.status) {
                PermissionStatus.GRANTED -> PermissionState.Granted
                PermissionStatus.DENIED -> PermissionState.Denied
                PermissionStatus.PERMANENTLY_DENIED -> PermissionState.PermanentlyDenied
                PermissionStatus.SPECIAL_ACCESS_REQUIRED -> PermissionState.NeedsSystemSettings
                PermissionStatus.NOT_REQUIRED -> PermissionState.Granted
                PermissionStatus.UNAVAILABLE -> PermissionState.Denied
            }
        }

        _evaluations.value = evalMap
        _permissionStates.value = stateMap
    }

    fun evaluatePermission(item: PermissionItem, activity: Activity? = null): PermissionEvaluation {
        if (Build.VERSION.SDK_INT < item.minAndroidSdk) {
            return PermissionEvaluation(
                item = item,
                status = PermissionStatus.NOT_REQUIRED,
                statusExplanation = "Not required on Android ${Build.VERSION.SDK_INT} (Requires API ${item.minAndroidSdk}+)."
            )
        }

        return when (item.type) {
            PermissionType.MICROPHONE -> {
                evaluateRuntimePermission(android.Manifest.permission.RECORD_AUDIO, item, activity)
            }
            PermissionType.NOTIFICATIONS -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    evaluateRuntimePermission(android.Manifest.permission.POST_NOTIFICATIONS, item, activity)
                } else {
                    PermissionEvaluation(
                        item = item,
                        status = PermissionStatus.NOT_REQUIRED,
                        statusExplanation = "Granted automatically on Android 12 and below."
                    )
                }
            }
            PermissionType.OVERLAY -> {
                val canDraw = Settings.canDrawOverlays(context)
                if (canDraw) {
                    PermissionEvaluation(
                        item = item,
                        status = PermissionStatus.GRANTED,
                        statusExplanation = "SYSTEM_ALERT_WINDOW granted. Floating assistant can overlay anywhere."
                    )
                } else {
                    PermissionEvaluation(
                        item = item,
                        status = PermissionStatus.SPECIAL_ACCESS_REQUIRED,
                        statusExplanation = "Requires manual toggle in Android Settings > Display over other apps."
                    )
                }
            }
            PermissionType.ACCESSIBILITY -> {
                val isEnabled = isAccessibilityServiceEnabled()
                if (isEnabled) {
                    PermissionEvaluation(
                        item = item,
                        status = PermissionStatus.GRANTED,
                        statusExplanation = "Accessibility service authorized and connected."
                    )
                } else {
                    PermissionEvaluation(
                        item = item,
                        status = PermissionStatus.SPECIAL_ACCESS_REQUIRED,
                        statusExplanation = "Must be enabled manually by the user in Android Accessibility Settings."
                    )
                }
            }
            PermissionType.BATTERY_OPTIMIZATION -> {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                val isIgnoring = powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
                if (isIgnoring) {
                    PermissionEvaluation(
                        item = item,
                        status = PermissionStatus.GRANTED,
                        statusExplanation = "Unrestricted background battery execution enabled."
                    )
                } else {
                    PermissionEvaluation(
                        item = item,
                        status = PermissionStatus.SPECIAL_ACCESS_REQUIRED,
                        statusExplanation = "Recommended: Exclude from battery optimization to prevent background kills."
                    )
                }
            }
            PermissionType.FOREGROUND_SERVICE -> {
                // Foreground service permissions are declared in manifest and granted install-time
                val check = ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.FOREGROUND_SERVICE
                )
                if (check == PackageManager.PERMISSION_GRANTED) {
                    PermissionEvaluation(
                        item = item,
                        status = PermissionStatus.GRANTED,
                        statusExplanation = "Foreground service permission declared in manifest and active."
                    )
                } else {
                    PermissionEvaluation(
                        item = item,
                        status = PermissionStatus.GRANTED,
                        statusExplanation = "Manifest declared install-time foreground service."
                    )
                }
            }
            PermissionType.SCREEN_CAPTURE -> {
                PermissionEvaluation(
                    item = item,
                    status = PermissionStatus.SPECIAL_ACCESS_REQUIRED,
                    statusExplanation = "Requires active user consent via MediaProjection dialog on-demand."
                )
            }
        }
    }

    private fun evaluateRuntimePermission(
        permission: String,
        item: PermissionItem,
        activity: Activity? = null
    ): PermissionEvaluation {
        val granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            return PermissionEvaluation(
                item = item,
                status = PermissionStatus.GRANTED,
                statusExplanation = "Runtime permission granted."
            )
        }

        if (activity != null) {
            val shouldShowRationale = ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
            val prefs = context.getSharedPreferences("nayro_perm_prefs", Context.MODE_PRIVATE)
            val requestedBefore = prefs.getBoolean("requested_$permission", false)

            if (!shouldShowRationale && requestedBefore) {
                return PermissionEvaluation(
                    item = item,
                    status = PermissionStatus.PERMANENTLY_DENIED,
                    statusExplanation = "Permission denied permanently ('Don't ask again'). Open App Settings to enable."
                )
            }
        }

        return PermissionEvaluation(
            item = item,
            status = PermissionStatus.DENIED,
            statusExplanation = "Runtime permission not yet granted."
        )
    }

    fun markRuntimePermissionRequested(permission: String) {
        context.getSharedPreferences("nayro_perm_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("requested_$permission", true)
            .apply()
    }

    fun isAccessibilityServiceEnabled(): Boolean {
        return try {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            enabledServices.contains(context.packageName)
        } catch (e: Exception) {
            false
        }
    }

    fun canDrawOverlays(): Boolean {
        return Settings.canDrawOverlays(context)
    }

    fun isMicrophoneGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isNotificationGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * Creates direct Intent to the exact Android System Settings screen for a permission.
     */
    fun createSettingsIntent(type: PermissionType): Intent? {
        return when (type) {
            PermissionType.OVERLAY -> {
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            }
            PermissionType.ACCESSIBILITY -> {
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            PermissionType.BATTERY_OPTIMIZATION -> {
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}")
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            }
            PermissionType.MICROPHONE, PermissionType.NOTIFICATIONS -> {
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}")
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            }
            else -> null
        }
    }
}
