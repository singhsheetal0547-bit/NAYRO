package com.example.permission

import android.content.Context
import com.example.core.permission.PermissionManager

/**
 * PermissionSetupManager in `com.example.permission`.
 * Integrates with `PermissionRegistry` to audit the current state of system permissions,
 * provides methods to check individual permission status (`checkPermissionStatus`,
 * `getPermissionEvaluation`, `isPermissionGranted`), and handles creating and triggering
 * the `Intent` for special access settings such as `Settings.ACTION_MANAGE_OVERLAY_PERMISSION`
 * (`createSpecialAccessIntent`, `triggerSpecialAccessSettings`, `triggerOverlayPermissionSettings`).
 */
class PermissionSetupManager(
    context: Context? = null,
    permissionManager: PermissionManager? = context?.let { PermissionManager(it) }
) : com.example.core.permission.PermissionSetupManager(context, permissionManager)
