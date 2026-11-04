package com.spkprac.app

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.runtime.collectAsState
import com.spkprac.app.core.SessionStartConfig
import com.spkprac.app.data.AppSettings
import com.spkprac.app.data.Mode
import com.spkprac.app.data.SettingsRepository
import com.spkprac.app.data.ThemeMode
import com.spkprac.app.net.AppVersion
import com.spkprac.app.net.UpdateCheck
import com.spkprac.app.net.UpdateChecker
import com.spkprac.app.net.UpdateInfo
import com.spkprac.app.ui.components.UpdateDialog
import com.spkprac.app.ui.screens.HomeScreen
import com.spkprac.app.ui.screens.PracticeSetupScreen
import com.spkprac.app.ui.screens.SessionDetailScreen
import com.spkprac.app.ui.screens.SessionScreen
import com.spkprac.app.ui.screens.SettingsScreen
import com.spkprac.app.ui.theme.SpkPracTheme
import com.spkprac.app.ui.theme.UiPrefs
import kotlinx.coroutines.flow.first

sealed class Screen {
    data object Home : Screen()
    data object Settings : Screen()
    data object PracticeSetup : Screen()
    data class Session(val cfg: SessionStartConfig) : Screen()
    data class SessionDetail(val id: String) : Screen()
}

/** Automatic update checks never run more often than this, even across restarts. */
private const val AUTO_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppRoot()
        }
    }
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val prefs = remember { SettingsRepository(context) }
    val settings by prefs.settings.collectAsState(initial = AppSettings())

    // Background update check, at most once per day. Reads the persisted timestamp first so a cold
    // start cannot re-check just because the settings flow has not emitted yet.
    var pendingUpdate by remember { mutableStateOf<UpdateInfo?>(null) }
    LaunchedEffect(Unit) {
        val stored = runCatching { prefs.settings.first() }.getOrNull() ?: return@LaunchedEffect
        if (!stored.autoCheckUpdate) return@LaunchedEffect
        val now = System.currentTimeMillis()
        if (now - stored.lastUpdateCheckAt < AUTO_CHECK_INTERVAL_MS) return@LaunchedEffect
        when (val result = UpdateChecker().check(stored.updateProxy)) {
            is UpdateCheck.Available -> pendingUpdate = result.info
            else -> Unit
        }
        runCatching { prefs.setLastUpdateCheckAt(now) }
    }

    val uiPrefs = remember(settings.animations, settings.haptics, settings.captionSize) {
        UiPrefs(
            animations = settings.animations,
            haptics = settings.haptics,
            captionSize = settings.captionSize
        )
    }

    // Keep the system bar icons readable against whichever theme is active.
    val view = LocalView.current
    val activity = context as? Activity
    LaunchedEffect(settings.themeMode) {
        val window = activity?.window ?: return@LaunchedEffect
        val dark = when (settings.themeMode) {
            ThemeMode.SYSTEM -> (context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    SpkPracTheme(themeMode = settings.themeMode, uiPrefs = uiPrefs) {
        var screen by remember { mutableStateOf<Screen>(Screen.Home) }

        BackHandler(enabled = screen !is Screen.Home) {
            screen = Screen.Home
        }

        val go: (Screen) -> Unit = { screen = it }

        if (uiPrefs.animations) {
            AnimatedContent(
                targetState = screen,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    val forward = targetState !is Screen.Home
                    val enter = fadeIn(tween(240)) +
                        slideInHorizontally(tween(260)) { if (forward) it / 10 else -it / 10 }
                    val exit = fadeOut(tween(150)) +
                        slideOutHorizontally(tween(240)) { if (forward) -it / 14 else it / 14 }
                    enter togetherWith exit
                },
                label = "screen"
            ) { s ->
                ScreenHost(
                    screen = s,
                    settings = settings,
                    go = go,
                    onBackHome = { screen = Screen.Home }
                )
            }
        } else {
            ScreenHost(
                screen = screen,
                settings = settings,
                go = go,
                onBackHome = { screen = Screen.Home }
            )
        }

        pendingUpdate?.let { info ->
            UpdateDialog(
                info = info,
                currentVersion = AppVersion.current,
                useProxy = settings.updateProxy,
                onDismiss = { pendingUpdate = null }
            )
        }
    }
}

@Composable
private fun ScreenHost(
    screen: Screen,
    settings: AppSettings,
    go: (Screen) -> Unit,
    onBackHome: () -> Unit
) {
    when (screen) {
        is Screen.Home -> HomeScreen(
            onChat = {
                go(
                    Screen.Session(
                        SessionStartConfig(
                            mode = Mode.CHAT,
                            chatSpeakLang = settings.chatSpeakLang.ifBlank { "auto" }
                        )
                    )
                )
            },
            onPractice = { go(Screen.PracticeSetup) },
            onSettings = { go(Screen.Settings) },
            onOpenHistory = { go(Screen.SessionDetail(it)) }
        )

        is Screen.PracticeSetup -> PracticeSetupScreen(
            onBack = onBackHome,
            onStart = { cfg -> go(Screen.Session(cfg)) }
        )

        is Screen.Session -> SessionScreen(
            cfg = screen.cfg,
            onExit = onBackHome
        )

        is Screen.SessionDetail -> SessionDetailScreen(
            id = screen.id,
            onBack = onBackHome
        )

        is Screen.Settings -> SettingsScreen(onBack = onBackHome)
    }
}
