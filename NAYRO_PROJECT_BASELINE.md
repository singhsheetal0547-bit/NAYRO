# NAYRO Project Baseline Specification

## 1. Executive Overview
NAYRO is a private, personal, voice-first Android AI Agent designed to run as a reliable native system assistant. Its primary assistant persona is **Babu**, supporting natural voice and text interactions in **Hindi, English, and Hinglish**.

## 2. Core Product Identity & Vision
- **Application Name**: NAYRO
- **Default Assistant Persona**: Babu
- **Default Wake Phrases**: `Hi Babu`, `Hey Babu`, `Hi Nayro`, `Hey Nayro`, `Hey Cutie`
- **Languages Supported**: Hindi, English, Hinglish
- **Architecture**: Local-First, Privacy-First, Clean Architecture on Native Android (Kotlin + Jetpack Compose)

## 3. Key Non-Negotiable Engineering Principles
1. **Local-First & Standby Audio Privacy**: Wake-word processing occurs on-device. Audio is never continuously streamed to cloud providers.
2. **Modular AI Provider Routing**: Native support for Google Gemini API, OpenAI API, Grok API, OpenRouter, DeepSeek, Custom OpenAI-compatible endpoints, and Local Ollama/LMStudio models.
3. **Android Keystore Security**: All API keys and secret tokens are encrypted with AES-256 GCM using Android's hardware-backed KeyStore.
4. **Transparent Risk & Confirmation Pipeline**: Model proposals are validated by an explicit risk evaluator (R0 through R4) before executing any native Android action.

## 4. Platform Baseline Stack
- **OS Target**: Android (minSdk 24, targetSdk 36)
- **Language**: Kotlin 2.2+ with Coroutines & Flow
- **UI Framework**: Jetpack Compose with Material Design 3
- **Local Persistence**: Room Database (SQLite) + Encrypted SharedPreferences via KeyStore
- **Build System**: Gradle Kotlin DSL with Version Catalog (`libs.versions.toml`)
