package com.spkprac.app.rt

import com.spkprac.app.data.ProtocolFlavor
import com.spkprac.app.data.VadMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.TimeUnit

data class SessionConfig(
    val instructions: String,
    val voice: String,
    val vadMode: VadMode,
    val vadThreshold: Float,
    val silenceMs: Int,
    val asrLang: String,
    val flavor: ProtocolFlavor
)

/** Server events we care about, parsed leniently from the realtime protocol. */
sealed class RtEvent {
    data class SessionCreated(val model: String?) : RtEvent()
    data object SessionUpdated : RtEvent()
    data class SpeechStarted(val itemId: String?) : RtEvent()
    data class SpeechStopped(val audioEndMs: Long?) : RtEvent()
    data class InputCommitted(val itemId: String?) : RtEvent()
    data class UserTranscriptDelta(val itemId: String?, val text: String, val stash: String?) : RtEvent()
    data class UserTranscriptDone(val itemId: String?, val transcript: String) : RtEvent()

    /** A new assistant response started. [responseId] lets the view model drop stale audio/transcripts. */
    data class ResponseCreated(val responseId: String?) : RtEvent()
    data class AgentTranscriptDelta(val responseId: String?, val delta: String) : RtEvent()
    data class AgentTranscriptDone(val responseId: String?, val transcript: String) : RtEvent()
    data class AudioDelta(val pcm: ByteArray, val responseId: String?) : RtEvent()
    data object AudioDone : RtEvent()
    data class ResponseDone(val status: String?, val responseId: String?) : RtEvent()
    data class RtError(val type: String?, val code: String?, val message: String?) : RtEvent()
}

/**
 * Thin realtime-protocol client: one websocket per instance, parsing server events into [RtEvent] and
 * sending the few client events the app needs.
 *
 * The HTTP client is shared by every instance (connection pool + dispatcher), so reconnecting no
 * longer leaks an OkHttp dispatcher per attempt. [close] only closes the socket.
 */
class RealtimeClient(
    private val endpoint: String,
    private val apiKey: String,
    private val model: String,
    private val listener: Listener
) {

    interface Listener {
        fun onOpen()
        fun onEvent(event: RtEvent)
        fun onClose(code: Int, reason: String?)
        fun onFailure(throwable: Throwable, message: String?)
    }

    companion object {
        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .pingInterval(20, TimeUnit.SECONDS)
                .connectTimeout(15, TimeUnit.SECONDS)
                // Long-lived websocket: liveness is detected by the ping interval, not a read timeout.
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var ws: WebSocket? = null

    @Volatile
    var closedByUser = false
        private set

    fun connect() {
        if (ws != null || closedByUser) return
        val url = if (endpoint.contains("model=")) {
            endpoint
        } else {
            endpoint + (if (endpoint.contains('?')) "&" else "?") +
                "model=" + URLEncoder.encode(model, "UTF-8")
        }

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("User-Agent", "SpkPrac/1.0.0")
            .build()

        ws = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen()

            override fun onMessage(webSocket: WebSocket, text: String) {
                parse(text)?.let { listener.onEvent(it) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                listener.onClose(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
                listener.onClose(code, reason)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val msg = buildString {
                    append(t.message ?: t.javaClass.simpleName)
                    response?.let { append(" (HTTP ${it.code})") }
                }
                listener.onFailure(t, msg)
            }
        })
    }

    // ------------------------------------------------------------------ parsing

    private fun parse(text: String): RtEvent? = try {
        val root = json.parseToJsonElement(text).jsonObject
        val type = root.str("type") ?: return RtEvent.RtError(null, null, "event without type")
        when (type) {
            "session.created" -> {
                val session = root["session"]?.jsonObject
                RtEvent.SessionCreated(session?.str("model"))
            }

            "session.updated" -> RtEvent.SessionUpdated
            "input_audio_buffer.speech_started" -> RtEvent.SpeechStarted(root.str("item_id"))
            "input_audio_buffer.speech_stopped" ->
                RtEvent.SpeechStopped(root["audio_end_ms"]?.jsonPrimitive?.content?.toLongOrNull())

            "input_audio_buffer.committed" -> RtEvent.InputCommitted(root.str("item_id"))
            "conversation.item.input_audio_transcription.delta" ->
                RtEvent.UserTranscriptDelta(root.str("item_id"), root.str("text").orEmpty(), root.str("stash"))

            "conversation.item.input_audio_transcription.completed" ->
                RtEvent.UserTranscriptDone(root.str("item_id"), root.str("transcript").orEmpty())

            "response.created" -> RtEvent.ResponseCreated(root.responseId())
            "response.audio_transcript.delta", "response.output_audio_transcript.delta" ->
                RtEvent.AgentTranscriptDelta(root.responseId(), root.str("delta").orEmpty())

            "response.audio_transcript.done", "response.output_audio_transcript.done" ->
                RtEvent.AgentTranscriptDone(root.responseId(), root.str("transcript").orEmpty())

            "response.audio.delta", "response.output_audio.delta" -> {
                val encoded = root.str("delta")
                if (encoded.isNullOrEmpty()) {
                    null
                } else {
                    val pcm = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
                    if (pcm == null) null else RtEvent.AudioDelta(pcm, root.responseId())
                }
            }

            "response.audio.done", "response.output_audio.done" -> RtEvent.AudioDone
            "response.done" -> {
                val response = root["response"]?.jsonObject
                RtEvent.ResponseDone(response?.str("status"), response?.str("id") ?: root.responseId())
            }

            "error" -> {
                val error = root["error"]?.jsonObject
                RtEvent.RtError(error?.str("type"), error?.str("code"), error?.str("message") ?: type)
            }

            else -> null
        }
    } catch (e: Exception) {
        RtEvent.RtError("parse_error", null, e.message)
    }

    private fun JsonObject.responseId(): String? =
        str("response_id") ?: this["response"]?.jsonObject?.str("id")

    private fun JsonObject.str(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)
            ?.takeIf { it !is JsonNull }
            ?.content

    // ------------------------------------------------------------------ client -> server

    fun sendSessionUpdate(cfg: SessionConfig) {
        val pcmName = if (cfg.flavor == ProtocolFlavor.OPENAI) "pcm16" else "pcm"
        val session = buildJsonObject {
            put("modalities", buildJsonArray {
                add("text")
                add("audio")
            })
            if (cfg.voice.isNotBlank()) put("voice", cfg.voice)
            put("instructions", cfg.instructions)
            put("input_audio_format", pcmName)
            put("output_audio_format", pcmName)
            put("input_audio_transcription", buildJsonObject {
                if (cfg.flavor == ProtocolFlavor.OPENAI) put("model", "whisper-1")
                if (cfg.asrLang.isNotBlank()) put("language", cfg.asrLang)
            })
            if (cfg.vadMode == VadMode.AUTO) {
                put("turn_detection", buildJsonObject {
                    put("type", "server_vad")
                    put("threshold", cfg.vadThreshold.toDouble())
                    put("silence_duration_ms", cfg.silenceMs)
                })
            } else {
                put("turn_detection", JsonNull)
            }
        }
        send(buildJsonObject {
            put("type", "session.update")
            put("session", session)
        })
    }

    /** Thread-safe (called from the audio thread). Returns false when the socket is gone. */
    fun sendAudio(base64Pcm: String): Boolean = send(buildJsonObject {
        put("type", "input_audio_buffer.append")
        put("audio", base64Pcm)
    })

    fun commitAudio(): Boolean = sendSimple("input_audio_buffer.commit")

    fun clearAudioBuffer(): Boolean = sendSimple("input_audio_buffer.clear")

    fun triggerResponse(): Boolean = sendSimple("response.create")

    fun cancelResponse(): Boolean = sendSimple("response.cancel")

    /** Sends a text user message and asks for a reply. Returns false when nothing could be queued. */
    fun sendUserText(text: String): Boolean {
        val created = send(buildJsonObject {
            put("type", "conversation.item.create")
            put("item", buildJsonObject {
                put("type", "message")
                put("role", "user")
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "input_text")
                        put("text", text)
                    })
                })
            })
        })
        return created && triggerResponse()
    }

    /** Replays a past message into the conversation (context restore after a reconnect). */
    fun replayMessage(roleUser: Boolean, text: String): Boolean = send(buildJsonObject {
        put("type", "conversation.item.create")
        put("item", buildJsonObject {
            put("type", "message")
            put("role", if (roleUser) "user" else "assistant")
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", if (roleUser) "input_text" else "text")
                    put("text", text)
                })
            })
        })
    })

    private fun sendSimple(type: String): Boolean =
        send(buildJsonObject { put("type", type) })

    private fun send(obj: JsonObject): Boolean {
        if (closedByUser) return false
        return ws?.send(obj.toString()) ?: false
    }

    /** Closes the socket. The shared HTTP client stays alive for the next connection. */
    fun close() {
        if (closedByUser) return
        closedByUser = true
        val socket = ws
        ws = null
        try {
            socket?.close(1000, "client closed")
        } catch (_: Exception) {
        }
    }
}
