---
map: aspect-engine
ticket: "21"
title: "Migration waves: every aspect onto the engine"
type: task
status: claimed
status-detail: "Waves 1-3 merged and VERIFIED on the A25 2026-08-23. Wave 3: 161 DETERMINISTIC + 7 UNRECONCILED, provenance 1:1 with the legacy ledger, rule-7 reconcile pass reviewed clean. Wave 4 (fleet) in flight."
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
