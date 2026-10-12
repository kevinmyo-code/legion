---
map: backend-erp
ticket: "31"
title: "Phase 6 deletions: the mirror, SyncEngine, and the dead notes-era tables"
type: build
status: open
status-detail: "Opened 2026-10-11 to carry ticket 05's phase 6, which no ticket held. Mirror deletion unblocked since the 2026-08-28 restore drill."
blockers: ["05"]
blocked-by: ["[[05-migration-path]]"]
open-blockers: 0
ready: true
tags: [ticket]
---
# Phase 6 deletions: the mirror, SyncEngine, and the dead notes-era tables

**The build ticket ticket 05 never left behind.** Ticket 05 resolved 2026-08-25 with a phase 6 of
deletions, "only what nothing reads any more". Phases 0 to 5 were carried by tickets 08 to 20. Phase
6 was carried by nothing, so it read as finished. It is not: `engine/mirror/`,
`ui/mirror/MirrorSyncActivity.kt`, `sync/SyncEngine.kt` and the `CarTask`, `ItemList`, `ListItem`
and `PlaceReminder` entities were all still in the tree on 2026-10-11.

## What phase 6 says to delete, as amended

From ticket 05's sequence, with ticket 18's amendment (`f418318`) applied:

1. **The xlsx mirror**, roughly 2,200 lines (`engine/mirror/`, `ui/mirror/MirrorSyncActivity.kt`,
   `MirrorSyncMergeTest`, `MirrorCodecTest`). Retired entirely by ticket 04 ruling 1. Its gate, C2
   (scheduled `DatabaseSnapshot` plus a restore exercised on a device), was met by the restore
   drill on the A25 on 2026-08-28 (`memory/library/decisions.md`: "Phase-6 mirror deletion is
   unblocked").
2. **`SyncEngine`, once its registry is empty.** Ruling 1: no table is ever in two sync channels,
   and each entry leaves in the commit its writes move. Fleet kept its entries under ticket 14, so
   the last drops wait on ticket 26. Deleting the object before the registry is empty is not
   allowed by the ruling.
3. **Genuinely dead tables only**, per ticket 05's C6 clarification: `item_lists`, `car_tasks`,
   `place_reminders`, and `list_items` (ticket 15 ruled it dead once notes moved to the events
   table). Confirm each has no live writer or reader before its drop migration; later checklist
   and groceries work may have touched these, and the ruling does not license dropping a table
   that something reads.
4. **The statement parsers, once C4 is proven.** Not in scope while open decisions
   `22-deterministic-parsing-comes-back-on-the-pc.md` and `25-ledger-ingestion-leaves-the-phone.md`
   question them. Do not delete them under this ticket until those rule.

**Not deleted:** the engine itself. Ticket 18 cancelled that; `engine/EngineBoundaryTest` guards
the scoped engine that remains.

## Rules that bind

- Room drops are their own additive-history migrations: verbatim generated SQL, schema JSON
  committed, migration test, `SCHEMA_VERSION` bumped in lockstep (CLAUDE.md section 5).
- Ticket 05's rollback section: every rollback depends on the code deleted here still existing
  during the middle. Delete only what nothing reads.

## Verification

- [ ] `grep` for each deleted class returns nothing outside history; compile and unit suite green.
- [ ] Each table drop has a migration test.
- [ ] On the A25: the app opens and the Drive backup and restore buttons still work after the
      mirror is gone.
