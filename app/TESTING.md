# NAYRO TESTING & VERIFICATION GUIDE

## 1. Automated Unit & Integration Testing

NAYRO includes a complete local JVM Robolectric test suite located under `app/src/test/java/com/example/`.

### Test Execution Command:
```bash
gradle :app:testDebugUnitTest
```

### Verified Test Suite Capabilities:
1. **Task Planning & Classification**: Validates regex and structured JSON plan generation across Hindi, English, and Hinglish prompts.
2. **Action Security Guard**: Validates that R0-R4 risk levels correctly gate execution and enforce user confirmation.
3. **App Navigation Resolution**: Validates dictionary matching and fallback launcher activity resolution.
4. **Local Calculator Engine**: Evaluates arithmetic expressions, operator precedence, and error states.
5. **Database DAOs & Repositories**: Validates CRUD operations for notes, memories, task history, and audit logs.

---

## 2. Real Device Verification Checklist

- [x] **Chat Execution**: Type prompt -> Gemini API NLU -> Action Plan -> Native Execution -> Visual Verification.
- [x] **Voice Pipeline**: Tap mic -> SpeechRecognizer STT -> Task execution -> TextToSpeech spoken response.
- [x] **Background Wake Word**: Frame audio -> Spectral ZCR keyword match -> Activate Babu assistant.
- [x] **Floating Overlay**: Enable overlay -> Display movable bubble over third-party apps -> Send instructions.
- [x] **WhatsApp Draft & Send**: Open WhatsApp -> Resolve contact -> Fill text -> Enforce R3 confirmation -> Verify UI change.
- [x] **Task Recovery**: Restart app during interrupted execution -> Verify `recoverInterruptedTasksOnBoot()` marks tasks `INTERRUPTED`.
