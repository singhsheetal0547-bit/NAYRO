# NAYRO Step 02 Remediation Report

## 1. Executive Summary
This document reports the critical remediation of issues identified in the STEP 02 foundation prior to deploying STEP 03 intelligence systems. All 5 critical areas have been refactored, tested, and verified against native Android runtime requirements.

---

## 2. Remediated Systems & Fixes

### 1.1 Floating Assistant Input & Message Pipeline Transfer
- **Issue**: Tapping "Send" in the floating overlay panel dismissed the panel and launched `MainActivity` without passing the entered text into the conversation system. Empty prompts were not disabled.
- **Remediation**:
  - Implemented `TextWatcher` inside `NayroFloatingService`: "Send" button is dynamically disabled (`#6F7C76`) until the user enters non-empty text, upon which it becomes active Forest Green (`#153B35`).
  - Upon clicking "Send", the text is extracted and bundled into `Intent` with key `NayroFloatingService.EXTRA_FLOATING_PROMPT` using flags `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_SINGLE_TOP or FLAG_ACTIVITY_CLEAR_TOP`.
  - In `MainActivity`, both `onCreate` and `onNewIntent` capture `EXTRA_FLOATING_PROMPT`.
  - `LaunchedEffect` detects the pending prompt, navigates automatically to the `Screen.Chat` view, saves the user message to Room DB, and dispatches the prompt through the unified `AIProviderManager` and TTS pipeline.

### 1.2 Background Service Lifecycle & Authentic State Tracking
- **Issue**: Services were marked as `RUNNING` optimistically before the Android framework confirmed start.
- **Remediation**:
  - Created genuine `ServiceLifecycleState` enum: `STOPPED`, `STARTING`, `RUNNING`, `STOPPING`, `FAILED`, `PERMISSION_DENIED`.
  - Created centralized `ServiceStateRepository` with Kotlin `StateFlow`.
  - `NayroForegroundService` updates state to `STARTING` during intent launch, to `RUNNING` only after `startForeground()` completes without exception, to `STOPPING` upon stop command, and `STOPPED` in `onDestroy()`.
  - Handled `SecurityException`, `ForegroundServiceStartNotAllowedException`, and `IllegalStateException` with state transitions to `FAILED` and recorded error messages.

### 1.3 Floating Window Failure Handling & WindowManager Leak Prevention
- **Issue**: Overlay errors could result in invisible or failed windows while still reporting `RUNNING`.
- **Remediation**:
  - `NayroFloatingService.initBubbleView()` verifies `Settings.canDrawOverlays(this)` before invoking `windowManager.addView()`.
  - Added try-catch around `windowManager.addView(bubbleRootView, bubbleParams)`: if attachment fails, state is set to `FAILED` and service calls `stopSelf()`.
  - Checked `bubbleRootView != null` to prevent duplicate window inflation.
  - In `onDestroy()`, both `panelRootView` and `bubbleRootView` are safely removed from `WindowManager` to prevent WindowManager leaks.

### 1.4 Permission Manager Audit & Corrections
- **A. Foreground Service Reporting**:
  - Instead of automatically returning `GRANTED`, `PermissionManager.isManifestPermissionDeclared()` inspects `packageInfo.requestedPermissions` for `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_SPECIAL_USE` (on API 34+).
- **B. Accessibility Service Component Detection**:
  - Eliminated broad package substring matching.
  - Implemented `isExactAccessibilityServiceEnabled()` utilizing `TextUtils.SimpleStringSplitter` and `ComponentName.unflattenFromString()`, matching the exact component name.
  - Implemented `isAccessibilityServiceDeclared()` checking for services requiring `android.permission.BIND_ACCESSIBILITY_SERVICE`.
  - Clarified that Accessibility automation is a Phase 4 feature; when undeclared, status reports `UNAVAILABLE` rather than misleading the user.

### 1.5 Capability Manager Dynamic Derivation
- **Issue**: Capability availability relied on manual boolean flags.
- **Remediation**:
  - `CapabilityManager` derives capability availability reactively from `ServiceStateRepository.foregroundServiceStatus`, `ServiceStateRepository.floatingServiceStatus`, `PermissionManager.evaluations`, and `SpeechRecognizer.isRecognitionAvailable()`.
  - Capabilities change reactively via `StateFlow` without manual toggling.
