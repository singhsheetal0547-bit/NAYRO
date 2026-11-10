package com.example.core.agent

enum class UserIntentType {
    APP_LAUNCH,
    SCREEN_READ,
    YOUTUBE_SEARCH,
    SETTINGS_NAVIGATE,
    MESSAGE_DRAFT,
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

        // 4b. Local Calculator Intent (e.g. "calculate (25 + 15) * 2", "hisab karo: 100 / 4", "(25 + 15) * 2")
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

        // 5. Message Draft Intent (e.g. "WhatsApp par Rahul ko message bhejo: Main 10 minute mein aa raha hoon")
        if ((lower.contains("message") || lower.contains("sandesh") || lower.contains("msg")) &&
            (lower.contains("likho") || lower.contains("write") || lower.contains("draft") || lower.contains("bhejo") || lower.contains("send"))
        ) {
            var recipient = "Contact"
            var messageContent = ""

            // Extract recipient if specified ("Rahul ko", "to Rahul", "Rahul ke liye")
            val recipientMatch = Regex("(?i)(?:aur|for|to)?\\s*([a-zA-Z0-9_]+)\\s*(?:ke liye|ko)\\s*(?:message|msg|sandesh)").find(trimmed)
                ?: Regex("(?i)(?:message|msg|sandesh)\\s*(?:to|bhejo|send)\\s*([a-zA-Z0-9_]+)").find(trimmed)
            if (recipientMatch != null) {
                val groupVal = if (recipientMatch.groupValues.size > 1) recipientMatch.groupValues[1] else ""
                if (groupVal.isNotBlank() && !groupVal.equals("message", ignoreCase = true) && !groupVal.equals("msg", ignoreCase = true)) {
                    recipient = groupVal
                }
            }

            // Extract message body if colon is used or after "message:" / "bhejo:"
            val splitByColon = trimmed.split(":", limit = 2)
            if (splitByColon.size > 1) {
                messageContent = splitByColon[1].trim()
            } else {
                messageContent = trimmed
                    .replaceFirst(Regex("(?i)^.*?\\b(message|msg|sandesh)\\b\\s*"), "")
                    .replaceFirst(Regex("(?i)^\\s*(likho|draft|send|bhejo)\\s*"), "")
                    .trim()
            }

            return ResolvedIntent(
                type = UserIntentType.MESSAGE_DRAFT,
                targetAppOrEntity = "com.whatsapp",
                recipient = recipient,
                searchQueryOrContent = messageContent.ifBlank { "Hello!" },
                rawPrompt = prompt
            )
        }

        // 6. YouTube Search Intent (e.g. "YouTube kholo aur Android AI search karo")
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

        // 7. Settings Navigate Intent (e.g. "Settings kholo aur Wi-Fi section tak jao")
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

        // 8. App Launch Intent (e.g. "Chrome kholo", "open YouTube", "Settings kholo")
        if (lower.contains("kholo") || lower.contains("open") || lower.contains("chalao") || lower.contains("launch") || lower.contains("start")) {
            val cleanedApp = trimmed
                .replaceFirst(Regex("^(?i)(babu|nayro)\\s*,?\\s*"), "")
                .replaceFirst(Regex("^(?i)(open|launch|start|kholo)\\s*"), "")
                .replaceFirst(Regex("(?i)\\s*(kholo|chalao|start karo|open karo)\\s*$"), "")
                .trim()

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
