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
