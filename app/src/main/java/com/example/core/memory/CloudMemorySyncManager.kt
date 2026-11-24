package com.example.core.memory

import com.example.core.security.EncryptedStorageManager
import com.example.data.local.entities.MemoryEntity
import com.example.data.repository.NayroRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

data class CloudMemorySyncResult(
    val isSuccess: Boolean,
    val message: String,
    val importedCount: Int = 0,
    val uploadedCount: Int = 0
)

/**
 * Explicit, user-triggered synchronization of approved NAYRO memories with a configurable HTTPS API.
 *
 * Wire protocol:
 *   GET  {baseUrl}/v1/nayro/memory -> {"memories":[{"category","keyName","memoryValue","userConfirmed","createdAt"}]}
 *   PUT  {baseUrl}/v1/nayro/memory with the same snapshot shape.
 *
 * No cloud host is built in. Until the user configures a compatible endpoint and bearer token,
 * sync remains disabled and all memories stay local. Sensitive content is excluded both directions.
 */
class CloudMemorySyncManager(
    private val repository: NayroRepository,
    private val memoryManager: MemoryManager,
    private val secureStorage: EncryptedStorageManager,
    private val httpClient: OkHttpClient = OkHttpClient()
) {
    companion object {
        private const val KEY_ENDPOINT = "cloud_memory_sync_endpoint"
        private const val KEY_TOKEN = "cloud_memory_sync_token"

        /**
         * Merges snapshots by (category,keyName). The record with the newest createdAt wins;
         * sensitive or non-user-confirmed remote entries are never imported.
         */
        fun mergeSnapshots(
            local: List<MemoryEntity>,
            remote: List<MemoryEntity>
        ): List<MemoryEntity> {
            val merged = linkedMapOf<String, MemoryEntity>()
            fun key(item: MemoryEntity) = "${item.category.trim().lowercase()}::${item.keyName.trim().lowercase()}"

            local.filter {
                it.userConfirmed && it.category.isNotBlank() && it.keyName.isNotBlank() &&
                    MemoryPrivacyGuard.isAllowedToPersist(it.memoryValue)
            }.forEach { candidate ->
                val k = key(candidate)
                val current = merged[k]
                if (current == null || candidate.createdAt > current.createdAt) {
                    merged[k] = candidate
                }
            }

            remote.filter {
                it.userConfirmed && it.category.isNotBlank() && it.keyName.isNotBlank() &&
                    MemoryPrivacyGuard.isAllowedToPersist(it.memoryValue)
            }.forEach { candidate ->
                val k = key(candidate)
                val current = merged[k]
                if (current == null || candidate.createdAt > current.createdAt) {
                    merged[k] = candidate.copy(id = 0, userConfirmed = true)
                }
            }
            return merged.values.sortedByDescending { it.createdAt }
        }
    }

    fun configure(endpoint: String, bearerToken: String) {
        val cleanEndpoint = endpoint.trim().trimEnd('/')
        val cleanToken = bearerToken.trim()
        require(cleanEndpoint.startsWith("https://", ignoreCase = true)) {
            "Memory sync endpoint must use HTTPS."
        }
        require(cleanEndpoint.length <= 512) { "Memory sync endpoint is too long." }
        require(cleanToken.isNotBlank()) { "A bearer token is required to enable cloud memory sync." }
        secureStorage.savePreference(KEY_ENDPOINT, cleanEndpoint)
        secureStorage.saveApiKey(KEY_TOKEN, cleanToken)
    }

    fun clearConfiguration() {
        secureStorage.savePreference(KEY_ENDPOINT, "")
        secureStorage.removeApiKey(KEY_TOKEN)
    }

    fun isConfigured(): Boolean =
        secureStorage.getPreference(KEY_ENDPOINT).startsWith("https://", ignoreCase = true) &&
            secureStorage.hasApiKey(KEY_TOKEN)

    fun configuredEndpoint(): String = secureStorage.getPreference(KEY_ENDPOINT)

    suspend fun syncNow(): CloudMemorySyncResult = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            return@withContext CloudMemorySyncResult(
                false,
                "Cloud memory sync is disabled. Configure a compatible HTTPS memory API and bearer token first."
            )
        }

        try {
            val endpoint = secureStorage.getPreference(KEY_ENDPOINT).trimEnd('/') +
                if (secureStorage.getPreference(KEY_ENDPOINT).trimEnd('/').endsWith("/v1/nayro/memory")) "" else "/v1/nayro/memory"
            val token = secureStorage.getApiKey(KEY_TOKEN)
            val local = repository.memories.first().filter {
                it.userConfirmed && MemoryPrivacyGuard.isAllowedToPersist(it.memoryValue)
            }

            val getRequest = Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .get()
                .build()
            val remoteSnapshot = httpClient.newCall(getRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Memory sync download failed with HTTP ${response.code}: ${response.message}")
                }
                val payload = response.body?.string().orEmpty()
                if (payload.isBlank()) emptyList() else decodeSnapshot(JSONObject(payload))
            }

            val merged = mergeSnapshots(local, remoteSnapshot)
            val localKeys = local.map { "${it.category.lowercase()}::${it.keyName.lowercase()}" }.toSet()
            val imported = merged.filter {
                val key = "${it.category.lowercase()}::${it.keyName.lowercase()}"
                key !in localKeys || (remoteSnapshot.any { r ->
                    "${r.category.lowercase()}::${r.keyName.lowercase()}" == key && r.createdAt > (local.firstOrNull { l ->
                        "${l.category.lowercase()}::${l.keyName.lowercase()}" == key
                    }?.createdAt ?: Long.MIN_VALUE)
                })
            }
            imported.forEach { repository.upsertMemory(it.copy(id = 0, userConfirmed = true)) }

            val refreshedLocal = repository.memories.first().filter {
                it.userConfirmed && MemoryPrivacyGuard.isAllowedToPersist(it.memoryValue)
            }
            val finalSnapshot = mergeSnapshots(refreshedLocal, remoteSnapshot)
            val putBody = JSONObject().put("memories", encodeSnapshot(finalSnapshot))
                .put("schemaVersion", 1)
                .toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val putRequest = Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer $token")
                .put(putBody)
                .build()

            httpClient.newCall(putRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Memory sync upload failed with HTTP ${response.code}: ${response.message}")
                }
            }
            memoryManager.refreshCachedMemories()
            secureStorage.savePreference("cloud_memory_sync_last_success_ms", System.currentTimeMillis().toString())

            CloudMemorySyncResult(
                isSuccess = true,
                message = "Cloud memory sync completed. Local: ${refreshedLocal.size}, cloud entries read: ${remoteSnapshot.size}, imported/updated: ${imported.size}.",
                importedCount = imported.size,
                uploadedCount = finalSnapshot.size
            )
        } catch (e: Exception) {
            CloudMemorySyncResult(
                isSuccess = false,
                message = e.localizedMessage ?: "Cloud memory sync failed."
            )
        }
    }

    private fun decodeSnapshot(root: JSONObject): List<MemoryEntity> {
        val array = root.optJSONArray("memories") ?: JSONArray()
        val result = mutableListOf<MemoryEntity>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val category = item.optString("category").trim()
            val keyName = item.optString("keyName").trim()
            val value = item.optString("memoryValue")
            if (category.isBlank() || keyName.isBlank() || value.isBlank()) continue
            if (!item.optBoolean("userConfirmed", false)) continue
            if (!MemoryPrivacyGuard.isAllowedToPersist(value)) continue
            result += MemoryEntity(
                category = category,
                keyName = keyName,
                memoryValue = MemoryPrivacyGuard.sanitizeContent(value),
                userConfirmed = true,
                createdAt = item.optLong("createdAt", 0L)
            )
        }
        return result
    }

    private fun encodeSnapshot(memories: List<MemoryEntity>): JSONArray {
        val array = JSONArray()
        memories.filter { it.userConfirmed && MemoryPrivacyGuard.isAllowedToPersist(it.memoryValue) }
            .forEach { memory ->
                array.put(
                    JSONObject()
                        .put("category", memory.category)
                        .put("keyName", memory.keyName)
                        .put("memoryValue", memory.memoryValue)
                        .put("userConfirmed", true)
                        .put("createdAt", memory.createdAt)
                )
            }
        return array
    }
}
