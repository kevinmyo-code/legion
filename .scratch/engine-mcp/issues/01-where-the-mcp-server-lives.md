---
map: engine-mcp
ticket: "01"
title: "Where the MCP server lives: inside Django, a sidecar, or a stdio adapter"
type: decision
status: open
status-detail: ""
blockers: ["02"]
blocked-by: ["[[02-sdk-django-and-client-support]]"]
open-blockers: 0
ready: true
tags: [ticket]
---

# Where the MCP server lives

## Question

Which process answers MCP requests, and how does it reach the data without bypassing
`household_of` or the gate?

## Constraints (traced 2026-10-02)

- The engine is gunicorn on WSGI, 2 workers, `--timeout 60`, on Cloud Run at min-instances 0
  (5.4 s cold start). No ASGI server runs today, though `legion/asgi.py` exists.
- Spec 2026-07-28 is stateless request/response (web-sourced), which removes the main reason an MCP
  server used to need a long-lived ASGI connection.
- ADR 0044: Django is the only writer, business rules live in Django once.

## Options

| | Shape | For | Against |
|---|---|---|---|
| A | **In-process Django view at `/mcp`**, calling the same service code the REST views call | One auth stack, one tenancy choke point, one deploy, one test DB. Rules cannot drift because there is one copy | Needs an SDK or library that runs under WSGI (02 answers whether the official SDK does); MCP code and REST code share a process and a failure |
| B | **Sidecar process** (official SDK, ASGI) that calls Django's REST API with the caller's token | Uses the reference SDK as designed; MCP failures cannot take down REST | Two deploys on Cloud Run; every tool is an HTTP hop; the sidecar must forward identity exactly, and a bug there is a cross-household read |
| C | **stdio adapter only** (`tools/mcp/engine/`) wrapping REST, run locally | Zero server change; it is ticket 09's spike | Serves Claude Code only. The phone and third-party clients need HTTP |

## Recommendation

**A, with C kept as the dev path until A exists.** Tenancy is the argument: one choke point that is
already tested from both directions beats a forwarding layer that has to be tested separately. If
02 finds the official SDK cannot run stateless under WSGI, A still holds with either
`django-mcp-server` (if it reaches 2026-07-28) or a thin hand-written JSON-RPC view, since a
stateless MCP POST is a JSON-RPC request and a JSON response.

## Resolution

Kevin picks A, B or C after 02 lands.
