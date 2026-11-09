package com.example.core.permission

import android.os.Build
import android.provider.Settings

/**
 * Single source of truth registry for all permissions, system settings,
 * and service declarations required by NAYRO.
 */
object PermissionRegistry {

    val items: List<PermissionItem> = listOf(
        PermissionItem(
            type = PermissionType.MICROPHONE,
            permissionName = "Microphone (RECORD_AUDIO)",
            purpose = "Permits Babu voice assistant and local wake-word engine to listen to user voice commands.",
            category = PermissionCategory.RUNTIME,
            minAndroidSdk = 24,
            androidPermissionString = android.Manifest.permission.RECORD_AUDIO,
            settingsAction = Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            associatedCapabilities = listOf("MICROPHONE_ACCESS", "WAKE_WORD_READY", "VOICE_INTERACTION_READY")
        ),
        PermissionItem(
            type = PermissionType.NOTIFICATIONS,
            permissionName = "Post Notifications (POST_NOTIFICATIONS)",
            purpose = "Required on Android 13+ (API 33+) to alert the user of background agent execution, reminders, and status.",
            category = PermissionCategory.RUNTIME,
            minAndroidSdk = Build.VERSION_CODES.TIRAMISU,
            androidPermissionString = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                android.Manifest.permission.POST_NOTIFICATIONS
            } else null,
            settingsAction = Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            associatedCapabilities = listOf("NOTIFICATION_ACCESS", "BACKGROUND_AGENT")
        ),
        PermissionItem(
            type = PermissionType.OVERLAY,
            permissionName = "Display Over Other Apps (SYSTEM_ALERT_WINDOW)",
            purpose = "Enables Babu floating assistant icon and compact overlay to float above other Android applications.",
            category = PermissionCategory.SPECIAL_APP_OP,
            minAndroidSdk = 24,
            settingsAction = Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            associatedCapabilities = listOf("FLOATING_ASSISTANT")
        ),
        PermissionItem(
            type = PermissionType.ACCESSIBILITY,
            permissionName = "Accessibility Service (Android Automation)",
            purpose = "Optional user-authorized service for on-screen context reading and assisting user with UI navigation.",
            category = PermissionCategory.ACCESSIBILITY_SERVICE,
            minAndroidSdk = 24,
            settingsAction = Settings.ACTION_ACCESSIBILITY_SETTINGS,
            associatedCapabilities = listOf("ACCESSIBILITY", "DEVICE_AUTOMATION_READY")
        ),
        PermissionItem(
            type = PermissionType.BATTERY_OPTIMIZATION,
            permissionName = "Unrestricted Battery Usage (Doze Exemption)",
            purpose = "Prevents aggressive Android OEM battery managers from freezing the background agent lifecycle.",
            category = PermissionCategory.BATTERY_OPTIMIZATION,
            minAndroidSdk = 24,
            settingsAction = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            associatedCapabilities = listOf("BACKGROUND_AGENT", "WAKE_WORD_READY")
        ),
        PermissionItem(
            type = PermissionType.FOREGROUND_SERVICE,
            permissionName = "Foreground Service (Microphone)",
            purpose = "Mandatory manifest permission allowing continuous background agent standby notification and lifecycle.",
            category = PermissionCategory.FOREGROUND_SERVICE,
            minAndroidSdk = Build.VERSION_CODES.P,
            androidPermissionString = android.Manifest.permission.FOREGROUND_SERVICE,
            associatedCapabilities = listOf("BACKGROUND_AGENT")
        ),
        PermissionItem(
            type = PermissionType.SCREEN_CAPTURE,
            permissionName = "Screen Capture Consent (MediaProjection)",
            purpose = "User-confirmed session consent required for taking screen snapshots for future multimodal AI analysis.",
            category = PermissionCategory.MEDIA_PROJECTION,
            minAndroidSdk = 24,
            settingsAction = null,
            associatedCapabilities = listOf("SCREEN_CAPTURE")
        )
    )

    fun findByType(type: PermissionType): PermissionItem? = items.firstOrNull { it.type == type }
}
