---
map: purchase-log
ticket: "04"
title: "The phone's half: Room replica and sync, or engine-only"
type: decision
status: open
status-detail: ""
blockers: ["01"]
blocked-by: ["[[01-the-bought-entry]]"]
open-blockers: 1
ready: false
tags: [ticket]
---

# The phone's half: Room replica and sync, or engine-only

## Question

CLAUDE.md §7: with the server down the phone reads its Room replica and queues writes. Does the
bought log get a Room table and sync like checklists (full offline), or does the phone reach it only
through the engine (the `ask_engine` bridge for voice, REST for the screen), saying so in words when
the server is down? Cost of each, and which existing sync to copy.
