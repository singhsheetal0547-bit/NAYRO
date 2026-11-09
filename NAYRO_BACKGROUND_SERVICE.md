# NAYRO Background Agent Service & Hardening Architecture

## 1. Overview
The NAYRO Background Agent Service (`NayroForegroundService`) and System Overlay (`NayroFloatingService`) manage the persistent background lifecycle of NAYRO, enabling voice wake-word detection, direct floating assistant interaction, and agent task continuity without violating Android platform execution policies or compromising user privacy.

---

## 2. Technical Architecture & Android Compliance
- **Foreground Service Types**:
  - `NayroForegroundService`: Declared with `android:foregroundServiceType="microphone"`, compliant with Android 14+ (API 34+) and Android 15+ strict FGS requirements for local audio wake phrase listening.
  - `NayroFloatingService`: Declared with `android:foregroundServiceType="specialUse"` (`PROPERTY_SPECIAL_USE_FGS_SUBTYPE` = "Babu Floating Assistant Overlay Window Management").
- **Android 12+, 13+, 14+, 15+ Background Compatibility**:
  - Services handle `POST_NOTIFICATIONS` (Android 13+), `SYSTEM_ALERT_WINDOW`, `RECORD_AUDIO`, and `FOREGROUND_SERVICE_MICROPHONE`.
  - Service starts use try-catch guards handling `ForegroundServiceStartNotAllowedException` and `SecurityException`.
- **Persistent Notifications & Controls**:
  - `nayro_agent_channel` (Importance: Low) with tap-to-open `MainActivity` and "Stop Agent" PendingIntent.
  - `nayro_floating_channel` for the floating assistant overlay.
- **Local Voice Pipeline Connection**:
  - Standby microphone captures audio energy locally on-device via `LocalWakeWordEngine`. Zero cloud streaming during standby.
  - When speech is recognized, `VoiceManager` dispatches instructions directly to `AgentRuntime`.
  - Speech synthesis output is spoken via `TextToSpeech` when TTS is enabled.

---

## 3. Persistent Floating Assistant (`NayroFloatingService`)
- WindowManager `TYPE_APPLICATION_OVERLAY` draggable bubble view with exact NAYRO palette tokens (#153B35, #0E2925, #D56A50, #F6F2E8).
- Edge snapping: Dragging snaps the bubble smoothly to the nearest screen edge.
- Direct Agent Runtime Dispatch: Tapping the bubble opens the quick panel. Submitting a prompt sends the instruction directly to `AgentRuntime` and `NayroRepository` in the background—user messages are never discarded even if `MainActivity` is closed.

---

## 4. Real Runtime Health (`BackgroundAgentHealthManager`)
- Evaluates live Android OS system APIs rather than hardcoding success states:
  - `ServiceStateRepository` (Foreground & Floating lifecycle states)
  - `PermissionManager` (`RECORD_AUDIO`, `POST_NOTIFICATIONS`)
  - `Settings.canDrawOverlays()`
  - `NayroAccessibilityService.isConnected()`
  - `PowerManager.isIgnoringBatteryOptimizations()`
  - `ActivityManager.isBackgroundRestricted`
  - `LocalWakeWordEngine.engineState` (`LISTENING_FOR_WAKE`, `MICROPHONE_BUSY`, etc.)

---

## 5. Task Continuity & Boot Recovery
- Tasks starting execution record an `IN_PROGRESS` state in `TaskHistoryEntity` (`task_history` table in Room Database).
- On application boot (`NayroApplication.onCreate()`), `agentRuntime.recoverInterruptedTasksOnBoot()` queries incomplete tasks and marks them as `INTERRUPTED`.
- Non-idempotent actions (e.g. messaging, clicking elements) are halted to prevent duplicate execution upon process recreation.
- Expired action authorization tokens are safely rejected by `ConfirmationManager`.

---

## 6. Real Android Platform Limitations
1. **Force Stop**: If the user performs a manual "Force Stop" in Android Settings, all services and alarm triggers are stopped by the OS until the user manually re-launches the application.
2. **OEM Aggressive Battery Optimization**: Devices running heavy vendor skins (Xiaomi MIUI/HyperOS, Samsung One UI, Oppo ColorOS) may kill background processes if "Unrestricted" battery usage is not granted by the user.
3. **Microphone Exclusivity**: Only one application can access `AudioRecord` at a time. When another app (e.g. Phone, Camera) uses the microphone, the local wake-word engine transitions cleanly to `MICROPHONE_BUSY`.
