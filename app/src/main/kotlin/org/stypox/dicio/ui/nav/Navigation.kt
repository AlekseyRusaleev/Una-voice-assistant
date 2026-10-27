package org.stypox.dicio.ui.nav

import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import org.stypox.dicio.R
import org.stypox.dicio.io.input.stt_popup.SttPopupActivity
import org.stypox.dicio.settings.MainSettingsScreen
import org.stypox.dicio.settings.SkillSettingsScreen
import org.stypox.dicio.ui.about.AboutScreen
import org.stypox.dicio.ui.home.HomeScreen
import org.stypox.dicio.ui.journal.JournalScreen
import org.stypox.dicio.ui.notes.NotesScreen
import org.stypox.dicio.ui.permissions.PermissionsScreen

@Composable
fun Navigation() {
    val navController = rememberNavController()
    val backIcon = @Composable {
        IconButton(
            onClick = { navController.navigateUp() }
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.back),
            )
        }
    }

    NavHost(navController = navController, startDestination = Home) {
        composable<Home> {
            val context = LocalContext.current
            // the uplink notice waits for the permission dialogs, so the two never overlap
            var permissionsSettled by remember { mutableStateOf(false) }
            askForPermissionsOnFirstRun(
                context = context,
                onSettled = { permissionsSettled = true },
            ) { navController.navigate(Permissions) }
            showUplinkNoticeOnFirstRun(context, permissionsSettled)
            ScreenWithDrawer(
                onSettingsClick = { navController.navigate(MainSettings) },
                onAboutClick = { navController.navigate(About) },
                onJournalClick = { navController.navigate(Journal) },
                onNotesClick = { navController.navigate(Notes) },
                onPermissionsClick = { navController.navigate(Permissions) },
                onSpeechToTextPopupClick = {
                    val intent = Intent(context, SttPopupActivity::class.java)
                    context.startActivity(intent)
                },
            ) {
                HomeScreen(it)
            }
        }

        composable<Journal> {
            JournalScreen(navigationIcon = backIcon)
        }

        composable<Notes> {
            NotesScreen(navigationIcon = backIcon)
        }

        composable<Permissions> {
            PermissionsScreen(navigationIcon = backIcon)
        }

        composable<MainSettings> {
            MainSettingsScreen(
                navigationIcon = backIcon,
                navigateToSkillSettings = { navController.navigate(SkillSettings) },
            )
        }

        composable<SkillSettings> {
            SkillSettingsScreen(navigationIcon = backIcon)
        }

        composable<About> {
            AboutScreen(navigationIcon = backIcon)
        }
    }
}

/**
 * Shows the system permission dialogs on the first launch and then opens the permissions screen
 * with whatever is still missing (special access cannot be granted by a dialog).
 */
@Composable
private fun askForPermissionsOnFirstRun(
    context: android.content.Context,
    onSettled: () -> Unit,
    openScreen: () -> Unit,
) {
    val prefs = context.getSharedPreferences("una_first_run", android.content.Context.MODE_PRIVATE)
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        onSettled()
        // show the checklist with what is still missing (special access cannot be granted here)
        openScreen()
    }

    LaunchedEffect(Unit) {
        if (prefs.getBoolean("permissions_requested", false)) {
            onSettled()
            return@LaunchedEffect
        }
        prefs.edit().putBoolean("permissions_requested", true).apply()

        val missing = firstRunRuntimePermissions().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            onSettled()
            openScreen()
        } else {
            launcher.launch(missing.toTypedArray())
        }
    }
}

/** Runtime permissions Una can ask for; microphone first, because nothing works without it. */
private fun firstRunRuntimePermissions(): List<String> {
    val permissions = mutableListOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
        Manifest.permission.CAMERA,
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        permissions.add(1, Manifest.permission.POST_NOTIFICATIONS)
    }
    return permissions
}

/** Tells the user once that the journal is uploaded by default, with a way to refuse. */
@Composable
private fun showUplinkNoticeOnFirstRun(context: android.content.Context, enabled: Boolean) {
    val prefs = context.getSharedPreferences("una_first_run", android.content.Context.MODE_PRIVATE)
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(enabled) {
        if (enabled && !prefs.getBoolean("uplink_notice", false)) {
            prefs.edit().putBoolean("uplink_notice", true).apply()
            visible = true
        }
    }

    if (!visible) {
        return
    }

    AlertDialog(
        onDismissRequest = { visible = false },
        title = { Text("Журнал уходит на сервер") },
        text = {
            Text(
                "Чтобы Una лучше слышала имя, приложение отправляет на сервер услышанные фразы " +
                    "и короткие аудиофрагменты (до 20 в день). Заметки, СМС и календарь не " +
                    "отправляются. Выключить можно в «Что я услышала»."
            )
        },
        confirmButton = {
            TextButton(onClick = { visible = false }) { Text("Понятно") }
        },
        dismissButton = {
            TextButton(onClick = {
                org.stypox.dicio.eval.Uplink.setEnabled(context, false)
                visible = false
            }) { Text("Не отправлять") }
        },
    )
}

@Composable
fun ScreenWithDrawer(
    onSettingsClick: () -> Unit,
    onAboutClick: () -> Unit,
    onJournalClick: () -> Unit,
    onNotesClick: () -> Unit,
    onPermissionsClick: () -> Unit,
    onSpeechToTextPopupClick: () -> Unit,
    screen: @Composable (navigationIcon: @Composable () -> Unit) -> Unit
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            DrawerContent(
                onSettingsClick = onSettingsClick,
                onAboutClick = onAboutClick,
                onJournalClick = onJournalClick,
                onNotesClick = onNotesClick,
                onPermissionsClick = onPermissionsClick,
                onSpeechToTextPopupClick = onSpeechToTextPopupClick,
                closeDrawer = {
                    scope.launch {
                        drawerState.close()
                    }
                }
            )
        },
    ) {
        screen {
            AppBarDrawerIcon(
                onDrawerClick = {
                    scope.launch {
                        drawerState.apply {
                            if (isClosed) open() else close()
                        }
                    }
                },
                isClosed = drawerState.isClosed,
            )
        }
    }
}
