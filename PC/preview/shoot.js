#!/usr/bin/env node
/* Preview-only screenshot helper: drives headless Edge over the DevTools protocol using only Node
   built-ins (fetch + WebSocket). Real-time waiting avoids the virtual-time screenshots freezing
   late DOM updates, so the selected-text toolbar and dialogs show up reliably.

   Usage:
     node shoot.js --url=<url> --out=<file.png> [--wait=<ms>] [--eval=<js>] [--eval=<js>]...
   Every --eval expression result is printed as JSON on stdout. */
const { spawn } = require("child_process");
const fs = require("fs");
const os = require("os");
const path = require("path");

const EDGE =
  process.env.EDGE_PATH ||
  "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe";

function argValue(argv, name, fallback) {
  for (let i = 0; i < argv.length; i += 1) {
    const item = argv[i];
    if (item === "--" + name) return argv[i + 1];
    if (item.startsWith("--" + name + "=")) return item.slice(name.length + 3);
  }
  return fallback;
}

function argList(argv, name) {
  const values = [];
  for (let i = 0; i < argv.length; i += 1) {
    const item = argv[i];
    if (item === "--" + name && argv[i + 1] !== undefined) values.push(argv[i + 1]);
    else if (item.startsWith("--" + name + "=")) values.push(item.slice(name.length + 3));
  }
  return values;
}

const argv = process.argv.slice(2);
const url = argValue(argv, "url");
const out = argValue(argv, "out");
const waitMs = Number(argValue(argv, "wait", "2500"));
const evals = argList(argv, "eval");
const evalFiles = argList(argv, "eval-file");
evalFiles.forEach((file) => {
  try {
    evals.push(fs.readFileSync(file, "utf8"));
  } catch (error) {
    evals.push("(() => ({ evalFileError: " + JSON.stringify(String(error)) + " }))()");
  }
});
const width = Number(argValue(argv, "width", "1280"));
const height = Number(argValue(argv, "height", "900"));

if (!url || !out) {
  console.error("usage: node shoot.js --url=<url> --out=<file.png> [--wait=ms] [--eval=js]");
  process.exit(2);
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function connect(wsUrl) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(wsUrl);
    const pending = new Map();
    let nextId = 0;
    ws.addEventListener("open", () => {
      resolve({
        send(method, params) {
          return new Promise((res, rej) => {
            const id = (nextId += 1);
            pending.set(id, { res, rej });
            ws.send(JSON.stringify({ id, method, params: params || {} }));
          });
        },
        close() {
          try {
            ws.close();
          } catch (_) {}
        },
      });
    });
    ws.addEventListener("message", (event) => {
      let msg;
      try {
        msg = JSON.parse(event.data);
      } catch (_) {
        return;
      }
      if (!msg.id || !pending.has(msg.id)) return;
      const { res, rej } = pending.get(msg.id);
      pending.delete(msg.id);
      if (msg.error) rej(new Error(msg.error.message));
      else res(msg.result);
    });
    ws.addEventListener("error", (err) => reject(err));
  });
}

async function main() {
  const port = 9300 + Math.floor(Math.random() * 600);
  const profileDir = fs.mkdtempSync(path.join(os.tmpdir(), "spkprac-shot-"));
  const edge = spawn(
    EDGE,
    [
      "--headless=new",
      "--disable-gpu",
      "--hide-scrollbars",
      "--no-first-run",
      "--disable-extensions",
      "--remote-debugging-port=" + port,
      "--user-data-dir=" + profileDir,
      "--window-size=" + width + "," + height,
      "about:blank",
    ],
    { stdio: "ignore", windowsHide: true }
  );

  let client = null;
  try {
    let version = null;
    for (let i = 0; i < 120; i += 1) {
      try {
        const res = await fetch("http://127.0.0.1:" + port + "/json/version");
        if (res.ok) {
          version = await res.json();
          break;
        }
      } catch (_) {
        /* not up yet */
      }
      await sleep(100);
    }
    if (!version) throw new Error("Edge DevTools endpoint did not start");

    const created = await fetch(
      "http://127.0.0.1:" + port + "/json/new?" + encodeURIComponent(url),
      { method: "PUT" }
    );
    if (!created.ok) throw new Error("could not open a DevTools target");
    const target = await created.json();

    client = await connect(target.webSocketDebuggerUrl);
    await client.send("Page.enable");
    await client.send("Runtime.enable");
    await client.send("Emulation.setDeviceMetricsOverride", {
      width,
      height,
      deviceScaleFactor: 1,
      mobile: false,
    });
    await client.send("Page.navigate", { url });

    for (let i = 0; i < 100; i += 1) {
      const ready = await client.send("Runtime.evaluate", {
        expression: "document.readyState",
        returnByValue: true,
      });
      if (ready.result && ready.result.value === "complete") break;
      await sleep(100);
    }

    await sleep(waitMs);

    const evalResults = [];
    for (const expression of evals) {
      try {
        const result = await client.send("Runtime.evaluate", {
          expression,
          returnByValue: true,
          awaitPromise: true,
        });
        evalResults.push(result.result ? result.result.value : null);
      } catch (error) {
        evalResults.push({ evalError: String(error && error.message ? error.message : error) });
      }
    }

    const shot = await client.send("Page.captureScreenshot", { format: "png" });
    fs.writeFileSync(out, Buffer.from(shot.data, "base64"));
    console.log(JSON.stringify({ out, url, evals: evalResults }, null, 2));
  } finally {
    if (client) client.close();
    try {
      edge.kill();
    } catch (_) {}
    await sleep(300);
    try {
      fs.rmSync(profileDir, { recursive: true, force: true });
    } catch (_) {}
  }
}

main().catch((error) => {
  console.error("shoot failed:", error && error.stack ? error.stack : error);
  process.exit(1);
});
