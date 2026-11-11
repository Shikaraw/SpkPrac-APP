/* Session detail: metadata, the practice report and the full transcript, with export/copy/delete. */
(function () {
  const { icon, el, esc, fmtDuration, fmtDate, fmtTime, toast } = SP.ui;
  const t = (k, v) => SP.i18n.t(k, v);

  function bubble(m) {
    const isUser = m.role === "USER";
    if (m.role === "SYSTEM") {
      return `<div class="msg system"><div class="msg-body"><div class="msg-text">${esc(
        m.text
      )}</div></div></div>`;
    }
    return `<div class="msg ${isUser ? "user" : "agent"}">
      <div class="msg-avatar">${icon(isUser ? "user" : "bot", 15)}</div>
      <div class="msg-body">
        <div class="msg-text">${esc(m.text)}</div>
        ${m.translation ? `<div class="msg-translation">${esc(m.translation)}</div>` : ""}
        <div class="msg-time">${esc(fmtTime(m.ts))}</div>
      </div>
    </div>`;
  }

  async function render(params) {
    const id = params.id;
    let session = null;
    try {
      session = await SP.api.loadSession(id);
    } catch (_) {}

    if (!session) {
      const content = el(`<div class="empty">
        <div class="empty-art">${icon("alert", 26)}</div>
        <div class="empty-title">${esc(t("detail.missing"))}</div>
        <div class="empty-sub">${esc(t("detail.missing_sub"))}</div>
      </div>`);
      return { titleKey: "detail.title", content };
    }

    const meta = session.meta;
    const practice = meta.mode === "PRACTICE";
    const report = session.report;

    const content = el(`<div class="stack" style="gap:20px;max-width:900px">
      <div class="card">
        <div class="detail-head">
          <div class="grow">
            <div class="detail-title">${esc(meta.title || "—")}</div>
            <div class="chip-row">
              <span class="chip chip-static">${icon(practice ? "cap" : "message", 13)}${esc(
      t(practice ? "sess.mode_practice" : "sess.mode_chat")
    )}</span>
              <span class="chip chip-static">${icon("clock", 13)}${esc(
      fmtDuration(meta.durationMs)
    )}</span>
              <span class="chip chip-static">${icon("calendar", 13)}${esc(
      fmtDate(meta.createdAt)
    )}</span>
              <span class="chip chip-static">${icon("message", 13)}${esc(
      t("detail.messages", { n: meta.messageCount })
    )}</span>
              ${
                meta.language
                  ? `<span class="chip chip-static">${esc(
                      SP.languages.name(meta.language)
                    )}</span>`
                  : ""
              }
            </div>
          </div>
        </div>
      </div>

      ${
        report
          ? `<div class="card report-card">
              <div class="report-head">${icon("sparkles", 16)}<span class="section-title">${esc(
              t("detail.report")
            )}</span></div>
              <div class="md">${SP.markdown.render(report.content)}</div>
            </div>`
          : practice
          ? `<div class="card"><div class="empty" style="padding:24px">
              <div class="empty-art">${icon("sparkles", 24)}</div>
              <div class="empty-title">${esc(t("detail.report_none"))}</div>
            </div></div>`
          : ""
      }

      <div class="card">
        <div class="card-title">${icon("message", 15)}<span>${esc(
      t("detail.transcript")
    )}</span></div>
        <div class="bubble-list">
          ${
            session.messages.length
              ? session.messages.map(bubble).join("")
              : `<div class="faint small">${esc(t("detail.no_messages"))}</div>`
          }
        </div>
      </div>
    </div>`);

    const fullText = () => {
      const parts = [`# ${meta.title}`, "", `${fmtDate(meta.createdAt)} · ${fmtDuration(meta.durationMs)}`];
      if (report) parts.push("", "## " + t("detail.report"), "", report.content);
      parts.push("", "## " + t("detail.transcript"), "");
      session.messages.forEach((m) => {
        const who = m.role === "USER" ? "**" + t("sess.you") + "**" : "**" + t("sess.coach") + "**";
        parts.push(`${who}: ${m.text}`);
        if (m.translation) parts.push(`> ${m.translation}`);
        parts.push("");
      });
      return parts.join("\n");
    };

    const actions = [
      {
        icon: "file",
        label: t("detail.export_md"),
        onClick: async () => {
          try {
            const safe = (meta.title || "session").replace(/[\\/:*?"<>|]/g, "_").slice(0, 40);
            const path = await SP.api.writeExport(
              `SpkPrac-${safe}-${meta.id}.md`,
              fullText()
            );
            toast(t("detail.exported", { path }));
          } catch (e) {
            toast(String(e), "error");
          }
        },
      },
      {
        icon: "copy",
        title: t("detail.copy_all"),
        onClick: async () => {
          try {
            await navigator.clipboard.writeText(fullText());
            toast(t("common.copied"));
          } catch (e) {
            toast(String(e), "error");
          }
        },
      },
      {
        icon: "trash",
        title: t("detail.delete"),
        className: "btn-danger",
        onClick: async () => {
          const ok = await SP.ui.confirm({
            title: t("home.delete_title"),
            body: t("home.delete_body"),
            confirmLabel: t("common.delete"),
            cancelLabel: t("common.cancel"),
            danger: true,
          });
          if (!ok) return;
          await SP.api.deleteSession(id);
          toast(t("detail.deleted"));
          SP.app.navigate("home");
        },
      },
      {
        icon: "arrowLeft",
        title: t("detail.back"),
        onClick: () => SP.app.navigate("home"),
      },
    ];

    return {
      titleKey: "detail.title",
      sub: meta.title,
      actions,
      content,
    };
  }

  SP.screens.detail = { render };
})();
