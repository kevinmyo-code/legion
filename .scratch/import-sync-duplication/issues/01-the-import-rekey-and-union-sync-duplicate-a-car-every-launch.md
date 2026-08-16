# The import rekey and UNION sync duplicate a car's history on every launch

Type: task
Status: open

## What is happening

Found 2026-08-16 while installing an unrelated build. `MidnightImport` runs on **every launch** and
never completes, and the Outlander's history is now duplicated **exactly six times**.

Measured off a pulled `legion_database` (with WAL), total rows vs distinct identity tuples:

| Table | vehicle | total | distinct | factor |
|---|---|---|---|---|
| `obd_samples` | `imported-mitsubishi-outlander-2020` | 31,452 | 5,242 | **6.0x** |
| `daily_drive_logs` | `imported-mitsubishi-outlander-2020` | 151 | 25 | **6.0x** |
| `monthly_recaps` | `imported-mitsubishi-outlander-2020` | 8 | 1 | **8.0x** |
| `obd_samples` | `12:34:56:11:22:33` (the Jeep) | 7,006 | 7,006 | clean |
| `daily_drive_logs` | `12:34:56:11:22:33` | 25 | 25 | clean |
| `obd_samples` | `car:c352...` (F-150) | 1,183 | 1,183 | clean |

**Only the imported car is affected**, and it is the only one that goes through the rekey path. The
Jeep and the F-150 are untouched. The factor is uniform, which is what a per-launch copy looks like.

## The loop

1. `MidnightImport.rekeyExistingRows` (`data/MidnightImport.kt:462`) repairs the 2026-08-03 sentinel
   bug by rewriting `vehicleId` from `default` to `imported-mitsubishi-outlander-2020`. It is an
   `UPDATE`, so on its own it moves rows and cannot duplicate them.
2. **But `vehicleId` is part of the SYNC IDENTITY.** `SyncEngine.REGISTRY` keys `obd_samples` on
   `(vehicleId, pid, timestamp)` and `monthly_recaps` on `(vehicleId, year, month)`, both
   `Mode.UNION` (`sync/SyncEngine.kt:167`, `:204`).
3. So the rekey does not look like a move to sync. It looks like the **arrival of a brand-new row**
   under a new identity, while the `default`-keyed original is still in the shared Drive file.
4. UNION means union. The rekeyed copy is pushed up; the `default`-keyed original is pulled back
   down. Neither ever wins, because union has no notion of one superseding the other.
5. Next launch the restored `default` rows are rekeyed again, and because none of these tables has a
   unique constraint on its identity columns (`naturalPk = false`, the real PK is an autoincrement
   `id`), the moved rows simply pile up beside the previous copies.

Each launch adds one full copy. Six launches, six copies.

**The two failing tables are the ones that CANNOT duplicate, and they fail loudly for that reason.**
`vehicle_specs` (PK `vehicleId`) and `maintenance_items` (PK `vehicleId, serviceName`) have real
primary keys, so the same `UPDATE` hits a genuine constraint:

```
midnight_import vehicle_specs failed
  UNIQUE constraint failed: vehicle_specs.vehicleId
  at MidnightImport.rekeyExistingRows(MidnightImport.kt:484)
midnight_import maintenance_items failed
  UNIQUE constraint failed: maintenance_items.vehicleId, maintenance_items.serviceName
```

Those two throw, their transaction rolls back, the table is counted as failed, the reconciliation
gate never latches, and the log says `2 table(s) failed, will retry next launch` - forever. **The
constraint is not the bug. It is the only thing reporting the bug.** The tables with no constraint
fail silently by duplicating instead.

## Why nothing caught it

This is CLAUDE.md §4 rule 6 again, in the sync layer: a repair step that cannot fail is not a repair.
`rekeyExistingRows` treats "the destination key is free" as an invariant and never checks it. Where
SQLite enforces the key, it gets an exception. Where SQLite does not, it gets silent duplication and
reports `rekeyed=18` as if that were success.

It also touches CLAUDE.md §2's open finding 2, from the other side. That finding says Drive's lack of
compare-and-swap means last-write-wins **loses** rows, and sync must become append-only. This shows
the matching hazard in the append-only direction: **if a local operation can rewrite part of the sync
identity, an append-only merge grows without bound.** Any move to append-only has to answer this
first.

## Current blast radius

- Kevin's Jeep, the car he actually uses, is **clean** on every table checked.
- The duplication is confined to the imported Outlander and to the archived `default` sentinel, both
  of which are `archived = 1` and therefore invisible in the UI. Nothing on screen is currently
  wrong.
- `obd_samples` is the volume problem: 26,210 junk rows today, growing by 5,242 per launch, and it is
  the table any future telemetry aggregate would read.
- **Two of the six copies were added by this investigation** - the app was launched twice to confirm
  the loop repeats rather than converges. Said plainly because the measurement is part of the
  measured number.

## What to do, not yet decided

1. **Stop the loop before deduping**, or the dedup is undone on the next launch. Either make the
   rekey check the destination first and DELETE the source row when the destination already holds an
   equivalent one, or gate the whole import off once the sentinel is gone.
2. **Decide what sync should do when a local id rewrite changes a row's identity.** This is the real
   question and it is bigger than the import. A tombstone-plus-insert would work for LWW tables and
   does not for UNION ones.
3. **Then dedup the existing rows**, keeping one of each identity tuple. Destructive, over real data,
   and Kevin's call - the counts above say exactly how many rows should survive.
4. **Consider whether the import should run at all anymore.** It exists to carry Midnight AI history
   onto a device once. It has already carried it. A one-shot migration that reruns forever because it
   cannot latch is a standing hazard, and the gate is doing exactly what it was designed to do.

## Assumptions ledger

- `on-device`: every count in the tables above, from `legion_database` pulled with its WAL, sizes
  matched against the device.
- `on-device`: the per-launch log lines, two consecutive launches, showing `rekeyed=18` /
  `rekeyed=5242` repeating rather than draining, and `2 table(s) failed, will retry next launch`.
- `traced`: `rekeyExistingRows` is an `UPDATE ... WHERE identity AND vehicleId = oldId` that assumes a
  free destination; `SyncEngine.REGISTRY` keys `obd_samples`/`monthly_recaps` on tuples that INCLUDE
  `vehicleId`, in `Mode.UNION`; the two failing tables are exactly the two with `naturalPk = true`.
- `reasoned`, NOT directly observed: that Drive sync is what restores the `default`-keyed rows
  between launches. It fits every observation (the sentinel never empties, the factor grows by
  exactly one copy per launch, sync is ON, and the identity includes the column being rewritten), but
  no sync pull was traced end to end, and no Drive-side file was inspected. **Verify this before
  building a fix on it** - if something else is restoring those rows, step 1 above targets the wrong
  thing.
- `reasoned`: that the 6.0x factor means six prior rekey passes. Uniform across two unrelated tables,
  but the launch history was not independently reconstructed.
