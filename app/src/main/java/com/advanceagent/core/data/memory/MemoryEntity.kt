package com.advanceagent.core.data.memory

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "agent_memories")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fact: String,
    val createdAt: Long = System.currentTimeMillis()
)
