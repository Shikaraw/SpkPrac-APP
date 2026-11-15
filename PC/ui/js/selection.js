/* Selected-text toolbar: translate / ask AI over the transcript (spec §5.3).
   Only transcript areas are eligible: the live session root and the detail-page bubble list. */
(function () {
  const { icon, el, esc, toast, openDialog } = SP.ui;
  const t = (k, v) => SP.i18n.t(k, v);

  let bar = null;
  let result = null;
  let selectedText = "";
  let busy = false;
  let throttleTimer = null;

  function errText(e) {
    if (!e) return "";
    if (typeof e === "string") return e.slice(0, 240);
    return String(e.message || e.error || e).slice(0, 240);
  }

  function ensureNodes() {
    if (bar) return;
    bar = el(`<div class="sel-bar hidden">
      <button type="button" class="btn btn-sm sel-btn" data-sel-act="translate">${icon(
        "globe",
        15
      )}<span>${esc(t("sel.translate"))}</span></button>
      <button type="button" class="btn btn-sm sel-btn" data-sel-act="ask">${icon(
        "sparkles",
        15
      )}<span>${esc(t("sel.ask"))}</span></button>
    </div>`);
    // mousedown on the toolbar must not collapse the selection.
    bar.addEventListener("mousedown", (e) => e.preventDefault());
    bar.addEventListener("click", (e) => {
      const btn = e.target.closest("[data-sel-act]");
      if (!btn) return;
      e.preventDefault();
      if (btn.getAttribute("data-sel-act") === "translate") translateSelection();
      else askSelection();
    });
    document.body.appendChild(bar);

    result = el(`<div class="sel-result hidden"></div>`);
    document.body.appendChild(result);
  }

  function hideResult() {
    if (!result) return;
    result.classList.add("hidden");
    result.innerHTML = "";
  }

  function hideToolbar() {
    if (bar) bar.classList.add("hidden");
    hideResult();
    selectedText = "";
    busy = false;
  }

  function eligibleNode(node) {
    if (!node) return false;
    const element = node.nodeType === 1 ? node : node.parentElement;
    if (!element || !element.isConnected) return false;
    if (element.closest("input, textarea, [contenteditable='true'], .composer")) return false;
    if (element.closest("#session-root")) return true;
    if (element.closest("#page-body .bubble-list")) return true;
    return false;
  }

  function readSelection() {
    const selection = window.getSelection();
    if (!selection || !selection.rangeCount || selection.isCollapsed) return null;
    if (!eligibleNode(selection.anchorNode) || !eligibleNode(selection.focusNode)) return null;
    const text = String(selection.toString() || "").trim();
    if (!text) return null;
    const range = selection.getRangeAt(0);
    const rect = range.getBoundingClientRect();
    if (!rect || (!rect.width && !rect.height)) return null;
    return { text: text.slice(0, 2000), rect };
  }

  function positionBar(rect) {
    const width = bar.offsetWidth;
    const height = bar.offsetHeight;
    // Spec §5.3: float above the selection's right edge; drop below when there is no room.
    let left = rect.right - width;
    let top = rect.top - height - 10;
    if (top < 8) top = rect.bottom + 10;
    left = Math.max(8, Math.min(left, window.innerWidth - width - 8));
    top = Math.max(8, Math.min(top, window.innerHeight - height - 8));
    bar.style.left = Math.round(left) + "px";
    bar.style.top = Math.round(top) + "px";
  }

  function positionResult() {
    if (!result || !bar) return;
    const width = result.offsetWidth;
    const height = result.offsetHeight;
    const barTop = parseFloat(bar.style.top) || 0;
    const barLeft = parseFloat(bar.style.left) || 0;
    let left = Math.max(8, Math.min(barLeft, window.innerWidth - width - 8));
    let top = barTop + bar.offsetHeight + 8;
    if (top + height > window.innerHeight - 8) top = Math.max(8, barTop - height - 8);
    result.style.left = Math.round(left) + "px";
    result.style.top = Math.round(top) + "px";
  }

  function showToolbar(selection) {
    ensureNodes();
    selectedText = selection.text;
    busy = false;
    hideResult();
    bar.classList.remove("hidden");
    positionBar(selection.rect);
  }

  function evaluateSelection() {
    if (busy) return; // keep the in-flight request/result on screen
    const selection = readSelection();
    if (!selection) {
      hideToolbar();
      return;
    }
    showToolbar(selection);
  }

  function schedule(delay) {
    if (throttleTimer) clearTimeout(throttleTimer);
    throttleTimer = setTimeout(() => {
      throttleTimer = null;
      evaluateSelection();
    }, delay == null ? 70 : delay);
  }

  // ---------------------------------------------------------------- text model helpers

  function textModelConfig() {
    const s = SP.store.settings || {};
    const endpoint = String(s.textEndpoint || "").trim();
    const model = String(s.textModel || "").trim();
    const apiKey = String(s.apiKey || "").trim();
    if (!endpoint || !model || !apiKey) return null;
    return { endpoint, model, apiKey };
  }

  function requireTextModel() {
    const config = textModelConfig();
    if (!config) {
      toast(t("sel.err_no_model"), "error");
      return null;
    }
    return config;
  }

  function uiLanguageName() {
    return SP.i18n.lang === "zh" ? "简体中文" : "English";
  }

  async function copyText(text) {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch (_) {
      try {
        const area = document.createElement("textarea");
        area.value = text;
        area.style.position = "fixed";
        area.style.left = "-9999px";
        document.body.appendChild(area);
        area.select();
        const ok = document.execCommand("copy");
        area.remove();
        return ok;
      } catch (_) {
        return false;
      }
    }
  }

  async function runTextCompletion(messages, maxTokens) {
    const config = textModelConfig();
    const reply = await SP.api.complete({
      endpoint: config.endpoint,
      apiKey: config.apiKey,
      model: config.model,
      messages: messages,
      temperature: 0.2,
      maxTokens: maxTokens,
    });
    return String(reply || "").trim();
  }

  // ---------------------------------------------------------------- actions

  async function translateSelection() {
    const text = selectedText;
    const config = requireTextModel();
    if (!config || !text || busy) return;
    ensureNodes();
    busy = true;
    result.classList.remove("hidden");
    result.innerHTML = `<div class="sel-result-head"><span class="spinner"></span><span>${esc(
      t("sel.translating")
    )}</span></div>`;
    positionResult();
    try {
      const output = await runTextCompletion(
        [
          [
            "system",
            "You are a translation engine. Translate the user's text into " +
              uiLanguageName() +
              ". Reply with ONLY the translation — no quotes, no explanations, no markdown.",
          ],
          ["user", text],
        ],
        1024
      );
      result.innerHTML = `<div class="sel-result-head">${icon("globe", 14)}<span>${esc(
        t("sel.translate")
      )}</span><button type="button" class="btn btn-icon btn-sm btn-ghost" data-sel-copy title="${esc(
        t("common.copy")
      )}">${icon("copy", 13)}</button></div>
        <div class="sel-result-body selectable">${esc(output)}</div>`;
      result.querySelector("[data-sel-copy]").addEventListener("click", async () => {
        if (await copyText(output)) toast(t("common.copied"));
      });
    } catch (e) {
      result.innerHTML = `<div class="sel-result-head">${icon("alert", 14)}<span>${esc(
        t("sel.translate_failed", { msg: errText(e) })
      )}</span></div>`;
    }
    busy = false;
    if (result.classList.contains("hidden")) return;
    positionResult();
  }

  async function askSelection() {
    const text = selectedText;
    const config = requireTextModel();
    if (!config || !text) return;

    let answer = "";
    const body = el(`<div class="stack" style="gap:12px;padding-bottom:6px">
      <div class="sel-quote selectable">${esc(text)}</div>
      <div class="sel-answer md" id="sel-answer"><span class="spinner"></span><span class="faint">${esc(
        t("sel.thinking")
      )}</span></div>
    </div>`);

    openDialog({
      title: t("sel.ask_title"),
      body: body,
      actions: [
        {
          label: t("common.copy"),
          icon: "copy",
          keepOpen: true,
          onClick: async () => {
            if (!answer) return;
            if (await copyText(answer)) toast(t("common.copied"));
          },
        },
        { label: t("common.close") },
      ],
      onClose: () => {
        hideToolbar();
      },
    });
    hideToolbar();

    const answerEl = body.querySelector("#sel-answer");
    try {
      answer = await runTextCompletion(
        [
          [
            "system",
            "You are a language tutor. The user selected a snippet from a spoken-conversation transcript. Explain what it means, point out grammar or idiom notes, and give one or two short example sentences. Answer in " +
              uiLanguageName() +
              ". Use concise Markdown.",
          ],
          ["user", text],
        ],
        900
      );
      if (answerEl) answerEl.innerHTML = SP.markdown.render(answer);
    } catch (e) {
      if (!answerEl) return;
      answerEl.innerHTML = `<div class="sel-error">${esc(
        t("sel.ask_failed", { msg: errText(e) })
      )}</div>`;
    }
  }

  // ---------------------------------------------------------------- wiring

  document.addEventListener("selectionchange", () => schedule(80));
  document.addEventListener("mouseup", (e) => {
    if (bar && bar.contains(e.target)) return;
    if (result && result.contains(e.target)) return;
    schedule(40);
  });
  document.addEventListener(
    "mousedown",
    (e) => {
      if (bar && bar.contains(e.target)) return;
      if (result && result.contains(e.target)) {
        // Keep the current selection (and the result bubble) when clicking its copy button.
        e.preventDefault();
        return;
      }
      hideToolbar();
    },
    true
  );
  window.addEventListener("scroll", () => hideToolbar(), true);
  window.addEventListener("resize", () => hideToolbar());
  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape") hideToolbar();
  });
  document.addEventListener("sp:navigate", () => hideToolbar());

  window.SP = window.SP || {};
  SP.selection = {
    refresh: evaluateSelection,
    hide: hideToolbar,
    translate: translateSelection,
    ask: askSelection,
  };
})();
