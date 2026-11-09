# NAYRO Accessibility & Automation Guide

## 1. Overview
NAYRO integrates a genuine Android `AccessibilityService` (`com.example.core.accessibility.NayroAccessibilityService`) declared in `AndroidManifest.xml` and configured via `res/xml/accessibility_service_config.xml`.

## 2. Configuration (`accessibility_service_config.xml`)
- `accessibilityEventTypes`: `typeWindowStateChanged|typeWindowContentChanged|typeViewClicked|typeViewFocused|typeViewScrolled`
- `accessibilityFlags`: `flagDefault|flagRetrieveInteractiveWindows|flagReportViewIds|flagIncludeNotImportantViews`
- `canRetrieveWindowContent`: `true`
- `canPerformGestures`: `true`

## 3. Supported Actions
1. **Semantic Clicking**: `AccessibilityActionController.performClick` targets elements by text or viewId.
2. **Text Entry**: `AccessibilityActionController.performSetText` sets `ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE` on focused editable fields.
3. **Scroll Gestures**: `performScrollForward` and `performScrollBackward`.
4. **Global Back**: `performGlobalBack` using `GLOBAL_ACTION_BACK`.
5. **App Navigation**: `AppNavigationManager.launchApp` uses Android Intent launcher resolution.

## 4. Enabling the Service
1. Open Android Settings on your device.
2. Navigate to **Accessibility**.
3. Under Downloaded Apps, locate **NAYRO**.
4. Toggle the service ON and approve the standard system dialog.
5. In NAYRO's **Capabilities** screen, the status will automatically reflect **AVAILABLE**.
