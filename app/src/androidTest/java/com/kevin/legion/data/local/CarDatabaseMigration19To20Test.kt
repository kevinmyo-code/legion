package com.kevin.legion.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented migration test for v19 -> v20 - the fleet-maintenance map's schema
 * (`.scratch/fleet-maintenance/map.md`, "THE MIGRATION", tickets 06/07/11/14, all resolved
 * 2026-08-15). See [MIGRATION_19_20]'s own doc comment for the full story: two additive columns on
 * `maintenance_items` (`intervalSource`, `deleted`), one additive column on `vehicles` (`engine`),
 * and `service_records.cost REAL` -> `.costCents INTEGER` - the map's one stated exception to
 * CLAUDE.md §5's additive-only rule, justified by `cost` being provably empty (verified against a
 * copy of Kevin's real database before this migration was written: 0 of 2 rows had a non-null
 * `cost`).
 *
 * **NOT RUN this session** - `connectedAndroidTest` wipes app data and Kevin's real database is on
 * the connected device (CLAUDE.md L11). Compiled only. Kevin verifies this migration against a
 * COPY of his real database himself before it goes anywhere near the phone (task instruction).
 */
@RunWith(AndroidJUnit4::class)
class CarDatabaseMigration19To20Test {
    private val dbName = "migration-test-19-20"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CarDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate19To20_maintenanceItemsGetIntervalSourceSeededAndDeletedFalse() {
        // Kevin's real shape: 54 pre-existing rows, all LLM-seeded, none ever confirmed or deleted.
        helper.createDatabase(dbName, 19).apply {
            execSQL(
                "INSERT INTO maintenance_items (vehicleId, serviceName, intervalMiles, intervalMonths) " +
                    "VALUES ('AA:BB', 'Oil Change', 3000, NULL)"
            )
            // An orphan row with no interval at all - ticket 06's null-interval carve-out (no tag to
            // apply) still needs intervalSource to default correctly even with nothing to doubt.
            execSQL(
                "INSERT INTO maintenance_items (vehicleId, serviceName) VALUES ('AA:BB', 'Brake Fluid')"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 20, true, MIGRATION_19_20)

        val oil = db.query(
            "SELECT intervalSource, deleted FROM maintenance_items WHERE serviceName = 'Oil Change'"
        )
        assertTrue(oil.moveToFirst())
        assertEquals("every existing row must default to SEEDED - all 54 on Kevin's phone were LLM-produced",
            "SEEDED", oil.getString(0))
        assertEquals(0, oil.getInt(1))
        oil.close()

        val orphan = db.query(
            "SELECT intervalSource, deleted FROM maintenance_items WHERE serviceName = 'Brake Fluid'"
        )
        assertTrue(orphan.moveToFirst())
        assertEquals("SEEDED", orphan.getString(0))
        assertEquals(0, orphan.getInt(1))
        orphan.close()
    }

    @Test
    fun migrate19To20_vehiclesGetEmptyEngineDefault() {
        helper.createDatabase(dbName, 19).apply {
            execSQL(
                "INSERT INTO vehicles (obdMac, name, make, model, year, personaPrompt, " +
                    "odometerBaseline, odometerBaselineAt, tripMilesSinceBaseline, " +
                    "lastOdometerPromptAt, onboarded) " +
                    "VALUES ('AA:BB', '1998 Jeep Cherokee', 'Jeep', 'Cherokee', 1998, '', " +
                    "118483, 1000, 0.0, 0, 0)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 20, true, MIGRATION_19_20)

        val cursor = db.query("SELECT engine FROM vehicles WHERE obdMac = 'AA:BB'")
        assertTrue(cursor.moveToFirst())
        assertEquals("", cursor.getString(0))
        cursor.close()
    }

    @Test
    fun migrate19To20_serviceRecordsCostBecomesCostCentsAndNullStaysNull() {
        // Kevin's real shape: both existing records have cost = NULL (no writer ever existed).
        helper.createDatabase(dbName, 19).apply {
            execSQL(
                "INSERT INTO service_records (vehicleId, serviceName, mileage, date, cost, syncId) " +
                    "VALUES ('AA:BB', 'Oil Change', 227374, 1000, NULL, 's-1')"
            )
            execSQL(
                "INSERT INTO service_records (vehicleId, serviceName, mileage, date, cost, syncId) " +
                    "VALUES ('AA:BB', 'Tire Rotation', 220000, 2000, NULL, 's-2')"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 20, true, MIGRATION_19_20)

        // Row count and content survive the create/copy/drop/rename intact.
        val count = db.query("SELECT COUNT(*) FROM service_records")
        assertTrue(count.moveToFirst())
        assertEquals(2, count.getInt(0))
        count.close()

        val oil = db.query(
            "SELECT vehicleId, serviceName, mileage, date, costCents, syncId " +
                "FROM service_records WHERE syncId = 's-1'"
        )
        assertTrue(oil.moveToFirst())
        assertEquals("AA:BB", oil.getString(0))
        assertEquals("Oil Change", oil.getString(1))
        assertEquals(227374, oil.getInt(2))
        assertEquals(1000, oil.getLong(3))
        assertTrue("costCents must be NULL, never a cost * 100 conversion - there was nothing to convert",
            oil.isNull(4))
        assertEquals("s-1", oil.getString(5))
        oil.close()

        val tire = db.query(
            "SELECT costCents FROM service_records WHERE syncId = 's-2'"
        )
        assertTrue(tire.moveToFirst())
        assertTrue(tire.isNull(0))
        tire.close()
    }

    @Test
    fun migrate19To20_serviceRecordsPrimaryKeyIdsSurviveTheRebuild() {
        // The create/copy/drop/rename dance must preserve each row's own `id`, not renumber it -
        // ServiceRecordDao has no foreign-key dependents today, but a stray `id` shift would still be
        // silent data corruption, and this is cheap to prove directly.
        helper.createDatabase(dbName, 19).apply {
            execSQL(
                "INSERT INTO service_records (id, vehicleId, serviceName, mileage, date, cost, syncId) " +
                    "VALUES (7, 'AA:BB', 'Oil Change', 227374, 1000, NULL, 's-1')"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 20, true, MIGRATION_19_20)

        val cursor = db.query("SELECT id FROM service_records WHERE syncId = 's-1'")
        assertTrue(cursor.moveToFirst())
        assertEquals(7, cursor.getInt(0))
        cursor.close()

        // A fresh insert after the rebuild still autoincrements without colliding.
        db.execSQL(
            "INSERT INTO service_records (vehicleId, serviceName, mileage, date, cost, syncId) " +
                "VALUES ('AA:BB', 'Tire Rotation', 227500, 2000, NULL, 's-2')"
        )
        val fresh = db.query("SELECT id FROM service_records WHERE syncId = 's-2'")
        assertTrue(fresh.moveToFirst())
        assertTrue("a fresh row must get a new, non-colliding id", fresh.getInt(0) > 7)
        fresh.close()
    }

    @Test
    fun migrate19To20_isSafeToRunAgainstAnEmptyDatabase() {
        // The common case for any car other than Kevin's - no maintenance_items, no vehicles, no
        // service_records rows at all yet. Must not throw.
        helper.createDatabase(dbName, 19).apply { close() }

        val db = helper.runMigrationsAndValidate(dbName, 20, true, MIGRATION_19_20)

        val count = db.query("SELECT COUNT(*) FROM service_records")
        assertTrue(count.moveToFirst())
        assertEquals(0, count.getInt(0))
        count.close()
    }
}
