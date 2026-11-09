package com.example.core.provider

import com.example.core.provider.adapter.AIProviderAdapter
import com.example.core.provider.adapter.GeminiProviderAdapter
import com.example.core.provider.adapter.OpenAICompatibleAdapter
import com.example.core.provider.model.AIChatMessage
import com.example.core.provider.model.AIRequest
import com.example.core.provider.model.AIResponse
import com.example.core.provider.model.AIResult
import com.example.core.security.EncryptedStorageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * System One — Multi-Provider AI Engine & Router for NAYRO.
 * Manages provider configurations, credential retrieval via KeyStore EncryptedStorageManager,
 * default routing, and seamless failover.
 */
class AIProviderManager(
    private val encryptedStorageManager: EncryptedStorageManager? = null
) {

    private val _configuredProviders = MutableStateFlow<List<ProviderConfig>>(
        listOf(
            ProviderConfig(
                id = "gemini_default",
                type = ProviderType.GEMINI,
                name = "Google Gemini Developer API",
                baseUrl = "https://generativelanguage.googleapis.com",
                modelId = "gemini-2.5-flash",
                priority = 1,
                isEnabled = true,
                hasKeySaved = false
            ),
            ProviderConfig(
                id = "openai_custom",
                type = ProviderType.OPENAI,
                name = "OpenAI API",
                baseUrl = "https://api.openai.com/v1",
                modelId = "gpt-4o-mini",
                priority = 2,
                isEnabled = true,
                hasKeySaved = false
            ),
            ProviderConfig(
                id = "grok_custom",
                type = ProviderType.GROK,
                name = "xAI Grok API",
                baseUrl = "https://api.x.ai/v1",
                modelId = "grok-beta",
                priority = 3,
                isEnabled = false,
                hasKeySaved = false
            ),
            ProviderConfig(
                id = "openrouter_custom",
                type = ProviderType.OPENROUTER,
                name = "OpenRouter API",
                baseUrl = "https://openrouter.ai/api/v1",
                modelId = "meta-llama/llama-3.1-8b-instruct",
                priority = 4,
                isEnabled = false,
                hasKeySaved = false
            ),
            ProviderConfig(
                id = "deepseek_custom",
                type = ProviderType.DEEPSEEK,
                name = "DeepSeek API",
                baseUrl = "https://api.deepseek.com/v1",
                modelId = "deepseek-chat",
                priority = 5,
                isEnabled = false,
                hasKeySaved = false
            ),
            ProviderConfig(
                id = "ollama_local",
                type = ProviderType.LOCAL_MODEL,
                name = "Local Ollama Endpoint",
                baseUrl = "http://192.168.1.100:11434/v1",
                modelId = "llama3",
                priority = 6,
                isEnabled = false,
                hasKeySaved = true // Local endpoints often need no API key
            )
        )
    )
    val configuredProviders: StateFlow<List<ProviderConfig>> = _configuredProviders.asStateFlow()

    private val _activeProvider = MutableStateFlow<ProviderConfig?>(null)
    val activeProvider: StateFlow<ProviderConfig?> = _activeProvider.asStateFlow()

    init {
        refreshActiveProvider()
    }

    fun refreshActiveProvider() {
        val providers = _configuredProviders.value.map { cfg ->
            val key = encryptedStorageManager?.getApiKey(cfg.id) ?: ""
            cfg.copy(hasKeySaved = key.isNotBlank() || cfg.type == ProviderType.LOCAL_MODEL)
        }
        _configuredProviders.value = providers

        // Select the highest priority enabled provider that has a key configured
        _activeProvider.value = providers
            .filter { it.isEnabled && it.hasKeySaved }
            .minByOrNull { it.priority }
    }

    fun updateProvider(updated: ProviderConfig) {
        val currentList = _configuredProviders.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == updated.id }
        if (index != -1) {
            currentList[index] = updated
        } else {
            currentList.add(updated)
        }
        _configuredProviders.value = currentList
        refreshActiveProvider()
    }

    private fun getAdapterFor(config: ProviderConfig): AIProviderAdapter {
        return when (config.type) {
            ProviderType.GEMINI -> GeminiProviderAdapter(config)
            else -> OpenAICompatibleAdapter(config)
        }
    }

    /**
     * Executes request against the active AI provider, with optional fallback to secondary enabled providers.
     */
    suspend fun sendRequest(
        userPrompt: String,
        history: List<AIChatMessage> = emptyList(),
        systemPrompt: String = "You are Babu, a friendly, helpful, highly capable personal Android AI assistant for NAYRO. You communicate fluently in Hindi, English, and Hinglish. Provide concise, clear, and direct responses."
    ): AIResult {
        refreshActiveProvider()

        val primary = _activeProvider.value
        if (primary == null) {
            return AIResult.Error(
                code = 400,
                message = "No AI Provider is configured. Please add an API Key (Gemini, OpenAI, Grok, etc.) in Settings."
            )
        }

        val messages = mutableListOf<AIChatMessage>()
        messages.addAll(history)
        messages.add(AIChatMessage(role = "user", content = userPrompt))

        val request = AIRequest(messages = messages, systemPrompt = systemPrompt)

        val primaryKey = encryptedStorageManager?.getApiKey(primary.id) ?: ""
        val primaryAdapter = getAdapterFor(primary)
        val primaryResult = primaryAdapter.generateResponse(request, primaryKey)

        if (primaryResult is AIResult.Success) {
            return primaryResult
        }

        // Try Fallback Provider if primary failed with retryable / availability error
        val fallbacks = _configuredProviders.value
            .filter { it.id != primary.id && it.isEnabled && it.hasKeySaved }
            .sortedBy { it.priority }

        for (fallback in fallbacks) {
            val fallbackKey = encryptedStorageManager?.getApiKey(fallback.id) ?: ""
            val fallbackAdapter = getAdapterFor(fallback)
            val fallbackResult = fallbackAdapter.generateResponse(request, fallbackKey)
            if (fallbackResult is AIResult.Success) {
                return fallbackResult
            }
        }

        return primaryResult
    }
}
