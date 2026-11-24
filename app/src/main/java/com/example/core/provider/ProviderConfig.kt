package com.example.core.provider

/**
 * Capability flags advertised by an AI provider/model pair for context-aware model selection.
 */
enum class ProviderCapability {
    TEXT_GENERATION,
    STREAMING,
    VISION,
    TOOL_CALLING,
    CODING,
    DEEP_REASONING,
    FAST_RESPONSE
}

/**
 * Task-level routing hints used by [AIProviderManager] to select the most suitable
 * configured model/provider for each request while honoring user priority & availability.
 */
enum class TaskRoutingHint {
    GENERAL,
    FAST_VOICE,
    DEEP_REASONING,
    CODING,
    VISION,
    TOOL_PLANNING
}

/**
 * Runtime health & latency telemetry recorded per provider (no fabricated metrics).
 */
data class ProviderHealthSnapshot(
    val providerId: String,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val consecutiveFailures: Int = 0,
    val rateLimitHits: Int = 0,
    val avgLatencyMs: Long = 0L,
    val cooldownUntilMs: Long = 0L,
    val lastErrorCode: Int? = null,
    val lastErrorMessage: String? = null
) {
    fun isInCooldown(nowMs: Long = System.currentTimeMillis()): Boolean = cooldownUntilMs > nowMs

    val successRate: Float
        get() {
            val total = successCount + failureCount
            return if (total == 0) 1.0f else successCount.toFloat() / total.toFloat()
        }
}

data class ProviderConfig(
    val id: String,
    val type: ProviderType,
    val name: String,
    val baseUrl: String,
    val modelId: String,
    val priority: Int = 1,
    val isEnabled: Boolean = true,
    val hasKeySaved: Boolean = false,
    val capabilities: Set<ProviderCapability> = defaultCapabilitiesFor(type, modelId),
    val contextWindowTokens: Int = defaultContextWindowFor(type, modelId)
) {
    fun supports(capability: ProviderCapability): Boolean = capability in capabilities

    companion object {
        fun defaultCapabilitiesFor(type: ProviderType, modelId: String): Set<ProviderCapability> {
            val m = modelId.lowercase()
            val caps = linkedSetOf(
                ProviderCapability.TEXT_GENERATION,
                ProviderCapability.STREAMING,
                ProviderCapability.TOOL_CALLING
            )
            when (type) {
                ProviderType.GEMINI -> {
                    caps.add(ProviderCapability.VISION)
                    caps.add(ProviderCapability.CODING)
                    if (m.contains("flash")) caps.add(ProviderCapability.FAST_RESPONSE)
                    if (m.contains("pro") || m.contains("2.5") || m.contains("thinking")) {
                        caps.add(ProviderCapability.DEEP_REASONING)
                    }
                }
                ProviderType.OPENAI -> {
                    if (m.contains("4o") || m.contains("vision") || m.contains("gpt-4.1") || m.contains("gpt-5")) {
                        caps.add(ProviderCapability.VISION)
                    }
                    caps.add(ProviderCapability.CODING)
                    if (m.contains("mini") || m.contains("nano")) caps.add(ProviderCapability.FAST_RESPONSE)
                    if (m.contains("o1") || m.contains("o3") || m.contains("4o") || m.contains("gpt-5")) {
                        caps.add(ProviderCapability.DEEP_REASONING)
                    }
                }
                ProviderType.GROK -> {
                    caps.add(ProviderCapability.CODING)
                    caps.add(ProviderCapability.DEEP_REASONING)
                    if (m.contains("vision") || m.contains("grok-2") || m.contains("grok-3") || m.contains("grok-4")) {
                        caps.add(ProviderCapability.VISION)
                    }
                    if (m.contains("fast") || m.contains("mini")) caps.add(ProviderCapability.FAST_RESPONSE)
                }
                ProviderType.DEEPSEEK -> {
                    caps.add(ProviderCapability.CODING)
                    caps.add(ProviderCapability.DEEP_REASONING)
                    if (m.contains("chat")) caps.add(ProviderCapability.FAST_RESPONSE)
                }
                ProviderType.OPENROUTER -> {
                    caps.add(ProviderCapability.CODING)
                    if (m.contains("vision") || m.contains("4o") || m.contains("claude-3") || m.contains("gemini")) {
                        caps.add(ProviderCapability.VISION)
                    }
                    if (m.contains("8b") || m.contains("mini") || m.contains("flash") || m.contains("haiku")) {
                        caps.add(ProviderCapability.FAST_RESPONSE)
                    }
                    if (m.contains("70b") || m.contains("sonnet") || m.contains("opus") || m.contains("r1") || m.contains("pro")) {
                        caps.add(ProviderCapability.DEEP_REASONING)
                    }
                }
                ProviderType.CUSTOM_OPENAI -> {
                    caps.add(ProviderCapability.CODING)
                    if (m.contains("vision") || m.contains("4o") || m.contains("vl") || m.contains("gemini") || m.contains("claude")) {
                        caps.add(ProviderCapability.VISION)
                    }
                    if (m.contains("mini") || m.contains("flash") || m.contains("8b")) {
                        caps.add(ProviderCapability.FAST_RESPONSE)
                    }
                }
                ProviderType.LOCAL_MODEL -> {
                    caps.add(ProviderCapability.FAST_RESPONSE)
                    if (m.contains("coder") || m.contains("qwen") || m.contains("deepseek")) {
                        caps.add(ProviderCapability.CODING)
                    }
                    if (m.contains("llava") || m.contains("vision") || m.contains("vl")) {
                        caps.add(ProviderCapability.VISION)
                    }
                }
            }
            return caps
        }

        fun defaultContextWindowFor(type: ProviderType, modelId: String): Int {
            val m = modelId.lowercase()
            return when {
                type == ProviderType.GEMINI -> 1_000_000
                m.contains("gpt-4o") || m.contains("gpt-4.1") -> 128_000
                type == ProviderType.GROK -> 131_072
                type == ProviderType.DEEPSEEK -> 64_000
                type == ProviderType.LOCAL_MODEL -> 8_192
                else -> 32_768
            }
        }
    }
}
