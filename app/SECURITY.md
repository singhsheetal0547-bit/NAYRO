# NAYRO SECURITY & GOVERNANCE MODEL

## 1. Action Risk Levels (R0 - R4)

`ActionExecutionGuard.kt` evaluates every action before execution:

- **R0 — Local Read-Only**: Local math calculation, device time retrieval, note saving, reading screen nodes. *Auto-allowed.*
- **R1 — Low Impact Reversible**: Launching installed applications, opening settings sections. *Checked for capability and permission.*
- **R2 — Application State Change**: Entering text into input fields, scrolling views. *Checked for active permissions.*
- **R3 — External Communication & Sensitive Actions**: Drafting/sending messaging, sharing data. *Requires explicit modal user confirmation.*
- **R4 — Financial & Destructive Operations**: Payments, data wiping. *Blocked in v1.*

---

## 2. Asynchronous Confirmation Workflow

```
AgentTask Execution
       │
       ▼
Action Requires Confirmation? (R2/R3)
       │
       ├────► YES ──► Pause Execution ──► Create PendingConfirmation
       │                                             │
       │                                             ▼
       │                                  Show Confirmation Dialog UI
       │                                             │
       │                        ┌────────────────────┴────────────────────┐
       │                        ▼                                         ▼
       │                   User Approves                            User Rejects
       │                        │                                         │
       │                        ▼                                         ▼
       │              Set Status CONFIRMED                      Set Status REJECTED
       │                        │                                         │
       │                        ▼                                         ▼
       │                Execute Action                          Cancel Task Safely
       │
       └────► NO  ──► Execute Action
```

---

## 3. Cryptographic Storage Architecture
All sensitive user API credentials (e.g. Gemini API Key) are managed by `EncryptedStorageManager`:
- Key derivation via `AndroidKeyStore`.
- AES-256 GCM encryption algorithm.
- No plain-text credentials stored on disk or committed to version control.
