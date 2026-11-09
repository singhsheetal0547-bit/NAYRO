package com.example.data.repository

import com.example.data.local.NayroDatabase
import com.example.data.local.entities.AssistantProfileEntity
import com.example.data.local.entities.AuditLogEntity
import com.example.data.local.entities.ConversationEntity
import com.example.data.local.entities.MemoryEntity
import kotlinx.coroutines.flow.Flow

class NayroRepository(private val database: NayroDatabase) {

    val assistantProfile: Flow<AssistantProfileEntity?> = database.assistantProfileDao().getProfileFlow()
    val conversations: Flow<List<ConversationEntity>> = database.conversationDao().getAllConversations()
    val memories: Flow<List<MemoryEntity>> = database.memoryDao().getAllMemories()
    val auditLogs: Flow<List<AuditLogEntity>> = database.auditLogDao().getAllLogs()

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
