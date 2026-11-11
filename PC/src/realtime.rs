use futures_util::{SinkExt, StreamExt};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Mutex;
use tauri::{AppHandle, Emitter, Manager};
use tokio::sync::mpsc::{unbounded_channel, UnboundedSender};
use tokio_tungstenite::tungstenite::client::IntoClientRequest;
use tokio_tungstenite::tungstenite::http::HeaderValue;
use tokio_tungstenite::tungstenite::Message;

/// Live websocket transport. The protocol itself is parsed in JavaScript, so Rust only has to own
/// the socket: browsers cannot set an `Authorization` header on a WebSocket, which is the whole
/// reason this lives on the Rust side.
#[derive(Default)]
pub struct RtState {
    tx: Mutex<Option<UnboundedSender<String>>>,
    generation: AtomicU64,
    user_closed: AtomicBool,
}

impl RtState {
    pub fn send(&self, text: String) {
        if let Ok(guard) = self.tx.lock() {
            if let Some(tx) = guard.as_ref() {
                let _ = tx.send(text);
            }
        }
    }

    fn close_socket(&self) {
        self.generation.fetch_add(1, Ordering::SeqCst);
        if let Ok(mut guard) = self.tx.lock() {
            *guard = None;
        }
    }

    pub fn close(&self) {
        self.user_closed.store(true, Ordering::SeqCst);
        self.close_socket();
    }
}

fn build_url(endpoint: &str, model: &str) -> String {
    if endpoint.contains("model=") {
        return endpoint.to_string();
    }
    let sep = if endpoint.contains('?') { '&' } else { '?' };
    format!("{endpoint}{sep}model={}", urlencode(model))
}

fn urlencode(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for b in s.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(b as char)
            }
            _ => out.push_str(&format!("%{b:02X}")),
        }
    }
    out
}

pub fn connect(app: AppHandle, endpoint: String, api_key: String, model: String) -> Result<(), String> {
    if endpoint.trim().is_empty() {
        return Err("empty endpoint".into());
    }
    let state = app.state::<RtState>();
    state.user_closed.store(false, Ordering::SeqCst);
    state.close_socket();
    let generation = state.generation.fetch_add(1, Ordering::SeqCst) + 1;

    let (tx, mut rx) = unbounded_channel::<String>();
    if let Ok(mut guard) = state.tx.lock() {
        *guard = Some(tx);
    }

    let url = build_url(&endpoint, &model);
    tauri::async_runtime::spawn(async move {
        let emit_status = |kind: &str, reason: String| {
            let _ = app.emit(
                "rt-status",
                serde_json::json!({ "kind": kind, "reason": reason }),
            );
        };

        let mut request = match url.as_str().into_client_request() {
            Ok(r) => r,
            Err(e) => {
                emit_status("failed", format!("bad endpoint: {e}"));
                return;
            }
        };
        let headers = request.headers_mut();
        let auth = HeaderValue::from_str(&format!("Bearer {api_key}"));
        if let Ok(auth) = auth {
            headers.insert("Authorization", auth);
        }
        headers.insert("User-Agent", HeaderValue::from_static("SpkPrac/1.0.0"));

        let socket = match tokio_tungstenite::connect_async(request).await {
            Ok((ws, _)) => ws,
            Err(e) => {
                emit_status("failed", describe(e));
                return;
            }
        };
        emit_status("open", String::new());

        let (mut write, mut read) = socket.split();

        // Outgoing frames: drain the queue until the socket dies.
        let mut writer = tauri::async_runtime::spawn(async move {
            while let Some(text) = rx.recv().await {
                if write.send(Message::Text(text.into())).await.is_err() {
                    break;
                }
            }
        });

        // Incoming frames: forwarded verbatim so the protocol lives in exactly one place (JS).
        loop {
            tokio::select! {
                _ = &mut writer => break,
                msg = read.next() => match msg {
                    Some(Ok(Message::Text(text))) => {
                        let _ = app.emit("rt-event", text.to_string());
                    }
                    Some(Ok(Message::Binary(bin))) => {
                        if let Ok(text) = String::from_utf8(bin) {
                            let _ = app.emit("rt-event", text);
                        }
                    }
                    Some(Ok(Message::Close(frame))) => {
                        let reason = frame
                            .map(|f| format!("{} (code {})", f.reason, f.code))
                            .unwrap_or_else(|| "closed".into());
                        emit_status("closed", reason);
                        break;
                    }
                    Some(Ok(_)) => {}
                    Some(Err(e)) => {
                        emit_status("failed", describe(e));
                        break;
                    }
                    None => {
                        emit_status("closed", "stream ended".into());
                        break;
                    }
                }
            }
        }

        // Only the newest connection may report state; a stale task must stay silent.
        let state = app.state::<RtState>();
        if state.generation.load(Ordering::SeqCst) == generation {
            state.close_socket();
            if !state.user_closed.load(Ordering::SeqCst) {
                emit_status("closed", String::new());
            }
        }
        writer.abort();
    });

    Ok(())
}

fn describe(e: tokio_tungstenite::tungstenite::Error) -> String {
    // Surface the HTTP status when the handshake was rejected, otherwise the raw message.
    match e {
        tokio_tungstenite::tungstenite::Error::Http(resp) => {
            format!("HTTP {}", resp.status().as_u16())
        }
        other => other.to_string(),
    }
}
