# NAYRO Agent Architecture

## 1. High-Level Architectural Flow

```
                      USER
                        |
                 VOICE / TEXT
                        |
                   NAYRO BRAIN
                        |
                 INTENT RESOLVER
                        |
                  TASK PLANNER
                        |
                   AGENT RUNTIME
                        |
                  PLAN VALIDATOR
                        |
              CAPABILITY + PERMISSION
                        |
                   RISK EVALUATOR
                        |
               CONFIRMATION MANAGER
                        |
                 ACTION EXECUTOR
                  /          \
                 /            \
         ACCESSIBILITY      NATIVE APIs
                 \            /
                  \          /
                 SCREEN INTELLIGENCE
                        |
                 ACTION VERIFIER
                        |
                 MEMORY MANAGER
                        |
                  FINAL RESPONSE
```

## 2. Core Execution Principles
1. **Model Never Executes Directly**: The LLM proposes plans; `AgentRuntime` strictly parses, validates, and dispatches actions through native Android APIs and Android Accessibility.
2. **Deterministic State Machine**: Every task undergoes validated state transitions:
   `CREATED` -> `UNDERSTANDING` -> `PLANNING` -> `VALIDATING` -> (`WAITING_FOR_PERMISSION` | `WAITING_FOR_CONFIRMATION` | `READY`) -> `EXECUTING` -> `VERIFYING` -> `COMPLETED` / `FAILED`.
3. **Multi-Step Coordination**: Complex tasks (e.g. YouTube search, Settings navigation, WhatsApp draft) are planned as sequential actions with pre- and post-condition checks.
4. **Independent Verification**: Actions are verified by observing foreground package changes, UI node tree modifications, or database records.
