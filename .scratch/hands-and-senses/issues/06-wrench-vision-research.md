# What can Gemini Live video actually do for wrench mode, on Kevin's key?

Type: research
Status: resolved
Blocked by: -

## Question

Wrench mode ("look at this engine bay - where is the charcoal canister?") rides vision plus fleet
context. Two candidate shapes: streamed video frames into the existing Live session
(`GeminiLiveSession`), or snap-a-photo one-shots through `SubAgent`'s existing inline-image path.
Surface the facts from Google's own Live API and Gemini API docs (ai.google.dev):

1. **Video on the Live API.** Supported input: frame format, resolution, frame rate, how frames
   interleave with audio on the WebSocket. Is camera streaming a first-class Live API feature on a
   plain API key, or an app-only capability?
2. **Session limits.** Live session duration caps, context-window behavior on long sessions,
   reconnect semantics. A garage session is tens of minutes.
3. **Cost.** Per-minute/token pricing for Live audio and video input on the current tiers, and for
   Flash image one-shots. Estimate: a 30-minute wrench session at 1 fps vs 20 one-shot photos.
   Money math in real numbers, Kevin's key pays.
4. **Resolution reality.** What input resolution does the API accept/downscale to, and is it
   enough to identify a component in a cluttered engine bay, per the docs' own guidance on image
   detail? Note what is documented vs what needs an on-device spike.
5. **Model choice.** Which models currently serve the Live API vs Flash one-shots; vision quality
   notes from Google's own model cards only.

Write findings to `research/06-wrench-vision.md`, cite every claim to the owning URL, then append
the Answer here and set Status: resolved. Flag anything that needs an on-device spike rather than
a documentation answer (L10: run the real thing).

## Answer

Resolved 2026-08-16. Full findings with citations: `../research/06-wrench-vision.md`. All claims
`docs` (ai.google.dev, fetched today) unless noted; nothing is `on-device` yet - spike list below.

1. **Video on Live API: yes, first-class, plain key.** Frames are individual JPEG/PNG images sent
   via `realtimeInput` on the same WebSocket as audio, **max 1 fps**. API-key auth is the
   documented dev path (ephemeral tokens only "recommended" for production); no app-only gating.
   `GeminiLiveSession` already pins `gemini-3.1-flash-live-preview`, which lists image+video input.
2. **Session limits are the hard part.** Without compression an audio+video session dies at
   **2 minutes** (audio-only: 15). Connection lifetime ~10 min regardless. A garage session
   requires `contextWindowCompression` (lifts duration to unlimited) plus `sessionResumption`
   handles (valid 2 h) and GoAway handling. Also context math: 1 fps x 70 tok/frame = 126k tokens
   in 30 min, which alone fills the 131k window - compression mandatory twice over.
3. **Cost: irrelevant either way.**

   | Path | Arithmetic | Total |
   |---|---|---|
   | Live 30 min, audio + 1 fps video | audio in 30x$0.005 + video 30x$0.002 (or 126k tok x $1/1M) + audio out 8x$0.018 + ~30k text tok x $0.75/1M | **~$0.36-0.44** |
   | 20 one-shot photos (gemini-3.5-flash-lite) | 20 x (~2,300 tok in x $0.30/1M + 300 tok out x $2.50/1M) | **~$0.03** |

   One-shots ~10x cheaper, both pocket change; free tier covers both at $0 within rate limits.
   Cost does not decide the shape.
4. **Resolution is the real gap.** Live video frames default to **70 tokens each** (280 at high);
   a one-shot photo carries ~1,120-1,600 tokens (768x768 tiles at 258 each / Gemini 3 image high
   1120). 16-23x per-look detail deficit for the stream. Docs publish no pixel dimensions and no
   cluttered-scene guidance; "can it find the charcoal canister" is empirical only.
5. **Models.** Live: `gemini-3.1-flash-live-preview` (current, already pinned) or
   `gemini-2.5-flash-native-audio-preview-12-2025` (3x video price). 2.0 Flash Live is shut down
   (June 2026). One-shot: `gemini-3.5-flash-lite` (SubAgent default). No vision-quality notes
   exist on the 3.1 live model card at all.

**Spikes required (L10):** (1) one JPEG frame down the existing socket on Kevin's key; (2) real
tokens/frame via `usageMetadata` (docs imply 33 vs 70 tok/frame, factor-2 ambiguity) and whether
`mediaResolution` is honored on Live; (3) compression + resumption + GoAway survival past 2 min
and 10 min; (4) same engine bay, Live frame vs one-shot photo, name a component; (5) CameraX at
1 fps against half-duplex VAD.

**Read of the evidence** (`reasoned`, not a decision): docs favor a hybrid - Live stream for
"look around" context, but snap a full-res one-shot through `SubAgent` when the user asks to
identify something specific, since the one-shot path carries an order of magnitude more visual
detail per look and costs nothing.
