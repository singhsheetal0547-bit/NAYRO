package com.example.core.system

import android.Manifest
import android.os.Build

data class AndroidCompatibilityReport(
    val sdkInt: Int,
    val androidVersionLabel: String,
    val manufacturer: String,
    val isSupportedAndroidVersion: Boolean,
    val requiresPostNotificationsPermission: Boolean,
    val requiresTypedForegroundServicePermissions: Boolean,
    val requiresExactAlarmPermissionCheck: Boolean,
    val supportsCallScreeningRole: Boolean,
    val usesScopedStorageDownloads: Boolean,
    val isAggressiveBackgroundOem: Boolean,
    val requiredRuntimePermissionsForCoreVoice: List<String>,
    val oemGuidance: String
)

/**
 * Android 10 (API 29) through Android 16 (API 36) Compatibility & Policy Engine for NAYRO.
 * Centralizes API-level permission rules, Foreground Service type requirements,
 * Scoped Storage rules, and OEM background battery guidance (Realme/ColorOS, Xiaomi, Oppo, Vivo, Samsung).
 */
object AndroidCompatibilityManager {

    private val AGGRESSIVE_OEMS = setOf(
        "realme", "oppo", "oneplus", "xiaomi", "redmi", "poco", "vivo", "iqoo", "huawei", "honor", "samsung"
    )

    fun evaluate(
        sdkInt: Int = Build.VERSION.SDK_INT,
        manufacturer: String = Build.MANUFACTURER ?: "Android"
    ): AndroidCompatibilityReport {
        val mfgLower = manufacturer.lowercase()
        val isAggressiveOem = AGGRESSIVE_OEMS.any { mfgLower.contains(it) }
        val requiresPostNotifications = sdkInt >= 33 // Android 13 (Tiramisu)
        val requiresTypedFgs = sdkInt >= 34          // Android 14 (UpsideDownCake)
        val requiresExactAlarm = sdkInt >= 31        // Android 12 (S)
        val supportsCallScreening = sdkInt >= 29     // Android 10 (Q)
        val usesScopedStorage = sdkInt >= 29         // Android 10+

        val versionLabel = when {
            sdkInt >= 36 -> "Android 16 (API $sdkInt)"
            sdkInt == 35 -> "Android 15 (API 35)"
            sdkInt == 34 -> "Android 14 (API 34)"
            sdkInt == 33 -> "Android 13 (API 33)"
            sdkInt == 32 -> "Android 12L (API 32)"
            sdkInt == 31 -> "Android 12 (API 31)"
            sdkInt == 30 -> "Android 11 (API 30)"
            sdkInt == 29 -> "Android 10 (API 29)"
            else -> "Android API $sdkInt"
        }

        val coreVoicePerms = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (requiresPostNotifications) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val oemGuidance = when {
            mfgLower.contains("realme") || mfgLower.contains("oppo") || mfgLower.contains("oneplus") ->
                "ColorOS/Realme UI detected: Enable 'Allow background activity' and disable battery optimization for reliable background voice."
            mfgLower.contains("xiaomi") || mfgLower.contains("redmi") || mfgLower.contains("poco") ->
                "MIUI/HyperOS detected: Enable Autostart and set Battery Saver to 'No restrictions' for background voice."
            mfgLower.contains("vivo") || mfgLower.contains("iqoo") ->
                "FuntouchOS detected: Enable High Background Power Consumption for NAYRO."
            mfgLower.contains("samsung") ->
                "One UI detected: Set NAYRO battery mode to 'Unrestricted' so background wake listening is not put to sleep."
            else ->
                "Standard Android power management ($versionLabel). Keep microphone & notification permissions enabled for background voice."
        }

        return AndroidCompatibilityReport(
            sdkInt = sdkInt,
            androidVersionLabel = versionLabel,
            manufacturer = manufacturer,
            isSupportedAndroidVersion = sdkInt >= 29,
            requiresPostNotificationsPermission = requiresPostNotifications,
            requiresTypedForegroundServicePermissions = requiresTypedFgs,
            requiresExactAlarmPermissionCheck = requiresExactAlarm,
            supportsCallScreeningRole = supportsCallScreening,
            usesScopedStorageDownloads = usesScopedStorage,
            isAggressiveBackgroundOem = isAggressiveOem,
            requiredRuntimePermissionsForCoreVoice = coreVoicePerms,
            oemGuidance = oemGuidance
        )
    }
}
