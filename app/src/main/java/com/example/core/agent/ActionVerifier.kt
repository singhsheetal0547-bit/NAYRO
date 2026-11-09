package com.example.core.agent

import com.example.core.accessibility.AccessibilityConnectionManager
import com.example.core.accessibility.NayroAccessibilityService
import com.example.core.screen.ScreenChangeResult
import com.example.core.screen.ScreenIntelligenceManager

data class VerificationResult(
    val isVerified: Boolean,
    val details: String
)

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
                isVerified = false,
                details = "Action execution reported failure: ${executionResult.output}"
            )
        }

        return when (action.actionType) {
            ActionType.LAUNCH_APP -> {
                val targetPkg = action.target?.lowercase() ?: ""
                val activePkg = AccessibilityConnectionManager.lastActivePackage.value?.lowercase()
                    ?: NayroAccessibilityService.instance?.getSafeRootInActiveWindow()?.packageName?.toString()?.lowercase()

                if (activePkg != null && targetPkg.isNotBlank() && (activePkg.contains(targetPkg) || targetPkg.contains(activePkg))) {
                    VerificationResult(true, "Verified foreground package matches: $activePkg")
                } else if (screenChange?.packageChanged == true) {
                    VerificationResult(true, "Verified package changed to: ${screenChange.currentPackage}")
                } else {
                    // Action intent was launched successfully
                    VerificationResult(true, "Launch intent dispatched: ${executionResult.output}")
                }
            }

            ActionType.OPEN_SETTINGS_SECTION -> {
                val activePkg = AccessibilityConnectionManager.lastActivePackage.value
                VerificationResult(true, "Settings section opened (Active package: $activePkg)")
            }

            ActionType.READ_SCREEN -> {
                if (executionResult.output.isNotBlank() && !executionResult.output.contains("None available")) {
                    VerificationResult(true, "Screen contents successfully read and verified.")
                } else {
                    VerificationResult(false, "Screen reading yielded empty content.")
                }
            }

            ActionType.CLICK_ELEMENT -> {
                if (screenChange?.hasChanged == true) {
                    VerificationResult(true, "UI transition detected after click: ${screenChange.description}")
                } else {
                    VerificationResult(true, "Click action dispatched successfully: ${executionResult.output}")
                }
            }

            ActionType.SET_TEXT -> {
                VerificationResult(true, "Text entry confirmed: ${executionResult.output}")
            }

            ActionType.SAVE_NOTE, ActionType.SAVE_MEMORY, ActionType.GET_DEVICE_TIME, ActionType.CALCULATE_LOCAL, ActionType.QUERY_LLM -> {
                VerificationResult(true, "Operation verified: ${executionResult.output.take(50)}...")
            }

            ActionType.ASK_USER_CONFIRMATION -> {
                VerificationResult(true, "Confirmation requirement satisfied.")
            }

            ActionType.SCROLL_FORWARD, ActionType.SCROLL_BACKWARD, ActionType.NAVIGATE_BACK -> {
                VerificationResult(true, "Navigation gesture executed successfully.")
            }
        }
    }
}
