package com.spkprac.app.ui.screens

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spkprac.app.R
import com.spkprac.app.audio.AudioOutput
import com.spkprac.app.core.SessionStartConfig
import com.spkprac.app.core.SessionViewModel
import com.spkprac.app.data.AudioRoute
import com.spkprac.app.data.ChatMessage
import com.spkprac.app.data.Languages
import com.spkprac.app.data.Mode
import com.spkprac.app.data.Role
import com.spkprac.app.data.VadMode
import com.spkprac.app.ui.components.AuroraBackground
import com.spkprac.app.ui.components.LevelMeter
import com.spkprac.app.ui.components.MarkdownText
import com.spkprac.app.ui.components.OrbState
import com.spkprac.app.ui.components.PillLabel
import com.spkprac.app.ui.components.StatusDot
import com.spkprac.app.ui.components.ThinkingDots
import com.spkprac.app.ui.components.VoiceOrb
import com.spkprac.app.ui.theme.captionTextStyle
import com.spkprac.app.ui.components.orbStateOf
import com.spkprac.app.ui.components.pressable
import com.spkprac.app.ui.components.rememberTick
import com.spkprac.app.ui.theme.Brand
import com.spkprac.app.ui.theme.LocalUiPrefs
import com.spkprac.app.ui.theme.isDarkScheme
import kotlinx.coroutines.launch

private typealias Ui = SessionViewModel.UiState
private typealias St = SessionViewModel.Status
private typealias Agent = SessionViewModel.AgentState

@Composable
fun SessionScreen(
    cfg: SessionStartConfig,
    onExit: () -> Unit
) {
    val vm: SessionViewModel = viewModel()
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasMicPermission = granted }

    LaunchedEffect(cfg) {
        if (!hasMicPermission) permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        if (ui.status == St.IDLE || ui.status == St.ENDED || ui.status == St.FAILED) {
            vm.start(cfg)
        }
    }

    // Keep the screen awake only while this screen is alive. The old code set the flag in an
    // effect but never cleared it when leaving, so the display stayed on forever.
    val live = ui.status == St.CONNECTING || ui.status == St.CONNECTED || ui.status == St.RECONNECTING
    val keepOn = live && ui.settings.keepScreenOn
    DisposableEffect(keepOn) {
        val window = (context as? Activity)?.window
        if (keepOn) {
            window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            (context as? Activity)?.window
                ?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    var leaving by remember { mutableStateOf(false) }
    // Single exit path: marks the screen as leaving so transient states (e.g. the report dialog
    // that pops up on ENDED) cannot flash while the navigation animation plays out.
    val exit: () -> Unit = {
        leaving = true
        onExit()
    }
    BackHandler {
        if (ui.status != St.ENDED) vm.endSession(false)
        exit()
    }

    val orb = orbStateOf(ui.status, ui.agentState)
    val ended = ui.status == St.ENDED
    var reportDismissed by remember { mutableStateOf(false) }

    AuroraBackground(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
        ) {
            SessionHeader(ui = ui, cfg = cfg, orbState = orb)

            Box(Modifier.weight(1f)) {
                if (ui.messages.isEmpty() && ui.liveUser.isBlank() && ui.liveAgent.isBlank()) {
                    Stage(ui = ui, orbState = orb, mode = cfg.mode, onRetry = vm::retry)
                } else {
                    Transcript(ui = ui)
                }
            }

            AnimatedVisibility(
                visible = ui.error != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                ErrorBanner(text = ui.error ?: "", onDismiss = vm::dismissError)
            }
            if (!hasMicPermission) {
                ErrorBanner(
                    text = stringResource(R.string.mic_permission_needed),
                    onDismiss = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                )
            }

            if (ui.settings.showWaveform && live) {
                LevelMeter(
                    level = ui.micLevel,
                    active = !ui.micMuted && !ended,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp, vertical = 4.dp)
                        .height(26.dp)
                )
            }

            Controls(
                ui = ui,
                ended = ended,
                onMute = vm::toggleMute,
                onSelectOutput = vm::selectOutput,
                onAutoRoute = vm::setAutoRoute,
                onOpenOutputPicker = vm::refreshOutputs,
                onPttDown = vm::pttDown,
                onPttUp = vm::pttUp,
                onEnd = { vm.endSession(true) },
                onSend = vm::sendText,
                onRetry = vm::retry,
                onExit = exit
            )
        }
    }

    if (!leaving && ended && cfg.mode == Mode.PRACTICE && ui.settings.pracReport && !reportDismissed) {
        ReportDialog(
            generating = ui.reportGenerating,
            report = ui.report,
            saved = ui.reportSaved,
            onRegenerate = vm::regenerateReport,
            onDismiss = { reportDismissed = true }
        )
    }
}

/* ---------------------------------------------------------------- header */

@Composable
private fun SessionHeader(ui: Ui, cfg: SessionStartConfig, orbState: OrbState) {
    val statusLabel = when (ui.status) {
        St.IDLE -> ""
        St.CONNECTING -> stringResource(R.string.st_connecting)
        St.RECONNECTING -> stringResource(R.string.st_reconnecting)
        St.CONNECTED -> when (ui.agentState) {
            Agent.LISTENING -> stringResource(R.string.st_listening)
            Agent.THINKING -> stringResource(R.string.st_thinking)
            Agent.SPEAKING -> stringResource(R.string.st_speaking)
        }
        St.ENDED -> stringResource(R.string.st_ended)
        St.FAILED -> stringResource(R.string.st_failed)
    }
    val statusColor = when (ui.status) {
        St.FAILED -> MaterialTheme.colorScheme.error
        St.RECONNECTING -> MaterialTheme.colorScheme.tertiary
        St.ENDED -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> when (ui.agentState) {
            Agent.SPEAKING -> MaterialTheme.colorScheme.primary
            Agent.THINKING -> MaterialTheme.colorScheme.tertiary
            Agent.LISTENING -> Brand.Aqua
        }
    }
    val modeText = when (cfg.mode) {
        Mode.CHAT -> stringResource(R.string.sess_chat)
        Mode.PRACTICE -> stringResource(R.string.sess_practice_fmt, Languages.name(cfg.pracLang))
    }
    val min = ui.elapsedSec / 60
    val sec = ui.elapsedSec % 60

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        VoiceOrb(state = orbState, level = ui.micLevel, size = 44.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(color = statusColor, size = 8, pulse = ui.status != St.ENDED)
                Spacer(Modifier.width(6.dp))
                Text(
                    statusLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (ui.translatingCount > 0) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 1.6.dp)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    modeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (ui.userTurns > 0) {
                    Text(
                        "  ·  " + stringResource(R.string.sess_turn_fmt, ui.userTurns),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
            shape = RoundedCornerShape(50)
        ) {
            Text(
                "%d:%02d".format(min, sec),
                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

/* ----------------------------------------------------------------- stage */

@Composable
private fun Stage(ui: Ui, orbState: OrbState, mode: Mode, onRetry: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        VoiceOrb(state = orbState, level = ui.micLevel, size = 176.dp)
        Spacer(Modifier.height(22.dp))
        Text(
            when {
                ui.status == St.FAILED -> stringResource(R.string.connect_failed_hint)
                ui.status == St.CONNECTING || ui.status == St.RECONNECTING ->
                    stringResource(R.string.st_connecting)
                mode == Mode.PRACTICE -> stringResource(R.string.sess_empty_practice)
                else -> stringResource(R.string.sess_empty_title)
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.sess_empty_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (ui.status == St.FAILED) {
            Spacer(Modifier.height(18.dp))
            OutlinedButton(onClick = onRetry) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.retry))
            }
        }
    }
}

/* ------------------------------------------------------------- transcript */

@Composable
private fun Transcript(ui: Ui) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val animatedIds = remember { mutableSetOf<String>() }
    val showUserLive = ui.settings.subtitleUser && ui.liveUser.isNotBlank()
    val showAgentLive = ui.settings.subtitleAi && ui.liveAgent.isNotBlank()
    val total = ui.messages.size + (if (showUserLive) 1 else 0) + (if (showAgentLive) 1 else 0)

    val atBottom by remember {
        derivedStateOf {
            val info: androidx.compose.foundation.lazy.LazyListLayoutInfo = listState.layoutInfo
            if (info.totalItemsCount == 0) true
            else (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 2
        }
    }

    // Follow the conversation only while the reader is parked at the live edge.
    LaunchedEffect(total, ui.liveUser, ui.liveAgent) {
        if (total > 0 && atBottom) listState.scrollToItem(total - 1)
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(ui.messages, key = { it.id }) { m ->
                val firstAppearance = m.id !in animatedIds
                BubbleItem(m, animateIn = firstAppearance)
                LaunchedEffect(m.id) { animatedIds.add(m.id) }
            }
            if (showUserLive) {
                item(key = "live_user") {
                    Bubble(role = Role.USER, text = ui.liveUser, translation = null)
                }
            }
            if (showAgentLive) {
                item(key = "live_agent") {
                    Bubble(role = Role.ASSISTANT, text = ui.liveAgent, translation = null)
                }
            }
            if (ui.agentState == Agent.THINKING && ui.liveAgent.isBlank()) {
                item(key = "thinking") {
                    Row(
                        Modifier.padding(start = 6.dp, top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ThinkingDots()
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.st_thinking),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = !atBottom && total > 2,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 10.dp),
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut()
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(50),
                modifier = Modifier.pressable {
                    scope.launch {
                        listState.animateScrollToItem((total - 1).coerceAtLeast(0))
                    }
                }
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.sess_jump_latest), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun BubbleItem(m: ChatMessage, animateIn: Boolean) {
    val animations = LocalUiPrefs.current.animations
    var visible by remember(m.id) { mutableStateOf(!animateIn || !animations) }
    LaunchedEffect(m.id) { visible = true }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically(spring()) { it / 3 }
    ) {
        Bubble(role = m.role, text = m.text, translation = m.translation)
    }
}

@Composable
private fun Bubble(role: Role, text: String, translation: String?) {
    val isUser = role == Role.USER
    val accent = Brand.accent(isDarkScheme())
    val style = captionTextStyle()

    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Surface(
            color = if (isUser) Color.Transparent else MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomEnd = if (isUser) 6.dp else 20.dp,
                bottomStart = if (isUser) 20.dp else 6.dp
            ),
            tonalElevation = if (isUser) 0.dp else 1.dp
        ) {
            Box(
                Modifier
                    .then(
                        if (isUser) Modifier.background(
                            Brush.linearGradient(accent),
                            RoundedCornerShape(
                                topStart = 20.dp,
                                topEnd = 20.dp,
                                bottomEnd = 6.dp,
                                bottomStart = 20.dp
                            )
                        ) else Modifier
                    )
                    .padding(horizontal = 15.dp, vertical = 11.dp)
                    .widthIn(max = 320.dp)
            ) {
                Text(
                    text,
                    color = if (isUser) Color.White else MaterialTheme.colorScheme.onSurface,
                    style = style
                )
            }
        }
        if (translation != null) {
            Row(
                Modifier
                    .padding(top = 4.dp)
                    .widthIn(max = 300.dp),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    Modifier
                        .width(2.dp)
                        .height(14.dp)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                            RoundedCornerShape(2.dp)
                        )
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    translation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/* --------------------------------------------------------------- banners */

@Composable
private fun ErrorBanner(text: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.close),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/* -------------------------------------------------------------- controls */

@Composable
private fun Controls(
    ui: Ui,
    ended: Boolean,
    onMute: () -> Unit,
    onSelectOutput: (AudioOutput) -> Unit,
    onAutoRoute: () -> Unit,
    onOpenOutputPicker: () -> Unit,
    onPttDown: () -> Unit,
    onPttUp: () -> Unit,
    onEnd: () -> Unit,
    onSend: (String) -> Unit,
    onRetry: () -> Unit,
    onExit: () -> Unit
) {
    var showKeyboard by remember { mutableStateOf(false) }
    var textInput by remember { mutableStateOf("") }
    val pttMode = ui.settings.vadMode == VadMode.PTT
    val active = ui.status == St.CONNECTED
    val tick = rememberTick()

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        if (ui.status == St.FAILED) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = { tick(); onRetry() }) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.retry))
                }
                Spacer(Modifier.width(12.dp))
                OutlinedButton(onClick = onExit) { Text(stringResource(R.string.back_home)) }
            }
        }

        AnimatedVisibility(
            visible = showKeyboard && active,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 10.dp)
            ) {
                OutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.text_input_hint)) },
                    maxLines = 3,
                    shape = RoundedCornerShape(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = {
                        if (textInput.isNotBlank()) {
                            tick()
                            onSend(textInput)
                            textInput = ""
                        }
                    },
                    enabled = textInput.isNotBlank()
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.send))
                }
            }
        }

        if (pttMode && !ended) {
            PttButton(
                active = active && !ui.micMuted,
                pressed = ui.pttActive,
                onPress = {
                    tick()
                    onPttDown()
                },
                onRelease = onPttUp
            )
            Spacer(Modifier.height(10.dp))
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            RoundIconBtn(
                icon = if (ui.micMuted) Icons.Default.MicOff else Icons.Default.Mic,
                contentDesc = stringResource(R.string.mute_mic),
                checked = ui.micMuted,
                enabled = active
            ) {
                tick()
                onMute()
            }

            OutputPickerButton(
                ui = ui,
                enabled = active,
                onOpen = {
                    tick()
                    onOpenOutputPicker()
                },
                onSelect = {
                    tick()
                    onSelectOutput(it)
                },
                onAuto = {
                    tick()
                    onAutoRoute()
                }
            )

            EndButton(ended = ended, onEnd = onEnd, onExit = onExit)

            RoundIconBtn(
                icon = Icons.Default.Keyboard,
                contentDesc = stringResource(R.string.text_input),
                checked = showKeyboard,
                enabled = active
            ) {
                tick()
                showKeyboard = !showKeyboard
            }
        }
    }
}

@Composable
private fun EndButton(ended: Boolean, onEnd: () -> Unit, onExit: () -> Unit) {
    val tick = rememberTick()
    val errorColor = MaterialTheme.colorScheme.error
    Box(
        Modifier
            .size(64.dp)
            .pressable {
                tick()
                if (ended) onExit() else onEnd()
            }
            .background(
                Brush.linearGradient(listOf(errorColor, errorColor.copy(alpha = 0.78f))),
                CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        if (ended) {
            Icon(Icons.Default.Home, contentDescription = stringResource(R.string.back_home), tint = Color.White, modifier = Modifier.size(24.dp))
        } else {
            Icon(
                Icons.Default.CallEnd,
                contentDescription = stringResource(R.string.end_call),
                tint = Color.White,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

@Composable
private fun RoundIconBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDesc: String,
    checked: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    FilledIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(52.dp),
        shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (checked) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            contentColor = if (checked) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Icon(icon, contentDescription = contentDesc)
    }
}

/**
 * Output-device picker: shows every route the platform currently offers (speaker, earpiece, wired
 * headset, Bluetooth, USB...) plus an explicit "follow system" entry. This is what makes headphones
 * usable - the previous build could only toggle between the two built-in transducers.
 */
@Composable
private fun OutputPickerButton(
    ui: Ui,
    enabled: Boolean,
    onOpen: () -> Unit,
    onSelect: (AudioOutput) -> Unit,
    onAuto: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val icon = when (ui.currentOutput?.route) {
        AudioRoute.SPEAKER -> Icons.AutoMirrored.Filled.VolumeUp
        AudioRoute.EARPIECE -> Icons.Default.PhoneInTalk
        AudioRoute.WIRED_HEADSET -> Icons.Default.Headphones
        AudioRoute.BLUETOOTH -> Icons.Default.BluetoothAudio
        AudioRoute.USB -> Icons.Default.Usb
        else -> Icons.AutoMirrored.Filled.VolumeUp
    }
    Box {
        RoundIconBtn(
            icon = icon,
            contentDesc = stringResource(R.string.sess_output),
            checked = false,
            enabled = enabled
        ) {
            onOpen()
            expanded = true
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.route_auto)) },
                leadingIcon = {
                    if (ui.currentOutput == null) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                },
                onClick = {
                    expanded = false
                    onAuto()
                }
            )
            if (ui.outputs.isNotEmpty()) HorizontalDivider()
            ui.outputs.forEach { output ->
                val selected = ui.currentOutput?.let { it.id == output.id && it.route == output.route } == true
                DropdownMenuItem(
                    text = {
                        Text(
                            buildString {
                                append(routeLabel(output.route))
                                output.name?.let { name ->
                                    if (!name.equals(routeLabel(output.route), ignoreCase = true)) {
                                        append(" · ").append(name)
                                    }
                                }
                            }
                        )
                    },
                    leadingIcon = {
                        if (selected) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(output)
                    }
                )
            }
        }
    }
}

@Composable
private fun routeLabel(route: AudioRoute): String = stringResource(
    when (route) {
        AudioRoute.SPEAKER -> R.string.route_speaker
        AudioRoute.EARPIECE -> R.string.route_earpiece
        AudioRoute.WIRED_HEADSET -> R.string.route_wired
        AudioRoute.BLUETOOTH -> R.string.route_bluetooth
        AudioRoute.USB -> R.string.route_usb
        else -> R.string.route_other
    }
)

@Composable
private fun PttButton(active: Boolean, pressed: Boolean, onPress: () -> Unit, onRelease: () -> Unit) {
    val accent = Brand.accent(isDarkScheme())
    val shape = RoundedCornerShape(28.dp)
    val container = if (pressed) {
        Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary))
    } else {
        Brush.horizontalGradient(accent.map { it.copy(alpha = 0.92f) })
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(container, shape)
            .pointerInput(active) {
                detectTapGestures(
                    onPress = {
                        if (active) {
                            onPress()
                            try {
                                awaitRelease()
                            } finally {
                                onRelease()
                            }
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Mic,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(if (pressed) R.string.ptt_release else R.string.ptt_hold),
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/* ---------------------------------------------------------------- report */

@Composable
private fun ReportDialog(
    generating: Boolean,
    report: String?,
    saved: Boolean,
    onRegenerate: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp
        ) {
            Column(Modifier.padding(22.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.practice_report),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    if (saved) {
                        PillLabel(
                            text = stringResource(R.string.sess_report_ready),
                            container = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Box(Modifier.height(340.dp)) {
                    when {
                        generating -> Column(
                            Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(14.dp))
                            Text(
                                stringResource(R.string.report_generating),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        report != null -> LazyColumn(Modifier.fillMaxSize()) {
                            item {
                                MarkdownText(report.ifBlank { stringResource(R.string.sess_report_empty) })
                            }
                        }

                        else -> Column(
                            Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                stringResource(R.string.report_failed_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!generating) {
                        TextButton(
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("report", report.orEmpty()))
                                Toast.makeText(context, R.string.sess_copied, Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.sess_copy))
                        }
                        TextButton(onClick = onRegenerate) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.sess_regenerate))
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
                }
            }
        }
    }
}
