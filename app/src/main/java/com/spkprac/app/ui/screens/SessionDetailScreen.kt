package com.spkprac.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.spkprac.app.R
import com.spkprac.app.data.ChatMessage
import com.spkprac.app.data.Languages
import com.spkprac.app.data.Mode
import com.spkprac.app.data.Role
import com.spkprac.app.data.SessionRepo
import com.spkprac.app.data.StoredSession
import com.spkprac.app.ui.components.AuroraBackground
import com.spkprac.app.ui.components.EmptyState
import com.spkprac.app.ui.components.MarkdownText
import com.spkprac.app.ui.components.PillLabel
import com.spkprac.app.ui.theme.Brand
import com.spkprac.app.ui.theme.captionTextStyle
import com.spkprac.app.ui.theme.isDarkScheme
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(
    id: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { SessionRepo(context) }

    var session by remember { mutableStateOf<StoredSession?>(null) }
    var loading by remember { mutableStateOf(true) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(id) {
        session = runCatching { repo.load(id) }.getOrNull()
        loading = false
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri ->
        val s = session ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(SessionRepo.exportMarkdown(s).toByteArray(Charsets.UTF_8))
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        session?.meta?.title ?: stringResource(R.string.recent_sessions),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    val s = session
                    if (s != null) {
                        IconButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, SessionRepo.exportText(s))
                            }
                            context.startActivity(Intent.createChooser(send, null))
                        }) {
                            Icon(Icons.Default.Share, contentDescription = stringResource(R.string.share))
                        }
                        IconButton(onClick = { exportLauncher.launch("${s.meta.id}.md") }) {
                            Icon(Icons.Default.SaveAlt, contentDescription = stringResource(R.string.detail_export))
                        }
                        IconButton(onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText(s.meta.title, SessionRepo.exportText(s)))
                            Toast.makeText(context, R.string.detail_copied, Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.detail_copy))
                        }
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = Color.Transparent
    ) { padding ->
        AuroraBackground(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().padding(padding)) {
                when {
                    loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                    session == null -> EmptyState(
                        icon = Icons.Default.HourglassEmpty,
                        title = stringResource(R.string.detail_missing),
                        subtitle = null,
                        modifier = Modifier.align(Alignment.Center)
                    )

                    else -> DetailContent(session!!)
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_session_title)) },
            text = { Text(stringResource(R.string.delete_session_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        repo.delete(id)
                        onBack()
                    }
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@Composable
private fun DetailContent(s: StoredSession) {
    val practice = s.meta.mode == Mode.PRACTICE
    val accent = if (practice) Brand.warm(isDarkScheme()).first() else Brand.accent(isDarkScheme()).first()

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 28.dp)
    ) {
        item("meta") {
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = accent.copy(alpha = 0.16f)
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (practice) Icons.Default.RecordVoiceOver else Icons.AutoMirrored.Filled.Chat,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            if (practice) stringResource(R.string.mode_practice)
                            else stringResource(R.string.mode_chat),
                            style = MaterialTheme.typography.labelSmall,
                            color = accent,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                PillLabel(
                    text = SessionRepo.formatDuration(s.meta.durationMs),
                    container = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
                PillLabel(
                    text = stringResource(R.string.msg_count, s.meta.messageCount),
                    container = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item("date") {
            Text(
                buildString {
                    if (s.meta.language.isNotBlank()) {
                        append(stringResource(R.string.detail_language_fmt, Languages.name(s.meta.language)))
                        append("  ·  ")
                    }
                    append(SessionRepo.formatDate(s.meta.createdAt))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        s.report?.let { rep ->
            item("report") {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
                    ),
                    shape = RoundedCornerShape(22.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.practice_report),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                Icons.Default.RecordVoiceOver,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        MarkdownText(rep.content)
                    }
                }
            }
        }

        item("divider") {
            Text(
                stringResource(R.string.recent_sessions),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
            )
        }

        items(s.messages, key = { it.id }) { m ->
            DetailBubble(m)
        }
    }
}

@Composable
private fun DetailBubble(m: ChatMessage) {
    val isUser = m.role == Role.USER
    val accent = Brand.accent(isDarkScheme())
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Text(
            stringResource(if (isUser) R.string.role_me else R.string.role_ai),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(3.dp))
        Surface(
            color = if (isUser) Color.Transparent else MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomEnd = if (isUser) 6.dp else 18.dp,
                bottomStart = if (isUser) 18.dp else 6.dp
            ),
            tonalElevation = if (isUser) 0.dp else 1.dp
        ) {
            Box(
                Modifier
                    .then(
                        if (isUser) Modifier.background(
                            androidx.compose.ui.graphics.Brush.linearGradient(accent),
                            RoundedCornerShape(
                                topStart = 18.dp,
                                topEnd = 18.dp,
                                bottomEnd = 6.dp,
                                bottomStart = 18.dp
                            )
                        ) else Modifier
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .widthIn(max = 320.dp)
            ) {
                Text(
                    m.text,
                    color = if (isUser) Color.White else MaterialTheme.colorScheme.onSurface,
                    style = captionTextStyle()
                )
            }
        }
        m.translation?.let {
            Spacer(Modifier.height(3.dp))
            Text(
                it,
                Modifier.widthIn(max = 300.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(6.dp))
    }
}
