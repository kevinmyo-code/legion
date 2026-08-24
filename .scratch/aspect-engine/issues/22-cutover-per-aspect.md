---
map: aspect-engine
ticket: "22"
title: "Cutover per aspect: the engine becomes the read and write path"
type: task
status: resolved
status-detail: "Resolved 2026-08-24. All five cutovers merged, device-verified, and the home flip approved by Kevin on the phone: I like it. The engine is the app."
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Cutover per aspect: the engine becomes the read and write path

## Question

The four waves copied data onto the engine; nothing reads or writes it yet except the pager,
mirror, and meta-tools. Cutover flips each aspect, one at a time, verified on the phone before
the next (ticket 14's rule). Per aspect: screens and voice tools read engine records; writes go
through RecordStore; ingestion plugins write engine-side through the gate; the legacy tables are
dropped only after the cutover is verified, in their own migration, with a Drive export first.

Open questions each cutover must answer (accumulated from the waves and reviews):
1. Dual-write or freeze during transition; the wave-3 reconcile pass retires when ledger cuts.
2. Pantry: persist subtotal/tax/other so the gate invariant is re-checkable post-hoc (wave 2).
3. Fleet: the ASSERTED staleness window (wave 4 follow-up 8); which of the 33 dies-into-meta-tools
   LiveToolbox tools go per cutover (docs/architecture/tool-inventory-2026-08-23.md).
4. The pager becomes the app home (mission-control default arrangements ship as seeded layouts);
   MainActivity's fate.
5. get_grocery_spend / spend aggregation needs COMPUTED group-by or a query tool (wave 2 note).

## Answer

Resolved 2026-08-24, all five cutovers in one arc, each senior-reviewed and device-verified:

| Cutover | Verdict path | On-device proof |
|---|---|---|
| 1 Notes+Places | BLOCK (SyncEngine raw-SQL places writer) -> fixed | 26+14 rows, zero dupes |
| 2 Pantry | APPROVE + atomicity test round | 3+26, anchor fields live on the seeded phone |
| 3 Ledger | BLOCK (catch-up first-launch race) -> guid-set fix | 161+7, provenance 1:1 |
| 4 Fleet | BLOCK (anchor cross-row pairing) -> single-row rule | 5+52+5, drift dead by construction |
| 5 Home flip | APPROVE (stale docs fixed at merge) | Cold start clean; **Kevin: "i like it."** |

Every blocking finding was a real bug caught by reading code adversarially; every fix verified on
the phone by pulling the database, never assumed. 569 active engine records; one write door;
provenance on every row.

Follow-ups on the board, not owed here: widget tap-through to the generated screens (the named
gap), legacy-table drops per aspect after soak (own migrations, Drive export first),
[Deferred fleet entities](23-fleet-deferred-entities.md), the Supabase sync build, semantic recall.
