# NAYRO Security & Risk Model

## 1. Risk Levels
- **R0 (Read-Only / Local)**: Information retrieval, device clock, calculator, screen reading. Permitted without confirmation.
- **R1 (Low-Impact / Reversible)**: App launching, back navigation. Permitted with audit logging.
- **R2 (Application State)**: Typing into fields, scroll gestures, local preference changes. Permitted with audit logging.
- **R3 (External Communication / Destructive)**: Sending messages, placing calls, sharing data, deleting records. Requires explicit, interactive user confirmation showing exact target and parameters.
- **R4 (Financial / Legally Significant)**: Payments, funds transfer, purchases, agreement signing. Autonomous execution is strictly prohibited in v1.

## 2. Confirmation Binding
Confirmations are bound to:
1. `taskId`
2. `actionId`
3. Exact target (e.g. contact name / package)
4. Parameter values (e.g. draft message text)

If action parameters change, the previous confirmation is invalidated.
