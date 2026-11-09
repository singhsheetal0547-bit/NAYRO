package com.example.core.provider

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Architecture Skeleton for Modular AI Provider Manager.
 * Supports provider routing, failover, and capabilities evaluation.
 */
class AIProviderManager {

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
                isEnabled = false,
                hasKeySaved = false
            ),
            ProviderConfig(
                id = "ollama_local",
                type = ProviderType.LOCAL_MODEL,
                name = "Local Ollama Endpoint",
                baseUrl = "http://192.168.1.100:11434/v1",
                modelId = "llama3",
                priority = 3,
                isEnabled = false,
                hasKeySaved = true
            )
        )
    )
    val configuredProviders: StateFlow<List<ProviderConfig>> = _configuredProviders

    private val _activeProvider = MutableStateFlow<ProviderConfig?>(null)
    val activeProvider: StateFlow<ProviderConfig?> = _activeProvider

    init {
        _activeProvider.value = _configuredProviders.value.firstOrNull { it.isEnabled && it.hasKeySaved }
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
        _activeProvider.value = currentList.firstOrNull { it.isEnabled && it.hasKeySaved }
    }
}
