# NAYRO Android Foundation & Technical Architecture

## 1. System Layers
```
┌─────────────────────────────────────────────────────────────┐
│                    PRESENTATION LAYER                        │
│   HomeScreen | ConversationScreen | CapabilitiesScreen      │
│   ActivityScreen | MemoryScreen   | SettingsScreen          │
└──────────────────────────────┬──────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────┐
│                    AGENT RUNTIME ENGINE                      │
│   AgentState | RiskEvaluator | ActionProposal | Execution   │
└──────────────────────────────┬──────────────────────────────┘
                               │
┌──────────────────────────────┼──────────────────────────────┐
│               │              │               │              │
┌───────────────▼──┐   ┌───────▼───────┐   ┌───▼──────────────┐
│  VOICE ENGINE    │   │  AI PROVIDER  │   │ PERMISSION MGR   │
│ Wake-Word / ASR  │   │ Manager/Router│   │ Security Intent  │
└──────────────────┘   └───────────────┘   └──────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────┐
│                     DATA & SECURITY LAYER                    │
│   Room DB (SQLite) | KeystoreManager | EncryptedStorage     │
└─────────────────────────────────────────────────────────────┘
```

## 2. Key Foundation Components
- **`NayroForegroundService`**: Persistent Android Foreground Service declared with `android:foregroundServiceType="microphone"`, providing lifecycle management for background agent standby.
- **`KeystoreManager`**: Provides hardware-backed key generation (`AES/GCM/NoPadding`) via Android KeyStore for encrypting provider keys.
- **`PermissionManager`**: Tracks standard runtime permissions and system settings intents (`SYSTEM_ALERT_WINDOW`, `ACCESSIBILITY`, `BATTERY_OPTIMIZATION`).
- **`NayroDatabase`**: Room Database schema containing tables for `AppSettings`, `AssistantProfile`, `Conversations`, `LocalMemory`, and `AuditLogs`.
- **`NayroTheme`**: Centralized M3 Design System adopting NAYRO's color tokens (Forest Green `#153B35`, Deep Green `#0E2925`, Warm Ivory `#F6F2E8`, Coral `#D56A50`, Soft Sage `#DCE7DE`).
