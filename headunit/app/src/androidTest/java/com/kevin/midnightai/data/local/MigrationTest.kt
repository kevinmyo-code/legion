package com.kevin.midnightai.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves MIGRATION_5_6 preserves existing rows instead of relying on Room's
 * destructive fallback - the whole point of moving off
 * fallbackToDestructiveMigration (see CarDatabase's KDoc). Runs against the
 * exported schema JSONs bundled from app/schemas/ (see build.gradle.kts
 * androidTest asset sourceSet).
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CarDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate5To6_preservesExistingRows() {
        val dbName = "migration-test"

        // Create a v5 database and seed one row in a pre-existing table so we
        // can prove it survives the migration (not just that it doesn't crash).
        helper.createDatabase(dbName, 5).apply {
            execSQL(
                "INSERT INTO music_plays (vehicleId, title, artist, album, durationMs, " +
                    "listenedMs, completed, timestamp) VALUES " +
                    "('test-car', 'Plastic Love', 'Mariya Takeuchi', 'Variety', 240000, 230000, 1, 1000)"
            )
            close()
        }

        // Migrate to v6 and validate the new schema.
        val migrated = helper.runMigrationsAndValidate(dbName, 6, true, MIGRATION_5_6)

        val cursor = migrated.query("SELECT title FROM music_plays WHERE vehicleId = 'test-car'")
        cursor.use {
            assert(it.moveToFirst()) { "Pre-migration row missing after 5->6" }
            assert(it.getString(0) == "Plastic Love")
        }

        // The three new tables must exist and be empty (fresh migration, no data loss elsewhere).
        for (table in listOf("library_track", "mixtape", "mixtape_track")) {
            migrated.query("SELECT COUNT(*) FROM $table").use {
                assert(it.moveToFirst())
                assert(it.getInt(0) == 0)
            }
        }
    }

    @Test
    fun migrate6To7_preservesRowsAndAddsUpdatedAt() {
        val dbName = "migration-test-6-7"

        // Seed a row in a mutable table at v6 (before `updatedAt` exists).
        helper.createDatabase(dbName, 6).apply {
            execSQL(
                "INSERT INTO car_tasks (text, category, done, createdAt) " +
                    "VALUES ('change the oil', 'maintenance', 0, 1000)"
            )
            close()
        }

        // Migrate to v7 and validate the new schema (the ADD COLUMN default must
        // match each entity's @ColumnInfo(defaultValue = "0") or this throws).
        val migrated = helper.runMigrationsAndValidate(dbName, 7, true, MIGRATION_6_7)

        // The pre-existing row survives, and its new updatedAt defaults to 0.
        migrated.query("SELECT text, updatedAt FROM car_tasks WHERE category = 'maintenance'").use {
            assert(it.moveToFirst()) { "Pre-migration row missing after 6->7" }
            assert(it.getString(0) == "change the oil")
            assert(it.getLong(1) == 0L) { "updatedAt should default to 0 for migrated rows" }
        }

        // Every mutable table must now carry the column (a bad ALTER would throw here).
        for (table in listOf(
            "vehicles", "car_tasks", "maintenance_items", "vehicle_specs",
            "place_reminders", "chassis_quirks", "mixtape", "mixtape_track", "library_track",
        )) {
            migrated.query("SELECT updatedAt FROM $table LIMIT 0").use {
                assert(it.columnCount == 1) { "updatedAt missing on $table after 6->7" }
            }
        }
    }

    @Test
    fun migrate7To8_preservesRowsAndAddsSyncId() {
        val dbName = "migration-test-7-8"

        // Seed a row in a user-content table at v7 (before `syncId` exists).
        helper.createDatabase(dbName, 7).apply {
            execSQL("INSERT INTO memories (text, timestamp) VALUES ('first road trip to the coast', 1000)")
            close()
        }

        // Migrate to v8 and validate (the ADD COLUMN default must match each
        // entity's @ColumnInfo(defaultValue = "''") or this throws).
        val migrated = helper.runMigrationsAndValidate(dbName, 8, true, MIGRATION_7_8)

        // The pre-existing row survives; its new syncId defaults to '' (backfilled later at export).
        migrated.query("SELECT text, syncId FROM memories WHERE timestamp = 1000").use {
            assert(it.moveToFirst()) { "Pre-migration row missing after 7->8" }
            assert(it.getString(0) == "first road trip to the coast")
            assert(it.getString(1) == "") { "syncId should default to '' for migrated rows" }
        }

        // Every syncId table must now carry the column.
        for (table in listOf(
            "memories", "service_records", "build_entries", "code_events",
            "oil_analyses", "car_tasks", "place_reminders",
        )) {
            migrated.query("SELECT syncId FROM $table LIMIT 0").use {
                assert(it.columnCount == 1) { "syncId missing on $table after 7->8" }
            }
        }
    }

    @Test
    fun migrate8To9_preservesRowsAndAddsDeletedTombstone() {
        val dbName = "migration-test-8-9"

        // Seed one row in each B19-scoped table at v8 (before `deleted` exists).
        helper.createDatabase(dbName, 8).apply {
            execSQL(
                "INSERT INTO car_tasks (text, category, done, createdAt, updatedAt, syncId) " +
                    "VALUES ('change the oil', 'maintenance', 0, 1000, 1000, 'task-1')"
            )
            execSQL(
                "INSERT INTO places (label, latitude, longitude, timestamp) " +
                    "VALUES ('home', 34.05, -118.25, 1000)"
            )
            close()
        }

        // Migrate to v9 and validate (the ADD COLUMN default must match each
        // entity's @ColumnInfo(defaultValue = "0") or this throws).
        val migrated = helper.runMigrationsAndValidate(dbName, 9, true, MIGRATION_8_9)

        // Both pre-existing rows survive, and their new `deleted` defaults to 0
        // (untombstoned) - a migrated row must not vanish from active reads.
        migrated.query("SELECT text, deleted FROM car_tasks WHERE syncId = 'task-1'").use {
            assert(it.moveToFirst()) { "Pre-migration row missing after 8->9" }
            assert(it.getString(0) == "change the oil")
            assert(it.getInt(1) == 0) { "deleted should default to 0 for migrated car_tasks rows" }
        }
        migrated.query("SELECT label, deleted FROM places WHERE label = 'home'").use {
            assert(it.moveToFirst()) { "Pre-migration row missing after 8->9" }
            assert(it.getInt(1) == 0) { "deleted should default to 0 for migrated places rows" }
        }

        for (table in listOf("car_tasks", "places")) {
            migrated.query("SELECT deleted FROM $table LIMIT 0").use {
                assert(it.columnCount == 1) { "deleted missing on $table after 8->9" }
            }
        }
    }

    @Test
    fun migrate9To10_preservesVehicleAndAddsArchivedAndReassignments() {
        val dbName = "migration-test-9-10"

        // Seed a vehicle at v9 with a real odometer baseline. This is the row the
        // whole car manager hangs off, and odometerBaseline is what LWW would
        // clobber if identity were ever wrong - so prove the migration keeps it.
        helper.createDatabase(dbName, 9).apply {
            execSQL(
                "INSERT INTO vehicles (obdMac, name, make, model, year, personaPrompt, " +
                    "odometerBaseline, odometerBaselineAt, tripMilesSinceBaseline, " +
                    "lastOdometerPromptAt, onboarded, voiceName, personaTraits, trim, " +
                    "confirmed, updatedAt) " +
                    "VALUES ('AA:BB:CC:DD:EE:FF', 'Cherokee', 'Jeep', 'Cherokee', 1998, '', " +
                    "204000, 1000, 12.5, 0, 1, 'Sulafat', '{}', 'Sport', 1, 1000)"
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(dbName, 10, true, MIGRATION_9_10)

        // The car survives with its odometer intact, and archived defaults to 0 -
        // a migrated car must not vanish from the roster.
        migrated.query(
            "SELECT name, odometerBaseline, archived FROM vehicles WHERE obdMac = 'AA:BB:CC:DD:EE:FF'"
        ).use {
            assert(it.moveToFirst()) { "Pre-migration vehicle missing after 9->10" }
            assert(it.getString(0) == "Cherokee")
            assert(it.getInt(1) == 204000) { "odometerBaseline must survive 9->10" }
            assert(it.getInt(2) == 0) { "archived should default to 0 for migrated vehicles" }
        }

        // The reassignment table exists and is writable at v10.
        migrated.execSQL(
            "INSERT INTO drive_reassignments (syncId, vehicleId, fromMs, toMs, newVehicleId, updatedAt) " +
                "VALUES ('r-1', 'AA:BB:CC:DD:EE:FF', 100, 200, 'car:abc', 5000)"
        )
        migrated.query("SELECT newVehicleId FROM drive_reassignments WHERE syncId = 'r-1'").use {
            assert(it.moveToFirst()) { "drive_reassignments not writable after 9->10" }
            assert(it.getString(0) == "car:abc")
        }
    }

    @Test
    fun migrate10To11_addsEpisodicTurnsAndCompanionMemories() {
        val dbName = "migration-test-10-11"

        // v10 has no pre-existing rows relevant to these two brand-new tables -
        // nothing to preserve, so this proves creation + writability + defaults.
        helper.createDatabase(dbName, 10).close()

        val migrated = helper.runMigrationsAndValidate(dbName, 11, true, MIGRATION_10_11)

        migrated.execSQL(
            "INSERT INTO episodic_turns (sessionId, vehicleId, role, text, timestamp) " +
                "VALUES ('sess-1', 'car:abc', 'driver', 'thinking about the LS swap', 1000)"
        )
        migrated.query("SELECT role, text FROM episodic_turns WHERE sessionId = 'sess-1'").use {
            assert(it.moveToFirst()) { "episodic_turns not writable after 10->11" }
            assert(it.getString(0) == "driver")
            assert(it.getString(1) == "thinking about the LS swap")
        }

        // importance/lastAccessedAt/syncId defaults must match the entity's
        // @ColumnInfo(defaultValue = ...) exactly, or Room's schema validation
        // (the `true` above) fails on a real device even though this insert
        // succeeds - the whole point of runMigrationsAndValidate.
        migrated.execSQL(
            "INSERT INTO companion_memories (vehicleId, text, category, source, createdAt) " +
                "VALUES ('car:abc', 'wants an LS swap eventually', 'driver', 'consolidated', 2000)"
        )
        migrated.query(
            "SELECT importance, lastAccessedAt, syncId FROM companion_memories WHERE vehicleId = 'car:abc'"
        ).use {
            assert(it.moveToFirst()) { "companion_memories not writable after 10->11" }
            assert(it.getInt(0) == 5) { "importance should default to 5" }
            assert(it.getInt(1) == 0) { "lastAccessedAt should default to 0" }
            assert(it.getString(2) == "") { "syncId should default to '' for a raw insert" }
        }
    }

    @Test
    fun migrate11To12_addsNeverDone() {
        val dbName = "migration-test-11-12"

        // Seed a pre-v12 maintenance_items row (before `neverDone` exists).
        helper.createDatabase(dbName, 11).apply {
            execSQL(
                "INSERT INTO maintenance_items (vehicleId, serviceName, intervalMiles, intervalMonths, " +
                    "lastDoneMileage, lastDoneDate, updatedAt) " +
                    "VALUES ('AA:BB:CC:DD:EE:FF', 'Oil Change', 5000, 6, 40000, 1000, 1000)"
            )
            close()
        }

        // Migrate to v12 and validate (the ADD COLUMN default must match the
        // entity's @ColumnInfo(defaultValue = "0") or this throws).
        val migrated = helper.runMigrationsAndValidate(dbName, 12, true, MIGRATION_11_12)

        // The pre-existing row survives, and its new neverDone defaults to 0
        // (not confirmed never-done) - a migrated row must not read as due
        // just because the column appeared.
        migrated.query(
            "SELECT serviceName, lastDoneMileage, neverDone FROM maintenance_items WHERE vehicleId = 'AA:BB:CC:DD:EE:FF'"
        ).use {
            assert(it.moveToFirst()) { "Pre-migration maintenance_items row missing after 11->12" }
            assert(it.getString(0) == "Oil Change")
            assert(it.getInt(1) == 40000) { "lastDoneMileage must survive 11->12" }
            assert(it.getInt(2) == 0) { "neverDone should default to 0 for migrated rows" }
        }
    }
}
