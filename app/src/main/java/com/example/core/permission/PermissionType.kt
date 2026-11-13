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
        description = "Allows the persistent floating Babu icon and compact assistant panel to stay available over other apps.",
        isSpecial = true
    ),
    ACCESSIBILITY(
        title = "Accessibility Service",
        description = "Enables screen understanding and user-authorized Android UI interaction.",
        isSpecial = true
    ),
    NOTIFICATION_LISTENER(
        title = "Notification Listener Access",
        description = "Allows Babu to read, summarize, and classify incoming status bar notifications.",
        isSpecial = true
    ),
    NOTIFICATIONS(
        title = "Post Notifications",
        description = "Required on Android 13+ to display agent status, reminders, and background execution alerts.",
        isSpecial = false,
        androidPermission = android.Manifest.permission.POST_NOTIFICATIONS
    ),
    BATTERY_OPTIMIZATION(
        title = "Unrestricted Battery Usage",
        description = "Prevents Android OEM battery managers from prematurely stopping the background agent or wake engine.",
        isSpecial = true
    ),
    READ_CONTACTS(
        title = "Read Contacts",
        description = "Enables resolving contact names (e.g., 'Call Rahul' or 'Message Priya') to phone numbers.",
        isSpecial = false,
        androidPermission = android.Manifest.permission.READ_CONTACTS
    ),
    READ_PHONE_STATE(
        title = "Phone State Awareness",
        description = "Allows checking incoming/ongoing call state and automatically pausing wake-word listening during calls.",
        isSpecial = false,
        androidPermission = android.Manifest.permission.READ_PHONE_STATE
    ),
    READ_CALENDAR(
        title = "Read Calendar Events",
        description = "Allows Babu to inspect upcoming meetings and events on your device calendar.",
        isSpecial = false,
        androidPermission = android.Manifest.permission.READ_CALENDAR
    ),
    WRITE_CALENDAR(
        title = "Create Calendar Events",
        description = "Allows Babu to schedule new calendar events and appointments when requested.",
        isSpecial = false,
        androidPermission = android.Manifest.permission.WRITE_CALENDAR
    ),
    COARSE_LOCATION(
        title = "Approximate Location",
        description = "Provides local city/region context for location-aware queries and reminders.",
        isSpecial = false,
        androidPermission = android.Manifest.permission.ACCESS_COARSE_LOCATION
    ),
    FOREGROUND_SERVICE(
        title = "Background Agent Service",
        description = "Manifest permission allowing NAYRO background engine to remain active with a visible status notification.",
        isSpecial = false,
        androidPermission = android.Manifest.permission.FOREGROUND_SERVICE
    ),
    SCREEN_CAPTURE(
        title = "MediaProjection Screen Capture",
        description = "Optional on-demand consent for capturing screen snapshots on devices without Accessibility screenshot support.",
        isSpecial = true
    ),
    CALL_PHONE(
        title = "Place Phone Calls",
        description = "Required to initiate actual outgoing phone calls via ACTION_CALL after your confirmation.",
        isSpecial = false,
        androidPermission = android.Manifest.permission.CALL_PHONE
    ),
    ANSWER_PHONE_CALLS(
        title = "Answer & End Phone Calls",
        description = "Required on Android 8.0+ (API 26+) to answer or end calls programmatically via TelecomManager.",
        isSpecial = false,
        androidPermission = android.Manifest.permission.ANSWER_PHONE_CALLS
    ),
    READ_CALL_LOG(
        title = "Read Call History",
        description = "Permits reading incoming, outgoing, and missed call records when Call Log access is allowed.",
        isSpecial = false,
        androidPermission = android.Manifest.permission.READ_CALL_LOG
    ),
    SEND_SMS(
        title = "Send SMS Messages",
        description = "Permits transmitting SMS messages directly via Android SmsManager after your explicit confirmation.",
        isSpecial = false,
        androidPermission = android.Manifest.permission.SEND_SMS
    ),
    READ_SMS(
        title = "Read SMS Inbox",
        description = "Permits reading SMS inbox messages, unread texts, OTP codes, and bank alerts via Telephony.Sms.",
        isSpecial = false,
        androidPermission = android.Manifest.permission.READ_SMS
    )
}
