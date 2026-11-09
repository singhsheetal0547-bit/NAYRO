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
 * Adapter for Google Gemini Developer API.
 * Endpoint format: https://generativelanguage.googleapis.com/v1beta/models/{modelId}:generateContent?key={apiKey}
 */
class GeminiProviderAdapter(
    override val providerConfig: ProviderConfig,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
) : AIProviderAdapter {

    override suspend fun generateResponse(request: AIRequest, apiKey: String): AIResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext AIResult.Error(401, "Gemini API key is missing. Please configure it in Settings.")
        }

        val url = "${providerConfig.baseUrl}/v1beta/models/${providerConfig.modelId}:generateContent?key=$apiKey"

        val contentsArray = JSONArray()

        // Map messages into Gemini contents structure
        for (msg in request.messages) {
            val role = if (msg.role == "user") "user" else "model"
            val partObj = JSONObject().put("text", msg.content)
            val contentObj = JSONObject()
                .put("role", role)
                .put("parts", JSONArray().put(partObj))
            contentsArray.put(contentObj)
        }

        val requestBodyJson = JSONObject().apply {
            put("contents", contentsArray)
            request.systemPrompt?.let { prompt ->
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

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val httpRequest = Request.Builder()
            .url(url)
            .post(requestBodyJson.toString().toRequestBody(mediaType))
            .build()

        try {
            val httpResponse = client.newCall(httpRequest).execute()
            val responseBody = httpResponse.body?.string() ?: ""

            if (!httpResponse.isSuccessful) {
                val errorMsg = try {
                    JSONObject(responseBody).optJSONObject("error")?.optString("message") ?: "HTTP ${httpResponse.code}"
                } catch (e: Exception) {
                    "HTTP ${httpResponse.code}: $responseBody"
                }
                return@withContext AIResult.Error(httpResponse.code, errorMsg, isRetryable = httpResponse.code in listOf(429, 500, 503))
            }

            val json = JSONObject(responseBody)
            val candidates = json.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                return@withContext AIResult.Error(204, "No response candidates returned by Gemini.")
            }

            val candidate = candidates.getJSONObject(0)
            val contentParts = candidate.optJSONObject("content")?.optJSONArray("parts")
            val textBuilder = StringBuilder()

            if (contentParts != null) {
                for (i in 0 until contentParts.length()) {
                    textBuilder.append(contentParts.getJSONObject(i).optString("text", ""))
                }
            }

            val replyText = textBuilder.toString().trim()
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
        } catch (e: IOException) {
            return@withContext AIResult.Error(0, "Network timeout or failure: ${e.localizedMessage ?: "Unknown error"}", isRetryable = true)
        } catch (e: Exception) {
            return@withContext AIResult.Error(500, "Unexpected error: ${e.localizedMessage ?: e.javaClass.simpleName}")
        }
    }

    override suspend fun testConnection(apiKey: String): AIResult {
        val testRequest = AIRequest(
            messages = listOf(AIChatMessage(role = "user", content = "Ping test"))
        )
        return generateResponse(testRequest, apiKey)
    }
}
