package com.advanceagent.core.data.chat

import com.example.data.local.entities.ConversationEntity
import com.example.data.repository.NayroRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class ChatTurn(
    val id: String = UUID.randomUUID().toString(),
    val role: String, // "user" | "assistant" | "tool"
    val text: String,
    val toolBadge: String? = null,
    val isVerified: Boolean = true,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Unified Chat History Repository for Gemini Live Audio Transcription (inputAudioTranscription &
 * outputAudioTranscription) and Tool Execution Badges.
 * Bridges directly into NAYRO's [NayroRepository] when attached so live voice turns persist in Room.
 */
object ChatRepository {

    private val _turns = MutableStateFlow<List<ChatTurn>>(emptyList())
    val turns: StateFlow<List<ChatTurn>> = _turns.asStateFlow()

    @Volatile
    var nayroRepository: NayroRepository? = null

    private val ioScope = CoroutineScope(Dispatchers.IO)

    @Synchronized
    fun recordUserTranscript(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val current = _turns.value.toMutableList()
        current.add(ChatTurn(role = "user", text = clean))
        _turns.value = current.takeLast(120)

        nayroRepository?.let { repo ->
            ioScope.launch {
                try {
                    repo.addConversationMessage(ConversationEntity(sender = "user", content = clean))
                } catch (_: Exception) {
                }
            }
        }
    }

    @Synchronized
    fun recordAssistantTranscript(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val current = _turns.value.toMutableList()
        current.add(ChatTurn(role = "assistant", text = clean))
        _turns.value = current.takeLast(120)

        nayroRepository?.let { repo ->
            ioScope.launch {
                try {
                    repo.addConversationMessage(ConversationEntity(sender = "assistant", content = clean))
                } catch (_: Exception) {
                }
            }
        }
    }

    @Synchronized
    fun recordToolExecution(toolName: String, summary: String, isVerified: Boolean) {
        val current = _turns.value.toMutableList()
        current.add(
            ChatTurn(
                role = "tool",
                text = summary.trim(),
                toolBadge = toolName,
                isVerified = isVerified
            )
        )
        _turns.value = current.takeLast(120)
    }

    @Synchronized
    fun clearAll() {
        _turns.value = emptyList()
    }
}
