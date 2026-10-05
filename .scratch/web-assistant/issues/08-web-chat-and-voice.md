---
map: web-assistant
ticket: "08"
title: "Web: chatbox and live voice"
type: build
status: open
status-detail: ""
blockers: ["03", "05", "07"]
blocked-by: ["[[03-one-prompt]]", "[[05-chat-and-voice-ui]]", "[[07-engine-endpoints]]"]
open-blockers: 3
ready: false
tags: [ticket]
---

# Web: chatbox and live voice

## Build

Per 03, 05, 07 in `server/frontend/`: chat panel, live voice (mic capture, playback, interruption),
tool calls answered via the engine, the shared prompt, every failure said in words. Vitest +
Playwright green. Owed on a real iPhone.
