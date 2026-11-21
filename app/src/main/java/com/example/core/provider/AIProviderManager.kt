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
                modelId = "grok-2-latest",
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
        restorePersistedProviderMetadata()
        refreshActiveProvider()
    }

    private fun restorePersistedProviderMetadata() {
        val storage = encryptedStorageManager ?: return
        val restored = _configuredProviders.value.map { cfg ->
            val savedModel = storage.getPreference("provider_model_${cfg.id}", "").ifBlank { cfg.modelId }
            val savedUrl = storage.getPreference("provider_url_${cfg.id}", "").ifBlank { cfg.baseUrl }
            val savedPriority = storage.getPreference("provider_priority_${cfg.id}", "").toIntOrNull() ?: cfg.priority
            val savedEnabledStr = storage.getPreference("provider_enabled_${cfg.id}", "")
            val hasKey = storage.getApiKey(cfg.id).isNotBlank()
            val resolvedEnabled = when (savedEnabledStr) {
                "true" -> true
                "false" -> false
                else -> cfg.isEnabled || (hasKey && cfg.type != ProviderType.LOCAL_MODEL)
            }
            cfg.copy(
                modelId = savedModel,
                baseUrl = savedUrl,
                priority = savedPriority,
                isEnabled = resolvedEnabled,
                hasKeySaved = hasKey || cfg.type == ProviderType.LOCAL_MODEL
            )
        }
        _configuredProviders.value = restored
    }

    private fun persistProviderMetadata(cfg: ProviderConfig) {
        val storage = encryptedStorageManager ?: return
        storage.savePreference("provider_model_${cfg.id}", cfg.modelId)
        storage.savePreference("provider_url_${cfg.id}", cfg.baseUrl)
        storage.savePreference("provider_priority_${cfg.id}", cfg.priority.toString())
        storage.savePreference("provider_enabled_${cfg.id}", cfg.isEnabled.toString())
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
        persistProviderMetadata(updated)
        refreshActiveProvider()
    }

    /**
     * Saves or updates an API key in hardware-encrypted storage and automatically enables
     * the provider so multi-provider routing and fallback stay synchronized across restarts.
     */
    fun saveApiKey(providerId: String, apiKey: String) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isEmpty()) {
            deleteApiKey(providerId)
            return
        }
        encryptedStorageManager?.saveApiKey(providerId, cleanKey)
        val updatedList = _configuredProviders.value.map { cfg ->
            if (cfg.id == providerId) {
                val updated = cfg.copy(isEnabled = true, hasKeySaved = true)
                persistProviderMetadata(updated)
                updated
            } else {
                cfg
            }
        }
        _configuredProviders.value = updatedList
        refreshActiveProvider()
    }

    /**
     * Securely removes a saved API key for [providerId] and recalculates the active route/fallback.
     */
    fun deleteApiKey(providerId: String) {
        encryptedStorageManager?.removeApiKey(providerId)
        val updatedList = _configuredProviders.value.map { cfg ->
            if (cfg.id == providerId) {
                cfg.copy(hasKeySaved = cfg.type == ProviderType.LOCAL_MODEL)
            } else {
                cfg
            }
        }
        _configuredProviders.value = updatedList
        refreshActiveProvider()
    }

    /**
     * Promotes [providerId] to priority 1 (primary active AI provider) while keeping
     * other configured providers ordered as automatic fallbacks.
     */
    fun selectPreferredProvider(providerId: String): Boolean {
        val exists = _configuredProviders.value.any { it.id == providerId }
        if (!exists) return false
        var nextPriority = 2
        val reordered = _configuredProviders.value
            .sortedBy { it.priority }
            .map { cfg ->
                val updated = if (cfg.id == providerId) {
                    cfg.copy(priority = 1, isEnabled = true)
                } else {
                    cfg.copy(priority = nextPriority++)
                }
                persistProviderMetadata(updated)
                updated
            }
        _configuredProviders.value = reordered
        refreshActiveProvider()
        return true
    }

    fun updateProviderModel(providerId: String, modelId: String): Boolean {
        val cleanModel = modelId.trim()
        if (cleanModel.isEmpty()) return false
        var updated = false
        val newList = _configuredProviders.value.map { cfg ->
            if (cfg.id == providerId) {
                updated = true
                val next = cfg.copy(modelId = cleanModel)
                persistProviderMetadata(next)
                next
            } else {
                cfg
            }
        }
        if (updated) {
            _configuredProviders.value = newList
            refreshActiveProvider()
        }
        return updated
    }

    fun setProviderEnabled(providerId: String, enabled: Boolean) {
        _configuredProviders.value = _configuredProviders.value.map { cfg ->
            if (cfg.id == providerId) {
                val next = cfg.copy(isEnabled = enabled)
                persistProviderMetadata(next)
                next
            } else {
                cfg
            }
        }
        refreshActiveProvider()
    }

    private fun getAdapterFor(config: ProviderConfig): AIProviderAdapter {
        return when (config.type) {
            ProviderType.GEMINI -> GeminiProviderAdapter(config)
            else -> OpenAICompatibleAdapter(config)
        }
    }

    /**
     * Tests connectivity directly against a specific configured provider using its own saved API key.
     */
    suspend fun testProviderConnection(providerId: String): AIResult {
        refreshActiveProvider()
        val target = _configuredProviders.value.firstOrNull { it.id == providerId }
            ?: return AIResult.Error(404, "Provider '$providerId' not found.")
        val key = encryptedStorageManager?.getApiKey(target.id).orEmpty()
        if (key.isBlank() && target.type != ProviderType.LOCAL_MODEL) {
            return AIResult.Error(401, "No API key saved for ${target.name}.")
        }
        val testRequest = AIRequest(
            messages = listOf(AIChatMessage(role = "user", content = "Reply with OK.")),
            systemPrompt = "You are a connectivity test endpoint. Reply concisely with OK."
        )
        return getAdapterFor(target).generateResponse(testRequest, key)
    }

    /**
     * Executes request against the active AI provider, with optional fallback to secondary enabled providers.
     */
    suspend fun sendRequest(
        userPrompt: String,
        history: List<AIChatMessage> = emptyList(),
        systemPrompt: String = run {
            val activeName = com.example.core.identity.AssistantIdentityStore.sharedInstance?.getDisplayName()
                ?: com.example.core.identity.AssistantIdentityProfile.DEFAULT_DISPLAY_NAME
            "You are $activeName, a friendly, helpful, highly capable personal Android AI assistant for NAYRO. You communicate fluently in Hindi, English, and Hinglish. Provide concise, clear, and direct responses."
        }
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
