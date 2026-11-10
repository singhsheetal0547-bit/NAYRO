# NAYRO KNOWN LIMITATIONS & ANDROID OS CONSTRAINTS

## 1. Android OS System Restrictions
1. **Background Microphone Access**: On Android 12+ (API 31+), the OS restricts background microphone access unless a valid Foreground Service with `foregroundServiceType="microphone"` is actively running with a visible user notification.
2. **Accessibility Automation Bounds**: Applications displaying `FLAG_SECURE` windows (e.g., banking applications, password managers) block screen capture and Accessibility Node inspection by design. NAYRO respects these OS privacy security boundaries.
3. **Aggressive Battery Optimization**: Custom Android OEM ROMs (e.g. MIUI, OneUI, ColorOS) may terminate background services after prolonged inactivity. Users are instructed to grant `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` in NAYRO settings for maximum background uptime.

---

## 2. Honest Verification Policy
- If screen state changes cannot be unambiguously verified via `AccessibilityNodeInfo` (e.g. custom web views or non-standard canvas views), NAYRO marks verification status as `INCONCLUSIVE` and notifies the user rather than faking success.
- Financial transactions (R4 Risk) are explicitly blocked in v1 to protect user security.
