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

    /**
     * Section 7 — Ranks non-expired memories by keyword relevance and recency.
     */
    fun rankMemoriesForQuery(
        query: String,
        allMemories: List<MemoryEntity> = getCachedMemories(),
        nowMs: Long = System.currentTimeMillis()
    ): List<MemoryEntity> {
        val valid = filterNonExpiredMemories(allMemories, nowMs)
        val tokens = query.lowercase().split(Regex("\\s+")).filter { it.length >= 2 }
        return valid.sortedByDescending { mem ->
            val hay = "${mem.keyName} ${mem.memoryValue} ${mem.category}".lowercase()
            val matchHits = tokens.count { tok -> hay.contains(tok) }
            val tierBoost = if (mem.userConfirmed) 2 else 0
            (matchHits * 100L) + (tierBoost * 10L) + (mem.createdAt / 1_000_000L)
        }
    }

    /**
     * Section 7 — Filters out expired short-term or temporary memories while keeping permanent user-approved memories.
     */
    fun filterNonExpiredMemories(
        memories: List<MemoryEntity> = getCachedMemories(),
        nowMs: Long = System.currentTimeMillis(),
        shortTermTtlMs: Long = 3_600_000L // 1 hour default TTL for TEMPORARY_SESSION / SHORT_TERM_MEMORY
    ): List<MemoryEntity> {
        return memories.filter { mem ->
            val isShortTerm = mem.category in setOf(
                MemoryCategory.TEMPORARY_SESSION.name,
                MemoryCategory.SHORT_TERM_MEMORY.name
            )
            if (isShortTerm) {
                (nowMs - mem.createdAt) < shortTermTtlMs
            } else {
                true
            }
        }
    }

    /**
     * Section 7 — Detects whether an identical key + normalized value memory already exists.
     */
    fun isDuplicateMemory(
        key: String,
        value: String,
        memories: List<MemoryEntity> = getCachedMemories()
    ): Boolean {
        val normKey = key.trim().lowercase()
        val normVal = value.trim().lowercase()
        return memories.any {
            it.keyName.trim().lowercase() == normKey &&
                it.memoryValue.trim().lowercase() == normVal
        }
    }

    /**
     * Section 7 — Updates or corrects an existing memory entry by key name after validating privacy rules.
     */
    suspend fun correctMemoryValue(
        key: String,
        newValue: String,
        category: MemoryCategory = MemoryCategory.USER_PREFERENCE
    ): MemoryEntity? {
        if (!MemoryPrivacyGuard.isAllowedToPersist(newValue)) return null
        val sanitized = MemoryPrivacyGuard.sanitizeContent(newValue)
        val updatedEntity = MemoryEntity(
            category = category.name,
            keyName = key.trim(),
            memoryValue = sanitized,
            userConfirmed = true,
            createdAt = System.currentTimeMillis()
        )
        synchronized(cachedMemories) {
            cachedMemories.removeAll { it.keyName.equals(key.trim(), ignoreCase = true) }
            cachedMemories.add(0, updatedEntity)
        }
        try {
            repository.addMemory(updatedEntity)
        } catch (_: Exception) {}
        return updatedEntity
    }

    /**
     * Section 7 — Memory must NEVER override a direct, newer user instruction.
     * If [directUserValue] is provided, it takes strict precedence over [storedMemoryValue].
     */
    fun resolveWithDirectInstructionPriority(
        directUserValue: String?,
        storedMemoryValue: String?
    ): String? {
        val cleanDirect = directUserValue?.trim()
        if (!cleanDirect.isNullOrEmpty()) {
            return cleanDirect
        }
        return storedMemoryValue?.trim()?.takeIf { it.isNotEmpty() }
    }
}

