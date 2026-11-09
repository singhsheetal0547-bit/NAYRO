# NAYRO Module Structure & Package Mapping

## 1. Directory Blueprint
```
com.example/
├── MainActivity.kt                       # Entry activity, navigation scaffold & runtime wiring
├── core/
│   ├── agent/
│   │   ├── AgentRuntime.kt              # Agent state machine and request lifecycle skeleton
│   │   ├── AgentState.kt                # UI & Execution states (Idle, Listening, Thinking, Speaking, etc.)
│   │   ├── ActionProposal.kt            # Action proposal & verified execution data models
│   │   └── RiskLevel.kt                 # R0-R4 risk evaluation rules
│   ├── permission/
│   │   ├── PermissionManager.kt         # Runtime & special system permission manager
│   │   ├── PermissionState.kt           # Granted, Denied, NeedsSystemSettings states
│   │   └── PermissionType.kt            # Microphone, Overlay, Accessibility, Notifications, etc.
│   ├── provider/
│   │   ├── AIProviderManager.kt         # Multi-provider router and configuration state
│   │   ├── ProviderConfig.kt            # Provider parameters (URL, Model, Key Alias)
│   │   └── ProviderType.kt              # Gemini, OpenAI, Grok, OpenRouter, DeepSeek, Local Model
│   ├── security/
│   │   ├── KeystoreManager.kt           # Android KeyStore AES-256 GCM encryption engine
│   │   └── EncryptedStorageManager.kt   # Encrypted SharedPreferences helper
│   ├── service/
│   │   └── NayroForegroundService.kt    # Background agent Android Foreground Service
│   └── voice/
│       ├── VoiceManager.kt              # Wake-word, ASR, TTS & audio lifecycle coordinator
│       └── VoiceState.kt                # Voice engine states & configuration
├── data/
│   ├── local/
│   │   ├── NayroDatabase.kt             # Room database definition (version 1)
│   │   ├── dao/
│   │   │   └── Daos.kt                  # AppSettings, Profile, Conversation, Memory, Audit DAOs
│   │   └── entities/
│   │       └── Entities.kt              # SQLite tables definitions
│   └── repository/
│       └── NayroRepository.kt           # Room Data Repository pattern interface
└── ui/
    ├── components/
    │   ├── AssistantOrb.kt              # Animated Babu Orb composable
    │   ├── NayroBars.kt                 # Material 3 Top and Bottom bars
    │   └── StatusBadge.kt               # Status pills for engine state
    ├── home/
    │   └── HomeScreen.kt                # Main assistant home UI
    ├── conversation/
    │   └── ConversationScreen.kt        # Voice/Text chat dialogue history UI
    ├── capabilities/
    │   └── CapabilitiesScreen.kt        # Native Android tool & permission registry
    ├── activity/
    │   └── ActivityScreen.kt            # Verifiable action & audit log UI
    ├── memory/
    │   └── MemoryScreen.kt              # Local memory vault UI
    ├── settings/
    │   └── SettingsScreen.kt            # Complete assistant & AI provider settings
    ├── onboarding/
    │   └── OnboardingScreen.kt          # Interactive first-run onboarding sequence
    ├── navigation/
    │   └── Screen.kt                    # Type-safe navigation routes
    └── theme/
        ├── Color.kt                     # NAYRO design system colors
        ├── Theme.kt                     # Light & Dark Material 3 NayroTheme
        └── Type.kt                      # Typography definitions
```
