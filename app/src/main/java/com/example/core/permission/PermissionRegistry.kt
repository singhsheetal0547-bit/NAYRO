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
            associatedCapabilities = listOf("MICROPHONE_ACCESS", "WAKE_WORD_READY", "VOICE_INTERACTION_READY"),
            requirementLevel = PermissionRequirementLevel.CORE_AGENT,
            featureDependency = "Voice Commands, Local 'Hey Babu' Wake Word, Floating Voice Input",
            verificationMethod = "ContextCompat.checkSelfPermission(RECORD_AUDIO)",
            fallbackBehavior = "Text chat, quick actions, and touch-driven agent execution remain fully functional."
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
            associatedCapabilities = listOf("NOTIFICATION_ACCESS", "BACKGROUND_AGENT"),
            requirementLevel = PermissionRequirementLevel.CORE_AGENT,
            featureDependency = "Background Service Status Notification, Task Completion Alerts, Reminders",
            verificationMethod = "ContextCompat.checkSelfPermission(POST_NOTIFICATIONS) on API 33+",
            fallbackBehavior = "Task updates and alerts are displayed inside the in-app Control Center and Activity screen."
        ),
        PermissionItem(
            type = PermissionType.OVERLAY,
            permissionName = "Display Over Other Apps (SYSTEM_ALERT_WINDOW)",
            purpose = "Enables Babu floating assistant icon and compact overlay panel to float above other Android applications.",
            category = PermissionCategory.SPECIAL_APP_OP,
            minAndroidSdk = 24,
            settingsAction = Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            associatedCapabilities = listOf("FLOATING_ASSISTANT"),
            requirementLevel = PermissionRequirementLevel.CORE_AGENT,
            featureDependency = "Andee-Style Floating Babu Bubble & Compact Overlay Assistant",
            verificationMethod = "Settings.canDrawOverlays(context)",
            fallbackBehavior = "Babu assistant remains accessible inside the main NAYRO app and via notification actions."
        ),
        PermissionItem(
            type = PermissionType.ACCESSIBILITY,
            permissionName = "Accessibility Service (Android Automation)",
            purpose = "User-authorized service for on-screen UI tree reading, clicking, typing, scrolling, and cross-app workflows.",
            category = PermissionCategory.ACCESSIBILITY_SERVICE,
            minAndroidSdk = 24,
            settingsAction = Settings.ACTION_ACCESSIBILITY_SETTINGS,
            associatedCapabilities = listOf("ACCESSIBILITY", "DEVICE_AUTOMATION_READY"),
            requirementLevel = PermissionRequirementLevel.CORE_AGENT,
            featureDependency = "Screen Reading, UI Element Click/Type/Scroll, Multi-Step Cross-App Automation",
            verificationMethod = "Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES component check & service connection",
            fallbackBehavior = "Falls back to standard Android Intents (launching apps, opening settings, dialer, SMS composer)."
        ),
        PermissionItem(
            type = PermissionType.NOTIFICATION_LISTENER,
            permissionName = "Notification Access (NotificationListenerService)",
            purpose = "Allows NAYRO to inspect, summarize, classify, and extract action items from incoming status bar notifications.",
            category = PermissionCategory.NOTIFICATION_LISTENER_SERVICE,
            minAndroidSdk = 24,
            settingsAction = Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
            associatedCapabilities = listOf("NOTIFICATION_ACCESS"),
            requirementLevel = PermissionRequirementLevel.OPTIONAL_FEATURE,
            featureDependency = "Notification Summarization, Priority Triage, Notification-to-Task Automation",
            verificationMethod = "Settings.Secure('enabled_notification_listeners') package component check",
            fallbackBehavior = "Users can paste message text or read current screen notifications via Accessibility."
        ),
        PermissionItem(
            type = PermissionType.BATTERY_OPTIMIZATION,
            permissionName = "Unrestricted Battery Usage (Doze Exemption)",
            purpose = "Prevents aggressive Android OEM battery managers from freezing the background agent lifecycle.",
            category = PermissionCategory.BATTERY_OPTIMIZATION,
            minAndroidSdk = 24,
            settingsAction = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            associatedCapabilities = listOf("BACKGROUND_AGENT", "WAKE_WORD_READY"),
            requirementLevel = PermissionRequirementLevel.CORE_AGENT,
            featureDependency = "Continuous Background Agent Survival & Standby Wake Detection",
            verificationMethod = "PowerManager.isIgnoringBatteryOptimizations(packageName)",
            fallbackBehavior = "Background service may be paused by OS during deep Doze; manual activation in app always works."
        ),
        PermissionItem(
            type = PermissionType.READ_CONTACTS,
            permissionName = "Contacts Access (READ_CONTACTS)",
            purpose = "Resolves contact names to phone numbers when you ask Babu to call or message a named contact.",
            category = PermissionCategory.RUNTIME,
            minAndroidSdk = 24,
            androidPermissionString = android.Manifest.permission.READ_CONTACTS,
            settingsAction = Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            associatedCapabilities = listOf("COMMUNICATION_TOOLS"),
            requirementLevel = PermissionRequirementLevel.OPTIONAL_FEATURE,
            featureDependency = "Contact Name Resolution (RESOLVE_CONTACT, MAKE_PHONE_CALL, SEND_SMS)",
            verificationMethod = "ContextCompat.checkSelfPermission(READ_CONTACTS)",
            fallbackBehavior = "Opens dialer or messaging app where user can pick the recipient directly."
        ),
        PermissionItem(
            type = PermissionType.READ_PHONE_STATE,
            permissionName = "Phone State Access (READ_PHONE_STATE)",
            purpose = "Detects active or ringing phone calls so Babu pauses wake-word listening and supports call status checks.",
            category = PermissionCategory.RUNTIME,
            minAndroidSdk = 24,
            androidPermissionString = android.Manifest.permission.READ_PHONE_STATE,
            settingsAction = Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            associatedCapabilities = listOf("COMMUNICATION_TOOLS"),
            requirementLevel = PermissionRequirementLevel.OPTIONAL_FEATURE,
            featureDependency = "Call State Awareness (CHECK_INCOMING_CALL) & Call Audio Conflict Prevention",
            verificationMethod = "ContextCompat.checkSelfPermission(READ_PHONE_STATE)",
            fallbackBehavior = "Uses standard Android AudioFocus change callbacks to pause audio playback/recording."
        ),
        PermissionItem(
            type = PermissionType.READ_CALENDAR,
            permissionName = "Read Calendar (READ_CALENDAR)",
            purpose = "Allows Babu to read upcoming calendar events and check schedule conflicts.",
            category = PermissionCategory.RUNTIME,
            minAndroidSdk = 24,
            androidPermissionString = android.Manifest.permission.READ_CALENDAR,
            settingsAction = Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            associatedCapabilities = listOf("CALENDAR_TOOLS"),
            requirementLevel = PermissionRequirementLevel.OPTIONAL_FEATURE,
            featureDependency = "Calendar Schedule Inspection (READ_CALENDAR_EVENTS)",
            verificationMethod = "ContextCompat.checkSelfPermission(READ_CALENDAR)",
            fallbackBehavior = "Uses NAYRO's local database reminders and notes."
        ),
        PermissionItem(
            type = PermissionType.WRITE_CALENDAR,
            permissionName = "Modify Calendar (WRITE_CALENDAR)",
            purpose = "Allows Babu to add new events directly to your Android system calendar.",
            category = PermissionCategory.RUNTIME,
            minAndroidSdk = 24,
            androidPermissionString = android.Manifest.permission.WRITE_CALENDAR,
            settingsAction = Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            associatedCapabilities = listOf("CALENDAR_TOOLS"),
            requirementLevel = PermissionRequirementLevel.OPTIONAL_FEATURE,
            featureDependency = "Direct Calendar Event Creation (CREATE_CALENDAR_EVENT)",
            verificationMethod = "ContextCompat.checkSelfPermission(WRITE_CALENDAR)",
            fallbackBehavior = "Creates local NAYRO reminders or launches the system Calendar insert intent."
        ),
        PermissionItem(
            type = PermissionType.COARSE_LOCATION,
            permissionName = "Approximate Location (ACCESS_COARSE_LOCATION)",
            purpose = "Provides approximate device location when requested by location-aware queries.",
            category = PermissionCategory.RUNTIME,
            minAndroidSdk = 24,
            androidPermissionString = android.Manifest.permission.ACCESS_COARSE_LOCATION,
            settingsAction = Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            associatedCapabilities = listOf("LOCATION_TOOLS"),
            requirementLevel = PermissionRequirementLevel.OPTIONAL_FEATURE,
            featureDependency = "Device Location Context (GET_CURRENT_LOCATION)",
            verificationMethod = "ContextCompat.checkSelfPermission(ACCESS_COARSE_LOCATION)",
            fallbackBehavior = "User can state city or location directly in chat/voice prompts."
        ),
        PermissionItem(
            type = PermissionType.FOREGROUND_SERVICE,
            permissionName = "Foreground Service (Microphone & SpecialUse)",
            purpose = "Mandatory manifest declaration allowing persistent background agent standby notification and lifecycle.",
            category = PermissionCategory.FOREGROUND_SERVICE,
            minAndroidSdk = Build.VERSION_CODES.P,
            androidPermissionString = android.Manifest.permission.FOREGROUND_SERVICE,
            associatedCapabilities = listOf("BACKGROUND_AGENT"),
            requirementLevel = PermissionRequirementLevel.CORE_AGENT,
            featureDependency = "NayroForegroundService & NayroFloatingService",
            verificationMethod = "PackageManager.GET_PERMISSIONS manifest verification",
            fallbackBehavior = "Agent executes while NAYRO is open in the foreground."
        ),
        PermissionItem(
            type = PermissionType.SCREEN_CAPTURE,
            permissionName = "Screen Capture Consent (MediaProjection)",
            purpose = "On-demand session consent for screen capture on devices below Android 11 without Accessibility screenshot support.",
            category = PermissionCategory.MEDIA_PROJECTION,
            minAndroidSdk = 24,
            settingsAction = null,
            associatedCapabilities = listOf("SCREEN_CAPTURE"),
            requirementLevel = PermissionRequirementLevel.OPTIONAL_FEATURE,
            featureDependency = "Legacy Screen Capture Fallback",
            verificationMethod = "On-demand MediaProjectionManager user consent dialog",
            fallbackBehavior = "Uses AccessibilityService.takeScreenshot() on Android 11+ or UI node hierarchy inspection."
        )
    )

    fun findByType(type: PermissionType): PermissionItem? = items.firstOrNull { it.type == type }

    fun getCorePermissions(): List<PermissionItem> =
        items.filter { it.requirementLevel == PermissionRequirementLevel.CORE_AGENT }

    fun getOptionalPermissions(): List<PermissionItem> =
        items.filter { it.requirementLevel == PermissionRequirementLevel.OPTIONAL_FEATURE }
}
