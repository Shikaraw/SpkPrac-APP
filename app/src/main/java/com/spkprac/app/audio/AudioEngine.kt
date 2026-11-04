package com.spkprac.app.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.spkprac.app.data.AudioRoute
import kotlin.math.sqrt

/**
 * Full-duplex audio: 16 kHz mono PCM16 capture from the microphone and 24 kHz mono PCM16 streaming
 * playback to the speaker/earpiece.
 *
 * Capture uses the VOICE_COMMUNICATION source (platform AEC/NS) and call-style audio routing so the
 * assistant's own voice is not fed back into the recogniser.
 *
 * Threading: the mic thread reads fixed 100 ms frames, applies [micGain] (clipped, so loud input never
 * wraps around) and then reports both the level ([onMicLevel], always) and the PCM frame ([onMicData],
 * only while [transmitEnabled] and not [micMuted]). Callbacks therefore run on the audio thread and
 * must not block; they never touch the main thread by themselves.
 */
class AudioEngine(context: Context) {

    companion object {
        const val SAMPLE_RATE_IN = 16000
        const val SAMPLE_RATE_OUT = 24000

        /** 100 ms of 16 kHz mono 16-bit audio. */
        private const val FRAME_BYTES = 3200
        private const val MIN_GAIN = 0.1f
        private const val MAX_GAIN = 3.0f
    }

    private val appContext: Context = context.applicationContext

    /** True while the capture thread is alive; [start] flips it back to false when the mic fails. */
    @Volatile
    var running = false
        private set

    /** While muted nothing is transmitted; the level meter drops to zero as well. */
    @Volatile
    var micMuted = false

    /** Software gain applied to captured frames before they are handed over (1.0 = passthrough). */
    @Volatile
    var micGain = 1.0f

    /**
     * Push-to-talk gate: when false the microphone is still metered but the audio is NOT transmitted,
     * so a PTT session never uploads the silence/noise that happens before the button is pressed.
     */
    @Volatile
    var transmitEnabled = true

    /** Called on the audio thread with a 100 ms PCM16 frame (16 kHz mono) that should be transmitted. */
    @Volatile
    var onMicData: ((ByteArray) -> Unit)? = null

    /** Called on the audio thread with a smoothed 0f..1f input level. */
    @Volatile
    var onMicLevel: ((Float) -> Unit)? = null

    /** English diagnostic of the most recent init/IO failure. Internal logging only. */
    val lastError: String?
        get() = lastErrorState

    @Volatile
    private var lastErrorState: String? = null

    private val audioManager: AudioManager?
        get() = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    @Volatile
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var audioTrack: AudioTrack? = null

    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private val trackLock = Any()

    /** Bumped by every start/stop so a dying capture thread can never publish into a newer session. */
    @Volatile
    private var sessionGen = 0

    // ------------------------------------------------------------------ lifecycle

    /**
     * Starts capture and playback.
     *
     * @return false when the microphone could not be initialized; the caller must surface an error
     * instead of pretending the session is live.
     */
    @SuppressLint("MissingPermission")
    fun start(preferredRoute: AudioRoute = AudioRoute.AUTO): Boolean {
        if (running) {
            applyPreferredRoute(preferredRoute)
            return true
        }
        lastErrorState = null
        running = true
        val generation = ++sessionGen
        enterCallMode(preferredRoute)
        if (!startRecorder(generation)) {
            // Never leave the engine half-initialized: the UI used to claim "connected" with a dead mic.
            running = false
            sessionGen++
            releaseRecorder()
            releaseTrack()
            exitCallMode()
            if (lastErrorState == null) lastErrorState = "microphone initialization failed"
            return false
        }
        ensureTrack()
        return true
    }

    /** Stops capture and playback and restores normal audio routing. Safe to call repeatedly. */
    fun stop() {
        running = false
        sessionGen++
        releaseRecorder()
        releaseTrack()
        exitCallMode()
    }

    // ------------------------------------------------------------------ capture

    @SuppressLint("MissingPermission")
    private fun startRecorder(generation: Int): Boolean {
        releaseRecorder()

        val minBuffer = try {
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE_IN,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
        } catch (e: Exception) {
            lastErrorState = "getMinBufferSize failed: ${e.message}"
            return false
        }
        if (minBuffer <= 0) {
            lastErrorState = "unsupported recording configuration (minBuffer=$minBuffer)"
            return false
        }

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE_IN,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, FRAME_BYTES * 4)
            )
        } catch (e: Exception) {
            lastErrorState = "AudioRecord creation failed: ${e.message}"
            return false
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            lastErrorState = "AudioRecord not initialized (state=${record.state})"
            runCatching { record.release() }
            return false
        }
        audioRecord = record

        aec = try {
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(record.audioSessionId)?.also { effect ->
                    runCatching { effect.setEnabled(true) }
                }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
        ns = try {
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(record.audioSessionId)?.also { effect ->
                    runCatching { effect.setEnabled(true) }
                }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }

        try {
            record.startRecording()
        } catch (e: Exception) {
            lastErrorState = "AudioRecord.startRecording failed: ${e.message}"
            runCatching { record.release() }
            audioRecord = null
            return false
        }

        Thread {
            val buffer = ByteArray(FRAME_BYTES)
            while (running && sessionGen == generation) {
                val read = try {
                    record.read(buffer, 0, buffer.size)
                } catch (_: Exception) {
                    break
                }
                if (read < 0) break
                if (read == 0) continue
                val level = applyGainAndMeasure(buffer, read)
                if (micMuted) {
                    onMicLevel?.invoke(0f)
                    continue
                }
                onMicLevel?.invoke(level)
                if (transmitEnabled) {
                    onMicData?.invoke(buffer.copyOf(read))
                }
            }
        }.apply {
            name = "SpkPracMic"
            isDaemon = true
            start()
        }
        return true
    }

    /**
     * Applies [micGain] in place (clipped to the 16-bit range) and returns the normalized RMS level of
     * the resulting frame, in 0f..1f, shaped so normal speech sits around 0.2-0.7.
     */
    private fun applyGainAndMeasure(buffer: ByteArray, bytes: Int): Float {
        val samples = bytes / 2
        if (samples <= 0) return 0f
        val gain = micGain.coerceIn(MIN_GAIN, MAX_GAIN)
        val amplify = gain != 1f
        var sumSquares = 0.0
        for (i in 0 until samples) {
            val index = i shl 1
            var sample = (buffer[index + 1].toInt() shl 8) or (buffer[index].toInt() and 0xFF)
            if (amplify) {
                sample = (sample * gain).toInt().coerceIn(-32768, 32767)
                buffer[index] = (sample and 0xFF).toByte()
                buffer[index + 1] = ((sample shr 8) and 0xFF).toByte()
            }
            sumSquares += sample.toDouble() * sample.toDouble()
        }
        val rms = sqrt(sumSquares / samples)
        return (sqrt(rms / 32768.0) * 2.2).coerceIn(0.0, 1.0).toFloat()
    }

    private fun releaseRecorder() {
        val record = audioRecord
        audioRecord = null
        runCatching { aec?.release() }
        aec = null
        runCatching { ns?.release() }
        ns = null
        if (record != null) {
            // The capture thread exits on its own once running/sessionGen change; stop()/release()
            // unblock a pending read() so shutting down never waits for the next 100 ms frame.
            runCatching { record.stop() }
            runCatching { record.release() }
        }
    }

    // ------------------------------------------------------------------ playback

    private fun ensureTrack(): AudioTrack? {
        synchronized(trackLock) {
            audioTrack?.let { return it }
            val minBuffer = try {
                AudioTrack.getMinBufferSize(
                    SAMPLE_RATE_OUT,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
            } catch (e: Exception) {
                lastErrorState = "AudioTrack.getMinBufferSize failed: ${e.message}"
                return null
            }
            if (minBuffer <= 0) {
                lastErrorState = "unsupported playback configuration (minBuffer=$minBuffer)"
                return null
            }
            val track = try {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE_OUT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(maxOf(minBuffer, SAMPLE_RATE_OUT * 2 / 5))
                    .build()
            } catch (e: Exception) {
                lastErrorState = "AudioTrack creation failed: ${e.message}"
                return null
            }
            try {
                track.play()
            } catch (e: Exception) {
                lastErrorState = "AudioTrack.play failed: ${e.message}"
                runCatching { track.release() }
                return null
            }
            audioTrack = track
            return track
        }
    }

    /** Feeds one decoded PCM chunk (24 kHz mono 16-bit) to the speaker. Blocking write. */
    fun playPcm(data: ByteArray) {
        if (!running || data.isEmpty()) return
        val track = audioTrack ?: return
        try {
            synchronized(trackLock) {
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
            }
            track.write(data, 0, data.size)
        } catch (_: Exception) {
        }
    }

    /** Barge-in: immediately drops everything still buffered in the playback pipeline. */
    fun stopPlayback() {
        val track = audioTrack ?: return
        try {
            synchronized(trackLock) {
                track.pause()
                track.flush()
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseTrack() {
        synchronized(trackLock) {
            val track = audioTrack
            audioTrack = null
            if (track != null) {
                runCatching { track.pause() }
                runCatching { track.flush() }
                runCatching { track.release() }
            }
        }
    }

    // ------------------------------------------------------------------ routing

    /**
     * Called with the current output list whenever the platform reports a device change (plugging
     * headphones, connecting a Bluetooth headset, ...) or after an explicit route change.
     */
    @Volatile
    var onOutputsChanged: ((List<AudioOutput>, AudioOutput?) -> Unit)? = null

    private var deviceCallback: AudioDeviceCallback? = null

    /** Every output the platform currently offers for communication audio. */
    fun availableOutputs(): List<AudioOutput> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = runCatching {
                audioManager?.availableCommunicationDevices ?: emptyList()
            }.getOrDefault(emptyList())
            if (devices.isNotEmpty()) {
                return devices.map { device ->
                    AudioOutput(
                        id = device.id,
                        route = device.type.toAudioRoute(),
                        name = runCatching { device.productName?.toString() }
                            .getOrNull()?.takeIf { it.isNotBlank() }
                    )
                }
            }
        }
        // Below Android 12 there is no enumerable communication-device API: offer the built-ins plus
        // whatever the platform currently reports as the live route (headset/BT when connected).
        val builtIns = listOf(
            AudioOutput(AudioOutput.LEGACY_SPEAKER_ID, AudioRoute.SPEAKER, null),
            AudioOutput(AudioOutput.LEGACY_EARPIECE_ID, AudioRoute.EARPIECE, null)
        )
        val current = currentOutput()
        return if (current != null && current.route != AudioRoute.SPEAKER &&
            current.route != AudioRoute.EARPIECE
        ) builtIns + current else builtIns
    }

    /** The route audio is actually going to right now, or null when unknown. */
    fun currentOutput(): AudioOutput? {
        val manager = audioManager ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val device = runCatching { manager.communicationDevice }.getOrNull() ?: return null
            return AudioOutput(
                id = device.id,
                route = device.type.toAudioRoute(),
                name = runCatching { device.productName?.toString() }
                    .getOrNull()?.takeIf { it.isNotBlank() }
            )
        }
        @Suppress("DEPRECATION")
        val speaker = runCatching { manager.isSpeakerphoneOn }.getOrDefault(true)
        return if (speaker) {
            AudioOutput(AudioOutput.LEGACY_SPEAKER_ID, AudioRoute.SPEAKER, null)
        } else {
            AudioOutput(AudioOutput.LEGACY_EARPIECE_ID, AudioRoute.EARPIECE, null)
        }
    }

    /**
     * Applies the preferred route. [AudioRoute.AUTO] clears any explicit pin so the platform's own
     * communication policy wins - that is what lets a wired or Bluetooth headset take over instead of
     * being overridden by the loudspeaker.
     */
    fun applyPreferredRoute(route: AudioRoute) {
        val manager = audioManager
        if (manager != null) {
            try {
                if (route == AudioRoute.AUTO) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        runCatching { manager.clearCommunicationDevice() }
                    }
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val target = runCatching { manager.availableCommunicationDevices }
                        .getOrDefault(emptyList())
                        .firstOrNull { it.type.toAudioRoute() == route }
                    if (target != null) runCatching { manager.setCommunicationDevice(target) }
                } else {
                    @Suppress("DEPRECATION")
                    manager.isSpeakerphoneOn = route == AudioRoute.SPEAKER
                }
            } catch (_: Exception) {
            }
        }
        publishOutputs()
    }

    /** Explicitly switches to one enumerated output. Returns false when the platform refused. */
    fun selectOutput(output: AudioOutput): Boolean {
        val manager = audioManager ?: return false
        val ok = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val target = runCatching { manager.availableCommunicationDevices }
                    .getOrDefault(emptyList())
                    .firstOrNull { it.id == output.id }
                if (target != null) manager.setCommunicationDevice(target) else false
            } else {
                @Suppress("DEPRECATION")
                manager.isSpeakerphoneOn = output.route == AudioRoute.SPEAKER
                true
            }
        } catch (_: Exception) {
            false
        }
        publishOutputs()
        return ok
    }

    /** Pushes the current output list to [onOutputsChanged] (also used on start). */
    fun publishOutputs() {
        val listener = onOutputsChanged ?: return
        val outputs = runCatching { availableOutputs() }.getOrDefault(emptyList())
        listener(outputs, currentOutput())
    }

    private fun registerDeviceCallback() {
        if (deviceCallback != null) return
        val manager = audioManager ?: return
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = publishOutputs()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = publishOutputs()
        }
        val ok = runCatching {
            manager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        }.isSuccess
        if (ok) deviceCallback = callback
    }

    private fun unregisterDeviceCallback() {
        val callback = deviceCallback ?: return
        deviceCallback = null
        runCatching { audioManager?.unregisterAudioDeviceCallback(callback) }
    }

    private fun enterCallMode(route: AudioRoute) {
        audioManager?.let { manager ->
            runCatching { manager.mode = AudioManager.MODE_IN_COMMUNICATION }
        }
        applyPreferredRoute(route)
        registerDeviceCallback()
    }

    private fun exitCallMode() {
        unregisterDeviceCallback()
        val manager = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching { manager.clearCommunicationDevice() }
            } else {
                @Suppress("DEPRECATION")
                manager.isSpeakerphoneOn = false
            }
            manager.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) {
        }
    }

    private fun Int.toAudioRoute(): AudioRoute = when (this) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioRoute.SPEAKER
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioRoute.EARPIECE
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> AudioRoute.WIRED_HEADSET
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> AudioRoute.WIRED_HEADSET
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> AudioRoute.BLUETOOTH
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> AudioRoute.BLUETOOTH
        AudioDeviceInfo.TYPE_USB_HEADSET -> AudioRoute.USB
        AudioDeviceInfo.TYPE_USB_DEVICE -> AudioRoute.USB
        else -> AudioRoute.AUTO
    }
}

/** One selectable audio output. [id] is the platform device id (synthetic for pre-Android-12). */
data class AudioOutput(
    val id: Int,
    val route: AudioRoute,
    /** Platform product name when the device exposes one; UI falls back to a localised route label. */
    val name: String? = null
) {
    companion object {
        const val LEGACY_SPEAKER_ID = -1
        const val LEGACY_EARPIECE_ID = -2
    }
}
