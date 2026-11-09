package com.example.core.memory

import com.example.data.local.entities.NoteEntity
import com.example.data.repository.NayroRepository
import kotlinx.coroutines.flow.Flow

/**
 * Manages user notebook notes: creation, updates, pinning, search, and deletion.
 */
class NotebookManager(private val repository: NayroRepository) {

    val notes: Flow<List<NoteEntity>> = repository.notes

    suspend fun createNote(title: String, content: String, category: String = "General"): Long {
        val sanitizedContent = MemoryPrivacyGuard.sanitizeContent(content)
        val note = NoteEntity(
            title = title.trim(),
            content = sanitizedContent.trim(),
            category = category.trim(),
            isPinned = false,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        return repository.saveNote(note)
    }

    suspend fun updateNote(note: NoteEntity): Long {
        val updated = note.copy(
            title = note.title.trim(),
            content = MemoryPrivacyGuard.sanitizeContent(note.content).trim(),
            updatedAt = System.currentTimeMillis()
        )
        return repository.saveNote(updated)
    }

    suspend fun togglePin(note: NoteEntity): Long {
        val updated = note.copy(
            isPinned = !note.isPinned,
            updatedAt = System.currentTimeMillis()
        )
        return repository.saveNote(updated)
    }

    suspend fun deleteNote(id: Long) {
        repository.deleteNote(id)
    }

    fun searchNotes(query: String): Flow<List<NoteEntity>> {
        return repository.searchNotes(query)
    }
}
