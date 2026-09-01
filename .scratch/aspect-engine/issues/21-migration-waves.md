---
map: aspect-engine
ticket: "21"
title: "Migration waves: every aspect onto the engine"
type: task
status: resolved
status-detail: "Resolved 2026-08-24. All four waves merged and verified on the A25 against real data: Notes 12/12, Places 3/3, Pantry 3+26 all LLM_RECONCILED, Ledger 161+7 provenance 1:1, Fleet 5 vehicles + 52 schedules + 1 OBSERVED + 4 ASSERTED with the Jeep drift case preserved. Cutover and deferred fleet entities live in tickets 22 and 23."
blockers: ["17", "18", "19", "20"]
blocked-by: ["[[17-build-voice-surface]]", "[[18-build-widget-pager]]", "[[19-build-dates-aspect]]", "[[20-build-mirror-sync]]"]
open-blockers: 2
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

## Answer

Resolved 2026-08-24. Every aspect's data now lives on the engine, additive-only, old paths
untouched:

| Wave | Verified on the A25 |
|---|---|
| 1 notes/lists/places | Notes 12/12, Places 3/3 (tombstone correctly excluded); Kevin ruled ONE Notes aspect |
| 2 pantry | 3/3 receipts, 26/26 line items, all LLM_RECONCILED; sum-vs-total gap traced to legacy discarding tax at ingestion (cutover must persist anchors) |
| 3 ledger | 161 DETERMINISTIC + 7 UNRECONCILED, 1:1, rule-7 reconcile pass reviewed clean |
| 4 fleet | 5 vehicles, 52 schedules, 1 OBSERVED + 4 ASSERTED; drifted Jeep anchor preserved, same-mileage different-service NOT collapsed |

Every wave senior-reviewed; waves 2 and 4 were BLOCKed and fixed (unwired migration + partial-copy
flag; dedup OR-across-rows fact drop). Remaining work is deliberately split out:
[Cutover per aspect](22-cutover-per-aspect.md) and
[Deferred fleet entities](23-fleet-deferred-entities.md).
