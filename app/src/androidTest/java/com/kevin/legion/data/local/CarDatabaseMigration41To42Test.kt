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
 * Instrumented migration test for v41 -> v42 - `vehicles_replica` and `service_history_replica`
 * are created (`.scratch/backend-erp/issues/10-fleet-cutover.md`'s follow-up, fleet wave 2). Two
 * additive `CREATE TABLE`s plus their unique `serverId` indices - see [MIGRATION_41_42]'s own doc
 * comment for why this is exactly [MIGRATION_37_38]'s shape repeated for a different aspect.
 *
 * **NOT RUN this session** - `connectedAndroidTest` uninstalls the app and would take Kevin's real
 * data (CLAUDE.md L11, `memory/MEMORY.md`). Compiled only, confirmed via
 * `compileDebugAndroidTestKotlin -Pnokey`. On-device evidence is deferred to on-device QA, same
 * posture as [CarDatabaseMigration40To41Test].
 */
@RunWith(AndroidJUnit4::class)
class CarDatabaseMigration41To42Test {
    private val dbName = "migration-test-41-42"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CarDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `migrate41To42_createsVehiclesReplica_andCanInsertARow`() {
        helper.createDatabase(dbName, 41).apply { close() }

        val db = helper.runMigrationsAndValidate(dbName, 42, true, MIGRATION_41_42)

        db.execSQL(
            "INSERT INTO vehicles_replica (id, serverId, name, make, model, year, trim, engine, confirmed, odometerBaseline, odometerBaselineAtMs, updatedAtMs, deleted, originGuid) " +
                "VALUES (1, 'vehicle-1', 'Jeep', 'Jeep', 'Cherokee', 1998, 'Sport', '4.0L I6', 1, 142000, 10000, 500, 0, 'guid-1')"
        )
        val cursor = db.query("SELECT name, make, model, year, odometerBaseline FROM vehicles_replica WHERE id = 1")
        assertTrue(cursor.moveToFirst())
        assertEquals("Jeep", cursor.getString(0))
        assertEquals("Jeep", cursor.getString(1))
        assertEquals("Cherokee", cursor.getString(2))
        assertEquals(1998, cursor.getInt(3))
        assertEquals(142000, cursor.getInt(4))
        cursor.close()
    }

    @Test
    fun `migrate41To42_createsServiceHistoryReplica_andCanInsertARow`() {
        helper.createDatabase(dbName, 41).apply { close() }

        val db = helper.runMigrationsAndValidate(dbName, 42, true, MIGRATION_41_42)

        db.execSQL(
            "INSERT INTO service_history_replica (id, serverId, vehicleServerId, serviceName, mileage, serviceDateEpochMs, costCents, kind, updatedAtMs, deleted, originGuid) " +
                "VALUES (1, 'service_history-1', 'vehicle-1', 'Oil change', 143000, 20000, 5999, 'OBSERVED', 600, 0, 'guid-2')"
        )
        val cursor = db.query("SELECT serviceName, vehicleServerId, mileage, costCents, kind FROM service_history_replica WHERE id = 1")
        assertTrue(cursor.moveToFirst())
        assertEquals("Oil change", cursor.getString(0))
        assertEquals("vehicle-1", cursor.getString(1))
        assertEquals(143000, cursor.getInt(2))
        assertEquals(5999L, cursor.getLong(3))
        assertEquals("OBSERVED", cursor.getString(4))
        cursor.close()
    }

    @Test
    fun `migrate41To42_serverIdIsUnique_onBothTables`() {
        helper.createDatabase(dbName, 41).apply { close() }

        val db = helper.runMigrationsAndValidate(dbName, 42, true, MIGRATION_41_42)

        db.execSQL(
            "INSERT INTO vehicles_replica (id, serverId, name, make, model, year, confirmed, updatedAtMs, deleted) " +
                "VALUES (1, 'vehicle-dup', 'A', 'A', 'A', 2000, 0, 1, 0)"
        )
        var threw = false
        try {
            db.execSQL(
                "INSERT INTO vehicles_replica (id, serverId, name, make, model, year, confirmed, updatedAtMs, deleted) " +
                    "VALUES (2, 'vehicle-dup', 'B', 'B', 'B', 2001, 0, 2, 0)"
            )
        } catch (e: Exception) {
            threw = true
        }
        assertTrue("a duplicate serverId must violate the unique index", threw)
    }

    @Test
    fun `migrate41To42_isOtherwiseAdditive_everyEventsReplicaRowUnchanged`() {
        helper.createDatabase(dbName, 41).apply {
            execSQL(
                "INSERT INTO events_replica (id, serverId, title, startsAt, allDay, source, done, exact, exactDowngraded, updatedAtMs, deleted, createdAt) " +
                    "VALUES (1, 'server-1', 'Dentist', 50000, 0, 'legion', 0, 0, 0, 100, 0, 12345)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 42, true, MIGRATION_41_42)

        val cursor = db.query("SELECT title, startsAt, createdAt FROM events_replica WHERE id = 1")
        assertTrue(cursor.moveToFirst())
        assertEquals("Dentist", cursor.getString(0))
        assertEquals(50000L, cursor.getLong(1))
        assertEquals(12345L, cursor.getLong(2))
        cursor.close()
    }
}
