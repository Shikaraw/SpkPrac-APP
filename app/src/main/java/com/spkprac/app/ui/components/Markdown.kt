package com.spkprac.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Tiny Markdown renderer for model output (practice reports).
 *
 * Deliberately hand-rolled instead of pulling in a Markdown library: the app ships a ~1.5 MB
 * APK and a full parser would add hundreds of KB for the handful of constructs we render.
 * Supports headings, bullets, numbered lists, quotes, bold, italics and inline code.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    baseStyle: TextStyle = MaterialTheme.typography.bodyMedium
) {
    val blocks = remember(markdown) { parseBlocks(markdown) }
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(
                    inline(block.text, accent),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    fontWeight = FontWeight.Bold,
                    color = accent
                )

                is MdBlock.Bullet -> Row(Modifier.padding(start = 2.dp)) {
                    Text("•", style = baseStyle, color = accent, modifier = Modifier.width(14.dp))
                    Text(inline(block.text, accent), style = baseStyle)
                }

                is MdBlock.Numbered -> Row(Modifier.padding(start = 2.dp)) {
                    Text(
                        "${block.index}.",
                        style = baseStyle,
                        color = accent,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.width(20.dp)
                    )
                    Text(inline(block.text, accent), style = baseStyle)
                }

                is MdBlock.Quote -> Row {
                    Box(
                        Modifier
                            .width(3.dp)
                            .padding(vertical = 2.dp)
                            .background(accent.copy(alpha = 0.5f), RoundedCornerShape(2.dp))
                    )
                    Box(Modifier.width(10.dp))
                    Text(
                        inline(block.text, accent),
                        style = baseStyle.copy(fontSize = baseStyle.fontSize * 0.95f),
                        color = muted
                    )
                }

                is MdBlock.Code -> Box(
                    Modifier
                        .background(codeBg, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Text(
                        block.text,
                        style = baseStyle.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    )
                }

                is MdBlock.Paragraph -> Text(inline(block.text, accent), style = baseStyle)

                MdBlock.Rule -> androidx.compose.material3.HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }
        }
    }
}

/* --------------------------------------------------------------- parsing */

private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Bullet(val text: String) : MdBlock
    data class Numbered(val index: Int, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Code(val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data object Rule : MdBlock
}

private val numberedRegex = Regex("^(\\d+)[.)]\\s+(.*)$")

private fun parseBlocks(md: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val lines = md.replace("\r\n", "\n").split('\n')
    var inCode = false
    val codeBuf = StringBuilder()

    for (raw in lines) {
        val line = raw.trimEnd()
        val trimmed = line.trim()

        if (trimmed.startsWith("```")) {
            if (inCode) {
                out += MdBlock.Code(codeBuf.toString().trimEnd())
                codeBuf.clear()
            }
            inCode = !inCode
            continue
        }
        if (inCode) {
            codeBuf.appendLine(line)
            continue
        }

        when {
            trimmed.isEmpty() -> Unit
            trimmed.startsWith("# ") -> out += MdBlock.Heading(1, trimmed.removePrefix("# ").trim())
            trimmed.startsWith("## ") -> out += MdBlock.Heading(2, trimmed.removePrefix("## ").trim())
            trimmed.startsWith("### ") -> out += MdBlock.Heading(3, trimmed.removePrefix("### ").trim())
            trimmed == "---" || trimmed == "***" -> out += MdBlock.Rule
            trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("• ") ->
                out += MdBlock.Bullet(trimmed.drop(2).trim())

            trimmed.startsWith("> ") -> out += MdBlock.Quote(trimmed.removePrefix("> ").trim())
            else -> {
                val m = numberedRegex.matchEntire(trimmed)
                if (m != null) {
                    out += MdBlock.Numbered(m.groupValues[1].toIntOrNull() ?: 1, m.groupValues[2].trim())
                } else {
                    // merge consecutive plain lines into one paragraph
                    val last = out.lastOrNull()
                    if (last is MdBlock.Paragraph && !last.text.endsWith("  ")) {
                        out[out.size - 1] = MdBlock.Paragraph(last.text + " " + trimmed)
                    } else {
                        out += MdBlock.Paragraph(trimmed)
                    }
                }
            }
        }
    }
    if (inCode && codeBuf.isNotEmpty()) out += MdBlock.Code(codeBuf.toString().trimEnd())
    return out
}

private val boldItalicRegex = Regex("(\\*\\*\\*|\\*\\*|__)(.+?)\\1")
private val codeRegex = Regex("`([^`]+)`")
private val italicRegex = Regex("(?<![*_])([*_])(?![*_])(.+?)(?<![*_])\\1(?![*_])")

/** Turn the inline markup of one line into a styled [AnnotatedString]. */
private fun inline(text: String, accent: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    // inline code
    codeRegex.findAll(text).forEach { m ->
        addStyle(
            SpanStyle(fontFamily = FontFamily.Monospace, background = accent.copy(alpha = 0.10f)),
            m.range.first,
            m.range.last + 1
        )
    }
    // bold / bold-italic
    boldItalicRegex.findAll(text).forEach { m ->
        addStyle(
            SpanStyle(fontWeight = FontWeight.Bold),
            m.range.first,
            m.range.last + 1
        )
    }
    italicRegex.findAll(text).forEach { m ->
        addStyle(
            SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
            m.range.first,
            m.range.last + 1
        )
    }
    // strikethrough
    Regex("~~(.+?)~~").findAll(text).forEach { m ->
        addStyle(
            SpanStyle(textDecoration = TextDecoration.LineThrough),
            m.range.first,
            m.range.last + 1
        )
    }
}
