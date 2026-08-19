---
map: import-sync-duplication
title: Import rekey vs UNION sync
charted: 2026-08-16
charted-by: ""
effort: ""
tickets: 1
open: 0
status: closed
tags: [map]
---
# Import rekey vs UNION sync

Charted 2026-08-16, from a defect found while installing an unrelated build.

## Destination

A device where `MidnightImport` has finished for good, no table is duplicated, and a local rewrite of
a row's `vehicleId` can never again be read by sync as the arrival of a new row. Ends with the
existing duplicate rows cleaned up on Kevin's phone.

## Notes

- **Do not dedup before the loop is stopped.** Cleaning first just gets undone on the next launch.
- Everything here touches Kevin's REAL data on the A25. `install -r` only, never `adb uninstall`,
  never `pm clear`. Pull the DB read-only to measure; propose deletions, do not perform them
  unasked.
- The A17k holds the only other copy of this data. Never write to it.
- This is adjacent to CLAUDE.md §2's open finding 2 (Drive has no compare-and-swap, sync must become
  append-only). Read that before proposing a sync change, because this defect is the append-only
  direction's own failure mode and any fix has to satisfy both.

## Decisions so far

<!-- one line per closed ticket -->

## Not yet specified

- **Whether the import should exist at all now.** It is a one-shot migration that has already carried
  Midnight AI's history across, and it reruns forever because it cannot latch. Retiring it may be
  simpler than repairing it, but that decision needs to know whether any device still needs a first
  import.
- **What sync should do about an identity-changing local write**, in general rather than just for
  this one rekey. Wider than this effort; may need to be its own map.
- **The dedup itself**: which copy survives, and whether `id` ordering is a safe tie-break.

## Out of scope

- The 2026-08-03 sentinel bug's original cause. That is history and is already documented at length
  in `MidnightImport.kt`'s own doc comments. This effort is about the repair looping, not about the
  damage it was written to repair.
