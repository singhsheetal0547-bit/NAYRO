package com.example.core.permission

enum class PermissionType(
    val title: String,
    val description: String,
    val isSpecial: Boolean = false,
    val androidPermission: String? = null
) {
    MICROPHONE(
        title = "Microphone Access",
        description = "Required for local wake-word detection and voice commands with Babu.",
        isSpecial = false,
        androidPermission = android.Manifest.permission.RECORD_AUDIO
    ),
    OVERLAY(
        title = "Floating Assistant Overlay",
        description = "Allows the persistent floating Babu icon to stay available over other apps.",
        isSpecial = true
    ),
    ACCESSIBILITY(
        title = "Accessibility Service",
        description = "Enables screen understanding and authorized Android application interaction.",
        isSpecial = true
    ),
    NOTIFICATIONS(
        title = "Notification Assistance",
        description = "Required to display agent status, reminders, and process assistant alerts.",
        isSpecial = false,
        androidPermission = android.Manifest.permission.POST_NOTIFICATIONS
    ),
    FOREGROUND_SERVICE(
        title = "Background Agent Service",
        description = "Allows NAYRO background engine to remain active and handle wake events.",
        isSpecial = false,
        androidPermission = android.Manifest.permission.FOREGROUND_SERVICE
    ),
    SCREEN_CAPTURE(
        title = "MediaProjection Screen Capture",
        description = "Optional permission for taking real-time screen snapshots for multimodal AI understanding.",
        isSpecial = true
    ),
    BATTERY_OPTIMIZATION(
        title = "Unrestricted Battery Usage",
        description = "Prevents Android OEM battery managers from prematurely killing the wake-word engine.",
        isSpecial = true
    )
}
