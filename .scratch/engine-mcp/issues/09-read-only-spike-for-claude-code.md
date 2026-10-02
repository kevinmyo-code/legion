---
map: engine-mcp
ticket: "09"
title: "Read-only spike, Claude Code queries the engine through a stdio adapter"
type: build
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---

# Read-only spike: Claude Code queries the engine

## Why this is unblocked

It changes nothing on the server and decides nothing. It wraps REST routes that exist today, with a
device token that exists today, so tenancy comes from `household_of` for free. Its job is to make 01,
03 and 06 concrete: which tools a model actually reaches for, and how bad CRUD-shaped tools are.

## Build

- `tools/mcp/engine/` (`pyproject.toml`, `server.py`), the same layout as `tools/mcp/board/`.
  stdio transport. Pin the SDK the way the sibling servers do unless 02 has landed and says
  otherwise.
- Config from env: `LEGION_ENGINE_URL`, `LEGION_ENGINE_TOKEN`. Registered in `.mcp.json` through
  `tools/mcp/launch.py --require LEGION_ENGINE_TOKEN`, so a missing token fails at launch in words.
  **The token is never committed.**
- Read-only tools, a handful: what is due (events), checklists, places, ledger transactions (with
  provenance), pantry receipts. GET only. No tool calls a write verb; a test asserts it.
- Every failure says what did not happen: engine unreachable, token rejected, household missing.
  Unreachable is never rendered as empty (§1).

## Verification

- Claude Code lists the tools and one query returns rows from Kevin's household.
- Unset token: launch fails naming the variable. Bad token: the tool says the engine refused it and
  nothing was read.
- Engine stopped: the tool says the engine is unreachable, not "no events".
- Write up in this ticket which tools were useful and which descriptions misled, as input to 03 and
  06.
- Server suite untouched (no server change); `python tools/docs_check.py` passes.
