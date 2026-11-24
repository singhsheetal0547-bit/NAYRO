package com.example.core.provider.adapter

import com.example.core.provider.ProviderConfig
import com.example.core.provider.model.AIRequest
import com.example.core.provider.model.AIResult

/**
 * Universal interface for all AI provider adapters in NAYRO.
 */
interface AIProviderAdapter {
    val providerConfig: ProviderConfig
    suspend fun generateResponse(request: AIRequest, apiKey: String): AIResult
    suspend fun generateStreamingResponse(
        request: AIRequest,
        apiKey: String,
        onToken: (String) -> Unit
    ): AIResult {
        val result = generateResponse(request, apiKey)
        if (result is AIResult.Success && result.response.content.isNotEmpty()) {
            onToken(result.response.content)
        }
        return result
    }
    suspend fun testConnection(apiKey: String): AIResult
}
