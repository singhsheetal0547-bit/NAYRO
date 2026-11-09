# NAYRO Permission & Security Architecture

## 1. Principles
- **No Automatic Assumptions**: Special permissions on Android (`SYSTEM_ALERT_WINDOW`, `ACCESSIBILITY`, `BATTERY_OPTIMIZATION`) cannot be auto-granted. The user must be guided transparently to Android System Settings.
- **Privacy-First Onboarding**: The user must understand *why* a permission is requested before the system intent is launched.
- **Graceful Degradation**: If a permission (such as floating overlay or accessibility) is denied, NAYRO must remain operational via standard manual app interaction without crashing.

## 2. Permission Registry Table
| Permission Type | Category | System Permission / Intent | Purpose in NAYRO |
| :--- | :--- | :--- | :--- |
| **Microphone** | Standard Runtime | `android.permission.RECORD_AUDIO` | Local wake-word detection & voice commands |
| **Notifications** | Standard Runtime | `android.permission.POST_NOTIFICATIONS` | Agent status alerts, task execution feedback |
| **Foreground Service** | Manifest Standard | `android.permission.FOREGROUND_SERVICE_MICROPHONE` | Keeping standby wake engine alive |
| **Overlay** | Special Setting | `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` | Floating Assistant Babu icon overlay |
| **Accessibility** | Special Setting | `Settings.ACTION_ACCESSIBILITY_SETTINGS` | User-authorized app interaction & screen text reading |
| **Battery Optimization** | Special Setting | `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Preventing OEM background process termination |
| **Screen Capture** | User Consent | MediaProjection API | Multimodal screen snapshot analysis |

## 3. Cryptographic Security & Keystore
- Hardware-backed key generation inside Android KeyStore (`NayroMasterSecretKey`).
- Standard AES-256 GCM encryption mode for saving provider API keys.
- Secret tokens are never stored as plain text on disk or logged in system logcat.
