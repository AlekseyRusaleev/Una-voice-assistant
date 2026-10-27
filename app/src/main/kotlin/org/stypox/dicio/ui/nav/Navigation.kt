package org.stypox.dicio.ui.nav

import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import androidx.compose.runtime.rememberCoroutineScope
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
            askPermissionsOnFirstRun(context) { navController.navigate(Permissions) }
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

/** Opens the permissions screen once, when the microphone is missing (fresh install). */
@Composable
private fun askPermissionsOnFirstRun(context: android.content.Context, open: () -> Unit) {
    val prefs = context.getSharedPreferences("una_first_run", android.content.Context.MODE_PRIVATE)
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted && !prefs.getBoolean("asked", false)) {
            prefs.edit().putBoolean("asked", true).apply()
            open()
        }
    }
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
