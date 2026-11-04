package com.spkprac.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

enum class VadMode { AUTO, PTT }
enum class ProtocolFlavor { QWEN, OPENAI }
enum class CorrectionPolicy { NEVER, SOMETIMES, ALWAYS }
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Which audio output the assistant's voice should go to.
 *
 * [AUTO] deliberately does NOT pin a device: it lets the platform pick the correct communication
 * device, which is what makes wired / Bluetooth headsets work (the old build always forced the
 * loudspeaker through setCommunicationDevice, so headphones were ignored).
 */
enum class AudioRoute { AUTO, SPEAKER, EARPIECE, WIRED_HEADSET, BLUETOOTH, USB }
enum class CaptionSize { SMALL, MEDIUM, LARGE }

data class AppSettings(
    val apiKey: String = "",
    val rtEndpoint: String = DEFAULT_RT_ENDPOINT,
    val rtModel: String = DEFAULT_RT_MODEL,
    val flavor: ProtocolFlavor = ProtocolFlavor.QWEN,
    val textEndpoint: String = DEFAULT_TEXT_ENDPOINT,
    val textModel: String = DEFAULT_TEXT_MODEL,
    val voice: String = "Tina",
    val vadMode: VadMode = VadMode.AUTO,
    val vadThreshold: Float = 0.5f,
    val silenceMs: Int = 600,
    val subtitleAi: Boolean = true,
    val subtitleUser: Boolean = true,
    val translateEnabled: Boolean = false,
    val translateLang: String = "zh",
    val asrLang: String = "",
    val audioRoute: AudioRoute = AudioRoute.AUTO,
    val keepScreenOn: Boolean = true,
    val chatPrompt: String = DEFAULT_CHAT_PROMPT,
    /** Language the assistant answers in during chat mode; "auto" mirrors the user. */
    val chatSpeakLang: String = "auto",
    val pracLang: String = "en",
    val pracLevel: String = "B1-B2",
    val pracTopic: String = "",
    val pracCorrection: CorrectionPolicy = CorrectionPolicy.SOMETIMES,
    val pracReport: Boolean = true,
    // ---- appearance / feel ----
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val captionSize: CaptionSize = CaptionSize.MEDIUM,
    val animations: Boolean = true,
    val haptics: Boolean = true,
    val showWaveform: Boolean = true,
    // ---- audio / connection ----
    val micGain: Float = 1.0f,
    val autoReconnect: Boolean = true,
    val bargeIn: Boolean = true,
    // ---- updates ----
    val autoCheckUpdate: Boolean = true,
    val updateProxy: Boolean = true,
    val lastUpdateCheckAt: Long = 0L
) {
    companion object {
        const val DEFAULT_RT_ENDPOINT =
            "wss://cn-beijing.maas.qianwenaiapi.com/api-ws/v1/realtime"
        const val DEFAULT_RT_MODEL = "qwen3.8-omni-flash-realtime"
        const val DEFAULT_TEXT_ENDPOINT =
            "https://maas.qianwenaiapi.com/compatible-mode/v1/chat/completions"
        const val DEFAULT_TEXT_MODEL = "qwen3.8-flash"
        const val DEFAULT_CHAT_PROMPT =
            "You are a friendly, knowledgeable voice assistant. Keep answers concise and natural " +
                "for speech (usually 1-3 short paragraphs unless asked for more). Avoid markdown " +
                "formatting since everything you say is spoken aloud."

        /** Voices commonly offered by the Qwen realtime endpoint; free text is still allowed. */
        val VOICE_PRESETS = listOf("Tina", "Ethan", "Cherry", "Serena", "Dylan", "Jada")
    }
}

class SettingsRepository(private val context: Context) {

    private object K {
        val apiKey = stringPreferencesKey("api_key")
        val rtEndpoint = stringPreferencesKey("rt_endpoint")
        val rtModel = stringPreferencesKey("rt_model")
        val flavor = stringPreferencesKey("flavor")
        val textEndpoint = stringPreferencesKey("text_endpoint")
        val textModel = stringPreferencesKey("text_model")
        val voice = stringPreferencesKey("voice")
        val vadMode = stringPreferencesKey("vad_mode")
        val vadThreshold = floatPreferencesKey("vad_threshold")
        val silenceMs = intPreferencesKey("silence_ms")
        val subtitleAi = booleanPreferencesKey("subtitle_ai")
        val subtitleUser = booleanPreferencesKey("subtitle_user")
        val translateEnabled = booleanPreferencesKey("translate_enabled")
        val translateLang = stringPreferencesKey("translate_lang")
        val asrLang = stringPreferencesKey("asr_lang")
        val audioRoute = stringPreferencesKey("audio_route")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")
        val chatPrompt = stringPreferencesKey("chat_prompt")
        val chatSpeakLang = stringPreferencesKey("chat_speak_lang")
        val pracLang = stringPreferencesKey("prac_lang")
        val pracLevel = stringPreferencesKey("prac_level")
        val pracTopic = stringPreferencesKey("prac_topic")
        val pracCorrection = stringPreferencesKey("prac_correction")
        val pracReport = booleanPreferencesKey("prac_report")
        val themeMode = stringPreferencesKey("theme_mode")
        val captionSize = stringPreferencesKey("caption_size")
        val animations = booleanPreferencesKey("animations")
        val haptics = booleanPreferencesKey("haptics")
        val showWaveform = booleanPreferencesKey("show_waveform")
        val micGain = floatPreferencesKey("mic_gain")
        val autoReconnect = booleanPreferencesKey("auto_reconnect")
        val bargeIn = booleanPreferencesKey("barge_in")
        val autoCheckUpdate = booleanPreferencesKey("auto_check_update")
        val updateProxy = booleanPreferencesKey("update_proxy")
        val lastUpdateCheckAt = longPreferencesKey("last_update_check_at")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            apiKey = p[K.apiKey] ?: "",
            rtEndpoint = p[K.rtEndpoint] ?: AppSettings.DEFAULT_RT_ENDPOINT,
            rtModel = p[K.rtModel] ?: AppSettings.DEFAULT_RT_MODEL,
            flavor = enumOr(p[K.flavor], ProtocolFlavor.QWEN),
            textEndpoint = p[K.textEndpoint] ?: AppSettings.DEFAULT_TEXT_ENDPOINT,
            textModel = p[K.textModel] ?: AppSettings.DEFAULT_TEXT_MODEL,
            voice = p[K.voice] ?: "Tina",
            vadMode = enumOr(p[K.vadMode], VadMode.AUTO),
            vadThreshold = p[K.vadThreshold] ?: 0.5f,
            silenceMs = p[K.silenceMs] ?: 600,
            subtitleAi = p[K.subtitleAi] ?: true,
            subtitleUser = p[K.subtitleUser] ?: true,
            translateEnabled = p[K.translateEnabled] ?: false,
            translateLang = p[K.translateLang] ?: "zh",
            asrLang = p[K.asrLang] ?: "",
            audioRoute = enumOr(p[K.audioRoute], AudioRoute.AUTO),
            keepScreenOn = p[K.keepScreenOn] ?: true,
            chatPrompt = p[K.chatPrompt] ?: AppSettings.DEFAULT_CHAT_PROMPT,
            chatSpeakLang = p[K.chatSpeakLang] ?: "auto",
            pracLang = p[K.pracLang] ?: "en",
            pracLevel = p[K.pracLevel] ?: "B1-B2",
            pracTopic = p[K.pracTopic] ?: "",
            pracCorrection = enumOr(p[K.pracCorrection], CorrectionPolicy.SOMETIMES),
            pracReport = p[K.pracReport] ?: true,
            themeMode = enumOr(p[K.themeMode], ThemeMode.SYSTEM),
            captionSize = enumOr(p[K.captionSize], CaptionSize.MEDIUM),
            animations = p[K.animations] ?: true,
            haptics = p[K.haptics] ?: true,
            showWaveform = p[K.showWaveform] ?: true,
            micGain = p[K.micGain] ?: 1.0f,
            autoReconnect = p[K.autoReconnect] ?: true,
            bargeIn = p[K.bargeIn] ?: true,
            autoCheckUpdate = p[K.autoCheckUpdate] ?: true,
            updateProxy = p[K.updateProxy] ?: true,
            lastUpdateCheckAt = p[K.lastUpdateCheckAt] ?: 0L
        )
    }

    private inline fun <reified T : Enum<T>> enumOr(raw: String?, fallback: T): T =
        if (raw == null) fallback
        else runCatching { enumValueOf<T>(raw) }.getOrDefault(fallback)

    suspend fun setApiKey(v: String) = set(K.apiKey, v)
    suspend fun setRtEndpoint(v: String) = set(K.rtEndpoint, v)
    suspend fun setRtModel(v: String) = set(K.rtModel, v)
    suspend fun setFlavor(v: ProtocolFlavor) = set(K.flavor, v.name)
    suspend fun setTextEndpoint(v: String) = set(K.textEndpoint, v)
    suspend fun setTextModel(v: String) = set(K.textModel, v)
    suspend fun setVoice(v: String) = set(K.voice, v)
    suspend fun setVadMode(v: VadMode) = set(K.vadMode, v.name)
    suspend fun setVadThreshold(v: Float) = set(K.vadThreshold, v)
    suspend fun setSilenceMs(v: Int) = set(K.silenceMs, v)
    suspend fun setSubtitleAi(v: Boolean) = set(K.subtitleAi, v)
    suspend fun setSubtitleUser(v: Boolean) = set(K.subtitleUser, v)
    suspend fun setTranslateEnabled(v: Boolean) = set(K.translateEnabled, v)
    suspend fun setTranslateLang(v: String) = set(K.translateLang, v)
    suspend fun setAsrLang(v: String) = set(K.asrLang, v)
    suspend fun setAudioRoute(v: AudioRoute) = set(K.audioRoute, v.name)
    suspend fun setKeepScreenOn(v: Boolean) = set(K.keepScreenOn, v)
    suspend fun setChatPrompt(v: String) = set(K.chatPrompt, v)
    suspend fun setChatSpeakLang(v: String) = set(K.chatSpeakLang, v)
    suspend fun setPracLang(v: String) = set(K.pracLang, v)
    suspend fun setPracLevel(v: String) = set(K.pracLevel, v)
    suspend fun setPracTopic(v: String) = set(K.pracTopic, v)
    suspend fun setPracCorrection(v: CorrectionPolicy) = set(K.pracCorrection, v.name)
    suspend fun setPracReport(v: Boolean) = set(K.pracReport, v)
    suspend fun setThemeMode(v: ThemeMode) = set(K.themeMode, v.name)
    suspend fun setCaptionSize(v: CaptionSize) = set(K.captionSize, v.name)
    suspend fun setAnimations(v: Boolean) = set(K.animations, v)
    suspend fun setHaptics(v: Boolean) = set(K.haptics, v)
    suspend fun setShowWaveform(v: Boolean) = set(K.showWaveform, v)
    suspend fun setMicGain(v: Float) = set(K.micGain, v)
    suspend fun setAutoReconnect(v: Boolean) = set(K.autoReconnect, v)
    suspend fun setBargeIn(v: Boolean) = set(K.bargeIn, v)
    suspend fun setAutoCheckUpdate(v: Boolean) = set(K.autoCheckUpdate, v)
    suspend fun setUpdateProxy(v: Boolean) = set(K.updateProxy, v)
    suspend fun setLastUpdateCheckAt(v: Long) = set(K.lastUpdateCheckAt, v)

    /** Wipe every stored preference (settings only; session history lives elsewhere). */
    suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
    }

    private suspend fun <T> set(key: androidx.datastore.preferences.core.Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }
}
