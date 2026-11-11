# Laptop voice service: wire contract (protocol 1)

Status: **STABLE as of 2026-11-10 (protocol 1).** The phone client builds against this file. Any change is listed in the Changelog at the bottom with a date, and a breaking change bumps `protocol`.

The service does speech only: speech-to-text (STT) and text-to-speech (TTS). It never holds the Gemini key, never runs tools, and keeps nothing after a connection closes (no audio or text written to disk, no transcripts in logs).

## 1. Endpoints and auth

| What | On the laptop (dev) | From the phone (over Tailscale) |
|---|---|---|
| Liveness | `GET http://127.0.0.1:8765/livez` | `GET https://<laptop>.<tailnet>.ts.net/livez` |
| Health | `GET http://127.0.0.1:8765/health` | `GET https://<laptop>.<tailnet>.ts.net/health` |
| Conversation | `ws://127.0.0.1:8765/v1/conversation` | `wss://<laptop>.<tailnet>.ts.net/v1/conversation` |

- **Link: Tailscale** (Kevin, 2026-11-10). The phone always uses the laptop's **MagicDNS name** (`<laptop>.<tailnet>.ts.net`), never a raw `100.x` address (the phone app allows plain http only to `*.ts.net`, localhost and 127.0.0.1).
  - **Primary:** the service binds `127.0.0.1:8765`; `tailscale serve --bg --https=443 http://127.0.0.1:8765` publishes it to the tailnet only as **HTTPS on port 443** with a real `*.ts.net` certificate (Tailscale docs: Serve requires HTTPS certificates enabled in the tailnet). Base URL: `https://<laptop>.<tailnet>.ts.net` (no port); WebSocket `wss://<laptop>.<tailnet>.ts.net/v1/conversation`.
  - **Fallback** (if the HTTPS proxy does not carry the WebSocket upgrade; Tailscale's docs do not say either way): `tailscale serve --tls-terminated-tcp=443 tcp://127.0.0.1:8765`. Tailscale ends TLS and passes the raw TCP stream to the service, so the upgrade is the service's own. **Same base URL for the phone** (`https://<laptop>.<tailnet>.ts.net`, `wss://.../v1/conversation`); the service still binds 127.0.0.1 only. (Replaces the earlier fallback of binding the 100.x address with a plain-http `:8765` URL.)
- **Auth (second layer, on top of the tailnet):** every request except `/livez`, including `/health` and the WebSocket upgrade, carries `Authorization: Bearer <token>`.
  - The token is generated on the laptop on first start and lives in a file only Kevin's user can read (`%LOCALAPPDATA%\legion-voice\token`). Kevin copies it into the phone's setting once; it is never in the repo, never logged, never printed.
  - Missing or wrong token: **401** with an empty body (WebSocket: 401 before the upgrade). Each refusal is delayed 0.5 s; more than 20 refusals in 60 s makes every route except `/livez` answer **429** for 30 s.
  - `/livez`: no auth, `204`, no body. It says only that something answers.
- Up to **2** conversations at once on Whisper + Kokoro; **1 on Kyutai** (its models run at batch size 1). One over the limit gets close code `1013`. GPU work is serialised inside the service.
- Frame limit: 64 KB per WebSocket message.

## 2. `GET /health`

Response `200`, JSON:

```json
{
  "status": "ready",
  "protocol": 1,
  "version": "0.1.0",
  "stt": {"model": "whisper-large-v3-turbo", "languages": "multi", "sample_rate": 16000, "end_of_turn": false, "partials": false},
  "tts": {"model": "kokoro-v1.0", "voices": ["am_puck", "am_michael", "bm_george"], "default_voice": "am_puck", "sample_rate": 24000, "text_streaming": false},
  "capabilities": {"end_of_turn": false, "partials": false, "text_streaming": false},
  "ram_pct": 71.5,
  "gpu_free_mb": 5120,
  "queue": 0,
  "connections": 1,
  "uptime_s": 3600
}
```

- `status` is one of:
  - `"ready"`: use it.
  - `"busy"`: alive but do not start a new conversation here (host RAM at or above the configured threshold, default 98%; GPU queue ≥ 3; or 3 jobs in a row over budget). The phone picks its own speech path.
  - `"loading"`: models still loading. Treat as busy.
- The phone's probe timeout is 250 ms (phone-side rule). The service answers `/health` without touching the GPU.
- Fields may be added later; the client must ignore unknown fields.

## 3. Frames on the WebSocket

- **Text frames** carry JSON control messages, UTF-8, one object per frame, always with a `"type"`.
- **Binary frames** carry audio with an 8-byte header:

| Byte | Meaning |
|---|---|
| 0 | kind: `0x01` = mic audio, phone to laptop; `0x02` = TTS audio, laptop to phone |
| 1-3 | reserved, send `0` |
| 4-7 | `id`, uint32 little-endian (the STT utterance id or the TTS request id) |
| 8.. | PCM, signed 16-bit little-endian, mono |

- Audio formats (fixed in protocol 1):
  - Mic up: **16,000 Hz**, s16le mono. Any frame length; the phone sends its 100 ms chunks (3,200 bytes of PCM). Must be a whole number of samples (even byte count).
  - TTS down: **24,000 Hz**, s16le mono. Frames are at most 9,600 bytes of PCM (200 ms).
- **Ids** are uint32 chosen by the phone, unique within the connection, used in both JSON (`"id"`) and the binary header. STT ids and TTS ids are separate spaces (the frame kind tells them apart). Ids need not be sequential.

## 4. Messages, phone to laptop

| type | Fields | Meaning |
|---|---|---|
| `hello` | `protocol` (int, 1), `client` (string, e.g. `"legion-phone/263142242"`), optional `voice` (default: the backend's `default_voice`; a voice id must come from `ready.tts.voices`), optional `lang` (`"auto"` default, or an ISO 639-1 code like `"en"`) | First message. Nothing else is accepted before it. |
| `stt.start` | `id`, optional `lang` (overrides hello) | Start an utterance. Mic frames with this `id` follow. |
| `stt.end` | `id` | End of the utterance (the phone's end-of-turn decision). The laptop decodes and sends `stt.final`. |
| `stt.cancel` | `id` | Drop the utterance. The laptop sends `stt.final` with `"cancelled": true` and empty text, or nothing if it had not started. |
| `tts.speak` | `id`, `text` (1 to 1,000 chars; one sentence or clause is best), optional `voice`, optional `speed` (0.5 to 2.0, default 1.0) | Synthesize. Binary `0x02` frames with this `id` follow, then `tts.done`. |
| `tts.cancel` | `id` | Stop now (barge-in). Frames already sent may still arrive; then `tts.done` with `"cancelled": true`. Cancelling an unknown or finished id is ignored. |
| `ping` | optional `t` (any) | Keepalive; answered with `pong` echoing `t`. |
| `bye` | | The laptop closes with code 1000. |

### 4a. Streaming text into the voice (optional, added 2026-11-10, see Changelog)

Some voice models (Kyutai TTS) can start speaking while the text is still arriving. The laptop advertises this in `ready.tts.text_streaming` (and `/health` `tts.text_streaming`): `true` or `false`.

| type | Fields | Meaning |
|---|---|---|
| `tts.stream.start` | `id`, optional `voice`, optional `speed` | Open a streamed utterance. Binary `0x02` frames with this `id` follow as audio is ready. |
| `tts.stream.text` | `id`, `text` | Append text exactly as the model streamed it (any size: a word, a few words, a sentence). Include spaces as received. |
| `tts.stream.end` | `id` | No more text for this id. The laptop finishes speaking it, then sends `tts.done`. |

- Cancel with the same `tts.cancel {id}`.
- Only when `text_streaming` is `true`. When it is `false` (Kokoro, Whisper fallback), a `tts.stream.*` message gets `error` `bad_request`, and the phone uses `tts.speak` per sentence.
- One streamed id at a time per connection; `tts.speak` requests queue behind it (FIFO, as in section 4).
- At most 1,000 chars in total per streamed id; crossing it gets `bad_request` and closes the id (no `tts.done`). Continue a long reply on a new id.
- The phone still normalises numbers in what it sends; it may normalise per streamed chunk.

### 4b. End of turn decided by the laptop (optional, added 2026-11-10, see Changelog)

`capabilities.end_of_turn` in `ready` and `/health`, **and the same flag as `stt.end_of_turn`** (the phone agent reads that one), says whether this backend decides end of turn itself (Kyutai STT has a built-in pause predictor; the Whisper fallback does not).

- **`end_of_turn: true`:** the phone does **not** run its own end-of-turn (no Silero/Smart Turn decision, no `stt.end`). It sends `stt.start`, streams mic frames continuously, and waits. The laptop sends:
  - `stt.partial` (running transcript, replace not append) as words arrive;
  - `stt.pause` `{id, prob, silence_ms}`: the model's current probability that the speaker has finished, about every 80 to 200 ms while audio flows (optional to use; for UI or an early ack);
  - then **`stt.final` with `"reason": "end_of_turn"`**: the turn is over, the text is final, and this id is closed. Mic frames for that id after this get `bad_request`; start a new id for the next turn.
  - The phone may still send `stt.end` (tap, push-to-talk release, its own max-length cap) to force a final, which then carries `"reason": "client_end"`.
  - Barge-in: while Alfred speaks the phone may keep a new utterance id streaming; the laptop's `stt.partial` / `stt.pause` on it are the signal. The phone still decides whether to cut playback (its `BargeInGate` stays on the phone).
  - **One live utterance at a time** (the model is batch size 1): a `stt.start` while another id is still open gets `error` `bad_request` naming the open id. Start the next id after that id's `stt.final` (or after `stt.cancel`).
  - **30 s cap:** at 30 s of audio the laptop closes the utterance itself: `error` `too_long`, then `stt.final` (reason `client_end`) with the text so far.
  - `stt.final` on Kyutai: `lang` is the forced `lang` or `null` (Kyutai has no language detection; it hears English and French), `lang_prob` is `null`. With `reason: "end_of_turn"`, `decode_ms` is the time of the model step that ended the turn (it includes the flush of the last words).
- **`end_of_turn: false`:** exactly as section 4 so far. The phone runs its own end-of-turn and sends `stt.end`; every `stt.final` carries `"reason": "client_end"`.
- Fields added to `stt.final`: `"reason"`: `"client_end"` | `"end_of_turn"` | `"cancelled"`.

### 4c. Notes on STT

- Speculative decode: the phone may send `stt.end` early (at its ~300 ms silence point) and, if speech resumes, `stt.cancel` that id and start a new id with the whole turn's audio again. The laptop keeps no audio across ids.
- `tts.speak` requests on one connection are synthesized **in order of arrival** (FIFO). STT work runs independently of TTS.
- Text is spoken as given. The phone normalises numbers ("$1,284.17" to words) before sending, as it does for its own voice.

## 5. Messages, laptop to phone

| type | Fields | Meaning |
|---|---|---|
| `ready` | `protocol`, `session` (uuid string), `stt`, `tts` and `capabilities` objects as in `/health`, `limits` (`max_utterance_ms` 30000, `max_tts_chars` 1000, `idle_timeout_ms` 120000) | Reply to `hello` |
| `stt.partial` | `id`, `text` | Best effort, may never come. The phone must not depend on it. With a streaming STT model (Kyutai) it carries the running transcript of the utterance so far (replace, do not append). |
| `stt.pause` | `id`, `prob` (0 to 1), `silence_ms` (int or null) | Only when `capabilities.end_of_turn` is true: the model's current probability that the speaker has finished (section 4b). Replaces the earlier `stt.endpoint` name. |
| `stt.final` | `id`, `text`, `lang` (detected or forced, ISO 639-1), `lang_prob` (0 to 1, or null), `audio_ms`, `decode_ms`, `reason` (`client_end` / `end_of_turn` / `cancelled`), optional `cancelled` (true) | One per utterance that was started (unless cancelled before any audio). |
| `tts.done` | `id`, `audio_ms`, `first_audio_ms` (laptop-side ms from request receipt to first frame sent), optional `cancelled` (true) | Last message for that TTS id; all its audio frames come before it. |
| `pong` | `t` | Reply to `ping` |
| `error` | `id` (int or null), `code`, `message`, `retry` (bool) | See below. After an error that names an id, nothing more comes for that id. |

## 6. Errors, timeouts, busy

| `code` | When | What the phone does |
|---|---|---|
| `bad_request` | malformed JSON, unknown type, frame for an id never started, odd byte count, text empty or too long | fix; do not retry |
| `protocol` | `hello` missing or `protocol` unsupported | fall back to the phone path |
| `too_long` | utterance over 30 s of audio (the laptop decodes the first 30 s and sends `stt.final` too; on a streaming backend it also closes the utterance, section 4b) | none needed |
| `busy` | GPU queue full when a job is submitted | phone path for that stage |
| `timeout` | a job ran over its budget (STT 5 s, TTS first audio 3 s) | phone path for that stage |
| `internal` | anything else | phone path for that stage |

- Close codes: `1000` normal; `1001` idle (no frame for 120 s) or shutdown; `1013` busy at connect (sent right after upgrade when `/health` would say `busy` or `loading`); `4400` protocol violation; `4401` unauthorized (normally refused before the upgrade with HTTP 401).
- The laptop never waits on the phone: if the phone stops reading, the socket's send buffer fills and the laptop drops the connection.
- Phone-side deadlines (from the design, not enforced by the laptop): `stt.final` within 400 ms after the phone's own STT finishes, first TTS frame within 500 ms of `tts.speak`. On a miss the phone uses its own path for the rest of the conversation.
  - **Kyutai misses the 500 ms TTS rule on this laptop** (measured 2026-11-10: first audio ~0.7 s for `tts.speak`, ~1.0 s for streamed text). The phone needs a Kyutai-specific first-audio budget (about 1.2 s for `tts.speak`, 1.5 s for streamed text, from `stream.start`), or every conversation falls back to the phone voice. Switch on `capabilities.text_streaming` (true only on Kyutai), never on the model name; numbers in `laptop-voice-1110.md`.

## 7. Example (one turn)

```
phone  -> {"type":"hello","protocol":1,"client":"legion-phone/263142242","voice":"am_puck","lang":"auto"}
laptop -> {"type":"ready","protocol":1,"session":"…","stt":{…},"tts":{…},"limits":{…}}
phone  -> {"type":"stt.start","id":1}
phone  -> [0x01 0 0 0 | 01 00 00 00 | 3,200 bytes PCM]   (repeated every 100 ms)
phone  -> {"type":"stt.end","id":1}
laptop -> {"type":"stt.final","id":1,"text":"Set a timer for twelve minutes.","lang":"en","lang_prob":0.99,"audio_ms":2300,"decode_ms":140}
phone  -> {"type":"tts.speak","id":1,"text":"Twelve minutes, running.","voice":"am_puck"}
laptop -> [0x02 0 0 0 | 01 00 00 00 | PCM 24 kHz] …
laptop -> {"type":"tts.done","id":1,"audio_ms":1450,"first_audio_ms":120}
phone  -> {"type":"bye"}
```

## Changelog
- 2026-11-10: protocol 1, first version.
- **2026-11-10 (later), ADDITIVE, protocol stays 1 (Kevin chose Kyutai for the laptop's speech):**
  - NEW optional streamed text input for the voice: `tts.stream.start` / `tts.stream.text` / `tts.stream.end` (section 4a), advertised by `tts.text_streaming` in `ready` and `/health`. A client that ignores it keeps working with `tts.speak`.
  - NEW optional `stt.endpoint` event, and `stt.partial` now defined as the running transcript (replace, not append).
  - `stt.model` / `tts.model` names in `/health` will change when the models swap (e.g. `kyutai-stt-1b-en_fr`, `kyutai-tts-1.6b-en_fr`); the client must not branch on them, only on `languages` and `text_streaming`.
- **2026-11-10 (later still), ADDITIVE, protocol stays 1 (Kevin: "since kyutai has end of turn built in lets use that first and ditch smart turn"):**
  - NEW `capabilities` object in `ready` and `/health`: `end_of_turn`, `partials`, `text_streaming` (booleans). `tts.text_streaming` stays as an alias.
  - NEW laptop-decided end of turn (section 4b): with `end_of_turn: true` the phone streams continuously and waits for `stt.final` with `reason: "end_of_turn"`.
  - The flag is also sent as **`ready.stt.end_of_turn`** (and `ready.stt.partials`), same value as `capabilities.end_of_turn`; the phone reads either. `true` only for a backend that really ends turns (Kyutai STT with its pause head); the Whisper fallback sends `false`.
  - **No `stt.endpoint` event is ever sent** (that name was renamed before any server sent it): switch on the flag, not on the first event; the per-step hint is `stt.pause`.
  - **RENAMED** the optional hint `stt.endpoint` (added an hour earlier, never served) to **`stt.pause`** with `prob` and `silence_ms`.
  - NEW `reason` field on every `stt.final`.
  - Tonight's fallback backend (Whisper + Kokoro) advertises `end_of_turn: false`, `partials: false`, `text_streaming: false`: the phone uses its own detector with it.
- **2026-11-10 (night), CONNECTION CHANGE, protocol stays 1 (Kevin: Tailscale, not a Cloudflare Tunnel; Kevin: "run as my account. just secure it"):**
  - Section 1 rewritten: base URL `https://<laptop>.<tailnet>.ts.net` via `tailscale serve` (fallback `http://<laptop>.<tailnet>.ts.net:8765`, MagicDNS name, never a raw 100.x address). **Cloudflare Access headers are gone; send only `Authorization: Bearer`.**
  - NEW `GET /livez` (no auth, 204). Token now comes from a file on the laptop, not an env var (the phone side is unchanged: same header).
  - NEW limits: 2 conversations (third gets 1013), 64 KB frames, 429 lockout after repeated bad tokens.
  - `tts.voices` is now a fixed allowlist of male voices (Kokoro: am_puck, am_michael, am_onyx, am_fenrir, am_echo, am_eric, am_liam, am_adam, bm_george, bm_lewis, bm_daniel, bm_fable); a voice outside it gets `bad_request`.
  - `tts.voices` lists change with the model (Kyutai voice names are paths like `expresso/ex03-ex01_happy_001_channel1_334s.wav`); the client must treat voice ids as opaque strings and take `default_voice` when unsure.
- **2026-11-10 (late night, Kyutai live), NO WIRE CHANGE, protocol stays 1. Read this, phone agent:**
  - **Kyutai voice ids are short keys, not paths** (corrects the line above): `dev` (default), `vctk_p226`, `ex03_calm`, `ex01_calm`, `ex03_happy`, `ex03_sympathetic`, `ex01_happy`, `ex01_sympathetic`. Take them from `ready.tts.voices`.
  - **A Kokoro id (`am_puck`) sent to Kyutai gets `bad_request`**, in `hello.voice` silently ignored (default used), in `tts.speak` / `tts.stream.start` as an error. Omit `voice`, or send `ready.tts.default_voice`, unless the id came from `ready.tts.voices`.
  - **Kyutai allows 1 conversation**, not 2 (second gets `1013`).
  - **One live STT utterance at a time** on an end-of-turn backend; a second `stt.start` gets `bad_request` (section 4b).
  - **30 s cap on a streaming utterance** closes it from the laptop: `too_long`, then `stt.final` (section 4b).
  - **Kyutai `stt.final`**: `lang` null unless forced, `lang_prob` null; English and French only.
  - **Measured first audio on Kyutai is ~0.7 s (`tts.speak`) and ~1.0 s (streamed)**, over the phone's 500 ms rule (section 6). The phone needs a Kyutai budget or it will always fall back.
  - **Tailscale fallback changed** (section 1): `--tls-terminated-tcp=443` keeps the same `https://`/`wss://` URL; no `:8765`, no 100.x bind.
  - **Streamed text is capped at 1,000 chars per stream id** (sum of all `tts.stream.text` for that id). A longer reply: end the id and continue on a new one. A `tts.stream.text` that crosses the cap gets `bad_request` and **closes that id** (no `tts.done` follows); speech queued behind it carries on.
  - **Barge-in partials are late on Kyutai:** while the laptop is speaking, STT and TTS share the GPU, and a streaming utterance's `stt.partial` / `stt.final` ran **0.9 to 3.0 s** behind (measured). Section 4b's barge-in line holds, but do not treat laptop partials as a fast barge-in trigger; keep the phone's `BargeInGate` for that.
