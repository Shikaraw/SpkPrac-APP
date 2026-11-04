package com.spkprac.app.data

import kotlinx.serialization.Serializable

enum class Mode { CHAT, PRACTICE }

enum class Role { USER, ASSISTANT, SYSTEM }

@Serializable
data class ChatMessage(
    val id: String,
    val role: Role,
    val text: String,
    val ts: Long,
    val translation: String? = null,
    val kind: String = "normal" // normal | system
)

@Serializable
data class SessionMeta(
    val id: String,
    val mode: Mode,
    val title: String,
    val language: String = "",
    val createdAt: Long,
    val durationMs: Long = 0,
    val messageCount: Int = 0
)

@Serializable
data class PracticeReport(
    val content: String,
    val generatedAt: Long
)

@Serializable
data class StoredSession(
    val meta: SessionMeta,
    val messages: List<ChatMessage> = emptyList(),
    val report: PracticeReport? = null
)

/** Aggregate numbers shown on the home screen. */
data class HistoryStats(
    val sessions: Int = 0,
    val totalMs: Long = 0L,
    val streakDays: Int = 0,
    val practiceSessions: Int = 0
)

object Languages {
    val list = listOf(
        "zh" to "中文（简体）",
        "en" to "English",
        "ja" to "日本語",
        "ko" to "한국어",
        "fr" to "Français",
        "de" to "Deutsch",
        "es" to "Español",
        "ru" to "Русский",
        "pt" to "Português",
        "it" to "Italiano",
        "th" to "ไทย",
        "vi" to "Tiếng Việt",
        "id" to "Bahasa Indonesia",
        "ar" to "العربية",
        "hi" to "हिन्दी"
    )

    fun name(code: String): String = list.firstOrNull { it.first == code }?.second ?: code

    /** Options for "reply in which language" — auto detection plus every supported language. */
    val speakOptions: List<Pair<String, String>> get() = list

    /** ASR language codes supported by the realtime input_audio_transcription.language field. */
    val asrCodes = listOf(
        "zh", "en", "ja", "ko", "vi", "th", "id", "ms", "tl", "hi", "ar",
        "fr", "de", "es", "pt", "ru", "it", "nl", "sv", "da", "fi", "no",
        "el", "pl", "cs", "hu", "ro", "bg", "hr", "sk"
    )
}
