/* Session controller — the single owner of the websocket, the audio engine, the transcript and the
   persistence of a finished session. Ported 1:1 from the Android SessionViewModel. */
(function () {
  const t = (k, v) => SP.i18n.t(k, v);

  const MAX_RECONNECT_ATTEMPTS = 3;
  const MAX_REPLAY_MESSAGES = 40;
  const PTT_MIN_COMMIT_BYTES = 1600;
  const MAX_TITLE_CHARS = 18;
  const MAX_ERROR_DETAIL_CHARS = 150;

  const DEFAULT_CHAT_PROMPT =
    "You are a friendly, knowledgeable voice assistant. Keep answers concise and natural for " +
    "speech (usually 1-3 short paragraphs unless asked for more). Avoid markdown formatting since " +
    "everything you say is spoken aloud.";

  const LANGUAGES = [
    ["zh", "中文（简体）"],
    ["en", "English"],
    ["ja", "日本語"],
    ["ko", "한국어"],
    ["fr", "Français"],
    ["de", "Deutsch"],
    ["es", "Español"],
    ["ru", "Русский"],
    ["pt", "Português"],
    ["it", "Italiano"],
    ["th", "ไทย"],
    ["vi", "Tiếng Việt"],
    ["id", "Bahasa Indonesia"],
    ["ar", "العربية"],
    ["hi", "हिन्दी"],
  ];

  const ASR_CODES = [
    "zh", "en", "ja", "ko", "vi", "th", "id", "ms", "tl", "hi", "ar",
    "fr", "de", "es", "pt", "ru", "it", "nl", "sv", "da", "fi", "no",
    "el", "pl", "cs", "hu", "ro", "bg", "hr", "sk",
  ];

  function langName(code) {
    const hit = LANGUAGES.find((l) => l[0] === code);
    return hit ? hit[1] : code;
  }

  // ------------------------------------------------------------------ prompts
  const prompts = {
    chat(customPrompt, speakLang) {
      const base = (customPrompt || "").trim() || DEFAULT_CHAT_PROMPT;
      const langLine =
        !speakLang || speakLang === "auto"
          ? "Reply in the same language the user speaks."
          : `Always reply in ${langName(speakLang)}.`;
      return (
        base.trimEnd() +
        "\n\nStyle: your replies are spoken aloud to the user. Keep them concise, natural and " +
        "conversational. Never use markdown. " +
        langLine
      );
    },

    practice(langCode, level, topic, policy) {
      const lang = langName(langCode);
      const topicLine = topic
        ? `"${topic}"`
        : "free conversation — pick interesting everyday subjects";
      const correction =
        policy === "NEVER"
          ? "Do NOT correct the learner's mistakes. Just keep the conversation natural and flowing."
          : policy === "ALWAYS"
          ? "Actively correct notable mistakes: after the learner's turn, briefly restate the " +
            "corrected version of what they said, then continue. Keep each correction to one short " +
            "sentence so the conversation stays flowing."
          : "If the learner makes a notable mistake, briefly correct it inline: say the corrected " +
            "phrase in one short sentence, then continue the conversation naturally. Do this at " +
            "most once per learner turn; ignore small slips.";
      return (
        `You are a professional spoken-${lang} coach having a live voice conversation with a ` +
        `learner (level: about CEFR ${level}).\n` +
        `Rules:\n` +
        `- Speak ONLY ${lang}, using vocabulary suited to a ${level} learner.\n` +
        `- Conversation topic: ${topicLine}.\n` +
        `- Keep your turns short (1-3 sentences) and usually end with a question to keep the conversation flowing.\n` +
        `- Never switch to another language, except at most a 3-5 word parenthetical clarification when a word is truly unclear.\n` +
        `- Be warm, patient and encouraging.\n` +
        `- Correction policy: ${correction}`
      );
    },

    translateSystem(targetLangName) {
      return (
        `You are a translation engine. Translate the user's text into ${targetLangName}. ` +
        "Output ONLY the translation, with no explanations or quotes. Preserve the tone. " +
        `If the text is already in ${targetLangName}, output it unchanged.`
      );
    },

    reportUserPrompt(langCode, level, messages) {
      const lang = langName(langCode);
      const dialog = messages
        .map(([isUser, text]) => (isUser ? "[学习者] " : "[教练] ") + text)
        .join("\n");
      return (
        `以下是学习者练习${lang}口语的一段对话记录（学习水平约 CEFR ${level}）。\n` +
        "请根据学习者的发言，用中文写一份口语练习报告，直接输出 Markdown 正文，包含以下小节：\n" +
        "## 总体表现\n2-3 句整体评价（流利度、内容、互动）。\n" +
        "## 语法与用词问题\n逐条列出：原句 → 建议改法 → 一句话原因。按严重程度排序，最多 8 条；没有明显问题就写「未发现明显问题」。\n" +
        "## 更地道的表达\n2-4 条，针对学习者说过的内容给出母语者更自然的说法。\n" +
        "## 下次练习建议\n2-3 条具体可执行的建议。\n\n" +
        `对话记录：\n${dialog}`
      );
    },

    reportSystem:
      "你是一位资深的口语教练，擅长根据对话记录给出具体、可操作、语气友善的改进建议。",
  };

  // ------------------------------------------------------------------ state
  function blankUi() {
    return {
      status: "IDLE",
      agentState: "LISTENING",
      mode: "CHAT",
      messages: [],
      liveUser: "",
      liveAgent: "",
      micMuted: false,
      pttActive: false,
      elapsedSec: 0,
      error: null,
      translatingCount: 0,
      reportGenerating: false,
      report: null,
      reportSaved: false,
      micLevel: 0,
      userTurns: 0,
    };
  }

  let ui = blankUi();
  const listeners = [];

  function emit() {
    listeners.forEach((fn) => {
      try {
        fn(ui);
      } catch (e) {
        console.error(e);
      }
    });
  }

  function update(patch) {
    ui = Object.assign({}, ui, patch);
    emit();
  }

  // runtime internals
  let settings = null;
  let config = null;
  let sessionId = "";
  let startedAt = 0;
  let ended = false;
  let starting = false;
  let connGeneration = 0;
  let sessionSerial = 0;
  let reconnectAttempts = 0;
  let replayDoneGeneration = -1;
  let reconnectTimer = null;
  let timerHandle = null;
  let responseActive = false;
  let currentResponseId = null;
  let liveAgentBuf = "";
  let finalAgentTranscript = null;
  let msgSeq = 0;
  let pttBytes = 0;
  let rawLevel = 0;
  let smoothedLevel = 0;
  let reportBase = null;
  let savePromise = null;

  // ------------------------------------------------------------------ helpers
  function str(key, vars) {
    return t(key, vars);
  }

  function hasNetwork() {
    return navigator.onLine !== false;
  }

  function nextId(prefix) {
    msgSeq += 1;
    return prefix + msgSeq;
  }

  function applyTransmitMode() {
    if (!settings) return;
    if (settings.vadMode === "PTT") {
      SP.audio.setTransmit(ui.pttActive && !ui.micMuted);
    } else {
      SP.audio.setTransmit(!ui.micMuted);
    }
  }

  function sendSessionUpdate() {
    if (!config) return;
    const instructions =
      config.mode === "PRACTICE"
        ? prompts.practice(
            config.pracLang,
            config.pracLevel,
            config.pracTopic,
            config.policy
          )
        : prompts.chat(
            settings.chatPrompt,
            config.chatSpeakLang || settings.chatSpeakLang
          );
    SP.rt.sendSessionUpdate({
      instructions,
      voice: settings.voice,
      vadMode: settings.vadMode,
      vadThreshold: settings.vadThreshold,
      silenceMs: settings.silenceMs,
      asrLang: settings.asrLang,
      flavor: settings.flavor,
    });
  }

  // ------------------------------------------------------------------ lifecycle

  async function start(cfg) {
    const active = ["CONNECTING", "CONNECTED", "RECONNECTING"];
    if (active.includes(ui.status) || starting) return;
    starting = true;
    const serial = ++sessionSerial;
    try {
      await beginSession(cfg, serial);
    } finally {
      starting = false;
    }
  }

  async function beginSession(cfg, serial) {
    config = cfg;
    const loaded = SP.store.settings;
    if (serial !== sessionSerial) return;
    settings = loaded;

    if (!settings.apiKey || !settings.apiKey.trim()) {
      fail(str("sess.err_no_key"));
      return;
    }
    if (!hasNetwork()) {
      fail(str("sess.err_offline"));
      return;
    }

    // Unlock the audio context inside the click's activation window.
    if (SP.audio.prime) await SP.audio.prime();

    sessionId = "s_" + Date.now();
    startedAt = Date.now();
    ended = false;
    connGeneration++;
    reconnectAttempts = 0;
    replayDoneGeneration = -1;
    liveAgentBuf = "";
    finalAgentTranscript = null;
    responseActive = false;
    currentResponseId = null;
    rawLevel = 0;
    smoothedLevel = 0;
    pttBytes = 0;
    reportBase = null;
    savePromise = null;
    msgSeq = 0;

    update(
      Object.assign(blankUi(), {
        status: "CONNECTING",
        mode: cfg.mode,
      })
    );

    startTimer();
    connect();
  }

  function startTimer() {
    stopTimer();
    timerHandle = setInterval(() => {
      if (ended || ui.status === "ENDED" || ui.status === "FAILED") return;
      update({ elapsedSec: ui.elapsedSec + 1 });
    }, 1000);
  }

  function stopTimer() {
    if (timerHandle) clearInterval(timerHandle);
    timerHandle = null;
  }

  function connect() {
    if (!config) return;
    const generation = ++connGeneration;
    SP.rt.connect({
      endpoint: settings.rtEndpoint,
      apiKey: settings.apiKey,
      model: settings.rtModel,
      onStatus: (payload) => {
        if (ended || connGeneration !== generation) return;
        if (payload.kind === "open") return;
        if (payload.kind === "failed") {
          const detail = payload.reason || "";
          if (detail.indexOf("401") >= 0 || detail.indexOf("403") >= 0) {
            fail(str("sess.err_auth"));
            return;
          }
          handleDisconnect(detail);
          return;
        }
        // graceful close
        handleDisconnect(payload.reason || "");
      },
      onEvent: (evt) => {
        if (!ended && connGeneration === generation) handleEvent(evt);
      },
    });
  }

  function handleEvent(evt) {
    switch (evt.type) {
      case "sessionCreated":
        sendSessionUpdate();
        break;

      case "sessionUpdated":
        onSessionReady();
        break;

      case "speechStarted":
        // The server heard the user over the assistant: barge in when allowed.
        if (settings.bargeIn && responseActive && !ended) bargeIn();
        break;

      case "userTranscriptDelta":
        if (!settings.subtitleUser) return;
        update({ liveUser: evt.text + (evt.stash || "") });
        break;

      case "userTranscriptDone":
        onUserTranscript(evt);
        break;

      case "responseCreated":
        responseActive = true;
        currentResponseId = evt.responseId;
        liveAgentBuf = "";
        finalAgentTranscript = null;
        update({ agentState: "THINKING", liveAgent: "" });
        break;

      case "agentTranscriptDelta":
        if (!responseActive || !isCurrentResponse(evt.responseId)) return;
        liveAgentBuf += evt.delta;
        if (settings.subtitleAi) update({ liveAgent: liveAgentBuf });
        break;

      case "agentTranscriptDone":
        if (!responseActive || !isCurrentResponse(evt.responseId)) return;
        finalAgentTranscript = evt.transcript || null;
        break;

      case "audioDelta": {
        // Audio from a cancelled response must never reach the speaker again.
        if (!responseActive || !isCurrentResponse(evt.responseId)) return;
        if (ui.agentState !== "SPEAKING") update({ agentState: "SPEAKING" });
        const bytes = SP.audio.base64ToBytes(evt.audio);
        SP.audio.playPcm(bytes);
        break;
      }

      case "responseDone":
        if (!responseActive || !isCurrentResponse(evt.responseId)) return;
        finishResponse();
        break;

      case "error": {
        const type = evt.errorType || "";
        if (type.toLowerCase().indexOf("server") >= 0) {
          if (!ended) handleDisconnect(evt.message || type);
        } else {
          update({
            error: str("sess.err_server", { msg: evt.message || type }),
          });
        }
        break;
      }

      default:
        break;
    }
  }

  function isCurrentResponse(responseId) {
    const current = currentResponseId;
    return !responseId || !current || responseId === current;
  }

  async function onSessionReady() {
    if (ended) return;
    const firstConnect = ui.status === "CONNECTING";
    if (reconnectTimer) {
      clearTimeout(reconnectTimer);
      reconnectTimer = null;
    }
    reconnectAttempts = 0;
    responseActive = false;
    currentResponseId = null;
    liveAgentBuf = "";
    finalAgentTranscript = null;

    update({
      status: "CONNECTED",
      agentState: "LISTENING",
      liveUser: "",
      liveAgent: "",
      error: null,
    });

    const ok = await SP.audio.start({
      inputDeviceId: settings.inputDeviceId || undefined,
      outputDeviceId: settings.outputDeviceId || undefined,
      gain: settings.micGain,
      onFrame: (b64) => transmitMicFrame(b64),
      onLevel: (lvl) => {
        rawLevel = lvl;
      },
    });

    if (!ok) {
      const err = SP.audio.lastError || "";
      fail(
        err.indexOf("mic-denied") >= 0
          ? str("sess.err_mic_denied")
          : str("sess.err_mic_init")
      );
      return;
    }

    SP.audio.setMuted(ui.micMuted);
    SP.audio.setGain(settings.micGain);
    applyTransmitMode();
    startLevelTicker();

    if (connGeneration !== replayDoneGeneration) {
      replayDoneGeneration = connGeneration;
      if (!firstConnect) replayRecentContext();
    }
  }

  function transmitMicFrame(base64) {
    if (ended || ui.status !== "CONNECTED") return;
    pttBytes += Math.floor((base64.length * 3) / 4);
    SP.rt.sendAudio(base64);
  }

  function replayRecentContext() {
    ui.messages
      .filter((m) => m.kind === "normal")
      .slice(-MAX_REPLAY_MESSAGES)
      .forEach((m) => {
        if (m.role === "USER") SP.rt.replayMessage(true, m.text);
        else if (m.role === "ASSISTANT") SP.rt.replayMessage(false, m.text);
      });
  }

  function onUserTranscript(evt) {
    const text = (evt.transcript || "").trim();
    if (!text) {
      update({ liveUser: "" });
      return;
    }
    const id = evt.itemId || nextId("m");
    if (ui.messages.some((m) => m.id === id)) {
      update({ liveUser: "" });
      return;
    }
    const message = {
      id,
      role: "USER",
      text,
      ts: Date.now(),
      kind: "normal",
    };
    update({
      liveUser: "",
      messages: ui.messages.concat([message]),
      userTurns: ui.userTurns + 1,
    });
    translateAsync(message);
  }

  function finishResponse() {
    responseActive = false;
    currentResponseId = null;
    const text = (finalAgentTranscript || liveAgentBuf).trim();
    liveAgentBuf = "";
    finalAgentTranscript = null;
    if (!text) {
      update({ liveAgent: "", agentState: "LISTENING" });
      return;
    }
    const message = {
      id: nextId("a"),
      role: "ASSISTANT",
      text,
      ts: Date.now(),
      kind: "normal",
    };
    update({
      liveAgent: "",
      agentState: "LISTENING",
      messages: ui.messages.concat([message]),
    });
    translateAsync(message);
  }

  function bargeIn() {
    responseActive = false;
    currentResponseId = null;
    SP.audio.flush();
    SP.rt.cancelResponse();
    commitPartialAgentText();
  }

  function commitPartialAgentText() {
    const partial = (finalAgentTranscript || liveAgentBuf).trim();
    liveAgentBuf = "";
    finalAgentTranscript = null;
    if (!partial) {
      update({ agentState: "LISTENING", liveAgent: "" });
      return;
    }
    const message = {
      id: nextId("a"),
      role: "ASSISTANT",
      text: partial,
      ts: Date.now(),
      kind: "normal",
    };
    update({
      agentState: "LISTENING",
      liveAgent: "",
      messages: ui.messages.concat([message]),
    });
    translateAsync(message);
  }

  // ------------------------------------------------------------------ connection lifecycle

  function handleDisconnect(reason) {
    if (ended) return;
    SP.audio.setTransmit(false);
    SP.audio.flush();
    connGeneration++;
    SP.rt.close();

    if (!settings.autoReconnect) {
      fail(str("sess.err_connection_closed", { reason: reason || "" }));
      return;
    }
    if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
      fail(str("sess.err_reconnect_failed"));
      return;
    }
    reconnectAttempts++;
    update({
      status: "RECONNECTING",
      agentState: "LISTENING",
      liveAgent: "",
      error: str("sess.err_reconnecting", { n: reconnectAttempts }),
    });
    reconnectTimer = setTimeout(() => {
      if (!ended) connect();
    }, 600 * reconnectAttempts);
  }

  function fail(message) {
    releaseTransport();
    update({
      status: "FAILED",
      agentState: "LISTENING",
      liveUser: "",
      liveAgent: "",
      pttActive: false,
      micLevel: 0,
      error: message,
    });
  }

  function releaseTransport() {
    connGeneration++;
    sessionSerial++;
    if (reconnectTimer) clearTimeout(reconnectTimer);
    reconnectTimer = null;
    stopTimer();
    stopLevelTicker();
    SP.audio.setTransmit(false);
    SP.audio.flush();
    SP.audio.stop();
    SP.rt.close();
    rawLevel = 0;
    smoothedLevel = 0;
  }

  // ------------------------------------------------------------------ level meter

  let levelHandle = null;
  function startLevelTicker() {
    stopLevelTicker();
    levelHandle = setInterval(() => {
      const connected = !ended && ui.status === "CONNECTED" && !ui.micMuted;
      const target = connected ? rawLevel : 0;
      // fast attack, slow release — same curve as the Android engine
      smoothedLevel +=
        (target - smoothedLevel) * (target > smoothedLevel ? 0.65 : 0.2);
      if (smoothedLevel < 0.004) smoothedLevel = 0;
      update({ micLevel: smoothedLevel });
    }, 50);
  }

  function stopLevelTicker() {
    if (levelHandle) clearInterval(levelHandle);
    levelHandle = null;
  }

  // ------------------------------------------------------------------ user actions

  function toggleMute() {
    const muted = !ui.micMuted;
    SP.audio.setMuted(muted);
    if (muted) rawLevel = 0;
    update({ micMuted: muted, micLevel: muted ? 0 : ui.micLevel });
    applyTransmitMode();
  }

  function pttDown() {
    if (!settings || settings.vadMode !== "PTT") return;
    if (settings.bargeIn && responseActive) bargeIn();
    pttBytes = 0;
    update({ pttActive: true });
    applyTransmitMode();
    SP.rt.clearAudioBuffer();
  }

  function pttUp() {
    if (!settings || settings.vadMode !== "PTT") return;
    if (!ui.pttActive) return;
    update({ pttActive: false });
    applyTransmitMode();
    const captured = pttBytes;
    pttBytes = 0;
    // Committing an empty buffer only earns a server error event.
    if (captured < PTT_MIN_COMMIT_BYTES) return;
    SP.rt.commitAudio();
    SP.rt.triggerResponse();
  }

  function sendText(text) {
    const trimmed = (text || "").trim();
    if (!trimmed) return false;
    const local = {
      id: nextId("m"),
      role: "USER",
      text: trimmed,
      ts: Date.now(),
      kind: "normal",
    };
    update({ messages: ui.messages.concat([local]) });

    if (ui.status !== "CONNECTED" || !SP.rt.sendUserText(trimmed)) {
      // Offline: the bubble would otherwise sit in the transcript forever.
      update({
        messages: ui.messages.filter((m) => m.id !== local.id),
        error: str("sess.err_not_connected"),
      });
      return false;
    }
    update({ userTurns: ui.userTurns + 1 });
    translateAsync(local);
    return true;
  }

  function dismissError() {
    update({ error: null });
  }

  async function retry() {
    const cfg = config;
    if (!cfg) return;
    const active = ["CONNECTING", "CONNECTED", "RECONNECTING"];
    if (active.includes(ui.status)) return;
    releaseTransport();
    ended = false;
    update({
      status: "IDLE",
      agentState: "LISTENING",
      liveUser: "",
      liveAgent: "",
      pttActive: false,
      micLevel: 0,
      error: null,
    });
    await start(cfg);
  }

  // ------------------------------------------------------------------ translation

  async function translateAsync(message) {
    if (!settings || !settings.translateEnabled || !message.text) return;
    update({ translatingCount: ui.translatingCount + 1 });
    try {
      const translated = await SP.api.complete({
        endpoint: settings.textEndpoint,
        apiKey: settings.apiKey,
        model: settings.textModel,
        messages: [
          ["system", prompts.translateSystem(langName(settings.translateLang))],
          ["user", message.text],
        ],
        temperature: 0.2,
        maxTokens: 1000,
      });
      update({
        messages: ui.messages.map((m) =>
          m.id === message.id ? Object.assign({}, m, { translation: translated }) : m
        ),
        translatingCount: Math.max(0, ui.translatingCount - 1),
      });
    } catch (_) {
      update({
        messages: ui.messages.map((m) =>
          m.id === message.id
            ? Object.assign({}, m, { translation: str("sess.err_translation") })
            : m
        ),
        translatingCount: Math.max(0, ui.translatingCount - 1),
      });
    }
  }

  // ------------------------------------------------------------------ end & persistence

  function buildTitle() {
    const firstUser = (ui.messages.find((m) => m.role === "USER") || {}).text;
    const head = firstUser ? firstUser.slice(0, MAX_TITLE_CHARS) : null;
    if (config.mode === "PRACTICE") {
      const language = langName(config.pracLang);
      return head
        ? str("sess.title_practice_topic", { lang: language, topic: head })
        : str("sess.title_practice", { lang: language });
    }
    return head || str("sess.title_chat");
  }

  async function endSession(generateReportIfEnabled) {
    if (ended) return;
    ended = true;
    const cfg = config;
    const messages = ui.messages.slice();
    const durationMs = startedAt > 0 ? Math.max(0, Date.now() - startedAt) : 0;

    releaseTransport();
    update({
      status: "ENDED",
      agentState: "LISTENING",
      liveUser: "",
      liveAgent: "",
      pttActive: false,
      micLevel: 0,
    });

    if (!cfg || !messages.length) return;

    const stored = {
      meta: {
        id: sessionId,
        mode: cfg.mode,
        title: buildTitle(),
        language: cfg.mode === "PRACTICE" ? cfg.pracLang : "",
        createdAt: startedAt,
        durationMs,
        messageCount: messages.length,
      },
      messages,
      report: null,
    };
    reportBase = stored;

    savePromise = SP.api.saveSession(stored).catch(() => null);
    await savePromise;

    const shouldReport =
      generateReportIfEnabled !== false &&
      cfg.mode === "PRACTICE" &&
      settings.pracReport &&
      messages.some((m) => m.role === "USER");

    if (shouldReport) await generateReport(cfg, messages, stored);
  }

  async function generateReport(cfg, messages, fallbackBase) {
    if (ui.reportGenerating) return;
    const ownerSessionId = sessionId;
    const model = settings.textModel;
    update({ reportGenerating: true, report: null, reportSaved: false });

    const dialog = messages
      .filter((m) => m.kind === "normal")
      .map((m) => [m.role === "USER", m.text]);

    try {
      const content = await SP.api.complete({
        endpoint: settings.textEndpoint,
        apiKey: settings.apiKey,
        model,
        messages: [
          ["system", prompts.reportSystem],
          ["user", prompts.reportUserPrompt(cfg.pracLang, cfg.pracLevel, dialog)],
        ],
        temperature: 0.4,
        maxTokens: 3000,
      });
      const persisted = await persistReport(ownerSessionId, content, fallbackBase);
      if (sessionId !== ownerSessionId) return;
      update({
        report: content,
        reportGenerating: false,
        reportSaved: persisted,
      });
    } catch (e) {
      if (sessionId !== ownerSessionId) return;
      update({
        reportGenerating: false,
        reportSaved: false,
        error: str("sess.err_report_failed", {
          msg: String((e && e.message) || e).slice(0, MAX_ERROR_DETAIL_CHARS),
        }),
      });
    }
  }

  async function persistReport(id, content, fallbackBase) {
    if (!id) return false;
    try {
      const existing = await SP.api.loadSession(id);
      const base = existing || fallbackBase;
      if (!base) return false;
      base.report = { content, generatedAt: Date.now() };
      await SP.api.saveSession(base);
      return true;
    } catch (_) {
      return false;
    }
  }

  async function regenerateReport() {
    if (!config || ui.reportGenerating) return;
    const messages = ui.messages;
    if (!messages.some((m) => m.role === "USER")) return;
    await generateReport(config, messages, reportBase);
  }

  // ------------------------------------------------------------------ public
  window.SP = window.SP || {};
  SP.session = {
    get ui() {
      return ui;
    },
    get config() {
      return config;
    },
    get sessionId() {
      return sessionId;
    },
    onChange(fn) {
      listeners.push(fn);
      return () => {
        const i = listeners.indexOf(fn);
        if (i >= 0) listeners.splice(i, 1);
      };
    },
    start,
    endSession,
    retry,
    toggleMute,
    pttDown,
    pttUp,
    sendText,
    dismissError,
    regenerateReport,
    reset() {
      ui = blankUi();
      config = null;
      sessionId = "";
      ended = false;
      emit();
    },
  };

  SP.prompts = prompts;
  SP.languages = {
    list: LANGUAGES,
    asrCodes: ASR_CODES,
    name: langName,
    speakOptions: LANGUAGES,
  };
})();
