package com.example.core.provider.adapter

import com.example.core.provider.ProviderConfig
import com.example.core.provider.model.AIChatMessage
import com.example.core.provider.model.AIRequest
import com.example.core.provider.model.AIResponse
import com.example.core.provider.model.AIResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Adapter for Google Gemini Developer API.
 * Supports:
 * - Normalized v1beta endpoint resolution (prevents duplicate slashes or duplicate /v1beta segments)
 * - Header-based API authentication (`x-goog-api-key`) to keep keys out of URL logs
 * - Strict alternating role normalization for Gemini `contents`
 * - Bounded exponential-backoff retry on transient 429 / 5xx / IOException errors
 * - Real Server-Sent Events (SSE) streaming via `:streamGenerateContent?alt=sse`
 */
class GeminiProviderAdapter(
    override val providerConfig: ProviderConfig,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build(),
    private val maxRetries: Int = 2,
    private val initialRetryDelayMs: Long = 500L
) : AIProviderAdapter {

    companion object {
        fun normalizeEndpointUrl(baseUrl: String, modelId: String, streaming: Boolean = false): String {
            var cleanBase = baseUrl.trim().trimEnd('/')
            if (cleanBase.isEmpty()) {
                cleanBase = "https://generativelanguage.googleapis.com"
            }
            if (cleanBase.endsWith("/v1beta")) {
                cleanBase = cleanBase.removeSuffix("/v1beta").trimEnd('/')
            } else if (cleanBase.endsWith("/v1")) {
                cleanBase = cleanBase.removeSuffix("/v1").trimEnd('/')
            }
            val cleanModel = modelId.trim().removePrefix("models/")
            return if (streaming) {
                "$cleanBase/v1beta/models/$cleanModel:streamGenerateContent?alt=sse"
            } else {
                "$cleanBase/v1beta/models/$cleanModel:generateContent"
            }
        }

        /**
         * Normalizes conversation history into Gemini's strict alternating `user` / `model` turns,
         * merging consecutive messages with the same role and dropping leading non-user turns
         * so the Gemini API never rejects requests with HTTP 400.
         */
        fun normalizeConversationTurns(messages: List<AIChatMessage>): List<Pair<String, String>> {
            val merged = mutableListOf<Pair<String, StringBuilder>>()
            for (msg in messages) {
                val text = msg.content.trim()
                if (text.isEmpty()) continue
                val role = if (msg.role.equals("user", ignoreCase = true)) "user" else "model"
                val last = merged.lastOrNull()
                if (last != null && last.first == role) {
                    last.second.append("\n").append(text)
                } else {
                    merged.add(role to StringBuilder(text))
                }
            }
            // Gemini requires contents to begin with a user turn when non-empty
            while (merged.isNotEmpty() && merged.first().first != "user") {
                merged.removeAt(0)
            }
            return merged.map { (role, sb) -> role to sb.toString() }
        }

        fun buildGeminiContentsArray(
            messages: List<AIChatMessage>,
            images: List<com.example.core.provider.model.AIImageAttachment> = emptyList()
        ): JSONArray {
            val turns = normalizeConversationTurns(messages)
            val contentsArray = JSONArray()
            val lastUserIndex = turns.indexOfLast { it.first == "user" }
            turns.forEachIndexed { idx, (role, text) ->
                val partsArray = JSONArray().put(JSONObject().put("text", text))
                if (idx == lastUserIndex && images.isNotEmpty()) {
                    for (img in images) {
                        if (img.base64Data.isNotBlank()) {
                            partsArray.put(
                                JSONObject().put(
                                    "inline_data",
                                    JSONObject()
                                        .put("mime_type", img.mimeType)
                                        .put("data", img.base64Data)
                                )
                            )
                        }
                    }
                }
                val contentObj = JSONObject()
                    .put("role", role)
                    .put("parts", partsArray)
                contentsArray.put(contentObj)
            }
            if (turns.isEmpty() && images.isNotEmpty()) {
                val partsArray = JSONArray().put(JSONObject().put("text", "Analyze this screen."))
                for (img in images) {
                    if (img.base64Data.isNotBlank()) {
                        partsArray.put(
                            JSONObject().put(
                                "inline_data",
                                JSONObject()
                                    .put("mime_type", img.mimeType)
                                    .put("data", img.base64Data)
                            )
                        )
                    }
                }
                contentsArray.put(JSONObject().put("role", "user").put("parts", partsArray))
            }
            return contentsArray
        }

        /**
         * Pure-Kotlin helper describing the multimodal part sequence for unit test verification.
         */
        fun describeMultimodalPayloadSummary(request: AIRequest): Pair<Int, Int> {
            val turns = normalizeConversationTurns(request.messages)
            val validImages = request.images.count { it.base64Data.isNotBlank() }
            return turns.size to validImages
        }

        fun extractCandidateText(json: JSONObject): Pair<String, String?> {
            val candidates = json.optJSONArray("candidates") ?: return "" to null
            if (candidates.length() == 0) return "" to null
            val candidate = candidates.getJSONObject(0)
            val finishReason = candidate.optString("finishReason", "").takeIf { it.isNotBlank() }
            val contentParts = candidate.optJSONObject("content")?.optJSONArray("parts")
            val textBuilder = StringBuilder()
            if (contentParts != null) {
                for (i in 0 until contentParts.length()) {
                    textBuilder.append(contentParts.getJSONObject(i).optString("text", ""))
                }
            }
            return textBuilder.toString() to finishReason
        }
    }

    private fun buildRequestJson(request: AIRequest): JSONObject {
        val contentsArray = buildGeminiContentsArray(request.messages, request.images)
        return JSONObject().apply {
            put("contents", contentsArray)
            request.systemPrompt?.takeIf { it.isNotBlank() }?.let { prompt ->
                put(
                    "system_instruction",
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                )
            }
            put(
                "generationConfig",
                JSONObject().apply {
                    put("temperature", request.temperature)
                    put("maxOutputTokens", request.maxTokens)
                }
            )
        }
    }

    override suspend fun generateResponse(request: AIRequest, apiKey: String): AIResult = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            return@withContext AIResult.Error(401, "Gemini API key is missing. Please configure it in Settings.")
        }

        val url = normalizeEndpointUrl(providerConfig.baseUrl, providerConfig.modelId, streaming = false)
        val requestBodyJson = buildRequestJson(request)
        val mediaType = "application/json; charset=utf-8".toMediaType()

        var attempt = 0
        var lastError: AIResult.Error? = null

        while (attempt <= maxRetries) {
            val httpRequest = Request.Builder()
                .url(url)
                .addHeader("x-goog-api-key", cleanKey)
                .addHeader("Content-Type", "application/json; charset=utf-8")
                .post(requestBodyJson.toString().toRequestBody(mediaType))
                .build()

            try {
                client.newCall(httpRequest).execute().use { httpResponse ->
                    val responseBody = httpResponse.body?.string() ?: ""

                    if (!httpResponse.isSuccessful) {
                        val errorMsg = try {
                            JSONObject(responseBody).optJSONObject("error")?.optString("message")
                                ?.takeIf { it.isNotBlank() }
                                ?: "HTTP ${httpResponse.code}"
                        } catch (_: Exception) {
                            "HTTP ${httpResponse.code}: ${responseBody.take(240)}"
                        }
                        val retryable = httpResponse.code in listOf(408, 429, 500, 502, 503, 504)
                        val err = AIResult.Error(httpResponse.code, errorMsg, isRetryable = retryable)
                        if (retryable && attempt < maxRetries) {
                            lastError = err
                        } else {
                            return@withContext err
                        }
                    } else {
                        val json = JSONObject(responseBody)
                        val (rawText, finishReason) = extractCandidateText(json)
                        val replyText = rawText.trim()
                        if (replyText.isEmpty()) {
                            val blockReason = json.optJSONObject("promptFeedback")?.optString("blockReason", "")
                            val reasonNote = finishReason ?: blockReason?.takeIf { it.isNotBlank() } ?: "EMPTY_RESPONSE"
                            return@withContext AIResult.Error(
                                204,
                                "Gemini returned no response text (reason: $reasonNote).",
                                isRetryable = false
                            )
                        }

                        val usage = json.optJSONObject("usageMetadata")
                        val promptTokens = usage?.optInt("promptTokenCount")
                        val candidateTokens = usage?.optInt("candidatesTokenCount")

                        return@withContext AIResult.Success(
                            AIResponse(
                                content = replyText,
                                providerId = providerConfig.id,
                                modelId = providerConfig.modelId,
                                promptTokens = promptTokens,
                                completionTokens = candidateTokens
                            )
                        )
                    }
                }
            } catch (e: IOException) {
                val err = AIResult.Error(
                    0,
                    "Network timeout or failure: ${e.localizedMessage ?: e.javaClass.simpleName}",
                    isRetryable = true
                )
                if (attempt < maxRetries) {
                    lastError = err
                } else {
                    return@withContext err
                }
            } catch (e: Exception) {
                return@withContext AIResult.Error(
                    500,
                    "Unexpected Gemini error: ${e.localizedMessage ?: e.javaClass.simpleName}",
                    isRetryable = false
                )
            }

            attempt++
            val backoffMs = (initialRetryDelayMs * (1L shl (attempt - 1))).coerceAtMost(4_000L)
            if (backoffMs > 0L) delay(backoffMs)
        }

        return@withContext lastError ?: AIResult.Error(0, "Gemini request failed after retries.", isRetryable = true)
    }

    override suspend fun generateStreamingResponse(
        request: AIRequest,
        apiKey: String,
        onToken: (String) -> Unit
    ): AIResult = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            return@withContext AIResult.Error(401, "Gemini API key is missing. Please configure it in Settings.")
        }

        val url = normalizeEndpointUrl(providerConfig.baseUrl, providerConfig.modelId, streaming = true)
        val requestBodyJson = buildRequestJson(request)
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val httpRequest = Request.Builder()
            .url(url)
            .addHeader("x-goog-api-key", cleanKey)
            .addHeader("Accept", "text/event-stream")
            .addHeader("Content-Type", "application/json; charset=utf-8")
            .post(requestBodyJson.toString().toRequestBody(mediaType))
            .build()

        try {
            client.newCall(httpRequest).execute().use { httpResponse ->
                if (!httpResponse.isSuccessful) {
                    val errBody = httpResponse.body?.string().orEmpty()
                    val errorMsg = try {
                        JSONObject(errBody).optJSONObject("error")?.optString("message")
                            ?.takeIf { it.isNotBlank() }
                            ?: "HTTP ${httpResponse.code}"
                    } catch (_: Exception) {
                        "HTTP ${httpResponse.code}: ${errBody.take(240)}"
                    }
                    return@withContext AIResult.Error(
                        httpResponse.code,
                        errorMsg,
                        isRetryable = httpResponse.code in listOf(408, 429, 500, 502, 503, 504)
                    )
                }

                val reader = httpResponse.body?.charStream()?.buffered()
                    ?: return@withContext AIResult.Error(204, "Empty streaming body returned by Gemini.")

                val fullText = StringBuilder()
                var promptTokens: Int? = null
                var completionTokens: Int? = null

                reader.useLines { lines ->
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (!trimmed.startsWith("data:")) continue
                        val payload = trimmed.removePrefix("data:").trim()
                        if (payload.isEmpty() || payload == "[DONE]") continue
                        try {
                            val chunkJson = JSONObject(payload)
                            val (deltaText, _) = extractCandidateText(chunkJson)
                            if (deltaText.isNotEmpty()) {
                                fullText.append(deltaText)
                                onToken(deltaText)
                            }
                            chunkJson.optJSONObject("usageMetadata")?.let { usage ->
                                promptTokens = usage.optInt("promptTokenCount")
                                completionTokens = usage.optInt("candidatesTokenCount")
                            }
                        } catch (_: Exception) {
                            // Ignore malformed SSE comment/heartbeat chunk
                        }
                    }
                }

                val finalReply = fullText.toString().trim()
                if (finalReply.isEmpty()) {
                    return@withContext AIResult.Error(204, "Gemini stream ended without producing text.")
                }

                return@withContext AIResult.Success(
                    AIResponse(
                        content = finalReply,
                        providerId = providerConfig.id,
                        modelId = providerConfig.modelId,
                        promptTokens = promptTokens,
                        completionTokens = completionTokens
                    )
                )
            }
        } catch (e: IOException) {
            return@withContext AIResult.Error(
                0,
                "Gemini stream network failure: ${e.localizedMessage ?: e.javaClass.simpleName}",
                isRetryable = true
            )
        } catch (e: Exception) {
            return@withContext AIResult.Error(
                500,
                "Unexpected Gemini streaming error: ${e.localizedMessage ?: e.javaClass.simpleName}",
                isRetryable = false
            )
        }
    }

    override suspend fun testConnection(apiKey: String): AIResult {
        val testRequest = AIRequest(
            messages = listOf(AIChatMessage(role = "user", content = "Reply with OK.")),
            systemPrompt = "You are a connectivity test endpoint. Reply concisely with OK."
        )
        return generateResponse(testRequest, apiKey)
    }
}
