package com.example.core.provider.model

/**
 * Common data model for user prompts and conversation context sent to AI models.
 */
data class AIChatMessage(
    val role: String, // "system", "user", or "assistant"
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class AIImageAttachment(
    val base64Data: String,
    val mimeType: String = "image/jpeg"
)

data class AIRequest(
    val messages: List<AIChatMessage>,
    val systemPrompt: String? = null,
    val temperature: Float = 0.7f,
    val maxTokens: Int = 1024,
    val images: List<AIImageAttachment> = emptyList()
)

data class AIResponse(
    val content: String,
    val providerId: String,
    val modelId: String,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val isSuccess: Boolean = true,
    val errorMessage: String? = null
)

sealed class AIResult {
    data class Success(val response: AIResponse) : AIResult()
    data class Error(val code: Int, val message: String, val isRetryable: Boolean = false) : AIResult()
}
