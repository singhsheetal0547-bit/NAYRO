package com.example.core.agent

enum class FailureDomain {
    NETWORK_FAILURE,
    API_ERROR_OR_QUOTA,
    MICROPHONE_FAILURE,
    PERMISSION_PROBLEM,
    ACCESSIBILITY_FAILURE,
    APP_CRASH_OR_UNAVAILABLE,
    UI_ELEMENT_NOT_FOUND,
    BROWSER_NAVIGATION_ERROR,
    BACKGROUND_SERVICE_INTERRUPTION,
    UNKNOWN
}

enum class RecoveryStrategy {
    RETRY_WITH_BACKOFF,
    FAILOVER_PROVIDER,
    SCROLL_AND_RETRY_UI,
    HTTP_FETCH_FALLBACK,
    REQUEST_PERMISSION_AND_PAUSE,
    MANUAL_VOICE_FALLBACK,
    RESTART_BACKGROUND_SERVICE,
    ABORT_HONESTLY
}

data class RecoveryDecision(
    val failureDomain: FailureDomain,
    val strategy: RecoveryStrategy,
    val canRetry: Boolean,
    val backoffDelayMs: Long = 0L,
    val alternativeAction: AgentAction? = null,
    val userExplanation: String
)

/**
 * Self-Recovery & Reliability Engine for NAYRO's Unified AgentRuntime.
 * Diagnoses execution failures, chooses safe supported recovery strategies or alternative actions,
 * enforces strict retry bounds, and NEVER fabricates successful completion.
 */
class ErrorRecoveryEngine(
    private val maxRecoveryAttemptsPerStep: Int = 2
) {

    fun diagnoseFailure(
        action: AgentAction,
        result: ActionExecutionResult
    ): FailureDomain {
        val msg = result.outputMessage.lowercase()
        return when {
            result.status == VerificationStatus.REQUIRES_PERMISSION ||
                msg.contains("permission") || msg.contains("denied") -> {
                if (msg.contains("accessibility")) FailureDomain.ACCESSIBILITY_FAILURE
                else FailureDomain.PERMISSION_PROBLEM
            }
            msg.contains("accessibility") -> FailureDomain.ACCESSIBILITY_FAILURE
            msg.contains("offline") || msg.contains("no internet") || msg.contains("timeout") ||
                msg.contains("unknownhost") || msg.contains("network") -> FailureDomain.NETWORK_FAILURE
            msg.contains("429") || msg.contains("quota") || msg.contains("rate limit") ||
                msg.contains("http 5") || msg.contains("401") || msg.contains("api key") -> FailureDomain.API_ERROR_OR_QUOTA
            msg.contains("microphone") || msg.contains("audiorecord") || msg.contains("silenced") -> FailureDomain.MICROPHONE_FAILURE
            msg.contains("not installed") || msg.contains("crashed") || msg.contains("no app found") -> FailureDomain.APP_CRASH_OR_UNAVAILABLE
            msg.contains("could not locate") || msg.contains("element not found") || msg.contains("no matching") -> FailureDomain.UI_ELEMENT_NOT_FOUND
            action.actionType in setOf(
                ActionType.BROWSER_OPEN_URL,
                ActionType.BROWSER_FILL_FORM,
                ActionType.BROWSER_COMPARE_SOURCES,
                ActionType.BROWSER_DOWNLOAD_FILE,
                ActionType.WEB_SEARCH
            ) -> FailureDomain.BROWSER_NAVIGATION_ERROR
            msg.contains("foreground service") || msg.contains("background service") -> FailureDomain.BACKGROUND_SERVICE_INTERRUPTION
            else -> FailureDomain.UNKNOWN
        }
    }

    fun planRecovery(
        action: AgentAction,
        result: ActionExecutionResult,
        attemptNumber: Int,
        hasSecondaryAiProvider: Boolean = false
    ): RecoveryDecision {
        val domain = diagnoseFailure(action, result)
        val retriesExhausted = attemptNumber >= maxRecoveryAttemptsPerStep

        return when (domain) {
            FailureDomain.NETWORK_FAILURE -> {
                if (!retriesExhausted && action.actionType in setOf(
                        ActionType.WEB_SEARCH,
                        ActionType.BROWSER_COMPARE_SOURCES,
                        ActionType.QUERY_LLM
                    )
                ) {
                    RecoveryDecision(
                        failureDomain = domain,
                        strategy = RecoveryStrategy.RETRY_WITH_BACKOFF,
                        canRetry = true,
                        backoffDelayMs = 600L * (attemptNumber + 1),
                        userExplanation = "Network hiccup detected; retrying safely with backoff."
                    )
                } else {
                    RecoveryDecision(
                        failureDomain = domain,
                        strategy = RecoveryStrategy.ABORT_HONESTLY,
                        canRetry = false,
                        userExplanation = "Network is offline or unreachable. Online action cannot be completed right now."
                    )
                }
            }

            FailureDomain.API_ERROR_OR_QUOTA -> {
                if (hasSecondaryAiProvider && !retriesExhausted) {
                    RecoveryDecision(
                        failureDomain = domain,
                        strategy = RecoveryStrategy.FAILOVER_PROVIDER,
                        canRetry = true,
                        backoffDelayMs = 300L,
                        userExplanation = "Primary AI provider hit an error/quota limit; failing over to secondary configured provider."
                    )
                } else {
                    RecoveryDecision(
                        failureDomain = domain,
                        strategy = RecoveryStrategy.ABORT_HONESTLY,
                        canRetry = false,
                        userExplanation = "AI provider returned an error or quota limit and no backup provider succeeded."
                    )
                }
            }

            FailureDomain.UI_ELEMENT_NOT_FOUND -> {
                if (!retriesExhausted && action.actionType in setOf(ActionType.CLICK_ELEMENT, ActionType.SET_TEXT, ActionType.BROWSER_FILL_FORM)) {
                    val scrollFallback = AgentAction(
                        taskId = action.taskId,
                        actionType = ActionType.SCROLL_FORWARD,
                        target = "down",
                        parameters = mapOf("direction" to "down"),
                        riskLevel = RiskLevel.R0_LOCAL_READONLY
                    )
                    RecoveryDecision(
                        failureDomain = domain,
                        strategy = RecoveryStrategy.SCROLL_AND_RETRY_UI,
                        canRetry = true,
                        backoffDelayMs = 350L,
                        alternativeAction = scrollFallback,
                        userExplanation = "UI element not currently visible; scrolling screen and retrying once."
                    )
                } else {
                    RecoveryDecision(
                        failureDomain = domain,
                        strategy = RecoveryStrategy.ABORT_HONESTLY,
                        canRetry = false,
                        userExplanation = "Could not find the requested on-screen element after recovery attempt."
                    )
                }
            }

            FailureDomain.BROWSER_NAVIGATION_ERROR -> {
                val url = action.parameters["url"] ?: action.target
                if (!retriesExhausted && action.actionType == ActionType.BROWSER_OPEN_URL && !url.isNullOrBlank()) {
                    val fallbackFetch = AgentAction(
                        taskId = action.taskId,
                        actionType = ActionType.WEB_SEARCH,
                        target = url,
                        parameters = mapOf("query" to url),
                        riskLevel = RiskLevel.R0_LOCAL_READONLY
                    )
                    RecoveryDecision(
                        failureDomain = domain,
                        strategy = RecoveryStrategy.HTTP_FETCH_FALLBACK,
                        canRetry = true,
                        alternativeAction = fallbackFetch,
                        userExplanation = "Browser launch failed; falling back to direct HTTP web search/read."
                    )
                } else {
                    RecoveryDecision(
                        failureDomain = domain,
                        strategy = RecoveryStrategy.ABORT_HONESTLY,
                        canRetry = false,
                        userExplanation = "Browser navigation failed: ${result.outputMessage}"
                    )
                }
            }

            FailureDomain.PERMISSION_PROBLEM, FailureDomain.ACCESSIBILITY_FAILURE -> {
                RecoveryDecision(
                    failureDomain = domain,
                    strategy = RecoveryStrategy.REQUEST_PERMISSION_AND_PAUSE,
                    canRetry = false,
                    userExplanation = result.outputMessage
                )
            }

            FailureDomain.MICROPHONE_FAILURE -> {
                RecoveryDecision(
                    failureDomain = domain,
                    strategy = RecoveryStrategy.MANUAL_VOICE_FALLBACK,
                    canRetry = false,
                    userExplanation = "Background microphone capture was interrupted; use the manual mic button or text composer."
                )
            }

            FailureDomain.BACKGROUND_SERVICE_INTERRUPTION -> {
                RecoveryDecision(
                    failureDomain = domain,
                    strategy = RecoveryStrategy.RESTART_BACKGROUND_SERVICE,
                    canRetry = !retriesExhausted,
                    backoffDelayMs = 500L,
                    userExplanation = "Background service was interrupted; attempting safe service recovery."
                )
            }

            FailureDomain.APP_CRASH_OR_UNAVAILABLE, FailureDomain.UNKNOWN -> {
                RecoveryDecision(
                    failureDomain = domain,
                    strategy = RecoveryStrategy.ABORT_HONESTLY,
                    canRetry = false,
                    userExplanation = result.outputMessage.ifBlank { "Action could not be completed safely." }
                )
            }
        }
    }
}
