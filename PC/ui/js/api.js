/* Thin wrapper over the Tauri IPC bridge.
   When the page is opened in a plain browser (design preview / headless screenshot) a mock backend
   backed by localStorage keeps every screen usable. */
(function () {
  const T = window.__TAURI__;
  const native = !!(T && T.core && T.core.invoke);

  const invoke = (cmd, args) => T.core.invoke(cmd, args);
  const listen = (event, cb) => T.event.listen(event, (e) => cb(e.payload));

  // ------------------------------------------------------------------ mock backend
  const MOCK_KEY = "spkprac.settings";
  const MOCK_SESSIONS = "spkprac.sessions";

  const mockSettings = () =>
    JSON.parse(
      localStorage.getItem(MOCK_KEY) ||
        JSON.stringify({
          apiKey: "",
          rtEndpoint: "wss://cn-beijing.maas.qianwenaiapi.com/api-ws/v1/realtime",
          rtModel: "qwen3.8-omni-flash-realtime",
          flavor: "QWEN",
          textEndpoint:
            "https://maas.qianwenaiapi.com/compatible-mode/v1/chat/completions",
          textModel: "qwen3.8-flash",
          voice: "Tina",
          vadMode: "AUTO",
          vadThreshold: 0.5,
          silenceMs: 600,
          subtitleAi: true,
          subtitleUser: true,
          translateEnabled: false,
          translateLang: "zh",
          asrLang: "",
          inputDeviceId: "",
          outputDeviceId: "",
          chatPrompt: "",
          chatSpeakLang: "auto",
          pracLang: "en",
          pracLevel: "B1-B2",
          pracTopic: "",
          pracCorrection: "SOMETIMES",
          pracReport: true,
          themeMode: "SYSTEM",
          captionSize: "MEDIUM",
          language: "zh",
          animations: true,
          showWaveform: true,
          micGain: 1.0,
          autoReconnect: true,
          bargeIn: true,
          autoCheckUpdate: true,
          updateProxy: true,
          lastUpdateCheckAt: 0,
        })
    );

  function mockSessions() {
    return JSON.parse(localStorage.getItem(MOCK_SESSIONS) || "[]");
  }

  const now = Date.now();
  const sample = [
    {
      meta: {
        id: "s_sample_1",
        mode: "PRACTICE",
        title: "English 点餐场景练习",
        language: "en",
        createdAt: now - 3600_000 * 5,
        durationMs: 412_000,
        messageCount: 8,
      },
      messages: [
        { id: "m1", role: "USER", text: "I want to order a coffee and a croissant.", ts: now - 3600_000 * 5, kind: "normal" },
        { id: "m2", role: "ASSISTANT", text: "Great choice! Would you like your coffee black or with milk?", ts: now - 3600_000 * 5 + 8000, kind: "normal" },
        { id: "m3", role: "USER", text: "With milk please. And how much is the total?", ts: now - 3600_000 * 5 + 16000, kind: "normal" },
        { id: "m4", role: "ASSISTANT", text: "That comes to five euros fifty. Small correction: it is more natural to say \"How much is it in total?\"", ts: now - 3600_000 * 5 + 24000, kind: "normal" },
      ],
      report: {
        content:
          "## 总体表现\n本次对话表达清晰、流利度不错，点餐场景用语基本准确。\n\n## 语法与用词问题\n- *how much is the total* → **how much is it in total**\n- *I want to order* 语气略直，可用 **I'd like to order**\n\n## 更地道的表达\n- **Could I get a coffee with milk, please?**\n- **What's the total?**\n\n## 下次练习建议\n- 多做数字与价格表达练习\n- 尝试加入礼貌用语",
        generatedAt: now - 3600_000 * 4,
      },
    },
    {
      meta: {
        id: "s_sample_2",
        mode: "CHAT",
        title: "周末去哪儿玩",
        language: "",
        createdAt: now - 86400_000,
        durationMs: 725_000,
        messageCount: 12,
      },
      messages: [
        { id: "m1", role: "USER", text: "帮我推荐一下周末适合去哪里放松。", ts: now - 86400_000, kind: "normal" },
        { id: "m2", role: "ASSISTANT", text: "如果天气好，可以去城郊的湿地公园走走，人少而且空气清新。", ts: now - 86400_000 + 6000, kind: "normal" },
      ],
      report: null,
    },
  ];

  const mock = {
    app_info: () => ({
      version: "1.0.0",
      dataDir: "C:\\Users\\You\\AppData\\Roaming\\com.spkprac.pc",
      sessionsDir: "C:\\Users\\You\\AppData\\Roaming\\com.spkprac.pc\\sessions",
      platform: "windows",
    }),
    settings_get: () => mockSettings(),
    settings_save: ({ settings }) => {
      localStorage.setItem(MOCK_KEY, JSON.stringify(settings));
      return null;
    },
    settings_voices: () => ["Tina", "Ethan", "Cherry", "Serena", "Dylan", "Jada"],
    sessions_list: () => {
      const stored = mockSessions();
      return (stored.length ? stored : sample).map((s) => s.meta);
    },
    sessions_load: ({ id }) => {
      const stored = mockSessions();
      return (stored.length ? stored : sample).find((s) => s.meta.id === id) || null;
    },
    sessions_save: ({ session }) => {
      const stored = mockSessions();
      const idx = stored.findIndex((s) => s.meta.id === session.meta.id);
      if (idx >= 0) stored[idx] = session;
      else stored.push(session);
      localStorage.setItem(MOCK_SESSIONS, JSON.stringify(stored));
      return null;
    },
    sessions_delete: ({ id }) => {
      const base = mockSessions().length ? mockSessions() : sample;
      localStorage.setItem(
        MOCK_SESSIONS,
        JSON.stringify(base.filter((s) => s.meta.id !== id))
      );
      return null;
    },
    sessions_delete_all: () => {
      localStorage.setItem(MOCK_SESSIONS, "[]");
      return null;
    },
    sessions_stats: () => {
      const list = (mockSessions().length ? mockSessions() : sample).map((s) => s.meta);
      return {
        sessions: list.length,
        totalMs: list.reduce((a, m) => a + m.durationMs, 0),
        streakDays: 3,
        practiceSessions: list.filter((m) => m.mode === "PRACTICE").length,
      };
    },
    sessions_export_all: () => "# mock export\n",
    session_export_text: ({ id }) => {
      const s = (mockSessions().length ? mockSessions() : sample).find((x) => x.meta.id === id);
      return s ? s.messages.map((m) => `${m.role}: ${m.text}`).join("\n") : "";
    },
    write_export: ({ fileName }) => `C:\\Users\\You\\Documents\\SpkPrac\\${fileName}`,
    open_external: () => null,
    reveal_path: () => null,
    open_dir: () => null,
    rt_connect: () => {
      throw new Error("mock: no realtime transport in browser preview");
    },
    rt_send: () => null,
    rt_close: () => null,
    text_complete: () => "# mock report\n\n这是浏览器预览模式的占位报告。",
    check_update: () => ({ kind: "uptodate", info: null, message: null }),
    download_update: () => "C:\\mock.exe",
  };

  function call(cmd, args) {
    if (native) return invoke(cmd, args);
    const fn = mock[cmd];
    if (!fn) return Promise.reject(new Error("mock missing: " + cmd));
    try {
      return Promise.resolve(fn(args || {}));
    } catch (e) {
      return Promise.reject(e);
    }
  }

  const handlers = { rtEvent: [], rtStatus: [], updateProgress: [] };

  if (native) {
    listen("rt-event", (payload) => handlers.rtEvent.forEach((cb) => cb(payload)))
      .catch((e) => console.error("rt-event listen failed", e));
    listen("rt-status", (payload) => handlers.rtStatus.forEach((cb) => cb(payload)))
      .catch((e) => console.error("rt-status listen failed", e));
    listen("update-progress", (payload) =>
      handlers.updateProgress.forEach((cb) => cb(payload))
    ).catch((e) => console.error("update-progress listen failed", e));
  }

  window.SP = window.SP || {};
  SP.api = {
    native,
    info: () => call("app_info"),
    getSettings: () => call("settings_get"),
    saveSettings: (settings) => call("settings_save", { settings }),
    voices: () => call("settings_voices"),
    listSessions: () => call("sessions_list"),
    loadSession: (id) => call("sessions_load", { id }),
    saveSession: (session) => call("sessions_save", { session }),
    deleteSession: (id) => call("sessions_delete", { id }),
    deleteAllSessions: () => call("sessions_delete_all"),
    stats: () => call("sessions_stats"),
    exportAll: () => call("sessions_export_all"),
    exportText: (id) => call("session_export_text", { id }),
    writeExport: (fileName, content) => call("write_export", { fileName, content }),
    openExternal: (url) => call("open_external", { url }),
    revealPath: (path) => call("reveal_path", { path }),
    openDir: (path) => call("open_dir", { path }),
    rtConnect: (endpoint, apiKey, model) =>
      call("rt_connect", { endpoint, apiKey, model }),
    rtSend: (text) => call("rt_send", { text }),
    rtClose: () => call("rt_close"),
    complete: ({ endpoint, apiKey, model, messages, temperature, maxTokens }) =>
      call("text_complete", {
        endpoint,
        apiKey,
        model,
        messages,
        temperature: temperature == null ? 0.3 : temperature,
        maxTokens: maxTokens == null ? 2048 : maxTokens,
      }),
    checkUpdate: (proxy) => call("check_update", { proxy }),
    downloadUpdate: (url, fileName, proxy, total) =>
      call("download_update", { url, fileName, proxy, total }),
    // Only exposed by the browser mock so a design-preview page can drive the state machine.
    _emitRt: (payload) => handlers.rtEvent.forEach((cb) => cb(payload)),
    _emitStatus: (payload) => handlers.rtStatus.forEach((cb) => cb(payload)),
    onRtEvent: (cb) => handlers.rtEvent.push(cb),
    onRtStatus: (cb) => handlers.rtStatus.push(cb),
    onUpdateProgress: (cb) => handlers.updateProgress.push(cb),
  };
})();
