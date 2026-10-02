---
map: engine-mcp
ticket: "02"
title: "What the SDK, Django and the target clients actually support at spec 2026-07-28"
type: research
status: resolved
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: false
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

**Resolved 2026-10-02 by a research agent. Findings and citations:
[research/02-sdk-django-and-client-support.md](../research/02-sdk-django-and-client-support.md).**
Each fact there is tagged `documented` / `tested` / `traced` / `reasoned`. Nothing was run against
the real engine, real Django or gunicorn.

1. **SDK 2.2.0 under WSGI: yes in practice, no on paper.** The docs say ASGI only.
   - Driving the SDK's ASGI app synchronously, with a fresh session manager per request, answered
     `server/discover`, `tools/list`, `tools/call` and a legacy `initialize`, all as
     `200 application/json` with no session minted (`tested`, SDK alone, not inside Django).
   - Two catches. First, the default host guard returns **421** for a public Host, so
     `transport_security` is required (`tested`). Second, sync tools run on a worker thread, so the
     DRF `request` that `household_of` needs must be passed in explicitly (`traced`).
   - `TokenVerifier` is `async verify_token(token) -> AccessToken | None`. The SDK serves PRM and the
     401 challenge, but only inside its own Starlette app (`documented`).
   - It is a resource server only. Something else issues tokens.
2. **`django-mcp-server` is unusable at 2026-07-28.**
   - Its pin is `mcp>=1.8.0` with no upper bound, so a fresh install pulls 2.2.0 and its import
     fails (`tested`).
   - Last release 0.5.7 (2025-10-10). Its auth docs cite 2025-03-26.
   - DRF auth classes plug in, but only on SDK 1.x.
3. **Clients.**
   - Static header: Claude Code, Gemini CLI and Gemini Interactions API yes. claude.ai beta only.
     ChatGPT no.
   - OAuth: **every OAuth client in the table does DCR.** CIMD: Claude and ChatGPT yes. Gemini app
     and CLI do not document it.
   - claude.ai states auth-spec support only up to 2025-11-25.
4. **Back-compat** is a MAY in the spec, but legacy clients cannot reach a modern-only server. The
   SDK serves both eras on one endpoint with nothing configured.
5. **Gemini Live has no MCP on the wire.** The Python SDK converts MCP to function declarations on
   the client side. Remote MCP exists in the Interactions API, not Live. **04's bridge is the only
   path for the phone's Live session.**
6. **Cloud Run:** 32 MiB per HTTP/1 request and response, 300 s default timeout. **gunicorn's 60 s
   is the binding limit**, and it is below claude.ai's 240 s tool timeout. `subscriptions/listen`
   would pin a sync worker.

**What it changes:**

- **01:** an in-Django mount is viable on SDK 2.2.0 via a sync bridge. `django-mcp-server` is out
  unless it is pinned to SDK 1.x.
- **05:** device tokens cover Claude Code, the Gemini CLI and Interactions. claude.ai (outside the
  beta), ChatGPT and the Gemini app need an OAuth AS. DCR is the one registration method all of them
  share.
- **08:** third-party clients need public HTTPS, and claude.ai calls from `160.79.104.0/21`. CIMD
  makes the AS fetch client URLs outbound.
