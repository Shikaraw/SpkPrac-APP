//! Voice cloning (`voice-enrollment` HTTP API), spec `docs/voice-clone-spec.md`.
//!
//! The frontend never talks to the network. It hands the local audio file over as base64; this
//! module builds the `data:` URI, posts the JSON request and turns every failure into a structured
//! [`VoiceError`] (server `message`, `code` and `request_id` preserved) so the UI can show it.
//! Network/server problems never panic and never get unwrapped.

use serde::Serialize;
use serde_json::{json, Value};
use std::time::Duration;

/// Spec §2.2: the enrollment model depends on the target model family. The frontend derives it
/// (`rtModel` contains "omni" → `qwen-voice-enrollment`, otherwise `voice-enrollment`) and passes
/// it in; this is only the fallback for callers that leave it empty.
const DEFAULT_ENROLL_MODEL: &str = "voice-enrollment";
const AGENT: &str = concat!("SpkPrac/", env!("CARGO_PKG_VERSION"));
const TIMEOUT_SECS: u64 = 180;
/// Spec §4: one page of 100 voices is enough for the local list.
const PAGE_SIZE: i64 = 100;

// ---------------------------------------------------------------- payload types

/// Structured command error. Tauri serialises this verbatim as the rejected promise value, so the
/// frontend receives `{ code, message, requestId, httpStatus? }` instead of a bare string.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct VoiceError {
    pub code: String,
    pub message: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub request_id: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub http_status: Option<u16>,
}

impl VoiceError {
    fn local(code: &str, message: impl Into<String>) -> Self {
        Self {
            code: code.into(),
            message: message.into(),
            request_id: String::new(),
            http_status: None,
        }
    }

    fn server(code: String, message: String, request_id: String, http_status: Option<u16>) -> Self {
        Self {
            code,
            message,
            request_id,
            http_status,
        }
    }
}

/// One item of `output.voice_list[]` (spec §2.3). The list API deliberately omits `target_model`.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RemoteVoice {
    pub voice_id: String,
    pub status: String,
    pub gmt_create: String,
    pub gmt_modified: String,
}

/// The `output` object of a `query_voice` call.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct VoiceDetail {
    pub voice_id: String,
    pub status: String,
    pub target_model: String,
    pub gmt_create: String,
    pub gmt_modified: String,
    pub resource_link: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CreatedVoice {
    pub voice_id: String,
}

// ---------------------------------------------------------------- HTTP plumbing

fn truncate(s: &str, max: usize) -> String {
    let mut out: String = s.chars().take(max).collect();
    if s.chars().count() > max {
        out.push('…');
    }
    out
}

fn code_to_string(value: &Value) -> String {
    match value {
        Value::String(s) => s.trim().to_string(),
        Value::Null => String::new(),
        other => other.to_string(),
    }
}

fn non_empty(value: Option<&Value>) -> Option<String> {
    value
        .filter(|v| !v.is_null())
        .map(code_to_string)
        .filter(|s| !s.is_empty())
}

/// Turns any non-2xx body (or a 2xx body that carries an error `code`) into a [`VoiceError`].
fn parse_server_error(body: &str, http_status: Option<u16>) -> VoiceError {
    let value: Value = serde_json::from_str(body).unwrap_or(Value::Null);
    let code = non_empty(value.get("code")).unwrap_or_else(|| {
        http_status
            .map(|s| format!("HTTP_{s}"))
            .unwrap_or_else(|| "SERVER".to_string())
    });
    let message = value
        .get("message")
        .and_then(|m| m.as_str())
        .map(|m| m.trim().to_string())
        .filter(|m| !m.is_empty())
        .unwrap_or_else(|| {
            let trimmed = body.trim();
            if !trimmed.is_empty() {
                truncate(trimmed, 300)
            } else if let Some(status) = http_status {
                format!("HTTP {status}")
            } else {
                "unknown server error".to_string()
            }
        });
    let request_id = value
        .get("request_id")
        .and_then(|r| r.as_str())
        .unwrap_or("")
        .to_string();
    VoiceError::server(code, message, request_id, http_status)
}

/// POSTs `{ "model": "<enroll model>", "input": {...} }` and returns the parsed envelope.
fn post_json(
    api_key: &str,
    endpoint: &str,
    model: &str,
    input: Value,
) -> Result<Value, VoiceError> {
    let api_key = api_key.trim();
    if api_key.is_empty() {
        return Err(VoiceError::local("NO_KEY", "API key is missing"));
    }
    let endpoint = endpoint.trim();
    if endpoint.is_empty() {
        return Err(VoiceError::local("NO_ENDPOINT", "cloning endpoint is missing"));
    }
    let model = model.trim();
    let model = if model.is_empty() {
        DEFAULT_ENROLL_MODEL
    } else {
        model
    };

    let payload = json!({ "model": model, "input": input });
    let response = ureq::post(endpoint)
        .set("Authorization", &format!("Bearer {api_key}"))
        .set("Content-Type", "application/json")
        .set("Accept", "application/json")
        .set("User-Agent", AGENT)
        .timeout(Duration::from_secs(TIMEOUT_SECS))
        .send_json(payload);

    let body = match response {
        Ok(resp) => resp
            .into_string()
            .map_err(|e| VoiceError::local("READ", format!("failed to read response: {e}")))?,
        Err(ureq::Error::Status(status, resp)) => {
            let body = resp.into_string().unwrap_or_default();
            return Err(parse_server_error(&body, Some(status)));
        }
        Err(ureq::Error::Transport(e)) => {
            return Err(VoiceError::local(
                "NETWORK",
                format!("network error: {e}"),
            ));
        }
    };

    let value: Value = serde_json::from_str(&body)
        .map_err(|e| VoiceError::local("PARSE", format!("invalid response: {e}")))?;

    // Some gateways answer 200 with `{ "code": "...", "message": "..." }` on failure.
    if let Some(code) = non_empty(value.get("code")) {
        let message = value
            .get("message")
            .and_then(|m| m.as_str())
            .map(|m| m.trim().to_string())
            .filter(|m| !m.is_empty())
            .unwrap_or_else(|| truncate(body.trim(), 300));
        let request_id = value
            .get("request_id")
            .and_then(|r| r.as_str())
            .unwrap_or("")
            .to_string();
        return Err(VoiceError::server(code, message, request_id, None));
    }

    Ok(value)
}

fn output_object(reply: &Value) -> Option<&serde_json::Map<String, Value>> {
    reply.get("output").and_then(|o| o.as_object())
}

fn output_str(reply: &Value, key: &str) -> String {
    reply
        .pointer(&format!("/output/{key}"))
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string()
}

// ---------------------------------------------------------------- audio helpers

fn normalize_mime(raw: &str) -> &'static str {
    match raw.trim().to_ascii_lowercase().as_str() {
        "audio/mpeg" | "audio/mp3" | "audio/x-mp3" => "audio/mpeg",
        "audio/mp4" | "audio/m4a" | "audio/x-m4a" => "audio/mp4",
        "audio/aac" | "audio/aacp" | "audio/x-aac" => "audio/aac",
        _ => "audio/wav",
    }
}

/// Wraps the base64 payload into the Data URI form the API accepts (spec §2.3). A malformed
/// payload is rejected locally so the request never reaches the server.
fn data_uri(audio_base64: &str, audio_mime: &str) -> Result<String, VoiceError> {
    let b64 = audio_base64.trim();
    if b64.is_empty() {
        return Err(VoiceError::local("AUDIO", "audio data is empty"));
    }
    if !b64
        .bytes()
        .all(|b| b.is_ascii_alphanumeric() || b == b'+' || b == b'/' || b == b'=')
    {
        return Err(VoiceError::local("AUDIO", "audio data is not valid base64"));
    }
    let mut uri = String::with_capacity("data:audio/wav;base64,".len() + b64.len());
    uri.push_str("data:");
    uri.push_str(normalize_mime(audio_mime));
    uri.push_str(";base64,");
    uri.push_str(b64);
    Ok(uri)
}

/// Spec §5.3: keep `[A-Za-z0-9]`, lowercase, at most 10 characters, fall back to `voice`.
fn sanitize_prefix(raw: &str) -> String {
    let mut out: String = raw
        .chars()
        .filter(|c| c.is_ascii_alphanumeric())
        .take(10)
        .collect();
    out.make_ascii_lowercase();
    if out.is_empty() {
        out.push_str("voice");
    }
    out
}

fn require_voice_id(voice_id: &str) -> Result<String, VoiceError> {
    let id = voice_id.trim();
    if id.is_empty() {
        return Err(VoiceError::local("VOICE_ID", "voice_id is missing"));
    }
    Ok(id.to_string())
}

// ---------------------------------------------------------------- blocking operations

fn list_voices(
    api_key: &str,
    endpoint: &str,
    model: &str,
) -> Result<Vec<RemoteVoice>, VoiceError> {
    let reply = post_json(
        api_key,
        endpoint,
        model,
        json!({
            "action": "list_voice",
            "page_index": 0,
            "page_size": PAGE_SIZE,
        }),
    )?;
    let items = reply
        .pointer("/output/voice_list")
        .and_then(|v| v.as_array())
        .cloned()
        .unwrap_or_default();
    Ok(items
        .iter()
        .filter_map(|item| {
            let voice_id = item
                .get("voice_id")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .trim()
                .to_string();
            if voice_id.is_empty() {
                return None;
            }
            Some(RemoteVoice {
                voice_id,
                status: item
                    .get("status")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .to_string(),
                gmt_create: item
                    .get("gmt_create")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .to_string(),
                gmt_modified: item
                    .get("gmt_modified")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .to_string(),
            })
        })
        .collect())
}

fn create_voice(
    api_key: &str,
    endpoint: &str,
    model: &str,
    target_model: &str,
    prefix: &str,
    audio: String,
    enable_preprocess: Option<bool>,
) -> Result<CreatedVoice, VoiceError> {
    let target_model = target_model.trim();
    if target_model.is_empty() {
        return Err(VoiceError::local(
            "TARGET_MODEL",
            "target model is missing; configure the realtime model first",
        ));
    }
    let mut input = json!({
        "action": "create_voice",
        "target_model": target_model,
        "prefix": sanitize_prefix(prefix),
        "audio": audio,
    });
    if let (Some(obj), Some(enabled)) = (input.as_object_mut(), enable_preprocess) {
        obj.insert("enable_preprocess".to_string(), Value::Bool(enabled));
    }
    let reply = post_json(api_key, endpoint, model, input)?;
    let voice_id = output_str(&reply, "voice_id");
    if voice_id.trim().is_empty() {
        return Err(VoiceError::local(
            "NO_VOICE_ID",
            "the server did not return a voice_id",
        ));
    }
    Ok(CreatedVoice { voice_id })
}

fn query_voice(
    api_key: &str,
    endpoint: &str,
    model: &str,
    voice_id: &str,
) -> Result<VoiceDetail, VoiceError> {
    let voice_id = require_voice_id(voice_id)?;
    let reply = post_json(
        api_key,
        endpoint,
        model,
        json!({ "action": "query_voice", "voice_id": voice_id }),
    )?;
    if output_object(&reply).is_none() {
        return Err(VoiceError::local(
            "NO_OUTPUT",
            "the server returned no voice details",
        ));
    }
    Ok(VoiceDetail {
        voice_id,
        status: output_str(&reply, "status"),
        target_model: output_str(&reply, "target_model"),
        gmt_create: output_str(&reply, "gmt_create"),
        gmt_modified: output_str(&reply, "gmt_modified"),
        resource_link: output_str(&reply, "resource_link"),
    })
}

fn update_voice(
    api_key: &str,
    endpoint: &str,
    model: &str,
    voice_id: &str,
    audio: String,
) -> Result<(), VoiceError> {
    let voice_id = require_voice_id(voice_id)?;
    post_json(
        api_key,
        endpoint,
        model,
        json!({ "action": "update_voice", "voice_id": voice_id, "audio": audio }),
    )?;
    Ok(())
}

fn delete_voice(
    api_key: &str,
    endpoint: &str,
    model: &str,
    voice_id: &str,
) -> Result<(), VoiceError> {
    let voice_id = require_voice_id(voice_id)?;
    post_json(
        api_key,
        endpoint,
        model,
        json!({ "action": "delete_voice", "voice_id": voice_id }),
    )?;
    Ok(())
}

// ---------------------------------------------------------------- Tauri commands

#[tauri::command]
pub async fn voice_list(
    api_key: String,
    endpoint: String,
    model: String,
) -> Result<Vec<RemoteVoice>, VoiceError> {
    tauri::async_runtime::spawn_blocking(move || list_voices(&api_key, &endpoint, &model))
        .await
        .map_err(|e| VoiceError::local("INTERNAL", e.to_string()))?
}

#[tauri::command]
pub async fn voice_create(
    api_key: String,
    endpoint: String,
    model: String,
    target_model: String,
    prefix: String,
    audio_base64: String,
    audio_mime: String,
    enable_preprocess: Option<bool>,
) -> Result<CreatedVoice, VoiceError> {
    tauri::async_runtime::spawn_blocking(move || {
        let audio = data_uri(&audio_base64, &audio_mime)?;
        create_voice(
            &api_key,
            &endpoint,
            &model,
            &target_model,
            &prefix,
            audio,
            enable_preprocess,
        )
    })
    .await
    .map_err(|e| VoiceError::local("INTERNAL", e.to_string()))?
}

#[tauri::command]
pub async fn voice_query(
    api_key: String,
    endpoint: String,
    model: String,
    voice_id: String,
) -> Result<VoiceDetail, VoiceError> {
    tauri::async_runtime::spawn_blocking(move || {
        query_voice(&api_key, &endpoint, &model, &voice_id)
    })
    .await
    .map_err(|e| VoiceError::local("INTERNAL", e.to_string()))?
}

#[tauri::command]
pub async fn voice_update(
    api_key: String,
    endpoint: String,
    model: String,
    voice_id: String,
    audio_base64: String,
    audio_mime: String,
) -> Result<(), VoiceError> {
    tauri::async_runtime::spawn_blocking(move || {
        let audio = data_uri(&audio_base64, &audio_mime)?;
        update_voice(&api_key, &endpoint, &model, &voice_id, audio)
    })
    .await
    .map_err(|e| VoiceError::local("INTERNAL", e.to_string()))?
}

#[tauri::command]
pub async fn voice_delete(
    api_key: String,
    endpoint: String,
    model: String,
    voice_id: String,
) -> Result<(), VoiceError> {
    tauri::async_runtime::spawn_blocking(move || {
        delete_voice(&api_key, &endpoint, &model, &voice_id)
    })
    .await
    .map_err(|e| VoiceError::local("INTERNAL", e.to_string()))?
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn prefix_keeps_alnum_and_trims_to_ten() {
        assert_eq!(sanitize_prefix("My Cool Voice!"), "mycoolvoic");
        assert_eq!(sanitize_prefix("  中文  "), "voice");
        assert_eq!(sanitize_prefix(""), "voice");
        assert_eq!(sanitize_prefix("ABCdef123456"), "abcdef1234");
    }

    #[test]
    fn data_uri_rejects_garbage() {
        assert!(data_uri("", "audio/wav").is_err());
        assert!(data_uri("not base64!", "audio/wav").is_err());
        assert_eq!(
            data_uri("QUJD", "audio/mpeg").unwrap(),
            "data:audio/mpeg;base64,QUJD"
        );
        assert_eq!(
            data_uri("QUJD", "application/octet-stream").unwrap(),
            "data:audio/wav;base64,QUJD"
        );
    }

    #[test]
    fn server_error_keeps_code_and_request_id() {
        let err = parse_server_error(
            r#"{"code":"InvalidParameter","message":"bad audio","request_id":"req-1"}"#,
            Some(400),
        );
        assert_eq!(err.code, "InvalidParameter");
        assert_eq!(err.message, "bad audio");
        assert_eq!(err.request_id, "req-1");
        assert_eq!(err.http_status, Some(400));
    }

    #[test]
    fn server_error_falls_back_to_body() {
        let err = parse_server_error("gateway timeout", Some(504));
        assert_eq!(err.code, "HTTP_504");
        assert_eq!(err.message, "gateway timeout");
    }
}
