/* Realtime protocol client: parses server events and emits the client events the app needs.
   Transport (websocket, auth header) lives in Rust; everything here is pure protocol. */
(function () {
  const listeners = { event: null, status: null };
  let closedByUser = false;
  let connected = false;

  function text(json) {
    return JSON.stringify(json);
  }

  function send(obj) {
    if (closedByUser) return false;
    SP.api.rtSend(text(obj));
    return true;
  }

  function str(obj, key) {
    const v = obj && obj[key];
    return typeof v === "string" ? v : null;
  }

  function responseIdOf(root) {
    return (
      str(root, "response_id") ||
      (root.response && typeof root.response.id === "string" ? root.response.id : null)
    );
  }

  /** lenient parse -> plain object the session controller switches on */
  function parse(raw) {
    let root;
    try {
      root = typeof raw === "string" ? JSON.parse(raw) : raw;
    } catch (e) {
      return { type: "error", errorType: "parse_error", message: String(e) };
    }
    const type = str(root, "type");
    if (!type) return { type: "error", message: "event without type" };

    switch (type) {
      case "session.created":
        return {
          type: "sessionCreated",
          model: root.session ? str(root.session, "model") : null,
        };
      case "session.updated":
        return { type: "sessionUpdated" };
      case "input_audio_buffer.speech_started":
        return { type: "speechStarted", itemId: str(root, "item_id") };
      case "input_audio_buffer.speech_stopped":
        return { type: "speechStopped", audioEndMs: root.audio_end_ms || null };
      case "input_audio_buffer.committed":
        return { type: "inputCommitted", itemId: str(root, "item_id") };
      case "conversation.item.input_audio_transcription.delta":
        return {
          type: "userTranscriptDelta",
          itemId: str(root, "item_id"),
          text: str(root, "text") || "",
          stash: str(root, "stash"),
        };
      case "conversation.item.input_audio_transcription.completed":
        return {
          type: "userTranscriptDone",
          itemId: str(root, "item_id"),
          transcript: str(root, "transcript") || "",
        };
      case "response.created":
        return { type: "responseCreated", responseId: responseIdOf(root) };
      case "response.audio_transcript.delta":
      case "response.output_audio_transcript.delta":
        return {
          type: "agentTranscriptDelta",
          responseId: responseIdOf(root),
          delta: str(root, "delta") || "",
        };
      case "response.audio_transcript.done":
      case "response.output_audio_transcript.done":
        return {
          type: "agentTranscriptDone",
          responseId: responseIdOf(root),
          transcript: str(root, "transcript") || "",
        };
      case "response.audio.delta":
      case "response.output_audio.delta": {
        const b64 = str(root, "delta");
        if (!b64) return null;
        return { type: "audioDelta", responseId: responseIdOf(root), audio: b64 };
      }
      case "response.audio.done":
      case "response.output_audio.done":
        return { type: "audioDone" };
      case "response.done": {
        const resp = root.response || {};
        return {
          type: "responseDone",
          status: str(resp, "status"),
          responseId: str(resp, "id") || responseIdOf(root),
        };
      }
      case "error": {
        const err = root.error || {};
        return {
          type: "error",
          errorType: str(err, "type"),
          code: str(err, "code"),
          message: str(err, "message") || type,
        };
      }
      default:
        return null;
    }
  }

  // Fan-out from the Rust bridge.
  SP.api.onRtEvent((raw) => {
    const evt = parse(raw);
    if (evt && listeners.event) listeners.event(evt);
  });
  SP.api.onRtStatus((payload) => {
    if (!payload) return;
    if (payload.kind === "open") {
      connected = true;
    } else {
      connected = false;
    }
    if (listeners.status) listeners.status(payload);
  });

  window.SP = window.SP || {};
  SP.rt = {
    get connected() {
      return connected;
    },

    async connect({ endpoint, apiKey, model, onEvent, onStatus }) {
      listeners.event = onEvent || null;
      listeners.status = onStatus || null;
      closedByUser = false;
      connected = false;
      await SP.api.rtConnect(endpoint, apiKey, model);
    },

    close() {
      closedByUser = true;
      connected = false;
      SP.api.rtClose();
    },

    /** session.update — mirrors the Android client exactly (pcm16 vs pcm naming included). */
    sendSessionUpdate(cfg) {
      const pcmName = cfg.flavor === "OPENAI" ? "pcm16" : "pcm";
      const session = {
        modalities: ["text", "audio"],
        instructions: cfg.instructions,
        input_audio_format: pcmName,
        output_audio_format: pcmName,
        input_audio_transcription: {},
      };
      if (cfg.voice) session.voice = cfg.voice;
      if (cfg.flavor === "OPENAI") session.input_audio_transcription.model = "whisper-1";
      if (cfg.asrLang) session.input_audio_transcription.language = cfg.asrLang;
      if (cfg.vadMode === "AUTO") {
        session.turn_detection = {
          type: "server_vad",
          threshold: cfg.vadThreshold,
          silence_duration_ms: cfg.silenceMs,
        };
      } else {
        session.turn_detection = null;
      }
      return send({ type: "session.update", session });
    },

    sendAudio(base64Pcm) {
      return send({ type: "input_audio_buffer.append", audio: base64Pcm });
    },

    commitAudio() {
      return send({ type: "input_audio_buffer.commit" });
    },

    clearAudioBuffer() {
      return send({ type: "input_audio_buffer.clear" });
    },

    triggerResponse() {
      return send({ type: "response.create" });
    },

    cancelResponse() {
      return send({ type: "response.cancel" });
    },

    sendUserText(content) {
      const created = send({
        type: "conversation.item.create",
        item: {
          type: "message",
          role: "user",
          content: [{ type: "input_text", text: content }],
        },
      });
      return created && this.triggerResponse();
    },

    /** Context restore after a reconnect. */
    replayMessage(roleUser, content) {
      return send({
        type: "conversation.item.create",
        item: {
          type: "message",
          role: roleUser ? "user" : "assistant",
          content: [{ type: roleUser ? "input_text" : "text", text: content }],
        },
      });
    },
  };
})();
