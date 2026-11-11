/* Session screen: full-window immersive call UI (orb + live captions on the left, transcript on the
   right), plus the end-of-session report dialog. */
(function () {
  const { icon, el, esc, fmtClock, fmtTime } = SP.ui;
  const t = (k, v) => SP.i18n.t(k, v);

  let host = null;
  let orb = null;
  let unsubscribe = null;
  let onExitCb = null;
  let rendered = new Set();
  let liveUserNode = null;
  let liveAgentNode = null;
  let atBottom = true;
  let ending = false;
  let reportShown = false;

  const STATUS_TEXT = {
    CONNECTING: "sess.connecting",
    CONNECTED: "sess.connected",
    RECONNECTING: "sess.reconnecting",
    ENDED: "sess.ended",
    FAILED: "sess.failed",
    IDLE: "sess.connecting",
  };

  function statusDotClass(status) {
    if (status === "CONNECTED") return "live";
    if (status === "CONNECTING" || status === "RECONNECTING") return "warn";
    if (status === "FAILED") return "err";
    return "";
  }

  function agentText(agentState) {
    if (agentState === "THINKING") return "sess.thinking";
    if (agentState === "SPEAKING") return "sess.speaking";
    return "sess.listening";
  }

  function bubble(m) {
    const isUser = m.role === "USER";
    const isSystem = m.role === "SYSTEM";
    if (isSystem) {
      return `<div class="msg system" data-id="${esc(m.id)}">
        <div class="msg-body"><div class="msg-text">${esc(m.text)}</div></div>
      </div>`;
    }
    return `<div class="msg ${isUser ? "user" : "agent"}" data-id="${esc(m.id)}">
      <div class="msg-avatar">${icon(isUser ? "user" : "bot", 15)}</div>
      <div class="msg-body">
        <div class="msg-text">${esc(m.text)}</div>
        ${m.translation ? `<div class="msg-translation">${esc(m.translation)}</div>` : ""}
        <div class="msg-time">${esc(fmtTime(m.ts))}</div>
      </div>
    </div>`;
  }

  function liveBubble(role) {
    const node = el(`<div class="msg live ${role === "USER" ? "user" : "agent"}">
      <div class="msg-avatar">${icon(role === "USER" ? "user" : "bot", 15)}</div>
      <div class="msg-body">
        <div class="msg-text"><span class="live-text"></span></div>
      </div>
    </div>`);
    return node;
  }

  // ------------------------------------------------------------------ render

  function mountShell() {
    host = document.getElementById("session-root");
    host.innerHTML = `
      <div class="session" id="session-shell">
        <div class="stage">
          <div class="stage-top">
            <span class="pill neutral" id="s-mode"></span>
            <span class="status-pill"><span class="status-dot" id="s-dot"></span><span id="s-status"></span></span>
            <div class="header-spacer grow"></div>
            <span class="stage-timer" id="s-timer">00:00</span>
            <span class="pill neutral" id="s-turns"></span>
          </div>

          <div class="orb-wrap">
            <canvas id="orb"></canvas>
            <div class="orb-caption" id="s-agent"></div>
            <div class="live-caption" id="s-live"></div>
          </div>

          <div class="meter" id="s-meter"></div>

          <div id="s-error"></div>

          <div class="controls" id="s-controls"></div>
        </div>

        <div class="panel">
          <div class="panel-head">
            <span class="panel-title">${esc(t("sess.transcript"))}</span>
            <span class="pill neutral" id="s-count">0</span>
            <div class="grow"></div>
            <button class="btn btn-sm btn-ghost" id="s-close">${icon("x", 15)}<span>${esc(
              t("sess.end")
            )}</span></button>
          </div>
          <div class="transcript" id="s-transcript">
            <div class="empty" id="s-empty">
              <div class="empty-art">${icon("message", 26)}</div>
              <div class="empty-title">${esc(t("sess.empty"))}</div>
              <div class="empty-sub">${esc(t("sess.empty_sub"))}</div>
            </div>
          </div>
          <div class="composer">
            <input class="input grow" id="s-input" placeholder="${esc(
              t("sess.placeholder")
            )}" />
            <button class="btn btn-primary btn-icon" id="s-send" title="${esc(
              t("sess.send")
            )}">${icon("send", 17)}</button>
          </div>
        </div>
      </div>`;

    const canvas = host.querySelector("#orb");
    orb = SP.orb.create(canvas);

    // meter bars
    const meter = host.querySelector("#s-meter");
    meter.innerHTML = Array.from({ length: 26 })
      .map(() => '<div class="meter-bar" style="height:3px"></div>')
      .join("");

    host.querySelector("#s-close").addEventListener("click", () => requestExit());

    const input = host.querySelector("#s-input");
    const send = () => {
      const value = input.value;
      if (!value.trim()) return;
      if (SP.session.sendText(value)) input.value = "";
    };
    host.querySelector("#s-send").addEventListener("click", send);
    input.addEventListener("keydown", (e) => {
      if (e.key === "Enter" && !e.shiftKey) {
        e.preventDefault();
        send();
      }
    });

    const transcript = host.querySelector("#s-transcript");
    transcript.addEventListener("scroll", () => {
      atBottom =
        transcript.scrollHeight - transcript.scrollTop - transcript.clientHeight < 60;
      const jump = host.querySelector(".jump");
      if (jump) jump.classList.toggle("hidden", atBottom);
    });

    // push-to-talk stays available while PTT mode is selected
    renderControls(SP.session.ui);
    renderTranscript(SP.session.ui, true);
  }

  function renderControls(ui) {
    const wrap = host.querySelector("#s-controls");
    if (!wrap) return;
    const settings = SP.store.settings;
    const ptt = settings.vadMode === "PTT";
    const active = ui.status === "CONNECTED";

    wrap.innerHTML = `
      <button class="ctl ${ui.micMuted ? "danger" : "active"}" id="c-mute" title="${esc(
        t(ui.micMuted ? "sess.unmute" : "sess.mute")
      )}" ${active ? "" : "disabled"}>
        ${icon(ui.micMuted ? "volumeOff" : "mic", 21)}
      </button>
      <button class="ctl" id="c-output" title="${esc(t("sess.output"))}">${icon(
        "headphones",
        21
      )}</button>
      <button class="ctl danger" id="c-end" title="${esc(t("sess.end"))}">${icon(
        "hangup",
        21
      )}</button>
      ${
        ptt
          ? `<button class="ptt ${ui.pttActive ? "down" : ""}" id="c-ptt" title="${esc(
              t("sess.ptt_hold")
            )}">${icon("mic", 30)}</button>`
          : ""
      }
      <button class="ctl wide" id="c-input" title="${esc(t("sess.keyboard"))}">${icon(
        "keyboard",
        17
      )}<span>${esc(t("sess.keyboard"))}</span></button>`;

    wrap.querySelector("#c-mute").addEventListener("click", () => {
      SP.session.toggleMute();
    });
    wrap.querySelector("#c-end").addEventListener("click", () => requestExit());
    wrap.querySelector("#c-input").addEventListener("click", () => {
      host.querySelector("#s-input").focus();
    });
    wrap.querySelector("#c-output").addEventListener("click", openOutputPicker);

    const pttBtn = wrap.querySelector("#c-ptt");
    if (pttBtn) {
      const down = (e) => {
        e.preventDefault();
        SP.session.pttDown();
      };
      const up = (e) => {
        e.preventDefault();
        SP.session.pttUp();
      };
      pttBtn.addEventListener("mousedown", down);
      pttBtn.addEventListener("mouseup", up);
      pttBtn.addEventListener("mouseleave", up);
      pttBtn.addEventListener("touchstart", down, { passive: false });
      pttBtn.addEventListener("touchend", up, { passive: false });
    }

    // Space bar push-to-talk
    wrap.dataset.ptt = ptt ? "1" : "";
  }

  function openOutputPicker() {
    SP.audio.enumerate().then(({ outputs }) => {
      const current = SP.store.settings.outputDeviceId || "";
      const items = [
        { id: "", label: t("set.device_default") },
        ...outputs.map((d, i) => ({
          id: d.id,
          label: d.label || `输出设备 ${i + 1}`,
        })),
      ];
      const body = el(`<div class="stack" style="gap:2px;padding-bottom:8px"></div>`);
      items.forEach((item) => {
        const row = el(`<button class="switch-row" style="width:100%;text-align:left">
          <div class="switch-text"><div class="switch-title">${esc(item.label)}</div></div>
          <div class="grow"></div>
          ${item.id === current ? icon("check", 17) : ""}
        </button>`);
        row.addEventListener("click", async () => {
          await SP.store.patch({ outputDeviceId: item.id });
          await SP.audio.setSink(item.id);
          SP.ui.toast(t("common.saved"));
        });
        body.appendChild(row);
      });
      SP.ui.openDialog({
        title: t("sess.device_title"),
        body,
        actions: [{ label: t("common.close") }],
      });
    });
  }

  function renderTranscript(ui, force) {
    const transcript = host.querySelector("#s-transcript");
    if (!transcript) return;
    const empty = transcript.querySelector("#s-empty");
    const visible = ui.messages.filter(
      (m) => m.kind !== "normal" || m.role === "USER" || m.role === "ASSISTANT"
    );

    if (visible.length && empty) empty.remove();
    if (!visible.length && !empty && !ui.liveUser && !ui.liveAgent) {
      transcript.innerHTML = `<div class="empty" id="s-empty">
        <div class="empty-art">${icon("message", 26)}</div>
        <div class="empty-title">${esc(t("sess.empty"))}</div>
        <div class="empty-sub">${esc(t("sess.empty_sub"))}</div>
      </div>`;
      return;
    }

    // Append only what is new so entrance animations play once.
    visible.forEach((m) => {
      if (rendered.has(m.id)) {
        const existing = transcript.querySelector(`[data-id="${m.id}"]`);
        if (existing && m.translation) {
          let tr = existing.querySelector(".msg-translation");
          if (!tr) {
            tr = el(`<div class="msg-translation"></div>`);
            existing.querySelector(".msg-body").appendChild(tr);
          }
          tr.textContent = m.translation;
        }
        return;
      }
      rendered.add(m.id);
      transcript.appendChild(el(bubble(m)));
      if (atBottom) scrollToBottom();
    });

    // live bubbles
    const wantUser = !!ui.liveUser;
    const wantAgent = !!ui.liveAgent || ui.agentState === "THINKING";

    if (wantUser) {
      if (!liveUserNode) {
        liveUserNode = liveBubble("USER");
        transcript.appendChild(liveUserNode);
      }
      liveUserNode.querySelector(".live-text").textContent = ui.liveUser;
    } else if (liveUserNode) {
      liveUserNode.remove();
      liveUserNode = null;
    }

    if (wantAgent) {
      if (!liveAgentNode) {
        liveAgentNode = liveBubble("ASSISTANT");
        transcript.appendChild(liveAgentNode);
      }
      const textEl = liveAgentNode.querySelector(".live-text");
      if (ui.liveAgent) {
        textEl.textContent = ui.liveAgent;
      } else {
        textEl.innerHTML =
          '<span class="thinking"><i></i><i></i><i></i></span>';
      }
    } else if (liveAgentNode) {
      liveAgentNode.remove();
      liveAgentNode = null;
    }

    if (atBottom) scrollToBottom();
  }

  function scrollToBottom() {
    const transcript = host && host.querySelector("#s-transcript");
    if (transcript) transcript.scrollTop = transcript.scrollHeight;
  }

  function renderState(ui) {
    if (!host) return;
    const shell = host.querySelector("#session-shell");
    if (!shell) return;

    const modeEl = host.querySelector("#s-mode");
    if (modeEl)
      modeEl.textContent = t(
        SP.session.config && SP.session.config.mode === "PRACTICE"
          ? "sess.mode_practice"
          : "sess.mode_chat"
      );

    const dot = host.querySelector("#s-dot");
    if (dot) dot.className = "status-dot " + statusDotClass(ui.status);
    const statusEl = host.querySelector("#s-status");
    if (statusEl) statusEl.textContent = t(STATUS_TEXT[ui.status] || "sess.connecting");

    const timer = host.querySelector("#s-timer");
    if (timer) timer.textContent = fmtClock(ui.elapsedSec);

    const turns = host.querySelector("#s-turns");
    if (turns) turns.textContent = t("sess.turns", { n: ui.userTurns });

    const count = host.querySelector("#s-count");
    if (count) count.textContent = ui.messages.length;

    const agentEl = host.querySelector("#s-agent");
    if (agentEl) {
      agentEl.textContent =
        ui.status === "CONNECTED" ? t(agentText(ui.agentState)) : "";
    }

    const live = host.querySelector("#s-live");
    if (live) {
      const text = ui.liveUser || "";
      live.textContent = text;
      live.classList.toggle("agent", false);
      live.style.opacity = text ? "1" : "0";
    }

    // level meter
    const meter = host.querySelector("#s-meter");
    if (meter) {
      const settings = SP.store.settings;
      const visible = settings.showWaveform && ui.status === "CONNECTED";
      meter.style.visibility = visible ? "visible" : "hidden";
      const bars = meter.children;
      const n = bars.length;
      for (let i = 0; i < n; i++) {
        const center = 1 - Math.abs(i - (n - 1) / 2) / ((n - 1) / 2);
        const h =
          3 + ui.micLevel * 30 * (0.35 + center * 0.95) * (0.6 + Math.random() * 0.6);
        bars[i].style.height = Math.min(32, h).toFixed(1) + "px";
      }
      meter.classList.toggle("hot", ui.micLevel > 0.72);
    }

    // error banner
    const errEl = host.querySelector("#s-error");
    if (errEl) {
      if (ui.error) {
        errEl.innerHTML = `<div class="banner">${icon("alert", 15)}<span>${esc(
          ui.error
        )}</span>
          <button class="btn btn-sm" id="s-retry">${esc(t("common.retry"))}</button>
          <button class="btn btn-sm btn-ghost" id="s-dismiss">${icon("x", 14)}</button>
        </div>`;
        errEl.querySelector("#s-retry").addEventListener("click", () => {
          SP.session.retry();
        });
        errEl.querySelector("#s-dismiss").addEventListener("click", () => {
          SP.session.dismissError();
        });
      } else {
        errEl.innerHTML = "";
      }
    }

    // orb
    if (orb) {
      orb.setState(SP.orb.stateOf(ui.status, ui.agentState));
      orb.setLevel(ui.status === "CONNECTED" ? ui.micLevel : 0);
    }

    // controls reflect mute / ptt state without rebuilding on every frame
    const muteBtn = host.querySelector("#c-mute");
    if (muteBtn) {
      muteBtn.classList.toggle("danger", ui.micMuted);
      muteBtn.classList.toggle("active", !ui.micMuted);
      muteBtn.innerHTML = icon(ui.micMuted ? "volumeOff" : "mic", 21);
    }
    const pttBtn = host.querySelector("#c-ptt");
    if (pttBtn) pttBtn.classList.toggle("down", !!ui.pttActive);
  }

  // ------------------------------------------------------------------ exit & report

  async function requestExit() {
    if (ending) return;
    const ui = SP.session.ui;
    const active = ["CONNECTING", "CONNECTED", "RECONNECTING"].includes(ui.status);
    if (active) {
      const ok = await SP.ui.confirm({
        title: t("sess.exit_title"),
        body: t("sess.exit_body"),
        confirmLabel: t("sess.end"),
        cancelLabel: t("common.cancel"),
        danger: true,
      });
      if (!ok) return;
    }
    await finish(true);
  }

  async function finish(generateReport) {
    ending = true;
    try {
      await SP.session.endSession(generateReport);
    } catch (e) {
      console.error(e);
    }
    const ui = SP.session.ui;
    if (ui.reportGenerating || ui.report) {
      showReport(ui);
    } else {
      close();
    }
  }

  function showReport(ui) {
    const hostEl = document.getElementById("dialog-root");
    const scrim = el(`<div class="scrim"></div>`);
    const dialog = el(`<div class="dialog wide">
      <div class="dialog-head">
        <div class="dialog-title">${esc(t("sess.report_title"))}</div>
        <div style="margin-left:auto" id="rep-actions"></div>
      </div>
      <div class="dialog-body" id="rep-body"></div>
      <div class="dialog-foot">
        <button class="btn" id="rep-copy">${icon("copy", 15)}<span>${esc(
      t("common.copy")
    )}</span></button>
        <button class="btn" id="rep-regen">${icon("refresh", 15)}<span>${esc(
      t("sess.regenerate")
    )}</span></button>
        <button class="btn btn-primary" id="rep-close">${esc(t("common.close"))}</button>
      </div>
    </div>`);
    scrim.appendChild(dialog);
    hostEl.appendChild(scrim);
    reportShown = true;

    const body = dialog.querySelector("#rep-body");
    const copyBtn = dialog.querySelector("#rep-copy");
    const regenBtn = dialog.querySelector("#rep-regen");

    function paint(state) {
      if (state.reportGenerating) {
        body.innerHTML = `<div class="row" style="padding:22px 0;justify-content:center">
          <div class="spinner"></div>
          <span class="muted small">${esc(t("sess.report_generating"))}</span>
        </div>`;
        copyBtn.disabled = true;
        regenBtn.disabled = true;
        return;
      }
      if (state.report) {
        body.innerHTML = `<div class="md">${SP.markdown.render(state.report)}</div>`;
        copyBtn.disabled = false;
        regenBtn.disabled = false;
      } else {
        body.innerHTML = `<div class="empty"><div class="empty-title">${esc(
          t("sess.report_failed")
        )}</div></div>`;
        regenBtn.disabled = true;
      }
    }

    paint(ui);

    const off = SP.session.onChange((next) => {
      if (reportShown) paint(next);
    });

    copyBtn.addEventListener("click", async () => {
      const text = SP.session.ui.report || "";
      try {
        await navigator.clipboard.writeText(text);
        SP.ui.toast(t("common.copied"));
      } catch (_) {
        SP.ui.toast(String(_), "error");
      }
    });
    regenBtn.addEventListener("click", () => {
      SP.session.regenerateReport();
    });
    dialog.querySelector("#rep-close").addEventListener("click", () => {
      off();
      close();
    });
  }

  function close() {
    if (unsubscribe) {
      unsubscribe();
      unsubscribe = null;
    }
    if (orb) {
      orb.stop();
      orb = null;
    }
    SP.audio.stop();
    SP.rt.close();
    rendered.clear();
    liveUserNode = null;
    liveAgentNode = null;
    reportShown = false;
    ending = false;
    atBottom = true;
    if (host) host.innerHTML = "";
    const cb = onExitCb;
    onExitCb = null;
    if (cb) cb();
  }

  // ------------------------------------------------------------------ public

  function open(cfg, onExit) {
    onExitCb = onExit || (() => SP.app.navigate("home"));
    mountShell();

    unsubscribe = SP.session.onChange((ui) => {
      renderState(ui);
      renderTranscript(ui);
      // controls only need rebuilding when the device list or PTT mode changes
      const ptt = SP.store.settings.vadMode === "PTT";
      const hasPtt = !!host.querySelector("#c-ptt");
      if (ptt !== hasPtt) renderControls(ui);
    });

    SP.session.start(cfg);
    setTimeout(() => {
      const input = host && host.querySelector("#s-input");
      if (input) input.focus();
    }, 260);
  }

  function prime() {
    // Nothing to pre-warm yet; kept so app boot has a stable hook.
  }

  SP.screens.session = { open, close, prime };
})();
