package com.example.core.memory

enum class MemoryCategory(val displayName: String) {
    USER_PREFERENCE("User Preferences"),
    ASSISTANT_PREFERENCE("Assistant Preferences"),
    CONVERSATION_SUMMARY("Conversation Summaries"),
    IMPORTANT_FACT("Important Facts"),
    FREQUENT_INSTRUCTION("Frequently Used Instructions"),
    PROJECT_CONTEXT("Project Context"),
    TASK_HISTORY("Task History"),
    SAVED_NOTE("Saved Notes"),
    REMINDER("User Reminders"),
    TEMPORARY_SESSION("Temporary Session Context")
}

data class ExtractedMemoryCandidate(
    val category: MemoryCategory,
    val key: String,
    val value: String,
    val originalPrompt: String
)
