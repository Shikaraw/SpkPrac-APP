package com.spkprac.app.ui.screens

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.spkprac.app.R
import com.spkprac.app.data.AppSettings
import com.spkprac.app.data.HistoryStats
import com.spkprac.app.data.Languages
import com.spkprac.app.data.Mode
import com.spkprac.app.data.SessionMeta
import com.spkprac.app.data.SessionRepo
import com.spkprac.app.data.SettingsRepository
import com.spkprac.app.ui.components.AuroraBackground
import com.spkprac.app.ui.components.EmptyState
import com.spkprac.app.ui.components.StatTile
import com.spkprac.app.ui.components.pressable
import com.spkprac.app.ui.components.rememberTick
import com.spkprac.app.ui.theme.Brand
import com.spkprac.app.ui.theme.isDarkScheme
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    onChat: () -> Unit,
    onPractice: () -> Unit,
    onSettings: () -> Unit,
    onOpenHistory: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { SessionRepo(context) }
    val prefs = remember { SettingsRepository(context) }
    val settings by prefs.settings.collectAsState(initial = AppSettings())

    var reloadKey by remember { mutableIntStateOf(0) }
    var sessions by remember { mutableStateOf<List<SessionMeta>>(emptyList()) }
    var stats by remember { mutableStateOf(HistoryStats()) }
    LaunchedEffect(reloadKey) {
        val metas = runCatching { repo.list() }.getOrDefault(emptyList())
        sessions = metas
        stats = runCatching { repo.stats() }.getOrDefault(HistoryStats())
    }

    var query by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<SessionMeta?>(null) }
    val tick = rememberTick()

    val filtered = remember(sessions, query) {
        val q = query.trim()
        if (q.isEmpty()) sessions
        else sessions.filter {
            it.title.contains(q, ignoreCase = true) ||
                Languages.name(it.language).contains(q, ignoreCase = true) ||
                (it.mode == Mode.PRACTICE && "practice".contains(q, ignoreCase = true))
        }
    }

    AuroraBackground(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item("hero") {
                HeroHeader(onSettings)
            }

            if (stats.sessions > 0) {
                item("stats") {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile(
                            value = stats.sessions.toString(),
                            label = stringResource(R.string.home_stat_sessions),
                            modifier = Modifier.weight(1f)
                        )
                        StatTile(
                            value = SessionRepo.formatDurationShort(stats.totalMs),
                            label = stringResource(R.string.home_stat_time),
                            modifier = Modifier.weight(1f),
                            accent = MaterialTheme.colorScheme.tertiary
                        )
                        StatTile(
                            value = stats.streakDays.toString(),
                            label = stringResource(R.string.home_stat_streak),
                            modifier = Modifier.weight(1f),
                            accent = Brand.Aqua
                        )
                    }
                }
            }

            if (settings.apiKey.isBlank()) {
                item("nokey") {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text(
                            stringResource(R.string.home_no_apikey_hint),
                            Modifier.padding(14.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }

            item("modes") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ModeCard(
                        icon = Icons.AutoMirrored.Filled.Chat,
                        title = stringResource(R.string.mode_chat),
                        subtitle = stringResource(R.string.mode_chat_desc),
                        cta = stringResource(R.string.home_start_chat),
                        gradient = Brand.accent(isDarkScheme()),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            tick()
                            onChat()
                        }
                    )
                    ModeCard(
                        icon = Icons.Default.RecordVoiceOver,
                        title = stringResource(R.string.mode_practice),
                        subtitle = stringResource(R.string.mode_practice_desc),
                        cta = stringResource(R.string.home_start_practice),
                        gradient = Brand.warm(isDarkScheme()),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            tick()
                            onPractice()
                        }
                    )
                }
            }

            item("recent_header") {
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.recent_sessions),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    if (sessions.isNotEmpty()) {
                        Text(
                            sessions.size.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (sessions.isNotEmpty()) {
                item("search") {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.home_search_hint)) },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
                    )
                }
            }

            if (filtered.isEmpty()) {
                item("empty") {
                    EmptyState(
                        icon = if (sessions.isEmpty()) Icons.Default.History else Icons.Default.Search,
                        title = if (sessions.isEmpty()) stringResource(R.string.no_sessions_hint)
                        else stringResource(R.string.home_no_results),
                        subtitle = null
                    )
                }
            } else {
                items(filtered, key = { it.id }) { meta ->
                    SessionRow(
                        meta = meta,
                        onClick = { onOpenHistory(meta.id) },
                        onDelete = { pendingDelete = meta }
                    )
                }
            }
        }
    }

    pendingDelete?.let { meta ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.home_delete_title)) },
            text = { Text(stringResource(R.string.home_delete_msg)) },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        pendingDelete = null
                        scope.launch {
                            repo.delete(meta.id)
                            reloadKey++
                        }
                    }
                ) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun HeroHeader(onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                stringResource(R.string.home_tagline),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)
        ) {
            IconButton(onClick = onSettings) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = stringResource(R.string.settings),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ModeCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    cta: String,
    gradient: List<Color>,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier
            .height(168.dp)
            .pressable(scaleTo = 0.97f, onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(gradient))
                .padding(16.dp)
        ) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                Box(
                    Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.22f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                }
                Column {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.85f),
                        maxLines = 2
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            cta,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("→", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionRow(meta: SessionMeta, onClick: () -> Unit, onDelete: () -> Unit) {
    val practice = meta.mode == Mode.PRACTICE
    val accent = if (practice) Brand.warm(isDarkScheme()).first() else Brand.accent(isDarkScheme()).first()
    Card(
        Modifier
            .fillMaxWidth()
            .pressable(scaleTo = 0.985f, onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (practice) Icons.Default.RecordVoiceOver else Icons.AutoMirrored.Filled.Chat,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(19.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    meta.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        if (meta.language.isNotBlank()) append(Languages.name(meta.language)).append(" · ")
                        append(SessionRepo.formatDate(meta.createdAt))
                        append(" · ").append(SessionRepo.formatDuration(meta.durationMs))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(19.dp)
                )
            }
        }
    }
}
