@file:OptIn(ExperimentalMaterial3Api::class)

package org.stypox.dicio.ui.notes

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import org.stypox.dicio.notes.NotesExport

/**
 * Notes list. Notes live on the phone. If the user picks a folder (for example an Obsidian vault),
 * every new note is written there automatically as a Markdown file — nothing is sent anywhere.
 */
@Composable
fun NotesScreen(navigationIcon: @Composable () -> Unit) {
    val context = LocalContext.current
    var notes by remember { mutableStateOf(Notes.all(context).reversed()) }
    var draft by remember { mutableStateOf("") }
    var target by remember { mutableStateOf(NotesExport.target(context)) }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (t: Throwable) {
                // not fatal: writing still works for this session
            }
            val result = NotesExport.exportAll(context, uri)
            target = NotesExport.target(context)
            Extras.notify(
                context, "Экспорт заметок",
                if (result == null) "Не удалось записать в выбранную папку"
                else "Записано файлов: ${result.written} → «${result.folder}». Новые заметки будут появляться там же",
            )
        }
    }

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

            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Куда сохранять", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    if (target == null) {
                        Text(
                            "Заметки хранятся на телефоне. Выберите папку — например, хранилище " +
                                "Obsidian — и новые заметки будут сразу появляться там файлами. " +
                                "Никуда в интернет они не уходят.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            "Папка выбрана: новые заметки пишутся туда автоматически, файлами .md. " +
                                "Всё остаётся на телефоне.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { folderPicker.launch(null) }) {
                            Text(if (target == null) "Экспорт в Obsidian" else "Сменить папку")
                        }
                        if (target != null) {
                            Spacer(Modifier.padding(horizontal = 6.dp))
                            TextButton(onClick = {
                                NotesExport.setTarget(context, null)
                                target = null
                                Extras.notify(context, "Заметки", "Автоэкспорт выключен")
                            }) { Text("Выключить") }
                        }
                    }
                }
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
