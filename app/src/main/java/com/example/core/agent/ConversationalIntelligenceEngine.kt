package com.example.core.agent

import com.example.data.local.entities.ConversationEntity

import com.example.core.provider.model.AIChatMessage
import java.util.UUID

/**
 * The THREE unified Execution Domains coordinated by ONE AgentRuntime, ONE TaskPlanner,
 * ONE Shared Memory, ONE AI Reasoning Engine, ONE Tool Registry, ONE ActionExecutor,
 * ONE ActionVerifier, and ONE Permission & Security Manager.
 */
enum class ExecutionDomain(val displayName: String) {
    DEVICE("Autonomous Device Agent"),
    VOICE("Autonomous Voice Agent"),
    COMMUNICATION("Autonomous Communication Agent")
}

/**
 * Automatically detected conversational language supporting seamless multi-turn switching.
 */
enum class ConversationLanguage(val localeTag: String, val displayName: String) {
    ENGLISH("en-IN", "English"),
    HINDI("hi-IN", "Hindi"),
    HINGLISH("hi-IN", "Hinglish")
}

data class ConversationTurn(
    val role: String, // "user" or "assistant"
    val content: String,
    val language: ConversationLanguage,
    val timestampMs: Long = System.currentTimeMillis()
)

data class DailyPlanItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val priority: String = "NORMAL", // HIGH, NORMAL, LOW
    val scheduledHint: String? = null,
    val domain: ExecutionDomain = ExecutionDomain.DEVICE,
    val isCompleted: Boolean = false,
    val createdAtMs: Long = System.currentTimeMillis()
)

data class ConversationalAnalysis(
    val language: ConversationLanguage,
    val isGreeting: Boolean,
    val isDailyPlanningDiscussion: Boolean,
    val isFollowUpQuestion: Boolean,
    val extractedPlanItems: List<String> = emptyList(),
    val activeDailyPlan: List<DailyPlanItem> = emptyList()
)

/**
 * Unified Conversational AI Intelligence Engine for NAYRO.
 *
 * Lives inside the ONE shared [AgentRuntime] (never a separate agent) and provides:
 * - Automatic Hindi, English, and Hinglish detection and dynamic per-turn language switching.
 * - Natural human-like greetings and multi-turn conversational memory.
 * - Interactive daily planning and task organization (e.g. "Babu, aaj mujhe kuch important kaam karne hain").
 * - Rich context-aware prompt assembly for Gemini, xAI Grok, OpenRouter, and OpenAI-compatible providers.
 * - Honest, helpful local conversational handling when offline or when no cloud provider key is configured.
 */
class ConversationalIntelligenceEngine {

    companion object {
        private const val MAX_ROLLING_TURNS = 20

        private val DEVANAGARI_REGEX = Regex("[\\u0900-\\u097F]")

        private val HINGLISH_MARKERS = setOf(
            "aaj", "kal", "mujhe", "mujhko", "mera", "meri", "mere", "karna", "karni", "karne",
            "karo", "kardo", "bhejo", "bhej", "phir", "fir", "aur", "kya", "kaise", "kaisa",
            "kaisi", "kyu", "kyun", "bolo", "batao", "bataiye", "kaam", "zaroori", "jaruri",
            "haan", "nahi", "nahin", "theek", "thik", "accha", "acha", "namaste", "namaskar",
            "shukriya", "dhanyawad", "abhi", "baad", "pehle", "use", "usko", "unko", "unhe",
            "chahiye", "chahie", "chalao", "kholo", "band", "padho", "sunao", "likho", "dekho",
            "yaad", "dilana", "subah", "shaam", "raat", "dost", "bhai", "yaar", "hain", "hai",
            "ho", "hoon", "hun", "kuch", "sab", "bahut", "thoda", "madad", "baje", "waqt"
        )

        private val GREETING_PATTERNS = listOf(
            Regex("^(?i)(hi|hello|hey|hii|helo|yo|good morning|good afternoon|good evening|namaste|namaskar|kaise ho|kaisa hai|how are you|what's up|whats up|sup)[!?.\\s]*$"),
            Regex("^(?i)(hi|hello|hey)\\s+(babu|nayro|nero|qki|cutie)[!?.\\s]*$"),
            Regex("^(?i)(babu|nayro)\\s+(hi|hello|hey|namaste|kaise ho)[!?.\\s]*$"),
            Regex("^\\s*(नमस्ते|नमस्कार|हाय|हेलो|कैसे हो|कैसे हैं)[!?.।\\s]*$")
        )

        private val DAILY_PLANNING_REGEX = Regex(
            "(?i)(" +
                "aaj\\s+mujhe|important\\s+kaam|aaj\\s+ka\\s+plan|aaj\\s+ke\\s+(?:saare\\s+|sabhi\\s+|important\\s+)?kaam|" +
                "kaam\\s+organize|daily\\s+plan|plan\\s+my\\s+day|organize\\s+my\\s+day|organize\\s+(?:all\\s+)?(?:my\\s+)?(?:important\\s+)?tasks?|" +
                "tasks?\\s+for\\s+today|today'?s\\s+(?:plan|tasks|schedule|priorities)|kuch\\s+kaam\\s+karne|" +
                "आज\\s+मुझे|आज\\s+के\\s+(?:सारे\\s+|सभी\\s+|जरूरी\\s+|ज़रूरी\\s+|important\\s+)*काम|" +
                "काम\\s+(?:organize|ऑर्गनाइज|ऑर्गनाइज़|प्लान|plan|व्यवस्थित)\\s*(?:करने|करना|करो|कर\\s+दो)?|" +
                "दिन\\s+का\\s+प्लान|आज\\s+का\\s+प्लान" +
                ")"
        )

        /**
         * Accurately classifies user text into [ConversationLanguage.HINDI],
         * [ConversationLanguage.HINGLISH], or [ConversationLanguage.ENGLISH].
         */
        fun detectLanguage(text: String): ConversationLanguage {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return ConversationLanguage.ENGLISH
            if (DEVANAGARI_REGEX.containsMatchIn(trimmed)) {
                return ConversationLanguage.HINDI
            }
            val tokens = trimmed.lowercase()
                .replace(Regex("[^a-z\\s']"), " ")
                .split(Regex("\\s+"))
                .filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return ConversationLanguage.ENGLISH

            val hinglishHits = tokens.count { it in HINGLISH_MARKERS }
            if (hinglishHits >= 2 || (tokens.size <= 4 && hinglishHits >= 1)) {
                return ConversationLanguage.HINGLISH
            }
            return ConversationLanguage.ENGLISH
        }

        /**
         * Maps any [ActionType] to its primary [ExecutionDomain] inside NAYRO's unified architecture.
         */
        fun domainForAction(actionType: ActionType): ExecutionDomain = when (actionType) {
            ActionType.MAKE_PHONE_CALL,
            ActionType.OPEN_PHONE_DIALER,
            ActionType.CHECK_INCOMING_CALL,
            ActionType.MANAGE_CALL,
            ActionType.READ_CALL_HISTORY,
            ActionType.SEND_SMS,
            ActionType.READ_SMS,
            ActionType.MESSAGE_DRAFT_SEND,
            ActionType.WHATSAPP_READ_CONVERSATION,
            ActionType.RESOLVE_CONTACT,
            ActionType.SAVE_CONTACT,
            ActionType.COMPOSE_EMAIL,
            ActionType.READ_EMAIL_NOTIFICATIONS,
            ActionType.CHECK_COMMUNICATION_SPAM,
            ActionType.READ_NOTIFICATIONS,
            ActionType.SUMMARIZE_NOTIFICATIONS,
            ActionType.CLASSIFY_NOTIFICATIONS,
            ActionType.CREATE_TASK_FROM_NOTIFICATION -> ExecutionDomain.COMMUNICATION

            else -> ExecutionDomain.DEVICE
        }
    }

    private val lock = Any()
    private val rollingHistory = ArrayDeque<ConversationTurn>()
    private val dailyPlan = mutableListOf<DailyPlanItem>()

    @Volatile
    var currentLanguage: ConversationLanguage = ConversationLanguage.ENGLISH
        private set

    @Volatile
    var isDailyPlanningSessionActive: Boolean = false
        private set

    fun recordTurn(role: String, content: String, languageOverride: ConversationLanguage? = null) {
        val clean = content.trim()
        if (clean.isEmpty()) return
        val detected = languageOverride ?: if (role == "user") detectLanguage(clean) else currentLanguage
        synchronized(lock) {
            if (role == "user") {
                currentLanguage = detected
            }
            if (rollingHistory.size >= MAX_ROLLING_TURNS) {
                rollingHistory.removeFirst()
            }
            rollingHistory.addLast(ConversationTurn(role = role, content = clean, language = detected))
        }
    }

    /**
     * Restores the provider's multi-turn prompt window from durable Room conversation history.
     */
    fun restoreHistoryFromStorage(messages: List<ConversationEntity>) {
        if (messages.isEmpty()) return
        synchronized(lock) {
            rollingHistory.clear()
            messages.sortedBy { it.timestamp }.takeLast(MAX_ROLLING_TURNS).forEach { message ->
                val role = if (message.sender.equals("user", ignoreCase = true)) "user" else "assistant"
                val detected = if (role == "user") detectLanguage(message.content) else currentLanguage
                rollingHistory.addLast(
                    ConversationTurn(role = role, content = message.content, language = detected)
                )
                if (role == "user") currentLanguage = detected
            }
        }
    }

    fun getRecentHistory(limit: Int = 10): List<ConversationTurn> = synchronized(lock) {
        rollingHistory.takeLast(limit.coerceAtLeast(1))
    }

    fun clearHistory() = synchronized(lock) {
        rollingHistory.clear()
        isDailyPlanningSessionActive = false
    }

    fun addDailyPlanItem(
        title: String,
        priority: String = "NORMAL",
        scheduledHint: String? = null,
        domain: ExecutionDomain = ExecutionDomain.DEVICE
    ): DailyPlanItem {
        val item = DailyPlanItem(
            title = title.trim(),
            priority = priority.uppercase(),
            scheduledHint = scheduledHint,
            domain = domain
        )
        synchronized(lock) {
            dailyPlan.add(item)
        }
        return item
    }

    fun markPlanItemCompleted(titleOrId: String): Boolean = synchronized(lock) {
        val idx = dailyPlan.indexOfFirst {
            it.id == titleOrId || it.title.contains(titleOrId, ignoreCase = true)
        }
        if (idx == -1) return false
        dailyPlan[idx] = dailyPlan[idx].copy(isCompleted = true)
        true
    }

    fun getDailyPlan(): List<DailyPlanItem> = synchronized(lock) { dailyPlan.toList() }

    fun clearDailyPlan() = synchronized(lock) { dailyPlan.clear() }

    /**
     * Analyzes an incoming user message for language, greetings, daily planning dialogue,
     * and follow-up conversational context.
     */
    fun analyzeUserMessage(rawText: String): ConversationalAnalysis {
        val cleaned = rawText.trim()
            .replaceFirst(Regex("^(?i)(hey babu|hi babu|babu|hey nayro|hi nayro|nayro|हे बाबू|हाय बाबू|बाबू|हे नायरो|हाय नायरो|नायरो)\\s*,?\\s*"), "")
            .trim()
            .ifBlank { rawText.trim() }

        val lang = detectLanguage(rawText)
        currentLanguage = lang

        val isGreeting = GREETING_PATTERNS.any { it.matches(rawText.trim()) || it.matches(cleaned) }
        val isPlanningIntent = DAILY_PLANNING_REGEX.containsMatchIn(rawText)
        val extractedItems = extractInlinePlanItems(cleaned)
        if (isPlanningIntent) {
            isDailyPlanningSessionActive = true
        }
        if (extractedItems.isNotEmpty() && (isPlanningIntent || isDailyPlanningSessionActive)) {
            for (item in extractedItems) {
                val lower = item.lowercase()
                val domain = when {
                    lower.contains("call") || lower.contains("कॉल") || lower.contains("फ़ोन") || lower.contains("फोन") ||
                        lower.contains("whatsapp") || lower.contains("व्हाट्सएप") || lower.contains("वॉट्सऐप") ||
                        lower.contains("sms") || lower.contains("message") || lower.contains("मैसेज") ||
                        lower.contains("संदेश") || lower.contains("email") || lower.contains("mail") || lower.contains("ईमेल") ->
                        ExecutionDomain.COMMUNICATION
                    else -> ExecutionDomain.DEVICE
                }
                val priority = if (lower.contains("urgent") || lower.contains("important") ||
                    lower.contains("zaroori") || lower.contains("जरूरी") || lower.contains("ज़रूरी")
                ) {
                    "HIGH"
                } else {
                    "NORMAL"
                }
                addDailyPlanItem(title = item, priority = priority, domain = domain)
            }
        }

        val isFollowUp = synchronized(lock) {
            rollingHistory.isNotEmpty() && (cleaned.length < 48 || isDailyPlanningSessionActive)
        }

        return ConversationalAnalysis(
            language = lang,
            isGreeting = isGreeting,
            isDailyPlanningDiscussion = isPlanningIntent || (isDailyPlanningSessionActive && extractedItems.isNotEmpty()),
            isFollowUpQuestion = isFollowUp,
            extractedPlanItems = extractedItems,
            activeDailyPlan = getDailyPlan()
        )
    }

    private fun extractInlinePlanItems(text: String): List<String> {
        val numbered = Regex("(?:^|\\n|\\s)\\d+[.)]\\s*(.+?)(?=(?:\\s+\\d+[.)]\\s)|[\\n,;]|$)").findAll(text)
            .map { it.groupValues[1].trim() }
            .filter { it.length >= 3 }
            .toList()
        if (numbered.isNotEmpty()) return numbered

        // Look for explicit colon list like "aaj ka plan: email bhejna, Rahul ko call, report banana"
        val colonIdx = text.indexOf(':')
        if (colonIdx in 3 until text.length - 3) {
            val tail = text.substring(colonIdx + 1)
            val split = tail.split(Regex("(?:,|\\band\\b|\\baur\\b)")).map { it.trim() }.filter { it.length >= 3 }
            if (split.size >= 2) return split
        }
        return emptyList()
    }

    /**
     * Builds the unified multilingual system prompt for Gemini, xAI Grok, OpenRouter, and compatible providers.
     */
    fun buildConversationalSystemPrompt(
        assistantName: String,
        language: ConversationLanguage,
        memoryContext: String? = null
    ): String = buildString {
        append("You are $assistantName (NAYRO), a warm, intelligent, autonomous Android personal AI agent ")
        append("unifying Device control, Voice interaction, and Communication management. ")
        when (language) {
            ConversationLanguage.HINDI ->
                append("Respond naturally and clearly in Hindi (Devanagari script), matching the user's tone. ")
            ConversationLanguage.HINGLISH ->
                append("Respond naturally in friendly Hinglish (Hindi written in Roman script with natural English words), matching the user's style. ")
            ConversationLanguage.ENGLISH ->
                append("Respond naturally, concisely, and helpfully in English. ")
        }
        append("Support multi-turn conversations, ask helpful follow-up questions when planning the user's day, ")
        append("and never claim an Android device action, call, or message succeeded unless executed and verified. ")
        append("CRITICAL CALLING RULE: You can help initiate, answer, reject, or end calls when authorized, ")
        append("but you NEVER speak to remote callers during a phone call—the user speaks personally. ")

        val currentPlan = getDailyPlan()
        if (currentPlan.isNotEmpty()) {
            append("\nActive Today's Plan:\n")
            currentPlan.forEachIndexed { i, item ->
                val mark = if (item.isCompleted) "[DONE]" else "[${item.priority}]"
                append("${i + 1}. $mark ${item.title} (${item.domain.displayName})\n")
            }
        }
        if (!memoryContext.isNullOrBlank()) {
            append("\nUser Approved Shared Memory:\n$memoryContext\n")
        }
    }

    /**
     * Builds the multi-turn chat message list for cloud LLM calls, retaining recent conversational context.
     */
    fun buildProviderMessages(currentUserMessage: String, maxHistoryTurns: Int = 8): List<AIChatMessage> {
        val recent = getRecentHistory(maxHistoryTurns)
        val result = recent.map { AIChatMessage(role = it.role, content = it.content) }.toMutableList()
        if (result.lastOrNull()?.role != "user" || result.lastOrNull()?.content != currentUserMessage.trim()) {
            result.add(AIChatMessage(role = "user", content = currentUserMessage.trim()))
        }
        return result
    }

    /**
     * Generates a natural, human-like response for greetings or interactive daily planning
     * in Hindi, English, or Hinglish—working immediately even offline or without cloud API keys.
     */
    fun buildLocalConversationalReply(
        assistantName: String,
        analysis: ConversationalAnalysis,
        userMessage: String
    ): String? {
        if (analysis.isGreeting) {
            return when (analysis.language) {
                ConversationLanguage.HINDI ->
                    "नमस्ते! मैं $assistantName हूँ। आज मैं आपकी क्या मदद कर सकता हूँ?"
                ConversationLanguage.HINGLISH ->
                    "Hi! Main $assistantName hoon. Bataiye, aaj main aapki kya madad kar sakta hoon?"
                ConversationLanguage.ENGLISH ->
                    "Hi, how can I help you today?"
            }
        }

        if (analysis.isDailyPlanningDiscussion) {
            val plan = analysis.activeDailyPlan
            return if (plan.isEmpty()) {
                when (analysis.language) {
                    ConversationLanguage.HINDI ->
                        "ज़रूर! बताइए आज के कौन-कौन से ज़रूरी काम हैं और उनकी प्राथमिकता क्या है? मैं उन्हें आपके डेली प्लान में व्यवस्थित कर दूँगा और जिन कामों की आप अनुमति देंगे, उन्हें पूरा करने में मदद करूँगा।"
                    ConversationLanguage.HINGLISH ->
                        "Bilkul! Bataiye aaj kaun-kaun se important kaam karne hain aur sabse pehle kya priority hai? Main unka structured plan bana doonga aur jo tasks aap authorize karenge unhe execute bhi kar doonga."
                    ConversationLanguage.ENGLISH ->
                        "Of course! Tell me what important tasks you need to get done today and which ones are top priority. I'll organize your daily plan and execute any authorized actions for you."
                }
            } else {
                val listText = plan.mapIndexed { idx, item ->
                    "${idx + 1}. [${item.priority}] ${item.title}"
                }.joinToString("\n")
                when (analysis.language) {
                    ConversationLanguage.HINDI ->
                        "मैंने आपके आज के प्लान में ये काम जोड़ दिए हैं:\n$listText\n\nक्या आप किसी काम का समय या प्राथमिकता बदलना चाहते हैं, या मैं अभी इनमें से कोई काम शुरू करूँ?"
                    ConversationLanguage.HINGLISH ->
                        "Maine aapke aaj ke plan mein ye tasks organize kar diye hain:\n$listText\n\nKya aap koi aur kaam jodna chahte hain, ya main pehle task se shuru karoon?"
                    ConversationLanguage.ENGLISH ->
                        "I've organized these items into your plan for today:\n$listText\n\nWould you like to add priorities or timings, or should I start executing the first authorized task?"
                }
            }
        }
        return null
    }
}
