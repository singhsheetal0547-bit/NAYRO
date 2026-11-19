package com.example.core.memory

enum class MemoryTier(val displayName: String) {
    SHORT_TERM("Short-Term Memory"),
    LONG_TERM_USER_APPROVED("Long-Term User-Approved Memory"),
    TASK_MEMORY("Task Memory")
}

enum class MemoryCategory(val displayName: String, val tier: MemoryTier = MemoryTier.LONG_TERM_USER_APPROVED) {
    USER_PREFERENCE("User Preferences", MemoryTier.LONG_TERM_USER_APPROVED),
    ASSISTANT_PREFERENCE("Assistant Preferences", MemoryTier.LONG_TERM_USER_APPROVED),
    CONVERSATION_SUMMARY("Conversation Summaries", MemoryTier.SHORT_TERM),
    IMPORTANT_FACT("Important Facts", MemoryTier.LONG_TERM_USER_APPROVED),
    FREQUENT_INSTRUCTION("Frequently Used Instructions", MemoryTier.LONG_TERM_USER_APPROVED),
    PROJECT_CONTEXT("Project Context", MemoryTier.TASK_MEMORY),
    TASK_HISTORY("Task History", MemoryTier.TASK_MEMORY),
    SAVED_NOTE("Saved Notes", MemoryTier.LONG_TERM_USER_APPROVED),
    REMINDER("User Reminders", MemoryTier.TASK_MEMORY),
    TEMPORARY_SESSION("Temporary Session Context", MemoryTier.SHORT_TERM),
    SHORT_TERM_MEMORY("Short-Term Memory", MemoryTier.SHORT_TERM),
    LONG_TERM_USER_APPROVED("Long-Term User-Approved Memory", MemoryTier.LONG_TERM_USER_APPROVED),
    TASK_MEMORY("Task Memory", MemoryTier.TASK_MEMORY)
}

data class ExtractedMemoryCandidate(
    val category: MemoryCategory,
    val key: String,
    val value: String,
    val originalPrompt: String,
    val tier: MemoryTier = category.tier,
    val expiresAtMs: Long? = null
)

