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
 * Instrumented migration test for v46 -> v47 - `service_records` gains `kind`/`updatedAt` and
 * widens `mileage`/`date` to nullable (engine retirement step 3,
 * `.scratch/backend-erp/issues/16-fleet-service-history-is-not-a-configured-split.md`, ticket 15's
 * "RULED... option 1"). Not additive - same create/copy/drop/rename shape [MIGRATION_19_20] already
 * used for this exact table - see [MIGRATION_46_47]'s own doc comment for why the widen is real,
 * not cosmetic, and why `updatedAt` backfills to each row's own `date` rather than to `0`.
 *
 * **NOT RUN this session** - `connectedAndroidTest` uninstalls the app and would take Kevin's real
 * data (CLAUDE.md L11, `memory/MEMORY.md`). Compiled only, confirmed via
 * `compileDebugAndroidTestKotlin -Pnokey`. On-device evidence is deferred to on-device QA, same
 * posture as [CarDatabaseMigration43To44Test].
 */
@RunWith(AndroidJUnit4::class)
class CarDatabaseMigration46To47Test {
    private val dbName = "migration-test-46-47"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CarDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `migrate46To47_existingRowBackfillsKindObservedAndUpdatedAtFromDate`() {
        helper.createDatabase(dbName, 46).apply {
            execSQL(
                "INSERT INTO service_records (id, vehicleId, serviceName, mileage, date, costCents, syncId, deleted) " +
                    "VALUES (1, 'V1', 'Oil Change', 227483, 1723000000000, 4599, 'guid-1', 0)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 47, true, MIGRATION_46_47)

        val cursor = db.query("SELECT kind, updatedAt, mileage, date, costCents FROM service_records WHERE id = 1")
        assertTrue(cursor.moveToFirst())
        assertEquals("OBSERVED", cursor.getString(0))
        assertEquals(1723000000000L, cursor.getLong(1))
        assertEquals(227483L, cursor.getLong(2))
        assertEquals(1723000000000L, cursor.getLong(3))
        assertEquals(4599L, cursor.getLong(4))
        cursor.close()
    }

    @Test
    fun `migrate46To47_canInsertAnAssertedRowWithOnlyOneAxis`() {
        helper.createDatabase(dbName, 46).apply { close() }

        val db = helper.runMigrationsAndValidate(dbName, 47, true, MIGRATION_46_47)

        // A dateless anchor - "did the oil change around 227,483 mi, not sure when" - the exact
        // shape this widen exists to allow (see MIGRATION_46_47's own doc).
        db.execSQL(
            "INSERT INTO service_records (id, vehicleId, serviceName, mileage, date, costCents, syncId, deleted, kind, updatedAt) " +
                "VALUES (2, 'V1', 'Oil Change', 227483, NULL, NULL, 'anchor-guid', 0, 'ASSERTED', 1723100000000)"
        )
        val cursor = db.query("SELECT mileage, date, kind FROM service_records WHERE id = 2")
        assertTrue(cursor.moveToFirst())
        assertEquals(227483L, cursor.getLong(0))
        assertTrue("a null date column must accept a genuinely dateless anchor", cursor.isNull(1))
        assertEquals("ASSERTED", cursor.getString(2))
        cursor.close()
    }

    @Test
    fun `migrate46To47_isOtherwiseAdditive_everyOtherColumnUnchanged`() {
        helper.createDatabase(dbName, 46).apply {
            execSQL(
                "INSERT INTO service_records (id, vehicleId, serviceName, mileage, date, costCents, syncId, deleted) " +
                    "VALUES (1, 'V1', 'Tire Rotation', 200000, 1650000000000, NULL, 'guid-2', 0)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 47, true, MIGRATION_46_47)

        val cursor = db.query("SELECT vehicleId, serviceName, syncId, deleted FROM service_records WHERE id = 1")
        assertTrue(cursor.moveToFirst())
        assertEquals("V1", cursor.getString(0))
        assertEquals("Tire Rotation", cursor.getString(1))
        assertEquals("guid-2", cursor.getString(2))
        assertEquals(0, cursor.getInt(3))
        cursor.close()
    }
}
