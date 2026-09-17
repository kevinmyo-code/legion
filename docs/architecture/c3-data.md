---
title: C3 Data
level: c3
tags: [architecture]
verified: 2026-09-16
---

# C3: Data layer and controllers

## Where the data actually lives

**Postgres, behind Django, is the system of record** ([[0044-django-is-the-engine]]). Room is a
replica the phone reads from and a staging area it writes through. Every row belongs to exactly one
household ([[0045-households-are-tenants]]).

That inverts what this page used to say. The old text described Room plus `engine/RecordStore.kt` as
the storage endpoint, with no server anywhere in it.

## Room

Declared in `data/local/CarDatabase.kt`. `exportSchema = true`, schema JSON committed under
`app/schemas/`, migration chain in `data/local/Migrations.kt`. No destructive fallback: the downgrade
path was removed 2026-08-12.

> **The version number is deliberately not written on this page.** CLAUDE.md §5: never quote a schema
> version from a document, including that one. This page said v25 at v34, v37 at v41, and v37 again
> while the code was far past it. The code is the only truth:
>
> ```
> sed -n '/version = /p' app/src/main/java/com/kevin/legion/data/local/CarDatabase.kt
> ```
>
> `CarDatabase.SCHEMA_VERSION` is a second hand-maintained copy that `DriveSyncScreen` reads to
> decide whether a backup may be restored. `CarDatabaseSchemaVersionTest` fails the build on drift
> between the two.

Likewise the entity count. The authoritative list is the `entities = [...]` array in the
`@Database` annotation; to recount rather than trust a number here:

```
awk '/@Database\(/,/version = /' app/src/main/java/com/kevin/legion/data/local/CarDatabase.kt \
  | grep -o "[A-Za-z]*::class"
```

Grouped by what they are for:

| Group | Entities |
|---|---|
| **Engine sync plumbing** | `OutboxEntry` (the `sync_outbox` queue), `VehicleReplica`, `ServiceHistoryReplica`, `VehicleSidecar`, `BackgroundPassState` |
| **Events** (dates) | `Event`, `EventSkip` |
| **Checklists** (notes) | `Checklist`, `ChecklistItem`, `ChecklistTick`, `ItemList`, `ListItem`, `ListItemSkip` |
| **Fleet** | `Vehicle`, `VehicleSpec`, `VehicleCapability`, `ServiceRecord`, `MaintenanceItem`, `BuildEntry`, `OdbSample`, `CodeEvent`, `CodeClearEvent`, `OilAnalysis`, `ChassisQuirk`, `ForesightNote`, `MonthlyRecap`, `DailyDriveLog`, `YearlyWrapped`, `Drive`, `DriveReassignment` |
| **Ledger** | `LedgerTransaction`, `Category`, `CategoryRule`, `BudgetTarget`, `IngestedFile` |
| **Pantry, grocery, meals** | `PantryReceipt`, `PantryLineItem`, `GroceryItem`, `GroceryStaple`, `MealTarget`, `MealLog` |
| **Body** | `WorkoutPlan`, `WorkoutPlanItem`, `WorkoutSetLog`, `BodyweightLog`, `SleepTarget`, `SleepLog` |
| **Places** | `TaggedPlace`, `PlaceReminder` *(tombstone)* |
| **Voice notes** | `VoiceNote` |
| **Memory, companion and AI** | `MemoryEntry`, `CompanionMemory`, `EpisodicTurn`, `CompanionProfileEntity`, `MemoryAudit`, `ConversationAudit` |
| **Proactive and sitrep** | `ProactiveRaiseRow`, `ProactiveSetting`, `SitrepSchedule`, `SitrepModuleSetting`, `WellbeingDigestSchedule`, `MutedReminder`, `FeedSubscription` |
| **Advisor and goals** | `Goal`, `AdvisorAdvice` |
| **Metering and media** | `GeminiUsage`, `LiveConnectDay`, `MusicPlayHistoryEntry` |
| **Aspect engine** | `Aspect`, `RecordType`, `FieldDef`, `EngineRecord`, `WidgetInstance` |
| **Tombstones** | `CarTask`, `PlaceReminder` |

Migration discipline: [[0017-room-migrations-additive]].

## Two storage shapes, both live

This is the single most confusing thing about the data layer and it is a migration in progress, not
a muddle.

**Shape one, the generic aspect engine.** `aspects`/`record_types`/`field_defs` define what a record
TYPE looks like and `records` holds every record of every type as a JSON payload plus a typed
identity. `engine/RecordStore.kt` is the only writer of **that table**: reference integrity,
per-field delete policy, a 30-day trash and computed-field materialization live there and nowhere
else.

**Shape two, per-aspect typed tables.** `Checklist`/`ChecklistItem`/`ChecklistTick`, `Event`, and the
fleet replicas are ordinary typed Room tables with their own DAOs, written by their own controllers.
`checklists/ChecklistController.kt` writes `ChecklistDao` and never touches `RecordStore`.

[[0039-per-aspect-typed-tables]] supersedes [[0037-the-aspect-engine-is-the-spine]] and makes shape
two the direction: once several clients write one Postgres, enforcement has to live where the
database can see it, and a jsonb payload caps foreign keys and CHECK constraints at whatever the
calling code remembers to do.

**So the old sentence "`RecordStore` is the only writer of records" is wrong twice over.** It is the
only writer of the engine's `records` table, which is now one shape of two; and the phone is not the
authority for any of it, because the server is. Say which table you mean.

Both shapes still have code and rows. Do not delete either on the strength of this page.

## Controllers

Almost all are Kotlin `object` singletons. No DI container, no ViewModel layer; Compose reads them
directly. `LiveSessionController` is a `class` instantiated once by `AriaForegroundService`.
`engine/RecordStore.kt` is a second `class`, instantiated and threaded with a `CarDatabase` rather
than reaching for one, which is why it is not in this roster: it is storage, not a domain controller.

**Fleet** - `VehicleController` (the roster, labels, identity and VIN writes; the largest),
`VehicleSpecController`, `BuildSheetController`, `DailyDriveLogController`, `MonthlyRecapController`,
`YearlyWrappedController`, `FleetSpendController` (the fleet-to-ledger seam), `DtcClearController`,
`GarageController`, `WeatherController`, `LocationController`, `PlaceController`,
`ReminderController`.

**Ledger** - `LedgerController`.

**Pantry and food** - `PantryController`, `GroceryController`, `MealController`.

**Body and goals** - `WorkoutController`, `SleepController`, `GoalController`.

**Checklists and notes** - `ChecklistController`, `TickHistoryController`, `NotesController`.

**Media** - `MusicController`, `SpotifyController`, `NowPlayingController`, `VolumeController`.

**Platform** - `LiveSessionController`, `TelephonyController`, `GlanceCardController`.

To recount rather than trust the roster above:

```
grep -rn "^object [A-Za-z]*Controller" app/src/main/java/
```

## Sync

**The live path is the engine.** `backend/engine/` holds the per-aspect Django backends and
`backend/engine/EnginePoll.kt` the poll that stands in for a realtime socket. Writes go through the
per-aspect outboxes in `backend/` and the durable `sync_outbox` table
(`data/local/SyncOutbox.kt`); reads come from Room. [[c2-containers]] has the write path step by
step.

**`sync/` is a different, older thing and it is not that path.** `sync/SyncEngine.kt` is the
Drive-`appDataFolder` cross-device engine written for the head-unit era; its own KDoc still talks
about "the head unit and phone" converging. `sync/DatabaseSnapshot.kt` is whole-database backup and
restore into the same folder. Two merge modes are documented in it, `Mode.UNION` on `syncId` and
`Mode.LWW` on a natural key, and [[0011-ledger-sync-union-and-lww]] records why the old
"sync must become append-only" blocker turned out to be false.

**Standing caveat, unchanged: `sync/` has never executed in LEGION.** Every claim about its behaviour
is traced from source; none is tested. It is not the sync path, and `appDataFolder` is not the store
- see the glossary entry, which used to say it was.

## Related

[[c1-context]] for the engine and the boundaries. [[c2-containers]] for the outbox write path and the
per-aspect transport switch. [[c3-ingestion]] for what still writes ledger and pantry rows.
