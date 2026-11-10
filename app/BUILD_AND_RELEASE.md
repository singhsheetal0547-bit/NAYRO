# NAYRO BUILD & RELEASE MANUAL

## 1. Environment & Build Toolchain
- **Language**: Kotlin 2.0+
- **Build System**: Gradle Kotlin DSL (`build.gradle.kts`)
- **Android Gradle Plugin (AGP)**: 8.5+
- **Compile SDK**: 35
- **Min SDK**: 26 (Android 8.0 Oreo)
- **Target SDK**: 35

---

## 2. Compilation Commands

### Standard Debug Build Verification:
```bash
compile_applet
```

### Full Gradle Debug Assembly:
```bash
gradle assembleDebug
```

### Run Unit Tests:
```bash
gradle :app:testDebugUnitTest
```

---

## 3. KeyStore & Signing Configuration
The project utilizes standard Android Gradle Plugin debug signing credentials for local development. Release builds require specifying release KeyStore credentials via environment variables or secret properties. Production API keys must be entered directly in the NAYRO Settings UI or AI Studio Secrets panel.
