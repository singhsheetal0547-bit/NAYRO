package com.example.core.memory

object MemoryClassifier {

    private val PREFERENCE_KEYWORDS = listOf("pasand", "like", "prefer", "favorite", "favourite", "chahta", "hamesha")
    private val FACT_KEYWORDS = listOf("mera naam", "my name", "birthday", "birthday kab", "kaam karta", "address", "stay in", "rehta hoon")
    private val INSTRUCTION_KEYWORDS = listOf("hamesha aise", "rule", "guideline", "instruct", "tarika")

    fun extractCandidate(prompt: String): ExtractedMemoryCandidate? {
        val trimmed = prompt.trim()
        val lower = trimmed.lowercase()

        // Check if instruction requests memorization
        val isExplicitMemory = lower.startsWith("yaad rakho") ||
                lower.startsWith("remember that") ||
                lower.startsWith("save this") ||
                lower.startsWith("note this") ||
                lower.contains("yaad rakhna")

        if (!isExplicitMemory) {
            return null
        }

        // Clean memory body text
        val cleaned = trimmed
            .replaceFirst(Regex("^(?i)(yaad rakho ki|yaad rakho|remember that|remember|save this:|note this:)\\s*"), "")
            .trim()

        if (cleaned.isBlank()) return null

        val category = when {
            PREFERENCE_KEYWORDS.any { lower.contains(it) } -> MemoryCategory.USER_PREFERENCE
            FACT_KEYWORDS.any { lower.contains(it) } -> MemoryCategory.IMPORTANT_FACT
            INSTRUCTION_KEYWORDS.any { lower.contains(it) } -> MemoryCategory.FREQUENT_INSTRUCTION
            else -> MemoryCategory.USER_PREFERENCE
        }

        val keyName = when (category) {
            MemoryCategory.USER_PREFERENCE -> "user_preference"
            MemoryCategory.IMPORTANT_FACT -> "user_fact"
            MemoryCategory.FREQUENT_INSTRUCTION -> "frequent_instruction"
            else -> "general_memory"
        }

        return ExtractedMemoryCandidate(
            category = category,
            key = keyName,
            value = cleaned,
            originalPrompt = prompt
        )
    }
}
