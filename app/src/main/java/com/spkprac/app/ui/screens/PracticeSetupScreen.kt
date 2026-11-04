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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.unit.dp
import com.spkprac.app.R
import com.spkprac.app.core.SessionStartConfig
import com.spkprac.app.data.AppSettings
import com.spkprac.app.data.CorrectionPolicy
import com.spkprac.app.data.Languages
import com.spkprac.app.data.Mode
import com.spkprac.app.data.SettingsRepository
import com.spkprac.app.ui.components.AuroraBackground
import com.spkprac.app.ui.components.DropdownField
import com.spkprac.app.ui.components.LabeledTextField
import com.spkprac.app.ui.components.SectionCard
import com.spkprac.app.ui.components.SegmentedSelector
import com.spkprac.app.ui.components.SwitchRow
import com.spkprac.app.ui.components.pressable
import com.spkprac.app.ui.components.rememberTick
import com.spkprac.app.ui.theme.Brand
import com.spkprac.app.ui.theme.isDarkScheme
import kotlinx.coroutines.launch

private val LEVELS = listOf("入门 A1-A2", "初级 A2-B1", "中级 B1-B2", "高级 C1-C2")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PracticeSetupScreen(
    onBack: () -> Unit,
    onStart: (SessionStartConfig) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { SettingsRepository(context) }
    val settings by repo.settings.collectAsState(initial = AppSettings())
    val tick = rememberTick()

    var lang by remember { mutableStateOf(settings.pracLang) }
    var levelIndex by remember {
        mutableIntStateOf(LEVELS.indexOf(settings.pracLevel).coerceAtLeast(2))
    }
    var topic by remember { mutableStateOf(settings.pracTopic) }
    var policy by remember { mutableStateOf(settings.pracCorrection) }
    var report by remember { mutableStateOf(settings.pracReport) }
    var translate by remember { mutableStateOf(settings.translateEnabled) }

    // Adopt stored values once, after the settings flow has actually delivered them.
    var seeded by remember { mutableStateOf(false) }
    LaunchedEffect(settings.pracLang, settings.pracLevel, settings.pracCorrection) {
        if (!seeded) {
            lang = settings.pracLang
            levelIndex = LEVELS.indexOf(settings.pracLevel).coerceAtLeast(2)
            topic = settings.pracTopic
            policy = settings.pracCorrection
            report = settings.pracReport
            translate = settings.translateEnabled
            seeded = true
        }
    }

    val topics = listOf(
        stringResource(R.string.topic_daily),
        stringResource(R.string.topic_travel),
        stringResource(R.string.topic_work),
        stringResource(R.string.topic_interview),
        stringResource(R.string.topic_restaurant),
        stringResource(R.string.topic_shopping)
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.mode_practice), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = Color.Transparent
    ) { padding ->
        AuroraBackground(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                SectionCard(stringResource(R.string.sec_practice_config), icon = Icons.Default.RecordVoiceOver) {
                    DropdownField(
                        label = stringResource(R.string.practice_language),
                        options = Languages.list,
                        selected = Languages.list.firstOrNull { it.first == lang } ?: ("" to Languages.name(lang)),
                        optionLabel = { it.second },
                        onSelect = { lang = it.first }
                    )
                    SegmentedSelector(
                        label = stringResource(R.string.practice_level),
                        options = LEVELS,
                        selectedIndex = levelIndex,
                        onSelect = { levelIndex = it }
                    )
                    LabeledTextField(
                        label = stringResource(R.string.practice_topic),
                        value = topic,
                        onValueChange = { topic = it },
                        supportingText = stringResource(R.string.practice_topic_hint)
                    )
                    Text(stringResource(R.string.quick_topics), style = MaterialTheme.typography.bodyMedium)
                    // Chips laid out by hand so we do not depend on the experimental FlowRow.
                    topics.chunked(3).forEach { rowItems ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowItems.forEach { t ->
                                FilterChip(
                                    selected = topic == t,
                                    onClick = { topic = if (topic == t) "" else t },
                                    label = { Text(t, maxLines = 1) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                )
                            }
                        }
                    }
                }

                SectionCard(stringResource(R.string.sec_coach_behavior)) {
                    SegmentedSelector(
                        label = stringResource(R.string.correction_policy),
                        options = listOf(
                            stringResource(R.string.correction_never),
                            stringResource(R.string.correction_sometimes),
                            stringResource(R.string.correction_always)
                        ),
                        selectedIndex = policy.ordinal,
                        onSelect = { policy = CorrectionPolicy.entries[it] }
                    )
                    SwitchRow(
                        title = stringResource(R.string.end_report),
                        subtitle = stringResource(R.string.end_report_desc),
                        checked = report,
                        onCheckedChange = { report = it }
                    )
                    SwitchRow(
                        title = stringResource(R.string.subtitle_translate),
                        subtitle = stringResource(R.string.subtitle_translate_desc),
                        checked = translate,
                        onCheckedChange = { translate = it }
                    )
                }

                Spacer(Modifier.height(2.dp))
                StartButton(
                    label = stringResource(R.string.start_practice),
                    onClick = {
                        tick()
                        scope.launch {
                            repo.setPracLang(lang)
                            repo.setPracLevel(LEVELS[levelIndex])
                            repo.setPracTopic(topic)
                            repo.setPracCorrection(policy)
                            repo.setPracReport(report)
                            repo.setTranslateEnabled(translate)
                            onStart(
                                SessionStartConfig(
                                    mode = Mode.PRACTICE,
                                    pracLang = lang,
                                    pracLevel = LEVELS[levelIndex],
                                    pracTopic = topic,
                                    policy = policy
                                )
                            )
                        }
                    }
                )
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

@Composable
private fun StartButton(label: String, onClick: () -> Unit) {
    val accent = Brand.accent(isDarkScheme())
    Box(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.horizontalGradient(accent))
            .pressable(scaleTo = 0.98f, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                label,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
