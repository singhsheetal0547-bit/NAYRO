package com.example.core.agent

import com.example.core.security.EncryptedStorageManager
import java.util.UUID

enum class UserFeedbackSignal {
    POSITIVE,
    NEGATIVE,
    CORRECTION
}

data class ToolExecutionMetrics(
    val actionType: ActionType,
    val totalExecutions: Int = 0,
    val verifiedSuccesses: Int = 0,
    val failures: Int = 0,
    val inconclusives: Int = 0,
    val avgLatencyMs: Long = 0L,
    val lastFailureReason: String? = null,
    val lastUpdatedMs: Long = System.currentTimeMillis()
) {
    val reliabilityScore: Float
        get() {
            if (totalExecutions == 0) return 0.85f // neutral prior
            val weightedSuccess = verifiedSuccesses + (inconclusives * 0.35f)
            return (weightedSuccess / totalExecutions.toFloat()).coerceIn(0.05f, 1.0f)
        }
}

data class ExecutionLesson(
    val id: String = UUID.randomUUID().toString(),
    val domainHint: String,
    val lessonText: String,
    val isFailureWarning: Boolean,
    val createdAtMs: Long = System.currentTimeMillis()
)

data class FeedbackRecord(
    val taskId: String,
    val userPrompt: String,
    val signal: UserFeedbackSignal,
    val correctionNote: String? = null,
    val recordedAtMs: Long = System.currentTimeMillis()
)

data class SelfImprovementReport(
    val totalTasksObserved: Int,
    val verifiedTaskSuccessRate: Float,
    val toolMetrics: List<ToolExecutionMetrics>,
    val activeLessons: List<ExecutionLesson>,
    val userFeedbackCount: Int,
    val transparencyNotice: String = "Performance improvements are achieved strictly through persistent memory, user feedback, workflow history, and execution analytics — underlying LLM model weights are NOT retrained on-device."
)

/**
 * Section 10 — Self-Improving Feedback-Driven Intelligence Engine for NAYRO.
 *
 * Continuously observes real execution outcomes and user feedback to:
 * - Track per-tool reliability, average latency, and recurring failure patterns
 * - Detect unreliable workflows and warn or recommend recovery strategies
 * - Store learned lessons from failures and user corrections
 * - Generate concise adaptive planning guidance injected into AgentBrain prompts
 * - Never fabricates metrics and never falsely claims underlying model weight retraining
 */
class SelfImprovementEngine(
    private val encryptedStorageManager: EncryptedStorageManager? = null
) {
    companion object {
        private const val PREF_KEY_LESSONS = "nayro_self_improvement_lessons_v1"
        private const val MAX_LESSONS = 40
        private const val MAX_FEEDBACK_RECORDS = 100
    }

    private val lock = Any()
    private val metricsByTool = mutableMapOf<ActionType, ToolExecutionMetrics>()
    private val lessons = mutableListOf<ExecutionLesson>()
    private val feedbackRecords = mutableListOf<FeedbackRecord>()
    private val recurringFailureCounts = mutableMapOf<String, Int>()
    private var totalTasksObserved: Int = 0
    private var verifiedTasksCount: Int = 0

    init {
        loadPersistedLessons()
    }

    /**
     * Records a single step's verified/failed/inconclusive execution outcome and latency.
     */
    fun recordStepOutcome(
        actionType: ActionType,
        verificationStatus: VerificationStatus,
        latencyMs: Long,
        details: String
    ) {
        val now = System.currentTimeMillis()
        val safeLatency = latencyMs.coerceAtLeast(1L)
        synchronized(lock) {
            val prev = metricsByTool[actionType] ?: ToolExecutionMetrics(actionType = actionType)
            val nextTotal = prev.totalExecutions + 1
            val isVerified = verificationStatus == VerificationStatus.VERIFIED
            val isInconclusive = verificationStatus == VerificationStatus.INCONCLUSIVE ||
                verificationStatus == VerificationStatus.PARTIALLY_COMPLETED
            val isFailed = !isVerified && !isInconclusive
            val nextAvgLatency = ((prev.avgLatencyMs * prev.totalExecutions) + safeLatency) / nextTotal

            metricsByTool[actionType] = prev.copy(
                totalExecutions = nextTotal,
                verifiedSuccesses = prev.verifiedSuccesses + if (isVerified) 1 else 0,
                failures = prev.failures + if (isFailed) 1 else 0,
                inconclusives = prev.inconclusives + if (isInconclusive) 1 else 0,
                avgLatencyMs = nextAvgLatency,
                lastFailureReason = if (isFailed || isInconclusive) details.take(180) else prev.lastFailureReason,
                lastUpdatedMs = now
            )

            if (isFailed) {
                val failureKey = "${actionType.name}:${classifyFailureCategory(details)}"
                val count = (recurringFailureCounts[failureKey] ?: 0) + 1
                recurringFailureCounts[failureKey] = count
                if (count >= 2) {
                    addLessonLocked(
                        domainHint = actionType.name.lowercase(),
                        lessonText = "Tool ${actionType.name} repeatedly failed ($count times) with ${classifyFailureCategory(details)}: ${details.take(110)}. Prefer checking prerequisites or using an alternative strategy.",
                        isFailureWarning = true
                    )
                }
            }
        }
        persistLessons()
    }

    /**
     * Records a completed or failed full task execution to track measurable agent task success rate.
     */
    fun recordTaskOutcome(
        task: AgentTask,
        verificationStatus: VerificationStatus,
        summary: String
    ) {
        synchronized(lock) {
            totalTasksObserved++
            if (verificationStatus == VerificationStatus.VERIFIED) {
                verifiedTasksCount++
            }
            val toolChain = task.actions.joinToString(" -> ") { it.actionType.name }
            if (verificationStatus == VerificationStatus.VERIFIED && task.actions.size >= 2) {
                addLessonLocked(
                    domainHint = task.resolvedIntent.lowercase(),
                    lessonText = "Verified workflow for '${task.userInstruction.take(70)}': [$toolChain].",
                    isFailureWarning = false
                )
            } else if (verificationStatus == VerificationStatus.FAILED) {
                addLessonLocked(
                    domainHint = task.resolvedIntent.lowercase(),
                    lessonText = "Avoid repeating failed plan [$toolChain] for '${task.userInstruction.take(60)}': ${summary.take(100)}",
                    isFailureWarning = true
                )
            }
        }
        persistLessons()
    }

    /**
     * Records explicit user feedback (thumbs up/down or verbal correction) and converts corrections
     * into persistent behavioral guidance.
     */
    fun recordUserFeedback(
        taskId: String,
        userPrompt: String,
        signal: UserFeedbackSignal,
        correctionNote: String? = null
    ) {
        synchronized(lock) {
            feedbackRecords.add(
                0,
                FeedbackRecord(
                    taskId = taskId,
                    userPrompt = userPrompt.take(160),
                    signal = signal,
                    correctionNote = correctionNote?.take(220)
                )
            )
            if (feedbackRecords.size > MAX_FEEDBACK_RECORDS) {
                feedbackRecords.removeAt(feedbackRecords.lastIndex)
            }
            if (signal == UserFeedbackSignal.CORRECTION && !correctionNote.isNullOrBlank()) {
                addLessonLocked(
                    domainHint = userPrompt.lowercase().take(40),
                    lessonText = "User correction for '${userPrompt.take(60)}': ${correctionNote.trim()}",
                    isFailureWarning = false
                )
            } else if (signal == UserFeedbackSignal.NEGATIVE && !correctionNote.isNullOrBlank()) {
                addLessonLocked(
                    domainHint = userPrompt.lowercase().take(40),
                    lessonText = "User reported issue on '${userPrompt.take(60)}': ${correctionNote.trim()}",
                    isFailureWarning = true
                )
            }
        }
        persistLessons()
    }

    fun getToolReliabilityScore(actionType: ActionType): Float = synchronized(lock) {
        metricsByTool[actionType]?.reliabilityScore ?: 0.85f
    }

    fun getMetricsForTool(actionType: ActionType): ToolExecutionMetrics? = synchronized(lock) {
        metricsByTool[actionType]
    }

    /**
     * Builds concise, relevant execution lessons to inject into AgentBrain planning & LLM reasoning prompts.
     */
    fun buildAdaptivePlanningGuidance(userPrompt: String, maxLessons: Int = 4): String {
        val tokens = userPrompt.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length >= 3 }
            .toSet()

        val relevant = synchronized(lock) {
            lessons.sortedByDescending { lesson ->
                val text = "${lesson.domainHint} ${lesson.lessonText}".lowercase()
                val overlap = tokens.count { tok -> text.contains(tok) }
                (overlap * 10) + if (lesson.isFailureWarning) 2 else 1
            }.take(maxLessons)
        }

        if (relevant.isEmpty()) return ""
        return buildString {
            append("Learned Execution Insights (from past task outcomes & user feedback):\n")
            for (lesson in relevant) {
                val tag = if (lesson.isFailureWarning) "[CAUTION]" else "[LEARNED]"
                append("- $tag ${lesson.lessonText}\n")
            }
        }.trim()
    }

    fun getPerformanceReport(): SelfImprovementReport = synchronized(lock) {
        val rate = if (totalTasksObserved == 0) 0f else verifiedTasksCount.toFloat() / totalTasksObserved.toFloat()
        SelfImprovementReport(
            totalTasksObserved = totalTasksObserved,
            verifiedTaskSuccessRate = rate,
            toolMetrics = metricsByTool.values.sortedByDescending { it.totalExecutions },
            activeLessons = lessons.toList(),
            userFeedbackCount = feedbackRecords.size
        )
    }

    fun resetAnalytics() {
        synchronized(lock) {
            metricsByTool.clear()
            lessons.clear()
            feedbackRecords.clear()
            recurringFailureCounts.clear()
            totalTasksObserved = 0
            verifiedTasksCount = 0
        }
        persistLessons()
    }

    private fun addLessonLocked(domainHint: String, lessonText: String, isFailureWarning: Boolean) {
        val normalized = lessonText.trim()
        if (normalized.isEmpty()) return
        lessons.removeAll { it.lessonText.equals(normalized, ignoreCase = true) }
        lessons.add(
            0,
            ExecutionLesson(
                domainHint = domainHint,
                lessonText = normalized,
                isFailureWarning = isFailureWarning
            )
        )
        if (lessons.size > MAX_LESSONS) {
            lessons.removeAt(lessons.lastIndex)
        }
    }

    private fun classifyFailureCategory(details: String): String {
        val lower = details.lowercase()
        return when {
            lower.contains("permission") || lower.contains("denied") -> "PERMISSION_MISSING"
            lower.contains("offline") || lower.contains("network") || lower.contains("timeout") -> "NETWORK_OR_TIMEOUT"
            lower.contains("accessibility") -> "ACCESSIBILITY_UNAVAILABLE"
            lower.contains("not found") || lower.contains("missing") -> "TARGET_NOT_FOUND"
            lower.contains("rate limit") || lower.contains("429") -> "RATE_LIMITED"
            else -> "EXECUTION_ERROR"
        }
    }

    private fun persistLessons() {
        val storage = encryptedStorageManager ?: return
        val encoded = synchronized(lock) {
            lessons.joinToString("\n") { l ->
                "${l.id}\t${l.domainHint.replace("\t", " ")}\t${l.isFailureWarning}\t${l.createdAtMs}\t${l.lessonText.replace("\t", " ").replace("\n", " ")}"
            }
        }
        try {
            storage.savePreference(PREF_KEY_LESSONS, encoded)
        } catch (_: Exception) {}
    }

    private fun loadPersistedLessons() {
        val storage = encryptedStorageManager ?: return
        val raw = try {
            storage.getPreference(PREF_KEY_LESSONS, "")
        } catch (_: Exception) {
            ""
        }
        if (raw.isBlank()) return
        val parsed = raw.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split("\t", limit = 5)
                if (parts.size < 5) return@mapNotNull null
                ExecutionLesson(
                    id = parts[0],
                    domainHint = parts[1],
                    isFailureWarning = parts[2].toBooleanStrictOrNull() ?: false,
                    createdAtMs = parts[3].toLongOrNull() ?: 0L,
                    lessonText = parts[4]
                )
            }.toList()
        synchronized(lock) {
            lessons.clear()
            lessons.addAll(parsed.take(MAX_LESSONS))
        }
    }
}
