package com.example.core.memory

import com.example.core.security.EncryptedStorageManager
import com.example.data.local.entities.MemoryEntity
import java.util.UUID
import kotlin.math.ln
import kotlin.math.sqrt

enum class SemanticMemoryTier {
    SHORT_TERM,
    LONG_TERM,
    CONVERSATION_SUMMARY,
    WORKFLOW_SUCCESS,
    WORKFLOW_FAILURE
}

data class SemanticMemoryRecord(
    val id: String = UUID.randomUUID().toString(),
    val tier: SemanticMemoryTier,
    val category: String,
    val key: String,
    val content: String,
    val tags: Set<String> = emptySet(),
    val importance: Float = 1.0f,
    val accessCount: Int = 1,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis(),
    val expiresAtMs: Long? = null
) {
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean =
        expiresAtMs != null && nowMs >= expiresAtMs
}

data class SemanticSearchResult(
    val record: SemanticMemoryRecord,
    val score: Float
)

data class ConsolidationReport(
    val mergedDuplicates: Int,
    val promotedToLongTerm: Int,
    val expiredPurged: Int,
    val remainingCount: Int
)

/**
 * Section 4 & Section 12 — Advanced Persistent Semantic Memory Engine for NAYRO.
 *
 * Implements:
 * - Short-term working memory with TTL expiration and automatic promotion of repeated facts
 * - Long-term user preference & personal fact memory
 * - Conversation summarization into structured persistent digests
 * - Workflow memory (recording successful & failed multi-step task patterns)
 * - Cross-lingual (English / Hindi / Hinglish) semantic retrieval via TF-IDF + char-trigram cosine similarity
 * - Encrypted persistence via Android Keystore-backed [EncryptedStorageManager]
 * - Explicit user controls: search, inspect, update, delete, and clear-all
 */
class SemanticMemoryEngine(
    private val encryptedStorageManager: EncryptedStorageManager? = null
) {
    companion object {
        private const val PREF_KEY_ENCRYPTED_RECORDS = "nayro_semantic_memory_v1"
        private const val DEFAULT_SHORT_TERM_TTL_MS = 3_600_000L // 1 hour
        private const val MAX_STORED_RECORDS = 500
        private const val PROMOTION_THRESHOLD_ACCESS_COUNT = 2
        private const val DEDUP_SIMILARITY_THRESHOLD = 0.86f

        /**
         * Multilingual concept bridges so Hindi/Hinglish queries match English memories and vice versa.
         */
        private val CONCEPT_SYNONYMS: Map<String, Set<String>> = mapOf(
            "music" to setOf("music", "song", "songs", "gaana", "gaane", "singer", "artist", "playlist", "youtube", "spotify", "favourite", "favorite", "mood", "sad", "happy", "chill", "lofi", "melody"),
            "mood" to setOf("mood", "sad", "off", "upset", "stressed", "tired", "happy", "khush", "udaas", "dukhi", "relax", "calm", "music", "gaana", "song"),
            "call" to setOf("call", "phone", "dial", "contact", "number", "baat", "kॉल", "फोन"),
            "message" to setOf("message", "msg", "whatsapp", "sms", "chat", "text", "sandesh", "bhejo"),
            "schedule" to setOf("meeting", "calendar", "schedule", "reminder", "alarm", "time", "kal", "aaj", "tomorrow", "today", "deadline"),
            "work" to setOf("project", "code", "coding", "python", "kotlin", "react", "javascript", "app", "bug", "office", "work", "job"),
            "food" to setOf("food", "khana", "dinner", "lunch", "breakfast", "coffee", "tea", "chai", "restaurant", "recipe"),
            "travel" to setOf("travel", "trip", "flight", "train", "hotel", "maps", "route", "location", "city", "home", "ghar")
        )
    }

    private val lock = Any()
    private val records = mutableListOf<SemanticMemoryRecord>()
    var currentTimeProvider: () -> Long = { System.currentTimeMillis() }

    init {
        loadFromEncryptedStorage()
    }

    fun getAllRecords(includeExpired: Boolean = false): List<SemanticMemoryRecord> {
        val now = currentTimeProvider()
        return synchronized(lock) {
            if (includeExpired) records.toList() else records.filterNot { it.isExpired(now) }
        }
    }

    fun upsertRecord(
        tier: SemanticMemoryTier,
        category: String,
        key: String,
        content: String,
        tags: Set<String> = emptySet(),
        importance: Float = 1.0f,
        ttlMs: Long? = if (tier == SemanticMemoryTier.SHORT_TERM) DEFAULT_SHORT_TERM_TTL_MS else null
    ): SemanticMemoryRecord? {
        if (!MemoryPrivacyGuard.isAllowedToPersist(content)) return null
        val sanitized = MemoryPrivacyGuard.sanitizeContent(content).trim()
        if (sanitized.isEmpty()) return null

        val cleanKey = key.trim().ifEmpty { "fact_${UUID.randomUUID().toString().take(8)}" }
        val now = currentTimeProvider()
        val expiresAt = ttlMs?.let { now + it }

        val result = synchronized(lock) {
            // Check exact key + tier match first
            val existingIdx = records.indexOfFirst {
                it.key.equals(cleanKey, ignoreCase = true) && it.tier == tier
            }
            if (existingIdx >= 0) {
                val prev = records[existingIdx]
                val nextCount = prev.accessCount + 1
                val promotedTier = if (tier == SemanticMemoryTier.SHORT_TERM && nextCount >= PROMOTION_THRESHOLD_ACCESS_COUNT) {
                    SemanticMemoryTier.LONG_TERM
                } else {
                    tier
                }
                val updated = prev.copy(
                    tier = promotedTier,
                    category = category,
                    content = sanitized,
                    tags = prev.tags + tags,
                    importance = maxOf(prev.importance, importance),
                    accessCount = nextCount,
                    updatedAtMs = now,
                    expiresAtMs = if (promotedTier == SemanticMemoryTier.LONG_TERM) null else expiresAt
                )
                records[existingIdx] = updated
                updated
            } else {
                // Check semantic duplicate in same tier
                val queryVec = buildFeatureVector("$cleanKey $sanitized")
                val dupIdx = records.indexOfFirst { existing ->
                    existing.tier == tier &&
                        cosineSimilarity(queryVec, buildFeatureVector("${existing.key} ${existing.content}")) >= DEDUP_SIMILARITY_THRESHOLD
                }
                if (dupIdx >= 0) {
                    val prev = records[dupIdx]
                    val nextCount = prev.accessCount + 1
                    val promotedTier = if (tier == SemanticMemoryTier.SHORT_TERM && nextCount >= PROMOTION_THRESHOLD_ACCESS_COUNT) {
                        SemanticMemoryTier.LONG_TERM
                    } else {
                        tier
                    }
                    val merged = prev.copy(
                        tier = promotedTier,
                        content = if (sanitized.length >= prev.content.length) sanitized else prev.content,
                        tags = prev.tags + tags,
                        importance = maxOf(prev.importance, importance),
                        accessCount = nextCount,
                        updatedAtMs = now,
                        expiresAtMs = if (promotedTier == SemanticMemoryTier.LONG_TERM) null else prev.expiresAtMs
                    )
                    records[dupIdx] = merged
                    merged
                } else {
                    val created = SemanticMemoryRecord(
                        tier = tier,
                        category = category,
                        key = cleanKey,
                        content = sanitized,
                        tags = tags,
                        importance = importance,
                        accessCount = 1,
                        createdAtMs = now,
                        updatedAtMs = now,
                        expiresAtMs = expiresAt
                    )
                    records.add(0, created)
                    trimCapacityLocked()
                    created
                }
            }
        }
        persistToEncryptedStorage()
        return result
    }

    /**
     * Records a successful or failed multi-step workflow into persistent Workflow Memory.
     */
    fun recordWorkflowOutcome(
        goal: String,
        actionSequence: List<String>,
        succeeded: Boolean,
        details: String
    ): SemanticMemoryRecord? {
        val cleanGoal = goal.trim()
        if (cleanGoal.isEmpty() || actionSequence.isEmpty()) return null
        val tier = if (succeeded) SemanticMemoryTier.WORKFLOW_SUCCESS else SemanticMemoryTier.WORKFLOW_FAILURE
        val key = "workflow_${cleanGoal.lowercase().replace(Regex("[^a-z0-9]+"), "_").take(36)}"
        val stepsStr = actionSequence.joinToString(" -> ")
        val content = if (succeeded) {
            "Goal: '$cleanGoal' succeeded via [$stepsStr]. Outcome: ${details.take(160)}"
        } else {
            "Goal: '$cleanGoal' failed via [$stepsStr]. Reason: ${details.take(160)}"
        }
        return upsertRecord(
            tier = tier,
            category = if (succeeded) "WORKFLOW_SUCCESS" else "WORKFLOW_FAILURE",
            key = key,
            content = content,
            tags = actionSequence.map { it.lowercase() }.toSet() + setOf("workflow"),
            importance = if (succeeded) 1.3f else 1.2f,
            ttlMs = null
        )
    }

    /**
     * Summarizes a list of conversation turns (role -> text) into a compact persistent summary record.
     */
    fun summarizeAndPersistConversation(
        turns: List<Pair<String, String>>,
        sessionLabel: String = "recent_session"
    ): SemanticMemoryRecord? {
        if (turns.isEmpty()) return null
        val userTurns = turns.filter { it.first.equals("user", ignoreCase = true) }.map { it.second.trim() }.filter { it.isNotEmpty() }
        val assistantTurns = turns.filter { it.first.equals("assistant", ignoreCase = true) }.map { it.second.trim() }.filter { it.isNotEmpty() }
        if (userTurns.isEmpty()) return null

        val keyTopics = extractTopKeywords(userTurns.joinToString(" "), limit = 6)
        val firstReq = userTurns.first().take(90)
        val lastReq = userTurns.last().take(90)
        val lastReply = assistantTurns.lastOrNull()?.take(110).orEmpty()

        val summaryText = buildString {
            append("Session ($sessionLabel, ${turns.size} turns): ")
            if (keyTopics.isNotEmpty()) append("Topics=[${keyTopics.joinToString(", ")}]. ")
            append("Started with: \"$firstReq\". ")
            if (userTurns.size > 1) append("Latest request: \"$lastReq\". ")
            if (lastReply.isNotBlank()) append("Latest outcome: \"$lastReply\".")
        }.trim()

        return upsertRecord(
            tier = SemanticMemoryTier.CONVERSATION_SUMMARY,
            category = "CONVERSATION_SUMMARY",
            key = "summary_$sessionLabel",
            content = summaryText,
            tags = keyTopics.toSet() + "summary",
            importance = 1.15f,
            ttlMs = null
        )
    }

    /**
     * Semantic search across stored records + optional Room [MemoryEntity] entries using
     * TF-IDF weighted tokens, character trigrams, cross-lingual concept expansion, and recency boost.
     */
    fun semanticSearch(
        query: String,
        additionalRoomMemories: List<MemoryEntity> = emptyList(),
        tiers: Set<SemanticMemoryTier>? = null,
        limit: Int = 6,
        minScore: Float = 0.08f
    ): List<SemanticSearchResult> {
        val now = currentTimeProvider()
        val pool = buildList {
            synchronized(lock) {
                addAll(records.filterNot { it.isExpired(now) })
            }
            for (entity in additionalRoomMemories) {
                val alreadyPresent = any {
                    it.key.equals(entity.keyName, ignoreCase = true) &&
                        it.content.equals(entity.memoryValue, ignoreCase = true)
                }
                if (!alreadyPresent) {
                    val entityTier = when (entity.category) {
                        MemoryCategory.SHORT_TERM_MEMORY.name,
                        MemoryCategory.TEMPORARY_SESSION.name -> SemanticMemoryTier.SHORT_TERM
                        else -> SemanticMemoryTier.LONG_TERM
                    }
                    add(
                        SemanticMemoryRecord(
                            id = "room_${entity.id}_${entity.keyName}",
                            tier = entityTier,
                            category = entity.category,
                            key = entity.keyName,
                            content = entity.memoryValue,
                            importance = if (entity.userConfirmed) 1.25f else 1.0f,
                            createdAtMs = entity.createdAt,
                            updatedAtMs = entity.createdAt
                        )
                    )
                }
            }
        }.filter { tiers == null || it.tier in tiers }

        if (pool.isEmpty()) return emptyList()
        val queryVec = buildFeatureVector(query)
        if (queryVec.isEmpty()) return emptyList()

        // Compute inverse document frequency (IDF) across the active memory pool
        val docVectors = pool.map { rec ->
            rec to buildFeatureVector("${rec.key} ${rec.category} ${rec.content} ${rec.tags.joinToString(" ")}")
        }
        val docCount = docVectors.size.toFloat()
        val dfMap = mutableMapOf<String, Int>()
        for ((_, vec) in docVectors) {
            for (term in vec.keys) {
                dfMap[term] = (dfMap[term] ?: 0) + 1
            }
        }
        val weightedQueryVec = queryVec.mapValues { (term, weight) ->
            val df = dfMap[term] ?: 0
            val idf = ln((docCount + 1.0) / (df + 1.0)).toFloat() + 1.0f
            weight * idf
        }

        return docVectors.mapNotNull { (rec, docVec) ->
            val weightedDocVec = docVec.mapValues { (term, weight) ->
                val df = dfMap[term] ?: 0
                val idf = ln((docCount + 1.0) / (df + 1.0)).toFloat() + 1.0f
                weight * idf
            }
            val sim = cosineSimilarity(weightedQueryVec, weightedDocVec)
            val ageHours = ((now - rec.updatedAtMs).coerceAtLeast(0L) / 3_600_000f)
            val recencyFactor = 1.0f / (1.0f + (ageHours / 168f)) // gentle 7-day half-life
            val importanceBoost = rec.importance.coerceIn(0.5f, 2.0f)
            val finalScore = sim * (0.85f + 0.15f * recencyFactor) * importanceBoost
            if (finalScore >= minScore) SemanticSearchResult(rec, finalScore) else null
        }.sortedByDescending { it.score }
            .take(limit)
    }

    /**
     * Consolidates memory: purges expired short-term records, promotes frequently accessed short-term
     * entries to long-term memory, and merges semantically redundant entries.
     */
    fun consolidateMemory(): ConsolidationReport {
        val now = currentTimeProvider()
        var expiredPurged = 0
        var promoted = 0
        var merged = 0

        synchronized(lock) {
            val beforeSize = records.size
            records.removeAll { it.isExpired(now) }
            expiredPurged = beforeSize - records.size

            // Promote repeated short-term items to long-term
            for (i in records.indices) {
                val rec = records[i]
                if (rec.tier == SemanticMemoryTier.SHORT_TERM && rec.accessCount >= PROMOTION_THRESHOLD_ACCESS_COUNT) {
                    records[i] = rec.copy(
                        tier = SemanticMemoryTier.LONG_TERM,
                        expiresAtMs = null,
                        updatedAtMs = now
                    )
                    promoted++
                }
            }

            // Merge high-similarity duplicates within the same tier
            val kept = mutableListOf<SemanticMemoryRecord>()
            val vectors = mutableListOf<Map<String, Float>>()
            for (rec in records.sortedByDescending { it.updatedAtMs }) {
                val vec = buildFeatureVector("${rec.key} ${rec.content}")
                val dupIndex = kept.indices.firstOrNull { idx ->
                    kept[idx].tier == rec.tier &&
                        cosineSimilarity(vec, vectors[idx]) >= DEDUP_SIMILARITY_THRESHOLD
                }
                if (dupIndex != null) {
                    val primary = kept[dupIndex]
                    kept[dupIndex] = primary.copy(
                        content = if (rec.content.length > primary.content.length) rec.content else primary.content,
                        tags = primary.tags + rec.tags,
                        accessCount = primary.accessCount + rec.accessCount,
                        importance = maxOf(primary.importance, rec.importance)
                    )
                    merged++
                } else {
                    kept.add(rec)
                    vectors.add(vec)
                }
            }
            records.clear()
            records.addAll(kept)
            trimCapacityLocked()
        }

        persistToEncryptedStorage()
        return ConsolidationReport(
            mergedDuplicates = merged,
            promotedToLongTerm = promoted,
            expiredPurged = expiredPurged,
            remainingCount = synchronized(lock) { records.size }
        )
    }

    /**
     * User control: update an existing semantic memory record by ID or key.
     */
    fun updateRecord(idOrKey: String, newContent: String): Boolean {
        if (!MemoryPrivacyGuard.isAllowedToPersist(newContent)) return false
        val sanitized = MemoryPrivacyGuard.sanitizeContent(newContent).trim()
        if (sanitized.isEmpty()) return false
        val now = currentTimeProvider()
        val updated = synchronized(lock) {
            val idx = records.indexOfFirst { it.id == idOrKey || it.key.equals(idOrKey, ignoreCase = true) }
            if (idx >= 0) {
                records[idx] = records[idx].copy(content = sanitized, updatedAtMs = now)
                true
            } else {
                false
            }
        }
        if (updated) persistToEncryptedStorage()
        return updated
    }

    /**
     * User control: delete a specific memory record by ID or key.
     */
    fun deleteRecord(idOrKey: String): Boolean {
        val removed = synchronized(lock) {
            records.removeAll { it.id == idOrKey || it.key.equals(idOrKey, ignoreCase = true) }
        }
        if (removed) persistToEncryptedStorage()
        return removed
    }

    /**
     * User control: clear all semantic memories (optionally filtered to a specific tier).
     */
    fun clearAll(tier: SemanticMemoryTier? = null) {
        synchronized(lock) {
            if (tier == null) {
                records.clear()
            } else {
                records.removeAll { it.tier == tier }
            }
        }
        persistToEncryptedStorage()
    }

    fun buildFeatureVector(text: String): Map<String, Float> {
        val normalized = text.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.isEmpty()) return emptyMap()

        val stopWords = setOf(
            "the", "and", "for", "with", "that", "this", "from", "have", "are", "was", "were",
            "hai", "hain", "karo", "kar", "mera", "mere", "meri", "aaj", "koi", "par", "mein", "ko", "ki", "ka", "ke"
        )
        val tokens = normalized.split(" ").filter { it.length >= 2 && it !in stopWords }
        if (tokens.isEmpty()) return emptyMap()

        val vec = mutableMapOf<String, Float>()
        for (tok in tokens) {
            vec["w:$tok"] = (vec["w:$tok"] ?: 0f) + 1.0f
            // Character trigrams provide fuzzy/morphological matching (e.g., song/songs, gaana/gaane)
            if (tok.length >= 4) {
                for (i in 0..(tok.length - 3)) {
                    val tri = tok.substring(i, i + 3)
                    vec["g:$tri"] = (vec["g:$tri"] ?: 0f) + 0.35f
                }
            }
            // Cross-lingual concept bridges (e.g., "gaana" & "mood" -> concept:music & concept:mood)
            for ((concept, synonyms) in CONCEPT_SYNONYMS) {
                if (tok in synonyms) {
                    vec["c:$concept"] = (vec["c:$concept"] ?: 0f) + 0.9f
                }
            }
        }
        return vec
    }

    fun cosineSimilarity(a: Map<String, Float>, b: Map<String, Float>): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for ((k, v) in a) {
            normA += (v * v).toDouble()
            b[k]?.let { bv -> dot += (v * bv).toDouble() }
        }
        for (v in b.values) {
            normB += (v * v).toDouble()
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom <= 1e-9) 0f else (dot / denom).toFloat().coerceIn(0f, 1f)
    }

    private fun extractTopKeywords(text: String, limit: Int): List<String> {
        val stop = setOf(
            "the", "and", "for", "with", "that", "this", "from", "have", "please", "can", "you",
            "hey", "babu", "nayro", "mera", "mere", "karo", "hai", "hain", "aur", "par", "mein"
        )
        return text.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length >= 3 && it !in stop }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key }
    }

    private fun trimCapacityLocked() {
        if (records.size <= MAX_STORED_RECORDS) return
        val sorted = records.sortedWith(
            compareByDescending<SemanticMemoryRecord> { it.tier == SemanticMemoryTier.LONG_TERM }
                .thenByDescending { it.importance }
                .thenByDescending { it.updatedAtMs }
        ).take(MAX_STORED_RECORDS)
        records.clear()
        records.addAll(sorted)
    }

    private fun persistToEncryptedStorage() {
        val storage = encryptedStorageManager ?: return
        val encoded = synchronized(lock) {
            records.joinToString("\n") { rec ->
                listOf(
                    escapeField(rec.id),
                    rec.tier.name,
                    escapeField(rec.category),
                    escapeField(rec.key),
                    escapeField(rec.content),
                    escapeField(rec.tags.joinToString(",")),
                    rec.importance.toString(),
                    rec.accessCount.toString(),
                    rec.createdAtMs.toString(),
                    rec.updatedAtMs.toString(),
                    rec.expiresAtMs?.toString().orEmpty()
                ).joinToString("\t")
            }
        }
        try {
            storage.savePreference(PREF_KEY_ENCRYPTED_RECORDS, encoded)
        } catch (_: Exception) {}
    }

    private fun loadFromEncryptedStorage() {
        val storage = encryptedStorageManager ?: return
        val raw = try {
            storage.getPreference(PREF_KEY_ENCRYPTED_RECORDS, "")
        } catch (_: Exception) {
            ""
        }
        if (raw.isBlank()) return
        val loaded = raw.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split("\t")
                if (parts.size < 10) return@mapNotNull null
                val tier = runCatching { SemanticMemoryTier.valueOf(parts[1]) }.getOrNull() ?: return@mapNotNull null
                val tags = unescapeField(parts[5]).split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                SemanticMemoryRecord(
                    id = unescapeField(parts[0]),
                    tier = tier,
                    category = unescapeField(parts[2]),
                    key = unescapeField(parts[3]),
                    content = unescapeField(parts[4]),
                    tags = tags,
                    importance = parts[6].toFloatOrNull() ?: 1.0f,
                    accessCount = parts[7].toIntOrNull() ?: 1,
                    createdAtMs = parts[8].toLongOrNull() ?: 0L,
                    updatedAtMs = parts[9].toLongOrNull() ?: 0L,
                    expiresAtMs = parts.getOrNull(10)?.toLongOrNull()
                )
            }
            .toList()
        synchronized(lock) {
            records.clear()
            records.addAll(loaded)
        }
    }

    private fun escapeField(value: String): String =
        value.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

    private fun unescapeField(value: String): String =
        value.replace("\\n", "\n").replace("\\t", "\t").replace("\\\\", "\\")
}
