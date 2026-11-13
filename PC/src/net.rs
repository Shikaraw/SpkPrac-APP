use serde::Serialize;
use serde_json::json;

pub const OWNER: &str = "Shikaraw";
pub const REPO: &str = "SpkPrac-APP";
pub const RELEASES_PAGE: &str = "https://github.com/Shikaraw/SpkPrac-APP/releases";
const API_DIRECT: &str = "https://api.github.com";
const API_MIRROR: &str = "https://gh-proxy.com/https://api.github.com";
const ASSET_MIRROR: &str = "https://gh-proxy.com/";

const AGENT: &str = "SpkPrac/1.2.0";

/// Release tags that belong to the Windows build, e.g. `pc-v1.2.0`.
pub const PC_TAG_PREFIX: &str = "pc-v";

// ---------------------------------------------------------------- text completions

pub fn complete(
    endpoint: &str,
    api_key: &str,
    model: &str,
    messages: Vec<(String, String)>,
    temperature: f64,
    max_tokens: i32,
) -> Result<String, String> {
    let payload = json!({
        "model": model,
        "messages": messages
            .iter()
            .map(|(role, content)| json!({ "role": role, "content": content }))
            .collect::<Vec<_>>(),
        "temperature": temperature,
        "max_tokens": max_tokens
    });

    let response = ureq::post(endpoint)
        .set("Authorization", &format!("Bearer {api_key}"))
        .set("Content-Type", "application/json")
        .set("User-Agent", AGENT)
        .timeout(std::time::Duration::from_secs(120))
        .send_json(payload);

    let value: serde_json::Value = match response {
        Ok(resp) => resp
            .into_json()
            .map_err(|e| format!("invalid response: {e}"))?,
        Err(ureq::Error::Status(code, resp)) => {
            let body = resp.into_string().unwrap_or_default();
            return Err(format!("HTTP {code}: {}", truncate(&body, 300)));
        }
        Err(e) => return Err(e.to_string()),
    };

    value["choices"]
        .get(0)
        .and_then(|c| c.get("message"))
        .and_then(|m| m.get("content"))
        .and_then(|c| c.as_str())
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
        .ok_or_else(|| "empty response from model".to_string())
}

fn truncate(s: &str, max: usize) -> String {
    let mut out: String = s.chars().take(max).collect();
    if s.chars().count() > max {
        out.push('…');
    }
    out
}

// ---------------------------------------------------------------- version comparison

/// Dotted numeric comparison: "1.2.0" vs "1.10.0". Positive when `a` is newer.
pub fn compare_versions(a: &str, b: &str) -> i32 {
    let parse = |v: &str| -> Vec<i64> {
        v.trim_start_matches('v')
            .split(['.', '-', '+'])
            .map(|part| {
                let digits: String = part.chars().take_while(|c| c.is_ascii_digit()).collect();
                digits.parse::<i64>().unwrap_or(0)
            })
            .collect()
    };
    let left = parse(a);
    let right = parse(b);
    let len = left.len().max(right.len());
    for i in 0..len {
        let l = left.get(i).copied().unwrap_or(0);
        let r = right.get(i).copied().unwrap_or(0);
        if l != r {
            return if l > r { 1 } else { -1 };
        }
    }
    0
}

// ---------------------------------------------------------------- update check

#[derive(Serialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct UpdateInfo {
    pub version: String,
    pub tag: String,
    pub notes: String,
    pub release_url: String,
    pub asset_url: Option<String>,
    pub asset_name: Option<String>,
    pub asset_size: Option<i64>,
}

#[derive(Serialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct UpdateCheck {
    /// "available" | "uptodate" | "failed"
    pub kind: String,
    pub info: Option<UpdateInfo>,
    pub message: Option<String>,
}

impl UpdateCheck {
    fn up_to_date() -> Self {
        Self {
            kind: "uptodate".into(),
            info: None,
            message: None,
        }
    }
    fn failed(msg: impl Into<String>) -> Self {
        Self {
            kind: "failed".into(),
            info: None,
            message: Some(msg.into()),
        }
    }
}

pub fn check_update(current_version: &str, prefer_proxy: bool) -> UpdateCheck {
    let bases: [&str; 2] = if prefer_proxy {
        [API_MIRROR, API_DIRECT]
    } else {
        [API_DIRECT, API_MIRROR]
    };
    let mut last_error = String::from("network unreachable");
    for base in bases {
        // The repository also hosts the Android app, so only tags prefixed with `pc-v` belong to
        // this build. `/releases/latest` would happily offer an APK as a Windows update.
        let url = format!("{base}/repos/{OWNER}/{REPO}/releases?per_page=30");
        match ureq::get(&url)
            .set("Accept", "application/vnd.github+json")
            .set("User-Agent", AGENT)
            .timeout(std::time::Duration::from_secs(12))
            .call()
        {
            Ok(resp) => {
                let list: serde_json::Value = match resp.into_json() {
                    Ok(v) => v,
                    Err(e) => {
                        last_error = format!("invalid json: {e}");
                        continue;
                    }
                };
                let releases = match list.as_array() {
                    Some(arr) => arr,
                    None => {
                        last_error = "unexpected response".into();
                        continue;
                    }
                };
                let ours = releases.iter().find(|r| {
                    let tag = r["tag_name"].as_str().unwrap_or("");
                    let draft = r["draft"].as_bool().unwrap_or(false);
                    !draft && tag.to_ascii_lowercase().starts_with(PC_TAG_PREFIX)
                });
                return match ours {
                    Some(release) => parse_release(release, current_version),
                    None => UpdateCheck::up_to_date(),
                };
            }
            // No releases at all yet is not an error.
            Err(ureq::Error::Status(404, _)) => return UpdateCheck::up_to_date(),
            Err(ureq::Error::Status(code, _)) => last_error = format!("HTTP {code}"),
            Err(e) => last_error = e.to_string(),
        }
    }
    UpdateCheck::failed(last_error)
}

fn parse_release(root: &serde_json::Value, current_version: &str) -> UpdateCheck {
    let draft = root["draft"].as_bool().unwrap_or(false);
    if draft {
        return UpdateCheck::up_to_date();
    }
    let tag = root["tag_name"].as_str().unwrap_or("").to_string();
    let version = tag
        .to_ascii_lowercase()
        .trim_start_matches(PC_TAG_PREFIX)
        .to_string();
    if version.is_empty() {
        return UpdateCheck::up_to_date();
    }

    let notes = root["body"].as_str().unwrap_or("").to_string();
    let release_url = root["html_url"]
        .as_str()
        .filter(|s| !s.is_empty())
        .unwrap_or(RELEASES_PAGE)
        .to_string();

    // Prefer a Windows build, but fall back to whatever single binary the release carries.
    let assets = root["assets"].as_array().cloned().unwrap_or_default();
    let pick = assets
        .iter()
        .find(|a| {
            a["name"]
                .as_str()
                .map(|n| n.to_ascii_lowercase().ends_with(".exe"))
                .unwrap_or(false)
        })
        .or_else(|| {
            assets.iter().find(|a| {
                a["name"]
                    .as_str()
                    .map(|n| n.to_ascii_lowercase().ends_with(".zip"))
                    .unwrap_or(false)
            })
        });

    let asset_url = pick
        .and_then(|a| a["browser_download_url"].as_str())
        .map(|s| s.to_string());
    let asset_name = pick
        .and_then(|a| a["name"].as_str())
        .map(|s| s.to_string());
    let asset_size = pick.and_then(|a| a["size"].as_i64());

    if compare_versions(&version, current_version) > 0 {
        UpdateCheck {
            kind: "available".into(),
            info: Some(UpdateInfo {
                version,
                tag,
                notes,
                release_url,
                asset_url,
                asset_name,
                asset_size,
            }),
            message: None,
        }
    } else {
        UpdateCheck::up_to_date()
    }
}

/// Downloads an asset into `dir`, reporting progress through `on_progress(bytes, total)`.
pub fn download(
    url: &str,
    dest: &std::path::Path,
    total: Option<i64>,
    mut on_progress: impl FnMut(i64, Option<i64>),
) -> Result<(), String> {
    let response = ureq::get(url)
        .set("User-Agent", AGENT)
        .timeout(std::time::Duration::from_secs(300))
        .call()
        .map_err(|e| e.to_string())?;

    let mut reader = response.into_reader();
    let mut file = std::fs::File::create(dest).map_err(|e| e.to_string())?;
    let mut buffer = vec![0u8; 64 * 1024];
    let mut written: i64 = 0;
    let mut last_report: i64 = 0;
    loop {
        use std::io::{Read, Write};
        let n = reader.read(&mut buffer).map_err(|e| e.to_string())?;
        if n == 0 {
            break;
        }
        file.write_all(&buffer[..n]).map_err(|e| e.to_string())?;
        written += n as i64;
        if written - last_report > 128 * 1024 {
            last_report = written;
            on_progress(written, total);
        }
    }
    on_progress(written, total);
    Ok(())
}

/// Rewrites a GitHub asset URL through the mirror when the user enabled acceleration.
pub fn accelerated(url: &str, use_proxy: bool) -> String {
    if use_proxy && !url.starts_with(ASSET_MIRROR) {
        format!("{ASSET_MIRROR}{url}")
    } else {
        url.to_string()
    }
}
