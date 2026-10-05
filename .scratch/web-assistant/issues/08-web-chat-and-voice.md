---
map: web-assistant
ticket: "08"
title: "Web: chatbox and live voice"
type: build
status: built
status-detail: "Built; vitest 585, playwright 6, tsc and check:api clean; owes a real mint, a real connect and Mia's iPhone"
blockers: ["03", "05", "07"]
blocked-by: ["[[03-one-prompt]]", "[[05-chat-and-voice-ui]]", "[[07-engine-endpoints]]"]
open-blockers: 1
ready: false
tags: [ticket]
---

# Web: chatbox and live voice

## Build

Per 03, 05, 07 in `server/frontend/`: chat panel, live voice (mic capture, playback, interruption),
tool calls answered via the engine, the shared prompt, every failure said in words. Vitest +
Playwright green. Owed on a real iPhone.

## Built (2026-10-05, feat/web-assistant-web)

- `src/assistant/`: `live-client.ts` (token, socket, typed and spoken turns, tools via the engine,
  transcript, end reasons), `audio.ts` (the seam), `web-audio.ts` + `capture.worklet.ts` (capture
  at the device rate resampled to 16 kHz PCM16 in 20 ms chunks, echoCancellation on; 24 kHz
  playback context, interruption flush), `pcm.ts`, `deps.ts` (generated client only).
- `components/assistant/`: family orb and sheet (opens to typing, mic inside, minimise keeps a
  status), workbench rail entry plus docked panel, "/" focuses the box. Name from the engine.
- Tests: Vitest 585 passed (36 new client/pcm + 14 app-level), Playwright `e2e/assistant.spec.ts`
  6 passed with Chromium's fake microphone, `tsc -b` and `check:api` clean.

Owed on devices (ticket 10): a real mint and connect, iOS 27 mic prompt, crackle (WebKit 326286),
speaker vs earpiece, screen lock, keyboard/visualViewport behaviour, a tool call by voice and typing.
