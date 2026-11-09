# NAYRO Capability & Device Management Registry (System D)

## 1. Overview
The Capability Management System serves as the authoritative single source of truth for all device capabilities, native tools, and assistant readiness in NAYRO.

## 2. Core Registry (`CapabilityRegistry.kt`)
| Capability ID | Display Name | Current Status in Step 02 | Prerequisite / Blocking Factor |
| :--- | :--- | :--- | :--- |
| `FLOATING_ASSISTANT` | Floating Assistant (Babu Bubble) | `AVAILABLE` or `BLOCKED_BY_SETTINGS` | Requires `SYSTEM_ALERT_WINDOW` permission |
| `BACKGROUND_AGENT` | Background Agent Engine | `AVAILABLE` or `SERVICE_NOT_RUNNING` | Managed via foreground service controls |
| `MICROPHONE_ACCESS` | Microphone Access | `AVAILABLE` or `BLOCKED_BY_PERMISSION` | Requires `RECORD_AUDIO` permission |
| `NOTIFICATION_ACCESS` | Notification Assistance | `AVAILABLE` or `BLOCKED_BY_PERMISSION` | Requires `POST_NOTIFICATIONS` on API 33+ |
| `ACCESSIBILITY` | Accessibility Service | `NOT_IMPLEMENTED` (Future Phase) | Automation action engine scheduled for Phase 3 |
| `SCREEN_CAPTURE` | MediaProjection Screen Capture | `NOT_IMPLEMENTED` (Future Phase) | Vision multimodal snapshot scheduled for Phase 4 |
| `WAKE_WORD_READY` | Local Wake-Word Engine | `NOT_IMPLEMENTED` (Future Phase) | Local DSP acoustic detection scheduled for Phase 3 |
| `VOICE_INTERACTION_READY` | Voice Interaction Pipeline | `NOT_IMPLEMENTED` (Future Phase) | SpeechRecognizer & TTS scheduled for Phase 3 |
| `DEVICE_AUTOMATION_READY`| Native Tools & Planning | `NOT_IMPLEMENTED` (Future Phase) | Autonomous tool execution scheduled for Phase 4 |

## 3. Honest Capability Evaluation
- Never marks a planned or future capability as `AVAILABLE` solely because a permission was granted.
- The `CapabilityManager` evaluates device OS, permissions, service health, and phase implementation state dynamically via Kotlin StateFlow.
