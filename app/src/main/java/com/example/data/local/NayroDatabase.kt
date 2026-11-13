package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

        /**
         * Phase 7 — Non-destructive migration from version 1 to 2:
         * Preserves existing conversations, memories, settings, and notes while ensuring
         * personal_notes and task_history tables exist.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `personal_notes` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `category` TEXT NOT NULL DEFAULT 'General',
                        `isPinned` INTEGER NOT NULL DEFAULT 0,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `task_history` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `taskId` TEXT NOT NULL,
                        `userInstruction` TEXT NOT NULL,
                        `resolvedIntent` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `stepsCount` INTEGER NOT NULL,
                        `resultSummary` TEXT NOT NULL,
                        `durationMs` INTEGER NOT NULL DEFAULT 0,
                        `timestamp` INTEGER NOT NULL,
                        `conversationContext` TEXT NOT NULL DEFAULT '',
                        `planJson` TEXT NOT NULL DEFAULT '',
                        `currentStepIndex` INTEGER NOT NULL DEFAULT 0,
                        `completedStepsJson` TEXT NOT NULL DEFAULT '[]',
                        `pendingStepsJson` TEXT NOT NULL DEFAULT '[]',
                        `toolNames` TEXT NOT NULL DEFAULT '',
                        `toolArgumentsJson` TEXT NOT NULL DEFAULT '{}',
                        `toolResultsJson` TEXT NOT NULL DEFAULT '[]',
                        `verificationEvidenceJson` TEXT NOT NULL DEFAULT '',
                        `retryCount` INTEGER NOT NULL DEFAULT 0,
                        `failureReason` TEXT NOT NULL DEFAULT '',
                        `recoveryMetadata` TEXT NOT NULL DEFAULT '',
                        `updatedAt` INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * Phase 7 — Non-destructive migration from version 2 to 3:
         * Adds Section 17 durable task checkpointing columns to `task_history` if upgrading from v2
         * without dropping or erasing any existing rows.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val alterStatements = listOf(
                    "ALTER TABLE `task_history` ADD COLUMN `conversationContext` TEXT NOT NULL DEFAULT ''",
                    "ALTER TABLE `task_history` ADD COLUMN `planJson` TEXT NOT NULL DEFAULT ''",
                    "ALTER TABLE `task_history` ADD COLUMN `currentStepIndex` INTEGER NOT NULL DEFAULT 0",
                    "ALTER TABLE `task_history` ADD COLUMN `completedStepsJson` TEXT NOT NULL DEFAULT '[]'",
                    "ALTER TABLE `task_history` ADD COLUMN `pendingStepsJson` TEXT NOT NULL DEFAULT '[]'",
                    "ALTER TABLE `task_history` ADD COLUMN `toolNames` TEXT NOT NULL DEFAULT ''",
                    "ALTER TABLE `task_history` ADD COLUMN `toolArgumentsJson` TEXT NOT NULL DEFAULT '{}'",
                    "ALTER TABLE `task_history` ADD COLUMN `toolResultsJson` TEXT NOT NULL DEFAULT '[]'",
                    "ALTER TABLE `task_history` ADD COLUMN `verificationEvidenceJson` TEXT NOT NULL DEFAULT ''",
                    "ALTER TABLE `task_history` ADD COLUMN `retryCount` INTEGER NOT NULL DEFAULT 0",
                    "ALTER TABLE `task_history` ADD COLUMN `failureReason` TEXT NOT NULL DEFAULT ''",
                    "ALTER TABLE `task_history` ADD COLUMN `recoveryMetadata` TEXT NOT NULL DEFAULT ''",
                    "ALTER TABLE `task_history` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0"
                )
                for (sql in alterStatements) {
                    try {
                        db.execSQL(sql)
                    } catch (_: Exception) {
                        // Column already exists; safe no-op
                    }
                }
            }
        }

        fun getDatabase(context: Context): NayroDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    NayroDatabase::class.java,
                    "nayro_local_db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
