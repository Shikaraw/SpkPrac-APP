/* Minimal Markdown renderer: headings, lists, quotes, fenced code, rules and inline emphasis.
   Deliberately small (no dependency) — the reports this renders are short and well-behaved. */
(function () {
  const { esc } = SP.ui;

  function inline(text) {
    let out = esc(text);
    // code spans first so their content is not touched by the other rules
    const codes = [];
    out = out.replace(/`([^`]+)`/g, (_, code) => {
      codes.push(code);
      return "\u0000" + (codes.length - 1) + "\u0000";
    });
    out = out
      .replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>")
      .replace(/__([^_]+)__/g, "<strong>$1</strong>")
      .replace(/(^|[^*])\*([^*\n]+)\*/g, "$1<em>$2</em>")
      .replace(/~~([^~]+)~~/g, "<del>$1</del>")
      .replace(
        /\[([^\]]+)\]\((https?:\/\/[^)\s]+)\)/g,
        '<a href="$2" target="_blank" rel="noreferrer">$1</a>'
      );
    out = out.replace(/\u0000(\d+)\u0000/g, (_, i) => "<code>" + codes[i] + "</code>");
    return out;
  }

  function render(src) {
    if (!src) return "";
    const lines = String(src).replace(/\r\n/g, "\n").split("\n");
    const out = [];
    let i = 0;
    let para = [];
    let list = null; // { ordered, items: [] }

    function flushPara() {
      if (para.length) {
        out.push("<p>" + para.map(inline).join("<br/>") + "</p>");
        para = [];
      }
    }
    function flushList() {
      if (list) {
        const tag = list.ordered ? "ol" : "ul";
        out.push(
          `<${tag}>` + list.items.map((it) => "<li>" + inline(it) + "</li>").join("") + `</${tag}>`
        );
        list = null;
      }
    }

    while (i < lines.length) {
      const line = lines[i];

      // fenced code
      if (/^\s*```/.test(line)) {
        flushPara();
        flushList();
        const buf = [];
        i++;
        while (i < lines.length && !/^\s*```/.test(lines[i])) {
          buf.push(lines[i]);
          i++;
        }
        i++; // closing fence
        out.push("<pre><code>" + esc(buf.join("\n")) + "</code></pre>");
        continue;
      }

      // horizontal rule
      if (/^\s*(---|\*\*\*|___)\s*$/.test(line)) {
        flushPara();
        flushList();
        out.push("<hr/>");
        i++;
        continue;
      }

      // heading
      const heading = line.match(/^(#{1,6})\s+(.*)$/);
      if (heading) {
        flushPara();
        flushList();
        const level = Math.min(3, heading[1].length);
        out.push(`<h${level}>` + inline(heading[2]) + `</h${level}>`);
        i++;
        continue;
      }

      // blockquote
      if (/^\s*>\s?/.test(line)) {
        flushPara();
        flushList();
        const buf = [];
        while (i < lines.length && /^\s*>\s?/.test(lines[i])) {
          buf.push(lines[i].replace(/^\s*>\s?/, ""));
          i++;
        }
        out.push("<blockquote>" + buf.map(inline).join("<br/>") + "</blockquote>");
        continue;
      }

      // unordered list
      const bullet = line.match(/^\s*[-*+]\s+(.*)$/);
      if (bullet) {
        flushPara();
        if (!list || list.ordered) {
          flushList();
          list = { ordered: false, items: [] };
        }
        list.items.push(bullet[1]);
        i++;
        continue;
      }

      // ordered list
      const numbered = line.match(/^\s*\d+[.)]\s+(.*)$/);
      if (numbered) {
        flushPara();
        if (!list || !list.ordered) {
          flushList();
          list = { ordered: true, items: [] };
        }
        list.items.push(numbered[1]);
        i++;
        continue;
      }

      // blank line terminates blocks
      if (!line.trim()) {
        flushPara();
        flushList();
        i++;
        continue;
      }

      // paragraph text
      flushList();
      para.push(line);
      i++;
    }

    flushPara();
    flushList();
    return out.join("");
  }

  window.SP = window.SP || {};
  SP.markdown = { render };
})();
