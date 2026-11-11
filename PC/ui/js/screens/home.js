/* Home: hero, dashboard stats, the two entry modes and the session history list. */
(function () {
  const { icon, el, esc, fmtDurationShort, fmtDate } = SP.ui;
  const t = (k, v) => SP.i18n.t(k, v);

  let query = "";

  function statTile(value, labelKey) {
    return `
      <div class="stat">
        <div class="stat-value">${esc(value)}</div>
        <div class="stat-label">${esc(t(labelKey))}</div>
      </div>`;
  }

  function modeCard(kind, titleKey, descKey, iconName) {
    return `
      <button class="mode-card ${kind}" data-mode="${kind}">
        <div class="mode-card-icon">${icon(iconName, 21)}</div>
        <div class="mode-card-title">${esc(t(titleKey))}</div>
        <div class="mode-card-desc">${esc(t(descKey))}</div>
        <div class="mode-card-go">
          <span>${esc(t("home.mode_start"))}</span>${icon("chevronRight", 14)}
        </div>
      </button>`;
  }

  function sessionRow(meta) {
    const practice = meta.mode === "PRACTICE";
    return `
      <div class="session-row" data-id="${esc(meta.id)}">
        <div class="session-dot ${practice ? "practice" : ""}"></div>
        <div class="session-main">
          <div class="session-name truncate">${esc(meta.title || "—")}</div>
          <div class="session-meta">
            ${esc(fmtDate(meta.createdAt))} · ${esc(fmtDurationShort(meta.durationMs))} · ${esc(
      t("detail.messages", { n: meta.messageCount })
    )}${meta.language ? " · " + esc(meta.language) : ""}
          </div>
        </div>
        <div class="row" style="gap:6px">
          <span class="pill ${practice ? "" : "neutral"}">${esc(
      t(practice ? "sess.mode_practice" : "sess.mode_chat")
    )}</span>
        </div>
        <div class="session-actions">
          <button class="btn btn-icon btn-sm btn-ghost" data-open title="${esc(
            t("common.open")
          )}">${icon("chevronRight", 15)}</button>
          <button class="btn btn-icon btn-sm btn-ghost" data-del title="${esc(
            t("common.delete")
          )}">${icon("trash", 15)}</button>
        </div>
      </div>`;
  }

  function renderList(listEl, sessions) {
    const filtered = sessions.filter((s) => {
      if (!query) return true;
      const q = query.toLowerCase();
      return (
        (s.title || "").toLowerCase().indexOf(q) >= 0 ||
        (s.language || "").toLowerCase().indexOf(q) >= 0
      );
    });
    if (!filtered.length) {
      listEl.innerHTML = `<div class="empty">
        <div class="empty-art">${icon("search", 26)}</div>
        <div class="empty-title">${esc(t("home.no_match"))}</div>
      </div>`;
      return;
    }
    listEl.innerHTML = filtered.map(sessionRow).join("");
    SP.ui.qsa(".session-row", listEl).forEach((row) => {
      const id = row.dataset.id;
      row.addEventListener("click", (e) => {
        if (e.target.closest("[data-del]")) return;
        SP.app.navigate("detail", { id });
      });
      row.querySelector("[data-del]").addEventListener("click", async (e) => {
        e.stopPropagation();
        const ok = await SP.ui.confirm({
          title: t("home.delete_title"),
          body: t("home.delete_body"),
          confirmLabel: t("common.delete"),
          cancelLabel: t("common.cancel"),
          danger: true,
        });
        if (!ok) return;
        try {
          await SP.api.deleteSession(id);
          SP.ui.toast(t("home.deleted"));
          SP.app.navigate("home");
        } catch (err) {
          SP.ui.toast(String(err), "error");
        }
      });
    });
  }

  async function render() {
    const [stats, sessions] = await Promise.all([
      SP.api.stats().catch(() => ({})),
      SP.api.listSessions().catch(() => []),
    ]);

    const needsKey = !(SP.store.settings.apiKey || "").trim();
    const content = el(`<div class="stack" style="gap:22px">
      ${
        needsKey
          ? `<div class="card" style="border-color: rgba(251,191,36,.35); background: linear-gradient(100deg, rgba(251,191,36,.10), var(--surface) 60%)">
              <div class="row">
                <div class="mode-card-icon" style="background: rgba(251,191,36,.18); color: var(--amber-400); margin:0">${icon("key", 19)}</div>
                <div class="grow">
                  <div class="setting-title">${esc(t("set.api_key"))}</div>
                  <div class="setting-sub">${esc(t("set.api_key_desc"))}</div>
                </div>
                <button class="btn btn-primary btn-sm" id="go-settings">${esc(t("nav.settings"))}</button>
              </div>
            </div>`
          : ""
      }
      <section class="home-hero">
        <div class="hero-eyebrow">${esc(t("home.eyebrow"))}</div>
        <div class="hero-title">${esc(t("home.title"))}</div>
        <div class="hero-sub">${esc(t("home.sub"))}</div>
      </section>

      <div class="stats">
        ${statTile(stats.sessions || 0, "home.stat_sessions")}
        ${statTile(fmtDurationShort(stats.totalMs || 0), "home.stat_time")}
        ${statTile(stats.streakDays || 0, "home.stat_streak")}
        ${statTile(stats.practiceSessions || 0, "home.stat_practice")}
      </div>

      <div class="mode-grid">
        ${modeCard("chat", "home.mode_chat", "home.mode_chat_desc", "message")}
        ${modeCard("practice", "home.mode_practice", "home.mode_practice_desc", "cap")}
      </div>

      <section class="stack" style="gap:12px">
        <div class="section-head">
          <div class="section-title">${esc(t("home.history"))}</div>
          <div class="section-count">${sessions.length}</div>
        </div>
        <div class="search">
          ${icon("search", 15)}
          <input class="input" id="history-search" placeholder="${esc(
            t("home.search_ph")
          )}" value="${esc(query)}" />
        </div>
        <div class="list" id="history-list"></div>
      </section>
    </div>`);

    const listEl = content.querySelector("#history-list");
    if (sessions.length) {
      renderList(listEl, sessions);
    } else {
      listEl.innerHTML = `<div class="empty">
        <div class="empty-art">${icon("book", 26)}</div>
        <div class="empty-title">${esc(t("home.empty_title"))}</div>
        <div class="empty-sub">${esc(t("home.empty_sub"))}</div>
      </div>`;
    }

    const goSettings = content.querySelector("#go-settings");
    if (goSettings) goSettings.addEventListener("click", () => SP.app.navigate("settings"));

    content.querySelectorAll("[data-mode]").forEach((card) => {
      card.addEventListener("click", () => {
        if (card.dataset.mode === "practice") {
          SP.app.navigate("practice");
          return;
        }
        SP.screens.session.open({
          mode: "CHAT",
          chatSpeakLang: SP.store.settings.chatSpeakLang || "auto",
        });
      });
    });

    const search = content.querySelector("#history-search");
    search.addEventListener(
      "input",
      SP.ui.debounce((e) => {
        query = e.target.value;
        renderList(listEl, sessions);
      }, 140)
    );

    return {
      titleKey: "nav.home",
      subKey: "app.tagline",
      content,
    };
  }

  SP.screens.home = { render };
})();
