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

/// One content-search hit: `snippet` is already truncated with `…` context (no markup).
#[derive(Serialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct SearchHit {
    pub id: String,
    pub snippet: String,
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

// ---------------------------------------------------------------- content search (spec §3.1)

/// Snippet budget in characters. Two of these are reserved for the `…` markers so the whole
/// snippet stays ≤ 80 characters (contract: 片段 ≤ 80 字，前后带上下文).
const SNIPPET_CONTENT_CHARS: usize = 78;
const SNIPPET_LEAD_CHARS: usize = 30;

/// Builds a one-line context snippet around the first case-insensitive match of `needle_lower`.
/// Returns `None` when the message does not contain the query.
fn make_snippet(text: &str, needle_lower: &str) -> Option<String> {
    if needle_lower.is_empty() || text.is_empty() {
        return None;
    }
    let chars: Vec<char> = text.chars().collect();
    let lowered = text.to_lowercase();
    let byte_pos = lowered.find(needle_lower)?;
    // Map a byte offset in the lowercased copy back to a char index. Case folding rarely changes
    // the character count, but clamp so a surprise cannot panic.
    let mut hit = lowered[..byte_pos]
        .chars()
        .count()
        .min(chars.len().saturating_sub(1));
    let hit_len = needle_lower.chars().count();
    let hit_end = (hit + hit_len).min(chars.len());
    if hit >= chars.len() {
        hit = chars.len() - 1;
    }

    let mut start = hit.saturating_sub(SNIPPET_LEAD_CHARS);
    let mut end = (start + SNIPPET_CONTENT_CHARS).min(chars.len());
    // Make sure the match itself always fits, even when it is longer than the lead window.
    if end < hit_end {
        end = hit_end.min(chars.len());
        start = end.saturating_sub(SNIPPET_CONTENT_CHARS);
    }
    let body: String = chars[start..end].iter().collect();
    let body = body.split_whitespace().collect::<Vec<_>>().join(" ");
    let body = body.trim().chars().take(SNIPPET_CONTENT_CHARS).collect::<String>();
    if body.is_empty() {
        return None;
    }
    let mut snippet = String::with_capacity(body.len() + 2);
    if start > 0 {
        snippet.push('…');
    }
    snippet.push_str(&body);
    if end < chars.len() {
        snippet.push('…');
    }
    Some(snippet)
}

/// Case-insensitive full-text search over every stored session's messages. A corrupt session file
/// is skipped instead of failing the whole search (spec §3.1). Newest sessions come first.
pub fn search(app: &tauri::AppHandle, query: &str) -> Vec<SearchHit> {
    let needle = query.trim().to_lowercase();
    if needle.is_empty() {
        return Vec::new();
    }
    let mut hits: Vec<(i64, SearchHit)> = Vec::new();
    let entries = match fs::read_dir(dir(app)) {
        Ok(entries) => entries,
        Err(_) => return Vec::new(),
    };
    for entry in entries.flatten() {
        let path = entry.path();
        if path.extension().map(|ext| ext != "json").unwrap_or(true) {
            continue;
        }
        let text = match fs::read_to_string(&path) {
            Ok(text) => text,
            Err(_) => continue,
        };
        let stored: StoredSession = match serde_json::from_str(&text) {
            Ok(stored) => stored,
            Err(_) => continue,
        };
        let snippet = stored
            .messages
            .iter()
            .find_map(|message| make_snippet(&message.text, &needle));
        if let Some(snippet) = snippet {
            hits.push((
                stored.meta.created_at,
                SearchHit {
                    id: stored.meta.id,
                    snippet,
                },
            ));
        }
    }
    hits.sort_by(|a, b| b.0.cmp(&a.0));
    hits.into_iter().map(|(_, hit)| hit).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn snippet_is_full_text_when_short() {
        let snippet = make_snippet("I want a coffee", "coffee").unwrap();
        assert_eq!(snippet, "I want a coffee");
    }

    #[test]
    fn snippet_is_case_insensitive_and_bounded() {
        let text = format!(
            "{} I LOVE Mango smoothies {}",
            "word ".repeat(40),
            "tail ".repeat(40)
        );
        let snippet = make_snippet(&text, "mango").unwrap();
        assert!(snippet.contains("Mango"), "keeps the original casing: {snippet}");
        assert!(snippet.starts_with('…'), "leading context marker: {snippet}");
        assert!(snippet.ends_with('…'), "trailing context marker: {snippet}");
        assert!(
            snippet.chars().count() <= 80,
            "snippet must stay ≤ 80 chars, got {}",
            snippet.chars().count()
        );
    }

    #[test]
    fn snippet_handles_cjk() {
        let text = format!("{}这是一段中文字幕内容，用来验证片段截断。{}", "前".repeat(50), "后".repeat(50));
        let snippet = make_snippet(&text, "中文字幕").unwrap();
        assert!(snippet.contains("中文字幕"));
        assert!(snippet.chars().count() <= 80);
    }

    #[test]
    fn snippet_returns_none_without_match() {
        assert!(make_snippet("hello", "world").is_none());
        assert!(make_snippet("hello", "").is_none());
    }
}
