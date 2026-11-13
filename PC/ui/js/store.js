/* Global app state: settings (persisted), app info, theme application, change fan-out. */
(function () {
  const { t } = SP.i18n;

  const listeners = [];
  const state = {
    settings: null,
    info: { version: "0.1.1", dataDir: "", sessionsDir: "", platform: "windows" },
    voices: [],
  };

  const CAPTION_SCALE = { SMALL: 0.9, MEDIUM: 1, LARGE: 1.16 };

  function emit(reason) {
    listeners.forEach((fn) => {
      try {
        fn(state.settings, reason);
      } catch (e) {
        console.error(e);
      }
    });
  }

  function applyAppearance() {
    const s = state.settings;
    if (!s) return;
    const dark =
      s.themeMode === "DARK" ||
      (s.themeMode === "SYSTEM" &&
        window.matchMedia("(prefers-color-scheme: dark)").matches);
    document.documentElement.dataset.theme = dark ? "dark" : "light";
    document.documentElement.dataset.animations = s.animations ? "on" : "off";
    document.documentElement.style.setProperty(
      "--caption-scale",
      CAPTION_SCALE[s.captionSize] || 1
    );
    SP.i18n.setLang(s.language || "zh");

    // Keep the native window chrome in sync with the in-app theme.
    if (SP.api.native && window.__TAURI__.window) {
      try {
        const theme = dark ? "dark" : "light";
        Promise.resolve(window.__TAURI__.window.getCurrentWindow().setTheme(theme)).catch(
          () => {}
        );
      } catch (_) {}
    }
  }

  /** Persist a partial settings patch; resolves once written. */
  async function patch(partial, reason) {
    const next = Object.assign({}, state.settings, partial);
    state.settings = next;
    applyAppearance();
    emit(reason || "update");
    try {
      await SP.api.saveSettings(next);
    } catch (e) {
      console.error("settings save failed", e);
    }
    return next;
  }

  /** Persist the whole object (used by screens that own a draft copy). */
  async function replace(next, reason) {
    state.settings = next;
    applyAppearance();
    emit(reason || "replace");
    try {
      await SP.api.saveSettings(next);
    } catch (e) {
      console.error("settings save failed", e);
    }
    return next;
  }

  async function init() {
    const tasks = [SP.api.getSettings(), SP.api.info(), SP.api.voices()];
    const [settings, info, voices] = await Promise.all(
      tasks.map((p) => p.catch(() => null))
    );
    state.settings = settings || {};
    if (info) state.info = info;
    if (voices && voices.length) state.voices = voices;

    // Defaults for keys a older settings file may not have.
    const defaults = {
      themeMode: "SYSTEM",
      captionSize: "MEDIUM",
      language: "zh",
      animations: true,
      showWaveform: true,
      micGain: 1,
      autoReconnect: true,
      bargeIn: true,
      autoCheckUpdate: true,
      updateProxy: true,
      vadMode: "AUTO",
      vadThreshold: 0.5,
      silenceMs: 600,
      pracLang: "en",
      pracLevel: "B1-B2",
      pracCorrection: "SOMETIMES",
      pracReport: true,
      chatSpeakLang: "auto",
      translateLang: "zh",
      inputDeviceId: "",
      outputDeviceId: "",
      voice: "Tina",
      cloneEndpoint:
        "https://cn-beijing.maas.qianwenaiapi.com/api/v1/services/audio/tts/customization",
      clonedVoices: [],
    };
    Object.keys(defaults).forEach((key) => {
      if (state.settings[key] === undefined || state.settings[key] === null) {
        state.settings[key] = defaults[key];
      }
    });

    applyAppearance();
    emit("init");

    // Follow the OS theme live while "system" is selected.
    window
      .matchMedia("(prefers-color-scheme: dark)")
      .addEventListener("change", () => {
        if (state.settings.themeMode === "SYSTEM") {
          applyAppearance();
          emit("theme");
        }
      });

    return state.settings;
  }

  function subscribe(fn) {
    listeners.push(fn);
    return () => {
      const i = listeners.indexOf(fn);
      if (i >= 0) listeners.splice(i, 1);
    };
  }

  function isDark() {
    return document.documentElement.dataset.theme === "dark";
  }

  window.SP = window.SP || {};
  SP.store = {
    state,
    init,
    patch,
    replace,
    subscribe,
    isDark,
    get settings() {
      return state.settings;
    },
    get info() {
      return state.info;
    },
    get voices() {
      return state.voices;
    },
    t,
  };
})();
