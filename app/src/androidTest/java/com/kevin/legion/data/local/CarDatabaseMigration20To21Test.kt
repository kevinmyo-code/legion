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
 * Instrumented migration test for v20 -> v21 - the `service_records.deleted` soft-delete tombstone
 * ticket 11 §2 asks for (`.scratch/fleet-maintenance/issues/11-service-history-cost-and-fleet-spend.md`,
 * resolved 2026-08-15). See [MIGRATION_20_21]'s own doc comment for the full story: one additive
 * `ALTER TABLE ... ADD COLUMN`, `createSql` confirmed against the generated
 * `app/schemas/com.kevin.legion.data.local.CarDatabase/21.json` after a kapt run rather than
 * assumed (`` `deleted` INTEGER NOT NULL DEFAULT 0 ``, byte for byte).
 *
 * **NOT RUN this session** - `connectedAndroidTest` wipes app data and Kevin's real database is on
 * the connected device (CLAUDE.md L11). Compiled only. Kevin verifies this migration against a
 * COPY of his real database himself before it goes anywhere near the phone (task instruction).
 */
@RunWith(AndroidJUnit4::class)
class CarDatabaseMigration20To21Test {
    private val dbName = "migration-test-20-21"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CarDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate20To21_existingRecordsGetDeletedFalse() {
        // Kevin's real shape post-v20: two rows, both cost-less (costCents NULL), neither ever
        // deleted - the migration must not disturb either fact.
        helper.createDatabase(dbName, 20).apply {
            execSQL(
                "INSERT INTO service_records (id, vehicleId, serviceName, mileage, date, costCents, syncId) " +
                    "VALUES (1, 'AA:BB', 'Oil Change', 227374, 1000, NULL, 's-1')"
            )
            execSQL(
                "INSERT INTO service_records (id, vehicleId, serviceName, mileage, date, costCents, syncId) " +
                    "VALUES (2, 'AA:BB', 'Tire Rotation', 220000, 2000, 4599, 's-2')"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 21, true, MIGRATION_20_21)

        val cursor = db.query("SELECT id, deleted, costCents FROM service_records ORDER BY id")
        assertTrue(cursor.moveToFirst())
        assertEquals(1, cursor.getInt(0))
        assertEquals("every pre-existing row must default to deleted = 0 - nothing on Kevin's phone was ever deleted", 0, cursor.getInt(1))
        assertTrue("costCents must ride along untouched (NULL stays NULL)", cursor.isNull(2))
        assertTrue(cursor.moveToNext())
        assertEquals(2, cursor.getInt(0))
        assertEquals(0, cursor.getInt(1))
        assertEquals("costCents must ride along untouched (a real value stays that value)", 4599, cursor.getInt(2))
        cursor.close()
    }

    @Test
    fun migrate20To21_isSafeToRunAgainstAnEmptyDatabase() {
        // The common case for any car other than Kevin's - no service_records rows at all yet.
        // Must not throw.
        helper.createDatabase(dbName, 20).apply { close() }

        val db = helper.runMigrationsAndValidate(dbName, 21, true, MIGRATION_20_21)

        val cursor = db.query("SELECT COUNT(*) FROM service_records")
        assertTrue(cursor.moveToFirst())
        assertEquals(0, cursor.getInt(0))
        cursor.close()
    }

    @Test
    fun migrate20To21_freshInsertAfterMigrationDefaultsDeletedToFalse() {
        // A row written through Room's own generated INSERT (no `deleted` column named explicitly,
        // matching @Insert(record: ServiceRecord) with the entity's Boolean default) must still land
        // as 0, not NULL/undefined - confirms the ADD COLUMN's own DEFAULT 0 is real at the SQLite
        // level, not just a Kotlin-side default that Room's generated SQL forgot to carry.
        helper.createDatabase(dbName, 20).apply { close() }
        val db = helper.runMigrationsAndValidate(dbName, 21, true, MIGRATION_20_21)

        db.execSQL(
            "INSERT INTO service_records (vehicleId, serviceName, mileage, date, syncId) " +
                "VALUES ('AA:BB', 'Brake Fluid', 100000, 3000, 's-3')"
        )
        val cursor = db.query("SELECT deleted FROM service_records WHERE syncId = 's-3'")
        assertTrue(cursor.moveToFirst())
        assertEquals(0, cursor.getInt(0))
        cursor.close()
    }
}
