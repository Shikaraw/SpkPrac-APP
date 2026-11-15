package com.spkprac.app.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.util.TypedValue
import android.view.ActionMode
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.spkprac.app.R
import com.spkprac.app.core.Prompts
import com.spkprac.app.data.AppSettings
import com.spkprac.app.data.Languages
import com.spkprac.app.data.SettingsRepository
import com.spkprac.app.net.TextApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/* ---------------------------------------------------------- selectable text */

private const val SELECTION_ITEM_ORDER = 100

/**
 * Read-only text the user can long-press and select (v1.3.0 spec §5.2).
 *
 * Compose's own [androidx.compose.material3.Text] cannot hand its selection back to us — the
 * platform text toolbar is driven by a `TextView` editor. So this wraps a real [TextView] via
 * [AndroidView], turns on selection and appends two entries to the *system* selection menu
 * through [TextView.setCustomSelectionActionModeCallback].
 *
 * The typography is copied from the Compose [style]/[color] pair on every change so the bubble looks
 * exactly like the plain-text version it replaced. Selection entry points live in
 * [rememberSelectionActions]; the actual dialog is rendered by [SelectionDialogHost].
 */
@Composable
fun SelectableText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = MaterialTheme.colorScheme.onSurface,
    highlightColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
    onTranslate: (String) -> Unit,
    onAskAi: (String) -> Unit
) {
    val callbacks = rememberUpdatedState(
        SelectionMenuCallbacks(
            translateLabel = stringResource(R.string.ask_translate),
            askLabel = stringResource(R.string.ask_ai),
            onTranslate = onTranslate,
            onAskAi = onAskAi
        )
    )
    // Stable menu ids: re-created only when the composable enters composition, so entries can be
    // de-duplicated every time the system calls onPrepareActionMode.
    val menuIds = remember { intArrayOf(View.generateViewId(), View.generateViewId()) }
    val styleKey = remember(style, color, highlightColor) { StyleKey(style, color, highlightColor) }

    AndroidView(
        modifier = modifier,
        factory = { ctx -> createSelectableTextView(ctx, callbacks, menuIds) },
        update = { view ->
            if (view.text?.toString() != text) view.setText(text)
            if (view.tag != styleKey) {
                applyComposeStyle(view, style, color, highlightColor)
                view.tag = styleKey
            }
        }
    )
}

private class SelectionMenuCallbacks(
    val translateLabel: String,
    val askLabel: String,
    val onTranslate: (String) -> Unit,
    val onAskAi: (String) -> Unit
)

private data class StyleKey(val style: TextStyle, val color: Color, val highlight: Color)

private fun createSelectableTextView(
    context: Context,
    callbacks: State<SelectionMenuCallbacks>,
    menuIds: IntArray
): TextView = TextView(context).apply {
    val self = this
    setTextIsSelectable(true)
    setIncludeFontPadding(false)
    setPadding(0, 0, 0, 0)
    setBackgroundColor(android.graphics.Color.TRANSPARENT)
    gravity = Gravity.TOP or Gravity.START
    customSelectionActionModeCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu?): Boolean = true

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu?): Boolean {
            menu ?: return false
            // The menu may be prepared more than once for the same selection — never add twice.
            menu.removeItem(menuIds[0])
            menu.removeItem(menuIds[1])

            val start = self.selectionStart
            val end = self.selectionEnd
            if (start < 0 || end <= start) return true
            val selected = self.text?.subSequence(start, end)?.toString()?.trim().orEmpty()
            if (selected.isEmpty()) return true

            val cb = callbacks.value
            menu.add(Menu.NONE, menuIds[0], SELECTION_ITEM_ORDER, cb.translateLabel).apply {
                setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                setOnMenuItemClickListener {
                    mode.finish()
                    cb.onTranslate(selected)
                    true
                }
            }
            menu.add(Menu.NONE, menuIds[1], SELECTION_ITEM_ORDER + 1, cb.askLabel).apply {
                setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                setOnMenuItemClickListener {
                    mode.finish()
                    cb.onAskAi(selected)
                    true
                }
            }
            return true
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem?): Boolean = false

        override fun onDestroyActionMode(mode: ActionMode) = Unit
    }
}

/**
 * Copy the Compose typography onto the backing [TextView]. The line height needs special care:
 * Compose measures whole line boxes while TextView adds "line spacing extra" on top of the font's
 * own ascent/descent, so the extra is derived from the requested line height.
 */
private fun applyComposeStyle(view: TextView, style: TextStyle, color: Color, highlight: Color) {
    view.setTextColor(color.toArgb())
    view.highlightColor = highlight.toArgb()

    val sizeSp = if (style.fontSize.isSp) style.fontSize.value else 16f
    view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    view.letterSpacing =
        if (style.letterSpacing.isSp && sizeSp > 0f) style.letterSpacing.value / sizeSp else 0f

    val bold = (style.fontWeight?.weight ?: FontWeight.Normal.weight) >= FontWeight.Bold.weight
    val italic = style.fontStyle == FontStyle.Italic
    view.typeface = when {
        bold && italic -> Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD_ITALIC)
        bold -> Typeface.DEFAULT_BOLD
        italic -> Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
        else -> Typeface.DEFAULT
    }

    val lineHeightPx = if (style.lineHeight.isSp) {
        style.lineHeight.value * view.resources.displayMetrics.scaledDensity
    } else 0f
    if (lineHeightPx > 0f) {
        val fm = view.paint.fontMetrics
        view.setLineSpacing((lineHeightPx - (fm.descent - fm.ascent)).coerceAtLeast(0f), 1f)
    } else {
        view.setLineSpacing(0f, 1f)
    }

    view.gravity = if (style.textAlign == TextAlign.Center) {
        Gravity.TOP or Gravity.CENTER_HORIZONTAL
    } else {
        Gravity.TOP or Gravity.START
    }
}

/* ------------------------------------------------------- translate / ask AI */

/** What the user asked for from the selection menu. */
internal enum class SelectionKind { TRANSLATE, EXPLAIN }

internal sealed interface SelectionDialogState {
    val kind: SelectionKind
    val title: String
    val source: String

    data class Loading(
        override val kind: SelectionKind,
        override val title: String,
        override val source: String
    ) : SelectionDialogState

    data class Result(
        override val kind: SelectionKind,
        override val title: String,
        override val source: String,
        val answer: String
    ) : SelectionDialogState

    data class Failure(
        override val kind: SelectionKind,
        override val title: String,
        override val source: String,
        val message: String
    ) : SelectionDialogState

    data class Notice(
        override val kind: SelectionKind,
        override val title: String,
        override val source: String,
        val message: String
    ) : SelectionDialogState
}

/**
 * State machine behind [SelectableText]'s two menu entries.
 *
 * Created by [rememberSelectionActions] so screens only need to pass its two methods to their
 * bubbles and call [SelectionDialogHost] once. Settings are read lazily through a provider, so the
 * text model configured *after* the screen was opened is picked up as well.
 */
class SelectionActions internal constructor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settingsProvider: () -> AppSettings
) {
    internal var dialog by mutableStateOf<SelectionDialogState?>(null)
        private set

    /** Guards against a slow answer reopening a dialog the user already dismissed. */
    private var requestSeq = 0

    fun translate(raw: String) {
        val selected = raw.trim()
        if (selected.isEmpty()) return

        // Preferred path: let a system translation app own the selection (spec §5.2). The manifest
        // declares the PROCESS_TEXT <queries> entry, so resolveActivity() can see those apps.
        val processText = Intent(Intent.ACTION_PROCESS_TEXT).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, selected)
            putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        }
        if (processText.resolveActivity(context.packageManager) != null) {
            if (runCatching { context.startActivity(processText) }.isSuccess) return
        }
        // No handler, or it refused to open: fall back to the configured text model.
        run(SelectionKind.TRANSLATE, selected)
    }

    fun askAi(raw: String) {
        val selected = raw.trim()
        if (selected.isEmpty()) return
        run(SelectionKind.EXPLAIN, selected)
    }

    /** Re-run the model call after a network error. */
    fun retry() {
        val failure = dialog as? SelectionDialogState.Failure ?: return
        run(failure.kind, failure.source)
    }

    fun dismiss() {
        requestSeq++
        dialog = null
    }

    private fun run(kind: SelectionKind, selected: String) {
        val settings = settingsProvider()
        val title = context.getString(
            if (kind == SelectionKind.TRANSLATE) R.string.ask_translate else R.string.ask_ai
        )
        val modelReady = settings.apiKey.isNotBlank() &&
            settings.textEndpoint.isNotBlank() &&
            settings.textModel.isNotBlank()
        if (!modelReady) {
            dialog = SelectionDialogState.Notice(
                kind = kind,
                title = title,
                source = selected,
                message = context.getString(R.string.ask_no_model)
            )
            return
        }

        val seq = ++requestSeq
        dialog = SelectionDialogState.Loading(kind = kind, title = title, source = selected)
        scope.launch {
            val system = when (kind) {
                SelectionKind.TRANSLATE -> Prompts.translateSystem(uiLanguageName(context))
                SelectionKind.EXPLAIN -> context.getString(R.string.ask_system_explain)
            }
            val outcome = runCatching {
                val answer = TextApi(settings.textEndpoint, settings.apiKey).complete(
                    model = settings.textModel,
                    messages = listOf("system" to system, "user" to selected),
                    temperature = 0.2,
                    maxTokens = 1200
                ).trim()
                answer.ifBlank { throw IllegalStateException("Empty response from model") }
            }
            // The user closed the dialog while we were waiting: drop this answer.
            if (seq != requestSeq) return@launch
            outcome
                .onSuccess { answer ->
                    dialog = SelectionDialogState.Result(
                        kind = kind,
                        title = title,
                        source = selected,
                        answer = answer
                    )
                }
                .onFailure { error ->
                    dialog = SelectionDialogState.Failure(
                        kind = kind,
                        title = title,
                        source = selected,
                        message = context.getString(
                            R.string.ask_failed,
                            error.message?.trim()?.take(200)?.ifBlank { error.javaClass.simpleName }
                                ?: error.javaClass.simpleName
                        )
                    )
                }
        }
    }
}

/** Remember per-screen selection actions. Call [SelectionDialogHost] once in the same screen. */
@Composable
fun rememberSelectionActions(): SelectionActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember(context) { SettingsRepository(context) }
    val settings by repo.settings.collectAsState(initial = AppSettings())
    val latestSettings = rememberUpdatedState(settings)
    return remember(context, scope) {
        SelectionActions(context, scope) { latestSettings.value }
    }
}

/** The modal that shows the translation / explanation, with copy and retry. */
@Composable
fun SelectionDialogHost(actions: SelectionActions) {
    val context = LocalContext.current
    val state = actions.dialog ?: return

    Dialog(onDismissRequest = actions::dismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                Modifier
                    .widthIn(max = 460.dp)
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    state.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() }
                )
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                ) {
                    Text(
                        state.source,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                when (state) {
                    is SelectionDialogState.Loading -> Row(
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            stringResource(R.string.ask_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    is SelectionDialogState.Result -> Text(
                        state.answer,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    )

                    is SelectionDialogState.Failure -> Text(
                        state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    )

                    is SelectionDialogState.Notice -> Text(
                        state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    when (state) {
                        is SelectionDialogState.Result -> TextButton(onClick = {
                            copyToClipboard(context, state.answer)
                            Toast.makeText(context, R.string.ask_copied, Toast.LENGTH_SHORT).show()
                        }) {
                            Text(stringResource(R.string.ask_copy))
                        }

                        is SelectionDialogState.Failure -> TextButton(onClick = actions::retry) {
                            Text(stringResource(R.string.retry))
                        }

                        else -> Unit
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = actions::dismiss) {
                        Text(stringResource(R.string.close))
                    }
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, value: String) {
    runCatching {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("selection", value))
    }
}

/** Interface language, used as the translation target for the model fallback. */
private fun uiLanguageName(context: Context): String {
    val language = context.resources.configuration.locales[0].language
    return if (language == "zh") Languages.name("zh") else "English"
}
