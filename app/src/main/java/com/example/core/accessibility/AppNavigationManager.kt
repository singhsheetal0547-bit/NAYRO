package com.example.core.accessibility

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/**
 * Handles package resolution, launchable application discovery, and safe Intent navigation.
 */
object AppNavigationManager {

    private const val TAG = "AppNavigationMgr"

    // Canonical package mapping for common Android utilities
    private val knownAppPackages = mapOf(
        "chrome" to "com.android.chrome",
        "browser" to "com.android.chrome",
        "youtube" to "com.google.android.youtube",
        "yt" to "com.google.android.youtube",
        "whatsapp" to "com.whatsapp",
        "settings" to "com.android.settings",
        "setting" to "com.android.settings",
        "camera" to "com.google.android.GoogleCamera",
        "calculator" to "com.google.android.calculator",
        "maps" to "com.google.android.apps.maps",
        "map" to "com.google.android.apps.maps",
        "gmail" to "com.google.android.gm",
        "email" to "com.google.android.gm",
        "messages" to "com.google.android.apps.messaging",
        "message" to "com.google.android.apps.messaging",
        "sms" to "com.google.android.apps.messaging",
        "phone" to "com.google.android.dialer",
        "call" to "com.google.android.dialer",
        "dialer" to "com.google.android.dialer",
        "playstore" to "com.android.vending",
        "play store" to "com.android.vending",
        "clock" to "com.google.android.deskclock",
        "alarm" to "com.google.android.deskclock",
        "files" to "com.google.android.apps.nbu.files"
    )

    fun resolvePackage(context: Context, appNameQuery: String): String? {
        val normalized = appNameQuery.trim().lowercase()

        // 1. Direct dictionary match
        knownAppPackages[normalized]?.let { return it }

        // 2. Partial dictionary match
        for ((key, pkg) in knownAppPackages) {
            if (normalized.contains(key) || key.contains(normalized)) {
                return pkg
            }
        }

        // 3. Dynamic package manager inspection
        return try {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolvedApps = pm.queryIntentActivities(intent, 0)
            for (info in resolvedApps) {
                val label = info.loadLabel(pm).toString().lowercase()
                val pkgName = info.activityInfo.packageName.lowercase()
                if (label.contains(normalized) || normalized.contains(label) || pkgName.contains(normalized)) {
                    return info.activityInfo.packageName
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving package dynamically: ${e.message}")
            null
        }
    }

    fun launchApp(context: Context, packageName: String): Boolean {
        return try {
            val pm = context.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                Log.i(TAG, "Launched app: $packageName")
                true
            } else {
                Log.w(TAG, "Launch intent not found for package: $packageName")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch $packageName: ${e.message}")
            false
        }
    }

    fun openSettingsSection(context: Context, section: String): Boolean {
        val intentAction = when (section.lowercase().trim()) {
            "wifi", "wi-fi", "internet" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display", "screen" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound", "volume" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "apps", "applications" -> Settings.ACTION_APPLICATION_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }

        return try {
            val intent = Intent(intentAction).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open settings section '$section': ${e.message}")
            false
        }
    }
}
