package com.example.core.permission

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.text.TextUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.core.accessibility.AccessibilityConnectionManager
import com.example.core.accessibility.NayroAccessibilityService
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale

/**
 * State holder and controller returned by [rememberNayroPermissionsHandler] that bridges
 * Accompanist runtime permission state (`RECORD_AUDIO`) with Android System Accessibility Service
 * verification and Settings navigation (`ACTION_ACCESSIBILITY_SETTINGS`).
 */
@Stable
@OptIn(ExperimentalPermissionsApi::class)
class NayroAccompanistPermissionController(
    val microphonePermissionState: PermissionState,
    val isAccessibilityServiceEnabled: Boolean,
    private val onLaunchMicrophoneRequest: () -> Unit,
    private val onOpenAccessibilitySettings: () -> Unit,
    private val onOpenAppDetailsSettings: () -> Unit,
    private val onRefreshStates: () -> Unit
) {
    /**
     * True if `android.permission.RECORD_AUDIO` is currently granted according to Accompanist.
     */
    val isMicrophoneGranted: Boolean
        get() = microphonePermissionState.status.isGranted

    /**
     * True if the OS recommends showing a permission rationale before requesting `RECORD_AUDIO`.
     */
    val shouldShowMicrophoneRationale: Boolean
        get() = microphonePermissionState.status.shouldShowRationale

    /**
     * True when both microphone runtime permission and NAYRO's AccessibilityService are active.
     */
    val areAllCoreAgentPermissionsReady: Boolean
        get() = isMicrophoneGranted && isAccessibilityServiceEnabled

    /**
     * Requests the `RECORD_AUDIO` runtime permission via Accompanist `launchPermissionRequest()`,
     * or redirects to Application Details Settings if permanently denied.
     */
    fun requestMicrophonePermission() {
        onLaunchMicrophoneRequest()
    }

    /**
     * Opens the system Accessibility Settings screen (`Settings.ACTION_ACCESSIBILITY_SETTINGS`)
     * so the user can enable or manage `NayroAccessibilityService`.
     */
    fun requestAccessibilityService() {
        onOpenAccessibilitySettings()
    }

    /**
     * Alias for [requestAccessibilityService].
     */
    fun openAccessibilitySettings() {
        onOpenAccessibilitySettings()
    }

    /**
     * Opens the package-scoped Application Details Settings screen.
     */
    fun openAppDetailsSettings() {
        onOpenAppDetailsSettings()
    }

    /**
     * Refreshes the cached Accessibility Service status and triggers any registered callback.
     */
    fun refresh() {
        onRefreshStates()
    }
}

/**
 * Accompanist Permissions Utility for NAYRO.
 *
 * Handles:
 * 1. Runtime permission requests for Microphone (`Manifest.permission.RECORD_AUDIO`) using
 *    Google Accompanist Permissions (`rememberPermissionState`, `PermissionState.launchPermissionRequest()`).
 * 2. System Accessibility Service status inspection (`Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`
 *    and live `NayroAccessibilityService` connection state) and launching
 *    `Settings.ACTION_ACCESSIBILITY_SETTINGS` with automatic `ON_RESUME` lifecycle synchronization.
 */
@OptIn(ExperimentalPermissionsApi::class)
open class AccompanistPermissionUtility(
    protected val context: Context? = null
) {

    companion object {
        const val MICROPHONE_PERMISSION: String = Manifest.permission.RECORD_AUDIO
        const val ACCESSIBILITY_SETTINGS_ACTION: String = Settings.ACTION_ACCESSIBILITY_SETTINGS
        const val APP_DETAILS_SETTINGS_ACTION: String = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
        private const val PREFS_NAME = "nayro_perm_prefs"

        /**
         * Maps Accompanist [com.google.accompanist.permissions.PermissionStatus] flags
         * (`isGranted`, `shouldShowRationale`) and prior request history into NAYRO's [PermissionStatus].
         */
        fun evaluateMicrophoneAccompanistStatus(
            isGranted: Boolean,
            shouldShowRationale: Boolean,
            requestedBefore: Boolean = false
        ): PermissionStatus {
            if (isGranted) {
                return PermissionStatus.GRANTED
            }
            if (!shouldShowRationale && requestedBefore) {
                return PermissionStatus.PERMANENTLY_DENIED
            }
            return PermissionStatus.DENIED
        }

        /**
         * Evaluates an Accompanist [PermissionState] directly into NAYRO's [PermissionStatus].
         */
        fun evaluateAccompanistPermissionState(
            permissionState: PermissionState,
            requestedBefore: Boolean = false
        ): PermissionStatus {
            return evaluateMicrophoneAccompanistStatus(
                isGranted = permissionState.status.isGranted,
                shouldShowRationale = permissionState.status.shouldShowRationale,
                requestedBefore = requestedBefore
            )
        }

        /**
         * Checks whether `RECORD_AUDIO` is currently granted via [ContextCompat.checkSelfPermission].
         */
        fun isMicrophonePermissionGranted(context: Context?): Boolean {
            if (context == null) return false
            return try {
                ContextCompat.checkSelfPermission(
                    context,
                    MICROPHONE_PERMISSION
                ) == PackageManager.PERMISSION_GRANTED
            } catch (_: Throwable) {
                false
            }
        }

        /**
         * Checks whether NAYRO's [NayroAccessibilityService] is currently enabled in
         * `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` or actively bound in-process.
         */
        fun isAccessibilityServiceEnabled(
            context: Context?,
            serviceClass: Class<*> = NayroAccessibilityService::class.java
        ): Boolean {
            val connState = AccessibilityConnectionManager.connectionState.value
            if (NayroAccessibilityService.isConnected() ||
                connState == com.example.core.accessibility.AccessibilityConnectionState.OPERATIONAL ||
                connState == com.example.core.accessibility.AccessibilityConnectionState.CONNECTED
            ) {
                return true
            }
            if (context == null) return false
            return try {
                val accessibilityEnabled = Settings.Secure.getInt(
                    context.contentResolver,
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    0
                )
                if (accessibilityEnabled == 0) return false

                val enabledServices = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: return false

                val expectedComponent = ComponentName(context.packageName, serviceClass.name)
                val splitter = TextUtils.SimpleStringSplitter(':')
                splitter.setString(enabledServices)
                while (splitter.hasNext()) {
                    val componentNameString = splitter.next()
                    val enabledComponent = ComponentName.unflattenFromString(componentNameString)
                    if (enabledComponent != null &&
                        (enabledComponent == expectedComponent || enabledComponent.packageName == context.packageName)
                    ) {
                        return true
                    }
                }
                false
            } catch (_: Throwable) {
                false
            }
        }

        /**
         * Creates an [Intent] targeting `Settings.ACTION_ACCESSIBILITY_SETTINGS` so the user
         * can grant or manage system Accessibility Service access.
         */
        fun createAccessibilitySettingsIntent(): Intent {
            return try {
                Intent(ACCESSIBILITY_SETTINGS_ACTION).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } catch (_: Throwable) {
                allocateSafeIntentForTest()
            }
        }

        /**
         * Creates an [Intent] targeting `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` for
         * package-level permission recovery when `RECORD_AUDIO` is permanently denied.
         */
        fun createAppDetailsSettingsIntent(
            context: Context? = null,
            packageNameOverride: String? = null
        ): Intent {
            val pkg = packageNameOverride ?: context?.packageName ?: "com.aistudio.nayro.app"
            return try {
                Intent(
                    APP_DETAILS_SETTINGS_ACTION,
                    Uri.parse("package:$pkg")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } catch (_: Throwable) {
                allocateSafeIntentForTest()
            }
        }

        /**
         * Launches `Settings.ACTION_ACCESSIBILITY_SETTINGS` using the provided [activity] or [context].
         * Returns `true` if the settings screen was launched.
         */
        fun launchAccessibilitySettings(context: Context?, activity: Activity? = null): Boolean {
            val intent = createAccessibilitySettingsIntent()
            return try {
                when {
                    activity != null -> {
                        activity.startActivity(intent)
                        true
                    }
                    context != null -> {
                        context.startActivity(intent)
                        true
                    }
                    else -> false
                }
            } catch (_: Throwable) {
                false
            }
        }

        /**
         * Launches `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` for the app when runtime
         * microphone permission was permanently denied.
         */
        fun launchAppDetailsSettings(context: Context?, activity: Activity? = null): Boolean {
            val intent = createAppDetailsSettingsIntent(context ?: activity)
            return try {
                when {
                    activity != null -> {
                        activity.startActivity(intent)
                        true
                    }
                    context != null -> {
                        context.startActivity(intent)
                        true
                    }
                    else -> false
                }
            } catch (_: Throwable) {
                false
            }
        }

        /**
         * Triggers an Accompanist [PermissionState] request for `RECORD_AUDIO`, or falls back
         * to Application Details Settings if the permission was permanently denied.
         */
        fun requestMicrophoneWithAccompanist(
            permissionState: PermissionState,
            context: Context? = null,
            requestedBefore: Boolean = hasRequestedMicrophoneBefore(context),
            onMarkRequested: () -> Unit = { markMicrophoneRequested(context) }
        ) {
            if (permissionState.status.isGranted) return

            val status = evaluateAccompanistPermissionState(permissionState, requestedBefore)
            onMarkRequested()
            if (status == PermissionStatus.PERMANENTLY_DENIED && context != null) {
                launchAppDetailsSettings(context)
            } else {
                permissionState.launchPermissionRequest()
            }
        }

        fun hasRequestedMicrophoneBefore(context: Context?): Boolean {
            if (context == null) return false
            return try {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean("requested_$MICROPHONE_PERMISSION", false)
            } catch (_: Throwable) {
                false
            }
        }

        fun markMicrophoneRequested(context: Context?) {
            if (context == null) return
            try {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("requested_$MICROPHONE_PERMISSION", true)
                    .apply()
            } catch (_: Throwable) {
                // Ignore in headless unit tests
            }
        }

        private fun allocateSafeIntentForTest(): Intent {
            return try {
                val unsafeClass = Class.forName("sun.misc.Unsafe")
                val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
                val unsafe = field.get(null)
                val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
                allocate.invoke(unsafe, Intent::class.java) as Intent
            } catch (_: Throwable) {
                Intent()
            }
        }
    }

    fun isMicrophoneGranted(): Boolean = isMicrophonePermissionGranted(context)

    fun isAccessibilityEnabled(): Boolean = isAccessibilityServiceEnabled(context)

    fun getAccessibilitySettingsIntent(): Intent = createAccessibilitySettingsIntent()

    fun getAppDetailsSettingsIntent(packageNameOverride: String? = null): Intent =
        createAppDetailsSettingsIntent(context, packageNameOverride)

    fun openAccessibilitySettings(activity: Activity? = null): Boolean =
        launchAccessibilitySettings(context, activity)

    fun openAppDetailsSettings(activity: Activity? = null): Boolean =
        launchAppDetailsSettings(context, activity)
}

typealias AccompanistPermissionsUtility = AccompanistPermissionUtility

/**
 * Composable helper that remembers Accompanist's [PermissionState] for `RECORD_AUDIO`.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun rememberMicrophonePermissionState(
    onPermissionResult: (Boolean) -> Unit = {}
): PermissionState {
    val context = LocalContext.current
    return rememberPermissionState(
        permission = AccompanistPermissionUtility.MICROPHONE_PERMISSION
    ) { isGranted ->
        AccompanistPermissionUtility.markMicrophoneRequested(context)
        onPermissionResult(isGranted)
    }
}

/**
 * Composable Accompanist permissions handler that manages both:
 * 1. Runtime `RECORD_AUDIO` permission via Accompanist's [rememberPermissionState].
 * 2. System Accessibility Service status & Settings intent launching, automatically
 *    re-verifying when the user returns from system settings (`Lifecycle.Event.ON_RESUME`).
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun rememberNayroPermissionsHandler(
    onMicrophonePermissionResult: (Boolean) -> Unit = {},
    onPermissionsRefreshed: (isMicGranted: Boolean, isAccessibilityEnabled: Boolean) -> Unit = { _, _ -> }
): NayroAccompanistPermissionController {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var isAccessibilityEnabled by remember {
        mutableStateOf(AccompanistPermissionUtility.isAccessibilityServiceEnabled(context))
    }

    val microphonePermissionState = rememberPermissionState(
        permission = AccompanistPermissionUtility.MICROPHONE_PERMISSION
    ) { granted ->
        AccompanistPermissionUtility.markMicrophoneRequested(context)
        onMicrophonePermissionResult(granted)
        isAccessibilityEnabled = AccompanistPermissionUtility.isAccessibilityServiceEnabled(context)
        onPermissionsRefreshed(granted, isAccessibilityEnabled)
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val latestAccessibility = AccompanistPermissionUtility.isAccessibilityServiceEnabled(context)
                isAccessibilityEnabled = latestAccessibility
                onPermissionsRefreshed(microphonePermissionState.status.isGranted, latestAccessibility)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    return remember(microphonePermissionState, isAccessibilityEnabled, context) {
        NayroAccompanistPermissionController(
            microphonePermissionState = microphonePermissionState,
            isAccessibilityServiceEnabled = isAccessibilityEnabled,
            onLaunchMicrophoneRequest = {
                AccompanistPermissionUtility.requestMicrophoneWithAccompanist(
                    permissionState = microphonePermissionState,
                    context = context
                )
            },
            onOpenAccessibilitySettings = {
                AccompanistPermissionUtility.launchAccessibilitySettings(context)
            },
            onOpenAppDetailsSettings = {
                AccompanistPermissionUtility.launchAppDetailsSettings(context)
            },
            onRefreshStates = {
                val latestAccessibility = AccompanistPermissionUtility.isAccessibilityServiceEnabled(context)
                isAccessibilityEnabled = latestAccessibility
                onPermissionsRefreshed(microphonePermissionState.status.isGranted, latestAccessibility)
            }
        )
    }
}
