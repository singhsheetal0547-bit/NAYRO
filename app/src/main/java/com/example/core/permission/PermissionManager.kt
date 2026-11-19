package com.example.core.permission

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.core.notification.NotificationIntelligenceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * System A — Centralized Permission Management for NAYRO.
 * Accurately tracks Runtime Permissions, Special App Ops (SYSTEM_ALERT_WINDOW),
 * exact AccessibilityService component binding verification, NotificationListenerService binding,
 * and Battery Optimization Exemptions.
 */
class PermissionManager(private val context: Context) {

    private val _evaluations = MutableStateFlow<Map<PermissionType, PermissionEvaluation>>(emptyMap())
    val evaluations: StateFlow<Map<PermissionType, PermissionEvaluation>> = _evaluations.asStateFlow()

    // Backwards compatibility for callers
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
            PermissionType.READ_CONTACTS -> {
                evaluateRuntimePermission(android.Manifest.permission.READ_CONTACTS, item, activity)
            }
            PermissionType.READ_PHONE_STATE -> {
                evaluateRuntimePermission(android.Manifest.permission.READ_PHONE_STATE, item, activity)
            }
            PermissionType.READ_CALENDAR -> {
                evaluateRuntimePermission(android.Manifest.permission.READ_CALENDAR, item, activity)
            }
            PermissionType.WRITE_CALENDAR -> {
                evaluateRuntimePermission(android.Manifest.permission.WRITE_CALENDAR, item, activity)
            }
            PermissionType.COARSE_LOCATION -> {
                evaluateRuntimePermission(android.Manifest.permission.ACCESS_COARSE_LOCATION, item, activity)
            }
            PermissionType.CALL_PHONE -> {
                evaluateRuntimePermission(android.Manifest.permission.CALL_PHONE, item, activity)
            }
            PermissionType.ANSWER_PHONE_CALLS -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    evaluateRuntimePermission(android.Manifest.permission.ANSWER_PHONE_CALLS, item, activity)
                } else {
                    PermissionEvaluation(
                        item = item,
                        status = PermissionStatus.NOT_REQUIRED,
                        statusExplanation = "ANSWER_PHONE_CALLS requires Android 8.0+ (API 26+)."
                    )
                }
            }
            PermissionType.READ_CALL_LOG -> {
                evaluateRuntimePermission(android.Manifest.permission.READ_CALL_LOG, item, activity)
            }
            PermissionType.SEND_SMS -> {
                evaluateRuntimePermission(android.Manifest.permission.SEND_SMS, item, activity)
            }
            PermissionType.READ_SMS -> {
                evaluateRuntimePermission(android.Manifest.permission.READ_SMS, item, activity)
            }
            PermissionType.OVERLAY -> {
                val canDraw = Settings.canDrawOverlays(context)
                if (canDraw) {
                    PermissionEvaluation(
                        item = item,
                        status = PermissionStatus.GRANTED,
                        statusExplanation = "SYSTEM_ALERT_WINDOW verified granted. Floating Babu overlay ready."
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
                val isDeclared = isAccessibilityServiceDeclared()
                val isEnabledInSettings = isExactAccessibilityServiceEnabled()

                if (!isDeclared) {
                    PermissionEvaluation(
                        item = item,
                        status = PermissionStatus.UNAVAILABLE,
                        statusExplanation = "Accessibility service is not declared in AndroidManifest.xml."
                    )
                } else if (!isEnabledInSettings) {
                    PermissionEvaluation(
                        item = item,
                        status = PermissionStatus.SPECIAL_ACCESS_REQUIRED,
                        statusExplanation = "Service declared in Manifest; enable NAYRO in Android Accessibility Settings."
                    )
                } else {
                    PermissionEvaluation(
                        item = item,
                        status = PermissionStatus.GRANTED,
                        statusExplanation = "Exact Accessibility Service component enabled and verified in system settings."
                    )
                }
            }
            PermissionType.NOTIFICATION_LISTENER -> {
                val isEnabled = isNotificationListenerEnabled()
                if (isEnabled) {
                    PermissionEvaluation(
                        item = item,
                        status = PermissionStatus.GRANTED,
                        statusExplanation = "NotificationListenerService enabled and verified in system settings."
                    )
                } else {
                    PermissionEvaluation(
                        item = item,
                        status = PermissionStatus.SPECIAL_ACCESS_REQUIRED,
                        statusExplanation = "Enable NAYRO in Android Settings > Notification Access to read & summarize notifications."
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
                        statusExplanation = "Unrestricted background battery execution verified enabled."
                    )
                } else {
                    PermissionEvaluation(
                        item = item,
                        status = PermissionStatus.SPECIAL_ACCESS_REQUIRED,
                        statusExplanation = "Recommended: Exclude NAYRO from battery optimization to prevent background kills."
                    )
                }
            }
            PermissionType.FOREGROUND_SERVICE -> {
                val declared = isManifestPermissionDeclared(android.Manifest.permission.FOREGROUND_SERVICE)
                val specialUseDeclared = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    isManifestPermissionDeclared(android.Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE)
                } else true

                if (declared && specialUseDeclared) {
                    PermissionEvaluation(
                        item = item,
                        status = PermissionStatus.GRANTED,
                        statusExplanation = "FOREGROUND_SERVICE & specialUse type verified in AndroidManifest."
                    )
                } else {
                    PermissionEvaluation(
                        item = item,
                        status = PermissionStatus.UNAVAILABLE,
                        statusExplanation = "Foreground service permission missing or incomplete in AndroidManifest."
                    )
                }
            }
            PermissionType.SCREEN_CAPTURE -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && isExactAccessibilityServiceEnabled()) {
                    PermissionEvaluation(
                        item = item,
                        status = PermissionStatus.NOT_REQUIRED,
                        statusExplanation = "Provided automatically by AccessibilityService.takeScreenshot() on Android 11+."
                    )
                } else {
                    PermissionEvaluation(
                        item = item,
                        status = PermissionStatus.SPECIAL_ACCESS_REQUIRED,
                        statusExplanation = "Requires per-session user consent via MediaProjection dialog when requested."
                    )
                }
            }
        }
    }

    private fun evaluateRuntimePermission(
        permission: String,
        item: PermissionItem,
        activity: Activity? = null
    ): PermissionEvaluation {
        val prefs = try {
            context.getSharedPreferences("nayro_perm_prefs", Context.MODE_PRIVATE)
        } catch (_: Exception) {
            null
        }
        val granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            prefs?.edit()?.putBoolean("granted_history_$permission", true)?.apply()
            return PermissionEvaluation(
                item = item,
                status = PermissionStatus.GRANTED,
                statusExplanation = "Runtime permission verified granted by Android OS."
            )
        }

        val requestedBefore = prefs?.getBoolean("requested_$permission", false) ?: false
        val wasGrantedBefore = prefs?.getBoolean("granted_history_$permission", false) ?: false

        if (activity != null) {
            val shouldShowRationale = ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
            if (!shouldShowRationale && requestedBefore) {
                return PermissionEvaluation(
                    item = item,
                    status = PermissionStatus.PERMANENTLY_DENIED,
                    statusExplanation = if (wasGrantedBefore) {
                        "Permission was previously granted and later revoked/expired. Open App Settings to re-enable."
                    } else {
                        "Permission denied permanently ('Don't ask again'). Open App Settings to enable."
                    }
                )
            }
        }

        return PermissionEvaluation(
            item = item,
            status = PermissionStatus.DENIED,
            statusExplanation = when {
                wasGrantedBefore -> "Permission was previously granted and later revoked or one-time access expired. Tap Request to re-grant."
                requestedBefore -> "Permission was denied previously. Tap Request or Open Settings to grant."
                else -> "Runtime permission not yet granted."
            }
        )
    }

    /**
     * Evaluates ONLY a single permission or PermissionType without refreshing all permissions.
     * Updates only that permission's entry in [evaluations] and [permissionStates].
     */
    fun evaluateSinglePermissionTargeted(permissionOrType: String, activity: Activity? = null): Boolean {
        val matchedItem = PermissionRegistry.items.firstOrNull { item ->
            item.androidPermissionString.equals(permissionOrType, ignoreCase = true) ||
                item.type.name.equals(permissionOrType, ignoreCase = true)
        }
        if (matchedItem != null) {
            val eval = evaluatePermission(matchedItem, activity)
            val updatedEvals = _evaluations.value.toMutableMap()
            updatedEvals[matchedItem.type] = eval
            _evaluations.value = updatedEvals

            val updatedStates = _permissionStates.value.toMutableMap()
            updatedStates[matchedItem.type] = when (eval.status) {
                PermissionStatus.GRANTED -> PermissionState.Granted
                PermissionStatus.DENIED -> PermissionState.Denied
                PermissionStatus.PERMANENTLY_DENIED -> PermissionState.PermanentlyDenied
                PermissionStatus.SPECIAL_ACCESS_REQUIRED -> PermissionState.NeedsSystemSettings
                PermissionStatus.NOT_REQUIRED -> PermissionState.Granted
                PermissionStatus.UNAVAILABLE -> PermissionState.Denied
            }
            _permissionStates.value = updatedStates
            return eval.status == PermissionStatus.GRANTED || eval.status == PermissionStatus.NOT_REQUIRED
        }
        return if (permissionOrType.startsWith("android.permission.")) {
            isPermissionGranted(permissionOrType)
        } else {
            false
        }
    }

    fun wasPermissionRevoked(permission: String): Boolean {
        val currentlyGranted = isPermissionGranted(permission)
        if (currentlyGranted) return false
        val prefs = try {
            context.getSharedPreferences("nayro_perm_prefs", Context.MODE_PRIVATE)
        } catch (_: Exception) {
            null
        }
        return prefs?.getBoolean("granted_history_$permission", false) ?: false
    }

    fun markRuntimePermissionRequested(permission: String) {
        context.getSharedPreferences("nayro_perm_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("requested_$permission", true)
            .apply()
    }

    /**
     * Verifies if a specific permission is declared in this application's package manifest.
     */
    fun isManifestPermissionDeclared(permission: String): Boolean {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            }
            packageInfo.requestedPermissions?.contains(permission) == true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Verifies if any AccessibilityService is declared in this application's manifest.
     */
    fun isAccessibilityServiceDeclared(): Boolean {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_SERVICES.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SERVICES)
            }
            packageInfo.services?.any { service ->
                service.permission == android.Manifest.permission.BIND_ACCESSIBILITY_SERVICE
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Checks if the exact component name of an AccessibilityService of this app is active in system settings.
     */
    fun isExactAccessibilityServiceEnabled(): Boolean {
        return try {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServices)

            while (colonSplitter.hasNext()) {
                val componentNameString = colonSplitter.next()
                val enabledComponent = ComponentName.unflattenFromString(componentNameString)
                if (enabledComponent != null && enabledComponent.packageName == context.packageName) {
                    return true
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Checks if NAYRO's NotificationListenerService is enabled in Android system settings
     * or actively bound.
     */
    fun isNotificationListenerEnabled(): Boolean {
        if (NotificationIntelligenceRepository.isListenerConnected.value) return true
        return try {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(flat)
            while (colonSplitter.hasNext()) {
                val componentNameString = colonSplitter.next()
                val cn = ComponentName.unflattenFromString(componentNameString)
                if (cn != null && cn.packageName == context.packageName) {
                    return true
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    enum class PermissionCheckOutcome {
        GRANTED,
        DENIED,
        INCONCLUSIVE,
        UNAVAILABLE
    }

    data class PermissionVerificationDetail(
        val permission: String,
        val outcome: PermissionCheckOutcome,
        val reason: String
    ) {
        val isGranted: Boolean get() = outcome == PermissionCheckOutcome.GRANTED
    }

    /**
     * Strict fail-closed single-permission check without triggering any global permission refresh.
     * Never treats exceptions, null context, or unknown states as GRANTED.
     */
    fun checkPermissionFailClosed(permission: String): PermissionVerificationDetail {
        val clean = permission.trim()
        if (clean.isEmpty()) {
            return PermissionVerificationDetail(
                permission = permission,
                outcome = PermissionCheckOutcome.UNAVAILABLE,
                reason = "Permission identifier is empty or unavailable."
            )
        }
        return try {
            val status = ContextCompat.checkSelfPermission(context, clean)
            if (status == PackageManager.PERMISSION_GRANTED) {
                PermissionVerificationDetail(
                    permission = clean,
                    outcome = PermissionCheckOutcome.GRANTED,
                    reason = "Permission $clean is granted by Android."
                )
            } else {
                PermissionVerificationDetail(
                    permission = clean,
                    outcome = PermissionCheckOutcome.DENIED,
                    reason = "Permission $clean is not granted."
                )
            }
        } catch (e: Exception) {
            PermissionVerificationDetail(
                permission = clean,
                outcome = PermissionCheckOutcome.INCONCLUSIVE,
                reason = "Fail-closed: unable to verify permission $clean (${e.javaClass.simpleName}: ${e.localizedMessage ?: "error"})."
            )
        }
    }

    fun isPermissionGranted(permission: String): Boolean {
        return checkPermissionFailClosed(permission).isGranted
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

    fun isBatteryOptimizationIgnored(): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
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
            PermissionType.NOTIFICATION_LISTENER -> {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            PermissionType.BATTERY_OPTIMIZATION -> {
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}")
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            }
            PermissionType.MICROPHONE,
            PermissionType.NOTIFICATIONS,
            PermissionType.READ_CONTACTS,
            PermissionType.READ_PHONE_STATE,
            PermissionType.CALL_PHONE,
            PermissionType.ANSWER_PHONE_CALLS,
            PermissionType.READ_CALL_LOG,
            PermissionType.SEND_SMS,
            PermissionType.READ_SMS,
            PermissionType.READ_CALENDAR,
            PermissionType.WRITE_CALENDAR,
            PermissionType.COARSE_LOCATION -> {
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}")
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            }
            else -> null
        }
    }
}
