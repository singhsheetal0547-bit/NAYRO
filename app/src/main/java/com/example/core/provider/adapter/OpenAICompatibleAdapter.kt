package com.example.core.provider.adapter

import com.example.core.provider.ProviderConfig
import com.example.core.provider.model.AIChatMessage
import com.example.core.provider.model.AIRequest
import com.example.core.provider.model.AIResponse
import com.example.core.provider.model.AIResult
import kotlinx.coroutines.Dispatchers
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
 */
class OpenAICompatibleAdapter(
    override val providerConfig: ProviderConfig,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
) : AIProviderAdapter {

    override suspend fun generateResponse(request: AIRequest, apiKey: String): AIResult = withContext(Dispatchers.IO) {
        val normalizedBaseUrl = providerConfig.baseUrl.trimEnd('/')
        val endpointUrl = if (normalizedBaseUrl.endsWith("/chat/completions")) {
            normalizedBaseUrl
        } else {
            "$normalizedBaseUrl/chat/completions"
        }

        val messagesArray = JSONArray()

        request.systemPrompt?.let { prompt ->
            messagesArray.put(JSONObject().put("role", "system").put("content", prompt))
        }

        for (msg in request.messages) {
            messagesArray.put(JSONObject().put("role", msg.role).put("content", msg.content))
        }

        val bodyJson = JSONObject().apply {
            put("model", providerConfig.modelId)
            put("messages", messagesArray)
            put("temperature", request.temperature)
            put("max_tokens", request.maxTokens)
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBuilder = Request.Builder()
            .url(endpointUrl)
            .post(bodyJson.toString().toRequestBody(mediaType))

        if (apiKey.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $apiKey")
        }

        try {
            val httpResponse = client.newCall(requestBuilder.build()).execute()
            val responseBody = httpResponse.body?.string() ?: ""

            if (!httpResponse.isSuccessful) {
                val errorMsg = try {
                    JSONObject(responseBody).optJSONObject("error")?.optString("message") ?: "HTTP ${httpResponse.code}"
                } catch (e: Exception) {
                    "HTTP ${httpResponse.code}: $responseBody"
                }
                return@withContext AIResult.Error(httpResponse.code, errorMsg, isRetryable = httpResponse.code in listOf(429, 500, 502, 503))
            }

            val json = JSONObject(responseBody)
            val choices = json.optJSONArray("choices")
            if (choices == null || choices.length() == 0) {
                return@withContext AIResult.Error(204, "Empty choices array returned by provider.")
            }

            val firstChoice = choices.getJSONObject(0)
            val messageObj = firstChoice.optJSONObject("message")
            val replyText = messageObj?.optString("content")?.trim() ?: ""

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
        } catch (e: IOException) {
            return@withContext AIResult.Error(0, "Connection failed: ${e.localizedMessage ?: "Network error"}", isRetryable = true)
        } catch (e: Exception) {
            return@withContext AIResult.Error(500, "Unexpected error: ${e.localizedMessage ?: e.javaClass.simpleName}")
        }
    }

    override suspend fun testConnection(apiKey: String): AIResult {
        val testRequest = AIRequest(
            messages = listOf(AIChatMessage(role = "user", content = "Ping"))
        )
        return generateResponse(testRequest, apiKey)
    }
}
