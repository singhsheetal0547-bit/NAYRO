package com.example.core.provider

data class ProviderConfig(
    val id: String,
    val type: ProviderType,
    val name: String,
    val baseUrl: String,
    val modelId: String,
    val priority: Int = 1,
    val isEnabled: Boolean = true,
    val hasKeySaved: Boolean = false
)
