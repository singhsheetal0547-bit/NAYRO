package com.example.core.provider

import com.example.core.network.NetworkConnectivityMonitor
import com.example.core.network.NetworkStatus
import com.example.core.network.NetworkTransportType
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

data class OnlineProviderStatus(
    val isNetworkOnline: Boolean,
    val transportType: NetworkTransportType,
    val activeProviderName: String?,
    val activeProviderId: String?,
    val lastCallSucceeded: Boolean? = null,
    val lastErrorMessage: String? = null
) {
    val summary: String
        get() = when {
            !isNetworkOnline -> "Offline (${transportType.displayName}) • Provider: ${activeProviderName ?: "Not configured"}"
            activeProviderName == null -> "Online (${transportType.displayName}) • No AI provider configured"
            lastCallSucceeded == false && !lastErrorMessage.isNullOrBlank() ->
                "Online (${transportType.displayName}) • $activeProviderName error: $lastErrorMessage"
            else -> "Online (${transportType.displayName}) • Active AI: $activeProviderName"
        }
}

/**
 * System One — Multi-Provider AI Engine & Router for NAYRO.
 * Manages provider configurations, credential retrieval via KeyStore EncryptedStorageManager,
 * real Android Wi-Fi/Mobile Data internet detection, streaming responses, default routing, and seamless failover.
 */
class AIProviderManager(
    private val encryptedStorageManager: EncryptedStorageManager? = null,
    val connectivityMonitor: NetworkConnectivityMonitor? = null
) {

    /**
     * Optional test hook allowing deterministic unit verification of failover, retry,
     * streaming, and offline honesty without live network traffic.
     */
    var customAdapterFactory: ((ProviderConfig) -> AIProviderAdapter)? = null

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

    private val _onlineStatus = MutableStateFlow(
        OnlineProviderStatus(
            isNetworkOnline = connectivityMonitor?.checkCurrentStatus()?.isOnline ?: true,
            transportType = connectivityMonitor?.networkStatus?.value?.transportType ?: NetworkTransportType.OTHER,
            activeProviderName = null,
            activeProviderId = null
        )
    )
    val onlineStatus: StateFlow<OnlineProviderStatus> = _onlineStatus.asStateFlow()

    init {
        restorePersistedProviderMetadata()
        refreshActiveProvider()
    }

    fun getNetworkStatus(): NetworkStatus {
        return connectivityMonitor?.checkCurrentStatus() ?: NetworkStatus(
            isConnected = true,
            hasValidatedInternet = true,
            transportType = NetworkTransportType.OTHER
        )
    }

    private fun updateOnlineStatus(lastCallSucceeded: Boolean? = _onlineStatus.value.lastCallSucceeded, lastError: String? = null) {
        val net = getNetworkStatus()
        val active = _activeProvider.value
        _onlineStatus.value = OnlineProviderStatus(
            isNetworkOnline = net.isOnline,
            transportType = net.transportType,
            activeProviderName = active?.name,
            activeProviderId = active?.id,
            lastCallSucceeded = lastCallSucceeded,
            lastErrorMessage = lastError
        )
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
                hasKeySaved = hasKey || cfg.type == ProviderType.LOCAL_MODEL,
                capabilities = ProviderConfig.defaultCapabilitiesFor(cfg.type, savedModel),
                contextWindowTokens = ProviderConfig.defaultContextWindowFor(cfg.type, savedModel)
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

    private fun syncGeminiKeyFromAdvancePrefsIfMissing(): String {
        val storage = encryptedStorageManager ?: return ""
        val current = storage.getApiKey("gemini_default").trim()
        if (current.isNotEmpty()) return current
        return try {
            val fallbackKey = com.example.NayroApplication.instance.prefs.geminiApiKey.trim()
            if (fallbackKey.isNotEmpty()) {
                storage.saveApiKey("gemini_default", fallbackKey)
            }
            fallbackKey
        } catch (_: Throwable) {
            ""
        }
    }

    fun refreshActiveProvider() {
        syncGeminiKeyFromAdvancePrefsIfMissing()
        val providers = _configuredProviders.value.map { cfg ->
            val key = encryptedStorageManager?.getApiKey(cfg.id) ?: ""
            val hasKey = key.isNotBlank() || cfg.type == ProviderType.LOCAL_MODEL
            val autoEnableGemini = cfg.id == "gemini_default" && key.isNotBlank()
            cfg.copy(
                hasKeySaved = hasKey,
                isEnabled = cfg.isEnabled || autoEnableGemini
            )
        }
        _configuredProviders.value = providers

        // Select the highest priority enabled provider that has a key configured
        _activeProvider.value = providers
            .filter { it.isEnabled && it.hasKeySaved }
            .minByOrNull { it.priority }
        updateOnlineStatus()
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
        if (providerId == "gemini_default") {
            try {
                com.example.NayroApplication.instance.prefs.geminiApiKey = cleanKey
            } catch (_: Throwable) {
            }
        }
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
        if (providerId == "gemini_default") {
            try {
                com.example.NayroApplication.instance.prefs.geminiApiKey = ""
            } catch (_: Throwable) {
            }
        }
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
                val next = cfg.copy(
                    modelId = cleanModel,
                    capabilities = ProviderConfig.defaultCapabilitiesFor(cfg.type, cleanModel),
                    contextWindowTokens = ProviderConfig.defaultContextWindowFor(cfg.type, cleanModel)
                )
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
        customAdapterFactory?.let { return it(config) }
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
        if (target.type != ProviderType.LOCAL_MODEL && connectivityMonitor != null) {
            val net = connectivityMonitor.checkCurrentStatus()
            if (!net.isOnline) {
                val err = AIResult.Error(
                    0,
                    "Offline: Cannot reach ${target.name} because Wi-Fi and Mobile Data are disconnected.",
                    isRetryable = true
                )
                updateOnlineStatus(lastCallSucceeded = false, lastError = err.message)
                return err
            }
        }
        val testRequest = AIRequest(
            messages = listOf(AIChatMessage(role = "user", content = "Reply with OK.")),
            systemPrompt = "You are a connectivity test endpoint. Reply concisely with OK."
        )
        val result = getAdapterFor(target).generateResponse(testRequest, key)
        when (result) {
            is AIResult.Success -> updateOnlineStatus(lastCallSucceeded = true, lastError = null)
            is AIResult.Error -> updateOnlineStatus(lastCallSucceeded = false, lastError = result.message)
        }
        return result
    }

    private val providerHealthLock = Any()
    private val providerHealthMap = mutableMapOf<String, ProviderHealthSnapshot>()

    /**
     * Injectable clock for deterministic cooldown & latency unit testing.
     */
    var currentTimeProvider: () -> Long = { System.currentTimeMillis() }

    /**
     * Maximum transient retry attempts on a single healthy provider before failing over.
     */
    var maxTransientRetriesPerProvider: Int = 1

    fun getProviderHealthSnapshots(): Map<String, ProviderHealthSnapshot> = synchronized(providerHealthLock) {
        providerHealthMap.toMap()
    }

    fun clearProviderCooldowns() = synchronized(providerHealthLock) {
        providerHealthMap.clear()
    }

    private fun recordProviderSuccess(providerId: String, latencyMs: Long) {
        synchronized(providerHealthLock) {
            val prev = providerHealthMap[providerId] ?: ProviderHealthSnapshot(providerId)
            val newSuccess = prev.successCount + 1
            val newAvgLatency = if (prev.successCount == 0) {
                latencyMs.coerceAtLeast(1L)
            } else {
                ((prev.avgLatencyMs * prev.successCount) + latencyMs.coerceAtLeast(1L)) / newSuccess
            }
            providerHealthMap[providerId] = prev.copy(
                successCount = newSuccess,
                consecutiveFailures = 0,
                avgLatencyMs = newAvgLatency,
                cooldownUntilMs = 0L,
                lastErrorCode = null,
                lastErrorMessage = null
            )
        }
    }

    private fun recordProviderFailure(providerId: String, code: Int, message: String) {
        val now = currentTimeProvider()
        synchronized(providerHealthLock) {
            val prev = providerHealthMap[providerId] ?: ProviderHealthSnapshot(providerId)
            val nextConsecutive = prev.consecutiveFailures + 1
            val isRateLimited = code == 429
            val isAuthError = code == 401 || code == 403
            val cooldownMs = when {
                isRateLimited -> (15_000L * (1L shl (nextConsecutive - 1).coerceAtMost(3))).coerceAtMost(120_000L)
                isAuthError -> 60_000L
                code >= 500 || code == 408 -> (5_000L * nextConsecutive.coerceAtMost(4))
                else -> 0L
            }
            providerHealthMap[providerId] = prev.copy(
                failureCount = prev.failureCount + 1,
                consecutiveFailures = nextConsecutive,
                rateLimitHits = prev.rateLimitHits + if (isRateLimited) 1 else 0,
                cooldownUntilMs = if (cooldownMs > 0L) now + cooldownMs else prev.cooldownUntilMs,
                lastErrorCode = code,
                lastErrorMessage = message.take(200)
            )
        }
    }

    /**
     * Infers the best [TaskRoutingHint] from prompt intent and attachments when caller does not specify one.
     */
    fun inferRoutingHint(userPrompt: String, hasImages: Boolean = false): TaskRoutingHint {
        if (hasImages) return TaskRoutingHint.VISION
        val lower = userPrompt.lowercase()
        return when {
            listOf(
                "code", "python", "kotlin", "javascript", "typescript", "react", "bug", "debug",
                "stacktrace", "refactor", "algorithm", "function", "compile", "script", "sql", "json schema"
            ).any { lower.contains(it) } -> TaskRoutingHint.CODING

            listOf(
                "analyze", "compare", "architecture", "strategy", "trade-off", "research",
                "step by step", "plan ", "explain in detail", "deep dive", "spreadsheet", "csv"
            ).any { lower.contains(it) } -> TaskRoutingHint.DEEP_REASONING

            userPrompt.length <= 70 -> TaskRoutingHint.FAST_VOICE
            else -> TaskRoutingHint.GENERAL
        }
    }

    /**
     * Context-aware model & provider selection (Section 2):
     * Ranks enabled, key-configured providers by:
     * 1. Health / non-cooldown status (rate-limited providers are deprioritized while healthy fallbacks exist)
     * 2. Capability fit for [hint] and [requiresVision]
     * 3. User-configured priority
     */
    fun selectOrderedCandidatesForTask(
        hint: TaskRoutingHint = TaskRoutingHint.GENERAL,
        requiresVision: Boolean = false,
        nowMs: Long = currentTimeProvider()
    ): List<ProviderConfig> {
        val eligible = _configuredProviders.value.filter { it.isEnabled && it.hasKeySaved }
        if (eligible.isEmpty()) return emptyList()

        val healthSnapshot = synchronized(providerHealthLock) { providerHealthMap.toMap() }
        val visionFiltered = if (requiresVision) {
            val visionCapable = eligible.filter { it.supports(ProviderCapability.VISION) }
            if (visionCapable.isNotEmpty()) visionCapable else eligible
        } else {
            eligible
        }

        val requiredCap = when (hint) {
            TaskRoutingHint.VISION -> ProviderCapability.VISION
            TaskRoutingHint.CODING -> ProviderCapability.CODING
            TaskRoutingHint.DEEP_REASONING -> ProviderCapability.DEEP_REASONING
            TaskRoutingHint.FAST_VOICE -> ProviderCapability.FAST_RESPONSE
            TaskRoutingHint.TOOL_PLANNING -> ProviderCapability.TOOL_CALLING
            TaskRoutingHint.GENERAL -> ProviderCapability.TEXT_GENERATION
        }

        return visionFiltered.sortedWith(
            compareBy<ProviderConfig>(
                // 1. Healthy (not in rate-limit/error cooldown) comes first
                { cfg -> if (healthSnapshot[cfg.id]?.isInCooldown(nowMs) == true) 1 else 0 },
                // 2. When explicit specialized capability is requested (vision/coding/deep reasoning), prefer capable models
                { cfg ->
                    val needsStrictCap = hint in setOf(
                        TaskRoutingHint.VISION,
                        TaskRoutingHint.CODING,
                        TaskRoutingHint.DEEP_REASONING
                    ) || requiresVision
                    if (needsStrictCap && !cfg.supports(requiredCap)) 1 else 0
                },
                // 3. Honor user priority
                { cfg -> cfg.priority },
                // 4. Prefer models supporting the soft capability hint as tie-breaker
                { cfg -> if (cfg.supports(requiredCap)) 0 else 1 }
            )
        )
    }

    /**
     * Executes request against the active AI provider, with context-aware model selection and automatic failover.
     * Checks real Android network availability before cloud requests so NAYRO never hangs or pretends to be online.
     */
    suspend fun sendRequest(
        userPrompt: String,
        history: List<AIChatMessage> = emptyList(),
        systemPrompt: String = defaultSystemPrompt()
    ): AIResult {
        return sendTaskAwareRequest(
            userPrompt = userPrompt,
            routingHint = inferRoutingHint(userPrompt, hasImages = false),
            history = history,
            systemPrompt = systemPrompt,
            images = emptyList(),
            onToken = null
        )
    }

    /**
     * Executes a multimodal Vision AI request (screenshot + prompt) with vision-capable provider routing and failover.
     */
    suspend fun sendVisionRequest(
        userPrompt: String,
        imageBase64: String,
        mimeType: String = "image/jpeg",
        history: List<AIChatMessage> = emptyList(),
        systemPrompt: String = defaultSystemPrompt()
    ): AIResult {
        val attachments = if (imageBase64.isNotBlank()) {
            listOf(com.example.core.provider.model.AIImageAttachment(imageBase64, mimeType))
        } else {
            emptyList()
        }
        return sendTaskAwareRequest(
            userPrompt = userPrompt,
            routingHint = TaskRoutingHint.VISION,
            history = history,
            systemPrompt = systemPrompt,
            images = attachments,
            onToken = null
        )
    }

    /**
     * Executes a streaming AI request where supported by the active provider (Gemini SSE / OpenAI-compatible SSE),
     * falling back cleanly across secondary configured providers on failure.
     */
    suspend fun sendStreamingRequest(
        userPrompt: String,
        history: List<AIChatMessage> = emptyList(),
        systemPrompt: String = defaultSystemPrompt(),
        onToken: (String) -> Unit
    ): AIResult {
        return sendTaskAwareRequest(
            userPrompt = userPrompt,
            routingHint = inferRoutingHint(userPrompt, hasImages = false),
            history = history,
            systemPrompt = systemPrompt,
            images = emptyList(),
            onToken = onToken
        )
    }

    /**
     * Context-aware routed AI execution supporting explicit [TaskRoutingHint], vision attachments,
     * streaming callbacks, bounded transient retries, rate-limit cooldowns, and multi-provider failover.
     */
    suspend fun sendTaskAwareRequest(
        userPrompt: String,
        routingHint: TaskRoutingHint = TaskRoutingHint.GENERAL,
        history: List<AIChatMessage> = emptyList(),
        systemPrompt: String = defaultSystemPrompt(),
        images: List<com.example.core.provider.model.AIImageAttachment> = emptyList(),
        onToken: ((String) -> Unit)? = null
    ): AIResult {
        return executeRoutedRequest(
            userPrompt = userPrompt,
            history = history,
            systemPrompt = systemPrompt,
            images = images,
            onToken = onToken,
            routingHint = routingHint
        )
    }

    private fun defaultSystemPrompt(): String {
        val store = com.example.core.identity.AssistantIdentityStore.sharedInstance
        if (store != null) {
            val hub = com.example.core.identity.PersonalIntelligenceHub(store)
            return hub.adaptiveConversationEngine.buildPersonalizedSystemPrompt()
        }
        val activeName = com.example.core.identity.AssistantIdentityProfile.DEFAULT_DISPLAY_NAME
        return "You are $activeName, a friendly, helpful, highly capable personal Android AI assistant for NAYRO. You communicate fluently in Hindi, English, and Hinglish. Provide concise, clear, and direct responses."
    }

    private suspend fun executeRoutedRequest(
        userPrompt: String,
        history: List<AIChatMessage>,
        systemPrompt: String,
        images: List<com.example.core.provider.model.AIImageAttachment>,
        onToken: ((String) -> Unit)?,
        routingHint: TaskRoutingHint = TaskRoutingHint.GENERAL
    ): AIResult {
        refreshActiveProvider()

        val orderedCandidates = selectOrderedCandidatesForTask(
            hint = routingHint,
            requiresVision = images.isNotEmpty()
        )
        if (orderedCandidates.isEmpty()) {
            val err = AIResult.Error(
                code = 400,
                message = "No AI Provider is configured. Please add an API Key (Gemini, Grok, OpenRouter, OpenAI, or DeepSeek) in Settings."
            )
            updateOnlineStatus(lastCallSucceeded = false, lastError = err.message)
            return err
        }

        val netStatus = connectivityMonitor?.checkCurrentStatus()
        val isDeviceOffline = netStatus != null && !netStatus.isOnline

        val messages = mutableListOf<AIChatMessage>()
        messages.addAll(history)
        messages.add(AIChatMessage(role = "user", content = userPrompt))
        val configuredMaxTokens = com.example.core.identity.AssistantIdentityStore.sharedInstance
            ?.getCurrentProfile()?.responseLength?.suggestedMaxTokens ?: 1024
        val request = AIRequest(
            messages = messages,
            systemPrompt = systemPrompt,
            maxTokens = configuredMaxTokens,
            images = images
        )

        var firstError: AIResult.Error? = null

        for (candidate in orderedCandidates) {
            if (isDeviceOffline && candidate.type != ProviderType.LOCAL_MODEL) {
                if (firstError == null) {
                    firstError = AIResult.Error(
                        code = 0,
                        message = "No internet connection (${netStatus?.summary ?: "Offline"}). Cloud AI provider ${candidate.name} is unreachable.",
                        isRetryable = true
                    )
                }
                continue
            }

            val key = encryptedStorageManager?.getApiKey(candidate.id) ?: ""
            val adapter = getAdapterFor(candidate)
            val maxAttempts = 1 + maxTransientRetriesPerProvider.coerceIn(0, 2)
            var candidateResult: AIResult? = null

            for (attempt in 1..maxAttempts) {
                val startMs = currentTimeProvider()
                val attemptResult = if (onToken != null) {
                    var emittedAnyToken = false
                    val streamResult = adapter.generateStreamingResponse(request, key) { token ->
                        emittedAnyToken = true
                        onToken(token)
                    }
                    // If streaming endpoint itself failed before emitting tokens, try standard non-streaming endpoint once
                    if (streamResult is AIResult.Error && !emittedAnyToken && streamResult.code in listOf(400, 404, 405, 501)) {
                        val stdRes = adapter.generateResponse(request, key)
                        if (stdRes is AIResult.Success && stdRes.response.content.isNotEmpty()) {
                            onToken(stdRes.response.content)
                        }
                        stdRes
                    } else {
                        streamResult
                    }
                } else {
                    adapter.generateResponse(request, key)
                }
                val elapsedMs = (currentTimeProvider() - startMs).coerceAtLeast(1L)
                candidateResult = attemptResult

                if (attemptResult is AIResult.Success) {
                    recordProviderSuccess(candidate.id, elapsedMs)
                    updateOnlineStatus(lastCallSucceeded = true, lastError = null)
                    return attemptResult
                }

                if (attemptResult is AIResult.Error) {
                    val isTransientGatewayError = attemptResult.isRetryable &&
                        attemptResult.code in setOf(408, 502, 503, 504)
                    if (isTransientGatewayError && attempt < maxAttempts) {
                        continue
                    }
                    recordProviderFailure(candidate.id, attemptResult.code, attemptResult.message)
                    if (firstError == null) {
                        firstError = attemptResult
                    }
                    break
                }
            }

            if (candidateResult is AIResult.Success) {
                updateOnlineStatus(lastCallSucceeded = true, lastError = null)
                return candidateResult
            }
        }

        val finalError = firstError ?: AIResult.Error(500, "All configured AI providers failed.")
        updateOnlineStatus(lastCallSucceeded = false, lastError = finalError.message)
        return finalError
    }
}

