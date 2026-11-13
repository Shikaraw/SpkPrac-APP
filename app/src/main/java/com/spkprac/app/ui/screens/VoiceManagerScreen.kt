package com.spkprac.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spkprac.app.R
import com.spkprac.app.data.AppSettings
import com.spkprac.app.data.ClonedVoice
import com.spkprac.app.data.SessionRepo
import com.spkprac.app.data.SettingsRepository
import com.spkprac.app.net.VoiceApi
import com.spkprac.app.net.VoiceApiException
import com.spkprac.app.net.VoiceAudio
import com.spkprac.app.net.VoiceAudioCheck
import com.spkprac.app.net.VoiceAudioError
import com.spkprac.app.net.parseVoiceTime
import com.spkprac.app.ui.components.EmptyState
import com.spkprac.app.ui.components.LabeledTextField
import com.spkprac.app.ui.components.PillLabel
import com.spkprac.app.ui.components.pressable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale

/* ---------------------------------------------------------------- screen */

/**
 * Voice manager (spec §5.2): list of cloned voices, add flow, delete and refresh.
 *
 * All network mistakes end up as snackbar text — nothing here may throw to the framework.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceManagerScreen(
    onBack: () -> Unit,
    onOpenVoice: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { SettingsRepository(context) }
    val settings by repo.settings.collectAsState(initial = AppSettings())
    val snackbar = remember { SnackbarHostState() }

    var refreshing by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ClonedVoice?>(null) }
    var pendingUse by remember { mutableStateOf<ClonedVoice?>(null) }
    var deleting by remember { mutableStateOf(false) }

    val api = remember(settings.cloneEndpoint, settings.apiKey, settings.rtModel) {
        VoiceApi(settings.cloneEndpoint, settings.apiKey, settings.rtModel)
    }
    val voices = remember(settings.clonedVoices) {
        settings.clonedVoices.sortedWith(
            compareByDescending<ClonedVoice> { it.createdAt }
                .thenByDescending { it.updatedAt }
        )
    }

    fun showError(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    fun refresh() {
        if (refreshing) return
        if (settings.apiKey.isBlank()) {
            showError(context.getString(R.string.voice_err_no_key))
            return
        }
        refreshing = true
        scope.launch {
            try {
                val merged = mergeRemoteVoices(api, settings.clonedVoices)
                repo.setClonedVoices(merged)
                // If the currently selected cloned voice vanished server-side, fall back to Tina.
                val latest = runCatching { repo.settings.first() }.getOrNull()
                val current = latest?.voice.orEmpty()
                val wasCloned = settings.clonedVoices.any { it.voiceId == current }
                if (wasCloned && merged.none { it.voiceId == current }) repo.setVoice("Tina")
                snackbar.showSnackbar(context.getString(R.string.voice_status_refreshed))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                snackbar.showSnackbar(
                    context.getString(R.string.voice_refresh_failed, e.readableMessage())
                )
            } finally {
                refreshing = false
            }
        }
    }

    fun performDelete(voice: ClonedVoice) {
        if (deleting) return
        if (settings.apiKey.isBlank()) {
            pendingDelete = null
            showError(context.getString(R.string.voice_err_no_key))
            return
        }
        deleting = true
        scope.launch {
            try {
                api.deleteVoice(voice.voiceId)
                val latest = runCatching { repo.settings.first() }.getOrNull()
                val currentList = latest?.clonedVoices ?: settings.clonedVoices
                repo.setClonedVoices(currentList.filterNot { it.voiceId == voice.voiceId })
                if (latest?.voice == voice.voiceId) repo.setVoice("Tina")
                pendingDelete = null
                snackbar.showSnackbar(context.getString(R.string.voice_delete_done))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pendingDelete = null
                snackbar.showSnackbar(
                    context.getString(R.string.voice_delete_failed, e.readableMessage())
                )
            } finally {
                deleting = false
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.voice_title),
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    if (refreshing) {
                        CircularProgressIndicator(
                            Modifier
                                .padding(end = 8.dp)
                                .size(20.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        IconButton(onClick = { refresh() }) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.voice_refresh)
                            )
                        }
                    }
                    TextButton(onClick = { showAdd = true }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.voice_add))
                    }
                }
            )
        }
    ) { padding ->
        when {
            voices.isEmpty() && refreshing -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }

            voices.isEmpty() -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                EmptyState(
                    icon = Icons.Default.RecordVoiceOver,
                    title = stringResource(R.string.voice_empty),
                    subtitle = stringResource(R.string.voice_empty_desc)
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { showAdd = true }) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.voice_add))
                }
            }

            else -> LazyColumn(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(voices, key = { it.voiceId }) { voice ->
                    VoiceRow(
                        voice = voice,
                        isCurrent = settings.voice == voice.voiceId,
                        onClick = { onOpenVoice(voice.voiceId) },
                        onDelete = { pendingDelete = voice }
                    )
                }
            }
        }
    }

    if (showAdd) {
        AddVoiceDialog(
            settings = settings,
            api = api,
            repo = repo,
            onDismiss = { showAdd = false },
            onCreated = { voice ->
                showAdd = false
                showError(context.getString(R.string.voice_created))
                if (voice.status == ClonedVoice.OK) {
                    if (settings.voice.isBlank()) {
                        scope.launch { runCatching { repo.setVoice(voice.voiceId) } }
                    } else {
                        pendingUse = voice
                    }
                }
            },
            onStillDeploying = {
                showAdd = false
                showError(context.getString(R.string.voice_warn_still_deploying))
            }
        )
    }

    pendingUse?.let { voice ->
        AlertDialog(
            onDismissRequest = { pendingUse = null },
            title = { Text(stringResource(R.string.voice_use_title)) },
            text = { Text(stringResource(R.string.voice_use_message, voice.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingUse = null
                        scope.launch {
                            runCatching { repo.setVoice(voice.voiceId) }
                            snackbar.showSnackbar(context.getString(R.string.voice_current_set))
                        }
                    }
                ) { Text(stringResource(R.string.voice_use_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingUse = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    pendingDelete?.let { voice ->
        AlertDialog(
            onDismissRequest = { if (!deleting) pendingDelete = null },
            title = { Text(stringResource(R.string.voice_delete)) },
            text = { Text(stringResource(R.string.voice_delete_confirm, voice.name)) },
            confirmButton = {
                TextButton(
                    enabled = !deleting,
                    onClick = { performDelete(voice) }
                ) {
                    Text(
                        stringResource(R.string.voice_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(enabled = !deleting, onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

/* ------------------------------------------------------------ list items */

@Composable
private fun VoiceRow(
    voice: ClonedVoice,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .pressable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = MaterialTheme.shapes.large
    ) {
        Row(
            Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        voice.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(8.dp))
                    VoiceStatusBadge(voice.status)
                    if (isCurrent) {
                        Spacer(Modifier.width(6.dp))
                        PillLabel(
                            text = stringResource(R.string.voice_set_current),
                            container = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    voice.targetModel.ifBlank { stringResource(R.string.voice_unknown_model) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    voice.voiceId,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.voice_delete),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/* ------------------------------------------------------------ add dialog */

private enum class AddPhase { IDLE, UPLOADING, POLLING }

@Composable
private fun AddVoiceDialog(
    settings: AppSettings,
    api: VoiceApi,
    repo: SettingsRepository,
    onDismiss: () -> Unit,
    onCreated: (ClonedVoice) -> Unit,
    onStillDeploying: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf("") }
    var check by remember { mutableStateOf<VoiceAudioCheck?>(null) }
    var reading by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf(AddPhase.IDLE) }
    var error by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        reading = true
        error = null
        scope.launch {
            val result = runCatching { VoiceAudio.inspect(context, uri) }.getOrNull()
            if (result == null) {
                error = context.getString(R.string.voice_err_read)
                check = null
            } else {
                check = result
            }
            reading = false
        }
    }

    val busy = phase != AddPhase.IDLE
    val selected = check
    val validationError = selected?.let { validationMessage(context, it) }
    val canCreate = !busy && !reading && name.isNotBlank() &&
        selected != null && selected.error == null

    fun startCreate() {
        if (busy) return
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            error = context.getString(R.string.voice_err_name_empty)
            return
        }
        if (settings.apiKey.isBlank()) {
            error = context.getString(R.string.voice_err_no_key)
            return
        }
        val audio = selected ?: return
        if (audio.error != null) return

        error = null
        phase = AddPhase.UPLOADING
        scope.launch {
            try {
                val dataUri = VoiceAudio.toDataUri(context, audio.info)
                phase = AddPhase.POLLING
                val voiceId = api.createVoice(
                    targetModel = settings.rtModel,
                    prefix = VoiceAudio.prefixFor(trimmed),
                    audioDataUri = dataUri
                )
                val now = System.currentTimeMillis()
                val created = ClonedVoice(
                    voiceId = voiceId,
                    name = trimmed,
                    targetModel = settings.rtModel,
                    status = ClonedVoice.DEPLOYING,
                    createdAt = now,
                    updatedAt = now,
                    audioName = audio.info.name
                )
                // Persist before polling: a killed app must not lose a voice that already exists
                // server-side.
                appendStoredVoice(repo, created)

                val poll = try {
                    api.pollStatus(voiceId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Created but status unknown (network hiccup): treat as still under review.
                    phase = AddPhase.IDLE
                    onStillDeploying()
                    return@launch
                }
                phase = AddPhase.IDLE
                when (poll) {
                    is VoiceApi.VoicePoll.Ok -> {
                        val settled = updateStoredVoice(repo, voiceId) { stored ->
                            stored.copy(
                                status = ClonedVoice.OK,
                                targetModel = poll.query.targetModel.ifBlank { stored.targetModel },
                                updatedAt = System.currentTimeMillis()
                            )
                        }
                        onCreated(settled ?: created.copy(status = ClonedVoice.OK))
                    }

                    is VoiceApi.VoicePoll.Undeployed -> {
                        updateStoredVoice(repo, voiceId) { stored ->
                            stored.copy(
                                status = ClonedVoice.UNDEPLOYED,
                                updatedAt = System.currentTimeMillis()
                            )
                        }
                        error = context.getString(R.string.voice_undeployed)
                    }

                    is VoiceApi.VoicePoll.Pending -> onStillDeploying()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                phase = AddPhase.IDLE
                error = context.getString(
                    R.string.voice_create_failed,
                    e.readableMessage()
                )
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.voice_add)) },
        text = {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                LabeledTextField(
                    label = stringResource(R.string.voice_name),
                    value = name,
                    onValueChange = { name = it.take(20) },
                    supportingText = stringResource(R.string.voice_name_hint)
                )
                OutlinedButton(
                    enabled = !busy && !reading,
                    onClick = { picker.launch(VoiceAudio.PICKER_MIME_TYPES) }
                ) {
                    Icon(Icons.Default.AudioFile, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(
                            if (check == null) R.string.voice_pick_audio else R.string.voice_pick_change
                        )
                    )
                }
                if (reading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.voice_file_reading), style = MaterialTheme.typography.bodySmall)
                    }
                }
                selected?.let { it ->
                    Text(
                        audioInfoText(context, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (it.error == null) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                    validationError?.let { message ->
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (it.durationWarning) {
                        Text(
                            stringResource(R.string.voice_warn_duration),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                    if (it.channelsWarning) {
                        Text(
                            stringResource(R.string.voice_warn_channels),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
                Text(
                    stringResource(R.string.voice_audio_tip),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(R.string.voice_audio_content_tip),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(
                                if (phase == AddPhase.UPLOADING) R.string.voice_uploading
                                else R.string.voice_creating
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                error?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { startCreate() }, enabled = canCreate) {
                Text(
                    stringResource(
                        if (busy) R.string.voice_creating else R.string.voice_create
                    )
                )
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/* --------------------------------------------------------------- helpers */

/** Human-readable validation result for a picked clip (spec §3 must name the failing item). */
private fun validationMessage(context: android.content.Context, check: VoiceAudioCheck): String? =
    when (check.error) {
        VoiceAudioError.FORMAT -> context.getString(R.string.voice_err_format)
        VoiceAudioError.SIZE -> context.getString(R.string.voice_err_size)
        VoiceAudioError.TOO_SHORT -> context.getString(R.string.voice_err_too_short)
        VoiceAudioError.TOO_LONG -> context.getString(R.string.voice_err_too_long)
        VoiceAudioError.SAMPLE_RATE -> context.getString(
            R.string.voice_err_sample_rate,
            check.info.sampleRate
        )
        VoiceAudioError.UNREADABLE -> context.getString(R.string.voice_err_read)
        null -> null
    }

private fun audioInfoText(context: android.content.Context, check: VoiceAudioCheck): String {
    val info = check.info
    val sizeMb = String.format(Locale.US, "%.1f", info.sizeBytes / (1024.0 * 1024.0))
    return if (info.durationMs > 0L) {
        context.getString(
            R.string.voice_audio_selected,
            info.name,
            (info.durationMs / 1000L).toInt(),
            sizeMb
        )
    } else {
        context.getString(R.string.voice_audio_selected_unknown, info.name, sizeMb)
    }
}

/** "My voices" status pill, shared with the detail screen. */
@Composable
internal fun VoiceStatusBadge(status: String) {
    val (container, content) = when (status.uppercase(Locale.US)) {
        ClonedVoice.OK -> MaterialTheme.colorScheme.primaryContainer to
            MaterialTheme.colorScheme.onPrimaryContainer
        ClonedVoice.UNDEPLOYED -> MaterialTheme.colorScheme.errorContainer to
            MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.tertiaryContainer to
            MaterialTheme.colorScheme.onTertiaryContainer
    }
    PillLabel(
        text = voiceStatusLabel(status),
        container = container,
        contentColor = content
    )
}

@Composable
internal fun voiceStatusLabel(status: String): String = when (status.uppercase(Locale.US)) {
    ClonedVoice.OK -> stringResource(R.string.voice_ok)
    ClonedVoice.UNDEPLOYED -> stringResource(R.string.voice_undeployed)
    else -> stringResource(R.string.voice_deploying)
}

@Composable
internal fun voiceStatusHint(status: String): String = when (status.uppercase(Locale.US)) {
    ClonedVoice.OK -> stringResource(R.string.voice_status_hint_ok)
    ClonedVoice.UNDEPLOYED -> stringResource(R.string.voice_status_hint_undeployed)
    else -> stringResource(R.string.voice_status_hint_deploying)
}

internal fun formatVoiceTime(ts: Long): String =
    if (ts <= 0L) "—" else SessionRepo.formatDate(ts)

/** Never show a raw class name in a snackbar. */
internal fun Throwable.readableMessage(): String {
    val text = message?.trim().orEmpty()
    return text.ifBlank { javaClass.simpleName }
}

/* ------------------------------------------------------ merge with server */

/**
 * `list_voice` merge strategy from spec §4:
 * 1. query one page (100/0),
 * 2. update status/last-sync for known ids,
 * 3. add remote-only entries (name = voice_id tail) and fill targetModel via query_voice,
 *    at most 30 queries — failures stay as "unknown model",
 * 4. drop local entries the server no longer returns.
 */
private suspend fun mergeRemoteVoices(
    api: VoiceApi,
    local: List<ClonedVoice>
): List<ClonedVoice> {
    val remote = api.listVoices(pageIndex = 0, pageSize = 100)
    val now = System.currentTimeMillis()
    val localById = local.associateBy { it.voiceId }
    var queriesLeft = 30
    val merged = ArrayList<ClonedVoice>(remote.size)

    for (item in remote) {
        val existing = localById[item.voiceId]
        if (existing != null) {
            merged += existing.copy(
                status = item.status.ifBlank { existing.status },
                updatedAt = now
            )
            continue
        }
        var targetModel = ""
        var status = item.status.ifBlank { ClonedVoice.DEPLOYING }
        var createdAt = parseVoiceTime(item.gmtCreate) ?: now
        if (queriesLeft > 0) {
            queriesLeft -= 1
            val detail = runCatching { api.queryVoice(item.voiceId) }.getOrNull()
            if (detail != null) {
                targetModel = detail.targetModel
                if (detail.status.isNotBlank()) status = detail.status
                createdAt = parseVoiceTime(detail.gmtCreate) ?: createdAt
            }
        }
        merged += ClonedVoice(
            voiceId = item.voiceId,
            name = defaultVoiceName(item.voiceId),
            targetModel = targetModel,
            status = status,
            createdAt = createdAt,
            updatedAt = now,
            audioName = null
        )
    }
    return merged
}

/** `name` defaults to the last segment of voice_id (spec §4.3). */
private fun defaultVoiceName(voiceId: String): String {
    val tail = voiceId.substringAfterLast('-').substringAfterLast('_')
    return tail.ifBlank { voiceId }
}

private suspend fun appendStoredVoice(repo: SettingsRepository, voice: ClonedVoice) {
    val current = runCatching { repo.settings.first() }.getOrNull()?.clonedVoices ?: emptyList()
    if (current.none { it.voiceId == voice.voiceId }) {
        runCatching { repo.setClonedVoices(current + voice) }
    }
}

private suspend fun updateStoredVoice(
    repo: SettingsRepository,
    voiceId: String,
    transform: (ClonedVoice) -> ClonedVoice
): ClonedVoice? {
    val current = runCatching { repo.settings.first() }.getOrNull()?.clonedVoices ?: return null
    var updated: ClonedVoice? = null
    val next = current.map { voice ->
        if (voice.voiceId == voiceId) transform(voice).also { updated = it } else voice
    }
    if (updated != null) runCatching { repo.setClonedVoices(next) }
    return updated
}
