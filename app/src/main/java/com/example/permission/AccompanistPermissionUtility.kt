package com.example.permission

import android.content.Context
import androidx.compose.runtime.Composable
import com.example.core.permission.NayroAccompanistPermissionController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState

/**
 * Accompanist Permissions Utility in `com.example.permission`.
 *
 * Bridges Accompanist runtime permission requests (`android.Manifest.permission.RECORD_AUDIO`)
 * and system Accessibility Service verification / Settings navigation
 * (`android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS`).
 */
class AccompanistPermissionUtility(
    context: Context? = null
) : com.example.core.permission.AccompanistPermissionUtility(context)

typealias AccompanistPermissionsUtility = AccompanistPermissionUtility

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun rememberMicrophonePermissionState(
    onPermissionResult: (Boolean) -> Unit = {}
): PermissionState = com.example.core.permission.rememberMicrophonePermissionState(onPermissionResult)

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun rememberNayroPermissionsHandler(
    onMicrophonePermissionResult: (Boolean) -> Unit = {},
    onPermissionsRefreshed: (isMicGranted: Boolean, isAccessibilityEnabled: Boolean) -> Unit = { _, _ -> }
): NayroAccompanistPermissionController =
    com.example.core.permission.rememberNayroPermissionsHandler(
        onMicrophonePermissionResult = onMicrophonePermissionResult,
        onPermissionsRefreshed = onPermissionsRefreshed
    )
