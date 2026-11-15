package com.spkprac.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Hard cap for the context excerpt returned by [SessionRepo.searchContent] (spec §3.1). */
private const val SEARCH_SNIPPET_MAX = 80

/** How many characters of context are kept on each side of the hit inside the excerpt. */
private const val SEARCH_SNIPPET_CONTEXT = 24

private val SEARCH_WHITESPACE = Regex("\\s+")

class SessionRepo(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    private fun dir(): File = File(context.filesDir, "sessions").apply { mkdirs() }

    suspend fun list(): List<SessionMeta> = withContext(Dispatchers.IO) {
        dir().listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.mapNotNull { f ->
                runCatching {
                    json.decodeFromString(StoredSession.serializer(), f.readText()).meta
                }.getOrNull()
            }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()
    }

    suspend fun load(id: String): StoredSession? = withContext(Dispatchers.IO) {
        val f = File(dir(), "$id.json")
        if (!f.exists()) return@withContext null
        runCatching { json.decodeFromString(StoredSession.serializer(), f.readText()) }.getOrNull()
    }

    suspend fun save(session: StoredSession) = withContext(Dispatchers.IO) {
        File(dir(), "${session.meta.id}.json").writeText(json.encodeToString(StoredSession.serializer(), session))
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(dir(), "$id.json").delete()
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        dir().listFiles()?.forEach { runCatching { it.delete() } }
    }

    /**
     * Full-text search over the stored transcripts (v1.3.0 spec §3.1).
     *
     * Returns `sessionId -> excerpt` for every session whose **user or assistant** text contains
     * [query] (case-insensitive). The excerpt is at most 80 characters and keeps some context
     * around the hit, marked with a leading/trailing `…`; no highlight markup is included because
     * the UI decides how to render it.
     *
     * An empty query yields an empty map. Reading happens on [Dispatchers.IO] and a single corrupt
     * session file is skipped instead of failing the whole search.
     */
    suspend fun searchContent(query: String): Map<String, String> = withContext(Dispatchers.IO) {
        val needle = normalizeSearchText(query)
        if (needle.isEmpty()) return@withContext emptyMap()

        val files = dir().listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?: return@withContext emptyMap()
        val hits = LinkedHashMap<String, String>()
        for (file in files) {
            val session = runCatching {
                json.decodeFromString(StoredSession.serializer(), file.readText())
            }.getOrNull() ?: continue
            val key = session.meta.id.ifBlank { file.nameWithoutExtension }
            if (key.isEmpty() || hits.containsKey(key)) continue
            val excerpt = session.messages.asSequence()
                .filter { it.kind == "normal" && (it.role == Role.USER || it.role == Role.ASSISTANT) }
                .mapNotNull { message -> snippetAround(message.text, needle) }
                .firstOrNull()
            if (excerpt != null) hits[key] = excerpt
        }
        hits
    }

    /** Aggregate statistics used by the home dashboard. */
    suspend fun stats(): HistoryStats = withContext(Dispatchers.IO) {
        val metas = list()
        if (metas.isEmpty()) return@withContext HistoryStats()
        val days = metas.map {
            val cal = Calendar.getInstance().apply { timeInMillis = it.createdAt }
            cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
        }.toSet()
        var streak = 0
        val cal = Calendar.getInstance()
        while (true) {
            val key = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
            if (days.contains(key)) {
                streak++
                cal.add(Calendar.DAY_OF_YEAR, -1)
            } else break
        }
        HistoryStats(
            sessions = metas.size,
            totalMs = metas.sumOf { it.durationMs },
            streakDays = streak,
            practiceSessions = metas.count { it.mode == Mode.PRACTICE }
        )
    }

    /** Everything, as one human-readable text file. */
    suspend fun exportAll(): String = withContext(Dispatchers.IO) {
        val parts = list().mapNotNull { meta ->
            runCatching { load(meta.id)?.let { exportText(it) } }.getOrNull()
        }
        buildString {
            appendLine("SpkPrac session export")
            appendLine(formatDate(System.currentTimeMillis()))
            parts.forEach { part ->
                appendLine()
                appendLine("──────────────────────────────")
                appendLine()
                appendLine(part)
            }
        }
    }

    companion object {
        /** Collapses runs of whitespace so a multi-line transcript can be matched and shown as one line. */
        internal fun normalizeSearchText(text: String): String =
            text.replace(SEARCH_WHITESPACE, " ").trim()

        /**
         * One-line excerpt around the first case-insensitive occurrence of [query], or null when the
         * text does not contain it. Worst case length is [SEARCH_SNIPPET_MAX] characters, ellipses
         * included. Never returns a highlight marker: the caller renders the hit as it likes.
         */
        internal fun snippetAround(text: String, query: String): String? {
            val flat = normalizeSearchText(text)
            if (flat.isEmpty() || query.isEmpty()) return null
            val hit = flat.indexOf(query, ignoreCase = true)
            if (hit < 0) return null

            // Reserve two characters for the optional leading/trailing ellipsis.
            val budget = SEARCH_SNIPPET_MAX - query.length - 2
            if (budget <= 0) {
                // A query longer than the excerpt size: keep the hit itself, truncated.
                return flat.substring(hit, (hit + SEARCH_SNIPPET_MAX).coerceAtMost(flat.length))
            }
            var lead = (budget / 2).coerceAtMost(hit)
            val tail = (budget - lead)
                .coerceAtMost((flat.length - hit - query.length).coerceAtLeast(0))
            // The hit may sit close to an edge; give the unused context budget to the other side.
            lead = (budget - tail).coerceAtMost(hit)
            val start = hit - lead
            val end = hit + query.length + tail
            val prefix = if (start > 0) "…" else ""
            val suffix = if (end < flat.length) "…" else ""
            return prefix + flat.substring(start, end).trim() + suffix
        }

        fun formatDuration(ms: Long): String {
            val totalSec = (ms / 1000).coerceAtLeast(0)
            val h = totalSec / 3600
            val m = (totalSec % 3600) / 60
            val s = totalSec % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }

        /** Compact duration for stat chips, e.g. 2h 15m / 43m / 12s. */
        fun formatDurationShort(ms: Long): String {
            val totalSec = (ms / 1000).coerceAtLeast(0)
            val h = totalSec / 3600
            val m = (totalSec % 3600) / 60
            return when {
                h > 0 -> "${h}h ${m}m"
                m > 0 -> "${m}m"
                else -> "${totalSec}s"
            }
        }

        fun formatDate(ts: Long): String =
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts))

        fun exportText(s: StoredSession): String = buildString {
            appendLine(s.meta.title)
            appendLine("${formatDate(s.meta.createdAt)}  ·  ${formatDuration(s.meta.durationMs)}  ·  ${s.meta.messageCount} msgs")
            appendLine()
            s.messages.forEach { m ->
                if (m.kind == "system") {
                    appendLine("— ${m.text}")
                } else {
                    appendLine("[${if (m.role == Role.USER) "Me" else "AI"}] ${m.text}")
                    m.translation?.let { appendLine("    ↳ $it") }
                }
            }
            s.report?.let {
                appendLine()
                appendLine("===== Report =====")
                appendLine(it.content)
            }
        }

        /** Markdown export used by the share/export actions on the detail screen. */
        fun exportMarkdown(s: StoredSession): String = buildString {
            appendLine("# ${s.meta.title}")
            appendLine()
            appendLine("- Date: ${formatDate(s.meta.createdAt)}")
            appendLine("- Duration: ${formatDuration(s.meta.durationMs)}")
            appendLine("- Messages: ${s.meta.messageCount}")
            if (s.meta.language.isNotBlank()) appendLine("- Language: ${Languages.name(s.meta.language)}")
            appendLine()
            appendLine("---")
            appendLine()
            s.messages.forEach { m ->
                if (m.kind == "system") {
                    appendLine("> ${m.text}")
                } else {
                    val who = if (m.role == Role.USER) "Me" else "AI"
                    appendLine("**$who:** ${m.text}")
                    m.translation?.let { appendLine(); appendLine("*↳ $it*") }
                    appendLine()
                }
            }
            s.report?.let {
                appendLine()
                appendLine("---")
                appendLine()
                appendLine("# Practice report")
                appendLine()
                appendLine(it.content)
            }
        }
    }
}
