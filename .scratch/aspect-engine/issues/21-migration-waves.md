---
map: aspect-engine
ticket: "21"
title: "Migration waves: every aspect onto the engine"
type: task
status: claimed
status-detail: "Wave 1 (notes/lists/places) MERGED and VERIFIED on the A25 2026-08-23: Notes 12/12, Places 3/3 live rows copied exactly, tombstones excluded, old tables untouched. Kevin ruled one Notes aspect. Waves 2-4 (pantry, ledger, fleet) remain."
blockers: ["17", "18", "19", "20"]
blocked-by: ["[[17-build-voice-surface]]", "[[18-build-widget-pager]]", "[[19-build-dates-aspect]]", "[[20-build-mirror-sync]]"]
open-blockers: 3
ready: false
tags: [ticket]
---
# Migration waves: every aspect onto the engine

## Question

Execute ticket 14's order, one wave at a time, cutover per aspect, never big bang:

1. **Wave order:** notes/lists/places, then pantry, then ledger (gate re-plumb), then fleet.
   (Wave zero - Dates plus a user-authored aspect - ships with tickets 16-19.)
2. Each wave: per-aspect carve (entities to record types with field defs, plugin-internal state,
   deletions), plugin registration per ticket 11 (required fields declared and badged), Room data
   migration with verbatim SQL and a migration test, Drive export of old tables first, old tables
   retained until on-device verification, then dropped in their own migration.
3. Each wave ends verified on the A25 with a hash-checked install, suite green both key ways,
   before the next begins.
4. **Owed from ticket 11:** the per-plugin inventory table (tools, widgets, screens, workers),
   produced per wave before its build starts.
