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
    suspend fun testConnection(apiKey: String): AIResult
}
