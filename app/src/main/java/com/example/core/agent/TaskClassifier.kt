package com.example.core.agent

enum class UserIntentType {
    APP_LAUNCH,
    SCREEN_READ,
    YOUTUBE_SEARCH,
    SETTINGS_NAVIGATE,
    MESSAGE_DRAFT,
    WHATSAPP_READ,
    SMS_SEND,
    OPEN_DIALER,
    PHONE_CALL,
    FLASHLIGHT_CONTROL,
    NOTIFICATION_READ,
    FILE_FIND,
    REMINDER_CREATE,
    CALENDAR_READ,
    CALENDAR_CREATE,
    CALENDAR_UPDATE,
    CALENDAR_DELETE,
    NOTE_CREATE,
    MEMORY_SAVE,
    LOCAL_TIME,
    LOCAL_CALCULATOR,
    MEDIA_CONTROL,
    GENERAL_AI_QUERY
}

data class ResolvedIntent(
    val type: UserIntentType,
    val targetAppOrEntity: String? = null,
    val searchQueryOrContent: String? = null,
    val recipient: String? = null,
    val section: String? = null,
    val rawPrompt: String,
    val confidenceScore: Float = 0.92f,
    val isAmbiguous: Boolean = false,
    val clarificationQuestion: String? = null
)

object TaskClassifier {

    fun classify(prompt: String): ResolvedIntent {
        val stripped = prompt.trim()
            .removeSuffix("।")
            .trim()
            .replaceFirst(
                Regex("^(?i)(?:hey babu|hi babu|babu|hey nayro|hi nayro|nayro|hey nairo|hi nairo|nairo|hey cutie|हे बाबू|हाय बाबू|बाबू|हे नायरो|हाय नायरो|नायरो|(?:hi|hey|hello|haan|sun|suno|हाँ|सुनो)\\s+[\\p{L}\\p{M}0-9_]+)\\s*,?\\s*"),
                ""
            )
            .trim()
        val trimmed = (if (stripped.isNotBlank()) stripped else prompt.trim()).removeSuffix("।").trim()
        val lower = trimmed.lowercase()

        // 1. Memory Save Intent
        if (lower.startsWith("yaad rakho") || lower.startsWith("remember that") || lower.contains("yaad rakhna") ||
            trimmed.startsWith("याद रखो") || trimmed.contains("याद रखना")
        ) {
            return ResolvedIntent(
                type = UserIntentType.MEMORY_SAVE,
                searchQueryOrContent = trimmed,
                rawPrompt = prompt
            )
        }

        // 2. Note Create Intent
        if (lower.startsWith("note banao") || lower.startsWith("note likho") || lower.startsWith("create note") ||
            lower.startsWith("take a note") || lower.startsWith("save a note") || lower.startsWith("save note") ||
            trimmed.startsWith("नोट बनाओ") || trimmed.startsWith("नोट लिखो")
        ) {
            val content = trimmed.replaceFirst(Regex("^(?i)(note banao:|note banao|note likho:|note likho|create note:|create note|take a note:|save a note:|save a note|save note:|save note|नोट बनाओ:|नोट बनाओ|नोट लिखो:|नोट लिखो)\\s*"), "")
            return ResolvedIntent(
                type = UserIntentType.NOTE_CREATE,
                searchQueryOrContent = content.ifBlank { "Untitled Note" },
                rawPrompt = prompt
            )
        }

        // 2a. Calendar Delete / Update / Create / Read Intents (Section 10)
        if (lower.contains("delete") && (lower.contains("calendar") || lower.contains("event") || lower.contains("meeting")) && Regex("\\d+").containsMatchIn(lower)) {
            val eventId = Regex("\\d+").find(lower)?.value ?: ""
            return ResolvedIntent(
                type = UserIntentType.CALENDAR_DELETE,
                targetAppOrEntity = eventId,
                rawPrompt = prompt
            )
        }

        if ((lower.contains("update") || lower.contains("reschedule")) && (lower.contains("calendar") || lower.contains("event")) && Regex("\\d+").containsMatchIn(lower)) {
            val eventId = Regex("\\d+").find(lower)?.value ?: ""
            val newTitle = Regex("(?i)\\bto\\s+(.+)$").find(trimmed)?.groupValues?.getOrNull(1)?.trim()?.removeSuffix(".") ?: "Updated Event"
            return ResolvedIntent(
                type = UserIntentType.CALENDAR_UPDATE,
                targetAppOrEntity = eventId,
                searchQueryOrContent = newTitle,
                rawPrompt = prompt
            )
        }

        if ((lower.startsWith("create calendar event") || lower.startsWith("schedule a meeting") || lower.startsWith("schedule meeting") || lower.startsWith("add calendar event")) &&
            !lower.contains("check") && !lower.contains("read")
        ) {
            val title = trimmed
                .replaceFirst(Regex("^(?i)(create calendar event|schedule a meeting|schedule meeting|add calendar event)\\s*(:|for|called|titled)?\\s*"), "")
                .trim()
                .removeSuffix(".")
                .ifBlank { "Scheduled Meeting" }
            return ResolvedIntent(
                type = UserIntentType.CALENDAR_CREATE,
                searchQueryOrContent = title,
                rawPrompt = prompt
            )
        }

        if ((lower.contains("calendar") || lower.contains("meeting") || lower.contains("meetings") || lower.contains("schedule") || lower.contains("agenda")) &&
            (lower.contains("check") || lower.contains("read") || lower.contains("show") || lower.contains("what") || lower.contains("tell") || lower.contains("batao") || lower.contains("dekho") || lower.contains("upcoming") || lower.contains("tomorrow") || lower.contains("today") || lower.contains("kal")) &&
            !lower.contains("conversation") && !lower.contains("whatsapp")
        ) {
            val dateQuery = when {
                lower.contains("day after tomorrow") || lower.contains("parso") -> "day after tomorrow"
                lower.contains("tomorrow") || lower.contains("kal") -> "tomorrow"
                lower.contains("today") || lower.contains("aaj") -> "today"
                else -> "upcoming"
            }
            return ResolvedIntent(
                type = UserIntentType.CALENDAR_READ,
                targetAppOrEntity = dateQuery,
                searchQueryOrContent = dateQuery,
                rawPrompt = prompt
            )
        }

        // 2b. Reminder / Alarm Create Intent (English, Hinglish, and Devanagari Hindi)
        if (lower.contains("reminder") ||
            lower.startsWith("remind me") ||
            lower.contains("yaad dilana") ||
            trimmed.contains("रिमाइंडर") ||
            trimmed.contains("याद दिलाना") ||
            trimmed.contains("याद दिला दो") ||
            ((lower.contains("alarm") || trimmed.contains("अलार्म")) &&
                (lower.contains("laga") || lower.contains("set") || lower.contains("baje") || lower.contains("schedule") || trimmed.contains("लगा") || trimmed.contains("बजे")))
        ) {
            val reminderText = trimmed
                .replaceFirst(Regex("^(?i)(create a reminder|create reminder|set a reminder|set reminder|remind me to|remind me|reminder lagao|रिमाइंडर लगाओ|रिमाइंडर सेट करो)\\b\\s*(?:(?:for|to)\\b|:)?\\s*"), "")
                .trim()
                .removeSuffix(".")
                .removeSuffix("।")
            return ResolvedIntent(
                type = UserIntentType.REMINDER_CREATE,
                searchQueryOrContent = reminderText.ifBlank { trimmed },
                rawPrompt = prompt
            )
        }

        // 2c. Flashlight / Torch Control Intent
        if (lower.contains("flashlight") || lower.contains("torch") || trimmed.contains("टॉर्च") || trimmed.contains("फ्लैशलाइट")) {
            val turnOff = lower.contains("off") || lower.contains("band") || lower.contains("disable") || trimmed.contains("बंद")
            return ResolvedIntent(
                type = UserIntentType.FLASHLIGHT_CONTROL,
                searchQueryOrContent = if (turnOff) "false" else "true",
                rawPrompt = prompt
            )
        }

        // 2d. Notification Read / Summarize Intent
        if ((lower.contains("notification") || trimmed.contains("नोटिफिकेशन")) &&
            (lower.contains("read") || lower.contains("check") || lower.contains("summarize") || lower.contains("padho") || lower.contains("dikhao") || lower.contains("batao") ||
                trimmed.contains("पढ़ो") || trimmed.contains("दिखाओ") || trimmed.contains("बताओ"))
        ) {
            val mode = if (lower.contains("summarize") || lower.contains("summary") || trimmed.contains("सारांश")) "summarize" else "read"
            return ResolvedIntent(
                type = UserIntentType.NOTIFICATION_READ,
                searchQueryOrContent = mode,
                rawPrompt = prompt
            )
        }

        // 2e. File / PDF Search Intent
        if ((lower.contains("find") || lower.contains("search") || lower.contains("dhundo") || lower.contains("locate") || trimmed.contains("ढूंढो") || trimmed.contains("खोजो")) &&
            (lower.contains("pdf") || lower.contains("file") || lower.contains("document") || lower.contains("download") || trimmed.contains("पीडीएफ") || trimmed.contains("फाइल"))
        ) {
            val query = when {
                lower.contains("pdf") || trimmed.contains("पीडीएफ") -> ".pdf"
                else -> trimmed
                    .replaceFirst(Regex("^(?i)(find|search|locate)\\s+(my\\s+)?(downloaded\\s+)?(file|document)s?\\s*"), "")
                    .trim()
                    .removeSuffix(".")
                    .removeSuffix("।")
                    .ifBlank { "txt" }
            }
            return ResolvedIntent(
                type = UserIntentType.FILE_FIND,
                searchQueryOrContent = query,
                rawPrompt = prompt
            )
        }

        // 3. Screen Read / Dialog Explanation Intent
        if (lower.contains("screen par kya") ||
            lower.contains("page par kya likha") ||
            lower.contains("dialog ko samjhao") ||
            lower.contains("explain this dialog") ||
            lower.contains("screen padho") ||
            lower.contains("what is on screen") ||
            lower.contains("what's on screen") ||
            lower.contains("read screen") ||
            trimmed.contains("स्क्रीन पढ़ो") ||
            trimmed.contains("स्क्रीन पर क्या") ||
            (lower.contains("read") && lower.contains("screen") && !lower.contains("save"))
        ) {
            return ResolvedIntent(
                type = UserIntentType.SCREEN_READ,
                rawPrompt = prompt
            )
        }

        // 4. Local Time Intent
        if (lower.contains("time kya") || lower.contains("what time") || lower.contains("current time") ||
            lower.contains("samay kya") || trimmed.contains("समय क्या") || trimmed.contains("टाइम क्या")
        ) {
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

        // 4c. WhatsApp Conversation Read / Summarize / Search Intent (Section 3 & Section 8)
        if (lower.startsWith("read whatsapp conversation with ") ||
            ((lower.contains("conversation") || (lower.contains("whatsapp") && (lower.contains("summarize") || lower.contains("read") || lower.contains("search")))) &&
                (lower.contains("summarize") || lower.contains("visible") || lower.contains("recent messages") || lower.contains("search for the conversation") || lower.contains("read whatsapp conversation")))
        ) {
            val withMatch = Regex("(?i)\\bconversation\\s+with\\s+([a-zA-Z0-9_]+)").find(trimmed)
            val aboutMatch = Regex("(?i)\\bconversation\\s+about\\s+(.+)$").find(trimmed)
            val query = withMatch?.groupValues?.getOrNull(1)?.trim()
                ?: aboutMatch?.groupValues?.getOrNull(1)?.trim()?.removeSuffix(".")
                ?: trimmed
            return ResolvedIntent(
                type = UserIntentType.WHATSAPP_READ,
                targetAppOrEntity = "com.whatsapp",
                searchQueryOrContent = query,
                rawPrompt = prompt
            )
        }

        // 4d. Explicit SMS Send Intent (Section 2 & Section 7: "Babu, Rahul ko SMS bhejo: main raste mein hoon", "Send SMS to +919876543210: Meeting at 5 PM")
        val isSmsReadQuery = lower.contains("padho") || lower.contains("read") || lower.contains("check") ||
            lower.contains("latest") || lower.contains("unread") || lower.contains("otp")
        if (lower.contains("sms") && !isSmsReadQuery &&
            (lower.contains("bhejo") || lower.contains("bhejna") || lower.contains("send") || lower.contains("likho") || lower.contains("karo") || lower.contains(":"))
        ) {
            val recipientMatch = Regex("(?i)\\b([+0-9][0-9\\-]{6,14}|[a-zA-Z0-9_]+)\\s+(?:ko|par)\\s+sms\\b").find(trimmed)
                ?: Regex("(?i)\\bsms\\s+(?:to|for|on)\\s+([+0-9][0-9\\-]{6,14}|[a-zA-Z0-9_]+)").find(trimmed)
                ?: Regex("(?i)\\bsend\\s+([+0-9][0-9\\-]{6,14}|[a-zA-Z0-9_]+)\\s+(?:an?\\s+)?sms\\b").find(trimmed)
            val recipient = recipientMatch?.groupValues?.getOrNull(1)?.trim() ?: "Contact"

            val splitByColon = trimmed.split(":", limit = 2)
            val thatMatch = Regex("(?i)\\b(?:saying|that|ki)\\s+(.+)$").find(trimmed)
            val smsBody = when {
                splitByColon.size > 1 -> splitByColon[1].trim()
                thatMatch != null -> thatMatch.groupValues[1].trim().removeSuffix(".")
                else -> trimmed
                    .replaceFirst(Regex("(?i)^.*?\\bsms\\b\\s*(?:bhejo|bhejna|send|karo|to\\s+[+a-zA-Z0-9_\\-]+)?\\s*"), "")
                    .trim()
            }
            return ResolvedIntent(
                type = UserIntentType.SMS_SEND,
                recipient = recipient,
                searchQueryOrContent = smsBody,
                rawPrompt = prompt
            )
        }

        // 4e. Open Phone Dialer Intent (Section 1A: "Open phone dialer for Rahul", "Open dialer for +919876543210")
        if (lower.contains("dialer")) {
            val targetMatch = Regex("(?i)\\b(?:for|with|to)\\s+([+0-9][0-9\\-]{6,14}|[a-zA-Z0-9_]+)").find(trimmed)
                ?: Regex("(?i)\\b([+0-9][0-9\\-]{6,14}|[a-zA-Z0-9_]+)\\s+(?:ka\\s+number\\s+)?dialer").find(trimmed)
            val target = targetMatch?.groupValues?.getOrNull(1)?.trim()
                ?.takeIf { !it.equals("without", ignoreCase = true) && !it.equals("phone", ignoreCase = true) }
                ?: "Contact"
            return ResolvedIntent(
                type = UserIntentType.OPEN_DIALER,
                recipient = target,
                rawPrompt = prompt
            )
        }

        // 5. Message Draft Intent
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

        val findAndSendShort = Regex("(?i)(?:open\\s+whatsapp\\s*,?\\s*)?(?:and\\s+)?find\\s+([a-zA-Z0-9_]+)\\s*(?:,|\\s+and)\\s*send\\s+(?:him\\s+|her\\s+)?(?!him\\b|her\\b|a\\s+message|a\\s+whatsapp|message|msg)(.+)$").find(trimmed)
        if (findAndSendShort != null) {
            val rec = findAndSendShort.groupValues[1].trim()
            val body = findAndSendShort.groupValues[2].trim().removeSuffix(".")
            return ResolvedIntent(
                type = UserIntentType.MESSAGE_DRAFT,
                targetAppOrEntity = "com.whatsapp",
                recipient = rec,
                searchQueryOrContent = body,
                rawPrompt = prompt
            )
        }

        val directMessageThat = Regex("(?i)(?:open\\s+whatsapp\\s*,?\\s*)?(?:and\\s+)?(?:message|msg|text)\\s+([a-zA-Z0-9_]+)\\s+(?:that|saying)\\s+(.+)$").find(trimmed)
        if (directMessageThat != null) {
            val rec = directMessageThat.groupValues[1].trim()
            val body = directMessageThat.groupValues[2].trim().removeSuffix(".")
            return ResolvedIntent(
                type = UserIntentType.MESSAGE_DRAFT,
                targetAppOrEntity = "com.whatsapp",
                recipient = rec,
                searchQueryOrContent = body,
                rawPrompt = prompt
            )
        }

        val sendPersonMessageSaying = Regex("(?i)(?:open\\s+whatsapp\\s*,?\\s*)?(?:and\\s+)?send\\s+([a-zA-Z0-9_]+)\\s+a\\s+(?:whatsapp\\s+)?(?:message|msg)\\s+(?:saying|that)\\s+(.+)$").find(trimmed)
        if (sendPersonMessageSaying != null) {
            val rawRec = sendPersonMessageSaying.groupValues[1].trim()
            val rec = if (rawRec.equals("him", ignoreCase = true) || rawRec.equals("her", ignoreCase = true)) {
                Regex("(?i)\\bfind\\s+([a-zA-Z0-9_]+)\\b").find(trimmed)?.groupValues?.getOrNull(1)?.trim() ?: rawRec
            } else {
                rawRec
            }
            val body = sendPersonMessageSaying.groupValues[2].trim().removeSuffix(".")
            return ResolvedIntent(
                type = UserIntentType.MESSAGE_DRAFT,
                targetAppOrEntity = "com.whatsapp",
                recipient = rec,
                searchQueryOrContent = body,
                rawPrompt = prompt
            )
        }

        val hasMessageNoun = lower.contains("message") || lower.contains("sandesh") || lower.contains("msg") ||
            trimmed.contains("मैसेज") || trimmed.contains("संदेश")
        val hasMessageVerb = lower.contains("likho") || lower.contains("write") || lower.contains("draft") ||
            lower.contains("bhejo") || lower.contains("bhej do") || lower.contains("bhejna") ||
            lower.contains("send") || lower.contains("saying") || lower.contains("that") ||
            trimmed.contains("भेजो") || trimmed.contains("भेज दो") || trimmed.contains("भेजना") ||
            trimmed.contains("लिखो") || trimmed.contains("भेज")

        if (hasMessageNoun && hasMessageVerb) {
            var recipient = "Contact"
            var messageContent = ""

            // Extract recipient across English, Hinglish, and Devanagari ("find Rahul", "Rahul ko", "Amit को message", "to Rahul")
            val findMatch = Regex("(?i)\\bfind\\s+([\\p{L}\\p{M}0-9_]+)").find(trimmed)
            val msgTargetMatch = Regex("(?i)\\b(?:message|msg|send)\\s+([\\p{L}\\p{M}0-9_]+)").find(trimmed)
            val koHindiMatch = Regex("(?i)(?:^|\\s)(?:aur|और|for|to)?\\s*([\\p{L}\\p{M}0-9_+]+)\\s+(?:ke liye|के लिए|ko|को)\\s+(?:whatsapp|व्हाट्सएप|वॉट्सऐप)?\\s*(?:par|पर)?\\s*(?:message|msg|sandesh|मैसेज|संदेश)").find(trimmed)
            val recipientMatch = findMatch
                ?: koHindiMatch
                ?: Regex("(?i)(?:message|msg|sandesh|मैसेज|संदेश)\\s*(?:to|bhejo|bhejna|send|भेजो|भेजना)\\s*([\\p{L}\\p{M}0-9_+]+)").find(trimmed)
                ?: msgTargetMatch
            if (recipientMatch != null) {
                val groupVal = if (recipientMatch.groupValues.size > 1) recipientMatch.groupValues[1] else ""
                val blockedTokens = setOf(
                    "message", "msg", "a", "the", "whatsapp", "him", "her", "to", "par", "पर",
                    "भेज", "भेजो", "भेजना", "दो", "है", "हैं", "करना", "करो", "व्हाट्सएप", "वॉट्सऐप",
                    "मुझे", "आज", "फिर", "और"
                )
                if (groupVal.isNotBlank() && groupVal.lowercase() !in blockedTokens) {
                    recipient = groupVal
                }
            }

            // Extract message body if colon or "that/saying/ki/कि <message>" is used
            val splitByColon = trimmed.split(":", limit = 2)
            val thatMatch = Regex("(?i)(?:message|msg|मैसेज|संदेश)\\s+(?:that|saying|ki|कि)\\s+(.+)$").find(trimmed)
                ?: Regex("(?i)\\b(?:saying|that|कि)\\s+(.+)$").find(trimmed)
            if (splitByColon.size > 1) {
                messageContent = splitByColon[1].trim().removeSuffix("।").trim()
            } else if (thatMatch != null) {
                messageContent = thatMatch.groupValues[1].trim().removeSuffix(".").removeSuffix("।").trim()
            } else {
                messageContent = trimmed
                    .replaceFirst(Regex("(?i)^.*?(?:message|msg|sandesh|मैसेज|संदेश)\\s*"), "")
                    .replaceFirst(Regex("(?i)^\\s*(likho|likh do|draft|send|bhejo|bhej do|bhejna|karo|kar do|hai|भेजो|भेज दो|भेज देना|भेजना है|भेजना|लिखो|लिख दो|करना है|करो|है)\\s*"), "")
                    .trim()
                    .removeSuffix(".")
                    .removeSuffix("।")
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

        // 5b. Phone Call Intent (English, Hinglish, and Devanagari Hindi: "call Rahul", "Rahul ko call karo", "आज मुझे Rahul को कॉल करना है")
        val isDevanagariCall = trimmed.contains("को कॉल") ||
            trimmed.contains("को फ़ोन") ||
            trimmed.contains("को फोन") ||
            trimmed.contains("कॉल करना") ||
            trimmed.contains("कॉल करो") ||
            trimmed.contains("कॉल लगाओ") ||
            trimmed.contains("फ़ोन करो") ||
            trimmed.contains("फोन करो") ||
            trimmed.contains("फोन लगाओ")

        if (lower.startsWith("call ") ||
            lower.contains("ko call karo") ||
            lower.contains("ko call kar do") ||
            lower.contains("ko call bhi kar do") ||
            lower.contains("ko call karna") ||
            lower.contains("ko phone karo") ||
            lower.contains("ko phone karna") ||
            lower.contains("par call karo") ||
            lower.startsWith("phone lagao") ||
            lower.startsWith("make a call to ") ||
            lower.startsWith("make a phone call to ") ||
            lower.startsWith("dial ") ||
            isDevanagariCall
        ) {
            val hindiCallTarget = Regex("(?i)([\\p{L}\\p{M}0-9_+]+)\\s+(?:ko|को|par|पर)\\s+(?:call|phone|कॉल|फ़ोन|फोन)").find(trimmed)
                ?.groupValues?.getOrNull(1)?.trim()
                ?.takeIf { it.lowercase() !in setOf("mujhe", "मुझे", "aaj", "आज", "ab", "अब", "phir", "फिर") }

            val targetContact = hindiCallTarget ?: trimmed
                .removeSuffix(".")
                .removeSuffix("।")
                .trim()
                .replaceFirst(Regex("^(?i)(aaj\\s+mujhe\\s+|mujhe\\s+|आज\\s+मुझे\\s+|मुझे\\s+|ab\\s+|अब\\s+|now\\s+)?(make a phone call to|make a call to|call|dial|phone lagao|कॉल करो|कॉल लगाओ|फोन लगाओ)\\s+"), "")
                .replaceFirst(Regex("(?i)\\s*(ko call bhi kar do|ko call kar do|ko call karna hai|ko call karna|ko call karo|ko phone karo|par call karo|को कॉल करना है|को कॉल करना|को कॉल करो|को कॉल कर दो|को कॉल लगाओ|को फ़ोन करो|को फोन करो|ko|को)\\s*$"), "")
                .trim()
                .removeSuffix(".")
                .removeSuffix("।")
            if (targetContact.isNotBlank()) {
                return ResolvedIntent(
                    type = UserIntentType.PHONE_CALL,
                    recipient = targetContact,
                    rawPrompt = prompt
                )
            }
        }

        // 6. Media & Music Control Intent
        if (lower in setOf("next song", "next track", "skip song", "skip track", "agla gaana", "agla song", "next gaana", "next song chalao", "agla gaana lagao")) {
            return ResolvedIntent(
                type = UserIntentType.MEDIA_CONTROL,
                section = "NEXT_TRACK",
                searchQueryOrContent = "Next track",
                rawPrompt = prompt
            )
        }
        if (lower in setOf("previous song", "previous track", "pichhla gaana", "pichla gaana", "prev song", "previous gaana")) {
            return ResolvedIntent(
                type = UserIntentType.MEDIA_CONTROL,
                section = "PREVIOUS_TRACK",
                searchQueryOrContent = "Previous track",
                rawPrompt = prompt
            )
        }
        if (lower in setOf("isse pause karo", "ise pause karo", "pause music", "pause song", "pause playback", "gaana roko", "music band karo", "pause karo", "song pause karo")) {
            return ResolvedIntent(
                type = UserIntentType.MEDIA_CONTROL,
                section = "PAUSE",
                searchQueryOrContent = "Pause playback",
                rawPrompt = prompt
            )
        }
        if (lower in setOf("resume music", "resume song", "resume playback", "gaana chalao", "phir se chalao", "continue music")) {
            return ResolvedIntent(
                type = UserIntentType.MEDIA_CONTROL,
                section = "RESUME",
                searchQueryOrContent = "Resume playback",
                rawPrompt = prompt
            )
        }
        if (lower.contains("favourite song") || lower.contains("favorite song") || lower.contains("pasandida gaana") || lower.contains("favourite gaana")) {
            if (lower.contains("chalao") || lower.contains("play") || lower.contains("lagao") || lower.contains("sunao")) {
                val targetApp = when {
                    lower.contains("spotify") -> "com.spotify.music"
                    lower.contains("youtube music") -> "com.google.android.apps.youtube.music"
                    else -> "com.google.android.youtube"
                }
                return ResolvedIntent(
                    type = UserIntentType.MEDIA_CONTROL,
                    targetAppOrEntity = targetApp,
                    section = "PLAY_FAVOURITE_SONG",
                    searchQueryOrContent = "favourite song",
                    rawPrompt = prompt
                )
            }
        }
        if ((lower.contains("song") || lower.contains("songs") || lower.contains("gaana") || lower.contains("gaane") || lower.contains("music")) &&
            (lower.contains("chalao") || lower.contains("play") || lower.contains("lagao") || lower.contains("sunao"))
        ) {
            val targetApp = when {
                lower.contains("spotify") -> "com.spotify.music"
                lower.contains("youtube music") -> "com.google.android.apps.youtube.music"
                else -> "com.google.android.youtube"
            }
            val cleanedQuery = trimmed
                .removeSuffix(".")
                .replaceFirst(Regex("^(?i)(hey babu|hi babu|babu|nayro)\\s*,?\\s*"), "")
                .replaceFirst(Regex("^(?i)(open\\s+youtube\\s+and\\s+|youtube\\s+kholo\\s+aur\\s+|spotify\\s+kholo\\s+aur\\s+|ab\\s+|now\\s+)"), "")
                .replaceFirst(Regex("^(?i)(play|chalao|lagao|sunao)\\s+"), "")
                .replaceFirst(Regex("(?i)\\s+(ke\\s+songs\\s+chalao|ke\\s+gaane\\s+chalao|songs\\s+chalao|song\\s+chalao|chalao|lagao|on\\s+youtube|on\\s+spotify)\\s*$"), "")
                .trim()
                .removeSuffix(".")
                .ifBlank { "Hindi songs" }

            val op = when {
                lower.contains("sad") || lower.contains("romantic") || lower.contains("party") || lower.contains("lofi") || lower.contains("bhajan") -> "PLAY_BY_GENRE_OR_MOOD"
                lower.contains("ke songs") || lower.contains("ke gaane") -> "PLAY_BY_ARTIST"
                else -> "PLAY_QUERY"
            }
            return ResolvedIntent(
                type = UserIntentType.MEDIA_CONTROL,
                targetAppOrEntity = targetApp,
                section = op,
                searchQueryOrContent = cleanedQuery,
                rawPrompt = prompt
            )
        }

        // 6b. YouTube Search Intent
        if (lower.contains("youtube") && (lower.contains("search") || lower.contains("dhundo"))) {
            val query = trimmed
                .removeSuffix(".")
                .trim()
                .replaceFirst(Regex("^(?i)(babu|nayro)\\s*,?\\s*"), "")
                .replaceFirst(Regex("^(?i)(open\\s+|launch\\s+)?youtube\\s*(kholo|par)?\\s*(,|and|aur)?\\s*"), "")
                .replaceFirst(Regex("(?i)\\s*(search karo|search|dhundo)\\s*$"), "")
                .replaceFirst(Regex("^(?i)(search for|search|dhundo)\\s*"), "")
                .replaceFirst(Regex("(?i)\\s*(on youtube|in youtube)\\s*$"), "")
                .trim()
                .removeSuffix(".")
            return ResolvedIntent(
                type = UserIntentType.YOUTUBE_SEARCH,
                targetAppOrEntity = "com.google.android.youtube",
                searchQueryOrContent = query.ifBlank { "Android" },
                rawPrompt = prompt
            )
        }

        // 7. Settings Navigate & Brightness/Volume Intent ("Phone ki brightness thodi kam kar do", "Wi-Fi settings kholo")
        if (lower.contains("brightness")) {
            val adjustMode = if (lower.contains("kam") || lower.contains("down") || lower.contains("decrease") || lower.contains("lower") || lower.contains("dim")) {
                "brightness_down"
            } else {
                "brightness_up"
            }
            return ResolvedIntent(
                type = UserIntentType.SETTINGS_NAVIGATE,
                targetAppOrEntity = "com.android.settings",
                section = "display",
                searchQueryOrContent = adjustMode,
                rawPrompt = prompt
            )
        }

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

        // 8. App Launch Intent (English, Hinglish, and Devanagari Hindi: "Chrome kholo", "open WhatsApp", "व्हाट्सएप खोलो")
        if ((lower.contains("kholo") || lower.contains("open") || lower.contains("chalao") || lower.contains("launch") || lower.contains("start") ||
                trimmed.contains("खोलो") || trimmed.contains("खोल दो") || trimmed.contains("चलाओ")) &&
            !lower.contains("screen par") && !lower.contains("option")
        ) {
            val cleanedAppRaw = trimmed
                .removeSuffix(".")
                .removeSuffix("।")
                .trim()
                .replaceFirst(Regex("^(?i)(babu|nayro|बाबू|नायरो)\\s*,?\\s*"), "")
                .replaceFirst(Regex("^(?i)(open|launch|start|kholo|खोलो)\\s*"), "")
                .replaceFirst(Regex("(?i)\\s*(kholo|chalao|start karo|open karo|खोलो|खोल दो|चलाओ|चालू करो)\\s*$"), "")
                .trim()
                .removeSuffix(".")
                .removeSuffix("।")

            val cleanedApp = when (cleanedAppRaw) {
                "व्हाट्सएप", "वॉट्सऐप", "व्हाट्सऐप" -> "WhatsApp"
                "यूट्यूब" -> "YouTube"
                "क्रोम" -> "Chrome"
                "इंस्टाग्राम" -> "Instagram"
                "सेटिंग्स", "सेटिंग" -> "Settings"
                "जीमेल" -> "Gmail"
                "कैमरा" -> "Camera"
                else -> cleanedAppRaw
            }

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
            rawPrompt = prompt,
            confidenceScore = 0.78f
        )
    }
}

