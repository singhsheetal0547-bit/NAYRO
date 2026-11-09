package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entities.AppSettingsEntity
import com.example.data.local.entities.AssistantProfileEntity
import com.example.data.local.entities.AuditLogEntity
import com.example.data.local.entities.ConversationEntity
import com.example.data.local.entities.MemoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AppSettingsDao {
    @Query("SELECT * FROM app_settings WHERE `key` = :key")
    suspend fun getSetting(key: String): AppSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSetting(setting: AppSettingsEntity)
}

@Dao
interface AssistantProfileDao {
    @Query("SELECT * FROM assistant_profile WHERE id = 'default_babu'")
    fun getProfileFlow(): Flow<AssistantProfileEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateProfile(profile: AssistantProfileEntity)
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY timestamp ASC")
    fun getAllConversations(): Flow<List<ConversationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(message: ConversationEntity)

    @Query("DELETE FROM conversations")
    suspend fun clearHistory()
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM local_memory ORDER BY createdAt DESC")
    fun getAllMemories(): Flow<List<MemoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: MemoryEntity)

    @Query("DELETE FROM local_memory WHERE id = :id")
    suspend fun deleteMemory(id: Long)

    @Query("DELETE FROM local_memory")
    suspend fun clearAll()
}

@Dao
interface AuditLogDao {
    @Query("SELECT * FROM audit_log ORDER BY timestamp DESC")
    fun getAllLogs(): Flow<List<AuditLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: AuditLogEntity)
}

@Dao
interface NoteDao {
    @Query("SELECT * FROM personal_notes ORDER BY isPinned DESC, updatedAt DESC")
    fun getAllNotes(): Flow<List<com.example.data.local.entities.NoteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: com.example.data.local.entities.NoteEntity): Long

    @Query("DELETE FROM personal_notes WHERE id = :id")
    suspend fun deleteNote(id: Long)

    @Query("SELECT * FROM personal_notes WHERE title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%'")
    fun searchNotes(query: String): Flow<List<com.example.data.local.entities.NoteEntity>>

    @Query("DELETE FROM personal_notes")
    suspend fun clearAllNotes()
}

@Dao
interface TaskHistoryDao {
    @Query("SELECT * FROM task_history ORDER BY timestamp DESC")
    fun getAllTasks(): Flow<List<com.example.data.local.entities.TaskHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: com.example.data.local.entities.TaskHistoryEntity): Long

    @Query("SELECT * FROM task_history WHERE status IN ('IN_PROGRESS', 'EXECUTING', 'UNDERSTANDING') ORDER BY timestamp DESC")
    suspend fun getIncompleteTasks(): List<com.example.data.local.entities.TaskHistoryEntity>

    @Query("DELETE FROM task_history")
    suspend fun clearTaskHistory()
}
