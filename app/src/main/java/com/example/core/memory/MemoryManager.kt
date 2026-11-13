package com.example.core.memory

import com.example.data.local.entities.MemoryEntity
import com.example.data.repository.NayroRepository
import kotlinx.coroutines.flow.firstOrNull

/**
 * Manages user memory lifecycle: candidate extraction, privacy filtering,
 * storage, and contextual retrieval for the Agent (Phase 6).
 */
class MemoryManager(private val repository: NayroRepository) {

    private val cachedMemories = java.util.Collections.synchronizedList(mutableListOf<MemoryEntity>())

    fun getCachedMemories(): List<MemoryEntity> {
        synchronized(cachedMemories) {
            return cachedMemories.toList()
        }
    }

    suspend fun refreshCachedMemories(): List<MemoryEntity> {
        val loaded = try {
            repository.memories.firstOrNull().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        synchronized(cachedMemories) {
            if (loaded.isNotEmpty()) {
                cachedMemories.clear()
                cachedMemories.addAll(loaded)
            }
            return cachedMemories.toList()
        }
    }

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
        synchronized(cachedMemories) {
            cachedMemories.removeAll { it.keyName == entity.keyName && it.keyName != "user_preference" }
            cachedMemories.add(0, entity)
        }
        try {
            repository.addMemory(entity)
        } catch (_: Exception) {}
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
        synchronized(cachedMemories) {
            cachedMemories.removeAll { it.keyName == key && key != "user_preference" }
            cachedMemories.add(0, entity)
        }
        try {
            repository.addMemory(entity)
        } catch (_: Exception) {}
        return true
    }

    /**
     * Builds a compact context snippet from existing memories that match query keywords.
     */
    fun buildRelevantContext(prompt: String, allMemories: List<MemoryEntity>): String {
        val pool = if (allMemories.isNotEmpty()) allMemories else getCachedMemories()
        if (pool.isEmpty()) return ""

        val queryWords = prompt.lowercase().split("\\s+".toRegex()).filter { it.length > 3 }
        val matched = pool.filter { mem ->
            val memText = "${mem.keyName} ${mem.memoryValue}".lowercase()
            queryWords.any { word -> memText.contains(word) } || mem.category == MemoryCategory.USER_PREFERENCE.name
        }.take(6)

        if (matched.isEmpty()) return ""

        return matched.joinToString(separator = "\n", prefix = "Saved Context & Preferences:\n") { mem ->
            "- [${mem.category}/${mem.keyName}] ${mem.memoryValue}"
        }
    }
}
