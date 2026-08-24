---
title: C3 Data
level: c3
tags: [architecture]
verified: 2026-08-24
---

# C3: Data layer and controllers

## Room

**Version 37**, declared in `data/local/CarDatabase.kt`. `exportSchema = true`, schema JSON
committed under `app/schemas/`, migration chain complete through `MIGRATION_36_37` (`records.guid`,
the cross-device identity column added for the mirror/sync effort) in `data/local/Migrations.kt`.
No destructive fallback: the downgrade path was removed 2026-08-12.

> **Drift warning, and the standing fix for it.** This page previously said v25 while the code was
> at v34, and before that said v21 while a `CarDatabase.kt` KDoc said 15. The code is the truth,
> checked as `sed -n '/version = /p' data/local/CarDatabase.kt`, not this page or CLAUDE.md's prose.

60 entities, 60 DAOs, one per entity (v34 through v37 added `Aspect`, `RecordType`, `FieldDef`,
`EngineRecord`, `WidgetInstance`, and `MutedReminder` on top of the 53 counted at v34). Grouped by
aspect:

| Aspect | Entities |
|---|---|
| **Fleet** (17) | `Vehicle`, `VehicleSpec`, `VehicleCapability`, `ServiceRecord`, `MaintenanceItem`, `BuildEntry`, `OdbSample`, `CodeEvent`, `CodeClearEvent`, `OilAnalysis`, `ChassisQuirk`, `ForesightNote`, `MonthlyRecap`, `DailyDriveLog`, `YearlyWrapped`, `Drive`, `DriveReassignment` |
| **Ledger** (5) | `LedgerTransaction`, `Category`, `CategoryRule`, `BudgetTarget`, `IngestedFile` |
| **Pantry, grocery, meals** (6) | `PantryReceipt`, `PantryLineItem`, `GroceryItem`, `GroceryStaple`, `MealTarget`, `MealLog` |
| **Body** (6) | `WorkoutPlan`, `WorkoutPlanItem`, `WorkoutSetLog`, `BodyweightLog`, `SleepTarget`, `SleepLog` |
| **Notes, places, tasks** (6) | `ItemList`, `ListItem`, `ListItemSkip`, `TaggedPlace`, `PlaceReminder` *(tombstone)*, `CarTask` *(tombstone)* |
| **Companion and AI** (4) | `MemoryEntry`, `CompanionMemory`, `EpisodicTurn`, `CompanionProfileEntity` |
| **Advisor and goals** (2) | `Goal`, `AdvisorAdvice` |
| **Aspect engine** (6) | `Aspect`, `RecordType`, `FieldDef`, `EngineRecord`, `WidgetInstance`, `MutedReminder` |

`CarTask` and `PlaceReminder` were tombstones from v10; as of the 2026-08-24 cutover (see below),
**every legacy fleet/ledger/pantry/notes/places table in this file is a tombstone by the same
definition** - still in the schema, still holding the pre-cutover rows, but written by nothing.
Do not delete them (one soak period first) and do not start using them for new writes.

## The aspect engine (spine as of 2026-08-24)

The six rows above under **Aspect engine** are the entire schema for a runtime metadata system -
`aspects`/`record_types`/`field_defs` define what a record TYPE looks like, `records` holds every
record of every type as a JSON payload plus a typed identity (`guid`, unique-indexed as of
`MIGRATION_36_37`, for cross-device merge). `engine/RecordStore.kt` (a `class`, not an `object` -
instantiated once and threaded through, not a global singleton like the 28 controllers below) is
the **only writer of records**: reference integrity, per-field delete policy, a 30-day trash, and
computed-field materialization all live there and nowhere else. See [[cutover1-2026-08-24]] through
[[cutover5-2026-08-24]] for the five flips (notes+places, pantry, ledger, fleet, home) and
[[wave1-carve-2026-08-23]] through [[wave4-carve-2026-08-23]] for the migration waves that copied
every legacy row onto it beforehand. Full detail: `docs/architecture/tool-inventory-2026-08-23.md`.

Three lifecycle facts worth knowing:

- A single instance guarded by a named `LOCK`, shared cross-file with `sync/DatabaseSnapshot` so
  snapshot and restore cannot race Room.
- The physical file name is a shared constant, so snapshot and Room cannot drift apart.
- A `RoomDatabase.Callback` seeds starter categories on `onCreate`, added at v12 to fix a fresh
  install landing with zero categories.

Migration discipline: [[0017-room-migrations-additive]].

## Controllers

31 of them. **30 are Kotlin `object` singletons.** No DI container, no ViewModel layer. Compose
reads them directly. The one exception is `LiveSessionController`, a `class` instantiated once by
`AriaForegroundService`. (`engine/RecordStore.kt`, the record write door, is a second `class` -
see the "aspect engine" section above - but it is not counted among these 31 because it is not a
domain controller, it is the storage layer the domain controllers now call into.)

**Fleet** - `VehicleController` (the roster, labels, identity and VIN writes; the largest),
`VehicleSpecController`, `BuildSheetController`, `DailyDriveLogController`, `MonthlyRecapController`,
`YearlyWrappedController`, `FleetSpendController` (the fleet-to-ledger seam), `DtcClearController`,
`GarageController`, `WeatherController`, `LocationController`, `PlaceController`,
`ReminderController`.

**Ledger** - `LedgerController`.

**Pantry and food** - `PantryController`, `GroceryController`, `MealController`.

**Body and goals** - `WorkoutController`, `SleepController`, `GoalController`.

**Notes** - `NotesController`.

**Media** - `MusicController`, `SpotifyController`, `NowPlayingController`, `VolumeController`.

**Platform** - `LiveSessionController`, `TelephonyController`, `GlanceCardController`.

## Sync

`sync/SyncEngine.kt` runs on a process-lifetime scope that nothing cancels. Two merge modes:

- **`Mode.UNION` on `syncId`** for immutable rows. `ledger_transactions` and seven other tables.
- **`Mode.LWW` on a natural key** for rows that legitimately change state. `ingested_files` on
  `driveFileId`.

[[0011-ledger-sync-union-and-lww]] records why the long-standing "sync must become append-only"
blocker turned out to be false, and why that matters more than the fix.

**Standing caveat: `sync/` has never executed in LEGION.** Every claim about its behaviour is traced
from source. None is tested. Treat it accordingly.

## Related

[[c2-containers]] for scopes and lifecycles. [[c3-ingestion]] for what writes the ledger tables.
