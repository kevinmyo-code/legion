---
title: C3 Data
level: c3
tags: [architecture]
verified: 2026-08-18
---

# C3: Data layer and controllers

## Room

**Version 25**, declared in `data/local/CarDatabase.kt`. `exportSchema = true`, schema JSON
committed under `app/schemas/`, migration chain complete through `MIGRATION_24_25` in
`data/local/Migrations.kt`. No destructive fallback: the downgrade path was removed 2026-08-12.

> **Drift warning.** CLAUDE.md says v21 in two places and a KDoc inside `CarDatabase.kt` itself says
> 15. The code is the truth. The class KDoc narrates v1 to v20 in prose and then stops, which is
> probably how the docs drifted in the first place.

46 entities, 46 DAOs, one per entity. Grouped by aspect:

| Aspect | Entities |
|---|---|
| **Fleet** (17) | `Vehicle`, `VehicleSpec`, `VehicleCapability`, `ServiceRecord`, `MaintenanceItem`, `BuildEntry`, `OdbSample`, `CodeEvent`, `CodeClearEvent`, `OilAnalysis`, `ChassisQuirk`, `ForesightNote`, `MonthlyRecap`, `DailyDriveLog`, `YearlyWrapped`, `Drive`, `DriveReassignment` |
| **Ledger** (5) | `LedgerTransaction`, `Category`, `CategoryRule`, `BudgetTarget`, `IngestedFile` |
| **Pantry, grocery, meals** (6) | `PantryReceipt`, `PantryLineItem`, `GroceryItem`, `GroceryStaple`, `MealTarget`, `MealLog` |
| **Body** (6) | `WorkoutPlan`, `WorkoutPlanItem`, `WorkoutSetLog`, `BodyweightLog`, `SleepTarget`, `SleepLog` |
| **Notes, places, tasks** (6) | `ItemList`, `ListItem`, `ListItemSkip`, `TaggedPlace`, `PlaceReminder` *(tombstone)*, `CarTask` *(tombstone)* |
| **Companion and AI** (4) | `MemoryEntry`, `CompanionMemory`, `EpisodicTurn`, `CompanionProfileEntity` |
| **Advisor and goals** (2) | `Goal`, `AdvisorAdvice` |

`CarTask` and `PlaceReminder` are **deliberate tombstones**: still in the schema, read by no new
code, since v10. Do not delete them and do not start using them.

Three lifecycle facts worth knowing:

- A single instance guarded by a named `LOCK`, shared cross-file with `sync/DatabaseSnapshot` so
  snapshot and restore cannot race Room.
- The physical file name is a shared constant, so snapshot and Room cannot drift apart.
- A `RoomDatabase.Callback` seeds starter categories on `onCreate`, added at v12 to fix a fresh
  install landing with zero categories.

Migration discipline: [[0017-room-migrations-additive]].

## Controllers

28 of them. **27 are Kotlin `object` singletons.** No DI container, no ViewModel layer. Compose
reads them directly. The one exception is `LiveSessionController`, a `class` instantiated once by
`AriaForegroundService`.

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
