# NAYRO Agent Architecture
## Final General Agent Brain, Persistent Background Autonomy & Security Model

## 1. High-Level Architectural Flow

```
                      USER (Main UI / Floating Overlay / Voice Wake Word)
                                        |
                                 SHARED AGENT RUNTIME (NayroApplication Container)
                                        |
                                   TASK PLANNER
                           /                          \
             Rule-Based Decomposition            LLM Structured JSON Plan
                           \                          /
                                 STRUCTURED ACTION PLAN
                                        |
                                  PLAN VALIDATOR
                                        |
                          CAPABILITY + PERMISSION CHECK
                                        |
                             RISK EVALUATION (R0 - R4)
                                        |
                         CONFIRMATION MANAGER (If R3/Sensitive)
                                        |
                                  ACTION EXECUTOR
                             /                      \
                    ACCESSIBILITY ENGINE        NATIVE ANDROID APIs
                             \                      /
                               SCREEN INTELLIGENCE
                                        |
                                 ACTION VERIFIER
                           (VERIFIED/FAILED/INCONCLUSIVE)
                                        |
                                 MEMORY & NOTEBOOK
                                        |
                                   FINAL RESPONSE
```

---

## 2. Core Execution Principles
1. **Model Proposes, Engine Validates**: The LLM proposes structured action plans in JSON schema format; `AgentRuntime` independently validates schema, permissions, capabilities, and risk policy prior to execution.
2. **Typed Action Schema**: Every action contains `actionId`, `taskId`, `actionType`, `target`, `parameters`, `requiredCapability`, `requiredPermission`, `riskLevel`, `timeoutMs`, `verificationStrategy`, `retryPolicy`, and `idempotencyClassification`.
3. **Application-Scoped Lifecycle**: `AgentRuntime` resides in `NayroApplication.instance`, making background voice triggers, floating assistant overlays, and notifications share the exact same runtime state without duplicate runtimes.
4. **Task Continuity & Boot Recovery**: Task execution states (`IN_PROGRESS`) are stored in the Room Database (`task_history`). Upon process reboot, incomplete tasks are recovered as `INTERRUPTED` and non-idempotent actions are halted to prevent duplicate execution.
5. **Independent Action Verification**: Operations are verified using screen snapshot diffs (`ScreenChangeDetector`) and foreground package checks, returning `VERIFIED`, `FAILED`, or `INCONCLUSIVE`.

---

## 3. Risk Classification Policy (R0 - R4)
- **R0 (Local Read-Only)**: Screen reading, local calculations, time checks, note creation, memory preference saving. Allowed automatically.
- **R1 (Low-Impact Reversible)**: Launching applications, opening settings sections. Checked for permission & capability.
- **R2 (Application State)**: Setting text into input fields, clicking navigation buttons. Checked for capability and active node bounds.
- **R3 (External Communication & Sensitive)**: Drafting/sending messages, publishing content, deleting data. **Requires authentic user confirmation token binding**.
- **R4 (Financial & Prohibited)**: Financial transactions, money transfers, purchases. **Unconditionally blocked from autonomous execution**.
