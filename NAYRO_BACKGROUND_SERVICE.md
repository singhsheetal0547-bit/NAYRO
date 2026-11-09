# NAYRO Background Agent Service (System C)

## 1. Overview
The Background Agent Service (`NayroForegroundService`) manages the persistent runtime lifecycle of NAYRO, ensuring that the assistant remains responsive without violating Android background execution policies or compromising user audio privacy.

## 2. Technical Architecture & Android Compliance
- **Foreground Service Type**:
  - Manifest declaration: `android:foregroundServiceType="specialUse"` with property `android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE` documenting agent lifecycle standby.
  - Fully compliant with Android 14+ (API 34+) and Android 15+ strict foreground service enforcement.
- **Android 12+ Background Start Restrictions**:
  - Services are started in response to direct user clicks or active UI lifecycle events, wrapping starts in exception guards to prevent `ForegroundServiceStartNotAllowedException`.
- **Notification Channel & Controls**:
  - Dedicated low-priority notification channel: `nayro_agent_channel` ("NAYRO Agent Engine").
  - Persistent ongoing notification with interactive actions:
    1. Direct tap navigates to `MainActivity`.
    2. "Stop Agent" action sends `ACTION_STOP` PendingIntent directly stopping the foreground service.
- **Microphone Standby Governance**:
  - Standby state **does not** continuously capture or stream microphone audio.
  - Zero cloud audio streaming while idle.
- **State Flow & Observation**:
  - Monitored via `NayroForegroundService.isRunning: StateFlow<Boolean>`.
  - Exposes reactive updates to `CapabilityManager`, `HomeScreen`, and `BackgroundControlScreen`.
