package com.example.core.permission

/**
 * Standardized status categories for all Android permissions and settings in NAYRO.
 */
enum class PermissionStatus {
    GRANTED,
    DENIED,
    PERMANENTLY_DENIED,
    NOT_REQUIRED,
    SPECIAL_ACCESS_REQUIRED,
    UNAVAILABLE
}

/**
 * High-level permission category classification.
 */
enum class PermissionCategory {
    RUNTIME,
    SPECIAL_APP_OP,
    ACCESSIBILITY_SERVICE,
    MEDIA_PROJECTION,
    BATTERY_OPTIMIZATION,
    FOREGROUND_SERVICE
}
