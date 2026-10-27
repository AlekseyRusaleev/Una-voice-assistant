@file:OptIn(ExperimentalMaterial3Api::class)

package org.stypox.dicio.ui.notes

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.stypox.dicio.eval.Extras
import org.stypox.dicio.notes.Notes
import org.stypox.dicio.notes.NotesSync

/**
 * Notes list: the same notes the assistant creates by voice, with manual adding and deleting.
 */
@Composable
fun NotesScreen(navigationIcon: @Composable () -> Unit) {
    val context = LocalContext.current
    var notes by remember { mutableStateOf(Notes.all(context).reversed()) }
    var draft by remember { mutableStateOf("") }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Заметки") }, navigationIcon = navigationIcon) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    singleLine = false,
                    placeholder = { Text("Новая заметка") },
                )
                Spacer(Modifier.padding(horizontal = 4.dp))
                Button(
                    enabled = draft.trim().length >= 2,
                    onClick = {
                        Notes.add(context, draft.trim())
                        draft = ""
                        notes = Notes.all(context).reversed()
                    },
                ) { Text("Добавить") }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = {
                    NotesSync.push(context) { sent ->
                        Extras.notify(
                            context, "Заметки",
                            when {
                                sent < 0 -> "Не удалось отправить: нет связи или токена"
                                sent == 0 -> "Всё уже отправлено"
                                else -> "Отправлено заметок: $sent"
                            },
                        )
                    }
                }) { Text("Отправить на сервер") }
            }

            if (notes.isEmpty()) {
                Text(
                    "Пока пусто. Скажите «Юна, запиши в заметки …» или добавьте вручную.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(24.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(notes, key = { it.id }) { note ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = note.text,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = {
                                Notes.remove(context, note.id)
                                notes = Notes.all(context).reversed()
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = "Удалить")
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
