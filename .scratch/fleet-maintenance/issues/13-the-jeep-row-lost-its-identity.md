# The Jeep row lost its identity and its odometer, in normal use

Type: grilling
Status: resolved (2026-08-15) - fix dispatched, not yet built

## Question

Surfaced by ticket 01, which pulled the real database. The active vehicle row
`12:34:56:11:22:33` today reads:

| field | value |
|---|---|
| `name` | `1998 Jeep Cherokee` |
| `make` / `model` / `year` | **empty / empty / 0** |
| `confirmed` | 1 |
| `onboarded` | **0** |
| `odometerBaseline` | **0** |
| `odometerBaselineAt` | **never** |
| `tripMilesSinceBaseline` | **0.0** |

**It did not always read that way.**

- On **2026-07-18 10:24:12** it had a year, make and model. It must have:
  `applyServiceIntervals` seeded ten maintenance items from a prompt built out of those three
  fields, and `onboardPendingVehicles` (`VehicleController.kt:653-659`) skips a vehicle with a
  blank make or model. Those ten rows are still on disk with that timestamp.
- On **2026-08-12 15:50:02** it had an odometer of about 118,374. It must have: `logServiceDirect`
  (`:179`) derives a `ServiceRecord`'s mileage from `currentMileage(vehicle)`, and the record it
  wrote that second says 118,374.

So five fields were populated and are now empty, and `onboarded` went from true back to false.

**Everything this map ships is built on that row.** A fix that restores the identity and the
odometer is worthless if the same thing happens again next month.

## What was already ruled out (ticket 01, by reading, not assuming)

| Suspect | Verdict |
|---|---|
| Migrations 16->17, 17->18, 18->19 (all ran 2026-08-13) | **Not it.** All three touch `ledger_transactions` and `category_rules` only. Neither mentions `vehicles` |
| `correctVehicle` - the rename, stamped 2026-08-13 14:37:54 | **Not it.** Builds `existing.copy(...)` and coalesces every field (`:434-442`). Preserves the odometer, cannot blank make/model. It could only have renamed a row that was **already** empty |
| `registerDirect` | **Not it.** Preserves the odometer explicitly (`:98-100`) and rejects a blank make or model at the door (`:86`) |
| `setOdometer` | Cannot produce this. It sets `odometerBaselineAt` alongside the baseline; here the baseline is 0 and `odometerBaselineAt` is **never**, a combination it never writes |

## What has to be decided

1. **Find the writer.** Enumerate every path that writes a `vehicles` row and check each against
   the observed end state - specifically, which can produce `make=''`, `model=''`, `year=0` while
   leaving `name` and `confirmed` intact. Candidates not yet audited: `sync/SyncEngine` (LWW merge
   on `vehicles`, `:183`), `data/MidnightImport` (`:190`, imports the table column-for-column - and
   ticket 01 found it **duplicated 5,242 obd_samples rows** between two vehicle ids, so it has
   demonstrably run and demonstrably made a mess), `DriveReassigner`, `ObdDeviceRegistry`, and
   `seedVehicle` (`:1036-1044`) if it can ever fire against an existing MAC.
   **`sync/` has never executed** per MEMORY.md, which if true removes the strongest suspect - so
   confirm that claim rather than inheriting it.
2. **`registerDirect`'s silent field loss, which is real regardless of whether it caused this.**
   It constructs a fresh `Vehicle(...)` rather than copying, so it drops `voiceName`,
   `personaTraits`, `trim`, `archived` and `lastOdometerPromptAt` to their defaults every time it
   runs. **Nobody has ticketed that.** Same class as the bug being hunted here, and cheaper to fix
   than to find.
3. **Whether the row can be repaired from data already on disk.** `vehicle_specs` holds the decoded
   VIN `1FAKEVIN000000001`: 6-cylinder, 4.0L in-line, FCA, Toledo, SUV, decoded 2026-07-26. That is
   enough to restore make and model. The **year** is in the VIN's 10th character (`W`) and is not
   stored as a field. The odometer is recoverable only as an approximation from the service records
   (118,374 on 2026-08-12) - **and an approximation must not be written as if it were a reading.**
   §4 rule 5 applies: a restored odometer Kevin did not state is an estimate.
4. **Why the decode never wrote back at all.** `vehicle_specs` and `vehicles` have disagreed for
   three weeks with nothing noticing. Is the write-back missing by design (specs are a separate
   concern) or by omission? If by design, **something still has to reconcile them**, because
   `check_recalls` and every label surface read `vehicles` and the truth is in `vehicle_specs`.
5. **How this becomes noticeable.** A car with an empty make and model is a detectable state.
   Today it renders as `THIS CAR` and nothing else. Should it be surfaced - the way ledger surfaces
   a quarantine - rather than silently degrading every dependent feature?

## Note on scope

This ticket was not on the original chart. It exists because ticket 01 looked at the data, which is
the fourth time on this repo that looking at the data has found what the test suite could not
(L15, and the 2026-08-13 ledger session's four bugs). **It blocks nothing formally**, but ticket
04's identity fix and ticket 12's recall button are both papering over whatever this is until it
is understood.

## Verification

Whatever writer is identified, reproduce it on a **copy** of the database, never on the phone.
CLAUDE.md §5's discipline for migrations applies to this diagnosis too: prove it against a copy of
Kevin's real data first.

---

## Answer (2026-08-15)

### The mechanism is certain. The trigger is not. Both statements are load-bearing.

**Proof that a whole-row overwrite happened, from the disk itself rather than from a timeline:**

`applyServiceIntervals` (`VehicleController.kt:664-677`) writes the schedule and sets
`onboarded = true` **in the same call**. `onboardPendingVehicles` (`:656`) skips any vehicle whose
make or model is blank. Kevin's row has **ten seeded `maintenance_items` and `onboarded = 0`**.

Those two facts cannot both be true of a row that was never overwritten. The row that received
`onboarded = true` was replaced by one carrying `onboarded = false`.

Corroborating, independently: `log_service` (`LiveToolbox.kt:808-817`) takes **no mileage
parameter** - only `service` and `vehicle`. So `ServiceRecord.mileage` can only come from
`currentMileage(vehicle)` (`VehicleController.kt:179`). The two records read 118,331 and 118,374.
**The odometer baseline existed and is now zero.**

### The mechanism

Two defects that are harmless alone and destructive together:

1. **`VehicleDao.upsert` is `@Insert(onConflict = REPLACE)`** (`VehicleDao.kt:13-22`) - a whole-row
   overwrite. There is no merging write anywhere in the vehicle path.
2. **`seedVehicle` (`:1036-1057`) constructs a `Vehicle` with every default** - blank make, model,
   year; zero odometer; `onboarded = false` - **and persists it.** `vehicleFor` / `currentVehicle`
   (`:265-268`) call it on any `getByMac` miss.

**One miss is permanent, total, and silent.** No log, no user-visible state, nothing that
reconciles the row against the child tables that outlive it.

**`seedVehicle` is the only writer that produces this exact combination.** `registerDirect` sets
`onboarded = false` too, but it requires a non-blank make and model (`:86`) and
`applyServiceIntervals` flips `onboarded` back to true immediately after (`:105`).

### The trigger path, named

**`TelemetryRecorder.kt:217` calls `VehicleController.currentVehicle(context)` every 30 seconds
while driving** - by a wide margin the highest-frequency caller of the seeding path. It re-reads the
vehicle each tick rather than caching (checked - so it is *not* a stale-copy clobberer), which means
every one of those reads is also a chance to seed.

This is not a new observation in this codebase. `CarDatabase.withDatabaseLock`'s own doc comment
(`:343-354`), written up as **"Ravi's review, 2026-08-13 (BLOCKING finding 2)"** - the same day the
row was blanked - describes exactly this thread:

> `TelemetryRecorder` calls [getDatabase] roughly every 30 seconds, independently of anything the
> driver is doing, and a call landing in that gap would see [INSTANCE] `== null` [...] and build a
> **BRAND NEW Room database** against whatever is - or, mid-restore, is NOT - at
> [DATABASE_FILE_NAME] at that exact instant.

A brand-new Room database means `getByMac` returns null, which means `seedVehicle`, which means
REPLACE. **The guard for that window landed in `a09aa68`, dated 2026-08-15 00:19:49 - two days
after the damage.**

### Suspects eliminated, each by evidence

| Suspect | Verdict |
|---|---|
| Migrations 16Ã¢â€ '17, 17Ã¢â€ '18, 18Ã¢â€ '19 (ran 2026-08-13) | **Not it.** All three touch `ledger_transactions` and `category_rules` only |
| `correctVehicle` (the rename, 2026-08-13 14:37:54) | **Not it.** `existing.copy(...)` coalescing every field. Could only have renamed an already-empty row |
| `registerDirect` | **Not it.** Preserves the odometer (`:98-100`), rejects blank make/model (`:86`) |
| `setOdometer` | **Not it.** Always stamps `odometerBaselineAt` alongside the baseline; the row has baseline 0 and `odometerBaselineAt` never - a pairing it cannot write |
| `MidnightImport` | **Not it.** `INSERT OR IGNORE` (`:533`) - structurally cannot overwrite an existing row |
| `DatabaseSnapshot.restore` | **Not it. The file did not exist.** `DatabaseSnapshot.kt` was added in `a09aa68`, 2026-08-15 |

**What made `getByMac` miss on 2026-08-13 is not established**, and this answer does not pretend
otherwise. `closeAndClear`'s only documented caller did not exist yet. An uninstall/reinstall with
an asynchronous Auto Backup restore is a candidate and was not confirmed. **The fix below does not
depend on knowing** - which is the point of fixing the mechanism rather than the instance.

### A confound worth recording

The pre-`a09aa68` `seedVehicle` **fabricated a placeholder `1998 Jeep Cherokee`** (`name = "Midnight"`,
make Jeep, model Cherokee, year 1998) for the `default` sentinel id. Kevin confirms he chose that
placeholder *because* it is his real car.

The consequence is not hypothetical: **no vehicle row written before 2026-08-15 can be distinguished
from a placeholder by its make, model or year.** Any future archaeology on old rows must treat
year/make/model as non-identifying. `a09aa68` made all seeding blank, which ends it going forward.

### Decisions (Kevin, 2026-08-15)

1. **Fix the class, not the instance.** Both halves:
   - **`seedVehicle` never persists.** A car exists when the driver says so, not when a dongle
     appears. The existing sentinel-only exemption generalises to every id.
   - **Whole-row REPLACE stops being how the vehicle row is edited.** Targeted `@Query` UPDATEs, so
     a writer that owns one column writes one column. `upsert` survives for genuine inserts only.
   - Consequence accepted at decision time: a newly-seen dongle no longer auto-creates a car.
     Registration becomes deliberate.
2. **`registerDirect`'s silent field loss is in scope.** It builds a fresh `Vehicle` and so resets
   `voiceName`, `personaTraits`, `trim`, `archived` and `lastOdometerPromptAt` on every call. Same
   class, found while hunting this, fixed with it.
3. **`TelemetryRecorder`'s accumulation moves into SQL** (`tripMilesSinceBaseline = tripMilesSinceBaseline
   + :delta`), removing a read-modify-write race on the app's highest-frequency vehicle write.
4. **Kevin's odometer is restored as ~118,374, derived from the 2026-08-12 service record** - his
   choice over typing a dash reading. **It is not a reading and must never render as one.**
   `odometerBaselineAt` is set to that record's own timestamp rather than to now, so the figure
   carries its own staleness and ticket 10's disclosure work has something true to describe.
   **§4 rule 5: an estimate is labelled an estimate.**
5. **No schema change, no Room bump.** These are queries against existing columns.

### Open, and owed

- **The identity write-back is ticket 04's**, not this one's: `vehicle_specs` has held a decoded VIN
  since 2026-07-26 that nothing propagates to `vehicles`.
- **Detection.** Question 5 of this ticket - should an identity-less active car announce itself
  rather than silently degrading every dependent feature - is **not decided here.** It belongs with
  ticket 04's rule. Carried forward, explicitly, as an unmet item rather than a footnote (L11).
- **The 2026-08-13 verification checked 497 ledger rows and 168,422 cents, and was reported as
  proving the data intact.** It did not read `vehicles`. The blanking is consistent with having
  happened inside the very window that was declared verified. **A verification scoped to the tables
  a change is about cannot testify about the tables it is not.**

### Assumptions ledger

- `traced`: the `onboarded`/`maintenance_items` contradiction; `upsert`'s REPLACE strategy;
  `seedVehicle`'s defaults and its persist call; `vehicleFor`'s seed-on-miss; `log_service` taking
  no mileage parameter; `TelemetryRecorder:217` re-reading per tick; every eliminated suspect above,
  read rather than assumed; the commit dates for `a09aa68`, `withDatabaseLock` and
  `DatabaseSnapshot.kt`; the pre-`a09aa68` `seedVehicle` body, read out of git.
- `reasoned`: that the trigger was a `getByMac` miss produced by a transiently-absent database.
  The mechanism is proven; **this specific cause is not**, and no further evidence was found for it.
- `reasoned`: that the identity and odometer were both present before the overwrite. Derived from
  what `applyServiceIntervals` and `logServiceDirect` structurally require, plus the on-disk
  contradiction - not from any stored history.
- **Not yet `built` or `tested`.** The fix is specified here and dispatched; nothing in this answer
  claims it works.

