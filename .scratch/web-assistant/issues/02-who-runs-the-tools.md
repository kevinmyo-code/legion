---
map: web-assistant
ticket: "02"
title: "Who runs the tools for a browser session, and how the engine authenticates it"
type: decision
status: resolved
status-detail: "Kevin: the browser calls the engine"
blockers: ["01"]
blocked-by: ["[[01-live-from-an-iphone-pwa]]"]
open-blockers: 0
ready: false
tags: [ticket]
---

# Who runs the tools for a browser session, and how the engine authenticates it

## Question

In a browser Live session, Gemini sends tool calls to the browser. Who executes them?

- The browser calls a session-authenticated engine endpoint with the same tool set as `/mcp`,
  through `visible()` for the signed-in member (Mia sees only shared + hers).
- Or the engine relays the whole Live session server-side (Cloud Run WebSockets, min-instances 0,
  5.4 s cold start).
- `/mcp` is device-token only by design; a cookie path must not open `/mcp` itself (CSRF reasoning in
  `server/engine_mcp/views.py`). A separate endpoint, or a token minted for the session?
- Throttling and audit (`McpCall`) for browser calls.

## Answer (2026-10-04)

**Kevin: the browser runs the tools by calling the engine.** Voice flows browser <-> Google
directly on the engine-minted token; each `toolCall` the browser receives is executed by a call to
the engine, signed in as that member.

Opus's details, built unless vetoed:
- **A separate session-authenticated endpoint** (e.g. `/api/assistant/tool`), never `/mcp` itself:
  `/mcp` stays device-token only, as its view's CSRF reasoning requires. Django session + CSRF token,
  same-origin only.
- **Same tool implementations as `/mcp`** (one registry, two doors), every read through `visible()`
  for the signed-in member: Mia's assistant cannot see Kevin's private rows, and the reverse.
- Throttled per member and audited like `McpCall` (tool name, outcome, no arguments stored).
- The locked token carries the tool declarations with `BLOCKING` behaviour, so the browser cannot
  add, remove or reword a tool.

**Also ruled the same day (Kevin):** Mia is on a recent iPhone on **iOS 27** (WebKit bug 326286,
playback crackle with the mic open, applies). **No device spike first: build it all, test after.**
**Screen lock or app switch ends the conversation** cleanly; coming back starts fresh, and the
session-only history clears.
