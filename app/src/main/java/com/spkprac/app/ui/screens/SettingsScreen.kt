package com.spkprac.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.spkprac.app.BuildConfig
import com.spkprac.app.R
import com.spkprac.app.data.AppSettings
import com.spkprac.app.data.AudioRoute
import com.spkprac.app.data.CaptionSize
import com.spkprac.app.data.ClonedVoice
import com.spkprac.app.data.Languages
import com.spkprac.app.data.ProtocolFlavor
import com.spkprac.app.data.SessionRepo
import com.spkprac.app.data.SettingsRepository
import com.spkprac.app.data.ThemeMode
import com.spkprac.app.data.VadMode
import com.spkprac.app.net.UpdateCheck
import com.spkprac.app.net.UpdateChecker
import com.spkprac.app.net.UpdateInfo
import com.spkprac.app.ui.components.DropdownField
import com.spkprac.app.ui.components.LabeledTextField
import com.spkprac.app.ui.components.SectionCard
import com.spkprac.app.ui.components.SegmentedSelector
import com.spkprac.app.ui.components.SwitchRow
import com.spkprac.app.ui.components.UpdateDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/* --------------------------------------------------------------- drafts */

/**
 * A text field value that survives recomposition but is *not* reset when the settings
 * flow emits. The previous implementation keyed `remember` on the stored value, so every
 * DataStore round-trip wiped the in-progress edit — which dropped characters and jumped the
 * cursor for IME users.
 */
private class Draft(initial: String) {
    var value by mutableStateOf(initial)
    var touched = false
}

@Composable
private fun rememberDraft(stored: String): Draft {
    val draft = remember { Draft(stored) }
    LaunchedEffect(stored) {
        if (!draft.touched && draft.value != stored) draft.value = stored
    }
    return draft
}

/** One voice-dropdown entry; [value] is what gets written into the voice setting. */
private data class VoiceOption(val label: String, val value: String)

/* ---------------------------------------------------------------- screen */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onManageVoices: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { SettingsRepository(context) }
    val sessionRepo = remember { SessionRepo(context) }
    val settings by repo.settings.collectAsState(initial = AppSettings())

    val snackbar = remember { SnackbarHostState() }
    var showKey by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<TestResult?>(null) }
    var confirmClearHistory by remember { mutableStateOf(false) }
    var confirmClearKey by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var checkMessage by remember { mutableStateOf<String?>(null) }

    val routeLabels = listOf(
        AudioRoute.AUTO to stringResource(R.string.route_auto),
        AudioRoute.SPEAKER to stringResource(R.string.route_speaker),
        AudioRoute.EARPIECE to stringResource(R.string.route_earpiece),
        AudioRoute.WIRED_HEADSET to stringResource(R.string.route_wired),
        AudioRoute.BLUETOOTH to stringResource(R.string.route_bluetooth),
        AudioRoute.USB to stringResource(R.string.route_usb)
    )

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = runCatching { sessionRepo.exportAll() }.getOrNull()
            if (text.isNullOrBlank()) {
                snackbar.showSnackbar(context.getString(R.string.set_export_empty))
                return@launch
            }
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(text.toByteArray(Charsets.UTF_8))
                } != null
            }.getOrDefault(false)
            snackbar.showSnackbar(
                context.getString(if (ok) R.string.set_export_ok else R.string.set_test_failed)
            )
        }
    }

    val asrAutoLabel = stringResource(R.string.asr_auto)
    val chatAutoLabel = stringResource(R.string.set_chat_lang_auto)
    val asrOptions = remember(asrAutoLabel) {
        listOf("" to asrAutoLabel) + Languages.asrCodes.map { it to Languages.name(it) }
    }
    val chatLangOptions = remember(chatAutoLabel) {
        listOf("auto" to chatAutoLabel) + Languages.speakOptions.map { it.first to it.second }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            ApiSection(
                settings = settings,
                apiKeyDraft = rememberDraft(settings.apiKey),
                showKey = showKey,
                onToggleShowKey = { showKey = !showKey },
                testing = testing,
                testResult = testResult,
                onTest = {
                    testing = true
                    testResult = null
                    scope.launch {
                        val res = withTimeoutOrNull(15_000L) {
                            testRealtime(settings.rtEndpoint, settings.apiKey, settings.rtModel)
                        } ?: TestResult.Timeout
                        testResult = res
                        testing = false
                    }
                },
                repo = repo,
                onManageVoices = onManageVoices
            )

            SectionCard(stringResource(R.string.set_sec_appearance), icon = Icons.Default.Palette) {
                SegmentedSelector(
                    label = stringResource(R.string.set_theme),
                    options = listOf(
                        stringResource(R.string.set_theme_system),
                        stringResource(R.string.set_theme_light),
                        stringResource(R.string.set_theme_dark)
                    ),
                    selectedIndex = when (settings.themeMode) {
                        ThemeMode.SYSTEM -> 0
                        ThemeMode.LIGHT -> 1
                        ThemeMode.DARK -> 2
                    },
                    onSelect = {
                        scope.launch {
                            repo.setThemeMode(
                                when (it) {
                                    0 -> ThemeMode.SYSTEM
                                    1 -> ThemeMode.LIGHT
                                    else -> ThemeMode.DARK
                                }
                            )
                        }
                    }
                )
                SegmentedSelector(
                    label = stringResource(R.string.set_caption_size),
                    options = listOf(
                        stringResource(R.string.set_caption_small),
                        stringResource(R.string.set_caption_medium),
                        stringResource(R.string.set_caption_large)
                    ),
                    selectedIndex = when (settings.captionSize) {
                        CaptionSize.SMALL -> 0
                        CaptionSize.MEDIUM -> 1
                        CaptionSize.LARGE -> 2
                    },
                    onSelect = {
                        scope.launch {
                            repo.setCaptionSize(
                                when (it) {
                                    0 -> CaptionSize.SMALL
                                    1 -> CaptionSize.MEDIUM
                                    else -> CaptionSize.LARGE
                                }
                            )
                        }
                    }
                )
                SwitchRow(
                    title = stringResource(R.string.set_animations),
                    subtitle = stringResource(R.string.set_animations_desc),
                    checked = settings.animations,
                    onCheckedChange = { scope.launch { repo.setAnimations(it) } }
                )
                SwitchRow(
                    title = stringResource(R.string.set_haptics),
                    subtitle = stringResource(R.string.set_haptics_desc),
                    checked = settings.haptics,
                    onCheckedChange = { scope.launch { repo.setHaptics(it) } }
                )
            }

            SectionCard(stringResource(R.string.sec_behavior), icon = Icons.Default.Tune) {
                SegmentedSelector(
                    label = stringResource(R.string.vad_mode),
                    options = listOf(
                        stringResource(R.string.vad_auto),
                        stringResource(R.string.vad_ptt)
                    ),
                    selectedIndex = if (settings.vadMode == VadMode.AUTO) 0 else 1,
                    onSelect = {
                        scope.launch { repo.setVadMode(if (it == 0) VadMode.AUTO else VadMode.PTT) }
                    }
                )
                SliderRow(
                    labelFor = { stringResource(R.string.vad_threshold, it) },
                    stored = settings.vadThreshold,
                    range = 0f..1f,
                    onCommit = { scope.launch { repo.setVadThreshold(it) } }
                )
                SliderRow(
                    labelFor = { stringResource(R.string.silence_duration, it.toInt()) },
                    stored = settings.silenceMs.toFloat(),
                    range = 200f..2000f,
                    steps = 17,
                    onCommit = { scope.launch { repo.setSilenceMs(it.toInt()) } }
                )
                DropdownField(
                    label = stringResource(R.string.asr_language),
                    options = asrOptions,
                    selected = asrOptions.firstOrNull { it.first == settings.asrLang } ?: asrOptions.first(),
                    optionLabel = { it.second },
                    onSelect = { scope.launch { repo.setAsrLang(it.first) } }
                )
                DropdownField(
                    label = stringResource(R.string.set_chat_lang),
                    options = chatLangOptions,
                    selected = chatLangOptions.firstOrNull { it.first == settings.chatSpeakLang }
                        ?: chatLangOptions.first(),
                    optionLabel = { it.second },
                    onSelect = { scope.launch { repo.setChatSpeakLang(it.first) } }
                )
                SwitchRow(
                    title = stringResource(R.string.set_auto_reconnect),
                    subtitle = stringResource(R.string.set_auto_reconnect_desc),
                    checked = settings.autoReconnect,
                    onCheckedChange = { scope.launch { repo.setAutoReconnect(it) } }
                )
                SwitchRow(
                    title = stringResource(R.string.set_barge_in),
                    subtitle = stringResource(R.string.set_barge_in_desc),
                    checked = settings.bargeIn,
                    onCheckedChange = { scope.launch { repo.setBargeIn(it) } }
                )
                val promptDraft = rememberDraft(settings.chatPrompt)
                LabeledTextField(
                    label = stringResource(R.string.chat_prompt),
                    value = promptDraft.value,
                    onValueChange = {
                        promptDraft.value = it
                        promptDraft.touched = true
                        scope.launch { repo.setChatPrompt(it) }
                    },
                    singleLine = false,
                    supportingText = stringResource(R.string.chat_prompt_hint)
                )
            }

            SectionCard(stringResource(R.string.sec_subtitles)) {
                SwitchRow(
                    title = stringResource(R.string.ai_subtitle),
                    checked = settings.subtitleAi,
                    onCheckedChange = { scope.launch { repo.setSubtitleAi(it) } }
                )
                SwitchRow(
                    title = stringResource(R.string.user_subtitle),
                    subtitle = stringResource(R.string.user_subtitle_desc),
                    checked = settings.subtitleUser,
                    onCheckedChange = { scope.launch { repo.setSubtitleUser(it) } }
                )
                SwitchRow(
                    title = stringResource(R.string.subtitle_translate),
                    subtitle = stringResource(R.string.subtitle_translate_desc),
                    checked = settings.translateEnabled,
                    onCheckedChange = { scope.launch { repo.setTranslateEnabled(it) } }
                )
                if (settings.translateEnabled) {
                    DropdownField(
                        label = stringResource(R.string.translate_target),
                        options = Languages.list,
                        selected = Languages.list.firstOrNull { it.first == settings.translateLang }
                            ?: Languages.list.first(),
                        optionLabel = { it.second },
                        onSelect = { scope.launch { repo.setTranslateLang(it.first) } }
                    )
                }
            }

            SectionCard(stringResource(R.string.sec_audio_display), icon = Icons.AutoMirrored.Filled.VolumeUp) {
                SliderRow(
                    labelFor = { stringResource(R.string.set_mic_gain, it) },
                    stored = settings.micGain,
                    range = 1f..3f,
                    steps = 19,
                    onCommit = { scope.launch { repo.setMicGain(it) } },
                    supporting = stringResource(R.string.set_mic_gain_desc)
                )
                SwitchRow(
                    title = stringResource(R.string.set_waveform),
                    subtitle = stringResource(R.string.set_waveform_desc),
                    checked = settings.showWaveform,
                    onCheckedChange = { scope.launch { repo.setShowWaveform(it) } }
                )
                DropdownField(
                    label = stringResource(R.string.set_audio_output),
                    options = AudioRoute.entries,
                    selected = settings.audioRoute,
                    optionLabel = { route ->
                        routeLabels.firstOrNull { it.first == route }?.second ?: route.name
                    },
                    onSelect = { scope.launch { repo.setAudioRoute(it) } }
                )
                Text(
                    stringResource(R.string.set_audio_output_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SwitchRow(
                    title = stringResource(R.string.keep_screen_on),
                    checked = settings.keepScreenOn,
                    onCheckedChange = { scope.launch { repo.setKeepScreenOn(it) } }
                )
            }

            SectionCard(stringResource(R.string.set_sec_about), icon = Icons.Default.Info) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.set_version_build, BuildConfig.VERSION_NAME),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        enabled = !checking,
                        onClick = {
                            checking = true
                            checkMessage = null
                            scope.launch {
                                when (val result = UpdateChecker().check(settings.updateProxy)) {
                                    is UpdateCheck.Available -> updateInfo = result.info
                                    is UpdateCheck.UpToDate -> checkMessage =
                                        context.getString(R.string.upd_uptodate)

                                    is UpdateCheck.Failed -> checkMessage =
                                        context.getString(R.string.upd_failed)
                                }
                                checking = false
                                runCatching {
                                    repo.setLastUpdateCheckAt(System.currentTimeMillis())
                                }
                            }
                        }
                    ) {
                        if (checking) {
                            CircularProgressIndicator(
                                Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.upd_checking))
                        } else {
                            Text(stringResource(R.string.set_check_update))
                        }
                    }
                }
                checkMessage?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                SwitchRow(
                    title = stringResource(R.string.set_auto_update),
                    subtitle = stringResource(R.string.set_auto_update_desc),
                    checked = settings.autoCheckUpdate,
                    onCheckedChange = { scope.launch { repo.setAutoCheckUpdate(it) } }
                )
                SwitchRow(
                    title = stringResource(R.string.set_update_proxy),
                    subtitle = stringResource(R.string.set_update_proxy_desc),
                    checked = settings.updateProxy,
                    onCheckedChange = { scope.launch { repo.setUpdateProxy(it) } }
                )
            }

            SectionCard(stringResource(R.string.set_sec_data), icon = Icons.Default.Storage) {
                Text(
                    stringResource(R.string.set_export_all_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { exportLauncher.launch("spkprac-sessions.txt") }) {
                        Text(stringResource(R.string.set_export_all))
                    }
                    OutlinedButton(
                        onClick = { confirmClearHistory = true },
                        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) { Text(stringResource(R.string.set_clear_history)) }
                }
                OutlinedButton(
                    onClick = { confirmClearKey = true },
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text(stringResource(R.string.set_clear_key)) }
            }

            Text(
                stringResource(R.string.settings_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                stringResource(R.string.set_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))
        }
    }

    if (confirmClearHistory) {
        ConfirmDialog(
            title = stringResource(R.string.set_clear_history_title),
            message = stringResource(R.string.set_clear_history_msg),
            confirmLabel = stringResource(R.string.set_clear_history),
            onConfirm = {
                confirmClearHistory = false
                scope.launch {
                    sessionRepo.deleteAll()
                    snackbar.showSnackbar(context.getString(R.string.set_cleared))
                }
            },
            onDismiss = { confirmClearHistory = false }
        )
    }
    updateInfo?.let { info ->
        UpdateDialog(
            info = info,
            currentVersion = BuildConfig.VERSION_NAME,
            useProxy = settings.updateProxy,
            onDismiss = { updateInfo = null }
        )
    }

    if (confirmClearKey) {
        ConfirmDialog(
            title = stringResource(R.string.set_clear_key_title),
            message = stringResource(R.string.set_clear_key_msg),
            confirmLabel = stringResource(R.string.set_clear_key),
            onConfirm = {
                confirmClearKey = false
                scope.launch {
                    repo.setApiKey("")
                    snackbar.showSnackbar(context.getString(R.string.set_cleared))
                }
            },
            onDismiss = { confirmClearKey = false }
        )
    }
}

/* --------------------------------------------------------------- sections */

@Composable
private fun ApiSection(
    settings: AppSettings,
    apiKeyDraft: Draft,
    showKey: Boolean,
    onToggleShowKey: () -> Unit,
    testing: Boolean,
    testResult: TestResult?,
    onTest: () -> Unit,
    repo: SettingsRepository,
    onManageVoices: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val voiceDraft = rememberDraft(settings.voice)
    val endpointDraft = rememberDraft(settings.rtEndpoint)

    // Preset dropdown options (spec §5.1): system presets + this model's usable cloned voices.
    val myVoices = remember(settings.clonedVoices, settings.rtModel) {
        settings.clonedVoices
            .filter { it.status == ClonedVoice.OK && it.targetModel == settings.rtModel }
            .sortedBy { it.createdAt }
            .map { VoiceOption(label = it.name.ifBlank { it.voiceId }, value = it.voiceId) }
    }
    val voiceSelection = settings.voice.ifBlank { "Tina" }
    val selectedVoiceLabel = when {
        voiceSelection in AppSettings.VOICE_PRESETS -> voiceSelection
        else -> myVoices.firstOrNull { it.value == voiceSelection }?.label
            ?: stringResource(R.string.voice_custom)
    }
    val showVoiceDropdown = settings.rtModel.trim() == AppSettings.DEFAULT_RT_MODEL

    SectionCard(stringResource(R.string.sec_api), icon = Icons.Default.Tune) {
        LabeledTextField(
            label = stringResource(R.string.api_key),
            value = apiKeyDraft.value,
            onValueChange = {
                apiKeyDraft.value = it
                apiKeyDraft.touched = true
                scope.launch { repo.setApiKey(it) }
            },
            password = !showKey,
            trailing = {
                IconButton(onClick = onToggleShowKey) {
                    Icon(
                        if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = null
                    )
                }
            },
            supportingText = stringResource(R.string.api_key_hint)
        )
        SegmentedSelector(
            label = stringResource(R.string.protocol_flavor),
            options = listOf(
                stringResource(R.string.flavor_qwen),
                stringResource(R.string.flavor_openai)
            ),
            selectedIndex = if (settings.flavor == ProtocolFlavor.QWEN) 0 else 1,
            onSelect = {
                scope.launch {
                    repo.setFlavor(if (it == 0) ProtocolFlavor.QWEN else ProtocolFlavor.OPENAI)
                }
            }
        )
        LabeledTextField(
            label = stringResource(R.string.rt_endpoint),
            value = endpointDraft.value,
            onValueChange = {
                endpointDraft.value = it
                endpointDraft.touched = true
                scope.launch { repo.setRtEndpoint(it) }
            },
            supportingText = stringResource(R.string.rt_endpoint_hint)
        )
        val modelDraft = rememberDraft(settings.rtModel)
        LabeledTextField(
            label = stringResource(R.string.rt_model),
            value = modelDraft.value,
            onValueChange = {
                modelDraft.value = it
                modelDraft.touched = true
                scope.launch { repo.setRtModel(it) }
            }
        )
        if (showVoiceDropdown) {
            VoiceDropdownField(
                label = stringResource(R.string.voice),
                systemVoices = AppSettings.VOICE_PRESETS,
                myVoices = myVoices,
                selectedLabel = selectedVoiceLabel,
                onSelect = { value ->
                    voiceDraft.value = value
                    voiceDraft.touched = true
                    scope.launch { repo.setVoice(value) }
                }
            )
        }
        LabeledTextField(
            label = stringResource(R.string.set_voice_custom),
            value = voiceDraft.value,
            onValueChange = {
                voiceDraft.value = it
                voiceDraft.touched = true
                scope.launch { repo.setVoice(it) }
            },
            supportingText = stringResource(R.string.voice_hint)
        )
        OutlinedButton(
            onClick = onManageVoices,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                Icons.Default.RecordVoiceOver,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.voice_manage))
        }
        val cloneEndpointDraft = rememberDraft(settings.cloneEndpoint)
        LabeledTextField(
            label = stringResource(R.string.voice_clone_endpoint),
            value = cloneEndpointDraft.value,
            onValueChange = {
                cloneEndpointDraft.value = it
                cloneEndpointDraft.touched = true
                scope.launch { repo.setCloneEndpoint(it) }
            },
            supportingText = stringResource(R.string.voice_clone_endpoint_hint)
        )
        val textEndpointDraft = rememberDraft(settings.textEndpoint)
        LabeledTextField(
            label = stringResource(R.string.text_endpoint),
            value = textEndpointDraft.value,
            onValueChange = {
                textEndpointDraft.value = it
                textEndpointDraft.touched = true
                scope.launch { repo.setTextEndpoint(it) }
            },
            supportingText = stringResource(R.string.text_endpoint_hint)
        )
        val textModelDraft = rememberDraft(settings.textModel)
        LabeledTextField(
            label = stringResource(R.string.text_model),
            value = textModelDraft.value,
            onValueChange = {
                textModelDraft.value = it
                textModelDraft.touched = true
                scope.launch { repo.setTextModel(it) }
            },
            supportingText = stringResource(R.string.text_model_hint)
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = !testing && apiKeyDraft.value.isNotBlank(),
                onClick = onTest
            ) { Text(stringResource(R.string.test_connection)) }
            Spacer(Modifier.width(12.dp))
            if (testing) {
                CircularProgressIndicator(Modifier.height(20.dp).width(20.dp), strokeWidth = 2.dp)
            } else {
                val result = testResult
                if (result != null) {
                    Text(
                        text = when (result) {
                            is TestResult.Ok -> stringResource(R.string.set_test_ok)
                            is TestResult.Auth -> stringResource(R.string.set_test_auth)
                            is TestResult.Timeout -> stringResource(R.string.set_test_timeout)
                            is TestResult.Failed -> stringResource(R.string.set_test_failed)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (result is TestResult.Ok) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceDropdownField(
    label: String,
    systemVoices: List<String>,
    myVoices: List<VoiceOption>,
    selectedLabel: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            shape = MaterialTheme.shapes.medium,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 420.dp)
        ) {
            DropdownMenuItem(
                text = { DropdownGroupHeader(stringResource(R.string.voice_group_system)) },
                onClick = {},
                enabled = false
            )
            systemVoices.forEach { preset ->
                DropdownMenuItem(
                    text = { Text(preset) },
                    onClick = {
                        onSelect(preset)
                        expanded = false
                    }
                )
            }
            if (myVoices.isNotEmpty()) {
                DropdownMenuItem(
                    text = { DropdownGroupHeader(stringResource(R.string.voice_group_mine)) },
                    onClick = {},
                    enabled = false
                )
                myVoices.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        onClick = {
                            onSelect(option.value)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun DropdownGroupHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun SliderRow(
    labelFor: @Composable (Float) -> String,
    stored: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    supporting: String? = null,
    onCommit: (Float) -> Unit
) {
    var local by remember { mutableFloatStateOf(stored) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(stored) { if (!dragging) local = stored }

    Column(Modifier.fillMaxWidth()) {
        Text(labelFor(local), style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = local,
            onValueChange = {
                dragging = true
                local = it
            },
            onValueChangeFinished = {
                dragging = false
                onCommit(local)
            },
            valueRange = range,
            steps = steps
        )
        if (supporting != null) {
            Text(
                supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/* --------------------------------------------------------- connectivity */

private sealed interface TestResult {
    data object Ok : TestResult
    data object Auth : TestResult
    data object Timeout : TestResult
    data class Failed(val message: String?) : TestResult
}

/**
 * Opens a realtime websocket and waits for the first server frame.
 *
 * Implemented with a bare OkHttp websocket on purpose: diagnostics should not depend on the
 * conversation client's state machine (which is busy with reconnects, generation counters and
 * so on). The first inbound frame means the handshake, auth and model routing all worked.
 */
private suspend fun testRealtime(endpoint: String, apiKey: String, model: String): TestResult =
    withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        try {
            val url = if (endpoint.contains("model=")) endpoint
            else endpoint + (if (endpoint.contains('?')) "&" else "?") +
                "model=" + URLEncoder.encode(model, "UTF-8")
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .build()
            suspendCancellableCoroutine<TestResult> { cont ->
                val ws = client.newWebSocket(request, object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (cont.isActive) {
                            webSocket.close(1000, null)
                            cont.resume(TestResult.Ok)
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                        if (!cont.isActive) return
                        val code = response?.code
                        cont.resume(
                            when (code) {
                                401, 403 -> TestResult.Auth
                                null -> TestResult.Failed(t.message)
                                else -> TestResult.Failed("HTTP $code")
                            }
                        )
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        if (cont.isActive) cont.resume(TestResult.Failed("closed $code"))
                    }
                })
                cont.invokeOnCancellation { runCatching { ws.cancel() } }
            }
        } catch (e: Exception) {
            TestResult.Failed(e.message)
        } finally {
            client.dispatcher.executorService.shutdown()
            Unit
        }
    }
