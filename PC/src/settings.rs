use serde::{Deserialize, Serialize};
use std::fs;
use std::path::PathBuf;

pub const DEFAULT_RT_ENDPOINT: &str = "wss://cn-beijing.maas.qianwenaiapi.com/api-ws/v1/realtime";
pub const DEFAULT_RT_MODEL: &str = "qwen3.8-omni-flash-realtime";
pub const DEFAULT_TEXT_ENDPOINT: &str = "https://maas.qianwenaiapi.com/compatible-mode/v1/chat/completions";
pub const DEFAULT_TEXT_MODEL: &str = "qwen3.8-flash";
pub const DEFAULT_CLONE_ENDPOINT: &str =
    "https://cn-beijing.maas.qianwenaiapi.com/api/v1/services/audio/tts/customization";
pub const DEFAULT_CHAT_PROMPT: &str = "You are a friendly, knowledgeable voice assistant. Keep answers concise and natural for speech (usually 1-3 short paragraphs unless asked for more). Avoid markdown formatting since everything you say is spoken aloud.";

/// A voice cloned through the `voice-enrollment` API and cached locally (spec §4).
///
/// `voice_id` is the server primary key; `name` is purely local. `target_model` stays empty when the
/// server has not told us which model the voice is bound to (`list_voice` omits it).
#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(rename_all = "camelCase", default)]
pub struct ClonedVoice {
    pub voice_id: String,
    pub name: String,
    pub target_model: String,
    /// `DEPLOYING` | `OK` | `UNDEPLOYED`
    pub status: String,
    /// epoch ms
    pub created_at: i64,
    /// epoch ms of the last successful local↔server sync
    pub updated_at: i64,
    /// Original file name, display only.
    pub audio_name: Option<String>,
}

impl Default for ClonedVoice {
    fn default() -> Self {
        Self {
            voice_id: String::new(),
            name: String::new(),
            target_model: String::new(),
            status: "DEPLOYING".into(),
            created_at: 0,
            updated_at: 0,
            audio_name: None,
        }
    }
}

/// Every user-tunable option. Mirrors the Android `AppSettings` one-for-one, with the audio route
/// replaced by concrete Windows device ids.
#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(rename_all = "camelCase", default)]
pub struct Settings {
    pub api_key: String,
    pub rt_endpoint: String,
    pub rt_model: String,
    /// "QWEN" | "OPENAI"
    pub flavor: String,
    pub text_endpoint: String,
    pub text_model: String,
    pub voice: String,
    /// Voice-cloning (`voice-enrollment`) endpoint, see spec §2.1.
    pub clone_endpoint: String,
    /// Locally cached cloned voices, see spec §4.
    pub cloned_voices: Vec<ClonedVoice>,
    /// "AUTO" | "PTT"
    pub vad_mode: String,
    pub vad_threshold: f32,
    pub silence_ms: i32,
    pub subtitle_ai: bool,
    pub subtitle_user: bool,
    pub translate_enabled: bool,
    pub translate_lang: String,
    pub asr_lang: String,
    /// Windows input device id ("" = system default)
    pub input_device_id: String,
    /// Windows output device id ("" = system default)
    pub output_device_id: String,
    pub chat_prompt: String,
    pub chat_speak_lang: String,
    pub prac_lang: String,
    pub prac_level: String,
    pub prac_topic: String,
    /// "NEVER" | "SOMETIMES" | "ALWAYS"
    pub prac_correction: String,
    pub prac_report: bool,
    /// "SYSTEM" | "LIGHT" | "DARK"
    pub theme_mode: String,
    /// "SMALL" | "MEDIUM" | "LARGE"
    pub caption_size: String,
    /// "zh" | "en"
    pub language: String,
    pub animations: bool,
    pub show_waveform: bool,
    pub mic_gain: f32,
    pub auto_reconnect: bool,
    pub barge_in: bool,
    pub auto_check_update: bool,
    pub update_proxy: bool,
    pub last_update_check_at: i64,
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            api_key: String::new(),
            rt_endpoint: DEFAULT_RT_ENDPOINT.into(),
            rt_model: DEFAULT_RT_MODEL.into(),
            flavor: "QWEN".into(),
            text_endpoint: DEFAULT_TEXT_ENDPOINT.into(),
            text_model: DEFAULT_TEXT_MODEL.into(),
            voice: "Tina".into(),
            clone_endpoint: DEFAULT_CLONE_ENDPOINT.into(),
            cloned_voices: Vec::new(),
            vad_mode: "AUTO".into(),
            vad_threshold: 0.5,
            silence_ms: 600,
            subtitle_ai: true,
            subtitle_user: true,
            translate_enabled: false,
            translate_lang: "zh".into(),
            asr_lang: String::new(),
            input_device_id: String::new(),
            output_device_id: String::new(),
            chat_prompt: DEFAULT_CHAT_PROMPT.into(),
            chat_speak_lang: "auto".into(),
            prac_lang: "en".into(),
            prac_level: "B1-B2".into(),
            prac_topic: String::new(),
            prac_correction: "SOMETIMES".into(),
            prac_report: true,
            theme_mode: "SYSTEM".into(),
            caption_size: "MEDIUM".into(),
            language: "zh".into(),
            animations: true,
            show_waveform: true,
            mic_gain: 1.0,
            auto_reconnect: true,
            barge_in: true,
            auto_check_update: true,
            update_proxy: true,
            last_update_check_at: 0,
        }
    }
}

/// Spec §5.1: Qwen3.8-Omni 系列官方有效系统音色，顺序即契约顺序（`Tina` 为默认）。
/// Single source of truth for the Windows build: the frontend mock reuses the same order.
pub const VOICE_PRESETS: [&str; 33] = [
    "Tina",
    "Cindy",
    "Liora Mira",
    "Raymond",
    "Zane",
    "Katerina",
    "Ryan",
    "Mia",
    "Cici",
    "Theo Calm",
    "Serena",
    "Maia",
    "Evan",
    "Qiao",
    "Momo",
    "Wil",
    "Angel",
    "Li Cassian",
    "Joyner",
    "Gold",
    "Jennifer",
    "Aiden",
    "Mione",
    "Sunny",
    "Dylan",
    "Eric",
    "Peter",
    "Joseph Chen",
    "Marcus",
    "Li",
    "Rocky",
    "Kiki",
    "Sohee",
];

fn path(app: &tauri::AppHandle) -> PathBuf {
    use tauri::Manager;
    let dir = app
        .path()
        .app_config_dir()
        .unwrap_or_else(|_| PathBuf::from("."));
    let _ = fs::create_dir_all(&dir);
    dir.join("settings.json")
}

pub fn load(app: &tauri::AppHandle) -> Settings {
    let p = path(app);
    match fs::read_to_string(&p) {
        Ok(text) => serde_json::from_str(&text).unwrap_or_default(),
        Err(_) => Settings::default(),
    }
}

pub fn save(app: &tauri::AppHandle, settings: &Settings) -> Result<(), String> {
    let p = path(app);
    let json = serde_json::to_string_pretty(settings).map_err(|e| e.to_string())?;
    // Write to a sibling temp file first so a crash mid-write cannot truncate the settings.
    let tmp = p.with_extension("json.tmp");
    fs::write(&tmp, json).map_err(|e| e.to_string())?;
    fs::rename(&tmp, &p).map_err(|e| e.to_string())?;
    Ok(())
}
