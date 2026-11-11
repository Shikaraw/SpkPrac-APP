/* Small DOM/UI helpers shared by every screen. */
(function () {
  const P = {
    home: '<path d="M3 10.2 12 3l9 7.2"/><path d="M5.4 9.4V20a1 1 0 0 0 1 1h11.2a1 1 0 0 0 1-1V9.4"/>',
    mic: '<rect x="9" y="2" width="6" height="12" rx="3"/><path d="M5 11a7 7 0 0 0 14 0"/><path d="M12 18v3"/>',
    settings:
      '<path d="M4 21v-6M4 11V3M12 21v-9M12 8V3M20 21v-4M20 13V3"/><path d="M2 15h4M10 8h4M18 17h4"/>',
    send: '<path d="m22 2-7 20-4-9-9-4Z"/><path d="M22 2 11 13"/>',
    x: '<path d="M18 6 6 18M6 6l12 12"/>',
    check: '<path d="M20 6 9 17l-5-5"/>',
    search: '<circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/>',
    trash:
      '<path d="M3 6h18M8 6V4h8v2"/><path d="M19 6l-1 14a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1L5 6"/>',
    chevronRight: '<path d="m9 18 6-6-6-6"/>',
    chevronDown: '<path d="m6 9 6 6 6-6"/>',
    play: '<path d="M6 3.5 20 12 6 20.5z"/>',
    download: '<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><path d="M7 10l5 5 5-5"/><path d="M12 15V3"/>',
    refresh:
      '<path d="M21 12a9 9 0 1 1-2.6-6.4"/><path d="M21 3v5h-5"/>',
    volume:
      '<path d="M11 5 6 9H3v6h3l5 4z"/><path d="M15.5 8.5a5 5 0 0 1 0 7"/><path d="M18.6 5.4a9 9 0 0 1 0 13.2"/>',
    volumeOff:
      '<path d="M11 5 6 9H3v6h3l5 4z"/><path d="m22 9-6 6M16 9l6 6"/>',
    headphones:
      '<path d="M3 18v-6a9 9 0 0 1 18 0v6"/><path d="M21 19a2 2 0 0 1-2 2h-1a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2h3zM3 19a2 2 0 0 0 2 2h1a2 2 0 0 0 2-2v-3a2 2 0 0 0-2-2H3z"/>',
    bluetooth: '<path d="m7 7 10 10-5 4.5V2.5L17 7 7 17"/>',
    usb: '<circle cx="12" cy="17" r="2.5"/><path d="M12 14.5V4M12 4 9 7M12 4l3 3"/><path d="M12 10H8a2 2 0 0 0-2 2v1"/>',
    hangup:
      '<path d="M10.7 13.3a16 16 0 0 0 3.4 2.6l1.3-1.3a1 1 0 0 1 1-.2 12 12 0 0 0 3.6.6 1 1 0 0 1 1 1v3.5a1 1 0 0 1-1 1A19 19 0 0 1 2 5a1 1 0 0 1 1-1h3.5a1 1 0 0 1 1 1 12 12 0 0 0 .6 3.6 1 1 0 0 1-.2 1L6.6 10.7a16 16 0 0 0 4.1 2.6Z"/><path d="m2 2 20 20"/>',
    keyboard:
      '<rect x="2" y="6" width="20" height="12" rx="2"/><path d="M6 10h.01M10 10h.01M14 10h.01M18 10h.01M8 14h8"/>',
    message:
      '<path d="M21 11.5a8.4 8.4 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.4 8.4 0 0 1-3.8-.9L3 21l1.9-5.7a8.4 8.4 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.4 8.4 0 0 1 3.8-.9h.5a8.5 8.5 0 0 1 8 8z"/>',
    cap: '<path d="M22 9.5 12 4.5 2 9.5l10 5 10-5Z"/><path d="M6 11.5v5c0 1.7 2.7 3 6 3s6-1.3 6-3v-5"/>',
    sparkles:
      '<path d="m12 3 1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9L12 3Z"/><path d="M19 16.5 20 19l2.5 1-2.5 1-1 2.5"/>',
    info: '<circle cx="12" cy="12" r="9"/><path d="M12 16v-4M12 8h.01"/>',
    alert: '<circle cx="12" cy="12" r="9"/><path d="M12 8v4M12 16h.01"/>',
    copy: '<rect x="9" y="9" width="12" height="12" rx="2"/><path d="M5 15V5a2 2 0 0 1 2-2h10"/>',
    share:
      '<path d="M4 12v7a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-7"/><path d="m16 6-4-4-4 4"/><path d="M12 2v13"/>',
    file: '<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M14 2v6h6"/><path d="M8 13h8M8 17h5"/>',
    arrowLeft: '<path d="m12 19-7-7 7-7"/><path d="M19 12H5"/>',
    arrowDown: '<path d="M12 5v14"/><path d="m19 12-7 7-7-7"/>',
    sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M2 12h2M20 12h2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M19.1 4.9l-1.4 1.4M6.3 17.7l-1.4 1.4"/>',
    moon: '<path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8Z"/>',
    monitor: '<rect x="2" y="4" width="20" height="13" rx="2"/><path d="M8 21h8M12 17v4"/>',
    clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3.5 2"/>',
    activity: '<path d="M2 12h4l2.5-7.5L13 19l2.5-7h6.5"/>',
    user: '<circle cx="12" cy="8" r="4"/><path d="M4 21a8 8 0 0 1 16 0"/>',
    bot: '<rect x="4" y="7" width="16" height="12" rx="3"/><path d="M12 3v4"/><path d="M9 13h.01M15 13h.01"/>',
    book: '<path d="M2 4h6a4 4 0 0 1 4 4v13a3 3 0 0 0-3-3H2z"/><path d="M22 4h-6a4 4 0 0 0-4 4v13a3 3 0 0 1 3-3h7z"/>',
    external:
      '<path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/><path d="M15 3h6v6"/><path d="M10 14 21 3"/>',
    pencil: '<path d="M17 3a2.8 2.8 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5Z"/>',
    bolt: '<path d="M13 2 4 14h7l-1 8 9-12h-7z"/>',
    key: '<circle cx="8" cy="15" r="4"/><path d="m10.8 12.2 8-8M16 4l3 3M18 6l2 2"/>',
    eye: '<path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12Z"/><circle cx="12" cy="12" r="3"/>',
    eyeOff:
      '<path d="M10.6 6.2A9.9 9.9 0 0 1 12 6c6.4 0 10 7 10 7a17 17 0 0 1-3 4M6.6 6.7C3.7 8.5 2 12 2 12s3.6 7 10 7a10 10 0 0 0 4.5-1"/><path d="m2 2 20 20"/>',
    folder: '<path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/>',
    stop: '<rect x="6" y="6" width="12" height="12" rx="2.5"/>',
    globe:
      '<circle cx="12" cy="12" r="9"/><path d="M3 12h18"/><path d="M12 3a14 14 0 0 1 0 18 14 14 0 0 1 0-18Z"/>',
    wave: '<path d="M3 12h2M7 8v8M11 5v14M15 9v6M19 11v2"/>',
    closeSmall: '<path d="M18 6 6 18M6 6l12 12"/>',
  };

  function icon(name, size) {
    const body = P[name] || P.info;
    const s = size || 24;
    return (
      `<svg viewBox="0 0 24 24" width="${s}" height="${s}" fill="none" ` +
      `stroke="currentColor" stroke-width="1.9" stroke-linecap="round" ` +
      `stroke-linejoin="round" aria-hidden="true">${body}</svg>`
    );
  }

  function esc(s) {
    return String(s == null ? "" : s)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#39;");
  }

  /** Build an element tree from an HTML string. */
  function el(html) {
    const tpl = document.createElement("template");
    tpl.innerHTML = html.trim();
    return tpl.content.firstElementChild;
  }

  function qs(sel, root) {
    return (root || document).querySelector(sel);
  }
  function qsa(sel, root) {
    return Array.from((root || document).querySelectorAll(sel));
  }

  function fmtDuration(ms) {
    const total = Math.max(0, Math.floor(ms / 1000));
    const h = Math.floor(total / 3600);
    const m = Math.floor((total % 3600) / 60);
    const s = total % 60;
    const pad = (n) => String(n).padStart(2, "0");
    return h > 0 ? `${h}:${pad(m)}:${pad(s)}` : `${m}:${pad(s)}`;
  }

  function fmtDurationShort(ms) {
    const total = Math.max(0, Math.floor(ms / 1000));
    const h = Math.floor(total / 3600);
    const m = Math.floor((total % 3600) / 60);
    if (h > 0) return `${h}h ${m}m`;
    if (m > 0) return `${m}m`;
    return `${total}s`;
  }

  function fmtClock(sec) {
    const s = Math.max(0, Math.floor(sec));
    const m = Math.floor(s / 60);
    return `${String(m).padStart(2, "0")}:${String(s % 60).padStart(2, "0")}`;
  }

  function fmtDate(ts) {
    const d = new Date(ts);
    const pad = (n) => String(n).padStart(2, "0");
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(
      d.getHours()
    )}:${pad(d.getMinutes())}`;
  }

  function fmtTime(ts) {
    const d = new Date(ts);
    const pad = (n) => String(n).padStart(2, "0");
    return `${pad(d.getHours())}:${pad(d.getMinutes())}`;
  }

  function fmtSize(bytes) {
    if (!bytes && bytes !== 0) return "";
    if (bytes >= 1048576) return (bytes / 1048576).toFixed(1) + " MB";
    if (bytes >= 1024) return Math.round(bytes / 1024) + " KB";
    return bytes + " B";
  }

  function fmtRelative(ts, t) {
    const diff = Date.now() - ts;
    const min = Math.floor(diff / 60000);
    if (min < 1) return t("common.just_now");
    if (min < 60) return t("common.minutes_ago", { n: min });
    const hours = Math.floor(min / 60);
    if (hours < 24) return t("common.hours_ago", { n: hours });
    const days = Math.floor(hours / 24);
    if (days < 30) return t("common.days_ago", { n: days });
    return fmtDate(ts);
  }

  // ------------------------------------------------------------------ toasts
  let toastHost = null;
  function toast(message, kind) {
    toastHost = toastHost || document.getElementById("toasts");
    if (!toastHost) return;
    const node = el(
      `<div class="toast ${kind === "error" ? "error" : ""}">${icon(
        kind === "error" ? "alert" : "check",
        15
      )}<span>${esc(message)}</span></div>`
    );
    toastHost.appendChild(node);
    const remove = () => {
      node.classList.add("leaving");
      setTimeout(() => node.remove(), 260);
    };
    setTimeout(remove, kind === "error" ? 4200 : 2400);
  }

  // ------------------------------------------------------------------ dialogs
  function openDialog(opts) {
    const host = document.getElementById("dialog-root");
    const scrim = el(`<div class="scrim"></div>`);
    const dialog = el(
      `<div class="dialog ${opts.wide ? "wide" : ""}" role="dialog" aria-modal="true"></div>`
    );
    const head = el(
      `<div class="dialog-head"><div class="dialog-title">${esc(
        opts.title || ""
      )}</div></div>`
    );
    if (opts.closable !== false) {
      const closeBtn = el(
        `<button class="btn btn-icon btn-ghost" style="margin-left:auto" aria-label="close">${icon(
          "x",
          17
        )}</button>`
      );
      closeBtn.addEventListener("click", () => close());
      head.appendChild(closeBtn);
    }
    const body = el(`<div class="dialog-body"></div>`);
    if (typeof opts.body === "string") body.innerHTML = opts.body;
    else if (opts.body) body.appendChild(opts.body);

    const foot = el(`<div class="dialog-foot"></div>`);
    (opts.actions || []).forEach((a) => {
      const btn = el(
        `<button class="btn ${a.className || ""}">${a.icon ? icon(a.icon, 16) : ""}<span>${esc(
          a.label
        )}</span></button>`
      );
      btn.addEventListener("click", async () => {
        if (a.onClick) {
          const keep = await a.onClick();
          if (keep === false) return;
        }
        if (a.keepOpen !== true) close();
      });
      foot.appendChild(btn);
    });

    dialog.appendChild(head);
    dialog.appendChild(body);
    if ((opts.actions || []).length) dialog.appendChild(foot);
    scrim.appendChild(dialog);
    host.appendChild(scrim);

    let closed = false;
    function close() {
      if (closed) return;
      closed = true;
      scrim.style.animation = "fade-in 160ms reverse both";
      setTimeout(() => scrim.remove(), 150);
      if (opts.onClose) opts.onClose();
    }
    scrim.addEventListener("mousedown", (e) => {
      if (e.target === scrim && opts.closable !== false) close();
    });
    const onKey = (e) => {
      if (e.key === "Escape" && opts.closable !== false) {
        close();
        document.removeEventListener("keydown", onKey);
      }
    };
    document.addEventListener("keydown", onKey);

    // focus the first interactive element for keyboard users
    setTimeout(() => {
      const target = dialog.querySelector("button, input, textarea, select");
      if (target) target.focus();
    }, 60);

    return { close, body, dialog };
  }

  function confirm(opts) {
    return new Promise((resolve) => {
      let settled = false;
      const settle = (v) => {
        if (!settled) {
          settled = true;
          resolve(v);
        }
      };
      openDialog({
        title: opts.title,
        body: `<div class="small muted" style="line-height:1.65;padding-bottom:6px">${esc(
          opts.body || ""
        )}</div>`,
        onClose: () => settle(false),
        actions: [
          {
            label: opts.cancelLabel || "Cancel",
            onClick: () => settle(false),
          },
          {
            label: opts.confirmLabel || "OK",
            className: opts.danger ? "btn-danger" : "btn-primary",
            onClick: () => settle(true),
          },
        ],
      });
    });
  }

  // ------------------------------------------------------------------ misc
  function clickOutside(node, onOutside) {
    function handler(e) {
      if (!node.contains(e.target)) {
        onOutside();
        document.removeEventListener("mousedown", handler, true);
      }
    }
    setTimeout(() => document.addEventListener("mousedown", handler, true), 0);
  }

  function debounce(fn, ms) {
    let timer = null;
    return function (...args) {
      clearTimeout(timer);
      timer = setTimeout(() => fn.apply(this, args), ms);
    };
  }

  window.SP = window.SP || {};
  // Screens register themselves into this namespace as their scripts load.
  SP.screens = SP.screens || {};
  SP.ui = {
    icon,
    esc,
    el,
    qs,
    qsa,
    fmtDuration,
    fmtDurationShort,
    fmtClock,
    fmtDate,
    fmtTime,
    fmtSize,
    fmtRelative,
    toast,
    openDialog,
    confirm,
    clickOutside,
    debounce,
  };
})();
