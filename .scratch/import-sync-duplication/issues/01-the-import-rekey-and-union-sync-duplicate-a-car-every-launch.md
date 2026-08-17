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

## Stage 1 attempt, 2026-08-16: the loop IS stopped, and NOT by the code

Read this before touching any of it, because what fixed the device is not what is in the build.

### Outcome on Kevin's A25

**The import is retired.** `midnight_import gate: completed=true (key=completed_v3)` on relaunch,
and no table lines after it. The rekey no longer runs at all, so nothing is duplicating any more.
Frozen final state:

| Table | vehicle | total | distinct | factor |
|---|---|---|---|---|
| `obd_samples` | Outlander | 36,694 | 5,242 | 7.0x |
| `daily_drive_logs` | Outlander | 169 | 25 | 6.8x |
| `monthly_recaps` | Outlander | 9 | 1 | 9.0x |
| everything | Jeep / F-150 | - | - | clean |

### What actually fixed it

Not the stage 1 code. A **side effect of its partial run.**

The stage 1 build got as far as `vehicle_specs` (`discarded=1`) and `maintenance_items`
(`discarded=16`), committed both, and then hung on `obd_samples`. Those two tables were the ONLY
two failing, because they are the only two with a real primary key over their identity. With their
colliding sentinel rows gone, the reverted build's own unmodified rekey found nothing to collide
with, reported `0 failed`, and latched `KEY_COMPLETED` for the first time since 2026-08-03.

So the device was fixed by deleting 17 rows, and the mechanism that stops the loop is the gate
latching, exactly as designed. **The bug is untouched in code.** Any other device in this state still
has it.

### Why the stage 1 code was reverted

**It hangs.** On `obd_samples` it ran for over four minutes with no progress, thread state `D`
(uninterruptible I/O) and CPU frozen at 33.52s across three minutes of observation - blocked, not
merely slow. The unmodified pass does the same table in ~21 seconds. Sync's own writer is the
likely other side of the lock, since `maybeAutoSync` fires from `MainActivity` at essentially the
same moment the import starts, but that was not proven.

Two separate defects were found and fixed in the attempt before it was abandoned, both worth keeping
if this is picked up again (the work is stashed: `git stash list`, "stage-1 rekey fix, HANGS on
device - needs rework"):

1. **A per-row `SELECT ... LIMIT 1` probe was pathological.** 11,511 shard rows against an
   `obd_samples` with no index over `(vehicleId, pid, timestamp)`. Replaced with one identity-set
   load per destination id, mirroring `loadExistingKeys`. Did not fix the hang.
2. **`places` has no `vehicleId` column at all.** Loading that identity set eagerly threw
   `no such column: vehicleId` and failed the table on-device. The long-standing
   `if (!row.has(VEHICLE_COL)) continue` guard is what has always kept such tables out of this
   function, so the load has to sit behind it, not in front. Six unit tests were written, including
   one pinning exactly this.

### What this changes about the remaining stages

- **Stage 1 is no longer urgent on this device** and should not be re-attempted as written. If it is
  wanted for other devices, it needs to not hold a long write transaction while sync is running -
  which probably means doing the work in bounded batches, or not at the same moment as
  `maybeAutoSync`.
- **Stage 3 (dedup) is now SAFE to do**, and it was not before. Nothing regenerates copies once the
  gate is latched, so a dedup will hold. Still destructive, still needs Kevin's go-ahead: delete
  31,452 `obd_samples`, 144 `daily_drive_logs`, 8 `monthly_recaps`, all under the Outlander only.
- **Stage 2 is still the real fix** and is unaffected by any of this. Drive still holds
  `default`-keyed rows - proven again during this session: the stage 1 build deleted the sentinel
  `vehicle_specs` and `maintenance_items` rows at 00:41, and by 00:58 sync had put them back. They
  are inert now only because nothing rekeys them any more.

### Honest accounting

The duplication went from 6.0x to 7.0x during this work. Launching the app is what adds a copy, and
diagnosing this required launching it. That is one full extra copy of the Outlander's samples,
attributable to the investigation rather than to normal use.

## Assumptions ledger, stage 1 attempt

- `on-device`: `gate: completed=true` on relaunch with no subsequent table lines; the `0 failed`
  pass that latched it; the final counts table above, from a pulled DB with WAL.
- `on-device`: the hang - thread `D` state with CPU frozen at 33.52s across three minutes, against a
  ~21s baseline for the same table on the unmodified build.
- `on-device`: sync restoring the sentinel `vehicle_specs`/`maintenance_items` rows between 00:41 and
  00:58, after the stage 1 build had deleted them. This is the strongest direct evidence yet that
  Drive still holds `default`-keyed rows.
- `tested`: six unit tests for the stashed rekey fix, all passing (1340 total), including the
  free-destination, occupied-destination, repeat-4-times, PK-collision, no-vehicle-column, and
  driver-s-own-row cases.
- `reasoned`, NOT proven: that sync's writer is the other side of the lock the stage 1 build blocked
  on. Timing and `maybeAutoSync`'s call site fit; no lock was actually traced.
- The reverted build was verified installed by sha256 (`c136aad9...`), and the app source at that
  commit is identical to the ticket-16 build - the two commits between them touched only `.scratch/`.

## Stage 3 done, 2026-08-16: deduped on the device

Authorised by Kevin. 31,604 rows deleted. Every table on the phone now has `total == distinct`.

| Table | before | after | deleted |
|---|---|---|---|
| `obd_samples` (Outlander) | 36,694 | 5,242 | 31,452 |
| `daily_drive_logs` (Outlander) | 169 | 25 | 144 |
| `monthly_recaps` (Outlander) | 9 | 1 | 8 |

Database file 4,513,792 -> 1,736,704 bytes after `VACUUM`.

### How it was done

There is no `sqlite3` binary on the A25 (checked `PATH`, `/system/bin`, `/system/xbin`, and the
runtime apex), so the work could not happen in place. Sequence, with the app force-stopped
throughout so nothing else held the database:

1. **Backup pulled first**, all three files, sizes matched against the device:
   `scratchpad/backup-20260816/legion_database{,-wal,-shm}`.
2. Dedup performed on a COPY, never on the device: `PRAGMA wal_checkpoint(TRUNCATE)`, then per table
   `DELETE ... WHERE vehicleId = <outlander> AND id NOT IN (SELECT MIN(id) ... GROUP BY <identity>)`.
   Lowest `id` survives, which is the earliest-inserted copy.
3. **Verified on the copy before anything was pushed**: Outlander `total == distinct` on all three
   tables; rows NOT under the Outlander unchanged (13,452 / 53 / 3); `PRAGMA integrity_check` = ok;
   `user_version` still 21; and a full table-by-table diff against the backup showing **exactly three
   tables changed out of forty-five**, with the Jeep identical on `obd_samples`, `daily_drive_logs`,
   `monthly_recaps`, `maintenance_items`, `service_records` and `code_events`.
4. `VACUUM`, then pushed and swapped in via `run-as cp`, with the stale `-wal`/`-shm` removed since
   the checkpointed file is complete on its own. File ownership stayed `u0_a311` because `run-as`
   runs as the app.
5. sha256 verified at three points: local file, `/data/local/tmp` after push, and
   `databases/legion_database` after the swap. All `e47cc95b...`.

### After

App launches clean - no crash, no Room migration attempt, `user_version` 21 accepted as-is. FLEET
renders, the Jeep reads `1998 JEEP CHEROKEE` / `about 227,495 mi` / `0 DUE - 7 UNKNOWN`, and
`midnight_import gate: completed=true` still holds, so nothing re-runs the rekey.

Counts re-read off the live device after several minutes of runtime: unchanged, 18,694 rows in
`obd_samples` total, every vehicle at `total == distinct`.

### What is deliberately still there

The `default`-keyed sentinel rows were NOT touched: 5,242 `obd_samples`, 24 `daily_drive_logs`, 2
`monthly_recaps`, 16 `maintenance_items`, 1 `vehicle_specs`. They are orphans under an archived
vehicle, they are not duplicates of each other, and deleting them is stage 2's business - sync would
simply pull them back, as it demonstrably did within 17 minutes earlier today. Removing them before
the Drive side is fixed would be theatre.

### Assumptions ledger

- `on-device`: every count above, before and after, from pulled databases with sizes matched.
- `on-device`: the three-point sha256 chain, the clean launch, the rendered FLEET screen, and the
  latched import gate afterwards.
- `built`: the 45-table diff proving only three tables changed, and the Jeep unchanged across six
  tables.
- `reasoned`, NOT proven: that the dedup survives a Drive sync. Counts held across several minutes
  of runtime with sync enabled, but `SyncEngine` logs nothing, so no sync was OBSERVED to run in that
  window. The reason to expect it holds is that UNION merges by identity tuple, and every identity
  Drive could offer is already present locally - unlike the original loop, where the rekey changed
  the identity and made the pulled rows look new. If duplicates ever reappear without the import
  running, that reasoning is wrong and this is the line to revisit.
- The backup lives at `scratchpad/backup-20260816/` in a SESSION-TEMP directory. Copy it somewhere
  durable if it is wanted beyond this session.

## Stage 2 done, 2026-08-16: the import records a synced rule

The import no longer repairs its sentinel re-key with a local `UPDATE` sync cannot see. It writes a
`DriveReassignment` rule, which is the mechanism that already existed for this exact problem.

`VehicleController.reassignDrive`'s doc comment, written 2026-07-16, states the hazard in as many
words:

> *"Writes a RULE rather than re-keying the rows directly: `obd_samples` syncs UNION on an identity
> that INCLUDES vehicleId, so a plain UPDATE would leave the originals on Drive under the old id, and
> the next sync would re-insert them - cloning the drive onto both cars instead of moving it,
> permanently, on every device."*

`rekeyExistingRows` then did precisely that plain UPDATE. **The fix was not to invent anything; it was
to use the primitive already sitting one package over.**

### Why a rule converges where an UPDATE oscillates

Not because it re-keys harder. Because of WHERE `SyncEngine` applies it (`sync/SyncEngine.kt:466`):
inside `syncFile`, after the merge and **before** the converged snapshot is re-read and uploaded. So
the rows Drive receives already carry the corrected id, the sentinel-keyed originals stop coming
back, and the correction sticks. The file's own comment says the same thing about the caller it was
built for: *"Re-keying after syncFile returned would fix this device and re-upload the OLD rows
anyway, so the correction would resurrect on every pass, forever, on every device."*

### What was built

`MidnightImport.recordSentinelReassignments`, called whenever the remap is non-empty - deliberately
NOT gated on rows having moved locally, because a device can hold no sentinel rows of its own and
still be handed them by Drive on a later sync.

- **Deterministic `syncId`** (`midnight-import-rekey:<old>-><new>`), not a fresh UUID. The import
  re-runs until its gate latches, roughly six times on Kevin's phone, and six rules all saying the
  same thing would be replayed one after another by `DriveReassigner.plan` on every sync forever.
- **Unbounded time range** (`0 .. Long.MAX_VALUE`). The rule shape is time-ranged because its
  original caller corrects one drive; here a car's entire history is on the wrong id.
- **Self-moves are never written.** `plan` already drops them ("a self-move would be an infinite
  no-op on every sync pass forever"), so writing one would be storing a known-bad row.

Five unit tests, 1334 -> 1339. No schema change: `drive_reassignments` already existed and is
already in the sync registry.

### It is DORMANT on Kevin's phone, by design

Verified after installing: `drive_reassignments` holds **0 rows**, because
`midnight_import gate: completed=true` and `run()` returns before any of this. The code is correct
and protects any device that still has an import to do. It does nothing for the device that already
had the problem.

That is not a gap in the fix. It is what stage 1's outcome already achieved: the import is retired
here, so the loop cannot restart regardless.

### What remains, and why it was NOT done unasked

Drive still holds the sentinel-keyed rows. Locally they are still present too: 5,242 `obd_samples`,
24 `daily_drive_logs`, 2 `monthly_recaps`, 16 `maintenance_items`, 1 `vehicle_specs`, all under the
archived `default` vehicle. Converging them would mean writing the reassignment rule straight into
the database by hand, the way the dedup was done. **That has a real cost and it is Kevin's call:**

1. Applying the rule moves the local 5,242 sentinel `obd_samples` onto the Outlander id, where an
   identical 5,242 already sit and nothing constrains them - so it **re-duplicates that table once**,
   deliberately, on the way to converging.
2. One sync pass then uploads the corrected snapshot and Drive stops holding sentinel rows.
3. A second dedup pass cleans up the one-time artifact.

Net: one Drive write, one duplication, one dedup, to tidy rows that are currently inert under an
archived car and harming nothing. Worth doing for correctness, not urgent.

**Known gap either way:** `applyReassignments` rewrites `obd_samples` ONLY. `monthly_recaps` and
`daily_drive_logs` are also UNION with `vehicleId` in their identity, but they are keyed by
year/month/day rather than a millisecond timestamp, so a `fromMs`/`toMs` window cannot address their
rows at all. Extending the rule shape to date-keyed tables is its own design question. Those tables
hold 2 and 24 rows against `obd_samples`' 5,242, so the volume argument for solving it now is weak.
`maintenance_items`/`vehicle_specs`/`vehicles` need nothing - LWW over a real primary key replaces
rather than duplicates, which is exactly why they never grew.

### Assumptions ledger

- `built`: `compileDebugKotlin -Pnokey` and the full suite green at 1339, and `app/schemas/` byte
  unchanged (no migration).
- `tested`: rule contents, idempotency across five re-runs, one rule per remapped vehicle, self-move
  refusal, and that the rule plans into the intended move via the real `DriveReassigner.plan`.
- `on-device`: installed by sha256, clean launch, dedup still holding at `total == distinct`, and
  `drive_reassignments` empty because the gate is latched.
- `traced`: `SyncEngine.syncFile` applying reassignments before re-reading the snapshot it uploads;
  the registry modes and identities for every table named above.
- `reasoned`, NOT proven: that applying the rule on this device would re-duplicate `obd_samples`
  once before converging. It follows from UNION merge inserting by identity plus an unconstrained
  target table, but it was not run - which is the main reason it is being offered as a choice rather
  than performed.

## Convergence ATTEMPTED and REVERTED, 2026-08-16. Stage 2's claim is not proven.

Kevin authorised converging Drive. It was tried, it did not work, the device was restored, and the
result contradicts what the stage 2 commit claimed. Recording that plainly because the claim is in a
commit message and in this file above it.

### What was done

A `DriveReassignment` rule was written by hand into the database (app stopped, fresh backup taken
first): `default -> imported-mitsubishi-outlander-2020`, `fromMs = 0`,
`toMs = 1785416586731` (the last sentinel sample).

**The bound is not `Long.MAX_VALUE`, and that matters beyond this attempt.** `default` is not only the
imported car's stale id, it is ALSO this device's live placeholder for a car with no dongle paired.
An unbounded rule would sweep every FUTURE placeholder sample onto the imported car, forever, on
every pass. The shipped code had exactly that bug for about an hour; it now bounds at
`System.currentTimeMillis()`, and a test pins that it is not unbounded.

### What happened

| Pass | `default` | Outlander | distinct |
|---|---|---|---|
| before | 5,242 | 5,242 | 5,242 |
| after sync 1 | 0 | 10,484 | 5,242 |
| after sync 2 | 0 | 15,726 | 5,242 |

The rule applied correctly - `default` emptied on the first pass, exactly as designed. But the second
pass added **another full 5,242**, which means Drive handed the sentinel-keyed rows back again. The
rule did not converge anything. It simply took over driving the same loop the import used to drive,
one copy per launch.

Restored from `backup-preconverge` at that point. Verified after a further launch and sync: every
vehicle back to `total == distinct`, `drive_reassignments` empty, Jeep untouched at 7,006 samples, no
regrowth.

### Why the stage 2 reasoning was wrong

The reasoning was: `syncFile` applies reassignments after the merge and BEFORE re-reading the
snapshot it uploads, so Drive receives corrected ids and stops serving stale ones. That reasoning
describes the code accurately. It is also evidently not what happens here, because the stale rows
came back.

**`monthsToSync` was checked and ruled out** - it drops months older than the retention floor, and
the sentinel samples are 2026-07-27/30, comfortably inside a 365-day window from today.

**The leading hypothesis is now that the UPLOAD half of sync is failing**, silently, and has been for
some time. It fits every observation better than anything else considered so far:

- the pull demonstrably works (rows arrive), the push demonstrably does not take effect (Drive keeps
  serving the same stale rows);
- it explains why the original duplication accumulated for roughly six passes over weeks without
  Drive ever self-correcting;
- it explains why deleting the sentinel `vehicle_specs`/`maintenance_items` rows locally saw them
  restored within 17 minutes;
- and it explains this attempt exactly.

**This is a hypothesis, not a finding.** It was not confirmed: `SyncEngine` logs nothing on this
path, the phone locked partway through the attempt so the Drive sync screen (which reports status and
errors, `ui/DriveSyncScreen.kt`) could not be read, and there are no Drive credentials outside the
app. **Confirming it is the next step, and it should happen before any further convergence work** -
if uploads are failing, then no reassignment rule, however well shaped, can ever converge anything,
and the real defect is somewhere else entirely.

### What this does and does not change about the shipped code

- The code change **stands**: writing a synced rule is strictly better than a local `UPDATE` that
  sync cannot see, and `VehicleController.reassignDrive`'s 2026-07-16 doc comment is unambiguous that
  a plain UPDATE is wrong for a UNION table keyed on `vehicleId`.
- The **claim that it converges is withdrawn.** It is untested in the only environment that matters,
  and the one attempt to test it here failed. The commit message says it converges; this section is
  the correction.
- The forward-bound fix (`now` instead of `Long.MAX_VALUE`) is a genuine improvement discovered by
  doing this, and is worth keeping regardless of what happens to the rest.

### Assumptions ledger

- `on-device`: every count in the table above; the restore, verified by sha256 (`e47cc95b...`) and by
  re-reading counts after a further launch and sync.
- `on-device`: `monthsToSync`'s retention floor does not exclude 2026-07 (read the code, checked the
  sample dates against today).
- `reasoned`, NOT confirmed: that Drive uploads are failing. It is the best fit for all the evidence
  and it is checkable, but nothing was traced and no Drive file was read.
- The pre-converge backup is at `scratchpad/backup-preconverge/`, and the older post-dedup backup at
  `scratchpad/backup-20260816/`. Both are in a SESSION-TEMP directory.

## The upload hypothesis was WRONG, and the rule came back from Drive

The Drive sync screen was read once Kevin unlocked the phone. Two things landed at once.

### Uploads work

`SYNC NOW` reported **"Synced with your Google Drive."** Google Drive shows `Connected`, the grant is
`Granted`, and the whole-database backup feature (a separate thing from table sync, worth knowing
exists) lists three backups, newest Aug 15 19:40 at 19,479 rows.

So the previous section's leading hypothesis - that the push half of sync was silently failing - is
**disproven.** Recorded because it was written down as the most likely explanation and it was wrong.

### The real reason the revert did not stick

Immediately after that successful sync, the database showed `drive_reassignments: 1` and the
Outlander back at 10,484.

**The hand-written rule had synced to Drive before the revert.** `drive_reassignments` is
`Mode.LWW` keyed on `syncId`, so restoring a local backup deleted the local copy and changed nothing
remotely - and the very next pull brought it straight back and applied it again. The revert removed
the symptom from one side of a two-sided system.

That also explains the failed convergence attempt without needing any upload defect: the rule was
live the whole time, on Drive, being re-applied every pass, moving each freshly-pulled batch of
sentinel rows onto the Outlander. One copy per sync, exactly as observed.

### How it was actually stopped

A rule in a synced LWW table cannot be deleted by deleting it locally. It can be **neutered through
the same channel that spread it**: set `newVehicleId = vehicleId` and bump `updatedAt`, so LWW
carries the harmless version to Drive and it wins.

`DriveReassigner.plan` drops self-moves by design - *"a self-move would be an infinite no-op on every
sync pass forever"* - so a neutered rule plans to nothing on every device that receives it, forever.
That property, written for a different reason, is what made a clean exit possible.

Done in one pass with a dedup, then pushed and synced:

| | |
|---|---|
| rule | `default -> default` (neutered), confirmed surviving two syncs |
| `obd_samples` Outlander | 5,242, `total == distinct` |
| `obd_samples` Jeep | 7,006, untouched |
| every vehicle, every table | `total == distinct` |
| stability | held across a force-stop, relaunch and further auto-sync |

Database 1,470,464 bytes.

### Final state, and what is still unexplained

**Stable and clean.** No duplication anywhere, no active rule, growth stopped, Jeep never touched at
any point in any of this.

Drive still holds the sentinel-keyed rows: they arrive on each sync as 5,242 `obd_samples` under
`default`, plus 24 `daily_drive_logs`, 2 `monthly_recaps`, 16 `maintenance_items`, 1 `vehicle_specs`.
They are inert - nothing re-keys them now - and they sit under an archived vehicle, invisible.

**What is genuinely not understood:** why the reassignment rule failed to converge Drive while it was
live. The design reads correctly - `syncFile` applies reassignments before re-reading the snapshot it
uploads, so the July shard should have been replaced with Outlander-keyed rows and the sentinel rows
should have stopped arriving. They did not stop. `monthsToSync`'s retention floor was checked and
excluded as a cause. **Whatever the answer is, it is upstream of anything this ticket has touched,
and it should be found before any future convergence attempt.**

### What a future attempt must know

1. **Anything written to a synced table is not local.** A hand-written row in `drive_reassignments`
   propagates, and cannot be taken back by restoring a local backup. Neuter it through LWW instead.
2. **Test convergence on a copy, not on the live Drive.** There is no undo for the shared file.
3. **The `default` sentinel is dual-purpose** - stale imported id AND the live unpaired-car
   placeholder - so any rule touching it must be bounded forwards in time. That fix is in the shipped
   code and is worth keeping regardless of what happens to the rest.

### Assumptions ledger

- `on-device`: the Drive sync screen contents; "Synced with your Google Drive."; the rule returning
  after the revert; every count in the table above; stability across a further force-stop, relaunch
  and auto-sync.
- `built`: the neutered rule and dedup verified on a copy before pushing - `total == distinct` for
  every vehicle, integrity ok, `user_version` 21 - then sha256-matched onto the device.
- **Disproven**: that Drive uploads were failing. They are not.
- **Unexplained, and flagged as such**: why a live reassignment rule did not converge the Drive-side
  rows. Not a hypothesis worth recording yet, because the two offered so far were both wrong.
- Backups: `scratchpad/backup-20260816/` (post-dedup) and `scratchpad/backup-preconverge/`, both in a
  SESSION-TEMP directory.

## Verification 2026-08-16 - PARTIALLY BUILT, and the defective path is UNTOUCHED

Swept against the tree. All `traced` unless noted.

**Stage 2 LANDED.** `MidnightImport.recordSentinelReassignments` (`:557-578`), called from the real
import path (`:329`), writes `drive_reassignments` with a deterministic syncId, INSERT OR REPLACE,
self-moves skipped (`:561`), and the forward-bound range `0 .. System.currentTimeMillis()`
(`:559`, `:570-571`) rather than `Long.MAX_VALUE`. Covered by
`MidnightImportReassignmentRuleTest.kt`. **Its convergence claim was withdrawn by this ticket
itself** and remains unexplained.

**Stage 1 was REVERTED** and survives only as `git stash@{0}` - "stage-1 rekey fix, HANGS on device -
needs rework".

**So the bug's own code path is unchanged.** `MidnightImport.rekeyExistingRows` (`:468-501`) is still
a plain UPDATE ... SET vehicleId WHERE identity AND vehicleId (`:490-493`), with **no
destination-occupancy check and no delete-the-source branch**.

**What stops it today is device state, not a fix** (`reasoned`, from traced code): the
`KEY_COMPLETED = "completed_v3"` latch (`:132`, read `:263`, set `:339` only when `failedTables == 0`)
latched on Kevin's phone after 17 colliding rows were deleted by hand. **Any device whose import has
not latched still carries the bug** - which includes the retired A17k if it is ever run again.

**Also still open, and documented in the code itself** (`:547-555`): `applyReassignments` rewrites
`obd_samples` only.
