---
map: aspect-engine
ticket: "22"
title: "Cutover per aspect: the engine becomes the read and write path"
type: task
status: claimed
status-detail: "Cutovers 1 (Notes+Places) and 2 (Pantry) MERGED and DATA-VERIFIED on the A25 2026-08-24. Pantry: 3+26 intact, zero dupes, the three anchor fields (subtotal/tax/otherCharges) live on the seeded phone - the gate now persists what it verifies. Owed: a real receipt scan end to end. Next: ledger."
blockers: []
blocked-by: []
open-blockers: 0
ready: true
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
