---
map: web-assistant
ticket: "01"
kind: research
title: "Gemini Live from an iPhone PWA: ephemeral tokens, mic, playback, lock screen, cost"
researched: 2026-10-04
tags: [research]
---
# Gemini Live from an iPhone PWA: ephemeral tokens, mic, playback, lock screen, cost

Researched 2026-10-04 for ticket 01. Primary sources fetched that day: ai.google.dev docs pages
and API reference, `googleapis/js-genai` and `googleapis/python-genai` source on `main`, Google's own
`gemini-live-api-examples` repo, WebKit Bugzilla (XML export), WebKit source on `main`, webkit.org
blog release notes, MDN browser-compat-data. No real API key or token was used.

**Tags.** `sourced` = read in a primary source on the date given. `secondary` = a third party's
report (bug commenter, forum post, blog), not confirmed by the vendor. `reasoned` = inferred here.
`tested` = run from this machine on 2026-10-04.

## Short answer

- **The protocol side is solid.** Ephemeral tokens exist for exactly this (engine mints, browser
  connects straight to Google), `gemini-3.8-live` is named in Google's own token examples, and the
  browser speaks the same JSON the Android app already speaks.
- **The iPhone side is the risk.** Mic plus speaker playback in an iOS web app works in principle,
  but WebKit has open bugs on output quality with the mic open (311451, NEW) and a report filed
  today of crackling on iOS 27 with echo cancellation on (326286, NEW, unconfirmed). Screen lock or
  app switch must be treated as "the conversation ends". Mic permission likely re-prompts on every
  cold launch of the web app.
- **Cost is dominated by re-billing, not by minutes.** About $0.23 for a 5-minute chat, $3.50 to
  $7.50 per hour of continuous talk depending on the compression threshold. A typed turn is about
  half a cent fresh.
- **Verdict: viable with caveats.** Build it, but spike on Mia's actual iPhone before ticket 08
  commits, and keep a record-per-turn fallback designed.

---

## 1. Ephemeral tokens

| Question | Answer | Tag | Source |
|---|---|---|---|
| Status | **Preview.** Page banner: "Ephemeral tokens are in Preview". Both SDKs mark token creation `[Experimental]`. | sourced | `ai.google.dev/gemini-api/docs/live-api/ephemeral-tokens` (page fetched 2026-10-04; doc metadata "last updated 2026-09-15"); `python-genai/google/genai/tokens.py`, `js-genai/src/tokens.ts` on main |
| Works with | **Live API only.** "At this time, ephemeral tokens are only compatible with Live API." | sourced | same page |
| Mint endpoint (REST) | `POST https://generativelanguage.googleapis.com/v1beta/auth_tokens` with header `x-goog-api-key: <household key>`, JSON body `{uses, expireTime, newSessionExpireTime, liveConnectConstraints?}`. Response field `name` is the token (form `auth_tokens/...`). | sourced | same page, REST tab |
| Mint endpoint exists on which versions | `v1alpha/auth_tokens` and `v1beta/auth_tokens` both answer 403 "unregistered callers" without a key (route exists); `v1/auth_tokens` answers 404. | tested | curl from this machine, 2026-10-04, no key sent |
| SDK call | Python `client.auth_tokens.create(config={...})`; JS `client.authTokens.create({config: {...}})`. | sourced | doc page; `js-genai/src/client.ts` (`readonly authTokens: Tokens`) |
| Required API version | **Conflict.** Current doc and Google's own example app say **v1beta** for both minting and connecting. Both SDKs on `main` (js-genai 2.27.0, 2026-10-02) still log "ephemeral token support is in v1alpha only". The older doc path (`/docs/ephemeral-tokens`) still shows v1alpha. The engine does not use the SDK (`server/ingest/statements.py` calls REST via `urllib`), so this only matters if the browser uses `@google/genai`. | sourced (conflict) | doc; `js-genai/src/live.ts` lines 170-180; `gemini-live-ephemeral-tokens-websocket/server.py` (`http_options: {"api_version": "v1beta"}`) |
| Lifetime defaults | `expireTime` default **30 min**; `newSessionExpireTime` default **60 s**. Both "must be less than 20 hours in the future". After `expireTime` "messages in BidiGenerateContent sessions will be rejected (Gemini may preemptively close the session after this time)". | sourced | `ai.google.dev/api/live#ephemeral-auth-tokens` |
| Use limit | `uses` default **1**; 0 = unlimited. "Resuming a Live API session does not count as a use." Doc: reconnecting every ~10 min with `sessionResumption` "can be done with the same token even if `uses: 1`". | sourced | API reference `AuthToken.uses`; doc page |
| What can be locked | Anything in `BidiGenerateContentSetup`: model, `generationConfig` (response modalities, `speechConfig` voice), `systemInstruction`, `tools`, `realtimeInputConfig`, `sessionResumption`, `contextWindowCompression`, transcription configs. Sent as `liveConnectConstraints` (SDK) / `bidiGenerateContentSetup` + `fieldMask` (wire). | sourced | API reference `AuthToken`; doc page lock example |
| Lock semantics | `fieldMask` empty + setup present: "the effective setup is taken **entirely** from `bidiGenerateContentSetup` in this request. The setup message from the Live API connection is **ignored**." `fieldMask` non-empty: only those fields overwrite the client's setup. SDK `lockAdditionalFields: []` = lock only the fields you set; omitted = lock everything. | sourced | API reference `AuthToken.fieldMask`; `tokens.py` docstring cases 1-4 |
| Does a locked token stop the browser changing the prompt or tools? | **Yes, if those fields are locked.** A full lock means the browser's `setup` is ignored. Example from the SDK docstring: "changing `output_audio_transcription` in the Live API connection will be ignored by the API." | sourced | `tokens.py`, `tokens.ts` docstrings |
| Catch with a full lock | `sessionResumption.handle` lives inside `setup`. With a full lock the browser's handle would be ignored, so resumption could silently start a fresh conversation. **Lock with a field mask** (model, generationConfig, systemInstruction, tools, realtimeInputConfig, contextWindowCompression) and leave `sessionResumption` client-set. Not verified. | reasoned | from the semantics above; must be `tested` in ticket 07 |
| How the browser connects (raw) | `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained?access_token=<token>`, or `Authorization: Token <token>` (not settable from a browser `WebSocket`, so query param it is). First message is `setup`, same shape as Android's `buildSetupJson`. | sourced | `live-api/get-started-websocket` "Authentication with ephemeral tokens"; API reference |
| Constrained endpoint exists | `BidiGenerateContentConstrained` on v1beta and v1alpha both close with **1007** "Missing or malformed auth token ... pass it in an `access_token` query parameter" for a bogus token; v1 fails at the handshake (1006). | tested | Node 24 `WebSocket` probe from this machine, 2026-10-04, bogus token only |
| How the browser connects (SDK) | `new GoogleGenAI({apiKey: token.name})` then `ai.live.connect(...)`. SDK detects the `auth_tokens/` prefix and switches to `BidiGenerateContentConstrained?access_token=`. | sourced | `js-genai/src/live.ts` lines 166-185 |
| What the token buys a thief | Until `expireTime`: Live sessions only, and only on the locked model and config if locked. Unlocked, it is a 30-minute Live key for anything. | reasoned | from the above |
| Token never carries the real key | Correct: the key goes engine to Google only. ADR 0056 holds. | sourced | doc "How ephemeral tokens work" |

**Mint recipe for ticket 07** (reasoned from the sourced fields above, not run):

| Field | Value | Why |
|---|---|---|
| `uses` | 1 | One conversation per mint; resumption is free |
| `newSessionExpireTime` | now + 60 s | Browser connects immediately after the fetch |
| `expireTime` | now + 31 min | Mirrors Android's 30-minute `CONVERSATION_BACKSTOP_MS`; a longer chat mints again |
| `bidiGenerateContentSetup` | model, generationConfig (AUDIO, voice), systemInstruction, tools (each `behavior: BLOCKING`), realtimeInputConfig, contextWindowCompression, input/output transcription | Prompt, honesty clause and tool list stay server-side (ticket 03) |
| `fieldMask` | the paths above, not `sessionResumption` | Keeps resumption working |

---

## 2. The model

| Question | Answer | Tag | Source |
|---|---|---|---|
| Model the Android app uses | `models/gemini-3.8-live`, endpoint `v1beta ... BidiGenerateContent?key=`, voice `Sulafat`, input `audio/pcm;rate=16000`, output 24 kHz, `behavior: BLOCKING` stamped on every declaration, `sessionResumption`, `contextWindowCompression` trigger 32,000 / target 16,000, `inputAudioTranscription` always, `outputAudioTranscription` only with subtitles on. | sourced | `app/src/main/java/com/kevin/legion/service/GeminiLiveSession.kt` lines 946-1050, 2840-2909 |
| Status | **Stable** (GA), "Latest update September 2026". Default Live model. | sourced | `docs/models/gemini-3.8-live`; models page "last updated 2026-10-01" |
| Accepts ephemeral-token connections | Google's own token examples lock to `gemini-3.8-live`, and its client-to-server tutorial uses it. Not run here with a real token. | sourced (not tested) | ephemeral-tokens doc; get-started-websocket |
| Native audio or half-cascade | **Native audio, audio-to-audio.** No half-cascade Live model is listed any more; every Live model on the models page is audio-to-audio. | sourced | models page; model card page |
| Input audio | Raw 16-bit PCM, little-endian, mono. "Natively 16kHz, but the Live API will resample if needed so any sample rate can be sent", declared in the MIME type (`audio/pcm;rate=48000` is legal). Best practice still says resample to 16 kHz client-side and send 20-40 ms chunks. | sourced | `live-api/capabilities` "Audio formats"; `live-api/best-practices` "Streaming", "Resampling" |
| Output audio | Raw 16-bit PCM, **24 kHz**, little-endian, base64 in `serverContent.modelTurn.parts[].inlineData.data`. | sourced | live-api overview "Technical specifications"; capabilities |
| Response modality | Migration note: "Audio is the supported response modality. Enable output audio transcription if your application requires a text transcript." (Model page lists output "Text and audio", which reads as the transcript.) **A typed turn gets a spoken answer plus its transcript, billed as audio out.** | sourced (slight conflict) | `docs/models/gemini-3.8-live` migration section |
| Showing text | `outputAudioTranscription: {}` in setup gives `serverContent.outputTranscription.text`; `inputAudioTranscription` gives the user's words. Surcharge at the text-output rate. | sourced | capabilities "Audio transcriptions"; best-practices "Transcription surcharge" |
| Typed input in a live session | Two ways. `realtimeInput.text` (streams alongside audio, turn end inferred, "ordering across these streams is not guaranteed"). `clientContent {turns, turnComplete: true}` (appended to history, "unconditionally interrupts active model generation" on 3.8). Android's `sendText()` already uses `clientContent`. | sourced | API reference `BidiGenerateContentRealtimeInput`, `BidiGenerateContentClientContent`; 3.8 migration note |
| Typed and spoken turns in one session | **Yes.** `clientContent` is "supported throughout the entire session lifecycle" on 3.8, and the mic can keep streaming `realtimeInput.audio` around it. | sourced | capabilities model-comparison table |
| Proactive audio | **Permanently on** in 3.8; `proactive_audio: false` returns an error. Billing consequence: input is charged "the entire time the Live API is listening". This also explains why Android's `proactivity` field was rejected on 2026-10-02. | sourced | 3.8 migration note; best-practices "Proactive audio" |
| Affective dialog | Removed from the API on 3.8 (matches the A25 failure noted in `GeminiLiveSession.kt`). | sourced | 3.8 migration note |

---

## 3. Tool calling from the browser

| Question | Answer | Tag | Source |
|---|---|---|---|
| How a call arrives | Server message `{"toolCall": {"functionCalls": [{id, name, args}]}}`. Several calls can arrive in one message. | sourced | API reference `BidiGenerateContentToolCall`; get-started-websocket |
| How the browser answers | `{"toolResponse": {"functionResponses": [{id, name, response: {...}}]}}`, matching ids. Same as Android `sendToolResponse`. "The Live API doesn't support automatic tool response handling." | sourced | live-api/tools; API reference |
| Cancellation | `toolCallCancellation {ids}` after a barge-in: "If there were side-effects ... clients may attempt to undo." The browser must forward this to the engine or ignore it knowingly. | sourced | API reference `BidiGenerateContentToolCallCancellation` |
| Blocking vs non-blocking on 3.8 | Default is **NON_BLOCKING** (model keeps talking; result lands later with `scheduling` INTERRUPT / WHEN_IDLE / SILENT). `behavior: BLOCKING` restores sequential: "The model will not start responding until you've sent the tool response." | sourced | capabilities model comparison; live-api/tools |
| Which to use | **BLOCKING, stamped server-side in the locked token.** Same reason as Android's `withBlockingBehavior`: CLAUDE.md §7's outcome-verb rule needs the result before the model speaks. Locking `tools` in the token means a browser cannot unstamp it. | reasoned | `GeminiLiveSession.kt` 2849-2872; §7 |
| Latency per call | Path: Google to iPhone (WebSocket) -> iPhone to engine (HTTPS) -> engine executes -> back to iPhone -> back to Google. Adds one phone-to-engine HTTPS round trip per call on top of Android's local execution. Cellular RTT plus TLS reuse: roughly 100-400 ms warm. A Cloud Run cold start (if min instances is 0) adds seconds, but the token mint just before the conversation usually warms the instance. With BLOCKING the user hears silence for that time. | reasoned | no measurement; engine is on Cloud Run per `memory/MEMORY.md` |
| Trust | The browser can fabricate a `toolResponse`. Harmless to others only if every tool executes on the engine under the member's own session (ADR 0052 privacy), so a forged answer can only mislead its own sender. Ticket 02's question. | reasoned | - |

---

## 4. iOS Safari, Home Screen web app (standalone)

Version context: iOS 26 shipped Sept 2025 (Safari 26.0 notes dated 2025-09-15). Safari 27.0 notes are
dated **2026-09-17**, so iOS 27 is current as of today. `sourced` (webkit.org blog dates).

| Topic | Behaviour | Tag | Source |
|---|---|---|---|
| Standalone at all | iOS 26: every site added to Home Screen opens as a web app by default; no manifest needed. | sourced | WebKit "Features in Safari 26.0" |
| `getUserMedia` in standalone | Works since iOS 13.4 (bug 185448 RESOLVED FIXED, 2020). | sourced | bugs.webkit.org/185448 |
| Permission prompt every launch? | **Likely yes, on every cold launch.** Reports on iOS 18.5 (2025-06-25): permission set to Allow in Safari, the installed web app asks again; killing and cold-starting the web app asks again. A WebKit engineer (youennf, 2026-02-03) restated it as "having persistent permission in PWA" and asked for a new bug, i.e. not a fixed behaviour. While the app stays alive it does not re-ask. | secondary + sourced (engineer comment) | bugs.webkit.org/215884 comments 2025-01-20, 2025-06-25, 2026-02-03 |
| Re-prompt on navigation | Hash-only navigation in standalone reset capture permission (bug 215884, the original). LEGION's router is TanStack with browser history (`server/frontend/src/main.tsx`), so not hash-based. | sourced + traced | 215884; `main.tsx` |
| One `getUserMedia` at a time | A second `getUserMedia` call can mute the first's tracks on iOS; clone the track instead. | sourced (WebKit engineer comment) | bugs.webkit.org/179363 (youennf, 2022-03-28) |
| AudioWorklet | Supported since Safari 14.1 (iOS mirrors desktop). | sourced | MDN BCD `api/AudioWorklet.json` |
| `AudioContext({sampleRate})` | Supported since Safari 14.1. New contexts start suspended until `resume()` in a user action. | sourced | MDN BCD `api/AudioContext.json` |
| Capturing 16 kHz PCM | iPhone mic runs at 48 kHz (bug 326286 reports context and mic at 48000). WebKit's `MediaStreamAudioSourceNode` inserts a `MultiChannelResampler` when the stream rate differs from the context rate, so a 16 kHz `AudioContext` + `createMediaStreamSource` + AudioWorklet (Google's own example design) is valid. | sourced (WebKit source) | `WebKit/Source/WebCore/Modules/webaudio/MediaStreamAudioSourceNode.cpp` lines 94-164 (main, last changed 2026-08-12); Google example `mediaUtils.js` |
| Capture design | Prefer **two contexts**: a 16 kHz capture context and a separate 48 kHz playback context, or one 48 kHz context with a worklet that downsamples (48 to 16 is an exact 3:1). Playing 24 kHz audio through a 16 kHz context would throw away the top of the voice. | reasoned | - |
| Playback of 24 kHz PCM | `AudioBuffer` at 24000 Hz scheduled back-to-back, or a playback worklet with a ring buffer (Google's example has `playback.worklet.js`). On `serverContent.interrupted` flush the queue. | sourced (pattern) | Google example repo; best-practices "Interruption Handling" |
| Echo cancellation | `echoCancellation` constraint works on iOS (bug 179411 FIXED 2019). WebKit uses the voice-processing audio unit; Safari 26.0 added a `configurationchange` event when that unit's EC mode changes. | sourced | bugs.webkit.org/179411; Safari 26.0 notes |
| Output quality with mic open | **Degraded: lower sample rate, stereo to mono**, with or without EC. Bug **311451, NEW**, filed 2026-04-03 (iOS 26.4), confirmed still present on iOS/macOS 27 public beta (2026-07-15). Apple-tracked (rdar 174529097). For speech this is mostly tolerable; it is not a blocker on its own. | secondary (reporter) + sourced (open, radar'd) | bugs.webkit.org/311451 |
| Crackle on iOS 27 | **Bug 326286, NEW, filed today 2026-10-04**: on iOS 27 (27.0.1) any playback while an EC-on mic is open crackles; clean on iOS 18.7. EC off is clean but output **moves to the earpiece at low volume**. Not yet triaged by Apple. If it holds, live voice on iOS 27 is degraded in exactly LEGION's configuration. | secondary (single reporter, unconfirmed) | bugs.webkit.org/326286 |
| Volume drop / earpiece routing | Long-running: output volume drops and routing flips between receiver and speaker when capture starts. Bug 218012 is closed "configuration changed" but comments report it on iOS 18.6 (2026-03-16). | secondary | bugs.webkit.org/218012 |
| `navigator.audioSession` | `type` supported since Safari 16.4 (`"play-and-record"`, `"playback"`, etc.); `state` not supported. Setting `"play-and-record"` before capture is the documented lever; there is no web equivalent of native `.defaultToSpeaker`. | sourced (BCD) + secondary (routing detail) | MDN BCD `api/AudioSession.json`; W3C audio-session explainer |
| Autoplay / user gesture | Start the conversation from a tap: create/resume the `AudioContext` and call `getUserMedia` in that handler. While the page is capturing, WebKit lets it keep playing audio without new gestures. | sourced (BCD note) + sourced (WebKit engineer comment) | BCD `AudioContext` note; bugs.webkit.org/237878 (youennf: "page is continuing to capture audio, which allows to continue playing audio") |
| Screen lock / app switch | **Treat as end of conversation.** Reports for Home Screen web apps: mic stops when backgrounded (2024-10-19: "Safari: Works. Add to Home Screen: Microphone stopped working."). Safari tabs may keep capture with the status-bar indicator (2022 forum). iOS suspends a backgrounded web app's process; the WebSocket dies with it. Safari 27.0 fixed "the WebProcess AudioSession to remain active while microphone capture is live" (180505014), which may improve this; untested. | secondary + sourced (27.0 note) | bugs.webkit.org/226620 last comment; developer.apple.com/forums/thread/689182; Safari 27.0 notes |
| Incoming phone call | iOS interrupts the audio session: the `AudioContext` goes to `"interrupted"` (supported in Safari per BCD) and the mic track mutes. The WebSocket survives only if the app stays foreground. Resume needs a user tap. | sourced (state exists) + reasoned (sequence) | MDN BCD `BaseAudioContext.state.interrupted`; WebAudio issue 2585 |
| Bluetooth (AirPods) | With the mic open iOS uses the call route: Bluetooth HFP, playout limited to 16 kHz mono; reports of output jumping to the speaker when capture starts. iOS 26's `bluetoothHighQualityRecording` is a native `AVAudioSession` option; no evidence WebKit uses it. | secondary | livekit client-sdk-react-native issue 467 (iOS 26); Medium write-up |
| CarPlay | A web app is not a CarPlay app. With the phone's mic open the car would get the HFP call route at best. Out of scope; Mia's use is not a car use. | reasoned | - |
| USB/external mics | Bug 211192 "USB microphone not recognized iOS Safari" REOPENED (2026-04-07). Irrelevant for a phone. | sourced | bugs.webkit.org/211192 |
| iOS 26.1 beta mic break | `getUserMedia` failed with "No AVAudioSessionCaptureDevice device" in 26.1 beta 1, fixed in beta 2 (Oct 2025). Shows how fragile this path is per release. | secondary | developer.apple.com/forums/thread/802555 |

---

## 5. Cost (paid tier, `gemini-3.8-live`, read 2026-10-04)

**Prices** (`sourced`, ai.google.dev/gemini-api/docs/pricing):

| | Per 1M tokens | Per minute (Google's figure) |
|---|---|---|
| Text in | $0.75 | - |
| Audio in | $3.00 | $0.005/min |
| Image/video in | $1.00 | $0.002/min |
| Text out (incl. transcription) | $4.50 | - |
| Audio out | $12.00 | $0.018/min |
| Google Search grounding | 5,000 free/month across Gemini 3.x, then $14 per 1,000 | - |

Free tier is free but "Used to improve our products: Yes"; paid is "No". **The household key must be
on a billed project** (Mia's conversations otherwise feed Google training). `sourced`.

**Billing mechanics** (`sourced`, live-api/best-practices "Pricing and billing"): ~25 audio tokens
per second; "The API charges you per turn for all tokens present in the session context window ...
Past tokens are re-processed and accounted for in each new turn"; audio history is kept as audio
tokens and re-billed at the audio-in rate; transcription is a surcharge at the text-out rate;
with proactive audio (always on in 3.8) input is billed "the entire time the Live API is listening".
No context caching for Live models.

**Model** (`reasoned`; script `live_cost.py` in the session scratchpad, assumptions: mic streams
the whole wall-clock time, 10 s user speech and 12 s model speech per turn, model audio retained in
history, setup = system prompt + tool declarations as text, Android's 32k/16k compression):

| Scenario | Estimate |
|---|---|
| 5-minute conversation, 10 turns, 4k-token setup | **~$0.23** |
| Same, 14k-token setup (Android-sized tool block) | ~$0.31 |
| Floor if Google did not re-bill history | ~$0.07 |
| 60 minutes continuous, 120 turns, compression 32k/16k | ~$7.50 |
| 60 minutes, compression 16k/8k | ~$3.50 |
| One typed turn, fresh session, 8 s spoken reply | **~$0.006** |
| One typed turn 5 minutes into a voice session | ~$0.035 |
| One typed turn on `gemini-3.8-flash` text model (non-live), 4k setup | ~$0.004 |

Tier 1 spend limit is $10 per rolling 10 minutes per project (`sourced`, rate-limits page), well
above one household. **Check the model against the Android app's own `GeminiUsageMeter` numbers**
before trusting the hour figure; it records `usageMetadata` per Live turn.

---

## 6. Session limits

| Limit | Value | Tag | Source |
|---|---|---|---|
| Session without compression | 15 min audio-only, 2 min audio+video | sourced | live-api/session-management |
| Session with `contextWindowCompression` | Unlimited | sourced | same |
| One WebSocket connection | ~10 min, preceded by `goAway {timeLeft}` | sourced | same |
| Resumption | `sessionResumption` in setup; server sends `sessionResumptionUpdate {newHandle, resumable}`; handle valid **2 h** after the last session terminates. Same token can resume (not a new "use"). Config except model may change on resume. | sourced | same; API reference; ephemeral-tokens doc |
| Context window | 131,072 input tokens, 65,536 output | sourced | `docs/models/gemini-3.8-live` |
| Compression | `slidingWindow.targetTokens` default trigger/2; trigger default ~80% of window (per Android's 2026-10-02 note) | sourced | API reference `ContextWindowCompressionConfig`; `GeminiLiveSession.kt` 1014-1018 |
| Token ceiling | One conversation cannot outlive the token's `expireTime` (max < 20 h) | sourced | API reference `AuthToken.expireTime` |
| Concurrent sessions per key | **Not published any more.** Limits are "per project, not per API key" and shown only in AI Studio. Two adults plus one phone is far from any plausible cap. | sourced (absence) | rate-limits page |
| Mid-session config change | Not possible; tools and prompt are fixed for the connection. Only a new connection (or resume) changes them. | sourced | API reference "Session configuration" |

---

## 7. Fallback if live voice on iOS is unreliable

| Piece | Answer | Tag | Source |
|---|---|---|---|
| Record a turn | `MediaRecorder` on iOS since 14.3: MP4 container, **AAC** audio (`audio/mp4`). Safari 26.0 added **ALAC and PCM** in MediaRecorder. Safari 26.2 fixed spurious error events when stopped right after track changes. | sourced | WebKit blog "MediaRecorder API" (11353); Safari 26.0 and 26.2 notes |
| Gemini accepts it | Yes: `audio/m4a`, `audio/aac`, `audio/wav`, `audio/webm`, `audio/opus`, ... Inline up to 20 MB per request; 32 tokens per second. | sourced | `gemini-api/docs/audio` "Supported audio formats" |
| Understanding + reply | Engine sends the clip to a non-live model with the same system prompt and tools, runs tools **server-side**, returns text. 10 s clip on `gemini-3.5-flash-lite` ($0.30/M in, $2.50/M out) with a 4k setup: **~$0.002 per turn**. | sourced (prices) + reasoned (design, cost) | pricing page |
| Spoken reply option A | `speechSynthesis` on iOS (Safari 7+). `speak()` outside a user gesture is silently dropped on iOS, so "unlock" it with an utterance in the tap that sent the turn. Safari 27.0 fixed `cancel()` removing later-queued utterances. Voice quality is the system voice, not the Live voice. | sourced (support, 27.0 fix) + secondary (gesture rule) | MDN BCD `SpeechSynthesis`; Safari 27.0 notes; secondary write-ups |
| Spoken reply option B | Engine calls `gemini-3.8-flash-lite-tts` ($0.0015 per 10 s of audio through 2026-12-31, doubles 2027-01-01), browser plays it with `<audio>` after a gesture unlock. Keeps a Gemini voice. | sourced (price) + reasoned | pricing page |
| What the fallback loses | Barge-in, sub-second turn-taking, open-mic conversation. Gains: no browser tool loop, no token minting, no WebKit EC/routing problems (record then play, never both at once), every tool runs on the engine. | reasoned | - |
| Typed chat | Independent of voice: either `clientContent` into a Live session (spoken reply + transcript) or a plain text model on the engine. Ticket 06 decides this for Android; the same fork applies to the web. | reasoned | - |

---

## Verdict

**Viable with caveats.**

| Caveat | Consequence for the build |
|---|---|
| Ephemeral tokens are Preview; SDKs disagree with the docs on v1alpha vs v1beta | Use raw REST from the engine and a raw WebSocket in the browser (both v1beta, as Google's own example does). Re-check before ticket 07 lands. |
| Full config lock may break resumption | Lock with a field mask; prove resume-with-same-token in ticket 07's tests. |
| iOS output quality with mic open (311451) and a fresh iOS 27 crackle report (326286) | Spike on Mia's real iPhone and iOS version before ticket 08 commits. Watch 326286. |
| Mic permission re-prompts on each cold launch of the web app | Say so in the UI; never ask on load, only on the talk button. |
| Lock screen / app switch kills the conversation | End the conversation on `visibilitychange` hidden, say so in words, offer resume (handle valid 2 h). |
| Each tool call is a phone-to-engine round trip, and BLOCKING means silence meanwhile | Keep engine tool handlers fast; a spoken "one moment" is the model's job, prompt-side. |
| Cost re-bills history every turn; 3.8 bills listening time | Lower the compression trigger for the web (16k/8k roughly halves an hour), cap conversations at 30 min, per-member mint throttle (ADR 0056). |
| Typed turns on Live always pay for spoken output | Consider a text model for the chatbox (ticket 06 fork). |

---

## Forks for Kevin

1. **Which iPhone and iOS does Mia run?** If iOS 27, bug 326286 decides whether live voice is
   pleasant at all today. A 20-minute spike on her phone answers it.
2. **Spike first, or build live voice and fallback together?** Recommended: a throwaway page on the
   engine that mints a token and holds a 2-minute conversation, run on Mia's phone, before ticket 08.
3. **Typed chatbox engine:** typed turns into the Live session (one conversation, spoken answers,
   ~$0.006 per fresh turn and rising with history) or a separate text model on the engine (~$0.004,
   text answers, no WebSocket needed to type). Same question as ticket 06.
4. **Web compression threshold:** keep Android's 32k/16k, or go lower (16k/8k) for the web to halve
   the hourly cost at the price of the model forgetting older turns sooner.
5. **Who runs the tools** (ticket 02): browser relays `toolCall` to the engine (live mode), or the
   fallback design where the engine runs everything server-side and the browser only plays audio.
6. **Lock screen behaviour:** end the conversation and say so (recommended), or try to keep the mic
   alive in the background (unsupported for Home Screen web apps per reports).

---

## Open items that only a device can settle

| Item | How |
|---|---|
| Real token mint + v1beta Constrained connect with `gemini-3.8-live` | Ticket 07 test with the household key |
| Field-masked lock keeps `sessionResumption.handle` usable | Ticket 07 test: drop socket, resume with the same token |
| Mic permission prompt frequency on Mia's iPhone | Spike: cold launch twice, background and return |
| EC on: speaker or earpiece, clean or crackling, on her iOS version | Spike |
| What happens on screen lock and on an incoming call | Spike |
| Barge-in works on the loudspeaker without the model hearing itself | Spike |
| Measured cost per conversation | Compare the spike's `usageMetadata` with section 5 |

---

## Assumptions ledger

| Claim | Tag |
|---|---|
| Ephemeral tokens are Preview, Live-only, default 30 min / 60 s / 1 use, max < 20 h | sourced (ai.google.dev, 2026-10-04) |
| `auth_tokens` route exists on v1alpha and v1beta, not v1 | tested (unauthenticated probe) |
| `BidiGenerateContentConstrained` exists on v1alpha and v1beta and wants `access_token` | tested (bogus-token probe) |
| A real token connects on v1beta with `gemini-3.8-live` | sourced (Google docs and example), not tested |
| Locked fields override the browser's setup | sourced (API reference, SDK docstrings), not tested |
| Full lock breaks resumption; field mask avoids it | reasoned |
| Android uses `models/gemini-3.8-live`, BLOCKING tools, 16 kHz in / 24 kHz out | sourced (repo code read 2026-10-04) |
| 3.8 Live: audio-only response modality, proactive audio always on, NON_BLOCKING default | sourced |
| Typed and spoken turns can share one session | sourced |
| Tool round-trip adds ~100-400 ms warm | reasoned (no measurement) |
| WebKit resamples a 48 kHz mic into a 16 kHz `AudioContext` | sourced (WebKit source) |
| AudioWorklet, `AudioContext` sampleRate, `audioSession.type` supported on iOS | sourced (MDN BCD) |
| Mic permission re-prompts on each cold launch of a Home Screen web app | secondary (bug reporters) + one WebKit engineer comment |
| Output degrades with mic open (311451) | secondary, open and radar'd |
| iOS 27 crackle with EC on (326286) | secondary, single reporter, filed today, untriaged |
| Mic stops when a Home Screen web app is backgrounded | secondary |
| Safari 27.0 AudioSession fix improves background capture | reasoned (note exists; effect untested) |
| HFP 16 kHz mono on AirPods with mic open | secondary |
| `speechSynthesis.speak()` needs a gesture on iOS | secondary |
| MediaRecorder AAC on iOS; ALAC/PCM since 26.0 | sourced (WebKit blog) |
| Cost figures in section 5 | reasoned from sourced prices and billing rules; not measured |
| Household key must be on a paid project for Google not to train on Mia's audio | sourced (pricing table row) |

## Sources (all read 2026-10-04)

- ai.google.dev/gemini-api/docs/live-api/ephemeral-tokens (and the older /docs/ephemeral-tokens)
- ai.google.dev/api/live (WebSockets API reference: setup, ClientContent, RealtimeInput, ToolCall, ToolCallCancellation, AuthToken)
- ai.google.dev/gemini-api/docs/live-api, /live-api/capabilities, /live-api/tools, /live-api/session-management, /live-api/best-practices, /live-api/get-started-websocket
- ai.google.dev/gemini-api/docs/models, /models/gemini-3.8-live, /pricing, /rate-limits, /audio
- github.com/googleapis/js-genai `src/live.ts`, `src/tokens.ts`, `src/client.ts` (main; release v2.27.0, 2026-10-02)
- github.com/googleapis/python-genai `google/genai/tokens.py` (main; release v2.28.0, 2026-10-02)
- github.com/google-gemini/gemini-live-api-examples `gemini-live-ephemeral-tokens-websocket/` (server.py, frontend/mediaUtils.js, geminilive.js, audio-processors/capture.worklet.js)
- bugs.webkit.org: 185448, 215884, 179363, 179411, 218012, 226620, 231105, 237878, 198277/232909, 180522, 211192, 241480, 311451, 326286
- github.com/WebKit/WebKit `Source/WebCore/Modules/webaudio/MediaStreamAudioSourceNode.cpp` (main)
- webkit.org/blog: Safari 26.0 (17333), 26.1 (17541), 26.2 (17640), 26.4 (17862), 27.0 (18325, dated 2026-09-17), MediaRecorder API (11353)
- MDN browser-compat-data: `api/AudioWorklet`, `AudioContext`, `AudioSession`, `MediaRecorder`, `SpeechSynthesis`, `BaseAudioContext`
- developer.apple.com/forums threads 689182, 802555 (secondary)
- github.com/livekit/client-sdk-react-native issue 467 (secondary)
- In repo: `app/src/main/java/com/kevin/legion/service/GeminiLiveSession.kt`, `docs/adr/0056-the-household-key-serves-the-web-assistant.md`, `.scratch/proactive-mode/research/live-tool-block-cost.md`, `memory/library/decisions.md` 2026-10-02
