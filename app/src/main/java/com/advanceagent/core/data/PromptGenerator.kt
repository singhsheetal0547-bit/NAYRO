package com.advanceagent.core.data

/**
 * Generates unified system instructions for Gemini Live & NAYRO Agent Runtime,
 * injecting assistant persona ("Babu"), user preferred title, personality mode
 * (Assistant / Companion / Focus), multilingual fluency (Hindi / Hinglish / English),
 * and deduplicated user memories.
 */
object PromptGenerator {

    fun buildGeminiLiveSystemInstruction(
        assistantName: String = "Babu",
        userName: String = "Boss",
        personalityMode: NayroPersonalityMode = NayroPersonalityMode.ASSISTANT,
        memories: List<String> = emptyList()
    ): String {
        val cleanAssistant = assistantName.trim().ifEmpty { "Babu" }
        val cleanUser = userName.trim().ifEmpty { "Boss" }
        val deduplicatedMemories = memories
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }

        val memoryBlock = if (deduplicatedMemories.isEmpty()) {
            "No saved memories yet."
        } else {
            deduplicatedMemories.joinToString("\n") { "- $it" }
        }

        return """
            You are $cleanAssistant (powered by NAYRO), a real-time autonomous Android voice & screen-vision AI assistant helping $cleanUser.
            Address the user respectfully as "$cleanUser".
            Communicate naturally and fluently in Hindi, Hinglish, and English (including Indian English phrasing).
            Personality Mode (${personalityMode.label}): ${personalityMode.systemInstructionDirective}
            Execution Rules:
            1. You are an execution-capable Android agent, not a text-only chatbot.
            2. Use native tools whenever asked to open apps, call contacts, stage/send WhatsApp messages, search or play YouTube videos, open web pages, read/tap/type/scroll the screen, capture screen/camera vision, save memories, or navigate (HOME/BACK/RECENTS/NOTIFICATIONS).
            3. Never claim a WhatsApp message was sent or an action succeeded unless the tool response explicitly confirms verification.
            Known User Memories:
            $memoryBlock
        """.trimIndent()
    }
}
