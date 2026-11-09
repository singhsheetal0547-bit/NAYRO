package com.example.core.permission

sealed class PermissionState {
    object Granted : PermissionState()
    object Denied : PermissionState()
    object PermanentlyDenied : PermissionState()
    object NeedsSystemSettings : PermissionState()
    object NotRequested : PermissionState()
}
