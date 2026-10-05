---
map: web-assistant
ticket: "01"
title: "Gemini Live from an iPhone PWA: ephemeral tokens, mic, playback, lock screen, cost"
type: research
status: resolved
status-detail: "Viable with caveats; iOS audio is the risk"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---

# Gemini Live from an iPhone PWA: ephemeral tokens, mic, playback, lock screen, cost

## Question

Can a live, interruptible voice conversation with Gemini Live run inside an installed iOS Safari PWA,
with the key held by the engine? Primary sources (ai.google.dev, WebKit, Apple docs), dated, tagged.

- Live API **ephemeral tokens**: how the engine mints them, lifetime, what they can be scoped to
  (model, config, tools), and whether the browser can connect to Live directly with one.
- The current Live model the phone uses (`gemini-3.8-live`, per decisions 2026-10-02) from a browser.
- iOS standalone PWA: `getUserMedia` mic permission behaviour (prompt each launch?), AudioWorklet /
  16 kHz PCM capture, 24 kHz playback, echo cancellation, what happens on screen lock / app switch,
  Bluetooth / car audio.
- Tool calling from a browser Live session: the browser receives `toolCall` and must answer it.
- Cost per hour of conversation at current prices, and per typed turn.
- Fallbacks if live voice is not viable on iOS (record-and-send per turn).

## Answer (2026-10-04)

**Viable with caveats.** Full detail: [research/01-live-from-an-iphone-pwa.md](../research/01-live-from-an-iphone-pwa.md).

- **Google side solid (sourced):** ephemeral tokens (Preview, Live-only) minted by the engine with
  `POST /v1beta/auth_tokens`; the browser connects to `BidiGenerateContentConstrained` with
  `access_token`. A token carrying the setup locks system prompt, tools and voice against the browser.
  Lock with a field mask that leaves session resumption open (reasoned; prove in 07).
- `gemini-3.8-live` works this way; audio out only, text via output transcription; typed and spoken
  turns can share one session; put `BLOCKING` in the locked tool config so the honesty rule holds.
- **iOS is the risk:** mic works in home-screen web apps but likely re-prompts each cold launch;
  open WebKit bugs on playback quality with the mic open (311451; 326286 filed today, iOS 27,
  crackle with echo cancellation, earpiece without); screen lock or app switch likely ends the
  conversation (resume within 2 h); AirPods drop to call-quality audio.
- **Cost (reasoned):** about $0.23 for a 5-minute conversation; $3.50-7.50 per hour of continuous
  talk depending on compression; about $0.006 per typed turn. The key must be on a billed project.
- **Fallback if iOS fails:** record each turn, engine runs a non-live model with all tools server-side
  (~$0.002 per turn), reply spoken by the browser.
- **Owed:** a real token mint and connect, and a spike on Mia's actual iPhone before the web build.
