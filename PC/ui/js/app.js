/* App shell: navigation, page header, theme switch, boot sequence. */
(function () {
  const { icon, el, esc, toast } = SP.ui;
  const t = (k, v) => SP.i18n.t(k, v);

  const NAV = [
    { id: "home", icon: "home", labelKey: "nav.home" },
    { id: "practice", icon: "cap", labelKey: "nav.practice" },
    { id: "settings", icon: "settings", labelKey: "nav.settings" },
  ];

  const app = {
    route: "home",
    params: null,
    dispose: null,
  };

  function renderNav() {
    const nav = document.getElementById("nav");
    nav.innerHTML = NAV.map(
      (item) => `
      <button class="nav-item ${app.route === item.id ? "active" : ""}" data-nav="${item.id}">
        ${icon(item.icon, 18)}
        <span class="nav-label">${esc(t(item.labelKey))}</span>
      </button>`
    ).join("");
    SP.ui.qsa("[data-nav]", nav).forEach((btn) => {
      btn.addEventListener("click", () => navigate(btn.dataset.nav));
    });
  }

  function renderBrand() {
    document.getElementById("brand-mark").innerHTML = icon("mic", 21);
  }

  function renderFoot() {
    const s = SP.store.settings;
    const label = document.getElementById("foot-theme-label");
    const btn = document.getElementById("theme-toggle");
    const mode = s.themeMode;
    label.textContent = t(
      mode === "LIGHT"
        ? "set.theme_light"
        : mode === "DARK"
        ? "set.theme_dark"
        : "set.theme_system"
    );
    btn.innerHTML = icon(
      mode === "LIGHT" ? "sun" : mode === "DARK" ? "moon" : "monitor",
      15
    );
    btn.title = t("set.theme");
    document.getElementById("foot-version").textContent =
      "v" + SP.store.info.version;
  }

  function cycleTheme() {
    const order = ["SYSTEM", "LIGHT", "DARK"];
    const cur = SP.store.settings.themeMode || "SYSTEM";
    const next = order[(order.indexOf(cur) + 1) % order.length];
    SP.store.patch({ themeMode: next }, "theme");
    renderFoot();
  }

  function setHeader({ title, sub, actions }) {
    const header = document.getElementById("page-header");
    header.innerHTML = `
      <div>
        <div class="page-title">${esc(title || "")}</div>
        ${sub ? `<div class="page-sub">${esc(sub)}</div>` : ""}
      </div>
      <div class="header-spacer"></div>
      <div class="row" id="header-actions"></div>`;
    const host = document.getElementById("header-actions");
    (actions || []).forEach((action) => {
      if (action.node) {
        host.appendChild(action.node);
        return;
      }
      const btn = el(
        `<button class="btn ${action.className || ""}" ${action.title ? `title="${esc(action.title)}"` : ""}>
          ${action.icon ? icon(action.icon, 16) : ""}
          ${action.label ? `<span>${esc(action.label)}</span>` : ""}
        </button>`
      );
      btn.addEventListener("click", () => action.onClick && action.onClick());
      host.appendChild(btn);
    });
  }

  function parseHash() {
    const raw = (location.hash || "").replace(/^#\/?/, "");
    if (!raw) return null;
    const parts = raw.split("/").filter(Boolean);
    if (!parts.length) return null;
    const route = parts[0];
    if (route === "detail" && parts[1]) return { route, params: { id: decodeURIComponent(parts[1]) } };
    return { route, params: null };
  }

  function writeHash(route, params) {
    let next = "#/" + route;
    if (route === "detail" && params && params.id) {
      next += "/" + encodeURIComponent(params.id);
    }
    if (location.hash !== next) {
      // file:// previews cannot rewrite history; the real app runs on http://tauri.localhost.
      try {
        history.replaceState(null, "", next);
      } catch (_) {}
    }
  }

  async function navigate(route, params) {
    if (app.route === route && !params && route !== "detail") {
      // still allow a re-render so lists refresh
    }
    if (app.dispose) {
      try {
        app.dispose();
      } catch (_) {}
      app.dispose = null;
    }
    app.route = route;
    app.params = params || null;
    writeHash(route, app.params);

    const body = document.getElementById("page-body");
    body.scrollTop = 0;
    body.className = "page-body";
    body.innerHTML = "";
    renderNav();

    const screen = SP.screens[route];
    if (!screen) {
      body.innerHTML = `<div class="empty">Unknown screen: ${esc(route)}</div>`;
      return;
    }

    let result;
    try {
      result = await screen.render(params || {});
    } catch (e) {
      console.error(e);
      body.innerHTML = `<div class="empty"><div class="empty-title">渲染失败</div><div class="empty-sub">${esc(
        String(e && e.message ? e.message : e)
      )}</div></div>`;
      return;
    }

    setHeader({
      title: SP.i18n.t(result.titleKey || "", result.titleVars || {}) || result.title,
      sub: SP.i18n.t(result.subKey || "", result.subVars || {}) || result.sub || "",
      actions: result.actions,
    });

    if (result.content) {
      body.appendChild(result.content);
      body.classList.add("view-enter");
      setTimeout(() => body.classList.remove("view-enter"), 600);
    }
    if (result.mount) {
      try {
        app.dispose = result.mount(body) || null;
      } catch (e) {
        console.error(e);
      }
    }
  }

  function refreshHeader() {
    const screen = SP.screens[app.route];
    if (screen && screen.header) {
      const h = screen.header(app.params || {});
      if (h) setHeader(h);
    }
  }

  /** Background update check, at most once per day (mirrors the Android behaviour). */
  async function autoCheckUpdates() {
    const DAY = 24 * 60 * 60 * 1000;
    const s = SP.store.settings;
    if (!s.autoCheckUpdate || !SP.api.native) return;
    if (Date.now() - (s.lastUpdateCheckAt || 0) < DAY) return;
    try {
      const result = await SP.api.checkUpdate(s.updateProxy);
      await SP.store.patch({ lastUpdateCheckAt: Date.now() }, "update-check");
      if (result && result.kind === "available" && result.info) {
        SP.screens.settings.showUpdate(result.info);
      }
    } catch (_) {
      // A failed background check is silent: the user can retry from Settings.
    }
  }

  /** Surfaces an unexpected failure instead of leaving a blank page. */
  function reportFatal(kind, detail) {
    const body = document.getElementById("page-body");
    if (!body) return;
    const box = document.createElement("div");
    box.className = "card";
    box.style.borderColor = "rgba(251,113,133,.4)";
    box.innerHTML =
      '<div class="card-title">' + esc(kind) + '</div>' +
      '<pre class="mono" style="white-space:pre-wrap;color:var(--coral-400);font-size:12px">' +
      esc(detail) + '</pre>';
    body.innerHTML = "";
    body.appendChild(box);
  }

  window.addEventListener("error", (e) => {
    console.error(e.error || e.message);
    reportFatal("Runtime error", (e.error && e.error.stack) || e.message || String(e));
  });
  window.addEventListener("unhandledrejection", (e) => {
    const r = e.reason || {};
    console.error(r);
    reportFatal("Unhandled rejection", r.stack || r.message || String(r));
  });

  async function boot() {
    renderBrand();
    document.getElementById("brand-mark").innerHTML = icon("mic", 21);
    document.getElementById("theme-toggle").addEventListener("click", cycleTheme);

    // Window chrome follows the in-app theme.
    document.addEventListener("contextmenu", (e) => {
      if (!e.target.closest("input, textarea, .selectable, .msg-text")) {
        e.preventDefault();
      }
    });

    await SP.store.init();
    renderFoot();

    SP.store.subscribe((settings, reason) => {
      renderFoot();
      if (reason === "theme" && SP.orb) {
        // canvases re-read the palette on their next frame
      }
    });

    window.addEventListener("hashchange", () => {
      const target = parseHash();
      if (target && (target.route !== app.route || target.params)) {
        navigate(target.route, target.params);
      }
    });

    const initial = parseHash();
    await navigate(initial ? initial.route : "home", initial ? initial.params : null);
    SP.screens.session.prime();
    autoCheckUpdates();
  }

  window.SP = window.SP || {};
  SP.app = {
    boot,
    navigate,
    refreshHeader,
    get route() {
      return app.route;
    },
    goHome: () => navigate("home"),
  };

  SP.screens = SP.screens || {};

  document.addEventListener("DOMContentLoaded", boot);
})();
