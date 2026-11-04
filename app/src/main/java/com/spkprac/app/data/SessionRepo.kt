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
