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
 * Instrumented migration test for v14 -> v15 - `vehicle_capabilities` ([MIGRATION_14_15]).
 * Purely additive, one new table.
 *
 * **NOT RUN this session** - `connectedAndroidTest` wipes app data and Kevin's real database is on
 * the connected device (CLAUDE.md L11). Compiled only.
 */
@RunWith(AndroidJUnit4::class)
class CarDatabaseMigration14To15Test {
    private val dbName = "migration-test-14-15"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CarDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate14To15_createsTheCapabilityTableEmpty() {
        helper.createDatabase(dbName, 14).apply { close() }

        val db = helper.runMigrationsAndValidate(dbName, 15, true, MIGRATION_14_15)

        val cursor = db.query("SELECT COUNT(*) FROM vehicle_capabilities")
        assertTrue(cursor.moveToFirst())
        assertEquals(0, cursor.getInt(0))
        cursor.close()
    }

    @Test
    fun migrate14To15_storesOnePidPerVehicleAndDedupesOnConflict() {
        helper.createDatabase(dbName, 14).apply { close() }
        val db = helper.runMigrationsAndValidate(dbName, 15, true, MIGRATION_14_15)

        db.execSQL("INSERT INTO vehicle_capabilities (vehicleId, pid, detectedAt) VALUES ('AA:BB', 92, 1000)")
        db.execSQL("INSERT INTO vehicle_capabilities (vehicleId, pid, detectedAt) VALUES ('AA:BB', 12, 1000)")
        // Same car, same PID, later scan. The composite primary key must make this ONE row, not two,
        // or a re-scan doubles the profile every time the driver plugs in.
        db.execSQL("INSERT OR REPLACE INTO vehicle_capabilities (vehicleId, pid, detectedAt) VALUES ('AA:BB', 92, 2000)")

        val count = db.query("SELECT COUNT(*) FROM vehicle_capabilities WHERE vehicleId = 'AA:BB'")
        assertTrue(count.moveToFirst())
        assertEquals(2, count.getInt(0))
        count.close()

        val fresh = db.query("SELECT detectedAt FROM vehicle_capabilities WHERE vehicleId = 'AA:BB' AND pid = 92")
        assertTrue(fresh.moveToFirst())
        assertEquals(2000L, fresh.getLong(0))
        fresh.close()
    }

    @Test
    fun migrate14To15_keepsTwoVehiclesProfilesSeparate() {
        // The multi-car case, at the schema level: the same PID number on two cars is two rows, and
        // clearing one car's profile must not touch the other's.
        helper.createDatabase(dbName, 14).apply { close() }
        val db = helper.runMigrationsAndValidate(dbName, 15, true, MIGRATION_14_15)

        db.execSQL("INSERT INTO vehicle_capabilities (vehicleId, pid, detectedAt) VALUES ('F150', 92, 1)")
        db.execSQL("INSERT INTO vehicle_capabilities (vehicleId, pid, detectedAt) VALUES ('XJ', 5, 1)")
        db.execSQL("DELETE FROM vehicle_capabilities WHERE vehicleId = 'F150'")

        val remaining = db.query("SELECT vehicleId, pid FROM vehicle_capabilities")
        assertTrue(remaining.moveToFirst())
        assertEquals("XJ", remaining.getString(0))
        assertEquals(5, remaining.getInt(1))
        assertTrue("only the XJ row should remain", !remaining.moveToNext())
        remaining.close()
    }

    @Test
    fun migrate14To15_leavesGroceryAndNotesDataAlone() {
        helper.createDatabase(dbName, 14).apply {
            execSQL(
                "INSERT INTO grocery_items (text, done, sortOrder, createdAt, updatedAt, syncId) " +
                    "VALUES ('Milk', 0, 0, 1, 1, 'g-1')"
            )
            execSQL(
                "INSERT INTO item_lists (name, tickable, sortOrder, lastUsedAt, archived, createdAt, updatedAt, syncId, deleted) " +
                    "VALUES ('List', 1, 0, 1, 0, 1, 1, 'sync-list', 0)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 15, true, MIGRATION_14_15)

        val grocery = db.query("SELECT text FROM grocery_items WHERE syncId = 'g-1'")
        assertTrue(grocery.moveToFirst())
        assertEquals("Milk", grocery.getString(0))
        grocery.close()

        val lists = db.query("SELECT name FROM item_lists WHERE syncId = 'sync-list'")
        assertTrue(lists.moveToFirst())
        assertEquals("List", lists.getString(0))
        lists.close()
    }
}
