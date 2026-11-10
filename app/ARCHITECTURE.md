# NAYRO SYSTEM ARCHITECTURE DOCUMENTATION

## 1. Overview
NAYRO is a voice-first, privacy-focused, agentic Android personal AI assistant built in Kotlin and Jetpack Compose. The core architecture follows modern Android Clean Architecture principles, decoupling UI presentation from agentic planning, native Android automation, and local persistence.

---

## 2. Layered Architecture Diagram

```
+-----------------------------------------------------------------------+
|                         PRESENTATION LAYER                            |
|  HomeScreen • ConversationScreen • FloatingAssistantView • Settings    |
+-----------------------------------------------------------------------+
                                    │
                                    ▼
+-----------------------------------------------------------------------+
|                       UNIFIED INPUT GATEWAY                           |
|      Typed Text • In-App Voice (ASR) • Background Wake Word           |
+-----------------------------------------------------------------------+
                                    │
                                    ▼
+-----------------------------------------------------------------------+
|                         AGENT RUNTIME ENGINE                          |
|  AgentRuntime ──► TaskClassifier ──► TaskPlanner (Gemini API NLU)     |
|       │                                   │                           |
|       ▼                                   ▼                           |
|  PlanValidator ◄────── ActionExecutionGuard (R0-R4 Risk Gate)         |
+-----------------------------------------------------------------------+
                                    │
                                    ▼
+-----------------------------------------------------------------------+
|                       EXECUTION & VERIFICATION                        |
|  ActionExecutor ──► AppNavigationManager / NayroAccessibilityService  |
|       │                                                               |
|       ▼                                                               |
|  ActionVerifier (Pre vs Post Screen State Inspection)                 |
+-----------------------------------------------------------------------+
                                    │
                                    ▼
+-----------------------------------------------------------------------+
|                      PERSISTENCE & SECURITY                           |
|  Room Database (NayroDatabase) • KeyStore (EncryptedStorageManager)    |
+-----------------------------------------------------------------------+
```

---

## 3. Subsystem Breakdown

### 3.1 Intelligence & Planning Subsystem
- **Provider**: Google Gemini Developer API (`gemini-2.5-flash`).
- **NLU Engine**: `TaskClassifier` handles intent classification for Hindi, English, and Hinglish prompts.
- **Task Planner**: `TaskPlanner` generates structured step-by-step `AgentTask` objects containing typed `AgentAction` sequences.

### 3.2 Security & Governance Subsystem
- **Encrypted Storage**: `EncryptedStorageManager` leverages Android KeyStore master keys to encrypt API credentials.
- **Risk Classification**: Actions are classified from R0 (Local Read-Only) to R4 (Financial/High-Impact).
- **Asynchronous Confirmation**: R2 and R3 actions trigger modal user approval prompts via `ConfirmationManager`.

### 3.3 Android Native Automation Subsystem
- **App Navigation**: `AppNavigationManager` dynamically queries installed launcher packages using `PackageManager.queryIntentActivities(...)`.
- **Accessibility Automation**: `NayroAccessibilityService` inspects dynamic UI node trees (`AccessibilityNodeInfo`) to execute clicks, text insertion, and scroll gestures.

### 3.4 Voice Pipeline Subsystem
- **Wake-Word Engine**: `LocalWakeWordEngine` processes audio frames using Spectral Zero-Crossing Rate analysis for offline keyword detection.
- **ASR & TTS**: Uses native Android `SpeechRecognizer` and `TextToSpeech` APIs for continuous local voice interaction.
