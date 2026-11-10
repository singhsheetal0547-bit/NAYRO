package com.example.core.skills

import com.example.core.agent.ActionType

data class SkillDefinition(
    val skillId: String,
    val name: String,
    val description: String,
    val version: String = "1.0",
    val requiredActionTypes: List<ActionType>,
    val systemInstructions: String,
    val isEnabled: Boolean = true,
    val isCustomUserSkill: Boolean = false
)

object BuiltInSkills {
    val RESEARCH_SKILL = SkillDefinition(
        skillId = "skill_research_agent",
        name = "Web Research & Synthesizer",
        description = "Performs multi-step web searches, fetches article content, synthesizes findings, and saves research reports.",
        requiredActionTypes = listOf(ActionType.WEB_SEARCH, ActionType.FETCH_WEB_PAGE, ActionType.FILE_WRITE_TEXT),
        systemInstructions = "Break down the research topic into search terms, fetch top article contents, cross-verify claims, synthesize findings, and save report in workspace."
    )

    val WHATSAPP_WORKFLOW_SKILL = SkillDefinition(
        skillId = "skill_whatsapp_workflow",
        name = "WhatsApp Communication Assistant",
        description = "Handles contact resolution, message drafting, R3 risk confirmation dialogs, and post-send screen state verification.",
        requiredActionTypes = listOf(ActionType.LAUNCH_APP, ActionType.CLICK_ELEMENT, ActionType.SET_TEXT, ActionType.ASK_USER_CONFIRMATION),
        systemInstructions = "Open WhatsApp, locate recipient, compose message draft, ask user confirmation, execute click send, and verify chat tree output."
    )

    val CODING_WORKSPACE_SKILL = SkillDefinition(
        skillId = "skill_coding_workspace",
        name = "Coding Workspace & Patch Inspector",
        description = "Reads workspace code files, diagnoses syntax/logic errors, generates code patches, and updates source files.",
        requiredActionTypes = listOf(ActionType.CODE_INSPECT_FILE, ActionType.CODE_EDIT_FILE, ActionType.FILE_WRITE_TEXT),
        systemInstructions = "Inspect code file contents, isolate root cause of syntax or logic issue, generate clean Kotlin/Java patch, and save update."
    )

    val DOCUMENT_ANALYSIS_SKILL = SkillDefinition(
        skillId = "skill_document_analysis",
        name = "Document Summarizer & Extractor",
        description = "Reads workspace text documents or PDF files, extracts key points, generates executive summaries, and logs notes.",
        requiredActionTypes = listOf(ActionType.FILE_READ_TEXT, ActionType.SAVE_NOTE),
        systemInstructions = "Extract text contents from target document, analyze core takeaways, format concise bullet points, and save note."
    )

    val ANDROID_AUTOMATION_SKILL = SkillDefinition(
        skillId = "skill_android_automation",
        name = "Android UI Automation Agent",
        description = "Navigates installed Android applications, reads screen node hierarchy, and performs automated touch or scroll gestures.",
        requiredActionTypes = listOf(ActionType.LAUNCH_APP, ActionType.READ_SCREEN, ActionType.CLICK_ELEMENT, ActionType.SET_TEXT, ActionType.SCROLL_FORWARD),
        systemInstructions = "Inspect accessibility node tree of current app, locate target interactive element, perform tap or scroll, and verify state transition."
    )

    val ALL_BUILTIN = listOf(
        RESEARCH_SKILL,
        WHATSAPP_WORKFLOW_SKILL,
        CODING_WORKSPACE_SKILL,
        DOCUMENT_ANALYSIS_SKILL,
        ANDROID_AUTOMATION_SKILL
    )
}

class SkillManager {

    private val activeSkills = mutableMapOf<String, SkillDefinition>()

    init {
        BuiltInSkills.ALL_BUILTIN.forEach { activeSkills[it.skillId] = it }
    }

    fun getAllSkills(): List<SkillDefinition> = activeSkills.values.toList()

    fun getSkill(skillId: String): SkillDefinition? = activeSkills[skillId]

    fun registerCustomSkill(skill: SkillDefinition) {
        activeSkills[skill.skillId] = skill
    }

    fun toggleSkill(skillId: String, enabled: Boolean) {
        activeSkills[skillId]?.let {
            activeSkills[skillId] = it.copy(isEnabled = enabled)
        }
    }
}
