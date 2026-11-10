package com.example.core.permission

/**
 * Complete definition of an individual permission or system setting requirement in NAYRO.
 */
data class PermissionItem(
    val type: PermissionType,
    val permissionName: String,
    val purpose: String,
    val category: PermissionCategory,
    val minAndroidSdk: Int = 24,
    val androidPermissionString: String? = null,
    val settingsAction: String? = null,
    val associatedCapabilities: List<String> = emptyList(),
    val requirementLevel: PermissionRequirementLevel = PermissionRequirementLevel.CORE_AGENT,
    val featureDependency: String = "",
    val verificationMethod: String = "",
    val fallbackBehavior: String = ""
)

/**
 * Runtime snapshot of an evaluated permission item.
 */
data class PermissionEvaluation(
    val item: PermissionItem,
    val status: PermissionStatus,
    val statusExplanation: String,
    val canLaunchSettings: Boolean = item.settingsAction != null
)
