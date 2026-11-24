package com.example.core.memory

import com.example.core.security.EncryptedStorageManager
import com.example.data.local.entities.MemoryEntity
import com.example.data.repository.NayroRepository
import kotlinx.coroutines.flow.firstOrNull

/**
 * Manages user memory lifecycle: candidate extraction, privacy filtering,
 * encrypted semantic indexing, consolidation, conversation summarization,
 * workflow memory, and contextual retrieval for the Agent.
 */
class MemoryManager(
    private val repository: NayroRepository,
    encryptedStorageManager: EncryptedStorageManager? = null
) {

    val semanticEngine = SemanticMemoryEngine(encryptedStorageManager)
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
        semanticEngine.upsertRecord(
            tier = when (candidate.category) {
                MemoryCategory.SHORT_TERM_MEMORY, MemoryCategory.TEMPORARY_SESSION -> SemanticMemoryTier.SHORT_TERM
                else -> SemanticMemoryTier.LONG_TERM
            },
            category = candidate.category.name,
            key = candidate.key,
            content = sanitized,
            tags = setOf(candidate.category.name.lowercase(), candidate.key.lowercase()),
            importance = 1.25f
        )
        try {
            repository.addMemory(entity)
        } catch (_: Exception) {}
        return entity
    }

    suspend fun saveDirectMemory(category: MemoryCategory, key: String, value: String): Boolean {
        if (!MemoryPrivacyGuard.isAllowedToPersist(value)) return false

        val sanitized = MemoryPrivacyGuard.sanitizeContent(value)
        val entity = MemoryEntity(
            category = category.name,
            keyName = key,
            memoryValue = sanitized,
            userConfirmed = true
        )
        synchronized(cachedMemories) {
            cachedMemories.removeAll { it.keyName == key && key != "user_preference" }
            cachedMemories.add(0, entity)
        }
        semanticEngine.upsertRecord(
            tier = when (category) {
                MemoryCategory.SHORT_TERM_MEMORY, MemoryCategory.TEMPORARY_SESSION -> SemanticMemoryTier.SHORT_TERM
                else -> SemanticMemoryTier.LONG_TERM
            },
            category = category.name,
            key = key,
            content = sanitized,
            tags = setOf(category.name.lowercase(), key.lowercase()),
            importance = 1.2f
        )
        try {
            repository.addMemory(entity)
        } catch (_: Exception) {}
        return true
    }

    /**
     * Records a completed or failed multi-step workflow into persistent semantic workflow memory.
     */
    fun recordWorkflowMemory(goal: String, actions: List<String>, succeeded: Boolean, details: String) {
        semanticEngine.recordWorkflowOutcome(goal, actions, succeeded, details)
    }

    /**
     * Summarizes multi-turn conversation history into persistent semantic summary memory.
     */
    fun summarizeConversation(turns: List<Pair<String, String>>, sessionLabel: String = "recent_session"): SemanticMemoryRecord? {
        return semanticEngine.summarizeAndPersistConversation(turns, sessionLabel)
    }

    /**
     * Consolidates short-term, long-term, and duplicate memories.
     */
    fun consolidateMemories(): ConsolidationReport = semanticEngine.consolidateMemory()

    /**
     * Builds a compact context snippet from semantic memory (long-term facts, summaries, workflows)
     * plus Room memories using cross-lingual semantic retrieval.
     */
    fun buildRelevantContext(prompt: String, allMemories: List<MemoryEntity>): String {
        val pool = if (allMemories.isNotEmpty()) allMemories else getCachedMemories()
        val semanticHits = semanticEngine.semanticSearch(
            query = prompt,
            additionalRoomMemories = pool,
            limit = 6,
            minScore = 0.08f
        )

        if (semanticHits.isNotEmpty()) {
            return semanticHits.joinToString(separator = "\n", prefix = "Saved Context & Preferences:\n") { hit ->
                "- [${hit.record.category}/${hit.record.key}] ${hit.record.content}"
            }
        }

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
     * Section 7 — Ranks non-expired memories by semantic + keyword relevance and recency.
     */
    fun rankMemoriesForQuery(
        query: String,
        allMemories: List<MemoryEntity> = getCachedMemories(),
        nowMs: Long = System.currentTimeMillis()
    ): List<MemoryEntity> {
        val valid = filterNonExpiredMemories(allMemories, nowMs)
        val tokens = query.lowercase().split(Regex("\\s+")).filter { it.length >= 2 }
        val queryVec = semanticEngine.buildFeatureVector(query)
        return valid.sortedByDescending { mem ->
            val hay = "${mem.keyName} ${mem.memoryValue} ${mem.category}".lowercase()
            val matchHits = tokens.count { tok -> hay.contains(tok) }
            val semScore = (semanticEngine.cosineSimilarity(queryVec, semanticEngine.buildFeatureVector(hay)) * 100f).toLong()
            val tierBoost = if (mem.userConfirmed) 2 else 0
            (matchHits * 100L) + semScore + (tierBoost * 10L) + (mem.createdAt / 1_000_000L)
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
        semanticEngine.upsertRecord(
            tier = SemanticMemoryTier.LONG_TERM,
            category = category.name,
            key = key.trim(),
            content = sanitized,
            importance = 1.3f
        )
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


