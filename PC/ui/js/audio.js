/* Capture + playback.

   Capture : getUserMedia (Chromium AEC/NS/AGC) -> AudioContext @16 kHz -> AudioWorklet -> 100 ms
             PCM16 frames -> base64 -> realtime socket.
   Playback: 24 kHz PCM16 deltas -> AudioBuffer queue scheduled on an AudioContext, with an explicit
             flush for barge-in.

   16 kHz capture with echo cancellation is the whole reason this runs in the webview instead of
   hand-rolled WASAPI code: Chromium already ships a reference AEC that works with any headset. */
(function () {
  const IN_RATE = 16000;
  const FRAME_SAMPLES = 1600; // 100 ms
  const OUT_RATE = 24000;

  const audio = {
    inCtx: null,
    outCtx: null,
    stream: null,
    node: null,
    source: null,
    sink: null, // playback gain node
    muted: false,
    transmitting: false,
    gain: 1.0,
    lastError: null,
    onFrame: null,
    onLevel: null,
    pending: new Float32Array(0),
    levelAcc: 0,
    levelCount: 0,
    lastLevelAt: 0,
    nextTime: 0,
    scheduled: 0,
  };

  // ------------------------------------------------------------------ helpers
  const B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
  function bytesToBase64(bytes) {
    let out = "";
    let i = 0;
    for (; i + 2 < bytes.length; i += 3) {
      const n = (bytes[i] << 16) | (bytes[i + 1] << 8) | bytes[i + 2];
      out += B64[(n >> 18) & 63] + B64[(n >> 12) & 63] + B64[(n >> 6) & 63] + B64[n & 63];
    }
    if (i < bytes.length) {
      const rem = bytes.length - i;
      const a = bytes[i];
      const b = rem > 1 ? bytes[i + 1] : 0;
      const n = (a << 16) | (b << 8);
      out += B64[(n >> 18) & 63] + B64[(n >> 12) & 63];
      out += rem > 1 ? B64[(n >> 6) & 63] : "=";
      out += "=";
    }
    return out;
  }

  function base64ToBytes(b64) {
    const clean = b64.replace(/[^A-Za-z0-9+/]/g, "");
    const len = (clean.length * 3) >> 2;
    const bytes = new Uint8Array(len);
    let p = 0;
    for (let i = 0; i < clean.length; i += 4) {
      const n =
        (B64.indexOf(clean[i]) << 18) |
        (B64.indexOf(clean[i + 1]) << 12) |
        ((clean[i + 2] ? B64.indexOf(clean[i + 2]) : 0) << 6) |
        (clean[i + 3] ? B64.indexOf(clean[i + 3]) : 0);
      if (p < len) bytes[p++] = (n >> 16) & 255;
      if (p < len) bytes[p++] = (n >> 8) & 255;
      if (p < len) bytes[p++] = n & 255;
    }
    return bytes;
  }

  // ------------------------------------------------------------------ capture

  function emitFrame(float32) {
    const n = float32.length;
    const pcm = new Int16Array(n);
    const g = audio.muted ? 0 : audio.gain;
    let sum = 0;
    for (let i = 0; i < n; i++) {
      let v = float32[i] * g;
      if (v > 1) v = 1;
      else if (v < -1) v = -1;
      sum += v * v;
      pcm[i] = v < 0 ? v * 0x8000 : v * 0x7fff;
    }
    const rms = Math.sqrt(sum / n);

    // Level is reported at ~20 Hz regardless of the render quantum size.
    audio.levelAcc += rms;
    audio.levelCount++;
    const now = performance.now();
    if (now - audio.lastLevelAt > 50) {
      const avg = audio.levelAcc / Math.max(1, audio.levelCount);
      audio.levelAcc = 0;
      audio.levelCount = 0;
      audio.lastLevelAt = now;
      if (audio.onLevel) {
        audio.onLevel(Math.min(1, Math.pow(avg * 5.2, 0.78)));
      }
    }

    if (!audio.transmitting || !audio.onFrame) return;
    audio.onFrame(bytesToBase64(new Uint8Array(pcm.buffer)));
  }

  function onAudioBlock(block) {
    // Accumulate into fixed 100 ms frames so the wire format matches the protocol exactly.
    const merged = new Float32Array(audio.pending.length + block.length);
    merged.set(audio.pending, 0);
    merged.set(block, audio.pending.length);
    let offset = 0;
    while (merged.length - offset >= FRAME_SAMPLES) {
      emitFrame(merged.subarray(offset, offset + FRAME_SAMPLES));
      offset += FRAME_SAMPLES;
    }
    audio.pending = merged.subarray(offset).slice();
  }

  async function ensureOutCtx() {
    if (audio.outCtx && audio.outCtx.state !== "closed") {
      if (audio.outCtx.state === "suspended") await audio.outCtx.resume();
      return audio.outCtx;
    }
    const ctx = new (window.AudioContext || window.webkitAudioContext)();
    audio.sink = ctx.createGain();
    audio.sink.gain.value = 1;
    audio.sink.connect(ctx.destination);
    audio.outCtx = ctx;
    audio.nextTime = 0;
    return ctx;
  }

  async function start(opts) {
    const options = opts || {};
    audio.onFrame = options.onFrame || null;
    audio.onLevel = options.onLevel || null;
    audio.gain = options.gain == null ? 1 : options.gain;
    audio.muted = false;
    audio.lastError = null;
    audio.pending = new Float32Array(0);

    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
      audio.lastError = "mediaDevices unavailable";
      return false;
    }

    let stream;
    try {
      const audio1 = {
        echoCancellation: true,
        noiseSuppression: true,
        autoGainControl: true,
        channelCount: 1,
      };
      if (options.inputDeviceId) {
        audio1.deviceId = { exact: options.inputDeviceId };
      }
      stream = await navigator.mediaDevices.getUserMedia({ audio: audio1, video: false });
    } catch (e) {
      audio.lastError = describeMediaError(e);
      return false;
    }
    audio.stream = stream;

    try {
      const ctx = new (window.AudioContext || window.webkitAudioContext)({
        sampleRate: IN_RATE,
      });
      if (ctx.state === "suspended") await ctx.resume();
      const source = ctx.createMediaStreamSource(stream);
      let node = null;

      if (ctx.audioWorklet) {
        try {
          await ctx.audioWorklet.addModule("js/pcm-worklet.js");
          const worklet = new AudioWorkletNode(ctx, "pcm-capture");
          worklet.port.onmessage = (e) => onAudioBlock(e.data);
          node = worklet;
        } catch (e) {
          node = null;
        }
      }
      if (!node) {
        // Fallback for webviews where the worklet module cannot load from the custom protocol.
        const proc = ctx.createScriptProcessor(2048, 1, 1);
        proc.onaudioprocess = (e) => {
          onAudioBlock(e.inputBuffer.getChannelData(0).slice(0));
        };
        node = proc;
      }
      source.connect(node);
      // A ScriptProcessor only runs when it is connected to a destination; route it into a muted sink.
      if (node instanceof ScriptProcessorNode) {
        const mute = ctx.createGain();
        mute.gain.value = 0;
        node.connect(mute);
        mute.connect(ctx.destination);
      }
      audio.inCtx = ctx;
      audio.node = node;
      audio.source = source;
    } catch (e) {
      audio.lastError = "audio pipeline: " + (e && e.message ? e.message : e);
      await stop();
      return false;
    }

    const out = await ensureOutCtx();
    if (options.outputDeviceId) await setSink(options.outputDeviceId);
    else if (audio.sinkId) await setSink(audio.sinkId);
    if (out.state === "suspended") await out.resume();

    audio.transmitting = true;
    return true;
  }

  function describeMediaError(e) {
    const name = (e && e.name) || "";
    if (name === "NotAllowedError" || name === "SecurityError") return "mic-denied";
    if (name === "NotFoundError" || name === "OverconstrainedError") return "mic-missing";
    if (name === "NotReadableError") return "mic-busy";
    return "mic-error:" + (e && e.message ? e.message : name);
  }

  async function stop() {
    audio.transmitting = false;
    audio.onFrame = null;
    audio.onLevel = null;
    try {
      if (audio.node) audio.node.disconnect();
    } catch (_) {}
    try {
      if (audio.source) audio.source.disconnect();
    } catch (_) {}
    audio.node = null;
    audio.source = null;
    if (audio.stream) {
      audio.stream.getTracks().forEach((t) => {
        try {
          t.stop();
        } catch (_) {}
      });
      audio.stream = null;
    }
    if (audio.inCtx) {
      try {
        await audio.inCtx.close();
      } catch (_) {}
      audio.inCtx = null;
    }
    flush();
  }

  async function closeAll() {
    await stop();
    if (audio.outCtx) {
      try {
        await audio.outCtx.close();
      } catch (_) {}
      audio.outCtx = null;
      audio.sink = null;
    }
  }

  // ------------------------------------------------------------------ playback

  function playPcm(arrayBuffer) {
    const ctx = audio.outCtx;
    const sink = audio.sink;
    if (!ctx || !sink || ctx.state === "closed") return;
    const bytes = arrayBuffer instanceof Uint8Array ? arrayBuffer : new Uint8Array(arrayBuffer);
    const samples = new Int16Array(bytes.buffer, bytes.byteOffset, bytes.byteLength >> 1);
    if (!samples.length) return;

    const buffer = ctx.createBuffer(1, samples.length, OUT_RATE);
    const channel = buffer.getChannelData(0);
    for (let i = 0; i < samples.length; i++) channel[i] = samples[i] / 32768;

    const src = ctx.createBufferSource();
    src.buffer = buffer;
    src.connect(sink);

    const now = ctx.currentTime;
    // Underrun (or first chunk): restart the schedule slightly ahead so playback stays gapless.
    if (audio.nextTime < now + 0.02) audio.nextTime = now + 0.06;
    src.start(audio.nextTime);
    audio.nextTime += buffer.duration;
    audio.scheduled++;
    src.onended = () => {
      audio.scheduled = Math.max(0, audio.scheduled - 1);
    };
  }

  /** Barge-in: drop everything already queued so the assistant stops mid-sentence. */
  function flush() {
    const ctx = audio.outCtx;
    if (!ctx) return;
    audio.nextTime = 0;
    audio.scheduled = 0;
    // Replacing the sink gain node is the only reliable way to cut already-scheduled sources.
    try {
      if (audio.sink) {
        audio.sink.disconnect();
      }
      const fresh = ctx.createGain();
      fresh.gain.value = 1;
      fresh.connect(ctx.destination);
      audio.sink = fresh;
    } catch (_) {}
  }

  // ------------------------------------------------------------------ devices

  async function enumerate() {
    if (!navigator.mediaDevices || !navigator.mediaDevices.enumerateDevices) {
      return { inputs: [], outputs: [] };
    }
    let devices = [];
    try {
      // Some webviews never settle this promise until a stream exists; never block the UI on it.
      devices = await Promise.race([
        navigator.mediaDevices.enumerateDevices(),
        new Promise((resolve) => setTimeout(() => resolve([]), 1500)),
      ]);
    } catch (_) {
      return { inputs: [], outputs: [] };
    }
    if (!Array.isArray(devices)) return { inputs: [], outputs: [] };
    const inputs = [];
    const outputs = [];
    devices.forEach((d) => {
      const label = d.label || "";
      if (d.kind === "audioinput") inputs.push({ id: d.deviceId, label });
      if (d.kind === "audiooutput") outputs.push({ id: d.deviceId, label });
    });
    // Labels stay empty until the user granted microphone access at least once.
    return { inputs, outputs };
  }

  async function setSink(deviceId) {
    audio.sinkId = deviceId || "";
    const ctx = audio.outCtx;
    if (!ctx || typeof ctx.setSinkId !== "function") return false;
    try {
      await ctx.setSinkId(deviceId || "");
      return true;
    } catch (e) {
      audio.lastError = "sink: " + (e && e.message ? e.message : e);
      return false;
    }
  }

  /** Creates and resumes the playback context inside a user gesture so later playback never blocks. */
  async function prime() {
    try {
      const ctx = await ensureOutCtx();
      if (ctx.state === "suspended") await ctx.resume();
    } catch (_) {}
  }

  function setMuted(v) {
    audio.muted = !!v;
  }
  function setTransmit(v) {
    audio.transmitting = !!v && !audio.muted;
  }
  function setGain(g) {
    audio.gain = Math.max(0.1, Math.min(3, Number(g) || 1));
  }
  function level() {
    return 0;
  }

  window.SP = window.SP || {};
  SP.audio = {
    start,
    stop,
    closeAll,
    playPcm,
    flush,
    enumerate,
    setSink,
    setMuted,
    setTransmit,
    setGain,
    prime,
    bytesToBase64,
    base64ToBytes,
    get lastError() {
      return audio.lastError;
    },
    get running() {
      return !!audio.inCtx;
    },
  };
})();
