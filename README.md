# NAYRO

**A voice-first Android personal AI assistant with native device interaction, task planning, and user-controlled automation.**

NAYRO is an Android project built with Kotlin and Jetpack Compose. It combines an AI planning layer with Android-native voice interfaces, accessibility-based UI interaction, a floating assistant, and local persistence. Its goal is to help users express tasks naturally and let the assistant plan, execute permitted actions, inspect the resulting device state, and report what it can verify.

> **Project status:** Active development. The repository contains the Android application and automated tests, but feature availability can vary by Android version, device manufacturer, permissions, and connected AI configuration. Do not treat every listed capability as guaranteed on every device.

---

## At a glance

| | |
|---|---|
| **Platform** | Android |
| **Language** | Kotlin |
| **UI** | Jetpack Compose, Material 3 |
| **Minimum Android version** | Android 10 (API 29) |
| **Compile / target SDK** | 36 / 36 |
| **Application ID** | `com.aistudio.nayro.app` |
| **AI integration** | Google Gemini through the configured Firebase AI integration |
| **Local data** | Room database |
| **Build system** | Gradle Kotlin DSL |

## What NAYRO is designed to do

- **Natural-language task planning** — interpret typed or spoken instructions and prepare structured action plans.
- **Voice interaction** — use Android SpeechRecognizer for speech input and Android TextToSpeech for spoken responses.
- **Wake-phrase pipeline** — provide on-device audio-frame processing for configured assistant wake phrases. Detection reliability depends on the implementation and device environment.
- **Floating assistant** — display a movable overlay when the user grants the required special permission.
- **Android app navigation** — resolve and launch installed applications through Android intents.
- **Accessibility interaction** — inspect supported accessibility UI nodes and perform permitted actions such as clicking, entering text, scrolling, and navigating back.
- **Task execution safeguards** — validate proposed actions and apply risk-based confirmation rules before sensitive operations.
- **Local notes and memory** — persist supported user notes, memories, and task history using Room.
- **Task-state verification** — distinguish verified, failed, and inconclusive outcomes instead of assuming that an attempted action succeeded.

## Architecture

NAYRO separates the user interface, agent reasoning, Android execution, and local storage.

```text
Typed input / Voice / Floating assistant
                  |
                  v
            Agent Runtime
                  |
        Task classification
                  |
          Task planning
          (Gemini + rules)
                  |
                  v
          Plan validation
                  |
       Permission + risk checks
                  |
       User confirmation, if needed
                  |
                  v
           Action executor
             /        \
            v          v
     Android APIs   Accessibility
             \        /
              v      v
          State verification
                  |
           Room persistence
                  |
           User-facing result
```

### Main components

| Component | Responsibility |
|---|---|
| `AgentRuntime` / `AgentBrain` | Coordinate agent tasks and reasoning |
| `TaskClassifier` / `TaskPlanner` | Interpret requests and produce structured plans |
| `PlanValidator` | Validate proposed task plans |
| `ActionExecutor` | Execute supported Android actions |
| `ActionExecutionGuard` | Apply action risk and permission checks |
| `ConfirmationManager` | Handle explicit approval for sensitive actions |
| `ActionVerifier` | Assess whether an action's result can be verified |
| `NayroAccessibilityService` | Inspect and interact with supported app UI elements |
| `NayroForegroundService` | Host foreground agent functionality where Android permits |
| `NayroFloatingService` | Manage the floating assistant overlay |
| `NayroDatabase` | Store local notes, memories, and task records |

## Security and user control

NAYRO uses a risk-based action model:

- **R0 — Local read-only:** low-impact local operations.
- **R1 — Low-impact navigation:** actions such as opening supported apps or settings.
- **R2 — App state changes:** actions that interact with an application's UI.
- **R3 — Sensitive or external communication:** requires explicit user confirmation.
- **R4 — Financial or prohibited operations:** blocked by the current v1 policy.

The model proposes actions; the application is responsible for validating them before execution. API credentials should be supplied through the supported secrets/configuration flow and must never be committed to the repository.

## Privacy notes

- Wake-phrase audio processing is intended to run locally on the device.
- Speech recognition behavior depends on the Android speech-recognition service configured on the device.
- Local notes and memory records are stored in the app's Room database.
- API credentials are handled through the app's encrypted-storage implementation.
- Accessibility access can expose on-screen content to the service. Enable it only if you understand and accept this capability.
- NAYRO cannot inspect or automate every screen. Android and individual apps may block access to protected or secure content.

See [Permissions](app/PERMISSIONS.md), [Security](app/SECURITY.md), and [Known Limitations](app/KNOWN_LIMITATIONS.md) for more detail.

## Requirements

- Android Studio with support for the project's Android Gradle Plugin.
- JDK 17 or the JDK version required by the installed Android Gradle Plugin.
- Android SDK Platform 36 and the corresponding build tools.
- Android device or emulator running Android 10 (API 29) or newer.
- A valid Gemini configuration for AI-backed features.

## Getting started

### 1. Clone the repository

```bash
git clone https://github.com/singhsheetal0547-bit/NAYRO.git
cd NAYRO
```

### 2. Configure AI access

The repository includes an `.env.example` placeholder. Configure your Gemini API key through the supported Android Studio / AI Studio Secrets configuration used by your environment.

**Never commit a real API key, signing password, keystore, or other secret.** The placeholder in `.env.example` is not a working API key.

### 3. Build the debug APK

On macOS or Linux:

```bash
./gradlew :app:assembleDebug
```

On Windows:

```bat
gradlew.bat :app:assembleDebug
```

The debug APK is normally generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

### 4. Run unit tests

macOS / Linux:

```bash
./gradlew :app:testDebugUnitTest
```

Windows:

```bat
gradlew.bat :app:testDebugUnitTest
```

To install a debug build on a connected device with ADB:

```bash
./gradlew :app:installDebug
```

## Android permissions and setup

Some NAYRO capabilities require additional user-granted access. Depending on the feature and Android version, setup may include:

- Microphone permission for voice input and audio processing.
- Notification permission for visible foreground-service notifications.
- Display-over-other-apps permission for the floating assistant.
- Accessibility Service enablement for supported screen inspection and UI actions.
- Battery-optimization settings for improved background reliability on some devices.

Grant only the permissions needed for the features you choose to use. Android version rules and manufacturer battery policies may limit background operation even after setup.

## Testing

The project includes JVM/Robolectric tests under `app/src/test` and instrumentation-test sources under `app/src/androidTest`.

Run the local unit-test suite with:

```bash
./gradlew :app:testDebugUnitTest
```

A successful unit-test run does **not** replace testing on physical devices. Before relying on voice activation, background behavior, Accessibility actions, or messaging workflows, test them on the Android versions and device manufacturers you intend to support.

## Documentation

- [Architecture](app/ARCHITECTURE.md)
- [Build and release notes](app/BUILD_AND_RELEASE.md)
- [Capability matrix](app/CAPABILITY_MATRIX.md)
- [Permissions and privacy](app/PERMISSIONS.md)
- [Security model](app/SECURITY.md)
- [Testing guide](app/TESTING.md)
- [Known limitations](app/KNOWN_LIMITATIONS.md)
- [Agent architecture](NAYRO_AGENT_ARCHITECTURE.md)
- [Background service notes](NAYRO_BACKGROUND_SERVICE.md)
- [Accessibility guide](NAYRO_ACCESSIBILITY_GUIDE.md)

## Contributing

Contributions, bug reports, and focused improvement proposals are welcome. When reporting an issue, include:

1. Android version and device manufacturer/model.
2. The NAYRO build or commit you tested.
3. Steps to reproduce the issue.
4. Relevant logs, with API keys, phone numbers, message contents, and other private data removed.
5. Whether the required Android permissions and services were enabled.

Please avoid including personal or sensitive device data in public issues.

## License

No license file is currently declared in this repository. Unless and until a license is added, do not assume that the source code is available for unrestricted reuse, modification, or redistribution.

---

**Repository:** [singhsheetal0547-bit/NAYRO](https://github.com/singhsheetal0547-bit/NAYRO)
