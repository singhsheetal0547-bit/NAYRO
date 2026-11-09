package com.example.core.provider

enum class ProviderType(val displayName: String, val supportsCustomBaseUrl: Boolean) {
    GEMINI("Google Gemini Developer API", false),
    OPENAI("OpenAI API", true),
    GROK("xAI Grok API", true),
    OPENROUTER("OpenRouter API", true),
    DEEPSEEK("DeepSeek API", true),
    CUSTOM_OPENAI("Custom OpenAI-Compatible Endpoint", true),
    LOCAL_MODEL("Local Model Endpoint (Ollama/LMStudio)", true)
}
