#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod net;
mod realtime;
mod sessions;
mod settings;

use serde::Serialize;
use tauri::{AppHandle, Emitter, Manager};
use realtime::RtState;

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct AppInfo {
    version: String,
    data_dir: String,
    sessions_dir: String,
    platform: String,
}

#[tauri::command]
fn app_info(app: AppHandle) -> AppInfo {
    let data_dir = app
        .path()
        .app_data_dir()
        .map(|p| p.display().to_string())
        .unwrap_or_default();
    AppInfo {
        version: app.package_info().version.to_string(),
        sessions_dir: format!("{data_dir}\\sessions"),
        data_dir,
        platform: "windows".into(),
    }
}

#[tauri::command]
fn settings_get(app: AppHandle) -> settings::Settings {
    settings::load(&app)
}

#[tauri::command]
fn settings_save(app: AppHandle, settings: settings::Settings) -> Result<(), String> {
    settings::save(&app, &settings)
}

#[tauri::command]
fn settings_voices() -> Vec<String> {
    settings::VOICE_PRESETS.iter().map(|v| v.to_string()).collect()
}

// ---------------------------------------------------------------- sessions

#[tauri::command]
fn sessions_list(app: AppHandle) -> Vec<sessions::SessionMeta> {
    sessions::list(&app)
}

#[tauri::command]
fn sessions_load(app: AppHandle, id: String) -> Option<sessions::StoredSession> {
    sessions::load(&app, &id)
}

#[tauri::command]
fn sessions_save(app: AppHandle, session: sessions::StoredSession) -> Result<(), String> {
    sessions::save(&app, &session)
}

#[tauri::command]
fn sessions_delete(app: AppHandle, id: String) -> Result<(), String> {
    sessions::delete(&app, &id)
}

#[tauri::command]
fn sessions_delete_all(app: AppHandle) -> Result<(), String> {
    sessions::delete_all(&app)
}

#[tauri::command]
fn sessions_stats(app: AppHandle) -> sessions::HistoryStats {
    sessions::stats(&app)
}

#[tauri::command]
fn session_export_text(app: AppHandle, id: String) -> Result<String, String> {
    let session = sessions::load(&app, &id).ok_or("session not found")?;
    Ok(sessions::export_text(&session))
}

#[tauri::command]
fn sessions_export_all(app: AppHandle) -> String {
    sessions::export_all(&app)
}

/// Writes a text file into `Documents\SpkPrac` and returns its absolute path.
#[tauri::command]
fn write_export(app: AppHandle, file_name: String, content: String) -> Result<String, String> {
    let docs = app
        .path()
        .document_dir()
        .or_else(|_| app.path().download_dir())
        .map_err(|e| e.to_string())?;
    let dir = docs.join("SpkPrac");
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    let safe: String = file_name
        .chars()
        .map(|c| if r#"\/:*?"<>|"#.contains(c) { '_' } else { c })
        .collect();
    let path = dir.join(safe);
    std::fs::write(&path, content).map_err(|e| e.to_string())?;
    Ok(path.display().to_string())
}

#[tauri::command]
fn open_external(url: String) -> Result<(), String> {
    if !(url.starts_with("https://") || url.starts_with("http://")) {
        return Err("unsupported url".into());
    }
    std::process::Command::new("rundll32")
        .args(["url.dll,FileProtocolHandler", &url])
        .spawn()
        .map_err(|e| e.to_string())?;
    Ok(())
}

#[tauri::command]
fn reveal_path(path: String) -> Result<(), String> {
    std::process::Command::new("explorer")
        .arg(format!("/select,{path}"))
        .spawn()
        .map_err(|e| e.to_string())?;
    Ok(())
}

#[tauri::command]
fn open_dir(path: String) -> Result<(), String> {
    std::process::Command::new("explorer")
        .arg(path)
        .spawn()
        .map_err(|e| e.to_string())?;
    Ok(())
}

// ---------------------------------------------------------------- realtime transport

#[tauri::command]
async fn rt_connect(
    app: AppHandle,
    endpoint: String,
    api_key: String,
    model: String,
) -> Result<(), String> {
    realtime::connect(app, endpoint, api_key, model)
}

#[tauri::command]
fn rt_send(app: AppHandle, text: String) {
    app.state::<RtState>().send(text);
}

#[tauri::command]
fn rt_close(app: AppHandle) {
    app.state::<RtState>().close();
}

// ---------------------------------------------------------------- text model

#[tauri::command]
async fn text_complete(
    endpoint: String,
    api_key: String,
    model: String,
    messages: Vec<(String, String)>,
    temperature: f64,
    max_tokens: i32,
) -> Result<String, String> {
    tauri::async_runtime::spawn_blocking(move || {
        net::complete(&endpoint, &api_key, &model, messages, temperature, max_tokens)
    })
    .await
    .map_err(|e| e.to_string())?
}

// ---------------------------------------------------------------- updates

#[tauri::command]
async fn check_update(app: AppHandle, proxy: bool) -> Result<net::UpdateCheck, String> {
    let current = app.package_info().version.to_string();
    tauri::async_runtime::spawn_blocking(move || net::check_update(&current, proxy))
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
async fn download_update(
    app: AppHandle,
    url: String,
    file_name: String,
    proxy: bool,
    total: Option<i64>,
) -> Result<String, String> {
    let dir = app
        .path()
        .download_dir()
        .map_err(|e| e.to_string())?
        .join("SpkPrac");
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    let path = dir.join(file_name);
    let target = net::accelerated(&url, proxy);

    let handle = app.clone();
    tauri::async_runtime::spawn_blocking(move || {
        net::download(&target, &path, total, |done, total| {
            let _ = handle.emit(
                "update-progress",
                serde_json::json!({ "done": done, "total": total }),
            );
        })
        .map(|_| path.display().to_string())
    })
    .await
    .map_err(|e| e.to_string())?
}

fn main() {
    tauri::Builder::default()
        .manage(RtState::default())
        .invoke_handler(tauri::generate_handler![
            app_info,
            settings_get,
            settings_save,
            settings_voices,
            sessions_list,
            sessions_load,
            sessions_save,
            sessions_delete,
            sessions_delete_all,
            sessions_stats,
            session_export_text,
            sessions_export_all,
            write_export,
            open_external,
            reveal_path,
            open_dir,
            rt_connect,
            rt_send,
            rt_close,
            text_complete,
            check_update,
            download_update,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
