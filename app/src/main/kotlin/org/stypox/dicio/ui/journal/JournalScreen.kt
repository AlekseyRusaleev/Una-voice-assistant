@file:OptIn(ExperimentalMaterial3Api::class)

package org.stypox.dicio.ui.journal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.stypox.dicio.BuildConfig
import org.stypox.dicio.eval.Journal
import org.stypox.dicio.eval.Uplink
import org.stypox.dicio.io.input.gigaam.GigaAmModel
import org.stypox.dicio.io.wake.WakeStats
import org.stypox.dicio.notes.NotesSync
import org.stypox.dicio.update.Updater

/**
 * Shows the last recognised phrases together with what happened to them, so that a wrong command
 * can be diagnosed instead of guessed at. The accurate recognition model status and download
 * button live here too, because this is the screen opened when something is unclear.
 */
@Composable
fun JournalScreen(navigationIcon: @Composable () -> Unit) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf(Journal.entries(context)) }
    var model by remember { mutableStateOf(GigaAmModel.status(context)) }
    var triggers by remember { mutableStateOf(WakeStats.triggers(context)) }
    var rejected by remember { mutableStateOf(WakeStats.rejected(context)) }
    var commands by remember { mutableStateOf(WakeStats.commands(context)) }
    var nearMisses by remember { mutableStateOf(WakeStats.nearMisses(context)) }
    var strict by remember { mutableStateOf(WakeStats.isStrict(context)) }
    var uplink by remember { mutableStateOf(Uplink.isEnabled(context)) }
    var showConsent by remember { mutableStateOf(false) }
    var notesServer by remember { mutableStateOf(NotesSync.isServerAllowed(context)) }

    LaunchedEffect(Unit) {
        while (true) {
            model = GigaAmModel.status(context)
            triggers = WakeStats.triggers(context)
            rejected = WakeStats.rejected(context)
            commands = WakeStats.commands(context)
            nearMisses = WakeStats.nearMisses(context)
            delay(1500)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Что я услышала") },
                navigationIcon = navigationIcon,
                actions = {
                    IconButton(onClick = {
                        Journal.clear(context)
                        entries = emptyList()
                    }) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Очистить")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            ModelCard(
                ready = model.ready,
                downloading = model.downloading,
                percent = model.percent,
                downloadedMb = model.downloadedBytes / (1024 * 1024),
                totalMb = model.totalBytes / (1024 * 1024),
                onDownload = { GigaAmModel.downloadIfNeeded(context) },
            )

            UpdateCard(onResult = { Journal.record(context, it, "update") })

            NotesUploadCard(
                enabled = notesServer,
                onToggle = {
                    notesServer = it
                    NotesSync.setServerAllowed(context, it)
                },
                onUpload = {
                    NotesSync.push(context) { sent ->
                        org.stypox.dicio.eval.Extras.notify(
                            context, "Заметки",
                            when {
                                sent == -2 -> "Отправка на сервер выключена"
                                sent < 0 -> "Не удалось отправить: нет связи или токена"
                                sent == 0 -> "Всё уже отправлено"
                                else -> "Отправлено заметок: $sent"
                            },
                        )
                    }
                },
            )

            StatsCard(
                triggers = triggers,
                rejected = rejected,
                commands = commands,
                nearMisses = nearMisses,
                strict = strict,
                uplink = uplink,
                onStrictChange = {
                    strict = it
                    WakeStats.setStrict(context, it)
                },
                onUplinkChange = { wanted ->
                    if (wanted && !Uplink.isConsented(context)) {
                        showConsent = true
                    } else {
                        uplink = wanted
                        Uplink.setEnabled(context, wanted)
                    }
                },
                onReset = {
                    WakeStats.reset(context)
                    triggers = 0
                    rejected = 0
                    commands = 0
                    strict = WakeStats.isStrict(context)
                },
            )

            if (showConsent) {
        AlertDialog(
            onDismissRequest = { showConsent = false },
            title = { Text("Отправка данных на сервер") },
            text = {
                Text(
                    "Приложение будет отправлять распознанный текст и короткие записи (до 20 в день) " +
                        "ложных срабатываний, чтобы обучить модель лучше слышать «Юна». " +
                        "Данные привязаны к вашему устройству, отключить можно в любой момент.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    Uplink.setConsented(context, true)
                    Uplink.setEnabled(context, true)
                    uplink = true
                    showConsent = false
                }) { Text("Согласен") }
            },
            dismissButton = {
                TextButton(onClick = { showConsent = false }) { Text("Отмена") }
            },
        )
    }

    if (entries.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Пока пусто. Скажите что-нибудь — здесь появится, что именно услышала Una.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(entries) { entry ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            Text(text = entry.text, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = "${entry.timeLabel()} · ${entry.result}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelCard(
    ready: Boolean,
    downloading: Boolean,
    percent: Int,
    downloadedMb: Long,
    totalMb: Long,
    onDownload: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "Модель распознавания речи",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(4.dp))
            when {
                ready -> Text(
                    text = "Скачана — команды распознаются точно.",
                    style = MaterialTheme.typography.bodySmall,
                )

                downloading -> {
                    Text(
                        text = "Скачивается: $percent% ($downloadedMb из $totalMb МБ)",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { percent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                else -> {
                    Text(
                        text = "Не скачана (~$totalMb МБ). Без неё команды понимаются хуже.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onDownload) {
                        Text("Скачать")
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateCard(onResult: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<Updater.UpdateStatus?>(null) }
    var dialog by remember { mutableStateOf<Updater.UpdateStatus?>(null) }
    val notifications = remember { Updater.notificationsEnabled(context) }

    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("Обновления", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "Установлено: ${BuildConfig.VERSION_NAME} (код ${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            status?.let { current ->
                Spacer(Modifier.height(6.dp))
                if (current.remoteCode > 0) {
                    Text(
                        "На сервере: ${current.remoteName} (код ${current.remoteCode})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    current.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (current.outcome == Updater.Outcome.ERROR)
                        MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                )
            }
            if (busy && stage.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (!notifications) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Уведомления выключены: предложение обновиться не покажется. " +
                        "Включите их в разделе «Разрешения».",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        stage = "Спрашиваю сервер…"
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    Updater.checkNow(context) { newStage -> stage = newStage }
                                }.getOrElse { error ->
                                    Updater.UpdateStatus(
                                        BuildConfig.VERSION_CODE,
                                        BuildConfig.VERSION_NAME,
                                        0,
                                        "",
                                        Updater.Outcome.ERROR,
                                        "Ошибка: ${error.message ?: error.javaClass.simpleName}",
                                    )
                                }
                            }
                            stage = ""
                            status = result
                            busy = false
                            // a small grey line was too easy to miss, so the result is shown
                            // in a dialog the user has to acknowledge
                            dialog = result
                            onResult("Обновление: ${result.message}")
                        }
                    },
                ) {
                    Text(if (busy) "Проверяю…" else "Проверить обновление")
                }

                val apk = status?.apk
                if (status?.outcome == Updater.Outcome.READY_TO_INSTALL && apk != null) {
                    TextButton(
                        onClick = {
                            if (!Updater.installNow(context, apk)) {
                                onResult("Обновление: установщик не открылся")
                            }
                        },
                    ) {
                        Text("Установить")
                    }
                }
            }
        }
    }

    val current = dialog
    if (current != null) {
        val apk = current.apk
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = {
                Text(
                    when (current.outcome) {
                        Updater.Outcome.UP_TO_DATE -> "Обновлений нет"
                        Updater.Outcome.READY_TO_INSTALL -> "Обновление скачано"
                        Updater.Outcome.ERROR -> "Не удалось проверить"
                    }
                )
            },
            text = {
                Text(
                    buildString {
                        append("Установлено: ${current.installedName} (код ${current.installedCode})")
                        if (current.remoteCode > 0) {
                            append("\nНа сервере: ${current.remoteName} (код ${current.remoteCode})")
                        }
                        append("\n\n${current.message}")
                    }
                )
            },
            confirmButton = {
                if (current.outcome == Updater.Outcome.READY_TO_INSTALL && apk != null) {
                    TextButton(onClick = {
                        dialog = null
                        if (!Updater.installNow(context, apk)) {
                            onResult("Обновление: установщик не открылся")
                        }
                    }) { Text("Установить") }
                } else {
                    TextButton(onClick = { dialog = null }) { Text("Закрыть") }
                }
            },
            dismissButton = if (current.outcome == Updater.Outcome.READY_TO_INSTALL && apk != null) {
                { TextButton(onClick = { dialog = null }) { Text("Позже") } }
            } else {
                null
            },
        )
    }
}

@Composable
private fun StatsCard(
    triggers: Int,
    rejected: Int,
    commands: Int,
    nearMisses: Int,
    strict: Boolean,
    uplink: Boolean,
    onStrictChange: (Boolean) -> Unit,
    onUplinkChange: (Boolean) -> Unit,
    onReset: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("Триггеры имени", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "Сработало: $triggers · отклонено проверкой: $rejected · с командой: $commands",
                style = MaterialTheme.typography.bodySmall,
            )
            if (nearMisses > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Почти: $nearMisses — имя услышано, но неуверенно. Растёт — поднимите чувствительность.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Строгая проверка", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Перепроверять «Юна» точной моделью — меньше ложных",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = strict, onCheckedChange = onStrictChange)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Отправлять журнал на сервер", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Включено по умолчанию: услышанные фразы и до 20 коротких аудиофрагментов " +
                            "в день — чтобы обучить модель лучше слышать «Юна»",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = uplink, onCheckedChange = onUplinkChange)
            }
            TextButton(onClick = onReset, modifier = Modifier.align(Alignment.End)) {
                Text("Сбросить счётчики")
            }
        }
    }
}
@Composable
private fun NotesUploadCard(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onUpload: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("Для опытных: заметки на сервер", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "По умолчанию заметки остаются на телефоне. Здесь можно включить отправку на сервер " +
                    "помощника — обычно это не нужно, достаточно экспорта в свою папку.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = enabled, onCheckedChange = onToggle)
                Spacer(Modifier.padding(horizontal = 8.dp))
                TextButton(onClick = onUpload) { Text("Отправить сейчас") }
            }
        }
    }
}
