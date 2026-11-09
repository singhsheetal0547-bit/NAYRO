package com.example.ui.memory

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.local.entities.MemoryEntity
import com.example.data.local.entities.NoteEntity
import com.example.ui.theme.Coral
import com.example.ui.theme.DeepGreen
import com.example.ui.theme.ForestGreen
import com.example.ui.theme.SoftSage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MemoryScreen(
    memories: List<MemoryEntity> = emptyList(),
    notes: List<NoteEntity> = emptyList(),
    onDeleteMemory: (Long) -> Unit = {},
    onCreateNote: (String, String, String) -> Unit = { _, _, _ -> },
    onDeleteNote: (Long) -> Unit = {},
    onTogglePinNote: (NoteEntity) -> Unit = {}
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var showCreateNoteDialog by remember { mutableStateOf(false) }
    var noteSearchQuery by remember { mutableStateOf("") }

    val tabs = listOf("Preferences & Memory", "Personal Notebook")

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp)
        ) {
            Text(
                text = "Memory & Notebook",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "Private local context, preferences, and personal notes",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = ForestGreen,
                contentColor = SoftSage,
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = Coral
                    )
                },
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .fillMaxWidth()
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selectedTab == index) Coral else SoftSage
                            )
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (selectedTab == 0) {
                // Memories Tab
                if (memories.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No saved memories. Say \"Yaad rakho ki...\" to save preferences.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        items(memories) { memory ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .testTag("memory_card_${memory.id}")
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "[${memory.category}] ${memory.keyName}",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = ForestGreen
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = memory.memoryValue,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    IconButton(
                                        onClick = { onDeleteMemory(memory.id) },
                                        modifier = Modifier.testTag("delete_memory_${memory.id}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Delete memory item",
                                            tint = Coral
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // Personal Notebook Tab
                OutlinedTextField(
                    value = noteSearchQuery,
                    onValueChange = { noteSearchQuery = it },
                    placeholder = { Text("Search notes...", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = ForestGreen) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("notes_search_input")
                )

                Spacer(modifier = Modifier.height(8.dp))

                val filteredNotes = if (noteSearchQuery.isBlank()) {
                    notes
                } else {
                    notes.filter {
                        it.title.contains(noteSearchQuery, ignoreCase = true) ||
                                it.content.contains(noteSearchQuery, ignoreCase = true)
                    }
                }

                if (filteredNotes.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (noteSearchQuery.isBlank()) "No personal notes yet. Tap + to create one." else "No notes matching '$noteSearchQuery'",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        items(filteredNotes) { note ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .testTag("note_card_${note.id}")
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (note.isPinned) {
                                                Icon(
                                                    imageVector = Icons.Default.PushPin,
                                                    contentDescription = "Pinned Note",
                                                    tint = Coral,
                                                    modifier = Modifier.padding(end = 4.dp)
                                                )
                                            }
                                            Text(
                                                text = note.title,
                                                style = MaterialTheme.typography.titleMedium,
                                                color = ForestGreen
                                            )
                                        }

                                        Row {
                                            IconButton(onClick = { onTogglePinNote(note) }) {
                                                Icon(
                                                    imageVector = Icons.Default.PushPin,
                                                    contentDescription = "Pin toggle",
                                                    tint = if (note.isPinned) Coral else MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            IconButton(onClick = { onDeleteNote(note.id) }) {
                                                Icon(
                                                    imageVector = Icons.Default.Delete,
                                                    contentDescription = "Delete note",
                                                    tint = Coral
                                                )
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(4.dp))

                                    Text(
                                        text = note.content,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )

                                    Spacer(modifier = Modifier.height(6.dp))

                                    val dateFormat = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
                                    Text(
                                        text = "Updated ${dateFormat.format(Date(note.updatedAt))} • ${note.category}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // FAB to add Note when in Notebook tab
        if (selectedTab == 1) {
            FloatingActionButton(
                onClick = { showCreateNoteDialog = true },
                containerColor = Coral,
                contentColor = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(24.dp)
                    .testTag("fab_create_note")
            ) {
                Icon(Icons.Default.Add, contentDescription = "Create note")
            }
        }
    }

    if (showCreateNoteDialog) {
        var noteTitle by remember { mutableStateOf("") }
        var noteContent by remember { mutableStateOf("") }
        var noteCategory by remember { mutableStateOf("General") }

        AlertDialog(
            onDismissRequest = { showCreateNoteDialog = false },
            title = { Text("Create Personal Note", color = ForestGreen) },
            text = {
                Column {
                    OutlinedTextField(
                        value = noteTitle,
                        onValueChange = { noteTitle = it },
                        label = { Text("Title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("input_note_title")
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = noteContent,
                        onValueChange = { noteContent = it },
                        label = { Text("Content") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth().testTag("input_note_content")
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = noteCategory,
                        onValueChange = { noteCategory = it },
                        label = { Text("Category") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("input_note_category")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (noteContent.isNotBlank()) {
                            onCreateNote(
                                noteTitle.ifBlank { "Note" },
                                noteContent,
                                noteCategory.ifBlank { "General" }
                            )
                            showCreateNoteDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ForestGreen),
                    modifier = Modifier.testTag("btn_save_note")
                ) {
                    Text("Save Note")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showCreateNoteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
