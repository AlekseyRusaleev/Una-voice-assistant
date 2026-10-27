@file:OptIn(ExperimentalMaterial3Api::class)

package org.stypox.dicio.ui.permissions

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Every permission the assistant needs, with its state and a one-tap way to grant it.
 *
 * Runtime permissions are requested with a system dialog; the "special access" ones (notification
 * access, exact alarms, installing updates, battery) can only be opened in system settings, so the
 * screen explains that instead of pretending it can grant them.
 */
@Composable
fun PermissionsScreen(navigationIcon: @Composable () -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }

    val runtimeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh++ }

    val items = remember(refresh) { permissionStates(context) }
    val missingRuntime = items.filter { it.runtime && !it.granted }.mapNotNull { it.permission }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Разрешения") }, navigationIcon = navigationIcon)
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            Text(
                "Без этих разрешений часть функций не работает. Галочка — всё в порядке.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))

            for (item in items) {
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (item.granted) Icons.Default.CheckCircle
                            else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (item.granted) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.padding(horizontal = 6.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.title, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                item.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!item.granted) {
                            TextButton(onClick = { item.open(context, runtimeLauncher) }) {
                                Text("Разрешить")
                            }
                        }
                    }
                }
            }

            if (missingRuntime.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { runtimeLauncher.launch(missingRuntime.toTypedArray()) }) {
                    Text("Запросить все сразу")
                }
            }
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = { refresh++ }) { Text("Проверить снова") }
        }
    }
}

private class PermissionState(
    val title: String,
    val detail: String,
    val granted: Boolean,
    val runtime: Boolean = false,
    val permission: String? = null,
    val open: (Context, androidx.activity.result.ActivityResultLauncher<Array<String>>) -> Unit,
)

private fun permissionStates(context: Context): List<PermissionState> {
    fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun openSettings(action: String, data: Uri? = null) {
        try {
            val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (data != null) intent.data = data
            context.startActivity(intent)
        } catch (t: Throwable) {
            context.startActivity(
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    val notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        granted(Manifest.permission.POST_NOTIFICATIONS)

    val listenerEnabled = NotificationManagerCompat.getEnabledListenerPackages(context)
        .contains(context.packageName)

    val exactAlarms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)
            ?.canScheduleExactAlarms() == true
    } else true

    val canInstall = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.packageManager.canRequestPackageInstalls()
    } else true

    val batteryOk = (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
        ?.isIgnoringBatteryOptimizations(context.packageName) == true

    return listOf(
        PermissionState(
            title = "Микрофон",
            detail = "Слушать имя и команды",
            granted = granted(Manifest.permission.RECORD_AUDIO),
            runtime = true,
            permission = Manifest.permission.RECORD_AUDIO,
        ) { _, launcher -> launcher.launch(arrayOf(Manifest.permission.RECORD_AUDIO)) },

        PermissionState(
            title = "Уведомления",
            detail = "Напоминания, ответы, обновления",
            granted = notificationsGranted,
            runtime = true,
            permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                Manifest.permission.POST_NOTIFICATIONS else null,
        ) { _, launcher ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                launcher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            } else {
                openSettings(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            }
        },

        PermissionState(
            title = "Контакты",
            detail = "«Позвони Юле» — поиск по имени",
            granted = granted(Manifest.permission.READ_CONTACTS),
            runtime = true,
            permission = Manifest.permission.READ_CONTACTS,
        ) { _, launcher -> launcher.launch(arrayOf(Manifest.permission.READ_CONTACTS)) },

        PermissionState(
            title = "Телефон",
            detail = "Звонить сразу, а не открывать набор",
            granted = granted(Manifest.permission.CALL_PHONE),
            runtime = true,
            permission = Manifest.permission.CALL_PHONE,
        ) { _, launcher -> launcher.launch(arrayOf(Manifest.permission.CALL_PHONE)) },

        PermissionState(
            title = "СМС",
            detail = "«Отправь смс Юле: я задержусь»",
            granted = granted(Manifest.permission.SEND_SMS),
            runtime = true,
            permission = Manifest.permission.SEND_SMS,
        ) { _, launcher -> launcher.launch(arrayOf(Manifest.permission.SEND_SMS)) },

        PermissionState(
            title = "Календарь",
            detail = "«Запиши в календарь завтра в 15:00 врач»",
            granted = granted(Manifest.permission.READ_CALENDAR) &&
                granted(Manifest.permission.WRITE_CALENDAR),
            runtime = true,
            permission = if (granted(Manifest.permission.READ_CALENDAR))
                Manifest.permission.WRITE_CALENDAR else Manifest.permission.READ_CALENDAR,
        ) { _, launcher ->
            launcher.launch(
                arrayOf(
                    Manifest.permission.READ_CALENDAR,
                    Manifest.permission.WRITE_CALENDAR,
                )
            )
        },

        PermissionState(
            title = "Доступ к уведомлениям",
            detail = "Управление плеером, чтение и ответ на сообщения",
            granted = listenerEnabled,
        ) { ctx, _ -> openSettingsCtx(ctx, Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) },

        PermissionState(
            title = "Точные будильники",
            detail = "Напоминания срабатывают минута в минуту",
            granted = exactAlarms,
        ) { ctx, _ ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                openSettingsCtx(ctx, Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            }
        },

        PermissionState(
            title = "Установка обновлений",
            detail = "Приложение обновляет себя само",
            granted = canInstall,
        ) { ctx, _ ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                openSettingsCtx(
                    ctx, Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + ctx.packageName),
                )
            }
        },

        PermissionState(
            title = "Работа в фоне",
            detail = "Система не усыпляет помощника",
            granted = batteryOk,
        ) { ctx, _ ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                openSettingsCtx(
                    ctx, Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + ctx.packageName),
                )
            }
        },
    )
}

private fun openSettingsCtx(context: Context, action: String, data: Uri? = null) {
    try {
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (data != null) intent.data = data
        context.startActivity(intent)
    } catch (t: Throwable) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Throwable) {
        }
    }
}
