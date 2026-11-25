package com.advanceagent.core.data.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface MemoryDao {
    @Insert
    suspend fun insert(memory: MemoryEntity): Long

    @Query("SELECT * FROM agent_memories ORDER BY createdAt DESC LIMIT 30")
    suspend fun getRecentMemories(): List<MemoryEntity>

    @Query("DELETE FROM agent_memories")
    suspend fun clearAll()
}
