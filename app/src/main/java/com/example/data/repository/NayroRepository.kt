package com.example.data.repository

import com.example.data.local.NayroDatabase
import com.example.data.local.entities.AssistantProfileEntity
import com.example.data.local.entities.AuditLogEntity
import com.example.data.local.entities.ConversationEntity
import com.example.data.local.entities.MemoryEntity
import com.example.data.local.entities.NoteEntity
import com.example.data.local.entities.TaskHistoryEntity
import kotlinx.coroutines.flow.Flow

class NayroRepository(private val database: NayroDatabase) {

    val assistantProfile: Flow<AssistantProfileEntity?> = database.assistantProfileDao().getProfileFlow()
    val conversations: Flow<List<ConversationEntity>> = database.conversationDao().getAllConversations()
    val memories: Flow<List<MemoryEntity>> = database.memoryDao().getAllMemories()
    val auditLogs: Flow<List<AuditLogEntity>> = database.auditLogDao().getAllLogs()
    val notes: Flow<List<NoteEntity>> = database.noteDao().getAllNotes()
    val taskHistory: Flow<List<TaskHistoryEntity>> = database.taskHistoryDao().getAllTasks()

    suspend fun updateProfile(profile: AssistantProfileEntity) {
        database.assistantProfileDao().insertOrUpdateProfile(profile)
    }

    suspend fun addConversationMessage(message: ConversationEntity) {
        database.conversationDao().insertConversation(message)
    }

    suspend fun clearHistory() {
        database.conversationDao().clearHistory()
    }

    suspend fun addMemory(memory: MemoryEntity) {
        database.memoryDao().insertMemory(memory)
    }

    suspend fun deleteMemory(id: Long) {
        database.memoryDao().deleteMemory(id)
    }

    suspend fun clearMemories() {
        database.memoryDao().clearAll()
    }

    suspend fun saveNote(note: NoteEntity): Long {
        return database.noteDao().insertNote(note)
    }

    suspend fun deleteNote(id: Long) {
        database.noteDao().deleteNote(id)
    }

    fun searchNotes(query: String): Flow<List<NoteEntity>> {
        return database.noteDao().searchNotes(query)
    }

    suspend fun recordTaskExecution(task: TaskHistoryEntity): Long {
        return database.taskHistoryDao().insertTask(task)
    }

    suspend fun getIncompleteTasks(): List<TaskHistoryEntity> {
        return database.taskHistoryDao().getIncompleteTasks()
    }

    suspend fun clearTaskHistory() {
        database.taskHistoryDao().clearTaskHistory()
    }

    suspend fun logAuditAction(actionType: String, target: String, riskLevel: String, outcome: String, details: String) {
        database.auditLogDao().insertLog(
            AuditLogEntity(
                actionType = actionType,
                target = target,
                riskLevel = riskLevel,
                outcome = outcome,
                details = details
            )
        )
    }
}
