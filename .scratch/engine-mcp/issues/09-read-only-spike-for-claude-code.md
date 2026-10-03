---
map: engine-mcp
ticket: "09"
title: "Read-only spike, Claude Code queries the engine through a stdio adapter"
type: build
status: built
status-detail: "owing a live query with Kevin's token"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
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

## Built 2026-10-02

`tools/mcp/engine/` (`pyproject.toml` pinned `mcp>=1.2,<2` like the siblings, `uv.lock`,
`server.py`, `test_server.py`), registered in `.mcp.json` as `engine` through
`launch.py --require LEGION_ENGINE_URL --require LEGION_ENGINE_TOKEN`. Row and token notes in
`tools/mcp/README.md`.

Tools, all GET through one `_get`: `due` (events bucketed upcoming / recent / overdue / repeating /
undated; repeat rules not expanded, said in the docstring), `checklists` (with items),
`places`, `ledger_transactions` (provenance, `verified`, a note beginning "Unverified" on every
UNRECONCILED row, `unverified_count`), `pantry_receipts` (same, plus `unaccounted_cents` never
summed). Date and account filters run client side, because the engine lists only by cursor.
A failure on any page returns nothing, never the pages before it.

Verification accounted for:

| Step | State |
|---|---|
| Claude Code lists the tools | done against a stdio client (five tools listed); not yet in Kevin's Claude Code session |
| One query returns rows from Kevin's household | **owed**: needs Kevin's token in `.claude/mcp.env` |
| Unset token: launch fails naming the variable | done, `launch.py` prints `LEGION_ENGINE_TOKEN not set ... Server not started.` |
| Bad token: refused, nothing read | done against a fake engine (401); owed against the real one |
| Engine stopped: unreachable, not "no events" | done, fake and a closed port |
| Which tools were useful, which descriptions misled | **owed**: written after the live session |
| Server suite untouched; docs_check passes | no `server/` change; docs_check run |

A 403 is read as "account is in no household": `IsHouseholdMember` refuses before `household_of`
runs, so the engine's own household sentence never reaches the wire and the adapter says it.
