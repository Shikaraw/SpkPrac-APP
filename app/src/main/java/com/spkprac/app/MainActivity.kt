package com.spkprac.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.core.content.ContextCompat
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
import com.spkprac.app.ui.screens.VoiceDetailScreen
import com.spkprac.app.ui.screens.VoiceManagerScreen
import com.spkprac.app.ui.theme.SpkPracTheme
import com.spkprac.app.ui.theme.UiPrefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

sealed class Screen {
    data object Home : Screen()
    data object Settings : Screen()
    data object PracticeSetup : Screen()
    /** Voice manager, opened from Settings → 音色 → 管理音色. */
    data object VoiceManager : Screen()
    /** One cloned voice's detail page, keyed by server voice id. */
    data class VoiceDetail(val voiceId: String) : Screen()
    data class Session(val cfg: SessionStartConfig) : Screen()
    data class SessionDetail(val id: String) : Screen()
}

/** Automatic update checks never run more often than this, even across restarts. */
private const val AUTO_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L

/** How long the POST_NOTIFICATIONS request waits for the RECORD_AUDIO dialog to settle first. */
private const val PERMISSION_ORDER_TIMEOUT_MS = 20_000L

/** Poll interval while waiting for the microphone permission (it is only granted via a system dialog). */
private const val PERMISSION_ORDER_POLL_MS = 250L

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

        // POST_NOTIFICATIONS (Android 13+) is only needed for the ongoing "练习中" notification, so it
        // is requested the first time a session screen opens — never at launch (v1.3.0 spec §1.4).
        // Denying it is fine: the foreground service still runs, the app just shows no notification.
        var notificationPermissionAsked by remember { mutableStateOf(false) }
        val notificationPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { /* Granted or denied: the session must work either way. */ }
        val sessionOpen = screen is Screen.Session
        LaunchedEffect(sessionOpen) {
            if (!sessionOpen || notificationPermissionAsked) return@LaunchedEffect
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@LaunchedEffect
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                return@LaunchedEffect
            }
            // SessionScreen opens the RECORD_AUDIO dialog on entry; two permission requests cannot be
            // in flight at once (the second is dropped silently), so wait until the microphone request
            // has settled — RECORD_AUDIO is needed for the foreground service type anyway.
            var waitedMs = 0L
            while (waitedMs < PERMISSION_ORDER_TIMEOUT_MS &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                delay(PERMISSION_ORDER_POLL_MS)
                waitedMs += PERMISSION_ORDER_POLL_MS
            }
            // Leaving the session screen cancels this effect; only ask while it is still open.
            if (screen !is Screen.Session) return@LaunchedEffect
            notificationPermissionAsked = true
            runCatching {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        BackHandler(enabled = screen !is Screen.Home) {
            // Nested pages pop one level instead of jumping straight home.
            screen = when (screen) {
                is Screen.VoiceDetail -> Screen.VoiceManager
                is Screen.VoiceManager -> Screen.Settings
                else -> Screen.Home
            }
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

        is Screen.Settings -> SettingsScreen(
            onBack = onBackHome,
            onManageVoices = { go(Screen.VoiceManager) }
        )

        is Screen.VoiceManager -> VoiceManagerScreen(
            onBack = { go(Screen.Settings) },
            onOpenVoice = { go(Screen.VoiceDetail(it)) }
        )

        is Screen.VoiceDetail -> VoiceDetailScreen(
            voiceId = screen.voiceId,
            onBack = { go(Screen.VoiceManager) }
        )
    }
}
