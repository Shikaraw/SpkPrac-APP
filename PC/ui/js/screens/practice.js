/* Practice setup: language, level, topic, correction policy and the two content toggles. */
(function () {
  const { icon, el, esc } = SP.ui;
  const t = (k, v) => SP.i18n.t(k, v);

  const LEVELS = [
    ["A1-A2", "practice.level_a1"],
    ["A2-B1", "practice.level_a2"],
    ["B1-B2", "practice.level_b1"],
    ["C1-C2", "practice.level_c1"],
  ];

  const TOPICS = [
    ["food", "点餐"],
    ["travel", "旅行"],
    ["work", "工作"],
    ["hobby", "兴趣爱好"],
    ["movie", "电影"],
    ["study", "学习"],
    ["health", "健康"],
    ["tech", "科技"],
  ];

  function card(titleKey, iconName, inner) {
    return `<div class="card">
      <div class="card-title">${icon(iconName, 15)}<span>${esc(t(titleKey))}</span></div>
      ${inner}
    </div>`;
  }

  function segmented(items, active) {
    return `<div class="segmented">
      ${items
        .map(
          ([value, label]) =>
            `<button class="segmented-item ${
              value === active ? "active" : ""
            }" data-value="${esc(value)}">${esc(label)}</button>`
        )
        .join("")}
    </div>`;
  }

  async function render() {
    const s = SP.store.settings;
    const langOptions = SP.languages.list
      .map(
        ([code, name]) =>
          `<option value="${esc(code)}" ${s.pracLang === code ? "selected" : ""}>${esc(
            name
          )}</option>`
      )
      .join("");

    const content = el(`<div class="setup-grid">
      <div class="stack">
        ${card(
          "practice.language",
          "globe",
          `<select class="select" id="prac-lang">${langOptions}</select>`
        )}

        ${card("practice.level", "activity", segmented(
          LEVELS.map(([v, k]) => [v, t(k)]),
          s.pracLevel
        ))}

        ${card(
          "practice.topic",
          "message",
          `<input class="input" id="prac-topic" placeholder="${esc(
            t("practice.topic_ph")
          )}" value="${esc(s.pracTopic || "")}" />
           <div class="row-wrap" style="margin-top:10px" id="topic-chips">
             ${TOPICS.map(
               ([key, label]) =>
                 `<button class="chip" data-topic="${esc(label)}">${esc(label)}</button>`
             ).join("")}
           </div>`
        )}

        ${card(
          "practice.correction",
          "pencil",
          segmented(
            [
              ["NEVER", t("practice.correction_never")],
              ["SOMETIMES", t("practice.correction_sometimes")],
              ["ALWAYS", t("practice.correction_always")],
            ],
            s.pracCorrection
          )
        )}

        <div class="card">
          <div class="switch-row" id="row-report">
            <div class="switch-text">
              <div class="switch-title">${esc(t("practice.report"))}</div>
              <div class="switch-sub">${esc(t("practice.report_desc"))}</div>
            </div>
            <div class="grow"></div>
            <div class="switch ${s.pracReport ? "on" : ""}" id="sw-report"></div>
          </div>
          <div class="switch-row" id="row-translate">
            <div class="switch-text">
              <div class="switch-title">${esc(t("practice.translate"))}</div>
              <div class="switch-sub">${esc(t("practice.translate_desc"))}</div>
            </div>
            <div class="grow"></div>
            <div class="switch ${s.translateEnabled ? "on" : ""}" id="sw-translate"></div>
          </div>
        </div>
      </div>

      <div class="setup-side">
        <div class="card">
          <div class="card-title">${icon("sparkles", 15)}<span>${esc(
            t("practice.summary")
          )}</span></div>
          <div class="summary-row">
            <span class="summary-key">${esc(t("practice.language"))}</span>
            <span class="summary-val" id="sum-lang"></span>
          </div>
          <div class="summary-row">
            <span class="summary-key">${esc(t("practice.level"))}</span>
            <span class="summary-val" id="sum-level"></span>
          </div>
          <div class="summary-row">
            <span class="summary-key">${esc(t("practice.correction"))}</span>
            <span class="summary-val" id="sum-correction"></span>
          </div>
          <button class="btn btn-hero" id="start-practice" style="margin-top:18px">
            ${icon("play", 18)}
            <span>${esc(t("practice.start"))}</span>
          </button>
        </div>
      </div>
    </div>`);

    // ---- language
    const langSel = content.querySelector("#prac-lang");
    langSel.addEventListener("change", () => {
      SP.store.patch({ pracLang: langSel.value });
      updateSummary();
    });

    // ---- level
    content.querySelectorAll(".segmented").forEach((seg) => {
      const isLevel = seg.closest(".card").textContent.includes(t("practice.level"));
      seg.querySelectorAll(".segmented-item").forEach((btn) => {
        btn.addEventListener("click", () => {
          seg
            .querySelectorAll(".segmented-item")
            .forEach((b) => b.classList.remove("active"));
          btn.classList.add("active");
          if (isLevel) SP.store.patch({ pracLevel: btn.dataset.value });
          else SP.store.patch({ pracCorrection: btn.dataset.value });
          updateSummary();
        });
      });
    });

    // ---- topic
    const topicInput = content.querySelector("#prac-topic");
    topicInput.addEventListener(
      "input",
      SP.ui.debounce(() => {
        SP.store.patch({ pracTopic: topicInput.value });
      }, 350)
    );
    content.querySelectorAll("[data-topic]").forEach((chip) => {
      chip.addEventListener("click", () => {
        const value = chip.dataset.topic;
        topicInput.value = topicInput.value === value ? "" : value;
        SP.store.patch({ pracTopic: topicInput.value });
        content.querySelectorAll("[data-topic]").forEach((c) => c.classList.remove("active"));
        if (topicInput.value) chip.classList.add("active");
      });
    });

    // ---- toggles
    content.querySelector("#row-report").addEventListener("click", () => {
      const next = !SP.store.settings.pracReport;
      SP.store.patch({ pracReport: next });
      content.querySelector("#sw-report").classList.toggle("on", next);
    });
    content.querySelector("#row-translate").addEventListener("click", () => {
      const next = !SP.store.settings.translateEnabled;
      SP.store.patch({ translateEnabled: next });
      content.querySelector("#sw-translate").classList.toggle("on", next);
    });

    function updateSummary() {
      const cur = SP.store.settings;
      content.querySelector("#sum-lang").textContent = SP.languages.name(cur.pracLang);
      const lvl = LEVELS.find(([v]) => v === cur.pracLevel);
      content.querySelector("#sum-level").textContent = lvl ? t(lvl[1]) : cur.pracLevel;
      content.querySelector("#sum-correction").textContent = t(
        cur.pracCorrection === "NEVER"
          ? "practice.correction_never"
          : cur.pracCorrection === "ALWAYS"
          ? "practice.correction_always"
          : "practice.correction_sometimes"
      );
    }
    updateSummary();

    // ---- start
    content.querySelector("#start-practice").addEventListener("click", () => {
      const cur = SP.store.settings;
      SP.screens.session.open({
        mode: "PRACTICE",
        pracLang: cur.pracLang,
        pracLevel: cur.pracLevel,
        pracTopic: cur.pracTopic || "",
        policy: cur.pracCorrection,
      });
    });

    return {
      titleKey: "practice.title",
      subKey: "nav.practice",
      content,
    };
  }

  SP.screens.practice = { render };
})();
