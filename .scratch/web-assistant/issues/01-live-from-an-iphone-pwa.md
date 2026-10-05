---
map: web-assistant
ticket: "01"
title: "Gemini Live from an iPhone PWA: ephemeral tokens, mic, playback, lock screen, cost"
type: research
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
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
