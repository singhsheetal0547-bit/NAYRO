package com.example.core.agent

enum class UserIntentType {
    APP_LAUNCH,
    SCREEN_READ,
    YOUTUBE_SEARCH,
    SETTINGS_NAVIGATE,
    MESSAGE_DRAFT,
    PHONE_CALL,
    FLASHLIGHT_CONTROL,
    NOTIFICATION_READ,
    FILE_FIND,
    REMINDER_CREATE,
    NOTE_CREATE,
    MEMORY_SAVE,
    LOCAL_TIME,
    LOCAL_CALCULATOR,
    GENERAL_AI_QUERY
}

data class ResolvedIntent(
    val type: UserIntentType,
    val targetAppOrEntity: String? = null,
    val searchQueryOrContent: String? = null,
    val recipient: String? = null,
    val section: String? = null,
    val rawPrompt: String
)

object TaskClassifier {

    fun classify(prompt: String): ResolvedIntent {
        val stripped = prompt.trim()
            .replaceFirst(Regex("^(?i)(hey babu|hi babu|babu|hey nayro|hi nayro|nayro|hey cutie)\\s*,?\\s*"), "")
            .trim()
        val trimmed = if (stripped.isNotBlank()) stripped else prompt.trim()
        val lower = trimmed.lowercase()

        // 1. Memory Save Intent
        if (lower.startsWith("yaad rakho") || lower.startsWith("remember that") || lower.contains("yaad rakhna")) {
            return ResolvedIntent(
                type = UserIntentType.MEMORY_SAVE,
                searchQueryOrContent = trimmed,
                rawPrompt = prompt
            )
        }

        // 2. Note Create Intent
        if (lower.startsWith("note banao") || lower.startsWith("note likho") || lower.startsWith("create note") || lower.startsWith("take a note")) {
            val content = trimmed.replaceFirst(Regex("^(?i)(note banao:|note banao|note likho:|note likho|create note:|create note|take a note:)\\s*"), "")
            return ResolvedIntent(
                type = UserIntentType.NOTE_CREATE,
                searchQueryOrContent = content.ifBlank { "Untitled Note" },
                rawPrompt = prompt
            )
        }

        // 2b. Reminder Create Intent (e.g. "create a reminder for tomorrow", "remind me to call mom", "reminder lagao")
        if (lower.contains("reminder") || lower.startsWith("remind me") || lower.contains("yaad dilana")) {
            val reminderText = trimmed
                .replaceFirst(Regex("^(?i)(create a reminder|create reminder|set a reminder|set reminder|remind me to|remind me|reminder lagao)\\s*(for|to|:)?\\s*"), "")
                .trim()
                .removeSuffix(".")
            return ResolvedIntent(
                type = UserIntentType.REMINDER_CREATE,
                searchQueryOrContent = reminderText.ifBlank { trimmed },
                rawPrompt = prompt
            )
        }

        // 2c. Flashlight / Torch Control Intent (e.g. "turn on the flashlight", "turn off flashlight", "torch chalu karo")
        if (lower.contains("flashlight") || lower.contains("torch")) {
            val turnOff = lower.contains("off") || lower.contains("band") || lower.contains("disable")
            return ResolvedIntent(
                type = UserIntentType.FLASHLIGHT_CONTROL,
                searchQueryOrContent = if (turnOff) "false" else "true",
                rawPrompt = prompt
            )
        }

        // 2d. Notification Read / Summarize Intent (e.g. "read my notifications", "summarize my notifications")
        if (lower.contains("notification") && (lower.contains("read") || lower.contains("check") || lower.contains("summarize") || lower.contains("padho") || lower.contains("dikhao") || lower.contains("batao"))) {
            val mode = if (lower.contains("summarize") || lower.contains("summary")) "summarize" else "read"
            return ResolvedIntent(
                type = UserIntentType.NOTIFICATION_READ,
                searchQueryOrContent = mode,
                rawPrompt = prompt
            )
        }

        // 2e. File / PDF Search Intent (e.g. "find my downloaded PDF", "search file report.pdf", "pdf dhundo")
        if ((lower.contains("find") || lower.contains("search") || lower.contains("dhundo") || lower.contains("locate")) &&
            (lower.contains("pdf") || lower.contains("file") || lower.contains("document") || lower.contains("download"))
        ) {
            val query = when {
                lower.contains("pdf") -> ".pdf"
                else -> trimmed
                    .replaceFirst(Regex("^(?i)(find|search|locate)\\s+(my\\s+)?(downloaded\\s+)?(file|document)s?\\s*"), "")
                    .trim()
                    .removeSuffix(".")
                    .ifBlank { "txt" }
            }
            return ResolvedIntent(
                type = UserIntentType.FILE_FIND,
                searchQueryOrContent = query,
                rawPrompt = prompt
            )
        }

        // 3. Screen Read Intent
        if (lower.contains("screen par kya") || lower.contains("screen padho") || lower.contains("what is on screen") || lower.contains("what's on screen") || lower.contains("read screen")) {
            return ResolvedIntent(
                type = UserIntentType.SCREEN_READ,
                rawPrompt = prompt
            )
        }

        // 4. Local Time Intent
        if (lower.contains("time kya") || lower.contains("what time") || lower.contains("current time") || lower.contains("samay kya")) {
            return ResolvedIntent(
                type = UserIntentType.LOCAL_TIME,
                rawPrompt = prompt
            )
        }

        // 4b. Local Calculator Intent
        val isExplicitCalc = lower.startsWith("calculate") || lower.startsWith("hisab karo") || lower.startsWith("solve") || lower.startsWith("math:")
        val isPureMathExpr = trimmed.matches(Regex("^[\\s\\(\\)\\d\\+\\-\\*/\\.\\^xX÷×]+$")) &&
                (trimmed.contains("+") || trimmed.contains("-") || trimmed.contains("*") || trimmed.contains("/") || trimmed.contains("×") || trimmed.contains("÷"))

        if (isExplicitCalc || isPureMathExpr) {
            val expr = trimmed.replaceFirst(Regex("^(?i)(calculate:|calculate|hisab karo:|hisab karo|solve:|solve|math:)\\s*"), "").trim()
            if (expr.isNotEmpty()) {
                return ResolvedIntent(
                    type = UserIntentType.LOCAL_CALCULATOR,
                    searchQueryOrContent = expr,
                    rawPrompt = prompt
                )
            }
        }

        // 5. Message Draft Intent (supports single-turn, multi-clause "Open WhatsApp, find Rahul, send him a message that...", and short "Message Rahul")
        val isShortMessageCmd = Regex("^(?i)(message|msg|text)\\s+([a-zA-Z0-9_]+)\\.?$").find(trimmed)
        if (isShortMessageCmd != null) {
            val shortRecipient = isShortMessageCmd.groupValues[2]
            return ResolvedIntent(
                type = UserIntentType.MESSAGE_DRAFT,
                targetAppOrEntity = "com.whatsapp",
                recipient = shortRecipient,
                searchQueryOrContent = "",
                rawPrompt = prompt
            )
        }

        if ((lower.contains("message") || lower.contains("sandesh") || lower.contains("msg")) &&
            (lower.contains("likho") || lower.contains("write") || lower.contains("draft") || lower.contains("bhejo") || lower.contains("bhejna") || lower.contains("send"))
        ) {
            var recipient = "Contact"
            var messageContent = ""

            // Extract recipient ("find Rahul", "Rahul ko", "to Rahul", "message Rahul")
            val findMatch = Regex("(?i)\\bfind\\s+([a-zA-Z0-9_]+)\\b").find(trimmed)
            val recipientMatch = findMatch
                ?: Regex("(?i)(?:aur|for|to)?\\s*([a-zA-Z0-9_]+)\\s*(?:ke liye|ko)\\s*(?:whatsapp\\s*)?(?:par\\s*)?(?:message|msg|sandesh)").find(trimmed)
                ?: Regex("(?i)(?:message|msg|sandesh)\\s*(?:to|bhejo|bhejna|send)\\s*([a-zA-Z0-9_]+)").find(trimmed)
            if (recipientMatch != null) {
                val groupVal = if (recipientMatch.groupValues.size > 1) recipientMatch.groupValues[1] else ""
                if (groupVal.isNotBlank() &&
                    !groupVal.equals("message", ignoreCase = true) &&
                    !groupVal.equals("msg", ignoreCase = true) &&
                    !groupVal.equals("whatsapp", ignoreCase = true) &&
                    !groupVal.equals("him", ignoreCase = true) &&
                    !groupVal.equals("her", ignoreCase = true)
                ) {
                    recipient = groupVal
                }
            }

            // Extract message body if colon or "that <message>" is used
            val splitByColon = trimmed.split(":", limit = 2)
            val thatMatch = Regex("(?i)\\b(?:message|msg)\\s+that\\s+(.+)$").find(trimmed)
            if (splitByColon.size > 1) {
                messageContent = splitByColon[1].trim()
            } else if (thatMatch != null) {
                messageContent = thatMatch.groupValues[1].trim().removeSuffix(".")
            } else {
                messageContent = trimmed
                    .replaceFirst(Regex("(?i)^.*?\\b(message|msg|sandesh)\\b\\s*"), "")
                    .replaceFirst(Regex("(?i)^\\s*(likho|draft|send|bhejo|bhejna|karo|hai)\\s*"), "")
                    .trim()
            }

            return ResolvedIntent(
                type = UserIntentType.MESSAGE_DRAFT,
                targetAppOrEntity = "com.whatsapp",
                recipient = recipient,
                searchQueryOrContent = messageContent,
                rawPrompt = prompt
            )
        }

        // 5b. Phone Call Intent (e.g. "call Rahul", "Rahul ko call karo", "phone lagao Rahul ko")
        if (lower.startsWith("call ") || lower.contains("ko call karo") || lower.startsWith("phone lagao") || lower.startsWith("dial ")) {
            val targetContact = trimmed
                .replaceFirst(Regex("^(?i)(call|dial|phone lagao)\\s+"), "")
                .replaceFirst(Regex("(?i)\\s*(ko call karo|ko phone karo|ko)\\s*$"), "")
                .trim()
                .removeSuffix(".")
            if (targetContact.isNotBlank()) {
                return ResolvedIntent(
                    type = UserIntentType.PHONE_CALL,
                    recipient = targetContact,
                    rawPrompt = prompt
                )
            }
        }

        // 6. YouTube Search Intent
        if (lower.contains("youtube") && (lower.contains("search") || lower.contains("dhundo"))) {
            val query = trimmed
                .replaceFirst(Regex("^(?i)(babu|nayro)\\s*,?\\s*"), "")
                .replaceFirst(Regex("^(?i)youtube\\s*(kholo|par)?\\s*(aur)?\\s*"), "")
                .replaceFirst(Regex("(?i)\\s*(search karo|search|dhundo)\\s*$"), "")
                .replaceFirst(Regex("^(?i)(search for|search|dhundo)\\s*"), "")
                .replaceFirst(Regex("(?i)\\s*(on youtube|in youtube)\\s*$"), "")
                .trim()
            return ResolvedIntent(
                type = UserIntentType.YOUTUBE_SEARCH,
                targetAppOrEntity = "com.google.android.youtube",
                searchQueryOrContent = query.ifBlank { "Android" },
                rawPrompt = prompt
            )
        }

        // 7. Settings Navigate Intent
        if (lower.contains("setting") && (lower.contains("wifi") || lower.contains("wi-fi") || lower.contains("bluetooth") || lower.contains("display") || lower.contains("sound") || lower.contains("battery"))) {
            val section = when {
                lower.contains("wifi") || lower.contains("wi-fi") -> "wifi"
                lower.contains("bluetooth") -> "bluetooth"
                lower.contains("display") -> "display"
                lower.contains("sound") -> "sound"
                lower.contains("battery") -> "battery"
                else -> "general"
            }
            return ResolvedIntent(
                type = UserIntentType.SETTINGS_NAVIGATE,
                targetAppOrEntity = "com.android.settings",
                section = section,
                rawPrompt = prompt
            )
        }

        // 8. App Launch Intent (e.g. "Chrome kholo", "open WhatsApp.", "Settings kholo")
        if (lower.contains("kholo") || lower.contains("open") || lower.contains("chalao") || lower.contains("launch") || lower.contains("start")) {
            val cleanedApp = trimmed
                .replaceFirst(Regex("^(?i)(babu|nayro)\\s*,?\\s*"), "")
                .replaceFirst(Regex("^(?i)(open|launch|start|kholo)\\s*"), "")
                .replaceFirst(Regex("(?i)\\s*(kholo|chalao|start karo|open karo)\\s*$"), "")
                .trim()
                .removeSuffix(".")

            if (cleanedApp.isNotBlank()) {
                return ResolvedIntent(
                    type = UserIntentType.APP_LAUNCH,
                    targetAppOrEntity = cleanedApp,
                    rawPrompt = prompt
                )
            }
        }

        // Default to General AI Reasoning
        return ResolvedIntent(
            type = UserIntentType.GENERAL_AI_QUERY,
            rawPrompt = prompt
        )
    }
}
