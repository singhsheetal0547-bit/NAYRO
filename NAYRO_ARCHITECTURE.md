# NAYRO System Architecture & Component Mapping

## 1. High-Level Architecture
```
┌────────────────────────────────────────────────────────────────────────┐
│                          PRESENTATION LAYER                            │
│  HomeScreen  |  ConversationScreen  |  BackgroundControlScreen         │
│  PermissionAccessScreen  |  CapabilitiesScreen  |  SettingsScreen      │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│                       UNIFIED AGENT PIPELINE                           │
│  MainActivity.processUserPrompt()  ◄── Floating Overlay (Intent extra) │
│  Shared Room Conversation DB       ◄── Voice ASR (SpeechRecognizer)    │
│  Text-to-Speech Spoken Feedback    ◄── Manual Text Input Composer      │
└───────────────────┬────────────────────────────────┬───────────────────┘
                    │                                │
┌───────────────────▼───────────┐  ┌─────────────────▼───────────────────┐
│     AI PROVIDER ROUTER        │  │     VOICE & WAKE-WORD ENGINE        │
│ Gemini / OpenAI / Grok /      │  │ LocalWakeWordEngine (On-Device PCM) │
│ DeepSeek / OpenRouter / Ollama│  │ Android SpeechRecognizer + TTS      │
└───────────────────┬───────────┘  └─────────────────┬───────────────────┘
                    │                                │
┌───────────────────▼────────────────────────────────▼───────────────────┐
│                      ANDROID SYSTEM INTEGRATION                        │
│  NayroForegroundService (specialUse) | NayroFloatingService (Overlay)  │
│  ServiceStateRepository              | PermissionManager               │
│  CapabilityManager                   | Android KeyStore AES-256 GCM    │
└────────────────────────────────────────────────────────────────────────┘
```

## 2. Directory & Module Blueprint
- **`com.example.core.floating`**: WindowManager `TYPE_APPLICATION_OVERLAY` service, position persistence, and intent prompt passing.
- **`com.example.core.service`**: Android Foreground Service (`specialUse`), `ServiceStateRepository`, and lifecycle observation.
- **`com.example.core.permission`**: `PermissionManager`, `PermissionRegistry`, `PermissionEvaluation`, and manifest inspectors.
- **`com.example.core.capability`**: `CapabilityManager` and `CapabilityRegistry` dynamic availability evaluators.
- **`com.example.core.provider`**: `AIProviderManager`, `GeminiProviderAdapter`, `OpenAICompatibleAdapter`, `AIRequest`, and `AIResponse`.
- **`com.example.core.voice`**: `VoiceManager`, `LocalWakeWordEngine`, ASR, TTS, and audio focus coordination.
- **`com.example.core.security`**: `KeystoreManager` and `EncryptedStorageManager` for zero-plaintext key storage.
- **`com.example.data.local`**: `NayroDatabase`, DAOs, and Room entities.
- **`com.example.ui`**: Jetpack Compose screens styled in NAYRO's design tokens (Forest Green, Warm Ivory, Coral, Soft Sage).
