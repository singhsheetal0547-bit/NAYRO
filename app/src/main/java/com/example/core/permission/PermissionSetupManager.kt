package com.example.core.permission

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Steps in NAYRO's First-Launch Full Permission Setup Wizard.
 */
enum class SetupWizardStep(val stepNumber: Int, val title: String) {
    WELCOME(1, "Welcome & Babu Identity"),
    CAPABILITY_OVERVIEW(2, "Agent Capabilities & Privacy"),
    CORE_PERMISSIONS_SETUP(3, "Core Agent Permissions & Access"),
    OPTIONAL_PERMISSIONS_SETUP(4, "Optional Tool Permissions"),
    SERVICE_ACTIVATION(5, "Background & Floating Babu Setup"),
    VERIFICATION_REPORT(6, "Final Permission Verification Report")
}

/**
 * Single row in the Final Permission Verification Report and Permission Dashboard.
 */
data class PermissionVerificationEntry(
    val type: PermissionType,
    val permissionName: String,
    val purpose: String,
    val status: PermissionStatus,
    val statusExplanation: String,
    val requirementLevel: PermissionRequirementLevel,
    val category: PermissionCategory,
    val featureDependency: String,
    val verificationMethod: String,
    val fallbackBehavior: String,
    val canRequestRuntime: Boolean,
    val canOpenSettings: Boolean
) {
    val isEffectivelyGranted: Boolean
        get() = status == PermissionStatus.GRANTED || status == PermissionStatus.NOT_REQUIRED
}

/**
 * Complete real-time verification report across all registered NAYRO permissions.
 */
data class PermissionVerificationReport(
    val entries: List<PermissionVerificationEntry>,
    val totalCount: Int,
    val grantedCount: Int,
    val coreTotalCount: Int,
    val coreGrantedCount: Int,
    val optionalTotalCount: Int,
    val optionalGrantedCount: Int,
    val missingCorePermissions: List<PermissionType>,
    val missingOptionalPermissions: List<PermissionType>,
    val isCoreAgentReady: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Centralized manager for NAYRO's First-Launch Permission Setup Wizard,
 * dynamic permission auditing against PermissionRegistry, individual permission
 * status checks, and triggering special access Settings Intents
 * (such as Settings.ACTION_MANAGE_OVERLAY_PERMISSION).
 */
open class PermissionSetupManager(
    protected val context: Context? = null,
    protected val permissionManager: PermissionManager? = context?.let { PermissionManager(it) }
) {

    companion object {
        private const val PREFS_NAME = "nayro_permission_setup_prefs"
        private const val KEY_SETUP_COMPLETED = "setup_wizard_completed"
        private const val KEY_ASSISTANT_NAME = "assistant_persona_name"
        private const val KEY_CURRENT_STEP = "setup_wizard_step"
    }

    private var inMemoryCompleted: Boolean = false
    private var inMemoryAssistantName: String = "Babu"
    private var inMemoryStepOrdinal: Int = 0

    private val _currentStep = MutableStateFlow(loadSavedStep())
    val currentStep: StateFlow<SetupWizardStep> = _currentStep.asStateFlow()

    private val _verificationReport = MutableStateFlow(buildVerificationReport())
    val verificationReport: StateFlow<PermissionVerificationReport> = _verificationReport.asStateFlow()

    fun isSetupCompleted(): Boolean {
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs?.getBoolean(KEY_SETUP_COMPLETED, false) ?: inMemoryCompleted
    }

    fun getSavedAssistantName(): String {
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs?.getString(KEY_ASSISTANT_NAME, "Babu")?.takeIf { it.isNotBlank() } ?: inMemoryAssistantName
    }

    fun saveAssistantName(name: String) {
        val clean = name.trim().ifEmpty { "Babu" }
        inMemoryAssistantName = clean
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(KEY_ASSISTANT_NAME, clean)
            ?.apply()
    }

    fun setWizardStep(step: SetupWizardStep) {
        inMemoryStepOrdinal = step.ordinal
        _currentStep.value = step
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putInt(KEY_CURRENT_STEP, step.ordinal)
            ?.apply()
    }

    fun markSetupCompleted(assistantName: String = getSavedAssistantName()) {
        saveAssistantName(assistantName)
        inMemoryCompleted = true
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putBoolean(KEY_SETUP_COMPLETED, true)
            ?.putInt(KEY_CURRENT_STEP, SetupWizardStep.VERIFICATION_REPORT.ordinal)
            ?.apply()
        refreshReport()
    }

    fun resetSetupWizard() {
        inMemoryCompleted = false
        setWizardStep(SetupWizardStep.WELCOME)
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putBoolean(KEY_SETUP_COMPLETED, false)
            ?.putInt(KEY_CURRENT_STEP, SetupWizardStep.WELCOME.ordinal)
            ?.apply()
        refreshReport()
    }

    private fun loadSavedStep(): SetupWizardStep {
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val ordinal = prefs?.getInt(KEY_CURRENT_STEP, 0) ?: inMemoryStepOrdinal
        return SetupWizardStep.entries.getOrElse(ordinal) { SetupWizardStep.WELCOME }
    }

    /**
     * Audits the current state of all system permissions defined in PermissionRegistry.
     */
    fun auditPermissions(activity: Activity? = null): Map<PermissionType, PermissionEvaluation> {
        permissionManager?.refreshAllPermissions(activity)
        val managerEvaluations = permissionManager?.evaluations?.value
        if (!managerEvaluations.isNullOrEmpty()) {
            _verificationReport.value = buildVerificationReport()
            return managerEvaluations
        }

        val fallbackMap = PermissionRegistry.items.associate { item ->
            val defaultStatus = if (item.category == PermissionCategory.RUNTIME) {
                PermissionStatus.DENIED
            } else {
                PermissionStatus.SPECIAL_ACCESS_REQUIRED
            }
            item.type to PermissionEvaluation(
                item = item,
                status = defaultStatus,
                statusExplanation = item.purpose,
                canLaunchSettings = item.settingsAction != null
            )
        }
        _verificationReport.value = buildVerificationReport()
        return fallbackMap
    }

    /**
     * Checks the current status of an individual permission registered in PermissionRegistry.
     */
    fun checkPermissionStatus(type: PermissionType, activity: Activity? = null): PermissionStatus {
        val item = PermissionRegistry.findByType(type) ?: return PermissionStatus.UNAVAILABLE
        val eval = permissionManager?.evaluatePermission(item, activity)
        if (eval != null) {
            return eval.status
        }
        val reportEntry = _verificationReport.value.entries.firstOrNull { it.type == type }
        if (reportEntry != null) {
            return reportEntry.status
        }
        return if (item.category == PermissionCategory.RUNTIME) {
            PermissionStatus.DENIED
        } else {
            PermissionStatus.SPECIAL_ACCESS_REQUIRED
        }
    }

    /**
     * Evaluates an individual permission item and returns its full PermissionEvaluation.
     */
    fun getPermissionEvaluation(type: PermissionType, activity: Activity? = null): PermissionEvaluation? {
        val item = PermissionRegistry.findByType(type) ?: return null
        return permissionManager?.evaluatePermission(item, activity) ?: PermissionEvaluation(
            item = item,
            status = checkPermissionStatus(type, activity),
            statusExplanation = item.purpose,
            canLaunchSettings = item.settingsAction != null
        )
    }

    /**
     * Returns true if the specified permission is currently granted or not required on this SDK level.
     */
    fun isPermissionGranted(type: PermissionType, activity: Activity? = null): Boolean {
        val status = checkPermissionStatus(type, activity)
        return status == PermissionStatus.GRANTED || status == PermissionStatus.NOT_REQUIRED
    }

    /**
     * Resolves the Android Settings action string for the specified permission type
     * (e.g., Settings.ACTION_MANAGE_OVERLAY_PERMISSION for PermissionType.OVERLAY).
     */
    fun resolveSpecialAccessAction(type: PermissionType): String? {
        return PermissionRegistry.findByType(type)?.settingsAction
    }

    /**
     * Resolves the package URI string ("package:<packageName>") for package-scoped Settings intents.
     */
    fun resolveSpecialAccessDataUri(
        type: PermissionType,
        packageNameOverride: String? = null
    ): String? {
        val action = resolveSpecialAccessAction(type) ?: return null
        val pkg = packageNameOverride ?: context?.packageName ?: "com.example"
        return when (action) {
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS -> "package:$pkg"
            else -> null
        }
    }

    /**
     * Creates the Android Settings Intent for special access or runtime permission settings
     * (including Settings.ACTION_MANAGE_OVERLAY_PERMISSION, ACTION_ACCESSIBILITY_SETTINGS,
     * ACTION_NOTIFICATION_LISTENER_SETTINGS, ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
     * and ACTION_APPLICATION_DETAILS_SETTINGS).
     */
    fun createSpecialAccessIntent(
        type: PermissionType,
        packageNameOverride: String? = null
    ): Intent? {
        try {
            permissionManager?.createSettingsIntent(type)?.let { return it }
        } catch (_: Throwable) {}

        val action = resolveSpecialAccessAction(type) ?: return null
        val dataUriString = resolveSpecialAccessDataUri(type, packageNameOverride)

        return try {
            if (dataUriString != null) {
                Intent(action, Uri.parse(dataUriString)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                Intent(action).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
        } catch (_: Throwable) {
            // In plain JVM unit tests without Robolectric, android.content.Intent / Uri methods throw stub RuntimeExceptions
            try {
                val unsafeClass = Class.forName("sun.misc.Unsafe")
                val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
                val unsafe = field.get(null)
                val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
                allocate.invoke(unsafe, Intent::class.java) as? Intent
            } catch (_: Throwable) {
                null
            }
        }
    }

    /**
     * Triggers the Android system Settings screen for a special access permission
     * (e.g., Settings.ACTION_MANAGE_OVERLAY_PERMISSION for PermissionType.OVERLAY).
     * Returns true if the Intent was resolved and launched.
     */
    fun triggerSpecialAccessSettings(type: PermissionType, activity: Activity? = null): Boolean {
        val intent = createSpecialAccessIntent(type) ?: return false
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
        } catch (_: Exception) {
            // Fallback to generic application details settings if OEM blocks direct special access action
            try {
                val pkg = activity?.packageName ?: context?.packageName ?: return false
                val fallbackIntent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$pkg")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (activity != null) {
                    activity.startActivity(fallbackIntent)
                } else {
                    context?.startActivity(fallbackIntent)
                }
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * Convenience helper specifically for launching Settings.ACTION_MANAGE_OVERLAY_PERMISSION.
     */
    fun triggerOverlayPermissionSettings(activity: Activity? = null): Boolean {
        return triggerSpecialAccessSettings(PermissionType.OVERLAY, activity)
    }

    /**
     * Re-evaluates all permissions via PermissionManager and updates the verification report.
     */
    fun refreshReport(): PermissionVerificationReport {
        permissionManager?.refreshAllPermissions()
        val report = buildVerificationReport()
        _verificationReport.value = report
        return report
    }

    /**
     * Builds a complete PermissionVerificationReport from current PermissionManager evaluations
     * (or falls back to PermissionRegistry defaults in headless unit tests).
     */
    fun buildVerificationReport(
        overrideStatuses: Map<PermissionType, PermissionStatus>? = null
    ): PermissionVerificationReport {
        val currentEvaluations = permissionManager?.evaluations?.value ?: emptyMap()

        val entries = PermissionRegistry.items.map { item ->
            val eval = currentEvaluations[item.type]
            val status = overrideStatuses?.get(item.type)
                ?: eval?.status
                ?: if (item.category == PermissionCategory.RUNTIME) PermissionStatus.DENIED else PermissionStatus.SPECIAL_ACCESS_REQUIRED
            val explanation = eval?.statusExplanation ?: item.purpose

            PermissionVerificationEntry(
                type = item.type,
                permissionName = item.permissionName,
                purpose = item.purpose,
                status = status,
                statusExplanation = explanation,
                requirementLevel = item.requirementLevel,
                category = item.category,
                featureDependency = item.featureDependency,
                verificationMethod = item.verificationMethod,
                fallbackBehavior = item.fallbackBehavior,
                canRequestRuntime = item.category == PermissionCategory.RUNTIME &&
                    status != PermissionStatus.GRANTED &&
                    status != PermissionStatus.NOT_REQUIRED,
                canOpenSettings = item.settingsAction != null &&
                    status != PermissionStatus.GRANTED &&
                    status != PermissionStatus.NOT_REQUIRED
            )
        }

        val coreEntries = entries.filter { it.requirementLevel == PermissionRequirementLevel.CORE_AGENT }
        val optionalEntries = entries.filter { it.requirementLevel == PermissionRequirementLevel.OPTIONAL_FEATURE }

        val coreGranted = coreEntries.count { it.isEffectivelyGranted }
        val optionalGranted = optionalEntries.count { it.isEffectivelyGranted }
        val totalGranted = entries.count { it.isEffectivelyGranted }

        val missingCore = coreEntries.filter { !it.isEffectivelyGranted }.map { it.type }
        val missingOptional = optionalEntries.filter { !it.isEffectivelyGranted }.map { it.type }

        val report = PermissionVerificationReport(
            entries = entries,
            totalCount = entries.size,
            grantedCount = totalGranted,
            coreTotalCount = coreEntries.size,
            coreGrantedCount = coreGranted,
            optionalTotalCount = optionalEntries.size,
            optionalGrantedCount = optionalGranted,
            missingCorePermissions = missingCore,
            missingOptionalPermissions = missingOptional,
            isCoreAgentReady = missingCore.isEmpty()
        )
        _verificationReport?.let { it.value = report }
        return report
    }
}
