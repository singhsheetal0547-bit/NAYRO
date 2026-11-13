package com.example.core.memory

object MemoryClassifier {

    private val PREFERENCE_KEYWORDS = listOf("pasand", "like", "prefer", "favorite", "favourite", "chahta", "hamesha", "song", "gaana", "artist", "singer", "music")
    private val FACT_KEYWORDS = listOf("mera naam", "my name", "birthday", "birthday kab", "kaam karta", "address", "stay in", "rehta hoon")
    private val INSTRUCTION_KEYWORDS = listOf("hamesha aise", "rule", "guideline", "instruct", "tarika")

    fun extractCandidate(prompt: String): ExtractedMemoryCandidate? {
        val trimmed = prompt.trim()
        val lower = trimmed.lowercase()

        // Check if instruction requests memorization or states an explicit personal preference
        val isExplicitMemory = lower.startsWith("yaad rakho") ||
            lower.startsWith("remember that") ||
            lower.startsWith("remember my") ||
            lower.startsWith("save this") ||
            lower.startsWith("note this") ||
            lower.contains("yaad rakhna") ||
            lower.startsWith("mera favourite song") ||
            lower.startsWith("my favourite song") ||
            lower.startsWith("my favorite song")

        if (!isExplicitMemory) {
            return null
        }

        // Clean memory body text
        val cleaned = trimmed
            .replaceFirst(Regex("^(?i)(yaad rakho ki|yaad rakho|remember that|remember|save this:|note this:)\\s*"), "")
            .trim()
            .removeSuffix(".")

        if (cleaned.isBlank()) return null

        val category = when {
            PREFERENCE_KEYWORDS.any { lower.contains(it) } -> MemoryCategory.USER_PREFERENCE
            FACT_KEYWORDS.any { lower.contains(it) } -> MemoryCategory.IMPORTANT_FACT
            INSTRUCTION_KEYWORDS.any { lower.contains(it) } -> MemoryCategory.FREQUENT_INSTRUCTION
            else -> MemoryCategory.USER_PREFERENCE
        }

        val keyName = when {
            lower.contains("favourite song") || lower.contains("favorite song") || lower.contains("pasandida gaana") -> "favourite_song"
            lower.contains("favourite artist") || lower.contains("favorite artist") || lower.contains("favourite singer") -> "favourite_artist"
            lower.contains("music app") || lower.contains("spotify") || lower.contains("youtube music") -> "preferred_music_app"
            lower.contains("language") || lower.contains("bhasha") -> "preferred_language"
            category == MemoryCategory.USER_PREFERENCE -> "user_preference"
            category == MemoryCategory.IMPORTANT_FACT -> "user_fact"
            category == MemoryCategory.FREQUENT_INSTRUCTION -> "frequent_instruction"
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
