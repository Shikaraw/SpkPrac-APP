/* Settings: every option the Android build exposes, plus Windows audio device routing. */
(function () {
  const { icon, el, esc, toast } = SP.ui;
  const t = (k, v) => SP.i18n.t(k, v);

  const LEVELS_KIND = null;

  function section(titleKey, iconName, inner) {
    return `<section class="card">
      <div class="card-title">${icon(iconName, 15)}<span>${esc(t(titleKey))}</span></div>
      <div class="stack" style="gap:0">${inner}</div>
    </section>`;
  }

  function row(titleKey, subKey, control) {
    return `<div class="setting-row">
      <div class="setting-main">
        <div class="setting-title">${esc(t(titleKey))}</div>
        ${subKey ? `<div class="setting-sub">${esc(t(subKey))}</div>` : ""}
      </div>
      <div class="setting-control">${control}</div>
    </div>`;
  }

  function rowWide(titleKey, subKey, control) {
    return `<div class="setting-row">
      <div class="setting-main">
        <div class="setting-title">${esc(t(titleKey))}</div>
        ${subKey ? `<div class="setting-sub">${esc(t(subKey))}</div>` : ""}
      </div>
      <div class="setting-control wide">${control}</div>
    </div>`;
  }

  function switchRow(id, titleKey, subKey, checked) {
    return `<div class="switch-row" id="${id}-row">
      <div class="switch-text">
        <div class="switch-title">${esc(t(titleKey))}</div>
        ${subKey ? `<div class="switch-sub">${esc(t(subKey))}</div>` : ""}
      </div>
      <div class="grow"></div>
      <div class="switch ${checked ? "on" : ""}" id="${id}"></div>
    </div>`;
  }

  function segmented(id, items, active) {
    return `<div class="segmented" id="${id}">
      ${items
        .map(
          ([value, label]) =>
            `<button class="segmented-item ${
              String(value) === String(active) ? "active" : ""
            }" data-value="${esc(value)}">${esc(label)}</button>`
        )
        .join("")}
    </div>`;
  }

  function options(list, active) {
    return list
      .map(
        ([value, label]) =>
          `<option value="${esc(value)}" ${String(active) === String(value) ? "selected" : ""}>${esc(
            label
          )}</option>`
      )
      .join("");
  }

  // The preset dropdown only exists for this model (spec §5.1).
  const REALTIME_MODEL = "qwen3.8-omni-flash-realtime";

  /** Preset dropdown content: system presets + this model's approved cloned voices. */
  function voiceSelectOptions(current, mine) {
    const presets = SP.voice.presets().map((v) => [v, v]);
    const known =
      presets.some((pair) => pair[0] === current) ||
      mine.some((pair) => pair[0] === current);
    const systemGroup = `<optgroup label="${esc(t("voice.group_system"))}">${options(
      presets,
      current
    )}</optgroup>`;
    const mineGroup = mine.length
      ? `<optgroup label="${esc(t("voice.group_mine"))}">${options(mine, current)}</optgroup>`
      : "";
    const custom = `<option value="__custom__" ${known ? "" : "selected"}>${esc(
      t("voice.custom")
    )}</option>`;
    return systemGroup + mineGroup + custom;
  }

  /** Spec §5.1: preset dropdown (only for the realtime model) + free input + manage button. */
  function voiceBlock(s) {
    const isRealtime = s.rtModel === REALTIME_MODEL;
    const current = String(s.voice || "").trim() || "Tina";
    let select = "";
    if (isRealtime) {
      const mine = SP.voice
        .usableVoices(REALTIME_MODEL)
        .map((v) => [v.voiceId, v.name || v.voiceId]);
      select = `<select class="select" id="sel-voice">${voiceSelectOptions(
        current,
        mine
      )}</select>`;
    }
    return `<div class="stack" style="gap:8px">
      ${select}
      <div class="row" style="gap:8px">
        <input class="input grow" id="in-voice" value="${esc(current)}" placeholder="Tina" />
        <button class="btn btn-sm" id="btn-manage-voice">${icon("mic", 14)}<span>${esc(
      t("voice.manage")
    )}</span></button>
      </div>
      ${
        isRealtime
          ? ""
          : `<div class="setting-sub">${esc(t("voice.only_omni_hint"))}</div>`
      }
    </div>`;
  }

  function bindSwitch(root, id, get, set) {
    const sw = root.querySelector("#" + id);
    const rowEl = root.querySelector("#" + id + "-row");
    if (!sw || !rowEl) return;
    const toggle = () => {
      const next = !get();
      set(next);
      sw.classList.toggle("on", next);
    };
    rowEl.addEventListener("click", toggle);
    sw.addEventListener("click", (e) => {
      e.stopPropagation();
      toggle();
    });
  }

  function bindSegmented(root, id, onPick) {
    const seg = root.querySelector("#" + id);
    if (!seg) return;
    seg.querySelectorAll(".segmented-item").forEach((btn) => {
      btn.addEventListener("click", () => {
        seg.querySelectorAll(".segmented-item").forEach((b) => b.classList.remove("active"));
        btn.classList.add("active");
        onPick(btn.dataset.value);
      });
    });
  }

  async function render() {
    const s = SP.store.settings;
    const info = SP.store.info;

    // Devices are filled in after the first paint: enumerateDevices can be slow (or never settle
    // before a stream exists) and must never block the whole screen.
    const deviceData = { inputs: [], outputs: [] };

    const langOpts = SP.languages.list.map(([code, name]) => [code, name]);
    const asrOpts = [["", t("set.asr_auto")]].concat(
      SP.languages.asrCodes.map((code) => [code, SP.languages.name(code) + " · " + code])
    );
    const speakOpts = [["auto", t("set.asr_auto")]].concat(langOpts);

    const inputOpts = [["", t("set.device_default")]].concat(
      deviceData.inputs.map((d, i) => [d.id, d.label || `麦克风 ${i + 1}`])
    );
    const outputOpts = [["", t("set.device_default")]].concat(
      deviceData.outputs.map((d, i) => [d.id, d.label || `输出设备 ${i + 1}`])
    );

    const content = el(`<div class="settings-grid">

      ${section(
        "set.sec_api",
        "key",
        rowWide(
          "set.api_key",
          "set.api_key_desc",
          `<div class="row" style="gap:6px">
            <input class="input grow" id="in-key" type="password" value="${esc(
              s.apiKey || ""
            )}" placeholder="${esc(t("set.api_key_ph"))}" />
            <button class="btn btn-icon btn-ghost" id="btn-key-eye" title="show">${icon(
              "eye",
              16
            )}</button>
          </div>`
        ) +
          row(null, null, `<div class="row" style="gap:8px">
            <span class="test-result" id="test-result"></span>
            <button class="btn btn-sm" id="btn-test">${icon("bolt", 14)}<span>${esc(
            t("set.test")
          )}</span></button>
          </div>`)
      )}

      ${section(
        "set.sec_endpoints",
        "globe",
        rowWide(
          "set.rt_endpoint",
          null,
          `<input class="input" id="in-rt-ep" value="${esc(s.rtEndpoint || "")}" />`
        ) +
          rowWide(
            "set.rt_model",
            null,
            `<input class="input" id="in-rt-model" value="${esc(s.rtModel || "")}" />`
          ) +
          row("set.flavor", null, segmented(
            "seg-flavor",
            [
              ["QWEN", t("set.flavor_qwen")],
              ["OPENAI", t("set.flavor_openai")],
            ],
            s.flavor
          )) +
          rowWide(
            "set.text_endpoint",
            null,
            `<input class="input" id="in-text-ep" value="${esc(s.textEndpoint || "")}" />`
          ) +
          rowWide(
            "set.text_model",
            null,
            `<input class="input" id="in-text-model" value="${esc(s.textModel || "")}" />`
          ) +
          rowWide(
            "set.clone_endpoint",
            "set.clone_endpoint_desc",
            `<input class="input" id="in-clone-ep" value="${esc(
              s.cloneEndpoint || ""
            )}" placeholder="https://…/api/v1/services/audio/tts/customization" />`
          )
      )}

      ${section(
        "set.sec_appearance",
        "sparkles",
        row("set.theme", null, segmented(
          "seg-theme",
          [
            ["SYSTEM", t("set.theme_system")],
            ["LIGHT", t("set.theme_light")],
            ["DARK", t("set.theme_dark")],
          ],
          s.themeMode
        )) +
          row("set.language", null, segmented(
            "seg-lang",
            SP.i18n.languages.map((l) => [l.id, l.name]),
            s.language
          )) +
          row("set.caption_size", null, segmented(
            "seg-caption",
            [
              ["SMALL", t("set.size_small")],
              ["MEDIUM", t("set.size_medium")],
              ["LARGE", t("set.size_large")],
            ],
            s.captionSize
          )) +
          switchRow("sw-anim", "set.animations", "set.animations_desc", s.animations) +
          switchRow("sw-wave", "set.show_waveform", "set.show_waveform_desc", s.showWaveform)
      )}

      ${section(
        "set.sec_audio",
        "headphones",
        rowWide("set.input_device", "set.device_desc", `<select class="select" id="sel-input">
          ${options(inputOpts, s.inputDeviceId || "")}
        </select>`) +
          rowWide("set.output_device", null, `<select class="select" id="sel-output">
          ${options(outputOpts, s.outputDeviceId || "")}
        </select>`) +
          `<div class="slider-row">
            <div class="slider-head">
              <span class="setting-title">${esc(t("set.mic_gain"))}</span>
              <span class="slider-value" id="gain-val">${(s.micGain || 1).toFixed(1)}×</span>
            </div>
            <input type="range" id="in-gain" min="0.5" max="3" step="0.1" value="${
              s.micGain || 1
            }" />
            <div class="setting-sub">${esc(t("set.mic_gain_desc"))}</div>
          </div>`
      )}

      ${section(
        "set.sec_behavior",
        "activity",
        row("set.vad_mode", null, segmented(
          "seg-vad",
          [
            ["AUTO", t("set.vad_auto")],
            ["PTT", t("set.vad_ptt")],
          ],
          s.vadMode
        )) +
          `<div class="slider-row">
            <div class="slider-head">
              <span class="setting-title">${esc(t("set.vad_threshold"))}</span>
              <span class="slider-value" id="thr-val">${(s.vadThreshold || 0.5).toFixed(2)}</span>
            </div>
            <input type="range" id="in-thr" min="0.1" max="0.9" step="0.05" value="${
              s.vadThreshold || 0.5
            }" />
          </div>` +
          `<div class="slider-row">
            <div class="slider-head">
              <span class="setting-title">${esc(t("set.silence_ms"))}</span>
              <span class="slider-value" id="sil-val">${s.silenceMs || 600} ms</span>
            </div>
            <input type="range" id="in-sil" min="200" max="1500" step="50" value="${
              s.silenceMs || 600
            }" />
          </div>` +
          switchRow(
            "sw-reconnect",
            "set.auto_reconnect",
            "set.auto_reconnect_desc",
            s.autoReconnect
          ) +
          switchRow("sw-barge", "set.barge_in", "set.barge_in_desc", s.bargeIn) +
          rowWide(
            "set.chat_speak_lang",
            null,
            `<select class="select" id="sel-speak">
              ${options(speakOpts, s.chatSpeakLang || "auto")}
            </select>`
          ) +
          `<div class="setting-row" style="flex-direction:column;align-items:stretch;gap:8px">
            <div class="setting-main">
              <div class="setting-title">${esc(t("set.voice"))}</div>
            </div>
            ${voiceBlock(s)}
          </div>` +
          `<div class="setting-row" style="flex-direction:column;align-items:stretch;gap:8px">
            <div class="setting-main">
              <div class="setting-title">${esc(t("set.chat_prompt"))}</div>
              <div class="setting-sub">${esc(t("set.chat_prompt_desc"))}</div>
            </div>
            <textarea class="textarea" id="in-prompt" rows="4">${esc(
              s.chatPrompt || ""
            )}</textarea>
          </div>`
      )}

      ${section(
        "set.sec_subtitle",
        "message",
        switchRow("sw-sub-ai", "set.subtitle_ai", null, s.subtitleAi) +
          switchRow("sw-sub-user", "set.subtitle_user", null, s.subtitleUser) +
          switchRow(
            "sw-translate",
            "set.translate_enabled",
            "set.translate_enabled_desc",
            s.translateEnabled
          ) +
          rowWide(
            "set.translate_lang",
            null,
            `<select class="select" id="sel-trans-lang">${options(
              langOpts,
              s.translateLang
            )}</select>`
          ) +
          rowWide(
            "set.asr_lang",
            null,
            `<select class="select" id="sel-asr">${options(asrOpts, s.asrLang || "")}</select>`
          )
      )}

      ${section(
        "set.sec_data",
        "folder",
        rowWide("set.storage", null, `<span class="mono faint truncate" id="data-dir" title="${esc(
          info.dataDir
        )}">${esc(info.dataDir)}</span>`) +
          row(
            "set.export_all",
            null,
            `<button class="btn btn-sm" id="btn-export">${icon("download", 14)}<span>${esc(
              t("common.export")
            )}</span></button>`
          ) +
          row(
            null,
            null,
            `<button class="btn btn-sm btn-ghost" id="btn-open-dir">${icon(
              "folder",
              14
            )}<span>${esc(t("set.open_folder"))}</span></button>`
          ) +
          row(
            "set.clear_all",
            null,
            `<button class="btn btn-sm btn-danger" id="btn-clear">${icon("trash", 14)}<span>${esc(
              t("common.clear")
            )}</span></button>`
          ) +
          row(
            "set.clear_key",
            null,
            `<button class="btn btn-sm btn-danger" id="btn-clear-key">${icon(
              "key",
              14
            )}<span>${esc(t("common.clear"))}</span></button>`
          )
      )}

      ${section(
        "set.sec_about",
        "info",
        row(
          "set.version",
          null,
          `<span class="pill neutral" id="ver-pill">v${esc(info.version)}</span>`
        ) +
          row(
            null,
            null,
            `<div class="row" style="gap:8px">
              <span class="test-result" id="upd-result"></span>
              <button class="btn btn-sm" id="btn-check">${icon(
                "refresh",
                14
              )}<span>${esc(t("set.check_update"))}</span></button>
            </div>`
          ) +
          switchRow("sw-auto-upd", "set.auto_update", "set.auto_update_desc", s.autoCheckUpdate) +
          switchRow("sw-proxy", "set.use_proxy", "set.use_proxy_desc", s.updateProxy) +
          row(
            "set.repo",
            null,
            `<button class="btn btn-sm btn-ghost" id="btn-repo">${icon(
              "external",
              14
            )}<span>github</span></button>`
          )
      )}

    </div>`);

    // Fill the device selects once enumeration completes.
    SP.audio.enumerate().then(({ inputs, outputs }) => {
      const refresh = (sel, list, prefix) => {
        if (!sel || !list.length) return;
        const current = sel.value;
        sel.innerHTML =
          options([["", t("set.device_default")]].concat(
            list.map((d, i) => [d.id, d.label || prefix + " " + (i + 1)])
          ), current);
      };
      refresh(content.querySelector("#sel-input"), inputs, "麦克风");
      refresh(content.querySelector("#sel-output"), outputs, "输出设备");
    });

    // ---------------------------------------------------------------- wiring
    const save = (patch) => SP.store.patch(patch);
    const debouncedSave = (patchFn) =>
      SP.ui.debounce(() => save(patchFn()), 420);

    // api key
    const keyInput = content.querySelector("#in-key");
    const keySave = debouncedSave(() => ({ apiKey: keyInput.value.trim() }));
    keyInput.addEventListener("input", keySave);
    content.querySelector("#btn-key-eye").addEventListener("click", (e) => {
      const btn = e.currentTarget;
      const hidden = keyInput.type === "password";
      keyInput.type = hidden ? "text" : "password";
      btn.innerHTML = icon(hidden ? "eyeOff" : "eye", 16);
    });

    // test connection (text endpoint proves the key; realtime is probed separately)
    const testBtn = content.querySelector("#btn-test");
    const testResult = content.querySelector("#test-result");
    testBtn.addEventListener("click", async () => {
      testBtn.disabled = true;
      testResult.className = "test-result";
      testResult.innerHTML = `<span class="spinner"></span><span class="faint">${esc(
        t("set.testing")
      )}</span>`;
      const cur = SP.store.settings;
      try {
        await SP.api.complete({
          endpoint: cur.textEndpoint,
          apiKey: cur.apiKey,
          model: cur.textModel,
          messages: [["user", "ping"]],
          temperature: 0,
          maxTokens: 4,
        });
        testResult.className = "test-result ok";
        testResult.innerHTML = `${icon("check", 14)}<span>${esc(t("set.test_ok"))}</span>`;
      } catch (e) {
        testResult.className = "test-result bad";
        const msg = String((e && e.message) || e).slice(0, 60);
        testResult.innerHTML = `${icon("alert", 14)}<span>${esc(
          t("set.test_fail")
        )}: ${esc(msg)}</span>`;
      } finally {
        testBtn.disabled = false;
      }
    });

    // endpoints
    const bindInput = (id, key) => {
      const input = content.querySelector("#" + id);
      input.addEventListener(
        "input",
        debouncedSave(() => ({ [key]: input.value.trim() }))
      );
    };
    bindInput("in-rt-ep", "rtEndpoint");
    bindInput("in-rt-model", "rtModel");
    bindInput("in-text-ep", "textEndpoint");
    bindInput("in-text-model", "textModel");
    bindInput("in-clone-ep", "cloneEndpoint");

    // The preset dropdown only exists for the realtime model: re-render when that flips.
    const rtModelInput = content.querySelector("#in-rt-model");
    rtModelInput.addEventListener("change", () => {
      const value = rtModelInput.value.trim();
      const wasRealtime = SP.store.settings.rtModel === REALTIME_MODEL;
      const isRealtime = value === REALTIME_MODEL;
      save({ rtModel: value });
      if (wasRealtime !== isRealtime) {
        setTimeout(() => SP.app.navigate("settings"), 80);
      }
    });

    bindSegmented(content, "seg-flavor", (v) => save({ flavor: v }));
    bindSegmented(content, "seg-theme", (v) => save({ themeMode: v }));
    bindSegmented(content, "seg-lang", (v) => {
      save({ language: v });
      SP.i18n.setLang(v);
      // copy is resolved at render time, so re-render the whole page
      setTimeout(() => SP.app.navigate("settings"), 60);
    });
    bindSegmented(content, "seg-caption", (v) => save({ captionSize: v }));
    bindSegmented(content, "seg-vad", (v) => save({ vadMode: v }));

    bindSwitch(
      content,
      "sw-anim",
      () => SP.store.settings.animations,
      (v) => save({ animations: v })
    );
    bindSwitch(
      content,
      "sw-wave",
      () => SP.store.settings.showWaveform,
      (v) => save({ showWaveform: v })
    );
    bindSwitch(
      content,
      "sw-reconnect",
      () => SP.store.settings.autoReconnect,
      (v) => save({ autoReconnect: v })
    );
    bindSwitch(
      content,
      "sw-barge",
      () => SP.store.settings.bargeIn,
      (v) => save({ bargeIn: v })
    );
    bindSwitch(
      content,
      "sw-sub-ai",
      () => SP.store.settings.subtitleAi,
      (v) => save({ subtitleAi: v })
    );
    bindSwitch(
      content,
      "sw-sub-user",
      () => SP.store.settings.subtitleUser,
      (v) => save({ subtitleUser: v })
    );
    bindSwitch(
      content,
      "sw-translate",
      () => SP.store.settings.translateEnabled,
      (v) => save({ translateEnabled: v })
    );
    bindSwitch(
      content,
      "sw-auto-upd",
      () => SP.store.settings.autoCheckUpdate,
      (v) => save({ autoCheckUpdate: v })
    );
    bindSwitch(
      content,
      "sw-proxy",
      () => SP.store.settings.updateProxy,
      (v) => save({ updateProxy: v })
    );

    // devices
    content.querySelector("#sel-input").addEventListener("change", (e) => {
      save({ inputDeviceId: e.target.value });
      toast(t("common.saved"));
    });
    content.querySelector("#sel-output").addEventListener("change", async (e) => {
      save({ outputDeviceId: e.target.value });
      await SP.audio.setSink(e.target.value);
      toast(t("common.saved"));
    });

    // sliders (persist once the drag ends, never per frame)
    const gain = content.querySelector("#in-gain");
    const gainVal = content.querySelector("#gain-val");
    gain.addEventListener("input", () => {
      gainVal.textContent = Number(gain.value).toFixed(1) + "×";
    });
    gain.addEventListener("change", () => {
      save({ micGain: Number(gain.value) });
      SP.audio.setGain(Number(gain.value));
    });

    const thr = content.querySelector("#in-thr");
    const thrVal = content.querySelector("#thr-val");
    thr.addEventListener("input", () => {
      thrVal.textContent = Number(thr.value).toFixed(2);
    });
    thr.addEventListener("change", () => save({ vadThreshold: Number(thr.value) }));

    const sil = content.querySelector("#in-sil");
    const silVal = content.querySelector("#sil-val");
    sil.addEventListener("input", () => {
      silVal.textContent = sil.value + " ms";
    });
    sil.addEventListener("change", () => save({ silenceMs: Number(sil.value) }));

    // selects
    content.querySelector("#sel-speak").addEventListener("change", (e) => {
      save({ chatSpeakLang: e.target.value });
    });
    content.querySelector("#sel-trans-lang").addEventListener("change", (e) => {
      save({ translateLang: e.target.value });
    });
    content.querySelector("#sel-asr").addEventListener("change", (e) => {
      save({ asrLang: e.target.value });
    });

    const voiceSel = content.querySelector("#sel-voice");
    const voiceInput = content.querySelector("#in-voice");
    const manageBtn = content.querySelector("#btn-manage-voice");
    const knownVoice = (value) =>
      !!voiceSel &&
      Array.from(voiceSel.options).some(
        (o) => o.value === value && o.value !== "__custom__"
      );
    const syncVoiceSelect = () => {
      if (voiceSel) {
        voiceSel.value = knownVoice(voiceInput.value)
          ? voiceInput.value
          : "__custom__";
      }
    };
    if (manageBtn) {
      manageBtn.addEventListener("click", () => SP.app.navigate("voices"));
    }
    if (voiceSel) {
      voiceSel.addEventListener("change", () => {
        const value = voiceSel.value;
        if (value === "__custom__") {
          // «自定义» never clears the input — it only lets the user type.
          voiceInput.focus();
          return;
        }
        voiceInput.value = value;
        save({ voice: value });
      });
    }
    voiceInput.addEventListener("input", syncVoiceSelect);
    voiceInput.addEventListener(
      "input",
      debouncedSave(() => ({ voice: voiceInput.value.trim() }))
    );
    voiceInput.addEventListener("change", () => {
      const value = voiceInput.value.trim();
      if (!value) {
        // Spec §5.1: an empty selection falls back to the default preset.
        voiceInput.value = "Tina";
        if (voiceSel) voiceSel.value = "Tina";
        save({ voice: "Tina" });
        return;
      }
      syncVoiceSelect();
      save({ voice: value });
    });

    const promptInput = content.querySelector("#in-prompt");
    promptInput.addEventListener(
      "input",
      debouncedSave(() => ({ chatPrompt: promptInput.value }))
    );

    // data
    content.querySelector("#btn-export").addEventListener("click", async () => {
      try {
        const text = await SP.api.exportAll();
        const path = await SP.api.writeExport(
          `SpkPrac-export-${new Date().toISOString().slice(0, 10)}.md`,
          text
        );
        toast(t("set.exported_all", { path }));
      } catch (e) {
        toast(String(e), "error");
      }
    });
    content.querySelector("#btn-open-dir").addEventListener("click", () => {
      SP.api.openDir(SP.store.info.dataDir).catch((e) => toast(String(e), "error"));
    });
    content.querySelector("#btn-clear").addEventListener("click", async () => {
      const ok = await SP.ui.confirm({
        title: t("set.clear_all_title"),
        body: t("set.clear_all_body"),
        confirmLabel: t("common.delete"),
        cancelLabel: t("common.cancel"),
        danger: true,
      });
      if (!ok) return;
      await SP.api.deleteAllSessions();
      toast(t("set.cleared"));
    });
    content.querySelector("#btn-clear-key").addEventListener("click", async () => {
      const ok = await SP.ui.confirm({
        title: t("set.clear_key_title"),
        body: t("set.clear_key_body"),
        confirmLabel: t("common.clear"),
        cancelLabel: t("common.cancel"),
        danger: true,
      });
      if (!ok) return;
      keyInput.value = "";
      await save({ apiKey: "" });
      toast(t("set.cleared"));
    });

    // updates
    const checkBtn = content.querySelector("#btn-check");
    const updResult = content.querySelector("#upd-result");
    checkBtn.addEventListener("click", async () => {
      checkBtn.disabled = true;
      updResult.className = "test-result";
      updResult.innerHTML = `<span class="spinner"></span><span class="faint">${esc(
        t("set.checking")
      )}</span>`;
      try {
        const result = await SP.api.checkUpdate(SP.store.settings.updateProxy);
        if (result.kind === "available") {
          updResult.innerHTML = "";
          showUpdate(result.info);
        } else if (result.kind === "uptodate") {
          updResult.className = "test-result ok";
          updResult.innerHTML = `${icon("check", 14)}<span>${esc(t("set.up_to_date"))}</span>`;
        } else {
          updResult.className = "test-result bad";
          updResult.innerHTML = `${icon("alert", 14)}<span>${esc(t("set.check_failed"))}</span>`;
        }
      } catch (e) {
        updResult.className = "test-result bad";
        updResult.innerHTML = `${icon("alert", 14)}<span>${esc(t("set.check_failed"))}</span>`;
      } finally {
        checkBtn.disabled = false;
        save({ lastUpdateCheckAt: Date.now() });
      }
    });
    content.querySelector("#btn-repo").addEventListener("click", () => {
      SP.api.openExternal("https://github.com/Shikaraw/SpkPrac-APP");
    });

    return {
      titleKey: "set.title",
      subKey: null,
      content,
    };
  }

  // ------------------------------------------------------------------ update dialog
  function showUpdate(info) {
    const body = el(`<div class="stack" style="gap:12px;padding-bottom:6px">
      <div class="row">
        <span class="pill neutral">v${esc(SP.store.info.version)}</span>
        <span class="faint">→</span>
        <span class="pill">v${esc(info.version)}</span>
        <div class="grow"></div>
        <span class="tiny faint">${esc(SP.ui.fmtSize(info.assetSize) || "")}</span>
      </div>
      <div>
        <div class="card-title" style="margin-bottom:6px">${esc(t("upd.notes"))}</div>
        <div class="md" style="max-height:300px;overflow:auto">${SP.markdown.render(
          info.notes || ""
        )}</div>
      </div>
    </div>`);

    const hasAsset = !!info.assetUrl;
    SP.ui.openDialog({
      title: t("upd.new_version"),
      body,
      wide: true,
      actions: [
        {
          label: t("upd.open_page"),
          onClick: () => {
            SP.api.openExternal(info.releaseUrl);
          },
        },
        {
          label: hasAsset ? t("upd.download") : t("upd.download"),
          className: "btn-primary",
          icon: "download",
          onClick: async () => {
            if (!hasAsset) {
              SP.api.openExternal(info.releaseUrl);
              toast(t("upd.no_asset"));
              return true;
            }
            const name = info.assetName || `SpkPrac-${info.version}.exe`;
            toast(t("upd.downloading", { v: info.version }));
            try {
              const path = await SP.api.downloadUpdate(
                info.assetUrl,
                name,
                SP.store.settings.updateProxy,
                info.assetSize || null
              );
              toast(t("upd.done", { path }));
              SP.api.revealPath(path).catch(() => {});
            } catch (e) {
              toast(t("upd.failed") + ": " + String(e), "error");
            }
            return true;
          },
        },
        { label: t("upd.later") },
      ],
    });
  }

  SP.screens.settings = { render, showUpdate };
})();
