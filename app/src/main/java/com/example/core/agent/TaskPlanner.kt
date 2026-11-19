package com.example.core.agent

import com.example.core.capability.CapabilityId
import org.json.JSONArray
import java.util.UUID

object TaskPlanner {

    fun planTask(intent: ResolvedIntent, existingTaskId: String = UUID.randomUUID().toString()): AgentTask {
        val taskId = existingTaskId
        val actions = mutableListOf<AgentAction>()

        when (intent.type) {
            UserIntentType.APP_LAUNCH -> {
                actions.add(
                    AgentAction(
                        taskId = taskId,
                        actionType = ActionType.LAUNCH_APP,
                        target = intent.targetAppOrEntity,
                        riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE,
                        verificationStrategy = "VERIFY_FOREGROUND_PACKAGE",
                        idempotencyClassification = "IDEMPOTENT"
                    )
                )
            }

            UserIntentType.SCREEN_READ -> {
                actions.add(
                    AgentAction(
                        taskId = taskId,
                        actionType = ActionType.READ_SCREEN,
                        requiredCapability = CapabilityId.ACCESSIBILITY,
                        riskLevel = RiskLevel.R0_LOCAL_READONLY,
                        verificationStrategy = "VERIFY_SCREEN_READ",
                        idempotencyClassification = "IDEMPOTENT"
                    )
                )
            }

            UserIntentType.YOUTUBE_SEARCH -> {
                val ytQuery = intent.searchQueryOrContent ?: "Android"
                val lowerRaw = intent.rawPrompt.lowercase()
                val wantsPlaybackSelection = lowerRaw.contains("play") ||
                    lowerRaw.contains("watch") ||
                    lowerRaw.contains("chalao") ||
                    lowerRaw.contains("bajao") ||
                    lowerRaw.contains("sunao") ||
                    lowerRaw.contains("video") ||
                    lowerRaw.contains("song") ||
                    lowerRaw.contains("gaana")
                actions.add(
                    AgentAction(
                        taskId = taskId,
                        actionType = ActionType.LAUNCH_APP,
                        target = "com.google.android.youtube",
                        riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE,
                        verificationStrategy = "VERIFY_FOREGROUND_PACKAGE",
                        idempotencyClassification = "IDEMPOTENT"
                    )
                )
                actions.add(
                    AgentAction(
                        taskId = taskId,
                        actionType = ActionType.CLICK_ELEMENT,
                        target = "Search",
                        requiredCapability = CapabilityId.ACCESSIBILITY,
                        riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE,
                        idempotencyClassification = "NON_IDEMPOTENT"
                    )
                )
                actions.add(
                    AgentAction(
                        taskId = taskId,
                        actionType = ActionType.SET_TEXT,
                        target = "Search",
                        parameters = mapOf(
                            "query" to ytQuery,
                            "text" to ytQuery,
                            "submit" to "true"
                        ),
                        requiredCapability = CapabilityId.ACCESSIBILITY,
                        riskLevel = RiskLevel.R2_APPLICATION_STATE,
                        idempotencyClassification = "NON_IDEMPOTENT"
                    )
                )
                if (wantsPlaybackSelection) {
                    actions.add(
                        AgentAction(
                            taskId = taskId,
                            actionType = ActionType.READ_SCREEN,
                            target = "YouTube Search Results",
                            requiredCapability = CapabilityId.ACCESSIBILITY,
                            riskLevel = RiskLevel.R0_LOCAL_READONLY,
                            verificationStrategy = "VERIFY_SCREEN_READ",
                            idempotencyClassification = "IDEMPOTENT"
                        )
                    )
                    actions.add(
                        AgentAction(
                            taskId = taskId,
                            actionType = ActionType.CONTROL_MEDIA,
                            target = ytQuery,
                            parameters = mapOf(
                                "operation" to "SELECT_AND_VERIFY_YOUTUBE",
                                "query" to ytQuery,
                                "app" to "com.google.android.youtube"
                            ),
                            requiredCapability = CapabilityId.ACCESSIBILITY,
                            riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE,
                            verificationStrategy = "VERIFY_MEDIA_PLAYBACK",
                            idempotencyClassification = "NON_IDEMPOTENT"
                        )
                    )
                }
            }

            UserIntentType.SETTINGS_NAVIGATE -> {
                val params = mutableMapOf("section" to (intent.section ?: "general"))
                if (!intent.searchQueryOrContent.isNullOrBlank()) {
                    params["adjustment"] = intent.searchQueryOrContent
                }
                actions.add(
                    AgentAction(
                        taskId = taskId,
                        actionType = ActionType.OPEN_SETTINGS_SECTION,
                        target = intent.section ?: "general",
                        parameters = params,
                        riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE,
                        idempotencyClassification = "IDEMPOTENT"
                    )
                )
                actions.add(
                    AgentAction(
                        taskId = taskId,
                        actionType = ActionType.READ_SCREEN,
                        requiredCapability = CapabilityId.ACCESSIBILITY,
                        riskLevel = RiskLevel.R0_LOCAL_READONLY,
                        idempotencyClassification = "IDEMPOTENT"
                    )
                )
            }

            UserIntentType.MESSAGE_DRAFT -> {
                actions.add(
                    AgentAction(
                        taskId = taskId,
                        actionType = ActionType.LAUNCH_APP,
                        target = "com.whatsapp",
                        riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE,
                        idempotencyClassification = "IDEMPOTENT"
                    )
                )
                actions.add(
                    AgentAction(
                        taskId = taskId,
                        actionType = ActionType.CLICK_ELEMENT,
                        target = intent.recipient ?: "Contact",
                        requiredCapability = CapabilityId.ACCESSIBILITY,
                        riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE,
                        idempotencyClassification = "NON_IDEMPOTENT"
                    )
                )
                actions.add(
                    AgentAction(
                        taskId = taskId,
                        actionType = ActionType.SET_TEXT,
                        parameters = mapOf("text" to (intent.searchQueryOrContent ?: "")),
                        requiredCapability = CapabilityId.ACCESSIBILITY,
                        riskLevel = RiskLevel.R2_APPLICATION_STATE,
                        idempotencyClassification = "NON_IDEMPOTENT"
                    )
                )
                actions.add(
                    AgentAction(
                        taskId = taskId,
                        actionType = ActionType.ASK_USER_CONFIRMATION,
                        target = intent.recipient,
                        parameters = mapOf(
                            "recipient" to (intent.recipient ?: "Unknown"),
                            "draft" to (intent.searchQueryOrContent ?: "")
                        ),
                        riskLevel = RiskLevel.R3_EXTERNAL_COMMUNICATION,
                        idempotencyClassification = "NON_IDEMPOTENT"
                    )
                )
            }

            UserIntentType.WHATSAPP_READ -> {
                val query = intent.searchQueryOrContent ?: intent.targetAppOrEntity ?: "WhatsApp"
                actions.add(
                    AgentAction(
                        taskId = taskId,
                        actionType = ActionType.WHATSAPP_READ_CONVERSATION,
                        target = query,
                        parameters = mapOf("query" to query),
                        requiredCapability = CapabilityId.ACCESSIBILITY,
                        riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE,
                        idempotencyClassification = "IDEMPOTENT"
                    )
                )
            }

            UserIntentType.CALENDAR_READ -> {
                val dateQuery = intent.searchQueryOrContent ?: intent.targetAppOrEntity ?: "upcoming"
                actions.add(
                    AgentAction(
                        taskId = taskId,
                        actionType = ActionType.READ_CALENDAR_EVENTS,
                        target = dateQuery,
                        parameters = mapOf("date" to dateQuery),
                        riskLevel = RiskLevel.R0_LOCAL_READONLY,
                        idempotencyClassification = "IDEMPOTENT"
                    )
                )
            }

            UserIntentType.CALENDAR_CREATE -> {
                val title = intent.searchQueryOrContent ?: "Scheduled Meeting"
                actions.add(
                    AgentAction(
                        taskId = taskId,
                        actionType = ActionType.CREATE_CALENDAR_EVENT,
                        target = title,
                        parameters = mapOf("title" to title),
                        riskLevel = RiskLevel.R2_APPLICATION_STATE,
                        idempotencyClassification = "NON_IDEMPOTENT"
                    )
                )
            }

            UserIntentType.CALENDAR_UPDATE -> {
                val eventId = intent.targetAppOrEntity ?: "0"
                val newTitle = intent.searchQueryOrContent ?: "Updated Meeting"
                actions.add(
                    AgentAction(
                        taskId = taskId,
                        actionType = ActionType.UPDATE_CALENDAR_EVENT,
                        target = eventId,
                        parameters = mapOf("eventId" to eventId, "title" to newTitle),
                        riskLevel = RiskLevel.R2_APPLICATION_STATE,
                        idempotencyClassification = "NON_IDEMPOTENT"
                    )
                )
            }

            UserIntentType.CALENDAR_DELETE -> {
                val eventId = intent.targetAppOrEntity ?: "0"
                actions.add(
                    AgentAction(
                        taskId = taskId,
                        actionType = ActionType.DELETE_CALENDAR_EVENT,
                        target = eventId,
                        parameters = mapOf("eventId" to eventId),
                        riskLevel = RiskLevel.R4_FINANCIAL_PROHIBITED,
                        idempotencyClassification = "NON_IDEMPOTENT"
                    )
                )
            }

            UserIntentType.SMS_SEND -> {
                val targetRecipient = intent.recipient ?: "Contact"
                val messageBody = intent.searchQueryOrContent ?: ""
                actions.add(
                    AgentAction(
                        taskId = taskId,
                        actionType = ActionType.SEND_SMS,
                        target = targetRecipient,
                        parameters = mapOf(
                            "recipient" to targetRecipient,
                            "message" to messageBody
                        ),
                        riskLevel = RiskLevel.R3_EXTERNAL_COMMUNICATION,
                        idempotencyClassification = "NON_IDEMPOTENT"
                    )
                )
            }

            UserIntentType.OPEN_DIALER -> {
                val targetRecipient = intent.recipient ?: "Contact"
                actions.add(
                    AgentAction(
                        taskId = taskId,
                        actionType = ActionType.OPEN_PHONE_DIALER,
                        target = targetRecipient,
                        parameters = mapOf(
                            "target" to targetRecipient,
                            "recipient" to targetRecipient
                        ),
                        riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE,
                        idempotencyClassification = "IDEMPOTENT"
                    )
                )
            }

            UserIntentType.PHONE_CALL -> {
                val targetRecipient = intent.recipient ?: "Contact"
                actions.add(
                    AgentAction(
                        taskId = taskId,
                        actionType = ActionType.MAKE_PHONE_CALL,
                        target = targetRecipient,
                        parameters = mapOf(
                            "target" to targetRecipient,
                            "recipient" to targetRecipient
                        ),
                        riskLevel = RiskLevel.R3_EXTERNAL_COMMUNICATION,
                        idempotencyClassification = "NON_IDEMPOTENT"
                    )
                )
            }

            UserIntentType.FLASHLIGHT_CONTROL -> {
                val enabledVal = intent.searchQueryOrContent ?: "true"
                actions.add(
                    AgentAction(
                        taskId = taskId,
                        actionType = ActionType.TOGGLE_FLASHLIGHT,
                        target = if (enabledVal == "false") "OFF" else "ON",
                        parameters = mapOf("enabled" to enabledVal),
                        riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE,
                        idempotencyClassification = "IDEMPOTENT"
                    )
                )
            }

            UserIntentType.NOTIFICATION_READ -> {
                val actionType = if (intent.searchQueryOrContent == "summarize") {
                    ActionType.SUMMARIZE_NOTIFICATIONS
                } else {
                    ActionType.READ_NOTIFICATIONS
                }
                actions.add(
                    AgentAction(
                        taskId = taskId,
                        actionType = actionType,
                        riskLevel = RiskLevel.R0_LOCAL_READONLY,
                        idempotencyClassification = "IDEMPOTENT"
                    )
                )
            }

            UserIntentType.FILE_FIND -> {
                val query = intent.searchQueryOrContent ?: ".pdf"
                actions.add(
                    AgentAction(
                        taskId = taskId,
                        actionType = ActionType.FILE_SEARCH,
                        target = query,
                        parameters = mapOf("query" to query),
                        riskLevel = RiskLevel.R0_LOCAL_READONLY,
                        idempotencyClassification = "IDEMPOTENT"
                    )
                )
            }

            UserIntentType.REMINDER_CREATE -> {
                val lowerRaw = intent.rawPrompt.lowercase()
                if (lowerRaw.contains("alarm") && (lowerRaw.contains("laga") || lowerRaw.contains("set") || lowerRaw.contains("baje") || lowerRaw.contains("schedule"))) {
                    val hourMatch = Regex("(\\d{1,2})\\s*(?:baje|am|pm|:)").find(lowerRaw)
                        ?: Regex("(\\d{1,2})").find(lowerRaw)
                    var hour = hourMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 6
                    if ((lowerRaw.contains("pm") || lowerRaw.contains("shaam") || lowerRaw.contains("raat")) && hour in 1..11) {
                        hour += 12
                    }
                    actions.add(
                        AgentAction(
                            taskId = taskId,
                            actionType = ActionType.SCHEDULE_ALARM,
                            target = "NAYRO Alarm",
                            parameters = mapOf(
                                "hour" to hour.coerceIn(0, 23).toString(),
                                "minutes" to "0",
                                "message" to "NAYRO Alarm"
                            ),
                            riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE,
                            idempotencyClassification = "NON_IDEMPOTENT"
                        )
                    )
                } else {
                    val title = intent.searchQueryOrContent ?: "Reminder"
                    actions.add(
                        AgentAction(
                            taskId = taskId,
                            actionType = ActionType.CREATE_REMINDER,
                            target = title,
                            parameters = mapOf("title" to title, "time" to "Tomorrow 09:00 AM"),
                            riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE,
                            idempotencyClassification = "NON_IDEMPOTENT"
                        )
                    )
                }
            }

            UserIntentType.NOTE_CREATE -> {
                val lowerPrompt = intent.rawPrompt.lowercase()
                if (lowerPrompt.contains("current screen") || lowerPrompt.contains("from screen")) {
                    actions.add(
                        AgentAction(
                            taskId = taskId,
                            actionType = ActionType.READ_SCREEN,
                            requiredCapability = CapabilityId.ACCESSIBILITY,
                            riskLevel = RiskLevel.R0_LOCAL_READONLY,
                            verificationStrategy = "VERIFY_SCREEN_READ",
                            idempotencyClassification = "IDEMPOTENT"
                        )
                    )
                }
                actions.add(
                    AgentAction(
                        taskId = taskId,
                        actionType = ActionType.SAVE_NOTE,
                        parameters = mapOf("content" to (intent.searchQueryOrContent ?: "")),
                        riskLevel = RiskLevel.R0_LOCAL_READONLY,
                        idempotencyClassification = "NON_IDEMPOTENT"
                    )
                )
            }

            UserIntentType.MEMORY_SAVE -> {
                actions.add(
                    AgentAction(
                        taskId = taskId,
                        actionType = ActionType.SAVE_MEMORY,
                        parameters = mapOf("prompt" to intent.rawPrompt),
                        riskLevel = RiskLevel.R0_LOCAL_READONLY,
                        idempotencyClassification = "IDEMPOTENT"
                    )
                )
            }

            UserIntentType.LOCAL_TIME -> {
                actions.add(
                    AgentAction(
                        taskId = taskId,
                        actionType = ActionType.GET_DEVICE_TIME,
                        riskLevel = RiskLevel.R0_LOCAL_READONLY,
                        idempotencyClassification = "IDEMPOTENT"
                    )
                )
            }

            UserIntentType.LOCAL_CALCULATOR -> {
                actions.add(
                    AgentAction(
                        taskId = taskId,
                        actionType = ActionType.CALCULATE_LOCAL,
                        parameters = mapOf("expression" to (intent.searchQueryOrContent ?: "")),
                        riskLevel = RiskLevel.R0_LOCAL_READONLY,
                        idempotencyClassification = "IDEMPOTENT"
                    )
                )
            }

            UserIntentType.MEDIA_CONTROL -> {
                val op = intent.section ?: "PLAY_QUERY"
                val query = intent.searchQueryOrContent ?: ""
                val app = intent.targetAppOrEntity ?: "com.google.android.youtube"
                actions.add(
                    AgentAction(
                        taskId = taskId,
                        actionType = ActionType.CONTROL_MEDIA,
                        target = query.ifBlank { op },
                        parameters = mapOf(
                            "operation" to op,
                            "query" to query,
                            "app" to app
                        ),
                        riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE,
                        idempotencyClassification = "IDEMPOTENT"
                    )
                )
            }

            UserIntentType.GENERAL_AI_QUERY -> {
                actions.add(
                    AgentAction(
                        taskId = taskId,
                        actionType = ActionType.QUERY_LLM,
                        parameters = mapOf("prompt" to intent.rawPrompt),
                        riskLevel = RiskLevel.R0_LOCAL_READONLY,
                        idempotencyClassification = "IDEMPOTENT"
                    )
                )
            }
        }

        return AgentTask(
            taskId = taskId,
            userInstruction = intent.rawPrompt,
            resolvedIntent = intent.type.name,
            actions = actions,
            state = TaskState.CREATED
        )
    }

    /**
     * Parses LLM-generated JSON action plan array into a validated AgentTask schema.
     */
    fun planTaskFromJson(json: String, userInstruction: String): AgentTask? {
        return try {
            val jsonArray = JSONArray(json)
            if (jsonArray.length() == 0) return null

            val taskId = UUID.randomUUID().toString()
            val actions = mutableListOf<AgentAction>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val typeStr = obj.optString("actionType", "")
                val actionType = try { ActionType.valueOf(typeStr) } catch (e: Exception) { continue }
                val target = if (obj.has("target") && !obj.isNull("target")) {
                    obj.optString("target", "").takeIf { it.isNotBlank() }
                } else null

                val paramsMap = mutableMapOf<String, String>()
                val paramsObj = obj.optJSONObject("parameters")
                paramsObj?.keys()?.forEach { key ->
                    paramsObj.optString(key, "")?.let { value ->
                        paramsMap[key] = value
                    }
                }

                val risk = when (actionType) {
                    ActionType.ASK_USER_CONFIRMATION,
                    ActionType.MAKE_PHONE_CALL,
                    ActionType.SEND_SMS,
                    ActionType.MESSAGE_DRAFT_SEND -> RiskLevel.R3_EXTERNAL_COMMUNICATION
                    ActionType.SET_TEXT -> RiskLevel.R2_APPLICATION_STATE
                    ActionType.CLICK_ELEMENT,
                    ActionType.LAUNCH_APP,
                    ActionType.OPEN_SETTINGS_SECTION,
                    ActionType.TOGGLE_FLASHLIGHT -> RiskLevel.R1_LOW_IMPACT_REVERSIBLE
                    else -> RiskLevel.R0_LOCAL_READONLY
                }

                val reqCap = when (actionType) {
                    ActionType.READ_SCREEN, ActionType.CLICK_ELEMENT, ActionType.SET_TEXT,
                    ActionType.SCROLL_FORWARD, ActionType.SCROLL_BACKWARD, ActionType.NAVIGATE_BACK -> CapabilityId.ACCESSIBILITY
                    else -> null
                }

                val idempotency = when (actionType) {
                    ActionType.CLICK_ELEMENT, ActionType.SET_TEXT, ActionType.SAVE_NOTE, ActionType.ASK_USER_CONFIRMATION -> "NON_IDEMPOTENT"
                    else -> "IDEMPOTENT"
                }

                actions.add(
                    AgentAction(
                        taskId = taskId,
                        actionType = ActionType.valueOf(typeStr),
                        target = target,
                        parameters = paramsMap,
                        requiredCapability = reqCap,
                        riskLevel = risk,
                        idempotencyClassification = idempotency
                    )
                )
            }

            if (actions.isEmpty()) return null

            AgentTask(
                taskId = taskId,
                userInstruction = userInstruction,
                resolvedIntent = "LLM_STRUCTURED_PLAN",
                actions = actions,
                state = TaskState.CREATED
            )
        } catch (e: Exception) {
            null
        }
    }
}
