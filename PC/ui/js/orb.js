/* Canvas voice orb — no animation library, everything is drawn by hand so the package stays tiny.
   States: idle | connecting | listening | thinking | speaking | error. */
(function () {
  function readColors() {
    const css = getComputedStyle(document.documentElement);
    return {
      accent: css.getPropertyValue("--accent").trim() || "#2dd4bf",
      teal300: css.getPropertyValue("--teal-300").trim() || "#6ee7d7",
      aqua: css.getPropertyValue("--aqua-400").trim() || "#22d3ee",
      coral: css.getPropertyValue("--coral-400").trim() || "#fb7185",
      text: css.getPropertyValue("--text").trim() || "#e9f6f2",
      dark: document.documentElement.dataset.theme === "dark",
    };
  }

  function rgba(hex, alpha) {
    const h = hex.replace("#", "");
    const n = parseInt(
      h.length === 3
        ? h.split("").map((c) => c + c).join("")
        : h,
      16
    );
    return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${alpha})`;
  }

  function create(canvas) {
    const ctx = canvas.getContext("2d");
    let state = "idle";
    let level = 0;
    let targetLevel = 0;
    let raf = null;
    let phase = 0;
    let colors = readColors();
    let dpr = window.devicePixelRatio || 1;
    const reduced = () => document.documentElement.dataset.animations === "off";

    function resize() {
      const rect = canvas.getBoundingClientRect();
      const w = Math.max(1, Math.round(rect.width));
      const h = Math.max(1, Math.round(rect.height));
      dpr = window.devicePixelRatio || 1;
      canvas.width = Math.round(w * dpr);
      canvas.height = Math.round(h * dpr);
    }

    function draw(now) {
      const w = canvas.width;
      const h = canvas.height;
      if (!w || !h) return;
      const cx = w / 2;
      const cy = h / 2;
      const unit = Math.min(w, h);
      const R = unit * 0.268;

      // Smooth the level with a fast attack and a slow release.
      level += (targetLevel - level) * (targetLevel > level ? 0.35 : 0.12);

      ctx.clearRect(0, 0, w, h);
      ctx.save();

      const breathe = 1 + Math.sin(now / 1500) * (state === "idle" ? 0.028 : 0.012);
      const energy = state === "listening" ? level : state === "speaking" ? 0.55 : 0;
      const scale = breathe * (1 + energy * 0.16);

      const isError = state === "error";
      const c1 = isError ? colors.coral : colors.aqua;
      const c2 = isError ? colors.coral : colors.teal300;

      // ---------------- outer aura
      const auraR = R * (2.05 + energy * 0.55);
      const aura = ctx.createRadialGradient(cx, cy, R * 0.4, cx, cy, auraR);
      aura.addColorStop(0, rgba(c1, 0.3 + energy * 0.16));
      aura.addColorStop(0.55, rgba(c1, 0.1 + energy * 0.08));
      aura.addColorStop(1, rgba(c1, 0));
      ctx.fillStyle = aura;
      ctx.beginPath();
      ctx.arc(cx, cy, auraR, 0, Math.PI * 2);
      ctx.fill();

      // ---------------- expanding waves (speaking / listening peaks)
      if (state === "speaking" || (state === "listening" && level > 0.32)) {
        const count = 3;
        for (let i = 0; i < count; i++) {
          const progress = ((now / 1900 + i / count) % 1 + 1) % 1;
          const ringR = R * (1.05 + progress * 1.15);
          const alpha = (1 - progress) * (state === "speaking" ? 0.34 : 0.3);
          ctx.strokeStyle = rgba(c2, alpha);
          ctx.lineWidth = Math.max(1, unit * 0.005 * (1 - progress * 0.5));
          ctx.beginPath();
          ctx.arc(cx, cy, ringR, 0, Math.PI * 2);
          ctx.stroke();
        }
      }

      // ---------------- orbit arcs (connecting / thinking)
      if (state === "connecting" || state === "thinking") {
        const speed = state === "connecting" ? 2.4 : 1.5;
        const arcs = [
          { r: R * 1.42, span: 1.25, offset: 0, alpha: 0.5, width: 0.017 },
          { r: R * 1.62, span: 0.75, offset: 2.1, alpha: 0.32, width: 0.011 },
        ];
        arcs.forEach((a, idx) => {
          const start = (now / 1000) * speed * (idx % 2 === 0 ? 1 : -0.72) + a.offset;
          ctx.strokeStyle = rgba(c2, a.alpha);
          ctx.lineWidth = Math.max(1.2, unit * a.width);
          ctx.lineCap = "round";
          ctx.beginPath();
          ctx.arc(cx, cy, a.r, start, start + a.span);
          ctx.stroke();
        });
      }

      // ---------------- core sphere
      const coreR = R * scale;
      const grad = ctx.createRadialGradient(
        cx - coreR * 0.36,
        cy - coreR * 0.42,
        coreR * 0.12,
        cx,
        cy,
        coreR
      );
      if (isError) {
        grad.addColorStop(0, rgba(c1, 0.95));
        grad.addColorStop(0.55, rgba(c1, 0.65));
        grad.addColorStop(1, rgba(c1, 0.2));
      } else {
        grad.addColorStop(0, rgba("#ffffff", colors.dark ? 0.92 : 0.98));
        grad.addColorStop(0.28, rgba(c2, 0.95));
        grad.addColorStop(0.72, rgba(c1, 0.85));
        grad.addColorStop(1, rgba(c1, 0.28));
      }
      ctx.fillStyle = grad;
      ctx.beginPath();
      ctx.arc(cx, cy, coreR, 0, Math.PI * 2);
      ctx.fill();

      // inner highlight
      const hl = ctx.createRadialGradient(
        cx - coreR * 0.34,
        cy - coreR * 0.4,
        0,
        cx - coreR * 0.34,
        cy - coreR * 0.4,
        coreR * 0.72
      );
      hl.addColorStop(0, rgba("#ffffff", isError ? 0.35 : 0.62));
      hl.addColorStop(1, rgba("#ffffff", 0));
      ctx.fillStyle = hl;
      ctx.beginPath();
      ctx.arc(cx, cy, coreR, 0, Math.PI * 2);
      ctx.fill();

      // rim
      ctx.strokeStyle = rgba("#ffffff", colors.dark ? 0.16 : 0.5);
      ctx.lineWidth = Math.max(1, unit * 0.0035);
      ctx.beginPath();
      ctx.arc(cx, cy, coreR * 0.995, 0, Math.PI * 2);
      ctx.stroke();

      // ---------------- level ring while listening
      if (state === "listening" && level > 0.02) {
        ctx.strokeStyle = rgba(c2, 0.28 + level * 0.5);
        ctx.lineWidth = Math.max(1.5, unit * 0.006 * (1 + level));
        ctx.beginPath();
        ctx.arc(cx, cy, coreR * (1.16 + level * 0.22), 0, Math.PI * 2);
        ctx.stroke();
      }

      // ---------------- thinking dots orbiting the core
      if (state === "thinking") {
        for (let i = 0; i < 3; i++) {
          const a = (now / 620 + (i * Math.PI * 2) / 3) % (Math.PI * 2);
          const rr = coreR * 1.22;
          const x = cx + Math.cos(a) * rr;
          const y = cy + Math.sin(a) * rr;
          ctx.fillStyle = rgba(c2, 0.85 - i * 0.18);
          ctx.beginPath();
          ctx.arc(x, y, Math.max(2, unit * 0.011), 0, Math.PI * 2);
          ctx.fill();
        }
      }

      ctx.restore();
    }

    function loop(now) {
      phase = now;
      draw(now || 0);
      if (reduced()) {
        raf = null;
        return;
      }
      raf = requestAnimationFrame(loop);
    }

    function kick() {
      if (raf != null) return;
      if (reduced()) {
        draw(performance.now());
        return;
      }
      raf = requestAnimationFrame(loop);
    }

    function halt() {
      if (raf != null) cancelAnimationFrame(raf);
      raf = null;
      draw(performance.now());
    }

    const onResize = () => {
      resize();
      draw(performance.now());
    };
    window.addEventListener("resize", onResize);
    resize();
    draw(performance.now());
    kick();

    return {
      setState(next) {
        if (state === next) return;
        state = next;
        if (next === "idle" || next === "error") targetLevel = 0;
        if (reduced()) draw(performance.now());
        else kick();
      },
      setLevel(v) {
        targetLevel = Math.max(0, Math.min(1, v || 0));
        if (reduced()) draw(performance.now());
      },
      refreshTheme() {
        colors = readColors();
        draw(performance.now());
      },
      stop() {
        if (raf != null) cancelAnimationFrame(raf);
        raf = null;
        window.removeEventListener("resize", onResize);
      },
      redraw() {
        resize();
        draw(performance.now());
      },
      get state() {
        return state;
      },
    };
  }

  function stateOf(status, agentState) {
    if (status === "FAILED") return "error";
    if (status === "CONNECTING" || status === "RECONNECTING") return "connecting";
    if (status !== "CONNECTED") return "idle";
    if (agentState === "THINKING") return "thinking";
    if (agentState === "SPEAKING") return "speaking";
    return "listening";
  }

  window.SP = window.SP || {};
  SP.orb = { create, stateOf };
})();
