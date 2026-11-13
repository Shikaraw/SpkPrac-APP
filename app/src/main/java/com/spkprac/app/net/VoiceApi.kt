package com.spkprac.app.net

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/* --------------------------------------------------------------- contract */

/**
 * Failure reported by the voice-enrollment API (spec §2.5).
 *
 * [message] is the server message verbatim when the server sent one, so the UI can show it
 * unchanged; [serverCode] is kept (never dropped) for diagnostics.
 */
class VoiceApiException(
    message: String,
    val serverCode: String? = null
) : Exception(message)

/** One entry of `list_voice` output. Deliberately has no target_model — see spec §4. */
data class VoiceListItem(
    val voiceId: String,
    val status: String,
    val gmtCreate: String? = null,
    val gmtModified: String? = null
)

/** Output of `query_voice` (spec §2.3). */
data class VoiceQuery(
    val voiceId: String,
    val status: String,
    val targetModel: String,
    val gmtCreate: String? = null,
    val gmtModified: String? = null,
    val resourceLink: String? = null
)

/* ----------------------------------------------------------------- client */

/**
 * Minimal client for the Bailian voice-enrollment HTTP API.
 *
 * Uses the same bare-OkHttp approach as [TextApi] on purpose: one shared client (connection pool)
 * and no extra dependencies. Every call is a single `POST {cloneEndpoint}` with
 * `{"model":"...","input":{"action":"..."}}` (spec §2.2).
 */
class VoiceApi(
    private val endpoint: String,
    private val apiKey: String,
    private val realtimeModel: String = ""
) {

    companion object {
        /**
         * Enrollment model depends on the target realtime model family (spec §2.2); the two
         * names are NOT interchangeable. Omni routes use [MODEL_OMNI], Qwen-Audio routes use
         * [MODEL_GENERIC].
         */
        const val MODEL_OMNI = "qwen-voice-enrollment"
        const val MODEL_GENERIC = "voice-enrollment"

        /** `enrollModelFor(rtModel)`: omni → qwen-voice-enrollment, otherwise voice-enrollment. */
        fun enrollmentModelFor(realtimeModel: String): String =
            if (realtimeModel.contains("omni", ignoreCase = true)) MODEL_OMNI else MODEL_GENERIC

        const val ACTION_CREATE = "create_voice"
        const val ACTION_LIST = "list_voice"
        const val ACTION_QUERY = "query_voice"
        const val ACTION_UPDATE = "update_voice"
        const val ACTION_DELETE = "delete_voice"

        const val STATUS_OK = "OK"
        const val STATUS_UNDEPLOYED = "UNDEPLOYED"
        const val STATUS_DEPLOYING = "DEPLOYING"

        /** Polling contract from spec §2.4: once a second, at most 30 attempts. */
        const val POLL_ATTEMPTS = 30
        const val POLL_INTERVAL_MS = 1_000L

        private val JSON_MEDIA = "application/json".toMediaType()

        private val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                // Uploads carry up to ~13 MB of Base64 JSON; allow slow mobile uplinks.
                .writeTimeout(120, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build()
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** Resolved once: all five actions must use the same enrollment model (spec §2.2). */
    private val enrollmentModel: String = enrollmentModelFor(realtimeModel)

    /* ------------------------------------------------------------- actions */

    /** `create_voice` — returns the server voice id (spec §2.3). */
    suspend fun createVoice(
        targetModel: String,
        prefix: String,
        audioDataUri: String,
        languageHints: List<String>? = null,
        enablePreprocess: Boolean? = null
    ): String {
        val input = buildJsonObject {
            put("action", ACTION_CREATE)
            put("target_model", targetModel)
            put("prefix", prefix)
            put("audio", audioDataUri)
            if (!languageHints.isNullOrEmpty()) {
                put("language_hints", buildJsonArray { languageHints.forEach { add(it) } })
            }
            if (enablePreprocess != null) put("enable_preprocess", enablePreprocess)
        }
        val output = call(input).outputOrThrow(ACTION_CREATE)
        return output.stringOrNull("voice_id")?.takeIf { it.isNotBlank() }
            ?: throw VoiceApiException("The server response did not include a voice_id")
    }

    /** `list_voice` — remote voice list, one page (spec §2.3). */
    suspend fun listVoices(
        prefix: String? = null,
        pageIndex: Int = 0,
        pageSize: Int = 100
    ): List<VoiceListItem> {
        val input = buildJsonObject {
            put("action", ACTION_LIST)
            if (!prefix.isNullOrBlank()) put("prefix", prefix)
            put("page_index", pageIndex)
            put("page_size", pageSize)
        }
        val output = call(input).outputOrThrow(ACTION_LIST)
        val array = output["voice_list"] as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            runCatching {
                val item = element.jsonObject
                VoiceListItem(
                    voiceId = item.stringOrNull("voice_id").orEmpty(),
                    status = item.stringOrNull("status").orEmpty(),
                    gmtCreate = item.stringOrNull("gmt_create"),
                    gmtModified = item.stringOrNull("gmt_modified")
                )
            }.getOrNull()
        }.filter { it.voiceId.isNotBlank() }
    }

    /** `query_voice` — status / bound model / timestamps for one voice (spec §2.3). */
    suspend fun queryVoice(voiceId: String): VoiceQuery {
        val input = buildJsonObject {
            put("action", ACTION_QUERY)
            put("voice_id", voiceId)
        }
        val output = call(input).outputOrThrow(ACTION_QUERY)
        return VoiceQuery(
            voiceId = voiceId,
            status = output.stringOrNull("status").orEmpty(),
            targetModel = output.stringOrNull("target_model").orEmpty(),
            gmtCreate = output.stringOrNull("gmt_create"),
            gmtModified = output.stringOrNull("gmt_modified"),
            resourceLink = output.stringOrNull("resource_link")
        )
    }

    /** `update_voice` — replaces the sample audio of an existing voice (spec §2.3). */
    suspend fun updateVoice(voiceId: String, audioDataUri: String) {
        val input = buildJsonObject {
            put("action", ACTION_UPDATE)
            put("voice_id", voiceId)
            put("audio", audioDataUri)
        }
        call(input)
    }

    /** `delete_voice` — removes the voice server-side; irreversible (spec §5.5). */
    suspend fun deleteVoice(voiceId: String) {
        val input = buildJsonObject {
            put("action", ACTION_DELETE)
            put("voice_id", voiceId)
        }
        call(input)
    }

    /**
     * Polls [queryVoice] until the voice settles (spec §2.4): once a second, at most 30 tries.
     * A timeout is reported as [VoicePoll.Pending] — "still under review", not a failure.
     */
    suspend fun pollStatus(
        voiceId: String,
        onUpdate: (VoiceQuery) -> Unit = {}
    ): VoicePoll {
        var last: VoiceQuery? = null
        repeat(POLL_ATTEMPTS) { attempt ->
            val query = queryVoice(voiceId)
            last = query
            onUpdate(query)
            when (query.status.uppercase(Locale.US)) {
                STATUS_OK -> return VoicePoll.Ok(query)
                STATUS_UNDEPLOYED -> return VoicePoll.Undeployed(query)
            }
            if (attempt < POLL_ATTEMPTS - 1) delay(POLL_INTERVAL_MS)
        }
        return VoicePoll.Pending(last)
    }

    /** Outcome of [pollStatus]. */
    sealed interface VoicePoll {
        data class Ok(val query: VoiceQuery) : VoicePoll
        data class Undeployed(val query: VoiceQuery) : VoicePoll
        data class Pending(val last: VoiceQuery?) : VoicePoll
    }

    /* ------------------------------------------------------------- helpers */

    private suspend fun call(input: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val url = endpoint.trim()
        if (url.isEmpty()) throw VoiceApiException("Voice enrollment endpoint is not configured")
        if (apiKey.isBlank()) throw VoiceApiException("API key is missing")
        val payload = buildJsonObject {
            put("model", enrollmentModel)
            put("input", input)
        }
        val request = try {
            Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()
        } catch (e: IllegalArgumentException) {
            throw VoiceApiException(e.message ?: "Invalid voice enrollment endpoint")
        }

        var code = 0
        var text = ""
        try {
            sharedClient.newCall(request).execute().use { response ->
                code = response.code
                text = response.body?.string().orEmpty()
            }
        } catch (e: Exception) {
            // Cancellation must propagate untouched; everything else becomes a readable message.
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw VoiceApiException(e.message?.takeIf { it.isNotBlank() } ?: "Network error")
        }

        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
        val serverCode = root?.stringOrNull("code")
        val serverMessage = root?.stringOrNull("message")
        if (code !in 200..299 || serverCode != null) {
            val message = serverMessage?.takeIf { it.isNotBlank() }
                ?: buildString {
                    append("HTTP ").append(code)
                    if (serverCode != null) append(" (").append(serverCode).append(')')
                    if (text.isNotBlank()) append(": ").append(text.take(300))
                }
            throw VoiceApiException(message, serverCode)
        }
        root ?: throw VoiceApiException("Invalid response from server: ${text.take(300)}")
    }

    private fun JsonObject.outputOrThrow(action: String): JsonObject =
        this["output"] as? JsonObject
            ?: throw VoiceApiException("The server response for $action did not include an output")

    private fun JsonObject.stringOrNull(key: String): String? =
        this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
}

/* ------------------------------------------------------------ audio input */

/** Everything the UI needs to know about a picked local audio clip (spec §3). */
data class VoiceAudioInfo(
    val uri: Uri,
    val name: String,
    val extension: String,
    val mime: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val sampleRate: Int,
    val channels: Int
)

/** Which pre-upload check failed. The UI maps each to a specific message (spec §3). */
enum class VoiceAudioError {
    FORMAT, SIZE, TOO_SHORT, TOO_LONG, SAMPLE_RATE, UNREADABLE
}

/** A validated (or rejected) audio selection; [error] is null when the clip is uploadable. */
data class VoiceAudioCheck(
    val info: VoiceAudioInfo,
    val error: VoiceAudioError? = null,
    /** Valid clip, but outside the recommended 10–20 s window — warn only, never block. */
    val durationWarning: Boolean = false,
    /** Valid clip but not mono: the service uses the first channel — warn only (spec §3). */
    val channelsWarning: Boolean = false
)

/**
 * Local audio validation + Base64 Data URI creation (spec §3, §2.3).
 *
 * All failures are represented as data, never thrown: the UI decides how to show them.
 * The 10 MB → ~13.3 MB Base64 string is built once right before upload and not cached elsewhere,
 * so at most one copy is retained (spec §7).
 */
object VoiceAudio {

    const val MAX_BYTES = 10L * 1024L * 1024L
    const val MIN_DURATION_MS = 5_000L
    const val MAX_DURATION_MS = 60_000L
    const val MIN_SAMPLE_RATE = 24_000

    private val RECOMMENDED_DURATION_MS = 10_000L..20_000L

    /** Containers accepted by the service (spec §3). */
    val SUPPORTED_EXTENSIONS = listOf("wav", "mp3", "m4a", "aac")

    /** MIME filter for the system file picker (SAF); extension validation happens after. */
    val PICKER_MIME_TYPES = arrayOf(
        "audio/wav",
        "audio/x-wav",
        "audio/mpeg",
        "audio/mp4",
        "audio/x-m4a",
        "audio/m4a",
        "audio/aac",
        "audio/x-aac"
    )

    /**
     * `prefix` for `create_voice`: `[A-Za-z0-9]` only, lower-cased, at most 10 chars; "voice"
     * when nothing is left (spec §5.3).
     */
    fun prefixFor(name: String): String {
        val cleaned = name.filter { ch ->
            ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9'
        }.lowercase(Locale.US)
        return cleaned.take(10).ifBlank { "voice" }
    }

    fun mimeFor(extension: String): String = when (extension.lowercase(Locale.US)) {
        "wav" -> "audio/wav"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        else -> "audio/*"
    }

    /** Inspects a picked document: name/format/size/duration/sample rate/channels. */
    suspend fun inspect(context: Context, uri: Uri): VoiceAudioCheck = withContext(Dispatchers.IO) {
        val name = displayName(context, uri)
        val rawExtension = name.substringAfterLast('.', "").lowercase(Locale.US)
        val extension = rawExtension.ifBlank { extensionForMime(context, uri) }
        val declaredSize = querySize(context, uri)
        val size = if (declaredSize != null && declaredSize > 0) {
            declaredSize
        } else {
            countBytes(context, uri)
        }
        val duration = readDuration(context, uri)
        val format = readFormat(context, uri)

        val info = VoiceAudioInfo(
            uri = uri,
            name = name,
            extension = extension,
            mime = mimeFor(extension),
            sizeBytes = size,
            durationMs = duration,
            sampleRate = format.first,
            channels = format.second
        )
        val error = when {
            extension !in SUPPORTED_EXTENSIONS -> VoiceAudioError.FORMAT
            size <= 0L -> VoiceAudioError.UNREADABLE
            size > MAX_BYTES -> VoiceAudioError.SIZE
            duration in 1 until MIN_DURATION_MS -> VoiceAudioError.TOO_SHORT
            duration > MAX_DURATION_MS -> VoiceAudioError.TOO_LONG
            format.first in 1 until MIN_SAMPLE_RATE -> VoiceAudioError.SAMPLE_RATE
            else -> null
        }
        VoiceAudioCheck(
            info = info,
            error = error,
            durationWarning = error == null && duration > 0L &&
                duration !in RECOMMENDED_DURATION_MS,
            // Stereo is accepted (first channel is used) — spec §3 wants a warning, not a block.
            channelsWarning = error == null && format.second > 1
        )
    }

    /** Reads the clip and returns `data:audio/xxx;base64,<...>` (spec §2.3). */
    suspend fun toDataUri(context: Context, info: VoiceAudioInfo): String =
        withContext(Dispatchers.IO) {
            val bytes = context.contentResolver.openInputStream(info.uri)?.use { input ->
                readBytesAtMost(input, MAX_BYTES)
            } ?: throw VoiceApiException("Cannot open the selected audio file")
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            "data:${info.mime};base64,$base64"
        }

    /* ------------------------------------------------------------ internals */

    private fun displayName(context: Context, uri: Uri): String {
        val provided = runCatching {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
                }
        }.getOrNull()
        val fallback = uri.lastPathSegment?.substringAfterLast('/')
        return provided?.takeIf { it.isNotBlank() }
            ?: fallback?.takeIf { it.isNotBlank() }
            ?: "audio"
    }

    private fun extensionForMime(context: Context, uri: Uri): String {
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
            ?.lowercase(Locale.US).orEmpty()
        return when {
            mime.contains("wav") -> "wav"
            mime.contains("mpeg") || mime.contains("mp3") -> "mp3"
            mime.contains("aac") -> "aac"
            mime.contains("mp4") || mime.contains("m4a") -> "m4a"
            else -> ""
        }
    }

    private fun querySize(context: Context, uri: Uri): Long? = runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
            }
    }.getOrNull()

    private fun countBytes(context: Context, uri: Uri): Long = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (total <= MAX_BYTES) {
                val read = input.read(buffer)
                if (read <= 0) return@use total
                total += read
            }
            total
        } ?: 0L
    }.getOrDefault(0L)

    private fun readDuration(context: Context, uri: Uri): Long = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } finally {
            runCatching { retriever.release() }
        }
    }.getOrDefault(0L)

    /** Sample rate + channel count; both 0 when the platform cannot report them (skip check). */
    private fun readFormat(context: Context, uri: Uri): Pair<Int, Int> = runCatching {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            var audioTrack = -1
            for (index in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrack = index
                    break
                }
            }
            if (audioTrack < 0) {
                0 to 0
            } else {
                val format = extractor.getTrackFormat(audioTrack)
                val rate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                } else 0
                val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else 0
                rate to channels
            }
        } finally {
            runCatching { extractor.release() }
        }
    }.getOrDefault(0 to 0)

    private fun readBytesAtMost(input: InputStream, limit: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            total += read
            if (total > limit) {
                throw VoiceApiException("Audio file exceeds ${limit / (1024 * 1024)} MB")
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}

/* ------------------------------------------------------------------ times */

/**
 * Parses the server's `gmt_create` / `gmt_modified` values into epoch millis.
 * Accepts epoch seconds/millis as numbers, ISO-8601 with a `Z` suffix and the common
 * `yyyy-MM-dd HH:mm:ss` form; returns null when the format is unknown instead of throwing.
 */
fun parseVoiceTime(raw: String?): Long? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return null
    text.toLongOrNull()?.let { value ->
        return if (value in 1L until 100_000_000_000L) value * 1000L else value
    }
    val utcPatterns = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss'Z'"
    )
    for (pattern in utcPatterns) {
        parseWith(pattern, text, TimeZone.getTimeZone("UTC"))?.let { return it }
    }
    val patterns = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm:ss"
    )
    for (pattern in patterns) {
        parseWith(pattern, text, null)?.let { return it }
    }
    return null
}

private fun parseWith(pattern: String, text: String, zone: TimeZone?): Long? = runCatching {
    val format = SimpleDateFormat(pattern, Locale.US)
    if (zone != null) format.timeZone = zone
    format.isLenient = false
    format.parse(text)?.time
}.getOrNull()
