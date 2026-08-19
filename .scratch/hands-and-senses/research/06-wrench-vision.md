# Wrench-mode vision: Live API video vs Flash one-shots

Research for ticket `issues/06-wrench-vision-research.md`. Date 2026-08-16. Sources: ai.google.dev
only (docs fetched today). Every claim cited to the owning URL. Claims are `docs` (read from
Google's pages) unless marked `code` (read from this repo). Nothing here is `on-device`; the spike
list at the bottom is what L10 still demands.

## 1. Video on the Live API

- Video input IS first-class on the Live API: supported inputs are audio (raw 16-bit PCM, 16 kHz,
  little-endian), images (JPEG), and text, over a stateful WebSocket (WSS).
  https://ai.google.dev/gemini-api/docs/live
- "Video" is not a stream codec. Frames are sent as individual images (JPEG or PNG) via
  `send_realtime_input` / `realtimeInput` with a `Blob { data, mime_type: "image/jpeg" }`, at
  **max 1 frame per second**. They interleave with audio chunks on the same socket as separate
  realtime-input messages. https://ai.google.dev/gemini-api/docs/live-guide,
  https://ai.google.dev/gemini-api/docs/live-api/capabilities
- Auth: plain API key works; ephemeral tokens are only "recommended" for production client-side to
  mitigate key exposure, not required. No doc gates video behind any app-only entitlement.
  https://ai.google.dev/gemini-api/docs/live
- LEGION already connects with the plain key for audio (`service/GeminiLiveSession.kt`, model
  pinned at line 1524: `models/gemini-3.1-flash-live-preview`) (`code`). Adding frames is the same
  socket, new message type. That it actually works on Kevin's key is a spike, not a doc fact.

## 2. Session limits

All from https://ai.google.dev/gemini-api/docs/live-session (Session management):

| Limit | Value |
|---|---|
| Audio-only session, no compression | 15 min, then session terminates |
| Audio + video session, no compression | **2 min**, then session terminates |
| WebSocket connection lifetime | ~10 min, regardless of session config |
| Context window (native-audio models) | 128k tokens (3.1 flash live model card: 131,072 in / 65,536 out) |
| Context window (other Live models) | 32k tokens |
| Resumption handle validity | 2 h after last session termination |

- `contextWindowCompression` (sliding-window, configurable token trigger) lifts the duration caps
  entirely: "enables sessions of unlimited duration".
- `sessionResumption` issues handles via periodic `SessionResumptionUpdate` messages; reconnect
  with the handle to continue the same session across the ~10-min connection resets.
- Server sends `GoAway` with `timeLeft` before killing a connection.
- Net for a garage session (tens of minutes with video): **compression is mandatory, resumption is
  mandatory**. The raw A/V cap is 2 minutes. GeminiLiveSession must handle GoAway + resume.
- Context arithmetic makes the same point: 1 fps at 70 tokens/frame (see §4) is 4,200 tok/min of
  video alone; 30 min = 126k tokens, which by itself fills the 131k window. Compression is not
  optional even ignoring the duration cap.

## 3. Cost

Pricing page, paid tier, fetched 2026-08-16: https://ai.google.dev/gemini-api/docs/pricing
(verbatim table rows confirmed against /gemini-api/docs/pricing.md.txt).

Rates:

| Model | In (text) | In (audio) | In (image/video) | Out (text) | Out (audio) |
|---|---|---|---|---|---|
| gemini-3.1-flash-live-preview | $0.75/1M | $3.00/1M or $0.005/min | $1.00/1M or $0.002/min | $4.50/1M | $12.00/1M or $0.018/min |
| gemini-2.5-flash-native-audio-preview-12-2025 | $0.50/1M | $3.00/1M | $3.00/1M (audio/video row) | $2.00/1M | $12.00/1M |
| gemini-3.5-flash-lite (SubAgent default) | $0.30/1M (text/image/video/audio) | - | - | $2.50/1M | - |

Footnote: Live audio = 25 tokens/sec (checks out: 25 x 60 x $3/1M = $0.0045/min ~ $0.005/min).

**30-minute Live wrench session** (gemini-3.1-flash-live-preview, mic open throughout, 1 fps
camera, assistant speaks ~8 min total, ~30k text tokens of system prompt + tool traffic):

| Component | Arithmetic | Cost |
|---|---|---|
| Audio in, 30 min | 30 x $0.005/min (= 45,000 tok x $3/1M = $0.135) | $0.14 |
| Video in, 1 fps, 30 min | published: 30 x $0.002/min = $0.06; token-derived: 1,800 frames x 70 tok = 126,000 tok x $1/1M = $0.126 | $0.06-0.13 |
| Audio out, ~8 min | 8 x $0.018/min | $0.14 |
| Text in, ~30k tok | 30,000 x $0.75/1M | $0.02 |
| **Total** | | **~$0.36-0.44** |

Note the video discrepancy: $0.002/min implies ~2,000 tok/min (~33 tok/frame at 1 fps) but the
media-resolution doc says 70 tok/frame default. Factor-of-2 ambiguity, bounded above at $0.13 for
the whole session. Real number comes from `usageMetadata` on device (spike 2).

**20 Flash one-shots** (gemini-3.5-flash-lite via `ai/SubAgent.kt`, per shot: camera photo ~1,500
tok, ~800 tok prompt with car context, ~300 tok answer):

| Component | Arithmetic | Cost |
|---|---|---|
| Images | 20 x 1,500 x $0.30/1M | $0.009 |
| Prompt text | 20 x 800 x $0.30/1M | $0.005 |
| Output | 20 x 300 x $2.50/1M | $0.015 |
| **Total** | | **~$0.03** |

**Verdict: one-shots are ~10x cheaper (~$0.03 vs ~$0.40), but both are pocket change.** Cost does
not decide this. Both models also have a free tier ("Free of charge" rows) - within free-tier rate
limits a session costs $0 outright. The deciding factors are session plumbing (§2) and per-frame
detail (§4).

## 4. Resolution reality

- Standard (one-shot) API tokenization: images <= 384 px in both dimensions = 258 tokens; larger
  images are tiled into 768x768 tiles at 258 tokens each. A full camera photo therefore carries
  ~1,000-1,600 tokens of visual detail. https://ai.google.dev/gemini-api/docs/tokens
- Gemini 3 `media_resolution` (per-request/per-part, Gemini 3 models only):
  images low 280 / medium 560 / high 1120 (default) / ultra_high 2240 tokens; **video frames low
  and medium are identical at 70 tokens, high 280, default 70**. Docs' own guidance: high is for
  "text-heavy content requiring OCR or detailed analysis".
  https://ai.google.dev/gemini-api/docs/media-resolution
- So the structural gap: a Live video frame carries **70 tokens** of detail by default (280 max),
  vs **~1,120-1,600** for a one-shot photo. A 16-23x detail deficit per look. Docs never state a
  pixel resolution the Live path downscales to, and never answer "can it find a small component in
  a cluttered engine bay" - that is empirical (spike 4).
- Whether `mediaResolution` can be raised per-session on the Live API (the config field exists in
  Live session config per the capabilities page, but frame token counts for Live are not
  published) is also spike territory.
  https://ai.google.dev/gemini-api/docs/live-api/capabilities

## 5. Model choice

- Live API models today: **gemini-3.1-flash-live-preview** (current; text/image/audio/video in,
  text+audio out; 131,072 in / 65,536 out; function calling, search grounding, thinking supported;
  caching, structured outputs, URL context NOT supported; last update March 2026) and
  **gemini-2.5-flash-native-audio-preview-12-2025** (previous gen, 3x the video input price).
  https://ai.google.dev/gemini-api/docs/models/gemini-3.1-flash-live-preview,
  https://ai.google.dev/gemini-api/docs/pricing
- Gemini 2.0 Flash (incl. its Live variant) is deprecated, shutdown June 1 2026 - already gone.
  https://ai.google.dev/gemini-api/docs/pricing
- One-shot: gemini-3.5-flash-lite (SubAgent's `DEFAULT_MODEL`, `code`) at $0.30/1M input including
  images; gemini-3.5-flash exists at $1.50/$9.00 if lite's vision proves too weak.
- Vision-quality notes from model cards: the 3.1-flash-live card publishes **none** - no
  benchmark, no guidance. Any claim about its ability to spot a charcoal canister would be
  invented. Not documented; spike 4.

## Needs an on-device spike (L10)

1. **Plain-key video accept.** Send one JPEG frame down the existing `GeminiLiveSession` socket on
   Kevin's key and get the model to describe it. Docs say it works; nothing here has run it.
2. **Real tokens/frame.** Read `usageMetadata` after streaming N frames; resolves the 33-vs-70
   tok/frame pricing ambiguity and whether `mediaResolution` is honored on Live.
3. **Session survival.** Verify the 2-min A/V cap fires without compression, that
   `contextWindowCompression` lifts it, and that `sessionResumption` + GoAway handling survives
   the ~10-min connection recycle mid-conversation.
4. **Vision adequacy.** The actual product question: same engine bay, Live frame (70/280 tok) vs
   one-shot photo (~1,120+ tok), ask for a specific component. No doc answers this.
5. **Frame source ergonomics.** CameraX -> JPEG at 1 fps while the mic is hot (half-duplex session
   today); does frame traffic disturb VAD/turn-taking. Undocumented interaction.

## Sources

- https://ai.google.dev/gemini-api/docs/live
- https://ai.google.dev/gemini-api/docs/live-guide
- https://ai.google.dev/gemini-api/docs/live-api/capabilities
- https://ai.google.dev/gemini-api/docs/live-session
- https://ai.google.dev/gemini-api/docs/pricing (+ pricing.md.txt for verbatim rows)
- https://ai.google.dev/gemini-api/docs/models/gemini-3.1-flash-live-preview
- https://ai.google.dev/gemini-api/docs/media-resolution
- https://ai.google.dev/gemini-api/docs/tokens
