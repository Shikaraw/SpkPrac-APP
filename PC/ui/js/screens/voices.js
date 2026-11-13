/* Voice management (spec §5.2) + voice detail (spec §5.4). */
(function () {
  const { icon, el, esc, toast, fmtDate, openDialog, confirm } = SP.ui;
  const t = (k, v) => SP.i18n.t(k, v);

  function statusPill(status) {
    const S = SP.voice.STATUS;
    const meta =
      status === S.OK
        ? { cls: "", dot: "live", key: "voice.ok" }
        : status === S.UNDEPLOYED
        ? { cls: "coral", dot: "err", key: "voice.undeployed" }
        : { cls: "warm", dot: "warn", key: "voice.deploying" };
    return `<span class="pill ${meta.cls}"><span class="status-dot ${meta.dot}"></span>${esc(
      t(meta.key)
    )}</span>`;
  }

  function modelLabel(voice) {
    return voice && voice.targetModel ? voice.targetModel : t("voice.unknown_model");
  }

  async function copyText(text) {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch (_) {
      // file:// previews do not expose the async clipboard API
      try {
        const ta = document.createElement("textarea");
        ta.value = text;
        ta.style.position = "fixed";
        ta.style.opacity = "0";
        ta.style.left = "-9999px";
        document.body.appendChild(ta);
        ta.select();
        const ok = document.execCommand("copy");
        ta.remove();
        return ok;
      } catch (_) {
        return false;
      }
    }
  }

  // ---------------------------------------------------------------- management page

  function voiceRow(voice) {
    return `<div class="voice-row" data-voice="${esc(voice.voiceId)}" role="button" tabindex="0">
      <div class="voice-avatar">${icon("mic", 18)}</div>
      <div class="voice-main">
        <div class="voice-name">${esc(voice.name || voice.voiceId)}</div>
        <div class="voice-meta">${esc(modelLabel(voice))}</div>
      </div>
      <div class="voice-row-status">${statusPill(voice.status)}</div>
      <div class="voice-actions">
        <button class="btn btn-icon btn-sm btn-ghost" data-delete="${esc(
          voice.voiceId
        )}" title="${esc(t("voice.delete"))}">${icon("trash", 15)}</button>
        <span class="voice-chevron">${icon("chevronRight", 16)}</span>
      </div>
    </div>`;
  }

  function emptyState() {
    return `<div class="empty">
      <div class="empty-art">${icon("mic", 26)}</div>
      <div class="empty-title">${esc(t("voice.empty"))}</div>
      <div class="empty-sub">${esc(t("voice.empty_desc"))}</div>
      <button class="btn btn-primary btn-sm" id="btn-empty-add">${icon("mic", 14)}<span>${esc(
      t("voice.add")
    )}</span></button>
    </div>`;
  }

  function confirmDelete(voice) {
    return confirm({
      title: t("voice.delete"),
      body: t("voice.delete_confirm", {
        name: (voice && (voice.name || voice.voiceId)) || "",
      }),
      confirmLabel: t("common.delete"),
      cancelLabel: t("common.cancel"),
      danger: true,
    });
  }

  async function deleteVoice(voiceId, nextRoute) {
    const voice = SP.voice.find(voiceId);
    if (!voice) return;
    if (!(await confirmDelete(voice))) return;
    try {
      await SP.voice.remove(voiceId);
      toast(t("voice.delete_done"));
      await SP.app.navigate(nextRoute || "voices");
    } catch (e) {
      toast(SP.voice.errorText(e), "error");
    }
  }

  async function refreshAll() {
    try {
      await SP.voice.refresh();
      toast(t("voice.status_refreshed"));
    } catch (e) {
      toast(SP.voice.errorText(e), "error");
    }
    await SP.app.navigate("voices");
  }

  async function render() {
    const list = SP.voice.list();
    const showHint = !SP.voice.isRealtimeModel(SP.store.settings.rtModel);
    const content = el(`<div class="stack" style="gap:16px;max-width:900px">
      <div class="card">
        <div class="voice-list-head">
          <div class="card-title" style="margin:0">${icon("mic", 15)}<span>${esc(
      t("voice.title")
    )}</span></div>
          <span class="faint tiny">${esc(t("voice.count", { n: list.length }))}</span>
        </div>
        ${
          showHint
            ? `<div class="voice-model-hint small faint">${icon("info", 13)}<span>${esc(
                t("voice.only_omni_hint")
              )}</span></div>`
            : ""
        }
        <div class="voice-list" id="voice-list">${
          list.length ? list.map(voiceRow).join("") : emptyState()
        }</div>
      </div>
      <div class="voice-note small faint">${icon("info", 13)}<span>${esc(
      t("voice.audio_note")
    )}</span></div>
    </div>`);

    const listHost = content.querySelector("#voice-list");
    listHost.addEventListener("click", (e) => {
      const del = e.target.closest("[data-delete]");
      if (del) {
        e.stopPropagation();
        deleteVoice(del.getAttribute("data-delete"), "voices");
        return;
      }
      const row = e.target.closest("[data-voice]");
      if (row) SP.app.navigate("voiceDetail", { id: row.getAttribute("data-voice") });
    });
    listHost.addEventListener("keydown", (e) => {
      if (e.key !== "Enter" && e.key !== " ") return;
      const row = e.target.closest("[data-voice]");
      if (!row) return;
      e.preventDefault();
      SP.app.navigate("voiceDetail", { id: row.getAttribute("data-voice") });
    });

    const emptyAdd = content.querySelector("#btn-empty-add");
    if (emptyAdd) emptyAdd.addEventListener("click", () => addVoiceDialog());

    return {
      titleKey: "voice.title",
      subKey: "voice.subtitle",
      content,
      actions: [
        {
          icon: "refresh",
          label: t("voice.refresh"),
          onClick: refreshAll,
        },
        {
          icon: "mic",
          label: t("voice.add"),
          className: "btn-primary",
          onClick: () => addVoiceDialog(),
        },
      ],
    };
  }

  // ---------------------------------------------------------------- add-voice dialog

  function addVoiceDialog() {
    const state = { file: null, checked: null, creating: false };
    const body = el(`<div class="stack" style="gap:14px;padding-bottom:4px">
      <div class="small faint">${esc(t("voice.add_desc"))}</div>
      <div>
        <div class="field-label">${esc(t("voice.name"))}</div>
        <input class="input" id="new-voice-name" maxlength="20" placeholder="${esc(
          t("voice.name_hint")
        )}" />
      </div>
      <div>
        <div class="field-label">${esc(t("voice.pick_audio"))}</div>
        <button class="voice-picker" id="btn-pick-audio" type="button">
          ${icon("file", 18)}
          <span class="grow truncate" id="audio-file-name">${esc(t("voice.audio_tip"))}</span>
          <span class="pill neutral">${esc(t("voice.pick_audio"))}</span>
        </button>
        <input type="file" id="audio-file" accept=".wav,.mp3,.m4a,.aac" class="hidden" />
      </div>
      <div id="audio-check" class="voice-check"></div>
      <div class="voice-note small faint">${icon("info", 13)}<span>${esc(
        t("voice.audio_note")
      )}</span></div>
      <div id="create-status" class="voice-check"></div>
      <div id="create-error" class="voice-check"></div>
    </div>`);

    const nameInput = body.querySelector("#new-voice-name");
    const fileInput = body.querySelector("#audio-file");
    const pickButton = body.querySelector("#btn-pick-audio");
    const fileName = body.querySelector("#audio-file-name");
    const checkHost = body.querySelector("#audio-check");
    const statusHost = body.querySelector("#create-status");
    const errorHost = body.querySelector("#create-error");

    function renderCheck(result) {
      const lines = [];
      if (!result.errors.length) {
        lines.push(
          `<div class="ok">${icon("check", 13)}<span>${esc(
            SP.voice.formatAudioMeta(result)
          )}</span></div>`
        );
      }
      result.errors.forEach((msg) => {
        lines.push(`<div class="bad">${icon("alert", 13)}<span>${esc(msg)}</span></div>`);
      });
      result.warnings.forEach((msg) => {
        lines.push(`<div class="warn">${icon("info", 13)}<span>${esc(msg)}</span></div>`);
      });
      checkHost.innerHTML = lines.join("");
    }

    function setStatus(kind, text) {
      if (!text) {
        statusHost.innerHTML = "";
        return;
      }
      const cls = kind === "error" ? "bad" : kind === "ok" ? "ok" : "faint";
      const iconName = kind === "error" ? "alert" : kind === "ok" ? "check" : "activity";
      const spinner = kind === "busy" ? '<span class="spinner"></span>' : icon(iconName, 13);
      statusHost.innerHTML = `<div class="${cls}">${spinner}<span>${esc(text)}</span></div>`;
    }

    function setError(text) {
      errorHost.innerHTML = text
        ? `<div class="bad">${icon("alert", 13)}<span>${esc(text)}</span></div>`
        : "";
    }

    pickButton.addEventListener("click", () => fileInput.click());
    fileInput.addEventListener("change", async () => {
      const file = fileInput.files && fileInput.files[0];
      state.file = file || null;
      state.checked = null;
      setError("");
      setStatus("", "");
      checkHost.innerHTML = "";
      if (!file) {
        fileName.textContent = t("voice.audio_tip");
        return;
      }
      fileName.textContent = file.name;
      checkHost.innerHTML = `<div class="faint"><span class="spinner"></span><span>${esc(
        t("common.loading")
      )}</span></div>`;
      const result = await SP.voice.validateAudio(file);
      state.checked = result;
      renderCheck(result);
    });

    const dialog = openDialog({
      title: t("voice.add"),
      body,
      actions: [
        { label: t("common.cancel") },
        {
          label: t("voice.create"),
          className: "btn-primary",
          icon: "mic",
          keepOpen: true,
          onClick: async () => {
            if (state.creating) return false;
            setError("");
            const name = nameInput.value.trim();
            if (!name) {
              setError(t("voice.err_name_empty"));
              nameInput.focus();
              return false;
            }
            if (!state.file) {
              setError(t("voice.err_no_file"));
              return false;
            }
            if (!state.checked) {
              state.checked = await SP.voice.validateAudio(state.file);
              renderCheck(state.checked);
            }
            if (!state.checked.ok) {
              setError(state.checked.errors.join(" "));
              return false;
            }

            const buttons = dialog.dialog.querySelectorAll(".dialog-foot .btn");
            const createBtn = buttons[buttons.length - 1];
            const labelSpan = createBtn ? createBtn.querySelector("span") : null;
            state.creating = true;
            if (createBtn) createBtn.disabled = true;
            if (labelSpan) labelSpan.textContent = t("voice.creating");
            setStatus("busy", t("voice.uploading"));

            try {
              const result = await SP.voice.create({
                name: name,
                file: state.file,
                onProgress: (p) => {
                  if (!p) return;
                  if (p.phase === "uploading") {
                    setStatus("busy", t("voice.uploading"));
                  } else if (p.phase === "created") {
                    setStatus("busy", t("voice.deploying"));
                  } else if (p.phase === "polling") {
                    setStatus(
                      p.status === "OK" ? "ok" : "busy",
                      t("voice.polling", { n: p.attempt })
                    );
                  }
                },
              });
              toast(t("voice.created"));
              dialog.close();
              await offerCurrent(result);
              await SP.app.navigate("voices");
            } catch (e) {
              setStatus("", "");
              setError(SP.voice.errorText(e));
            } finally {
              state.creating = false;
              if (createBtn) createBtn.disabled = false;
              if (labelSpan) labelSpan.textContent = t("voice.create");
            }
            return false;
          },
        },
      ],
    });
    return dialog;
  }

  /** Spec §5.3.6: make the new voice current when the slot is empty or the user agrees. */
  async function offerCurrent(result) {
    if (!result || !result.voiceId) return;
    const current = String(SP.store.settings.voice || "").trim();
    if (!current || current === "Tina") {
      await SP.store.patch({ voice: result.voiceId }, "voices");
      return;
    }
    const ok = await confirm({
      title: t("voice.set_current_title"),
      body: t("voice.set_current_body", { name: result.name || result.voiceId }),
      confirmLabel: t("common.confirm"),
      cancelLabel: t("common.cancel"),
    });
    if (ok) await SP.store.patch({ voice: result.voiceId }, "voices");
  }

  // ---------------------------------------------------------------- detail page

  function fieldRow(labelKey, control) {
    return `<div class="setting-row">
      <div class="setting-main"><div class="setting-title">${esc(t(labelKey))}</div></div>
      <div class="setting-control">${control}</div>
    </div>`;
  }

  async function renderDetail(params) {
    const id = params && params.id;
    const voice = SP.voice.find(id);

    if (!voice) {
      const content = el(`<div class="empty">
        <div class="empty-art">${icon("alert", 26)}</div>
        <div class="empty-title">${esc(t("voice.not_found"))}</div>
        <div class="empty-sub">${esc(t("voice.not_found_desc"))}</div>
        <button class="btn btn-sm" id="btn-back-voices">${icon("arrowLeft", 14)}<span>${esc(
        t("detail.back")
      )}</span></button>
      </div>`);
      const back = content.querySelector("#btn-back-voices");
      if (back) back.addEventListener("click", () => SP.app.navigate("voices"));
      return { titleKey: "voice.detail", content, actions: [] };
    }

    const content = el(`<div class="stack" style="gap:16px;max-width:860px">
      <div class="card">
        <div class="detail-head">
          <div class="voice-avatar large">${icon("mic", 22)}</div>
          <div class="grow">
            <div class="detail-title">${esc(voice.name || voice.voiceId)}</div>
            <div class="chip-row">
              ${statusPill(voice.status)}
              <span class="chip chip-static">${icon("activity", 13)}${esc(
      t("voice.target_model")
    )}: ${esc(modelLabel(voice))}</span>
            </div>
            <div class="voice-status-note">${esc(SP.voice.statusHint(voice.status))}</div>
          </div>
        </div>
      </div>

      <div class="card">
        <div class="card-title">${icon("info", 15)}<span>${esc(t("voice.detail"))}</span></div>
        <div class="stack" style="gap:0">
          <div class="setting-row">
            <div class="setting-main">
              <div class="setting-title">${esc(t("voice.name"))}</div>
              <div class="setting-sub">${esc(t("voice.rename_local_hint"))}</div>
            </div>
            <div class="setting-control wide">
              <div class="row" style="gap:8px">
                <input class="input grow" id="voice-name-input" maxlength="20" value="${esc(
                  voice.name || ""
                )}" />
                <button class="btn btn-sm" id="btn-rename">${icon("pencil", 14)}<span>${esc(
      t("common.save")
    )}</span></button>
              </div>
            </div>
          </div>
          ${fieldRow("voice.status", statusPill(voice.status))}
          ${fieldRow(
            "voice.target_model",
            `<span class="mono selectable">${esc(modelLabel(voice))}</span>`
          )}
          ${fieldRow(
            "voice.created_at",
            `<span class="small">${esc(voice.createdAt ? fmtDate(voice.createdAt) : "—")}</span>`
          )}
          ${fieldRow(
            "voice.synced_at",
            `<span class="small">${esc(voice.updatedAt ? fmtDate(voice.updatedAt) : "—")}</span>`
          )}
          ${fieldRow(
            "voice.source_file",
            `<span class="small mono truncate" title="${esc(voice.audioName || "")}">${esc(
              voice.audioName || "—"
            )}</span>`
          )}
        </div>
      </div>

      <div class="card">
        <div class="card-title">${icon("key", 15)}<span>${esc(t("voice.voice_id"))}</span></div>
        <div class="voice-id-row">
          <code class="voice-id selectable" id="voice-id">${esc(voice.voiceId)}</code>
          <button class="btn btn-sm" id="btn-copy-id">${icon("copy", 14)}<span>${esc(
      t("voice.copy_id")
    )}</span></button>
        </div>
      </div>
    </div>`);

    content.querySelector("#btn-copy-id").addEventListener("click", async () => {
      const ok = await copyText(voice.voiceId);
      toast(ok ? t("voice.copied") : t("voice.copy_failed"), ok ? undefined : "error");
    });

    content.querySelector("#btn-rename").addEventListener("click", async () => {
      const input = content.querySelector("#voice-name-input");
      const name = input.value.trim();
      if (!name) {
        toast(t("voice.err_name_empty"), "error");
        input.focus();
        return;
      }
      try {
        await SP.voice.rename(voice.voiceId, name);
        toast(t("common.saved"));
        await SP.app.navigate("voiceDetail", { id: voice.voiceId });
      } catch (e) {
        toast(SP.voice.errorText(e), "error");
      }
    });

    const actions = [
      {
        icon: "refresh",
        label: t("voice.refresh"),
        onClick: async () => {
          try {
            await SP.voice.refreshOne(voice.voiceId);
            toast(t("voice.status_refreshed"));
            await SP.app.navigate("voiceDetail", { id: voice.voiceId });
          } catch (e) {
            toast(SP.voice.errorText(e), "error");
          }
        },
      },
      {
        icon: "trash",
        title: t("voice.delete"),
        className: "btn-danger",
        onClick: () => deleteVoice(voice.voiceId, "voices"),
      },
      {
        icon: "arrowLeft",
        title: t("detail.back"),
        onClick: () => SP.app.navigate("voices"),
      },
    ];

    return {
      titleKey: "voice.detail",
      sub: voice.name || voice.voiceId,
      content,
      actions,
    };
  }

  SP.screens.voices = { render };
  SP.screens.voiceDetail = { render: renderDetail };
})();
