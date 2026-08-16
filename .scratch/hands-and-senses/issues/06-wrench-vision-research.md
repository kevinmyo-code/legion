# What can Gemini Live video actually do for wrench mode, on Kevin's key?

Type: research
Status: open
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
