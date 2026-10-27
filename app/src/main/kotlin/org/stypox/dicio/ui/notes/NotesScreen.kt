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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import org.stypox.dicio.notes.NotesSync

/**
 * Notes list. Notes live on the phone; the user decides whether to copy them anywhere else:
 * export as Markdown files into their own folder (Obsidian and friends), or upload to the
 * assistant server — the latter is off by default and asks for confirmation.
 */
@Composable
fun NotesScreen(navigationIcon: @Composable () -> Unit) {
    val context = LocalContext.current
    var notes by remember { mutableStateOf(Notes.all(context).reversed()) }
    var draft by remember { mutableStateOf("") }
    var allowServer by remember { mutableStateOf(NotesSync.isServerAllowed(context)) }
    var confirmUpload by remember { mutableStateOf(false) }

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
                // not fatal: writing works within this session even without a persisted grant
            }
            val result = NotesExport.exportAll(context, uri)
            Extras.notify(
                context, "Экспорт заметок",
                if (result == null) "Не удалось записать в выбранную папку"
                else "Записано файлов: ${result.written} → «${result.folder}»",
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
                    Text(
                        "Заметки хранятся на телефоне. Можно выгрузить их файлами в свою папку — " +
                            "например, в хранилище Obsidian.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { folderPicker.launch(null) }) {
                            Text("Экспорт в Obsidian")
                        }
                        Spacer(Modifier.padding(horizontal = 6.dp))
                        TextButton(onClick = { confirmUpload = true }) {
                            Text("На сервер")
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Разрешить сервер", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Отправлять заметки на сервер помощника (по умолчанию выключено)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = allowServer,
                            onCheckedChange = {
                                allowServer = it
                                NotesSync.setServerAllowed(context, it)
                            },
                        )
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

    if (confirmUpload) {
        AlertDialog(
            onDismissRequest = { confirmUpload = false },
            title = { Text("Отправить заметки на сервер?") },
            text = {
                Text(
                    "Заметки уйдут на сервер помощника. Обычно это не нужно: их можно выгрузить " +
                        "файлами в свою папку (Obsidian), и тогда они останутся только у вас.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmUpload = false
                    if (!allowServer) {
                        allowServer = true
                        NotesSync.setServerAllowed(context, true)
                    }
                    NotesSync.push(context) { sent ->
                        Extras.notify(
                            context, "Заметки",
                            when {
                                sent == -2 -> "Отправка на сервер выключена"
                                sent < 0 -> "Не удалось отправить: нет связи или токена"
                                sent == 0 -> "Всё уже отправлено"
                                else -> "Отправлено заметок: $sent"
                            },
                        )
                    }
                }) { Text("Отправить") }
            },
            dismissButton = {
                TextButton(onClick = { confirmUpload = false }) { Text("Отмена") }
            },
        )
    }
}
