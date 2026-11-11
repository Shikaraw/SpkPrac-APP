use serde::{Deserialize, Serialize};
use std::collections::HashSet;
use std::fs;
use std::path::PathBuf;

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct ChatMessage {
    pub id: String,
    /// "USER" | "ASSISTANT" | "SYSTEM"
    pub role: String,
    pub text: String,
    pub ts: i64,
    #[serde(default)]
    pub translation: Option<String>,
    #[serde(default = "normal_kind")]
    pub kind: String,
}

fn normal_kind() -> String {
    "normal".into()
}

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct SessionMeta {
    pub id: String,
    /// "CHAT" | "PRACTICE"
    pub mode: String,
    pub title: String,
    #[serde(default)]
    pub language: String,
    pub created_at: i64,
    #[serde(default)]
    pub duration_ms: i64,
    #[serde(default)]
    pub message_count: i32,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct PracticeReport {
    pub content: String,
    pub generated_at: i64,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct StoredSession {
    pub meta: SessionMeta,
    #[serde(default)]
    pub messages: Vec<ChatMessage>,
    #[serde(default)]
    pub report: Option<PracticeReport>,
}

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
#[serde(rename_all = "camelCase")]
pub struct HistoryStats {
    pub sessions: i32,
    pub total_ms: i64,
    pub streak_days: i32,
    pub practice_sessions: i32,
}

fn dir(app: &tauri::AppHandle) -> PathBuf {
    use tauri::Manager;
    let base = app
        .path()
        .app_data_dir()
        .unwrap_or_else(|_| PathBuf::from("."));
    let d = base.join("sessions");
    let _ = fs::create_dir_all(&d);
    d
}

pub fn list(app: &tauri::AppHandle) -> Vec<SessionMeta> {
    let mut metas: Vec<SessionMeta> = fs::read_dir(dir(app))
        .map(|entries| {
            entries
                .flatten()
                .filter(|e| e.path().extension().map(|x| x == "json").unwrap_or(false))
                .filter_map(|e| {
                    let text = fs::read_to_string(e.path()).ok()?;
                    let stored: StoredSession = serde_json::from_str(&text).ok()?;
                    Some(stored.meta)
                })
                .collect()
        })
        .unwrap_or_default();
    metas.sort_by(|a, b| b.created_at.cmp(&a.created_at));
    metas
}

pub fn load(app: &tauri::AppHandle, id: &str) -> Option<StoredSession> {
    let p = dir(app).join(format!("{id}.json"));
    let text = fs::read_to_string(p).ok()?;
    serde_json::from_str(&text).ok()
}

pub fn save(app: &tauri::AppHandle, session: &StoredSession) -> Result<(), String> {
    let p = dir(app).join(format!("{}.json", session.meta.id));
    let json = serde_json::to_string(session).map_err(|e| e.to_string())?;
    let tmp = p.with_extension("json.tmp");
    fs::write(&tmp, json).map_err(|e| e.to_string())?;
    fs::rename(&tmp, &p).map_err(|e| e.to_string())?;
    Ok(())
}

pub fn delete(app: &tauri::AppHandle, id: &str) -> Result<(), String> {
    let p = dir(app).join(format!("{id}.json"));
    match fs::remove_file(p) {
        Ok(_) => Ok(()),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(e) => Err(e.to_string()),
    }
}

pub fn delete_all(app: &tauri::AppHandle) -> Result<(), String> {
    if let Ok(entries) = fs::read_dir(dir(app)) {
        for entry in entries.flatten() {
            let _ = fs::remove_file(entry.path());
        }
    }
    Ok(())
}

/// Day index (yyyy*1000 + day-of-year) used for the practice streak.
fn day_key(ts: i64) -> i64 {
    use chrono::{Datelike, Local, TimeZone};
    match Local.timestamp_millis_opt(ts).single() {
        Some(dt) => dt.year() as i64 * 1000 + dt.ordinal() as i64,
        None => 0,
    }
}

pub fn stats(app: &tauri::AppHandle) -> HistoryStats {
    use chrono::{Datelike, Duration, Local};
    let metas = list(app);
    if metas.is_empty() {
        return HistoryStats::default();
    }
    let days: HashSet<i64> = metas.iter().map(|m| day_key(m.created_at)).collect();
    let mut streak = 0;
    let mut cursor = Local::now();
    loop {
        let key = cursor.year() as i64 * 1000 + cursor.ordinal() as i64;
        if days.contains(&key) {
            streak += 1;
            cursor -= Duration::days(1);
        } else {
            break;
        }
    }
    HistoryStats {
        sessions: metas.len() as i32,
        total_ms: metas.iter().map(|m| m.duration_ms).sum(),
        streak_days: streak,
        practice_sessions: metas.iter().filter(|m| m.mode == "PRACTICE").count() as i32,
    }
}

pub fn format_duration(ms: i64) -> String {
    let total = (ms / 1000).max(0);
    let (h, m, s) = (total / 3600, (total % 3600) / 60, total % 60);
    if h > 0 {
        format!("{h}:{m:02}:{s:02}")
    } else {
        format!("{m}:{s:02}")
    }
}

pub fn format_date(ts: i64) -> String {
    use chrono::{Local, TimeZone};
    Local
        .timestamp_millis_opt(ts)
        .single()
        .map(|dt| dt.format("%Y-%m-%d %H:%M").to_string())
        .unwrap_or_default()
}

/// Human-readable transcript of one session (Markdown flavoured).
pub fn export_text(s: &StoredSession) -> String {
    let mut out = String::new();
    out.push_str(&format!("# {}\n\n", s.meta.title));
    out.push_str(&format!(
        "{}  ·  {}  ·  {} messages\n\n",
        format_date(s.meta.created_at),
        format_duration(s.meta.duration_ms),
        s.meta.message_count
    ));
    if let Some(report) = &s.report {
        out.push_str("## 练习报告\n\n");
        out.push_str(&report.content);
        out.push_str("\n\n");
    }
    out.push_str("## 对话记录\n\n");
    for m in &s.messages {
        match m.role.as_str() {
            "USER" => {
                out.push_str(&format!("**学习者**: {}\n\n", m.text));
            }
            "ASSISTANT" => {
                out.push_str(&format!("**教练**: {}\n\n", m.text));
            }
            _ => {
                out.push_str(&format!("_{}_\n\n", m.text));
            }
        }
        if let Some(tr) = &m.translation {
            out.push_str(&format!("> 翻译: {}\n\n", tr));
        }
    }
    out
}

pub fn export_all(app: &tauri::AppHandle) -> String {
    let mut out = String::new();
    out.push_str("# SpkPrac 会话导出\n\n");
    out.push_str(&format!("导出时间：{}\n\n", format_date(now_ms())));
    for meta in list(app) {
        if let Some(s) = load(app, &meta.id) {
            out.push_str("---\n\n");
            out.push_str(&export_text(&s));
            out.push('\n');
        }
    }
    out
}

pub fn now_ms() -> i64 {
    use std::time::{SystemTime, UNIX_EPOCH};
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}
