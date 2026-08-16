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
5. Next launch the restored `default` rows are rekeyed again, and one more copy lands.

Each launch adds one full copy. Six launches, six copies.

**Mechanism CORRECTED after verification (2026-08-16).** The first draft of this ticket said the
duplication was rekeyed rows "piling up" because these tables have no unique constraint. That was
reasoned, and it was wrong about which statement creates the rows. An `UPDATE` reuses rowids and
cannot manufacture them. The six copies of one `(pid, timestamp)` tuple carry row ids:

```
4645, 25176, 30993, 36284, 41526, 46768
```

The last three gaps are **exactly 5,242**, one full shard's worth, and every copy sits in its own
ascending id block. Those are six separate INSERT batches. The import logs `inserted=0` on every
run, so the import is not the inserter - **sync's pull is.** The corrected sequence:

1. Drive still holds the `obd_samples` rows keyed `vehicleId = "default"`.
2. Sync pulls. Its identity for the table is `(vehicleId, pid, timestamp)`, so the local copies -
   which the import already rekeyed to `imported-mitsubishi-outlander-2020` - **do not match**. The
   rows read as absent, and UNION mode inserts a full fresh batch under `default`.
3. The import's rekey then moves that batch onto the Outlander id, where nothing constrains it, so
   it lands beside the five already there.
4. Drive is unchanged by any of this, so the next sync does it again.

The rekey is not the duplicator. It is the thing that makes each pulled batch *invisible to the next
pull*, which is what turns a repeated pull into unbounded growth. Both halves are needed.

This also explains the asymmetry between the two observed launches (`obd_samples rekeyed=0` then
`rekeyed=5242`): the rekey only has something to move once a sync has pulled a fresh batch.

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
- **`on-device`, VERIFIED 2026-08-16**: sync's pull is the inserter. Six copies of one
  `(pid, timestamp)` occupy six separate ascending row-id blocks, the last three exactly 5,242 apart
  (one full shard), while the import reports `inserted=0` on every pass. An UPDATE cannot mint
  rowids, so the rekey is not creating rows. Verified WITHOUT running a sync, which matters: a manual
  sync would have pushed the local duplicates to the shared Drive file and on to the other phone.
- `reasoned`, still NOT directly observed: that the Drive-side file specifically still holds
  `default`-keyed rows. It is the only source consistent with rows appearing under `default` when the
  import inserts nothing, but Drive was never read - there are no credentials outside the app.
- **Unknown, and it matters:** whether Drive now also holds the duplicated Outlander-keyed rows.
  UNION pushes as well as pulls, so it probably does, which would mean the other phone is affected
  too. Cannot be settled without reading the Drive file.
- `reasoned`: that the 6.0x factor means six prior rekey passes. Uniform across two unrelated tables,
  but the launch history was not independently reconstructed.

## Proposed fix

Three stages, in this order. Stage 3 is destructive and needs Kevin's explicit go-ahead.

### Stage 1 - stop the growth (small, safe, deletes nothing)

Make `rekeyExistingRows` check the destination before moving. Where an equivalent row already exists
at the target identity, **DELETE the source row instead of UPDATEing it**. That single change:

- ends the `UNIQUE constraint failed` exceptions on `vehicle_specs` and `maintenance_items`, since
  those are exactly the collisions it would now handle rather than throw on;
- lets the reconciliation gate finally latch, so the import stops rerunning;
- stops each pulled batch adding a copy - the pull still happens, but the batch is discarded instead
  of folded in beside the previous ones.

This does not fix the cause. It bounds the damage at today's level, which is worth having before
anything slower.

### Stage 2 - fix the cause: reassign on Drive, not only locally

**The right primitive already exists and already syncs.** `drive_reassignments` is in
`SyncEngine.REGISTRY` (`sync/SyncEngine.kt:165`, `Mode.LWW`, keyed by `syncId`) and
`applyReassignments` (`:377`) consumes it immediately after the `obd_samples` merge, rewriting
`vehicleId` over a time range. It was built for exactly this shape of problem: moving samples from
one car to another in a way the OTHER device also learns about.

The sentinel repair should write a reassignment rule rather than performing a local-only `UPDATE`.
Then the change propagates, the Drive-side rows stop being `default`-keyed, and the pull stops
re-manufacturing them. The loop closes at its source instead of being mopped up every launch.

Two things to settle before building it:
- `applyReassignments` currently rewrites **`obd_samples` only**. `daily_drive_logs`,
  `monthly_recaps`, `vehicle_specs` and `maintenance_items` are duplicated too, and need either the
  same treatment or a different answer.
- Its rules are time-ranged (`fromMs`/`toMs`). A whole-history reassignment needs a range that
  genuinely covers everything, or an explicit unbounded form.

### Stage 3 - dedup what is already there (DESTRUCTIVE, needs sign-off)

Keep the lowest `id` per identity tuple, delete the rest. The counts are known exactly:

| Table | vehicle | delete | keep |
|---|---|---|---|
| `obd_samples` | Outlander | 26,210 | 5,242 |
| `daily_drive_logs` | Outlander | 126 | 25 |
| `monthly_recaps` | Outlander | 7 | 1 |

Plus the orphaned sentinel rows under `default`, once stage 2 makes them safe to drop.

**Do not run stage 3 before stage 1 ships**, or the next launch undoes it. And if Drive also holds
the duplicates, a local-only dedup gets re-pulled - which is why stage 2 is not optional.

### Not proposed: retiring the import

Worth considering separately, and deliberately not folded in here. It is a one-shot migration that
has already carried Midnight AI's history across, and it reruns forever only because it cannot
latch. Stage 1 makes it latch, which may make retirement moot.
