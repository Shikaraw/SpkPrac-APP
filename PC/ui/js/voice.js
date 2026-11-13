/* Voice presets + cloned voices: local/server list merge, creation, polling and deletion.
   Contract: docs/voice-clone-spec.md (v0.1.1). Keep this file dependency-free. */
(function () {
  const t = (k, v) => SP.i18n.t(k, v);

  /** Spec §5.1 — Qwen3.8-Omni 官方有效系统音色，顺序与 Rust/Cargo 端常量一致。 */
  const PRESETS = [
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

  /** The preset dropdown is only rendered for this model (spec §5.1). */
  const REALtime_MODEL = "qwen3.8-omni-flash-realtime";

  const STATUS = { DEPLOYING: "DEPLOYING", OK: "OK", UNDEPLOYED: "UNDEPLOYED" };
  const MAX_FILE_SIZE = 10 * 1024 * 1024; // spec §3: < 10 MB
  const MAX_POLLS = 30;
  const POLL_INTERVAL = 1000;
  const MAX_NAME = 20;

  const EXT_MIME = {
    wav: "audio/wav",
    mp3: "audio/mpeg",
    m4a: "audio/mp4",
    aac: "audio/aac",
  };

  function settings() {
    return SP.store.settings || {};
  }

  function localList() {
    const list = settings().clonedVoices;
    return Array.isArray(list) ? list : [];
  }

  function presets() {
    return PRESETS.slice();
  }

  function isRealtimeModel(model) {
    return model === REALtime_MODEL;
  }

  /**
   * Spec §2.2: the enrollment model follows the target model family — Qwen-Omni uses
   * `qwen-voice-enrollment`, everything else `voice-enrollment`.
   */
  function enrollModelFor(rtModel) {
    return String(rtModel || "").toLowerCase().indexOf("omni") >= 0
      ? "qwen-voice-enrollment"
      : "voice-enrollment";
  }

  function enrollModel() {
    return enrollModelFor(settings().rtModel);
  }

  function find(voiceId) {
    return localList().find((v) => v.voiceId === voiceId) || null;
  }

  /** Cloned voices that may appear in the settings dropdown (spec §4.5 + §5.1). */
  function usableVoices(targetModel) {
    const model = targetModel || settings().rtModel || REALtime_MODEL;
    return localList().filter(
      (v) => v && v.status === STATUS.OK && v.targetModel === model
    );
  }

  function statusLabel(status) {
    if (status === STATUS.OK) return t("voice.ok");
    if (status === STATUS.UNDEPLOYED) return t("voice.undeployed");
    return t("voice.deploying");
  }

  function statusHint(status) {
    if (status === STATUS.OK) return t("voice.hint_ok");
    if (status === STATUS.UNDEPLOYED) return t("voice.hint_undeployed");
    return t("voice.hint_deploying");
  }

  function error(code, message) {
    return { code: code, message: message, requestId: "" };
  }

  /** Spec §2.5: show the server message verbatim, without dropping `code` / `request_id`. */
  function errorText(e) {
    if (!e) return t("common.retry");
    if (typeof e === "string") return e;
    const message = e.message || e.error || "";
    const code = e.code ? String(e.code) : "";
    const requestId = e.requestId ? String(e.requestId) : "";
    let text = message || code || String(e);
    if (code && message && text.indexOf(code) < 0) text += " [" + code + "]";
    if (requestId) text += " (request_id " + requestId + ")";
    return text;
  }

  // ------------------------------------------------------------------ file helpers

  function extOf(name) {
    const m = /\.([a-z0-9]+)$/i.exec(String(name || ""));
    return m ? m[1].toLowerCase() : "";
  }

  /** Spec §3: keep `[A-Za-z0-9]`, lowercase, at most 10 characters, fall back to `voice`. */
  function prefixFor(name) {
    const prefix = String(name || "")
      .toLowerCase()
      .replace(/[^a-z0-9]/g, "")
      .slice(0, 10);
    return prefix || "voice";
  }

  function mimeFor(name) {
    return EXT_MIME[extOf(name)] || "audio/wav";
  }

  /** Reads the WAV header directly: exact sample rate/channels/duration, no decoding needed. */
  function parseWavHeader(buffer) {
    try {
      const view = new DataView(buffer);
      if (view.byteLength < 44) return null;
      if (view.getUint32(0, false) !== 0x52494646) return null; // "RIFF"
      if (view.getUint32(8, false) !== 0x57415645) return null; // "WAVE"
      let offset = 12;
      let fmt = null;
      let dataSize = 0;
      while (offset + 8 <= view.byteLength) {
        const id = String.fromCharCode(
          view.getUint8(offset),
          view.getUint8(offset + 1),
          view.getUint8(offset + 2),
          view.getUint8(offset + 3)
        );
        const size = view.getUint32(offset + 4, true);
        const body = offset + 8;
        if (id === "fmt " && body + 16 <= view.byteLength) {
          fmt = {
            format: view.getUint16(body, true),
            channels: view.getUint16(body + 2, true),
            sampleRate: view.getUint32(body + 4, true),
            byteRate: view.getUint32(body + 8, true),
            bits: view.getUint16(body + 14, true),
          };
        } else if (id === "data") {
          dataSize = size;
          if (fmt) break;
        }
        offset = body + size + (size % 2);
      }
      if (!fmt) return null;
      const duration =
        fmt.byteRate > 0 && dataSize > 0 ? dataSize / fmt.byteRate : 0;
      return {
        duration: duration,
        sampleRate: fmt.sampleRate,
        channels: fmt.channels,
        bits: fmt.bits,
        wavFormat: fmt.format,
      };
    } catch (_) {
      return null;
    }
  }

  /** Decodes MP3/M4A/AAC to read duration/channels (sample rate when the browser reports one). */
  function decodeAudio(buffer) {
    const Ctx = window.AudioContext || window.webkitAudioContext;
    if (!Ctx) return Promise.resolve(null);
    let ctx;
    try {
      ctx = new Ctx();
    } catch (_) {
      return Promise.resolve(null);
    }
    const close = () => {
      try {
        if (!ctx) return;
        const p = ctx.close();
        if (p && p.catch) p.catch(() => {});
      } catch (_) {}
    };
    return new Promise((resolve) => {
      let settled = false;
      let timer = null;
      const done = (info) => {
        if (settled) return;
        settled = true;
        if (timer) clearTimeout(timer);
        close();
        resolve(info);
      };
      const info = (buf) => ({
        duration: buf && isFinite(buf.duration) ? buf.duration : 0,
        sampleRate: buf && buf.sampleRate ? buf.sampleRate : 0,
        channels: buf && buf.numberOfChannels ? buf.numberOfChannels : 0,
      });
      try {
        const maybe = ctx.decodeAudioData(
          buffer,
          (buf) => done(info(buf)),
          () => done(null)
        );
        if (maybe && typeof maybe.then === "function") {
          maybe.then((buf) => done(info(buf)), () => done(null));
        }
      } catch (_) {
        done(null);
      }
      // Never hang the dialog if the decoder drops the callback.
      timer = setTimeout(() => done(null), 8000);
    });
  }

  async function readFileBytes(file) {
    if (file.arrayBuffer) return file.arrayBuffer();
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onerror = () => reject(new Error(t("voice.err_read")));
      reader.onload = () => resolve(reader.result);
      reader.readAsArrayBuffer(file);
    });
  }

  /**
   * Spec §3 validation. Rejects on format/size/duration/sample-rate problems and warns when the
   * duration is outside 10–20 s or the file is not mono / not 16-bit WAV.
   */
  async function validateAudio(file) {
    const result = {
      ok: false,
      errors: [],
      warnings: [],
      duration: 0,
      sampleRate: 0,
      channels: 0,
      size: 0,
      ext: "",
      mime: "audio/wav",
    };
    if (!file || !file.name) {
      result.errors.push(t("voice.err_read"));
      return result;
    }
    const ext = extOf(file.name);
    result.ext = ext;
    result.size = file.size || 0;
    result.mime = mimeFor(file.name);
    if (!EXT_MIME[ext]) {
      result.errors.push(t("voice.err_format"));
      return result;
    }
    if (!result.size) {
      result.errors.push(t("voice.err_read"));
      return result;
    }
    if (result.size > MAX_FILE_SIZE) result.errors.push(t("voice.err_size"));

    let bytes = null;
    try {
      bytes = await readFileBytes(file);
    } catch (_) {
      result.errors.push(t("voice.err_read"));
      return result;
    }

    let header = null;
    if (ext === "wav") header = parseWavHeader(bytes);
    if (header) {
      result.duration = header.duration || 0;
      result.sampleRate = header.sampleRate || 0;
      result.channels = header.channels || 0;
      if (header.bits && header.bits !== 16) result.warnings.push(t("voice.warn_wav16"));
    }
    if (!result.duration || !result.channels) {
      const decoded = await decodeAudio(bytes);
      if (decoded) {
        if (!result.duration) result.duration = decoded.duration || 0;
        if (!result.sampleRate) result.sampleRate = decoded.sampleRate || 0;
        if (!result.channels) result.channels = decoded.channels || 0;
      } else if (!header) {
        // Spec §3 / lead review: if it cannot be decoded, treat it as unsupported.
        result.errors.push(t("voice.err_decode"));
        result.ok = false;
        return result;
      }
    }

    if (result.duration > 0) {
      if (result.duration < 5) result.errors.push(t("voice.err_too_short"));
      else if (result.duration > 60) result.errors.push(t("voice.err_too_long"));
      else if (result.duration < 10 || result.duration > 20)
        result.warnings.push(t("voice.warn_duration"));
    }
    if (result.sampleRate > 0 && result.sampleRate < 24000) {
      result.errors.push(t("voice.err_sample_rate"));
    }
    if (result.channels > 1) result.warnings.push(t("voice.warn_channels"));
    result.ok = result.errors.length === 0;
    return result;
  }

  function formatAudioMeta(info) {
    const size = SP.ui.fmtSize(info.size) || "";
    const seconds = info.duration ? info.duration.toFixed(1) : "?";
    let text = t("voice.audio_meta", { size: size, seconds: seconds });
    if (info.sampleRate) text += " · " + Math.round(info.sampleRate / 1000) + " kHz";
    if (info.channels) text += " · " + info.channels + " ch";
    return text;
  }

  /** FileReader produces the base64 payload; Rust wraps it into `data:<mime>;base64,...`. */
  function readBase64(file) {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onerror = () => reject(error("READ", t("voice.err_read")));
      reader.onload = () => {
        const text = String(reader.result || "");
        const comma = text.indexOf(",");
        if (comma < 0) {
          reject(error("READ", t("voice.err_read")));
          return;
        }
        resolve(text.slice(comma + 1));
      };
      reader.readAsDataURL(file);
    });
  }

  // ------------------------------------------------------------------ local persistence

  async function saveList(list, extra) {
    const patch = Object.assign({ clonedVoices: list }, extra || {});
    await SP.store.patch(patch, "voices");
    return list;
  }

  async function upsertLocal(item) {
    const list = localList().filter((v) => v && v.voiceId !== item.voiceId);
    list.push(item);
    await saveList(list);
    return item;
  }

  async function patchLocal(voiceId, partial) {
    let updated = null;
    const list = localList().map((v) => {
      if (!v || v.voiceId !== voiceId) return v;
      updated = Object.assign({}, v, partial);
      return updated;
    });
    await saveList(list);
    return updated;
  }

  // ------------------------------------------------------------------ server sync

  function parseGmt(value) {
    const text = String(value || "").trim();
    if (!text) return 0;
    const m = /^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2}):(\d{2})/.exec(text);
    if (m) {
      // The cn-beijing endpoint reports Beijing time (UTC+8).
      return Date.UTC(
        Number(m[1]),
        Number(m[2]) - 1,
        Number(m[3]),
        Number(m[4]) - 8,
        Number(m[5]),
        Number(m[6])
      );
    }
    const parsed = Date.parse(text);
    return isNaN(parsed) ? 0 : parsed;
  }

  function lastSegment(voiceId) {
    const parts = String(voiceId || "").split("-");
    const tail = parts[parts.length - 1] || voiceId;
    return tail || String(voiceId || "");
  }

  /**
   * Spec §4 merge: remote list wins; unknown remote entries are added (name = voice_id tail) and
   * their target model is filled with at most 30 `query_voice` calls; local-only entries are
   * dropped because the server already deleted them.
   */
  async function refresh() {
    const s = settings();
    if (!s.apiKey) throw error("NO_KEY", t("voice.err_no_key"));
    if (!s.cloneEndpoint) throw error("NO_ENDPOINT", t("voice.err_no_endpoint"));
    const remote = await SP.api.voiceList(s.apiKey, s.cloneEndpoint, enrollModel());
    const remoteList = Array.isArray(remote) ? remote : [];
    const now = Date.now();
    const byId = new Map();
    localList().forEach((v) => {
      if (v && v.voiceId) byId.set(v.voiceId, v);
    });

    const merged = [];
    remoteList.forEach((r) => {
      const voiceId = r && r.voiceId;
      if (!voiceId) return;
      const existing = byId.get(voiceId);
      if (existing) {
        byId.delete(voiceId);
        merged.push(
          Object.assign({}, existing, {
            status: r.status || existing.status,
            updatedAt: now,
          })
        );
        return;
      }
      merged.push({
        voiceId: voiceId,
        name: lastSegment(voiceId),
        targetModel: "",
        status: r.status || STATUS.DEPLOYING,
        createdAt: parseGmt(r.gmtCreate) || now,
        updatedAt: now,
        audioName: null,
      });
    });

    // Fill unknown target models (spec §4.3), at most 30 queries per refresh.
    const unknown = merged.filter((v) => !v.targetModel).slice(0, 30);
    for (let i = 0; i < unknown.length; i++) {
      const voice = unknown[i];
      try {
        const detail = await SP.api.voiceQuery(
          s.apiKey,
          s.cloneEndpoint,
          enrollModelFor(settings().rtModel),
          voice.voiceId
        );
        if (detail) {
          if (detail.targetModel) voice.targetModel = detail.targetModel;
          if (detail.status) voice.status = detail.status;
          voice.updatedAt = Date.now();
        }
      } catch (_) {
        // Leave targetModel empty: the management page shows 「未知模型」 and the dropdown hides it.
      }
    }

    await saveList(merged);
    return merged;
  }

  async function refreshOne(voiceId) {
    const s = settings();
    if (!s.apiKey) throw error("NO_KEY", t("voice.err_no_key"));
    const current = find(voiceId);
    const targetModel = (current && current.targetModel) || s.rtModel;
    const detail = await SP.api.voiceQuery(
      s.apiKey,
      s.cloneEndpoint,
      enrollModelFor(targetModel),
      voiceId
    );
    const patch = {
      status: (detail && detail.status) || (current && current.status) || STATUS.DEPLOYING,
      updatedAt: Date.now(),
    };
    if (detail && detail.targetModel) patch.targetModel = detail.targetModel;
    await patchLocal(voiceId, patch);
    return Object.assign({}, current, patch);
  }

  function poll(voiceId, targetModel, onProgress) {
    const model = enrollModelFor(targetModel || settings().rtModel);
    const report = onProgress || (() => {});
    return new Promise((resolve, reject) => {
      let attempt = 0;
      let lastError = null;
      const tick = async () => {
        attempt += 1;
        try {
          const s = settings();
          const detail = await SP.api.voiceQuery(
            s.apiKey,
            s.cloneEndpoint,
            model,
            voiceId
          );
          const status = (detail && detail.status) || STATUS.DEPLOYING;
          const patch = { status: status, updatedAt: Date.now() };
          if (detail && detail.targetModel) patch.targetModel = detail.targetModel;
          await patchLocal(voiceId, patch);
          report({ phase: "polling", status: status, attempt: attempt });
          if (status === STATUS.OK) {
            resolve({ status: status, attempt: attempt });
            return;
          }
          if (status === STATUS.UNDEPLOYED) {
            reject(error("UNDEPLOYED", t("voice.undeployed")));
            return;
          }
          if (attempt >= MAX_POLLS) {
            resolve({ status: status, attempt: attempt, timeout: true });
            return;
          }
          setTimeout(tick, POLL_INTERVAL);
        } catch (e) {
          lastError = e;
          const code = e && e.code ? String(e.code) : "";
          if (
            code === "NO_KEY" ||
            code === "NO_ENDPOINT" ||
            code === "HTTP_401" ||
            code === "HTTP_403"
          ) {
            reject(e);
            return;
          }
          if (attempt >= MAX_POLLS) {
            const current = find(voiceId);
            resolve({
              status: (current && current.status) || STATUS.DEPLOYING,
              attempt: attempt,
              timeout: true,
              error: lastError,
            });
            return;
          }
          setTimeout(tick, POLL_INTERVAL);
        }
      };
      tick();
    });
  }

  /**
   * Spec §5.3 create flow: validate → read base64 → POST → store local record → poll query_voice.
   * Resolves with the final local record; throws a structured error understood by `errorText`.
   */
  async function create(opts) {
    const options = opts || {};
    const name = String(options.name || "").trim().slice(0, MAX_NAME);
    if (!name) throw error("NAME", t("voice.err_name_empty"));
    const file = options.file;
    if (!file) throw error("AUDIO", t("voice.err_no_file"));
    const s = settings();
    if (!s.apiKey) throw error("NO_KEY", t("voice.err_no_key"));
    if (!s.cloneEndpoint) throw error("NO_ENDPOINT", t("voice.err_no_endpoint"));

    const checked = await validateAudio(file);
    if (!checked.ok) throw error("AUDIO", checked.errors.join(" "));

    const report = options.onProgress || (() => {});
    report({ phase: "uploading" });
    const audioBase64 = await readBase64(file);
    const targetModel = s.rtModel || REALtime_MODEL;

    const reply = await SP.api.voiceCreate({
      apiKey: s.apiKey,
      endpoint: s.cloneEndpoint,
      model: enrollModelFor(targetModel),
      targetModel: targetModel,
      prefix: prefixFor(name),
      audioBase64: audioBase64,
      audioMime: checked.mime || mimeFor(file.name),
      enablePreprocess: true,
    });
    const voiceId = reply && reply.voiceId;
    if (!voiceId) throw error("NO_VOICE_ID", "the server returned no voice_id");

    const item = {
      voiceId: voiceId,
      name: name,
      targetModel: targetModel,
      status: STATUS.DEPLOYING,
      createdAt: Date.now(),
      updatedAt: Date.now(),
      audioName: file.name || null,
    };
    await upsertLocal(item);
    report({ phase: "created", voiceId: voiceId, status: STATUS.DEPLOYING });

    const polled = await poll(voiceId, targetModel, report);
    const record = find(voiceId) || item;
    return {
      voiceId: voiceId,
      name: record.name,
      targetModel: record.targetModel,
      status: record.status || polled.status,
      timeout: !!polled.timeout,
    };
  }

  /** Spec §5.5: delete on the server first, then drop it locally and fall back to `Tina`. */
  async function remove(voiceId) {
    const s = settings();
    if (!s.apiKey) throw error("NO_KEY", t("voice.err_no_key"));
    const current = find(voiceId);
    const model = enrollModelFor((current && current.targetModel) || s.rtModel);
    await SP.api.voiceDelete(s.apiKey, s.cloneEndpoint, model, voiceId);
    const list = localList().filter((v) => v && v.voiceId !== voiceId);
    const extra = {};
    if (String(s.voice || "") === voiceId) extra.voice = "Tina";
    await saveList(list, extra);
    return list;
  }

  /** Rename is local-only (spec §5.4). */
  async function rename(voiceId, name) {
    const trimmed = String(name || "").trim().slice(0, MAX_NAME);
    if (!trimmed) throw error("NAME", t("voice.err_name_empty"));
    await patchLocal(voiceId, { name: trimmed });
    return trimmed;
  }

  function presetIsSystem(voice) {
    return PRESETS.indexOf(String(voice || "")) >= 0;
  }

  window.SP = window.SP || {};
  SP.voice = {
    PRESETS: PRESETS,
    REALtime_MODEL: REALtime_MODEL,
    STATUS: STATUS,
    presets: presets,
    isRealtimeModel: isRealtimeModel,
    enrollModelFor: enrollModelFor,
    enrollModel: enrollModel,
    list: localList,
    find: find,
    usableVoices: usableVoices,
    statusLabel: statusLabel,
    statusHint: statusHint,
    presetIsSystem: presetIsSystem,
    errorText: errorText,
    prefixFor: prefixFor,
    mimeFor: mimeFor,
    validateAudio: validateAudio,
    formatAudioMeta: formatAudioMeta,
    refresh: refresh,
    refreshOne: refreshOne,
    create: create,
    remove: remove,
    rename: rename,
  };
})();
