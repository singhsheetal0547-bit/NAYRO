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
 * Universal adapter for all OpenAI-compatible REST endpoints:
 * Supports OpenAI, xAI Grok, OpenRouter, DeepSeek, and Local endpoints (Ollama, LMStudio, vLLM).
 * Endpoint standard: {baseUrl}/chat/completions with "Authorization: Bearer {apiKey}"
 * Supports bounded exponential-backoff retry on transient failures and real SSE streaming (`stream: true`).
 */
class OpenAICompatibleAdapter(
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
        const val OPENROUTER_REFERER = "https://github.com/singhsheetal0547-bit/NAYRO"
        const val OPENROUTER_TITLE = "NAYRO Autonomous Android Agent"

        fun normalizeChatCompletionsUrl(baseUrl: String): String {
            val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
            return if (normalizedBaseUrl.endsWith("/chat/completions")) {
                normalizedBaseUrl
            } else {
                "$normalizedBaseUrl/chat/completions"
            }
        }

        fun buildRequestHeaders(config: ProviderConfig, apiKey: String): Map<String, String> {
            val headers = linkedMapOf<String, String>()
            val cleanKey = apiKey.trim()
            if (cleanKey.isNotBlank()) {
                headers["Authorization"] = "Bearer $cleanKey"
            }
            if (config.type == com.example.core.provider.ProviderType.OPENROUTER ||
                config.baseUrl.contains("openrouter.ai", ignoreCase = true)
            ) {
                headers["HTTP-Referer"] = OPENROUTER_REFERER
                headers["X-Title"] = OPENROUTER_TITLE
            }
            return headers
        }
        fun buildImageDataUri(mimeType: String, base64Data: String): String =
            "data:${mimeType.ifBlank { "image/jpeg" }};base64,$base64Data"
    }

    private fun buildBodyJson(request: AIRequest, stream: Boolean): JSONObject {
        val messagesArray = JSONArray()

        request.systemPrompt?.takeIf { it.isNotBlank() }?.let { prompt ->
            messagesArray.put(JSONObject().put("role", "system").put("content", prompt))
        }

        val validImages = request.images.filter { it.base64Data.isNotBlank() }
        val lastUserIndex = request.messages.indexOfLast { it.role.equals("user", ignoreCase = true) }

        request.messages.forEachIndexed { idx, msg ->
            if (idx == lastUserIndex && validImages.isNotEmpty()) {
                val contentParts = JSONArray().put(
                    JSONObject().put("type", "text").put("text", msg.content)
                )
                for (img in validImages) {
                    contentParts.put(
                        JSONObject()
                            .put("type", "image_url")
                            .put(
                                "image_url",
                                JSONObject().put("url", buildImageDataUri(img.mimeType, img.base64Data))
                            )
                    )
                }
                messagesArray.put(JSONObject().put("role", msg.role).put("content", contentParts))
            } else {
                messagesArray.put(JSONObject().put("role", msg.role).put("content", msg.content))
            }
        }

        return JSONObject().apply {
            put("model", providerConfig.modelId)
            put("messages", messagesArray)
            put("temperature", request.temperature)
            put("max_tokens", request.maxTokens)
            if (stream) {
                put("stream", true)
            }
        }
    }

    override suspend fun generateResponse(request: AIRequest, apiKey: String): AIResult = withContext(Dispatchers.IO) {
        val endpointUrl = normalizeChatCompletionsUrl(providerConfig.baseUrl)
        val bodyJson = buildBodyJson(request, stream = false)
        val mediaType = "application/json; charset=utf-8".toMediaType()

        var attempt = 0
        var lastError: AIResult.Error? = null

        while (attempt <= maxRetries) {
            val requestBuilder = Request.Builder()
                .url(endpointUrl)
                .post(bodyJson.toString().toRequestBody(mediaType))

            buildRequestHeaders(providerConfig, apiKey).forEach { (key, value) ->
                requestBuilder.addHeader(key, value)
            }

            try {
                client.newCall(requestBuilder.build()).execute().use { httpResponse ->
                    val responseBody = httpResponse.body?.string() ?: ""

                    if (!httpResponse.isSuccessful) {
                        val baseErrorMsg = try {
                            JSONObject(responseBody).optJSONObject("error")?.optString("message")
                                ?.takeIf { it.isNotBlank() }
                                ?: "HTTP ${httpResponse.code}"
                        } catch (_: Exception) {
                            "HTTP ${httpResponse.code}: ${responseBody.take(240)}"
                        }
                        val retryAfter = httpResponse.header("Retry-After")?.takeIf { it.isNotBlank() }
                        val errorMsg = if (httpResponse.code == 429) {
                            if (retryAfter != null) {
                                "Rate limit exceeded (${providerConfig.name}). Retry after ${retryAfter}s. $baseErrorMsg"
                            } else {
                                "Rate limit exceeded (${providerConfig.name}). $baseErrorMsg"
                            }
                        } else {
                            baseErrorMsg
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
                        val choices = json.optJSONArray("choices")
                        if (choices == null || choices.length() == 0) {
                            return@withContext AIResult.Error(204, "Empty choices array returned by ${providerConfig.name}.")
                        }

                        val firstChoice = choices.getJSONObject(0)
                        val messageObj = firstChoice.optJSONObject("message")
                        val replyText = messageObj?.optString("content")?.trim() ?: ""
                        if (replyText.isEmpty()) {
                            return@withContext AIResult.Error(204, "${providerConfig.name} returned an empty message body.")
                        }

                        val usage = json.optJSONObject("usage")
                        val promptTokens = usage?.optInt("prompt_tokens")
                        val completionTokens = usage?.optInt("completion_tokens")

                        return@withContext AIResult.Success(
                            AIResponse(
                                content = replyText,
                                providerId = providerConfig.id,
                                modelId = providerConfig.modelId,
                                promptTokens = promptTokens,
                                completionTokens = completionTokens
                            )
                        )
                    }
                }
            } catch (e: IOException) {
                val err = AIResult.Error(
                    0,
                    "Connection failed (${providerConfig.name}): ${e.localizedMessage ?: "Network error"}",
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
                    "Unexpected error (${providerConfig.name}): ${e.localizedMessage ?: e.javaClass.simpleName}",
                    isRetryable = false
                )
            }

            attempt++
            val backoffMs = (initialRetryDelayMs * (1L shl (attempt - 1))).coerceAtMost(4_000L)
            if (backoffMs > 0L) delay(backoffMs)
        }

        return@withContext lastError ?: AIResult.Error(0, "${providerConfig.name} request failed after retries.", isRetryable = true)
    }

    override suspend fun generateStreamingResponse(
        request: AIRequest,
        apiKey: String,
        onToken: (String) -> Unit
    ): AIResult = withContext(Dispatchers.IO) {
        val endpointUrl = normalizeChatCompletionsUrl(providerConfig.baseUrl)
        val bodyJson = buildBodyJson(request, stream = true)
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBuilder = Request.Builder()
            .url(endpointUrl)
            .addHeader("Accept", "text/event-stream")
            .post(bodyJson.toString().toRequestBody(mediaType))

        buildRequestHeaders(providerConfig, apiKey).forEach { (key, value) ->
            requestBuilder.addHeader(key, value)
        }

        try {
            client.newCall(requestBuilder.build()).execute().use { httpResponse ->
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
                    ?: return@withContext AIResult.Error(204, "Empty streaming body returned by ${providerConfig.name}.")

                val fullText = StringBuilder()
                reader.useLines { lines ->
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (!trimmed.startsWith("data:")) continue
                        val payload = trimmed.removePrefix("data:").trim()
                        if (payload.isEmpty() || payload == "[DONE]") break
                        try {
                            val chunkJson = JSONObject(payload)
                            val choices = chunkJson.optJSONArray("choices")
                            if (choices != null && choices.length() > 0) {
                                val delta = choices.getJSONObject(0).optJSONObject("delta")
                                val token = delta?.optString("content", "").orEmpty()
                                if (token.isNotEmpty()) {
                                    fullText.append(token)
                                    onToken(token)
                                }
                            }
                        } catch (_: Exception) {
                            // Ignore SSE keep-alive comment frames
                        }
                    }
                }

                val finalReply = fullText.toString().trim()
                if (finalReply.isEmpty()) {
                    return@withContext AIResult.Error(204, "${providerConfig.name} stream ended without producing text.")
                }

                return@withContext AIResult.Success(
                    AIResponse(
                        content = finalReply,
                        providerId = providerConfig.id,
                        modelId = providerConfig.modelId
                    )
                )
            }
        } catch (e: IOException) {
            return@withContext AIResult.Error(
                0,
                "Streaming connection failed (${providerConfig.name}): ${e.localizedMessage ?: "Network error"}",
                isRetryable = true
            )
        } catch (e: Exception) {
            return@withContext AIResult.Error(
                500,
                "Unexpected streaming error (${providerConfig.name}): ${e.localizedMessage ?: e.javaClass.simpleName}",
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
