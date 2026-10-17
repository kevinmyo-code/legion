package com.kevin.midnightai.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Real, additive Room migrations. Each SQL string is copied byte-identical from
 * the generated schema JSON in app/schemas/ (Room emits one per version because
 * exportSchema = true - see CarDatabase's KDoc) so it matches exactly what Room
 * itself would have created for a fresh install at that version. Never
 * hand-tweak these strings; regenerate by bumping the version, building once,
 * and copying the new createSql from the emitted JSON.
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `library_track` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`filePath` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `album` TEXT NOT NULL, " +
                "`durationMs` INTEGER NOT NULL, `sourceName` TEXT NOT NULL, `addedAt` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `mixtape` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `coverImagePath` TEXT, `coverPrompt` TEXT, `createdAt` INTEGER NOT NULL, " +
                "`sortOrder` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `mixtape_track` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`mixtapeId` INTEGER NOT NULL, `libraryTrackId` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL, " +
                "`addedAt` INTEGER NOT NULL)"
        )
    }
}

/**
 * v6 -> v7: add `updatedAt` (epoch ms, DEFAULT 0) to every mutable table so the
 * cross-device BYO-cloud sync (S1) can resolve conflicts last-write-wins. Each
 * ADD COLUMN's `DEFAULT 0` must match the entity's `@ColumnInfo(defaultValue =
 * "0")` exactly, or MigrationTest's schema validation fails. Append-only tables
 * (obd_samples, music_plays, code_events, service_records, build_entries,
 * memories, oil_analyses, tagged_places, foresight_notes, and the recap tables)
 * are merged by union at sync time and need no such column.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        for (table in listOf(
            "vehicles", "car_tasks", "maintenance_items", "vehicle_specs",
            "place_reminders", "chassis_quirks", "mixtape", "mixtape_track",
            "library_track",
        )) {
            db.execSQL("ALTER TABLE `$table` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
        }
    }
}

/**
 * v7 -> v8: add `syncId` (TEXT, DEFAULT '') to the user-generated tables that
 * lack a portable natural key, so the cross-device BYO-cloud sync (S1) can
 * recognise the same logical row on both devices (the local autoincrement `id`
 * is per-device and can't). A UUID is assigned at row creation going forward;
 * SyncEngine (retired 2026-10-17) backfilled the blank legacy rows before the first export. Each
 * ADD COLUMN's `DEFAULT ''` must match the entity's `@ColumnInfo(defaultValue =
 * "''")` exactly, or MigrationTest's schema validation fails. Tables with a
 * stable natural key (vehicles/obdMac, places/label, obd_samples' composite,
 * etc.) are keyed on that instead and get no syncId.
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        for (table in listOf(
            "memories", "service_records", "build_entries", "code_events",
            "oil_analyses", "car_tasks", "place_reminders",
        )) {
            db.execSQL("ALTER TABLE `$table` ADD COLUMN `syncId` TEXT NOT NULL DEFAULT ''")
        }
    }
}

/**
 * v8 -> v9: add `deleted` (INTEGER/Boolean, DEFAULT 0) to the two actively-
 * deletable LWW tables (B19). Room maps Boolean to INTEGER 0/1. Each ADD
 * COLUMN's `DEFAULT 0` must match the entity's `@ColumnInfo(defaultValue =
 * "0")` exactly, or MigrationTest's schema validation fails. Every other LWW
 * table (vehicles, maintenance_items, vehicle_specs, chassis_quirks,
 * place_reminders) has no user-facing delete today, so no tombstone column
 * is needed there yet - see LiveToolbox for the two dispatched delete paths
 * (remove_car_task, forget_place) this unblocks.
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `car_tasks` ADD COLUMN `deleted` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `places` ADD COLUMN `deleted` INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * v9 -> v10 (car manager, 2026-07-16). Additive only, per CLAUDE.md §5/§9.
 *
 *  - `vehicles.archived`: hide a car from the CARS roster without destroying its
 *    history. Rides the existing LWW `updatedAt` path, so it propagates for free.
 *  - `drive_reassignments`: "this drive belongs to a different car" as a RULE
 *    rather than a mutation. See [DriveReassignment]'s doc for why re-keying
 *    obd_samples directly would clone a drive onto both cars instead of moving it.
 *
 * SQL copied verbatim from the generated `app/schemas/.../10.json`.
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `vehicles` ADD COLUMN `archived` INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `drive_reassignments` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`syncId` TEXT NOT NULL DEFAULT '', " +
                "`vehicleId` TEXT NOT NULL, " +
                "`fromMs` INTEGER NOT NULL, " +
                "`toMs` INTEGER NOT NULL, " +
                "`newVehicleId` TEXT NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL DEFAULT 0)"
        )
    }
}

/**
 * v10 -> v11 (companion-memory map, ticket 01, 2026-07-22). Two brand-new,
 * additive tables - no ALTER on any existing table, so no legacy-default
 * concerns.
 *
 *  - `episodic_turns`: raw Live-conversation transcript buffer (ticket 02
 *    consolidates and clears it). No `syncId` - transient by design, never
 *    meant to cross devices.
 *  - `companion_memories`: durable, sec-9.1-categorized, distilled memories.
 *    Separate from the older `memories` table (untouched, still backs the
 *    explicit "remember X" tool).
 *
 * SQL copied verbatim from the generated `app/schemas/.../11.json`.
 */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `episodic_turns` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`sessionId` TEXT NOT NULL, " +
                "`vehicleId` TEXT NOT NULL, " +
                "`role` TEXT NOT NULL, " +
                "`text` TEXT NOT NULL, " +
                "`timestamp` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `companion_memories` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`vehicleId` TEXT NOT NULL, " +
                "`text` TEXT NOT NULL, " +
                "`category` TEXT NOT NULL, " +
                "`source` TEXT NOT NULL, " +
                "`importance` INTEGER NOT NULL DEFAULT 5, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`lastAccessedAt` INTEGER NOT NULL DEFAULT 0, " +
                "`embeddingVector` TEXT, " +
                "`embeddingModel` TEXT, " +
                "`syncId` TEXT NOT NULL DEFAULT '')"
        )
    }
}

/**
 * v11 -> v12 (maintenance-schedule Part 1, 2026-07-29). Single additive column
 * on the existing `maintenance_items` table.
 *
 *  - `neverDone`: a driver-confirmed "I've never done this" fact, distinct
 *    from an unknown anchor (both lastDoneMileage/lastDoneDate null). See
 *    [MaintenanceItem]'s doc comment for why the two must not be conflated -
 *    conflating them made a freshly-seeded schedule read as 100% overdue.
 *
 * SQL copied verbatim from the generated `app/schemas/.../12.json`.
 */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `maintenance_items` ADD COLUMN `neverDone` INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * v12 -> v13 (fleet upload, 2026-10-17). One brand-new, additive table; no ALTER on anything
 * existing. `drives` holds one row per finished drive with its recorded start (see [Drive]).
 * It starts EMPTY: drives before this version are not reconstructed from marker samples.
 *
 * SQL copied verbatim from the generated `app/schemas/.../13.json`.
 */
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `drives` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`vehicleId` TEXT NOT NULL, " +
                "`startedAt` INTEGER NOT NULL, " +
                "`endedAt` INTEGER NOT NULL, " +
                "`miles` REAL NOT NULL, " +
                "`gallons` REAL, " +
                "`endReason` TEXT NOT NULL, " +
                "`syncId` TEXT NOT NULL)"
        )
    }
}
