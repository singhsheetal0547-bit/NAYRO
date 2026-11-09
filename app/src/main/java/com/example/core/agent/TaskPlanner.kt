package com.example.core.agent

import com.example.core.capability.CapabilityId
import java.util.UUID

object TaskPlanner {

    fun planTask(intent: ResolvedIntent): AgentTask {
        val taskId = UUID.randomUUID().toString()
        val actions = mutableListOf<AgentAction>()

        when (intent.type) {
            UserIntentType.APP_LAUNCH -> {
                actions.add(
                    AgentAction(
                        taskId = taskId,
                        actionType = ActionType.LAUNCH_APP,
                        target = intent.targetAppOrEntity,
                        riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE,
                        verificationStrategy = "VERIFY_FOREGROUND_PACKAGE"
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
                        verificationStrategy = "VERIFY_SCREEN_READ"
                    )
                )
            }

            UserIntentType.YOUTUBE_SEARCH -> {
                actions.add(
                    AgentAction(
                        taskId = taskId,
                        actionType = ActionType.LAUNCH_APP,
                        target = "com.google.android.youtube",
                        riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE
                    )
                )
                actions.add(
                    AgentAction(
                        taskId = taskId,
                        actionType = ActionType.CLICK_ELEMENT,
                        target = "Search",
                        requiredCapability = CapabilityId.ACCESSIBILITY,
                        riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE
                    )
                )
                actions.add(
                    AgentAction(
                        taskId = taskId,
                        actionType = ActionType.SET_TEXT,
                        parameters = mapOf("query" to (intent.searchQueryOrContent ?: "Android")),
                        requiredCapability = CapabilityId.ACCESSIBILITY,
                        riskLevel = RiskLevel.R2_APPLICATION_STATE
                    )
                )
            }

            UserIntentType.SETTINGS_NAVIGATE -> {
                actions.add(
                    AgentAction(
                        taskId = taskId,
                        actionType = ActionType.OPEN_SETTINGS_SECTION,
                        target = intent.section ?: "general",
                        parameters = mapOf("section" to (intent.section ?: "general")),
                        riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE
                    )
                )
                actions.add(
                    AgentAction(
                        taskId = taskId,
                        actionType = ActionType.READ_SCREEN,
                        requiredCapability = CapabilityId.ACCESSIBILITY,
                        riskLevel = RiskLevel.R0_LOCAL_READONLY
                    )
                )
            }

            UserIntentType.MESSAGE_DRAFT -> {
                actions.add(
                    AgentAction(
                        taskId = taskId,
                        actionType = ActionType.LAUNCH_APP,
                        target = "com.whatsapp",
                        riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE
                    )
                )
                actions.add(
                    AgentAction(
                        taskId = taskId,
                        actionType = ActionType.CLICK_ELEMENT,
                        target = intent.recipient ?: "Contact",
                        requiredCapability = CapabilityId.ACCESSIBILITY,
                        riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE
                    )
                )
                actions.add(
                    AgentAction(
                        taskId = taskId,
                        actionType = ActionType.SET_TEXT,
                        parameters = mapOf("text" to (intent.searchQueryOrContent ?: "")),
                        requiredCapability = CapabilityId.ACCESSIBILITY,
                        riskLevel = RiskLevel.R2_APPLICATION_STATE
                    )
                )
                // Stop before sending and require explicit user confirmation
                actions.add(
                    AgentAction(
                        taskId = taskId,
                        actionType = ActionType.ASK_USER_CONFIRMATION,
                        target = intent.recipient,
                        parameters = mapOf(
                            "recipient" to (intent.recipient ?: "Unknown"),
                            "draft" to (intent.searchQueryOrContent ?: "")
                        ),
                        riskLevel = RiskLevel.R3_EXTERNAL_COMMUNICATION
                    )
                )
            }

            UserIntentType.NOTE_CREATE -> {
                actions.add(
                    AgentAction(
                        taskId = taskId,
                        actionType = ActionType.SAVE_NOTE,
                        parameters = mapOf("content" to (intent.searchQueryOrContent ?: "")),
                        riskLevel = RiskLevel.R0_LOCAL_READONLY
                    )
                )
            }

            UserIntentType.MEMORY_SAVE -> {
                actions.add(
                    AgentAction(
                        taskId = taskId,
                        actionType = ActionType.SAVE_MEMORY,
                        parameters = mapOf("prompt" to intent.rawPrompt),
                        riskLevel = RiskLevel.R0_LOCAL_READONLY
                    )
                )
            }

            UserIntentType.LOCAL_TIME -> {
                actions.add(
                    AgentAction(
                        taskId = taskId,
                        actionType = ActionType.GET_DEVICE_TIME,
                        riskLevel = RiskLevel.R0_LOCAL_READONLY
                    )
                )
            }

            UserIntentType.LOCAL_CALCULATOR -> {
                actions.add(
                    AgentAction(
                        taskId = taskId,
                        actionType = ActionType.CALCULATE_LOCAL,
                        parameters = mapOf("expression" to (intent.searchQueryOrContent ?: "")),
                        riskLevel = RiskLevel.R0_LOCAL_READONLY
                    )
                )
            }

            UserIntentType.GENERAL_AI_QUERY -> {
                actions.add(
                    AgentAction(
                        taskId = taskId,
                        actionType = ActionType.QUERY_LLM,
                        parameters = mapOf("prompt" to intent.rawPrompt),
                        riskLevel = RiskLevel.R0_LOCAL_READONLY
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
}
