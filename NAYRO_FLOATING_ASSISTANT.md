# NAYRO Floating Assistant Architecture (System B)

## 1. Overview
The Floating Assistant (`NayroFloatingService`) provides a persistent, draggable system overlay window representing Babu over any Android application. It utilizes the native Android `WindowManager` API without relying on dummy inside-app simulated views.

## 2. Technical Implementation
- **Window Type**: `WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY` on Android 8.0+ (API 26+), with backwards fallback to `TYPE_PHONE`.
- **Permission Requirement**: `Settings.canDrawOverlays(context)` checking `android.permission.SYSTEM_ALERT_WINDOW`.
  - Service automatically aborts with a graceful notification if permission is revoked or missing.
  - User can be guided directly to `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` via `package:com.aistudio.nayro.app`.
- **Draggable Dynamics & Edge Docking**:
  - Touch listener detects `ACTION_DOWN`, `ACTION_MOVE`, and `ACTION_UP`.
  - Distinguishes drags from clicks with a 10px motion threshold.
  - Automatically docks to the nearest screen edge (left or right margin) when the user releases the bubble.
- **Position Persistence**:
  - `FloatingAssistantPreferences` records the last `(x, y)` coordinate upon release and restores it on next service boot.
- **Compact Overlay Panel**:
  - Tapping the Babu bubble expands a compact quick assistant panel (`NayroFloatingPanel`).
  - Contains Babu identity, availability status, prompt text field, and quick action controls.
  - Outside touch detection (`FLAG_WATCH_OUTSIDE_TOUCH`) automatically collapses the panel.
- **Lifecycle & Duplicate Window Prevention**:
  - Tracked via `NayroFloatingService.isRunning` StateFlow.
  - Avoids duplicate window inflation by verifying `bubbleRootView != null`.
  - Cleans up and detaches views from `WindowManager` on `onDestroy()` to prevent window leaks.
