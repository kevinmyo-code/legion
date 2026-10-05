---
map: purchase-log
ticket: "04"
title: "The phone's half: Room replica and sync, or engine-only"
type: decision
status: resolved
status-detail: "Kevin: online only"
blockers: ["01"]
blocked-by: ["[[01-the-bought-entry]]"]
open-blockers: 0
ready: false
tags: [ticket]
---

# The phone's half: Room replica and sync, or engine-only

## Question

CLAUDE.md §7: with the server down the phone reads its Room replica and queues writes. Does the
bought log get a Room table and sync like checklists (full offline), or does the phone reach it only
through the engine (the `ask_engine` bridge for voice, REST for the screen), saying so in words when
the server is down? Cost of each, and which existing sync to copy.

## Answer (2026-10-04)

**Kevin: online only.** The phone reaches the bought log through the engine (REST for the screen,
`/mcp` via the `ask_engine` bridge or a direct voice tool for voice). No Room table, no sync.

This is a deliberate exception to CLAUDE.md §7's "the phone reads its Room replica when the server
is down" for this one feature, ruled by Kevin. What still binds: **with the server unreachable,
every surface says so in words** ("I can't reach the bought log right now, so I didn't log
shampoo"), never an empty list and never a silent drop.
