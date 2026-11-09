package com.example.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_settings")
data class AppSettingsEntity(
    @PrimaryKey val key: String,
    val valueJson: String,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "assistant_profile")
data class AssistantProfileEntity(
    @PrimaryKey val id: String = "default_babu",
    val personaName: String = "Babu",
    val language: String = "Hinglish",
    val tone: String = "Friendly & Professional",
    val isTtsEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sender: String, // "user" or "babu"
    val content: String,
    val audioUrl: String? = null,
    val toolCallProposed: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "local_memory")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String,
    val keyName: String,
    val memoryValue: String,
    val userConfirmed: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "audit_log")
data class AuditLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val actionType: String,
    val target: String,
    val riskLevel: String,
    val outcome: String,
    val details: String,
    val timestamp: Long = System.currentTimeMillis()
)
