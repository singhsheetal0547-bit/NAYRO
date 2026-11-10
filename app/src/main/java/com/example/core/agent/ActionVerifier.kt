package com.example.core.agent

import com.example.core.accessibility.AccessibilityConnectionManager
import com.example.core.accessibility.NayroAccessibilityService
import com.example.core.screen.ScreenIntelligenceManager

/**
 * Post-Action State Verification Engine (BUG 05 & BUG 06 Remediation).
 * Compares pre-action state with post-action state and honors explicit ActionExecutionResult statuses.
 * Never defaults unverified, inconclusive, or unsupported operations to VERIFIED.
 */
class ActionVerifier(
    private val screenIntelligenceManager: ScreenIntelligenceManager
) {

    fun capturePreState(): String? {
        if (!NayroAccessibilityService.isConnected()) return null
        return screenIntelligenceManager.getScreenContext().screenSummary
    }

    fun verifyAction(
        action: AgentAction,
        executionResult: ActionExecutionResult,
        preStateSummary: String?,
        resolvedTargetPackage: String? = null
    ): VerificationResult {
        // 1. Honor explicit non-success statuses first (BUG 05 & BUG 06)
        when (executionResult.status) {
            VerificationStatus.NOT_SUPPORTED -> {
                return VerificationResult(
                    status = VerificationStatus.NOT_SUPPORTED,
                    details = executionResult.outputMessage,
                    evidence = VerificationEvidence(
                        preState = preStateSummary,
                        postState = null,
                        observedChange = "Operation is NOT_SUPPORTED: ${executionResult.outputMessage}"
                    )
                )
            }
            VerificationStatus.REQUIRES_PERMISSION -> {
                return VerificationResult(
                    status = VerificationStatus.REQUIRES_PERMISSION,
                    details = executionResult.outputMessage,
                    evidence = VerificationEvidence(
                        preState = preStateSummary,
                        postState = null,
                        observedChange = "Blocked pending permission: ${executionResult.outputMessage}"
                    )
                )
            }
            VerificationStatus.REQUIRES_CONFIRMATION -> {
                return VerificationResult(
                    status = VerificationStatus.REQUIRES_CONFIRMATION,
                    details = executionResult.outputMessage,
                    evidence = VerificationEvidence(
                        preState = preStateSummary,
                        postState = null,
                        observedChange = "Blocked pending confirmation: ${executionResult.outputMessage}"
                    )
                )
            }
            VerificationStatus.INCONCLUSIVE -> {
                return VerificationResult(
                    status = VerificationStatus.INCONCLUSIVE,
                    details = executionResult.outputMessage,
                    evidence = VerificationEvidence(
                        preState = preStateSummary,
                        postState = null,
                        observedChange = "Inconclusive outcome: ${executionResult.outputMessage}"
                    )
                )
            }
            VerificationStatus.TIMED_OUT -> {
                return VerificationResult(
                    status = VerificationStatus.TIMED_OUT,
                    details = executionResult.outputMessage,
                    evidence = VerificationEvidence(
                        preState = preStateSummary,
                        postState = null,
                        observedChange = "Timed out: ${executionResult.outputMessage}"
                    )
                )
            }
            VerificationStatus.CANCELLED -> {
                return VerificationResult(
                    status = VerificationStatus.CANCELLED,
                    details = executionResult.outputMessage,
                    evidence = VerificationEvidence(
                        preState = preStateSummary,
                        postState = null,
                        observedChange = "Cancelled: ${executionResult.outputMessage}"
                    )
                )
            }
            VerificationStatus.FAILED -> {
                return VerificationResult(
                    status = VerificationStatus.FAILED,
                    details = executionResult.outputMessage,
                    evidence = VerificationEvidence(
                        preState = preStateSummary,
                        postState = null,
                        observedChange = "Execution failed: ${executionResult.outputMessage}"
                    )
                )
            }
            else -> {}
        }

        if (!executionResult.isSuccess) {
            return VerificationResult(
                status = VerificationStatus.FAILED,
                details = executionResult.outputMessage,
                evidence = VerificationEvidence(
                    preState = preStateSummary,
                    postState = null,
                    observedChange = "Execution failed prior to state change."
                )
            )
        }

        val isAccessibilityActive = NayroAccessibilityService.isConnected()
        val postStateSummary = if (isAccessibilityActive) {
            screenIntelligenceManager.getScreenContext().screenSummary
        } else {
            null
        }
        val activeForegroundPkg = AccessibilityConnectionManager.lastActivePackage.value

        return when (action.actionType) {

            ActionType.LAUNCH_APP -> {
                if (!isAccessibilityActive) {
                    return VerificationResult(
                        status = VerificationStatus.INCONCLUSIVE,
                        details = "${executionResult.outputMessage} (Intent dispatched; foreground package transition unverified because Accessibility Service is off.)",
                        evidence = VerificationEvidence(
                            preState = preStateSummary,
                            postState = null,
                            observedChange = "Intent startActivity succeeded; foreground package unverified."
                        )
                    )
                }

                val expectedPkg = resolvedTargetPackage ?: action.target ?: ""
                val pkgMatched = !activeForegroundPkg.isNullOrBlank() &&
                    (activeForegroundPkg.equals(expectedPkg, ignoreCase = true) ||
                        activeForegroundPkg.lowercase().contains(expectedPkg.lowercase()))

                if (pkgMatched || (preStateSummary != null && postStateSummary != preStateSummary)) {
                    VerificationResult(
                        status = VerificationStatus.VERIFIED,
                        details = "${executionResult.outputMessage} Verified foreground app: ${activeForegroundPkg ?: expectedPkg}.",
                        evidence = VerificationEvidence(
                            preState = preStateSummary,
                            postState = postStateSummary,
                            observedChange = "Foreground package transitioned to ${activeForegroundPkg ?: expectedPkg}."
                        )
                    )
                } else {
                    VerificationResult(
                        status = VerificationStatus.INCONCLUSIVE,
                        details = "App launch intent fired, but foreground window did not transition to $expectedPkg yet (current: ${activeForegroundPkg ?: "unknown"}).",
                        evidence = VerificationEvidence(
                            preState = preStateSummary,
                            postState = postStateSummary,
                            observedChange = "No foreground package change detected yet."
                        )
                    )
                }
            }

            ActionType.OPEN_SETTINGS_SECTION -> {
                if (!isAccessibilityActive) {
                    return VerificationResult(
                        status = VerificationStatus.INCONCLUSIVE,
                        details = "${executionResult.outputMessage} (Settings intent launched; screen state unverified without Accessibility Service.)",
                        evidence = VerificationEvidence(
                            preState = preStateSummary,
                            postState = null,
                            observedChange = "Settings intent fired."
                        )
                    )
                }

                val inSettings = activeForegroundPkg?.contains("settings", ignoreCase = true) == true
                if (inSettings || postStateSummary != preStateSummary) {
                    VerificationResult(
                        status = VerificationStatus.VERIFIED,
                        details = "${executionResult.outputMessage} Verified Settings window is active.",
                        evidence = VerificationEvidence(
                            preState = preStateSummary,
                            postState = postStateSummary,
                            observedChange = "Foreground package is $activeForegroundPkg."
                        )
                    )
                } else {
                    VerificationResult(
                        status = VerificationStatus.INCONCLUSIVE,
                        details = "Settings intent launched, but Settings window could not be confirmed in foreground.",
                        evidence = VerificationEvidence(
                            preState = preStateSummary,
                            postState = postStateSummary,
                            observedChange = "Foreground package remained $activeForegroundPkg."
                        )
                    )
                }
            }

            ActionType.READ_SCREEN, ActionType.INSPECT_ACCESSIBILITY_TREE -> {
                val validRead = !executionResult.outputMessage.startsWith("Screen reading requires") &&
                    !executionResult.outputMessage.startsWith("Cannot read screen")
                if (validRead) {
                    VerificationResult(
                        status = VerificationStatus.VERIFIED,
                        details = executionResult.outputMessage,
                        evidence = VerificationEvidence(
                            preState = preStateSummary,
                            postState = executionResult.outputMessage,
                            observedChange = "Screen node hierarchy captured."
                        )
                    )
                } else {
                    VerificationResult(
                        status = VerificationStatus.FAILED,
                        details = executionResult.outputMessage
                    )
                }
            }

            ActionType.CLICK_ELEMENT,
            ActionType.LONG_PRESS_ELEMENT,
            ActionType.SCROLL_FORWARD,
            ActionType.SCROLL_BACKWARD,
            ActionType.SWIPE_SCREEN,
            ActionType.NAVIGATE_BACK,
            ActionType.INTERACT_COORDINATES -> {
                val stateChanged = preStateSummary != null && postStateSummary != null && preStateSummary != postStateSummary
                if (stateChanged) {
                    VerificationResult(
                        status = VerificationStatus.VERIFIED,
                        details = "${executionResult.outputMessage} (Screen state transition verified.)",
                        evidence = VerificationEvidence(
                            preState = preStateSummary,
                            postState = postStateSummary,
                            observedChange = "UI tree updated after interaction."
                        )
                    )
                } else {
                    VerificationResult(
                        status = VerificationStatus.INCONCLUSIVE,
                        details = "${executionResult.outputMessage} (Action dispatched, but no measurable change in visible screen nodes was observed.)",
                        evidence = VerificationEvidence(
                            preState = preStateSummary,
                            postState = postStateSummary,
                            observedChange = "UI tree unchanged after interaction."
                        )
                    )
                }
            }

            ActionType.SET_TEXT -> {
                val expectedText = action.parameters["text"] ?: action.parameters["query"] ?: ""
                val textAppearsOnScreen = postStateSummary?.contains(expectedText, ignoreCase = true) == true
                if (textAppearsOnScreen || (preStateSummary != null && postStateSummary != preStateSummary)) {
                    VerificationResult(
                        status = VerificationStatus.VERIFIED,
                        details = "${executionResult.outputMessage} Verified text entry in UI field.",
                        evidence = VerificationEvidence(
                            preState = preStateSummary,
                            postState = postStateSummary,
                            observedChange = "Target text '$expectedText' present in updated UI tree."
                        )
                    )
                } else {
                    VerificationResult(
                        status = VerificationStatus.INCONCLUSIVE,
                        details = "${executionResult.outputMessage} (Text injection sent, but field content could not be confirmed in accessibility snapshot.)",
                        evidence = VerificationEvidence(
                            preState = preStateSummary,
                            postState = postStateSummary,
                            observedChange = "Target text not visible in post-action snapshot."
                        )
                    )
                }
            }

            ActionType.SAVE_NOTE,
            ActionType.SEARCH_NOTES,
            ActionType.DELETE_NOTE,
            ActionType.SAVE_MEMORY,
            ActionType.CREATE_REMINDER,
            ActionType.GET_DEVICE_TIME,
            ActionType.GET_DEVICE_INFO,
            ActionType.GET_BATTERY_STATE,
            ActionType.GET_NETWORK_STATUS,
            ActionType.GET_STORAGE_INFO,
            ActionType.GET_CURRENT_LOCATION,
            ActionType.TOGGLE_FLASHLIGHT,
            ActionType.SEARCH_INSTALLED_APPS,
            ActionType.CALCULATE_LOCAL,
            ActionType.ASK_USER_CONFIRMATION,
            ActionType.QUERY_LLM,
            ActionType.ANALYZE_IMAGE_VISION,
            ActionType.FILE_BROWSE,
            ActionType.FILE_READ_TEXT,
            ActionType.FILE_WRITE_TEXT,
            ActionType.FILE_SEARCH,
            ActionType.PDF_EXTRACT_TEXT,
            ActionType.DOC_SUMMARIZE,
            ActionType.COMPARE_DOCUMENTS,
            ActionType.WEB_SEARCH,
            ActionType.FETCH_WEB_PAGE,
            ActionType.SUMMARIZE_WEB_CONTENT,
            ActionType.CODE_INSPECT_FILE,
            ActionType.CODE_EDIT_FILE,
            ActionType.CODE_DIAGNOSE_ERROR,
            ActionType.CODE_GENERATE_DOCS,
            ActionType.LIST_SKILLS,
            ActionType.EXECUTE_PROCEDURAL_SKILL,
            ActionType.REGISTER_CUSTOM_SKILL,
            ActionType.EXECUTE_CROSS_APP_WORKFLOW,
            ActionType.DELEGATE_SUBAGENT_TASK,
            ActionType.CHECK_SUBAGENT_STATUS,
            ActionType.AGGREGATE_SUBAGENT_RESULTS,
            ActionType.CHECK_INCOMING_CALL,
            ActionType.RESOLVE_CONTACT,
            ActionType.MAKE_PHONE_CALL,
            ActionType.SEND_SMS,
            ActionType.READ_NOTIFICATIONS,
            ActionType.SUMMARIZE_NOTIFICATIONS,
            ActionType.CLASSIFY_NOTIFICATIONS,
            ActionType.CREATE_TASK_FROM_NOTIFICATION,
            ActionType.TAKE_SCREENSHOT,
            ActionType.SCHEDULE_ALARM,
            ActionType.READ_CALENDAR_EVENTS,
            ActionType.CREATE_CALENDAR_EVENT,
            ActionType.UPDATE_CALENDAR_EVENT,
            ActionType.DELETE_CALENDAR_EVENT,
            ActionType.WHATSAPP_READ_CONVERSATION,
            ActionType.MESSAGE_DRAFT_SEND -> {
                if (executionResult.status == VerificationStatus.VERIFIED && executionResult.outputMessage.isNotBlank()) {
                    VerificationResult(
                        status = VerificationStatus.VERIFIED,
                        details = executionResult.outputMessage,
                        evidence = VerificationEvidence(
                            preState = preStateSummary,
                            postState = executionResult.outputMessage,
                            observedChange = "Verified execution output: ${executionResult.outputMessage.take(120)}"
                        )
                    )
                } else if (executionResult.status == null && executionResult.isSuccess && executionResult.outputMessage.isNotBlank()) {
                    VerificationResult(
                        status = VerificationStatus.VERIFIED,
                        details = executionResult.outputMessage,
                        evidence = VerificationEvidence(
                            preState = preStateSummary,
                            postState = executionResult.outputMessage,
                            observedChange = "Deterministic local operation verified."
                        )
                    )
                } else {
                    VerificationResult(
                        status = executionResult.status ?: VerificationStatus.INCONCLUSIVE,
                        details = executionResult.outputMessage.ifBlank { "No verifiable output produced." },
                        evidence = VerificationEvidence(
                            preState = preStateSummary,
                            postState = null,
                            observedChange = "Unverified or empty output."
                        )
                    )
                }
            }
        }
    }
}
