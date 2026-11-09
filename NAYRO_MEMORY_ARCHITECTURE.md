# NAYRO Personal Memory & Notebook Architecture

## 1. Overview
NAYRO persists user context, preferences, and personal notes locally in a Room SQLite database (`nayro_local_db`, schema v2). No mandatory cloud sync or external servers are required.

## 2. Entities
1. **`local_memory` (`MemoryEntity`)**:
   - Stores user preferences ("User Preferences", "Important Facts", "Frequently Used Instructions").
   - Populated naturally via phrases like: "Yaad rakho ki mujhe Hindi mein jawab pasand hain".
2. **`personal_notes` (`NoteEntity`)**:
   - Stores personal notes with title, content, category, and pinned status.
   - Managed via the personal notebook interface.
3. **`task_history` (`TaskHistoryEntity`)**:
   - Stores executed agent tasks, instructions, resolved intents, status (`COMPLETED`/`FAILED`/`CANCELLED`), step counts, result summaries, and duration.

## 3. Privacy Guard
`MemoryPrivacyGuard` strictly evaluates all candidate memory and note text before storage:
- Rejects credit card numbers.
- Rejects passwords, passcodes, and PINs.
- Rejects OTPs and 2FA verification codes.
- Rejects API keys (e.g. Google `AIzaSy...`, OpenAI `sk-...`).
