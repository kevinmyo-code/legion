---
map: engine-mcp
ticket: "02"
title: "What the SDK, Django and the target clients actually support at spec 2026-07-28"
type: research
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---

# What the SDK, Django and the clients support

## Question

Facts that 01, 05 and 08 wait on, from primary sources (spec, SDK source and release notes, client
docs). Not a recommendation.

1. **Official `mcp` Python SDK 2.x** (2.2.0, 2026-09-07, web-sourced): can a server run in stateless
   JSON-response mode under **WSGI**, or only ASGI? How is it mounted inside an existing Django app?
   What does `TokenVerifier` expect, and does the SDK serve Protected Resource Metadata
   (`/.well-known/oauth-protected-resource`) for us?
2. **`django-mcp-server`** (gts360): last release, which spec revision it implements, whether it is
   built on SDK 1.x or 2.x, whether DRF auth classes (so `DeviceTokenAuthentication`) work unchanged.
3. **Clients, one row each**: Claude Code, claude.ai custom connectors, ChatGPT, the Gemini app.
   Which spec revisions each speaks today, whether each can send a static `Authorization` header
   (bypassing OAuth), and which OAuth registration each supports: Client ID Metadata Documents, DCR,
   or pre-registered only. DCR is deprecated in 2026-07-28 (web-sourced); a client that only does
   DCR decides what the engine must support.
4. **Back-compat:** must a 2026-07-28 server also answer 2025-11-25 clients (with `initialize`),
   and does the SDK do that for free?
5. **Gemini Live:** does the Live WebSocket API accept MCP servers in its setup message, or is MCP
   only in the Python/JS SDKs? This decides whether 04's bridge is the only option.
6. **Cloud Run:** any request or response size or timeout limit that a Streamable HTTP response
   would hit at the current gunicorn `--timeout 60`.

## Resolution

A `/research` subagent writes findings to `.scratch/engine-mcp/research/` and links them here, each
fact with its source and date.
