package com.spkprac.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.spkprac.app.R
import com.spkprac.app.data.AppSettings
import com.spkprac.app.data.ClonedVoice
import com.spkprac.app.data.SettingsRepository
import com.spkprac.app.net.VoiceApi
import com.spkprac.app.net.parseVoiceTime
import com.spkprac.app.ui.components.EmptyState
import com.spkprac.app.ui.components.SectionCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/* ---------------------------------------------------------------- screen */

/**
 * Voice detail page (spec §5.4): metadata, copyable voice_id, local rename, status refresh and
 * deletion. Rename only touches the local display name; everything else is synced from the server.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceDetailScreen(
    voiceId: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { SettingsRepository(context) }
    val settings by repo.settings.collectAsState(initial = AppSettings())
    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }

    val voice = settings.clonedVoices.firstOrNull { it.voiceId == voiceId }

    var refreshing by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }

    val api = remember(settings.cloneEndpoint, settings.apiKey, settings.rtModel) {
        VoiceApi(settings.cloneEndpoint, settings.apiKey, settings.rtModel)
    }

    fun toast(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    fun refreshStatus() {
        if (refreshing) return
        if (settings.apiKey.isBlank()) {
            toast(context.getString(R.string.voice_err_no_key))
            return
        }
        refreshing = true
        scope.launch {
            try {
                val query = api.queryVoice(voiceId)
                val latest = runCatching { repo.settings.first() }.getOrNull()
                if (latest != null) {
                    val updated = latest.clonedVoices.map { item ->
                        if (item.voiceId != voiceId) {
                            item
                        } else {
                            item.copy(
                                status = query.status.ifBlank { item.status },
                                targetModel = query.targetModel.ifBlank { item.targetModel },
                                updatedAt = System.currentTimeMillis(),
                                createdAt = if (item.createdAt > 0L) {
                                    item.createdAt
                                } else {
                                    parseVoiceTime(query.gmtCreate) ?: 0L
                                }
                            )
                        }
                    }
                    repo.setClonedVoices(updated)
                }
                toast(context.getString(R.string.voice_status_refreshed))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(context.getString(R.string.voice_refresh_failed, e.readableMessage()))
            } finally {
                refreshing = false
            }
        }
    }

    fun performDelete(target: ClonedVoice) {
        if (deleting) return
        if (settings.apiKey.isBlank()) {
            showDelete = false
            toast(context.getString(R.string.voice_err_no_key))
            return
        }
        deleting = true
        scope.launch {
            try {
                api.deleteVoice(target.voiceId)
                val latest = runCatching { repo.settings.first() }.getOrNull()
                if (latest != null) {
                    repo.setClonedVoices(latest.clonedVoices.filterNot { it.voiceId == target.voiceId })
                    // Spec §5.5: deleting the selected voice falls back to the default preset.
                    if (latest.voice == target.voiceId) repo.setVoice("Tina")
                }
                showDelete = false
                Toast.makeText(
                    context,
                    context.getString(R.string.voice_delete_done),
                    Toast.LENGTH_SHORT
                ).show()
                onBack()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                deleting = false
                toast(context.getString(R.string.voice_delete_failed, e.readableMessage()))
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.voice_detail),
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
                }
            )
        }
    ) { padding ->
        if (voice == null) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                EmptyState(
                    icon = Icons.Default.RecordVoiceOver,
                    title = stringResource(R.string.voice_not_found),
                    subtitle = null
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onBack) {
                    Text(stringResource(R.string.back))
                }
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                SectionCard(
                    title = voice.name,
                    icon = Icons.Default.RecordVoiceOver
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        VoiceStatusBadge(voice.status)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            voiceStatusHint(voice.status),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { showRename = true }) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = stringResource(R.string.voice_rename),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    DetailInfoRow(
                        label = stringResource(R.string.voice_target_model),
                        value = voice.targetModel.ifBlank {
                            stringResource(R.string.voice_unknown_model)
                        }
                    )
                    DetailInfoRow(
                        label = stringResource(R.string.voice_voice_id),
                        value = voice.voiceId,
                        monospace = true
                    ) {
                        IconButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(voice.voiceId))
                                toast(context.getString(R.string.voice_copied))
                            }
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = stringResource(R.string.voice_copy_id),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    DetailInfoRow(
                        label = stringResource(R.string.voice_created_at),
                        value = formatVoiceTime(voice.createdAt)
                    )
                    DetailInfoRow(
                        label = stringResource(R.string.voice_synced_at),
                        value = formatVoiceTime(voice.updatedAt)
                    )
                    DetailInfoRow(
                        label = stringResource(R.string.voice_source_file),
                        value = voice.audioName?.takeIf { it.isNotBlank() } ?: "—"
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        enabled = !refreshing && !deleting,
                        onClick = { refreshStatus() }
                    ) {
                        if (refreshing) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(stringResource(R.string.voice_refresh))
                    }
                    Button(
                        enabled = !deleting && !refreshing,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        onClick = { showDelete = true }
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.voice_delete))
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showRename && voice != null) {
        var draft by remember(voice.voiceId) { mutableStateOf(voice.name) }
        var renameError by remember(voice.voiceId) { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text(stringResource(R.string.voice_rename_title)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it.take(20) },
                        label = { Text(stringResource(R.string.voice_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    renameError?.let { message ->
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = draft.trim()
                        if (trimmed.isEmpty()) {
                            renameError = context.getString(R.string.voice_err_name_empty)
                        } else {
                            scope.launch {
                                val latest = runCatching { repo.settings.first() }.getOrNull()
                                if (latest != null) {
                                    runCatching {
                                        repo.setClonedVoices(
                                            latest.clonedVoices.map { item ->
                                                if (item.voiceId == voiceId) {
                                                    item.copy(name = trimmed)
                                                } else {
                                                    item
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                            showRename = false
                        }
                    }
                ) { Text(stringResource(R.string.voice_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showRename = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showDelete && voice != null) {
        AlertDialog(
            onDismissRequest = { if (!deleting) showDelete = false },
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
                TextButton(enabled = !deleting, onClick = { showDelete = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

/* --------------------------------------------------------------- widgets */

@Composable
private fun DetailInfoRow(
    label: String,
    value: String,
    monospace: Boolean = false,
    trailing: (@Composable () -> Unit)? = null
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = if (monospace) FontFamily.Monospace else null,
                modifier = Modifier.weight(1f)
            )
            trailing?.invoke()
        }
    }
}
