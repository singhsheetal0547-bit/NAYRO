package com.example.core.memory

import com.example.data.local.entities.MemoryEntity
import com.example.data.repository.NayroRepository

/**
 * Manages user memory lifecycle: candidate extraction, privacy filtering,
 * storage, and contextual retrieval for the Agent.
 */
class MemoryManager(private val repository: NayroRepository) {

    suspend fun processPromptForMemory(prompt: String): MemoryEntity? {
        val candidate = MemoryClassifier.extractCandidate(prompt) ?: return null

        // Security check
        if (!MemoryPrivacyGuard.isAllowedToPersist(candidate.value)) {
            return null
        }

        val sanitized = MemoryPrivacyGuard.sanitizeContent(candidate.value)
        val entity = MemoryEntity(
            category = candidate.category.name,
            keyName = candidate.key,
            memoryValue = sanitized,
            userConfirmed = true
        )
        repository.addMemory(entity)
        return entity
    }

    suspend fun saveDirectMemory(category: MemoryCategory, key: String, value: String): Boolean {
        if (!MemoryPrivacyGuard.isAllowedToPersist(value)) return false

        val entity = MemoryEntity(
            category = category.name,
            keyName = key,
            memoryValue = MemoryPrivacyGuard.sanitizeContent(value),
            userConfirmed = true
        )
        repository.addMemory(entity)
        return true
    }

    /**
     * Builds a compact context snippet from existing memories that match query keywords.
     */
    fun buildRelevantContext(prompt: String, allMemories: List<MemoryEntity>): String {
        if (allMemories.isEmpty()) return ""

        val queryWords = prompt.lowercase().split("\\s+".toRegex()).filter { it.length > 3 }
        val matched = allMemories.filter { mem ->
            val memText = "${mem.keyName} ${mem.memoryValue}".lowercase()
            queryWords.any { word -> memText.contains(word) } || mem.category == MemoryCategory.USER_PREFERENCE.name
        }.take(4)

        if (matched.isEmpty()) return ""

        return matched.joinToString(separator = "\n", prefix = "Saved Context & Preferences:\n") { mem ->
            "- [${mem.category}] ${mem.memoryValue}"
        }
    }
}
