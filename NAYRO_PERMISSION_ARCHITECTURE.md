# NAYRO Permission & Security Architecture (Version 2.0 - Step 02)

## 1. Principles & Threat Modeling
- **Zero-Bypass Policy**: NAYRO respects all Android security boundaries. Runtime permissions (`RECORD_AUDIO`, `POST_NOTIFICATIONS`) and special AppOps (`SYSTEM_ALERT_WINDOW`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `ACCESSIBILITY`) are strictly requested or guided through official Android mechanisms.
- **Microphone Standby Privacy**:
  - Standby microphone audio is **never** streamed to cloud providers.
  - No continuous background recording is performed during idle state.
  - Future local wake-word analysis will be strictly on-device.
- **Hardware-Backed Encryption**: All API keys and secrets configured by the user are encrypted via AES-256 GCM in Android KeyStore.

## 2. Centralized Permission Registry (`PermissionRegistry.kt`)
The registry provides a single source of truth for all permissions:
- `MICROPHONE` (Runtime: `RECORD_AUDIO`): Voice commands & wake word.
- `NOTIFICATIONS` (Runtime: `POST_NOTIFICATIONS`): System status & foreground notification.
- `OVERLAY` (Special AppOp: `SYSTEM_ALERT_WINDOW`): Floating assistant bubble over applications.
- `ACCESSIBILITY` (Accessibility Service): Manual toggle in Settings for screen navigation context.
- `BATTERY_OPTIMIZATION` (Special Setting): Exemption to avoid OEM background execution termination.
- `FOREGROUND_SERVICE` & `FOREGROUND_SERVICE_SPECIAL_USE`: Android 14+ compliant background lifecycle.
- `SCREEN_CAPTURE` (MediaProjection): Optional user consent on-demand.

## 3. Status Categories
1. `GRANTED`: Active and authorized.
2. `DENIED`: Runtime permission denied by user (request dialog available).
3. `PERMANENTLY_DENIED`: User checked "Don't ask again" (directs to App Details Settings).
4. `SPECIAL_ACCESS_REQUIRED`: Requires deep-linking to dedicated Android Settings.
5. `NOT_REQUIRED`: Inactive or unnecessary on the device's API level.
6. `UNAVAILABLE`: Hardware or OS unsupported.

## 4. Revalidation & Lifecycle
- `PermissionManager.refreshAllPermissions()` is re-evaluated inside `MainActivity.onResume()`.
- Directly updates `evaluations` StateFlow, driving dynamic capability evaluation and UI state.
