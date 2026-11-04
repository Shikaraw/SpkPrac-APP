package com.spkprac.app.core

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spkprac.app.R
import com.spkprac.app.audio.AudioEngine
import com.spkprac.app.audio.AudioOutput
import com.spkprac.app.data.AppSettings
import com.spkprac.app.data.AudioRoute
import com.spkprac.app.data.ChatMessage
import com.spkprac.app.data.CorrectionPolicy
import com.spkprac.app.data.Languages
import com.spkprac.app.data.Mode
import com.spkprac.app.data.PracticeReport
import com.spkprac.app.data.Role
import com.spkprac.app.data.SessionMeta
import com.spkprac.app.data.SessionRepo
import com.spkprac.app.data.SettingsRepository
import com.spkprac.app.data.StoredSession
import com.spkprac.app.data.VadMode
import com.spkprac.app.net.TextApi
import com.spkprac.app.rt.RealtimeClient
import com.spkprac.app.rt.RtEvent
import com.spkprac.app.rt.SessionConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * Application-lifetime scope for work that must outlive the ViewModel: the final session save and the
 * report persistence. [viewModelScope] is already cancelled while onCleared() runs, which is exactly
 * why the tail of a session used to be lost.
 */
private object SessionPersistence {
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

private const val MAX_RECONNECT_ATTEMPTS = 3
private const val MAX_REPLAY_MESSAGES = 40
private const val MIC_LEVEL_INTERVAL_MS = 50L
private const val PTT_MIN_COMMIT_BYTES = 1600L
private const val MAX_TITLE_CHARS = 18
private const val MAX_ERROR_DETAIL_CHARS = 150

data class SessionStartConfig(
    val mode: Mode,
    val chatSpeakLang: String = "auto",
    val pracLang: String = "en",
    val pracLevel: String = "B1-B2",
    val pracTopic: String = "",
    val policy: CorrectionPolicy = CorrectionPolicy.SOMETIMES
)

/**
 * The only session state machine: owns the realtime websocket, the audio engine, the transcript and
 * the persistence of the finished session (plus its practice report).
 */
class SessionViewModel(app: Application) : AndroidViewModel(app) {

    enum class Status { IDLE, CONNECTING, CONNECTED, RECONNECTING, ENDED, FAILED }
    enum class AgentState { LISTENING, THINKING, SPEAKING }

    data class UiState(
        val status: Status = Status.IDLE,
        val agentState: AgentState = AgentState.LISTENING,
        val mode: Mode = Mode.CHAT,
        val messages: List<ChatMessage> = emptyList(),
        val liveUser: String = "",
        val liveAgent: String = "",
        val micMuted: Boolean = false,
        /** Every output the platform offers right now (speaker, earpiece, headset, Bluetooth...). */
        val outputs: List<AudioOutput> = emptyList(),
        val currentOutput: AudioOutput? = null,
        val pttActive: Boolean = false,
        val elapsedSec: Int = 0,
        val error: String? = null,
        val translatingCount: Int = 0,
        val reportGenerating: Boolean = false,
        val report: String? = null,
        val reportSaved: Boolean = false,
        val micLevel: Float = 0f,
        val userTurns: Int = 0,
        val settings: AppSettings = AppSettings()
    )

    private val prefsRepo = SettingsRepository(app)
    private val sessionRepo = SessionRepo(app)
    private val audio = AudioEngine(app)

    private val _ui = MutableStateFlow(UiState())
    val ui = _ui.asStateFlow()

    private val starting = AtomicBoolean(false)

    @Volatile
    private var settings = AppSettings()

    private var config: SessionStartConfig? = null

    @Volatile
    private var client: RealtimeClient? = null

    @Volatile
    private var connGeneration = 0

    /** Invalidates a start coroutine that is still waiting for settings when the session ends. */
    @Volatile
    private var sessionSerial = 0

    @Volatile
    private var ended = false

    private var textApi: TextApi? = null

    private var sessionId = ""
    private var startedAt = 0L
    private var saveJob: Job? = null
    private var reportBase: StoredSession? = null
    private var reconnectAttempts = 0
    private var replayDoneGeneration = -1
    private var reconnectJob: Job? = null
    private var timerJob: Job? = null
    private var playbackJob: Job? = null
    private var micLevelJob: Job? = null

    /** Recreated for every session so the previous session's queued PCM can never bleed into a new one. */
    @Volatile
    private var playChannel = Channel<ByteArray>(Channel.UNLIMITED)

    private val msgSeq = AtomicLong(0)
    private val base64Encoder = Base64.getEncoder()
    private val pttBytes = AtomicLong(0)

    /** Latest raw mic level from the audio thread; smoothed and throttled by [startMicLevelTicker]. */
    @Volatile
    private var rawMicLevel = 0f

    // Transcript accumulation for the assistant response currently in flight.
    @Volatile
    private var responseActive = false

    @Volatile
    private var currentResponseId: String? = null
    private var liveAgentBuf = ""
    private var finalAgentTranscript: String? = null

    // ------------------------------------------------------------------ session start

    fun start(cfg: SessionStartConfig) {
        val status = _ui.value.status
        if (status == Status.CONNECTING || status == Status.CONNECTED ||
            status == Status.RECONNECTING
        ) {
            return
        }
        if (!starting.compareAndSet(false, true)) return
        val serial = ++sessionSerial
        viewModelScope.launch {
            try {
                beginSession(cfg, serial)
            } finally {
                starting.set(false)
            }
        }
    }

    private suspend fun beginSession(cfg: SessionStartConfig, serial: Int) {
        // Remember the config first so retry() also works when the start-up checks below fail.
        config = cfg
        val loaded = prefsRepo.settings.first()
        // The user may have left the screen while the settings were loading.
        if (serial != sessionSerial) return
        settings = loaded
        if (loaded.apiKey.isBlank()) {
            fail(str(R.string.core_error_no_api_key))
            return
        }
        if (!hasNetwork()) {
            fail(str(R.string.core_error_offline))
            return
        }

        sessionId = "s_" + System.currentTimeMillis()
        startedAt = System.currentTimeMillis()
        ended = false
        connGeneration++
        reconnectAttempts = 0
        saveJob = null
        reportBase = null
        liveAgentBuf = ""
        finalAgentTranscript = null
        responseActive = false
        currentResponseId = null
        rawMicLevel = 0f
        pttBytes.set(0L)
        textApi = TextApi(loaded.textEndpoint, loaded.apiKey, timeoutSec = 120)

        audio.micMuted = false
        audio.micGain = loaded.micGain
        audio.transmitEnabled = loaded.vadMode != VadMode.PTT

        _ui.update {
            it.copy(
                status = Status.CONNECTING,
                agentState = AgentState.LISTENING,
                mode = cfg.mode,
                messages = emptyList(),
                liveUser = "",
                liveAgent = "",
                micMuted = false,
                outputs = emptyList(),
                currentOutput = null,
                pttActive = false,
                elapsedSec = 0,
                error = null,
                translatingCount = 0,
                reportGenerating = false,
                report = null,
                reportSaved = false,
                micLevel = 0f,
                userTurns = 0,
                settings = loaded
            )
        }

        startTimer()
        startMicLevelTicker()
        resetPlaybackChannel()
        connect()
    }

    private fun connect() {
        if (config == null) return
        val generation = ++connGeneration
        // Creating the new socket without closing the old one leaked an OkHttp dispatcher thread and a
        // socket on every reconnect attempt.
        client?.close()
        val holder = arrayOfNulls<RealtimeClient>(1)
        val rc = RealtimeClient(
            settings.rtEndpoint,
            settings.apiKey,
            settings.rtModel,
            object : RealtimeClient.Listener {
                override fun onOpen() = Unit

                override fun onEvent(event: RtEvent) {
                    if (!ended && connGeneration == generation) handleEvent(event)
                }

                override fun onClose(code: Int, reason: String?) {
                    if (ended || connGeneration != generation) return
                    if (holder[0]?.closedByUser == true) return
                    handleDisconnect(describeClose(code, reason))
                }

                override fun onFailure(throwable: Throwable, message: String?) {
                    if (ended || connGeneration != generation) return
                    val detail = message ?: throwable.message
                        ?: throwable.javaClass.simpleName
                    if (detail.contains("401") || detail.contains("403")) {
                        fail(str(R.string.core_error_auth))
                        return
                    }
                    handleDisconnect(detail)
                }
            }
        )
        holder[0] = rc
        client = rc
        rc.connect()
    }

    private fun describeClose(code: Int, reason: String?): String =
        if (reason.isNullOrBlank()) "code $code" else "$reason (code $code)"

    // ------------------------------------------------------------------ realtime events

    private fun handleEvent(event: RtEvent) {
        when (event) {
            is RtEvent.SessionCreated -> {
                val cfg = config ?: return
                client?.sendSessionUpdate(
                    SessionConfig(
                        instructions = buildInstructions(cfg),
                        voice = settings.voice,
                        vadMode = settings.vadMode,
                        vadThreshold = settings.vadThreshold,
                        silenceMs = settings.silenceMs,
                        asrLang = settings.asrLang,
                        flavor = settings.flavor
                    )
                )
            }

            is RtEvent.SessionUpdated -> onSessionReady()

            is RtEvent.SpeechStarted -> {
                // Server VAD heard the user talking over the assistant: barge in only if allowed.
                if (settings.bargeIn && responseActive && !ended) bargeIn()
            }

            is RtEvent.SpeechStopped -> Unit

            is RtEvent.InputCommitted -> Unit

            is RtEvent.UserTranscriptDelta -> {
                if (!settings.subtitleUser) return
                _ui.update { it.copy(liveUser = event.text + (event.stash ?: "")) }
            }

            is RtEvent.UserTranscriptDone -> onUserTranscript(event)

            is RtEvent.ResponseCreated -> {
                responseActive = true
                currentResponseId = event.responseId
                liveAgentBuf = ""
                finalAgentTranscript = null
                _ui.update { it.copy(agentState = AgentState.THINKING, liveAgent = "") }
            }

            is RtEvent.AgentTranscriptDelta -> {
                if (!responseActive || !isCurrentResponse(event.responseId)) return
                liveAgentBuf += event.delta
                if (settings.subtitleAi) _ui.update { it.copy(liveAgent = liveAgentBuf) }
            }

            is RtEvent.AgentTranscriptDone -> {
                if (!responseActive || !isCurrentResponse(event.responseId)) return
                finalAgentTranscript = event.transcript.ifBlank { null }
            }

            is RtEvent.AudioDelta -> {
                // Audio belonging to a cancelled response must never reach the speaker again.
                if (!responseActive || !isCurrentResponse(event.responseId)) return
                _ui.update { it.copy(agentState = AgentState.SPEAKING) }
                playChannel.trySend(event.pcm)
            }

            is RtEvent.AudioDone -> Unit

            is RtEvent.ResponseDone -> {
                if (!responseActive || !isCurrentResponse(event.responseId)) return
                finishResponse()
            }

            is RtEvent.RtError -> {
                val type = event.type.orEmpty()
                if (type.contains("server", ignoreCase = true)) {
                    if (!ended) handleDisconnect(event.message ?: type)
                } else {
                    _ui.update {
                        it.copy(error = str(R.string.core_error_server, event.message ?: type))
                    }
                }
            }
        }
    }

    /** The server only sends a response id when it knows it; fall back to "the active response". */
    private fun isCurrentResponse(responseId: String?): Boolean {
        val current = currentResponseId
        return responseId == null || current == null || responseId == current
    }

    private fun onSessionReady() {
        if (ended) return
        val firstConnect = _ui.value.status == Status.CONNECTING
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempts = 0
        responseActive = false
        currentResponseId = null
        liveAgentBuf = ""
        finalAgentTranscript = null

        // Explicitly reset the state machine: after a reconnect the orb used to stay stuck in SPEAKING.
        _ui.update {
            it.copy(
                status = Status.CONNECTED,
                agentState = AgentState.LISTENING,
                liveUser = "",
                liveAgent = "",
                error = null
            )
        }

        audio.onMicData = { bytes -> transmitMicFrame(bytes) }
        audio.onMicLevel = { level -> rawMicLevel = level }
        audio.onOutputsChanged = { list, current ->
            _ui.update { it.copy(outputs = list, currentOutput = current) }
        }
        if (!audio.start(settings.audioRoute)) {
            // The engine returns false when the microphone is unusable: do not fake a live session.
            fail(str(R.string.core_error_mic_init))
            return
        }
        audio.micMuted = _ui.value.micMuted
        audio.micGain = settings.micGain
        applyTransmitMode()

        // A server may confirm the agent setup more than once per socket; replay the history only once
        // per connection or the model receives duplicated context.
        if (connGeneration != replayDoneGeneration) {
            replayDoneGeneration = connGeneration
            if (!firstConnect) replayRecentContext()
        }
    }

    /** Runs on the audio thread. Base64 encoding and the OkHttp send call are both thread-safe. */
    private fun transmitMicFrame(bytes: ByteArray) {
        val rc = client ?: return
        if (ended || _ui.value.status != Status.CONNECTED) return
        pttBytes.addAndGet(bytes.size.toLong())
        rc.sendAudio(base64Encoder.encodeToString(bytes))
    }

    /** After a reconnect the model keeps its context by replaying the recent tail of the conversation. */
    private fun replayRecentContext() {
        val rc = client ?: return
        _ui.value.messages
            .filter { it.kind == "normal" }
            .takeLast(MAX_REPLAY_MESSAGES)
            .forEach { message ->
                when (message.role) {
                    Role.USER -> rc.replayMessage(true, message.text)
                    Role.ASSISTANT -> rc.replayMessage(false, message.text)
                    Role.SYSTEM -> Unit
                }
            }
    }

    private fun onUserTranscript(event: RtEvent.UserTranscriptDone) {
        val text = event.transcript.trim()
        if (text.isEmpty()) {
            _ui.update { it.copy(liveUser = "") }
            return
        }
        val message = ChatMessage(
            id = event.itemId ?: "m${msgSeq.incrementAndGet()}",
            role = Role.USER,
            text = text,
            ts = System.currentTimeMillis()
        )
        // A replayed/re-sent item id must not duplicate the bubble.
        if (_ui.value.messages.any { it.id == message.id }) {
            _ui.update { it.copy(liveUser = "") }
            return
        }
        _ui.update {
            it.copy(
                liveUser = "",
                messages = it.messages + message,
                userTurns = it.userTurns + 1
            )
        }
        translateAsync(message)
    }

    private fun finishResponse() {
        responseActive = false
        currentResponseId = null
        val text = (finalAgentTranscript ?: liveAgentBuf).trim()
        liveAgentBuf = ""
        finalAgentTranscript = null
        if (text.isEmpty()) {
            _ui.update { it.copy(liveAgent = "", agentState = AgentState.LISTENING) }
            return
        }
        val message = ChatMessage(
            id = "a${msgSeq.incrementAndGet()}",
            role = Role.ASSISTANT,
            text = text,
            ts = System.currentTimeMillis()
        )
        _ui.update {
            it.copy(
                liveAgent = "",
                agentState = AgentState.LISTENING,
                messages = it.messages + message
            )
        }
        translateAsync(message)
    }

    /** User interrupts the assistant: cancel server-side, drop local playback and keep what was said. */
    private fun bargeIn() {
        responseActive = false
        currentResponseId = null
        drainPlaybackChannel()
        runCatching { audio.stopPlayback() }
        client?.cancelResponse()
        commitPartialAgentText()
    }

    private fun drainPlaybackChannel() {
        while (playChannel.tryReceive().isSuccess) {
            // Discard whatever the interrupted response already queued up.
        }
    }

    private fun commitPartialAgentText() {
        val partial = (finalAgentTranscript ?: liveAgentBuf).trim()
        liveAgentBuf = ""
        finalAgentTranscript = null
        if (partial.isEmpty()) {
            _ui.update { it.copy(agentState = AgentState.LISTENING, liveAgent = "") }
            return
        }
        val message = ChatMessage(
            id = "a${msgSeq.incrementAndGet()}",
            role = Role.ASSISTANT,
            text = partial,
            ts = System.currentTimeMillis()
        )
        _ui.update {
            it.copy(
                agentState = AgentState.LISTENING,
                liveAgent = "",
                messages = it.messages + message
            )
        }
        translateAsync(message)
    }

    // ------------------------------------------------------------------ connection lifecycle

    private fun handleDisconnect(reason: String) {
        if (ended) return
        audio.transmitEnabled = false
        drainPlaybackChannel()
        runCatching { audio.stopPlayback() }
        connGeneration++
        client?.close()
        client = null
        reconnectJob?.cancel()
        reconnectJob = null

        if (!settings.autoReconnect) {
            fail(str(R.string.core_error_connection_closed, reason))
            return
        }
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            fail(str(R.string.core_error_reconnect_failed))
            return
        }
        reconnectAttempts++
        _ui.update {
            it.copy(
                status = Status.RECONNECTING,
                agentState = AgentState.LISTENING,
                liveAgent = "",
                error = str(R.string.core_error_reconnecting)
            )
        }
        reconnectJob = viewModelScope.launch {
            delay(600L * reconnectAttempts)
            if (!ended) connect()
        }
    }

    private fun fail(message: String) {
        releaseTransport()
        _ui.update {
            it.copy(
                status = Status.FAILED,
                agentState = AgentState.LISTENING,
                liveUser = "",
                liveAgent = "",
                pttActive = false,
                micLevel = 0f,
                error = message
            )
        }
    }

    private fun releaseTransport() {
        connGeneration++
        sessionSerial++
        reconnectJob?.cancel()
        reconnectJob = null
        timerJob?.cancel()
        timerJob = null
        micLevelJob?.cancel()
        micLevelJob = null
        playbackJob?.cancel()
        playbackJob = null
        audio.transmitEnabled = false
        runCatching { audio.stopPlayback() }
        runCatching { audio.stop() }
        client?.close()
        client = null
        runCatching { playChannel.close() }
        rawMicLevel = 0f
    }

    // ------------------------------------------------------------------ user actions

    fun toggleMute() {
        val muted = !_ui.value.micMuted
        audio.micMuted = muted
        if (muted) rawMicLevel = 0f
        _ui.update { it.copy(micMuted = muted, micLevel = if (muted) 0f else it.micLevel) }
        applyTransmitMode()
    }

    /**
     * Switches the assistant's voice to one of the enumerated outputs and remembers that choice as
     * the preferred route for future sessions. AUTO stays available from the settings screen.
     */
    fun selectOutput(output: AudioOutput) {
        if (!audio.selectOutput(output)) return
        viewModelScope.launch {
            runCatching { prefsRepo.setAudioRoute(output.route) }
        }
        audio.publishOutputs()
    }

    /** Re-reads the device list (used when the picker opens so it is never stale). */
    fun refreshOutputs() {
        audio.publishOutputs()
    }

    /** Drops the explicit device pin so the platform follows headphones/Bluetooth on its own. */
    fun setAutoRoute() {
        audio.applyPreferredRoute(AudioRoute.AUTO)
        viewModelScope.launch {
            runCatching { prefsRepo.setAudioRoute(AudioRoute.AUTO) }
        }
    }

    fun pttDown() {
        if (settings.vadMode != VadMode.PTT) return
        if (settings.bargeIn && responseActive) bargeIn()
        pttBytes.set(0L)
        _ui.update { it.copy(pttActive = true) }
        applyTransmitMode()
        client?.clearAudioBuffer()
    }

    fun pttUp() {
        if (settings.vadMode != VadMode.PTT) return
        if (!_ui.value.pttActive) return
        _ui.update { it.copy(pttActive = false) }
        applyTransmitMode()
        val captured = pttBytes.getAndSet(0L)
        // Committing an empty / too short buffer only earns a server error event.
        if (captured < PTT_MIN_COMMIT_BYTES) return
        val rc = client ?: return
        rc.commitAudio()
        rc.triggerResponse()
    }

    fun sendText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val local = ChatMessage(
            id = "m${msgSeq.incrementAndGet()}",
            role = Role.USER,
            text = trimmed,
            ts = System.currentTimeMillis()
        )
        _ui.update { it.copy(messages = it.messages + local) }

        val rc = client
        if (_ui.value.status != Status.CONNECTED || rc == null || !rc.sendUserText(trimmed)) {
            // Offline: the bubble would otherwise sit in the transcript forever, so roll it back.
            _ui.update {
                it.copy(
                    messages = it.messages.filterNot { message -> message.id == local.id },
                    error = str(R.string.core_error_not_connected)
                )
            }
            return
        }
        _ui.update { it.copy(userTurns = it.userTurns + 1) }
        translateAsync(local)
    }

    fun dismissError() {
        _ui.update { it.copy(error = null) }
    }

    fun retry() {
        val cfg = config ?: return
        val status = _ui.value.status
        if (status == Status.CONNECTING || status == Status.CONNECTED ||
            status == Status.RECONNECTING
        ) {
            return
        }
        releaseTransport()
        ended = false
        _ui.update {
            it.copy(
                status = Status.IDLE,
                agentState = AgentState.LISTENING,
                liveUser = "",
                liveAgent = "",
                pttActive = false,
                micLevel = 0f,
                error = null
            )
        }
        start(cfg)
    }

    // ------------------------------------------------------------------ session end

    fun endSession(generateReportIfEnabled: Boolean = true) {
        if (ended) return
        ended = true
        val cfg = config
        val messages = _ui.value.messages
        val durationMs = if (startedAt > 0L) {
            (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
        } else {
            0L
        }

        releaseTransport()
        _ui.update {
            it.copy(
                status = Status.ENDED,
                agentState = AgentState.LISTENING,
                liveUser = "",
                liveAgent = "",
                pttActive = false,
                micLevel = 0f
            )
        }

        if (cfg == null || messages.isEmpty()) return

        val stored = StoredSession(
            meta = SessionMeta(
                id = sessionId,
                mode = cfg.mode,
                title = buildTitle(cfg, messages),
                language = if (cfg.mode == Mode.PRACTICE) cfg.pracLang else "",
                createdAt = startedAt,
                durationMs = durationMs,
                messageCount = messages.size
            ),
            messages = messages
        )
        reportBase = stored
        // Capture the save job and the fallback copy locally: starting another session resets these
        // fields, but the report must still be written into *this* session.
        val persistJob = SessionPersistence.scope.launch {
            runCatching { sessionRepo.save(stored) }
        }
        saveJob = persistJob

        if (generateReportIfEnabled && cfg.mode == Mode.PRACTICE && settings.pracReport &&
            messages.any { it.role == Role.USER }
        ) {
            generateReport(cfg, messages, persistJob, stored)
        }
    }

    fun regenerateReport() {
        val cfg = config ?: return
        if (_ui.value.reportGenerating) return
        val messages = _ui.value.messages
        if (messages.none { it.role == Role.USER }) return
        generateReport(cfg, messages, saveJob, reportBase)
    }

    private fun generateReport(
        cfg: SessionStartConfig,
        messages: List<ChatMessage>,
        baseJob: Job?,
        fallbackBase: StoredSession?
    ) {
        val ownerSessionId = sessionId
        val api = textApi
        if (api == null) {
            _ui.update { it.copy(error = str(R.string.core_error_report_unavailable)) }
            return
        }
        val model = settings.textModel
        _ui.update { it.copy(reportGenerating = true, report = null, reportSaved = false) }
        // Runs on the application scope so the report still lands in the stored session even when the
        // user leaves the screen while it is being generated.
        SessionPersistence.scope.launch {
            try {
                val dialog = messages.filter { it.kind == "normal" }
                    .map { message -> (message.role == Role.USER) to message.text }
                val content = api.complete(
                    model,
                    listOf(
                        "system" to Prompts.reportSystem,
                        "user" to Prompts.reportUserPrompt(cfg.pracLang, cfg.pracLevel, dialog)
                    ),
                    temperature = 0.4,
                    maxTokens = 3000
                )
                // Persist into the session this report belongs to, even if another one is already live.
                val persisted = persistReport(ownerSessionId, content, baseJob, fallbackBase)
                // Never leak this report's state (text, spinner, error) into the next session's UiState.
                if (sessionId != ownerSessionId) return@launch
                _ui.update {
                    it.copy(report = content, reportGenerating = false, reportSaved = persisted)
                }
            } catch (e: Exception) {
                if (sessionId != ownerSessionId) return@launch
                _ui.update {
                    it.copy(
                        reportGenerating = false,
                        reportSaved = false,
                        error = str(
                            R.string.core_error_report_failed,
                            e.message?.take(MAX_ERROR_DETAIL_CHARS).orEmpty()
                        )
                    )
                }
            }
        }
    }

    /**
     * Writes the report into the already stored session. The save job and the fallback copy are passed
     * in explicitly because a new session may already have reset the corresponding fields, and the
     * report must still end up in the session it belongs to. The save started by [endSession] may also
     * still be in flight, so this waits for it first; otherwise the report-less copy could win the race
     * and the report would silently disappear.
     */
    private suspend fun persistReport(
        id: String,
        content: String,
        baseJob: Job?,
        fallbackBase: StoredSession?
    ): Boolean {
        if (id.isEmpty()) return false
        baseJob?.join()
        return try {
            val existing = sessionRepo.load(id)
            val base = existing ?: fallbackBase?.takeIf { it.meta.id == id } ?: return false
            sessionRepo.save(base.copy(report = PracticeReport(content, System.currentTimeMillis())))
            true
        } catch (_: Exception) {
            false
        }
    }

    // ------------------------------------------------------------------ translation

    private fun translateAsync(message: ChatMessage) {
        if (!settings.translateEnabled || message.text.isBlank()) return
        val api = textApi ?: return
        _ui.update { it.copy(translatingCount = it.translatingCount + 1) }
        viewModelScope.launch {
            try {
                val target = Languages.name(settings.translateLang)
                val translated = api.complete(
                    settings.textModel,
                    listOf("system" to Prompts.translateSystem(target), "user" to message.text),
                    temperature = 0.2,
                    maxTokens = 1000
                )
                _ui.update { state ->
                    state.copy(
                        messages = state.messages.map { current ->
                            if (current.id == message.id) {
                                current.copy(translation = translated)
                            } else {
                                current
                            }
                        },
                        translatingCount = (state.translatingCount - 1).coerceAtLeast(0)
                    )
                }
            } catch (_: Exception) {
                _ui.update { state ->
                    state.copy(
                        messages = state.messages.map { current ->
                            if (current.id == message.id) {
                                current.copy(translation = str(R.string.core_translation_failed))
                            } else {
                                current
                            }
                        },
                        translatingCount = (state.translatingCount - 1).coerceAtLeast(0)
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun buildInstructions(cfg: SessionStartConfig): String =
        if (cfg.mode == Mode.PRACTICE) {
            Prompts.practice(cfg.pracLang, cfg.pracLevel, cfg.pracTopic, cfg.policy)
        } else {
            Prompts.chat(settings.chatPrompt, cfg.chatSpeakLang.ifBlank { settings.chatSpeakLang })
        }

    private fun buildTitle(cfg: SessionStartConfig, messages: List<ChatMessage>): String {
        val firstUser = messages.firstOrNull { it.role == Role.USER }?.text?.take(MAX_TITLE_CHARS)
        return when (cfg.mode) {
            Mode.PRACTICE -> {
                val language = Languages.name(cfg.pracLang)
                if (firstUser == null) {
                    str(R.string.core_title_practice, language)
                } else {
                    str(R.string.core_title_practice_topic, language, firstUser)
                }
            }

            Mode.CHAT -> firstUser ?: str(R.string.core_title_chat)
        }
    }

    private fun applyTransmitMode() {
        audio.transmitEnabled = if (settings.vadMode == VadMode.PTT) {
            val state = _ui.value
            state.pttActive && !state.micMuted
        } else {
            true
        }
    }

    private fun hasNetwork(): Boolean {
        val manager = getApplication<Application>()
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun str(resId: Int, vararg args: Any): String =
        getApplication<Application>().getString(resId, *args)

    // ------------------------------------------------------------------ timers & pumps

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                if (ended) break
                _ui.update { it.copy(elapsedSec = it.elapsedSec + 1) }
            }
        }
    }

    /** Smooths the mic level (fast attack, slow release) and publishes it at most 20 times a second. */
    private fun startMicLevelTicker() {
        micLevelJob?.cancel()
        micLevelJob = viewModelScope.launch {
            var smoothed = 0f
            while (isActive) {
                delay(MIC_LEVEL_INTERVAL_MS)
                val state = _ui.value
                val target = if (!ended && state.status == Status.CONNECTED && !state.micMuted) {
                    rawMicLevel
                } else {
                    0f
                }
                smoothed += (target - smoothed) * if (target > smoothed) 0.65f else 0.2f
                if (smoothed < 0.004f) smoothed = 0f
                val previous = state.micLevel
                if (abs(smoothed - previous) > 0.003f || (smoothed == 0f && previous != 0f)) {
                    _ui.update { it.copy(micLevel = smoothed) }
                }
            }
        }
    }

    private fun resetPlaybackChannel() {
        val previous = playChannel
        val fresh = Channel<ByteArray>(Channel.UNLIMITED)
        playChannel = fresh
        playbackJob?.cancel()
        // Drop anything the previous session left queued / buffered in the speaker pipeline.
        runCatching { previous.close() }
        runCatching { audio.stopPlayback() }
        playbackJob = viewModelScope.launch(Dispatchers.IO) {
            for (chunk in fresh) {
                if (ended) break
                audio.playPcm(chunk)
            }
        }
    }

    override fun onCleared() {
        // Persist the tail of the session on the application scope: viewModelScope is already cancelled
        // here, so saving through it used to lose the final part of every session.
        endSession(generateReportIfEnabled = false)
        super.onCleared()
    }
}
