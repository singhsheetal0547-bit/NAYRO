package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.local.dao.AppSettingsDao
import com.example.data.local.dao.AssistantProfileDao
import com.example.data.local.dao.AuditLogDao
import com.example.data.local.dao.ConversationDao
import com.example.data.local.dao.MemoryDao
import com.example.data.local.dao.NoteDao
import com.example.data.local.dao.TaskHistoryDao
import com.example.data.local.entities.AppSettingsEntity
import com.example.data.local.entities.AssistantProfileEntity
import com.example.data.local.entities.AuditLogEntity
import com.example.data.local.entities.ConversationEntity
import com.example.data.local.entities.MemoryEntity
import com.example.data.local.entities.NoteEntity
import com.example.data.local.entities.TaskHistoryEntity

@Database(
    entities = [
        AppSettingsEntity::class,
        AssistantProfileEntity::class,
        ConversationEntity::class,
        MemoryEntity::class,
        AuditLogEntity::class,
        NoteEntity::class,
        TaskHistoryEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class NayroDatabase : RoomDatabase() {

    abstract fun appSettingsDao(): AppSettingsDao
    abstract fun assistantProfileDao(): AssistantProfileDao
    abstract fun conversationDao(): ConversationDao
    abstract fun memoryDao(): MemoryDao
    abstract fun auditLogDao(): AuditLogDao
    abstract fun noteDao(): NoteDao
    abstract fun taskHistoryDao(): TaskHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: NayroDatabase? = null

        fun getDatabase(context: Context): NayroDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    NayroDatabase::class.java,
                    "nayro_local_db"
                ).fallbackToDestructiveMigration(true).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
