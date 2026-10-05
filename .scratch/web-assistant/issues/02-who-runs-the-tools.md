---
map: web-assistant
ticket: "02"
title: "Who runs the tools for a browser session, and how the engine authenticates it"
type: decision
status: open
status-detail: ""
blockers: ["01"]
blocked-by: ["[[01-live-from-an-iphone-pwa]]"]
open-blockers: 1
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
