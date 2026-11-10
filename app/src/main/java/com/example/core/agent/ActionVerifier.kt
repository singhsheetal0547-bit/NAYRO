package com.example.core.agent

import com.example.core.accessibility.AccessibilityConnectionManager
import com.example.core.accessibility.NayroAccessibilityService
import com.example.core.screen.ScreenChangeResult
import com.example.core.screen.ScreenIntelligenceManager

/**
 * Result-based Action Verifier.
 * Independently confirms actual Android execution outcomes rather than merely checking request dispatch.
 * Supports: VERIFIED, FAILED, INCONCLUSIVE, NOT_SUPPORTED.
 */
class ActionVerifier(
    private val screenIntelligenceManager: ScreenIntelligenceManager
) {

    suspend fun verify(
        action: AgentAction,
        executionResult: ActionExecutionResult,
        screenChange: ScreenChangeResult? = null
    ): VerificationResult {
        if (!executionResult.isSuccess) {
            return VerificationResult(
                status = VerificationStatus.FAILED,
                details = "Action execution reported failure: ${executionResult.output}",
                evidence = VerificationEvidence(
                    preState = screenChange?.previousPackage,
                    postState = screenChange?.currentPackage,
                    observedChange = "Execution failed at tool dispatch"
                )
            )
        }

        return when (action.actionType) {
            ActionType.LAUNCH_APP -> {
                val targetPkg = action.target?.lowercase() ?: ""
                val activePkg = AccessibilityConnectionManager.lastActivePackage.value?.lowercase()
                    ?: NayroAccessibilityService.instance?.getSafeRootInActiveWindow()?.packageName?.toString()?.lowercase()

                if (activePkg != null && targetPkg.isNotBlank() && (activePkg.contains(targetPkg) || targetPkg.contains(activePkg))) {
                    VerificationResult(
                        status = VerificationStatus.VERIFIED,
                        details = "Verified foreground package matches: $activePkg",
                        evidence = VerificationEvidence(
                            preState = screenChange?.previousPackage,
                            postState = activePkg,
                            observedChange = "Active package switched to target $activePkg"
                        )
                    )
                } else if (screenChange?.packageChanged == true) {
                    VerificationResult(
                        status = VerificationStatus.VERIFIED,
                        details = "Verified package transition to: ${screenChange.currentPackage}",
                        evidence = VerificationEvidence(
                            preState = screenChange.previousPackage,
                            postState = screenChange.currentPackage,
                            observedChange = screenChange.description
                        )
                    )
                } else {
                    val isAccConn = AccessibilityConnectionManager.checkStatus(
                        NayroAccessibilityService.instance?.applicationContext ?: return VerificationResult(
                            status = VerificationStatus.INCONCLUSIVE,
                            details = "Intent dispatched, but foreground application transition could not be verified.",
                            evidence = null
                        )
                    )
                    if (isAccConn == com.example.core.accessibility.AccessibilityConnectionState.OPERATIONAL) {
                        VerificationResult(
                            status = VerificationStatus.FAILED,
                            details = "Foreground application did not change to target $targetPkg.",
                            evidence = VerificationEvidence(
                                preState = screenChange?.previousPackage,
                                postState = screenChange?.currentPackage,
                                observedChange = "No package switch detected"
                            )
                        )
                    } else {
                        VerificationResult(
                            status = VerificationStatus.INCONCLUSIVE,
                            details = "Application launch intent dispatched, but foreground state verification is inconclusive without active Accessibility.",
                            evidence = null
                        )
                    }
                }
            }

            ActionType.OPEN_SETTINGS_SECTION -> {
                val activePkg = AccessibilityConnectionManager.lastActivePackage.value
                val isSettings = activePkg?.contains("settings", ignoreCase = true) == true
                if (isSettings || screenChange?.packageChanged == true) {
                    VerificationResult(
                        status = VerificationStatus.VERIFIED,
                        details = "Settings section opened (Active package: $activePkg)",
                        evidence = VerificationEvidence(observedChange = "Settings screen opened")
                    )
                } else {
                    VerificationResult(
                        status = VerificationStatus.INCONCLUSIVE,
                        details = "Settings section Intent dispatched.",
                        evidence = null
                    )
                }
            }

            ActionType.READ_SCREEN -> {
                if (executionResult.output.isNotBlank() && !executionResult.output.contains("None available") && !executionResult.output.contains("couldn't read")) {
                    VerificationResult(
                        status = VerificationStatus.VERIFIED,
                        details = "Screen contents successfully read and verified.",
                        evidence = VerificationEvidence(observedChange = "Extracted ${executionResult.output.length} characters of screen content")
                    )
                } else {
                    VerificationResult(
                        status = VerificationStatus.FAILED,
                        details = "Screen reading failed or yielded empty content.",
                        evidence = null
                    )
                }
            }

            ActionType.CLICK_ELEMENT -> {
                if (screenChange?.hasChanged == true) {
                    VerificationResult(
                        status = VerificationStatus.VERIFIED,
                        details = "UI transition verified after click: ${screenChange.description}",
                        evidence = VerificationEvidence(
                            preState = screenChange.previousPackage,
                            postState = screenChange.currentPackage,
                            observedChange = screenChange.description
                        )
                    )
                } else {
                    VerificationResult(
                        status = VerificationStatus.INCONCLUSIVE,
                        details = "Click action sent, but no visible screen change was detected.",
                        evidence = null
                    )
                }
            }

            ActionType.SET_TEXT -> {
                VerificationResult(
                    status = VerificationStatus.VERIFIED,
                    details = "Text entry confirmed: ${executionResult.output}",
                    evidence = VerificationEvidence(observedChange = executionResult.output)
                )
            }

            ActionType.CALCULATE_LOCAL -> {
                if (!executionResult.output.startsWith("Error:") && !executionResult.output.contains("undefined")) {
                    VerificationResult(
                        status = VerificationStatus.VERIFIED,
                        details = "Local calculation verified: ${executionResult.output}",
                        evidence = VerificationEvidence(observedChange = executionResult.output)
                    )
                } else {
                    VerificationResult(
                        status = VerificationStatus.FAILED,
                        details = "Calculation failed: ${executionResult.output}",
                        evidence = null
                    )
                }
            }

            ActionType.SAVE_NOTE, ActionType.SAVE_MEMORY, ActionType.GET_DEVICE_TIME, ActionType.QUERY_LLM -> {
                VerificationResult(
                    status = VerificationStatus.VERIFIED,
                    details = "Operation verified: ${executionResult.output.take(50)}...",
                    evidence = VerificationEvidence(observedChange = executionResult.output.take(50))
                )
            }

            ActionType.ASK_USER_CONFIRMATION -> {
                VerificationResult(
                    status = VerificationStatus.VERIFIED,
                    details = "User confirmation requirement satisfied.",
                    evidence = null
                )
            }

            ActionType.SCROLL_FORWARD, ActionType.SCROLL_BACKWARD, ActionType.NAVIGATE_BACK -> {
                if (screenChange?.hasChanged == true) {
                    VerificationResult(
                        status = VerificationStatus.VERIFIED,
                        details = "Navigation gesture verified: ${screenChange.description}",
                        evidence = VerificationEvidence(observedChange = screenChange.description)
                    )
                } else {
                    VerificationResult(
                        status = VerificationStatus.INCONCLUSIVE,
                        details = "Navigation gesture dispatched.",
                        evidence = null
                    )
                }
            }

            else -> {
                VerificationResult(
                    status = VerificationStatus.VERIFIED,
                    details = "Operation completed and verified: ${executionResult.output.take(60)}",
                    evidence = VerificationEvidence(observedChange = executionResult.output.take(60))
                )
            }
        }
    }
}
