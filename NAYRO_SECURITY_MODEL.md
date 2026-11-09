# NAYRO Security & Risk Model
## Action Execution Guard, Asynchronous Confirmation Binding & Privacy Architecture

## 1. Risk Levels & Policy Rules
- **R0 (Read-Only / Local)**: Information retrieval, device time, local calculator, screen reading, memory preference saving, note creation. Permitted automatically.
- **R1 (Low-Impact / Reversible)**: App launching, opening settings sections, back navigation. Permitted with audit logging.
- **R2 (Application State)**: Entering text into editable fields, scrolling. Permitted with audit logging.
- **R3 (External Communication / Sensitive)**: Message drafting/sending, publishing content, deleting data records. **Requires explicit, authentic user confirmation showing target and parameters**.
- **R4 (Financial / Prohibited)**: Money transfers, payments, purchases, signing agreements. **Strictly blocked from autonomous execution**.

---

## 2. Centralized Security Gate (`ActionExecutionGuard`)
Every action proposal MUST pass `ActionExecutionGuard.validateExecution()` immediately prior to execution:
1. **Schema Check**: Validates required fields (e.g. non-empty target for app launch).
2. **Capability Availability**: Verifies that required capability is connected and ready.
3. **Android Permission Check**: Confirms runtime Android permissions are granted.
4. **Risk Level R4 Block**: Unconditionally blocks financial transactions.
5. **Confirmation Token Verification**: Confirms presence of an unexpired, authentic user confirmation token for R3 operations.

---

## 3. Asynchronous Confirmation Binding (`ConfirmationManager`)
1. **Asynchronous Suspension**: Suspends execution using `CompletableDeferred<ConfirmationStatus>` with a 60-second expiration timeout.
2. **Parameter-Level Binding**: Approvals are bound to `actionId` and `parameters`. Any parameter alteration between proposal and approval immediately invalidates the token.
3. **Rejection & Cancellation**: User rejection transitions the task to `CANCELLED` and safely halts all execution without dispatching actions.

---

## 4. Privacy Guard & Sensitive Information Redaction
- **Memory Privacy Guard (`MemoryPrivacyGuard`)**: Filters out passwords, OTPs, CVVs, credit card numbers, and API keys from local memory persistence.
- **Screen Node Redaction (`ScreenNodeInfo`)**: Password, PIN, and sensitive input fields are marked as redacted in screen snapshots.
- **Encrypted Local Credentials (`EncryptedStorageManager`)**: Encrypts API keys using Android Keystore hardware-backed keys.
