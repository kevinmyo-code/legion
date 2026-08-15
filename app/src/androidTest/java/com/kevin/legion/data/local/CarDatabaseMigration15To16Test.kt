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
 * Instrumented migration test for v15 -> v16 - `goals` + `advisor_advice` ([MIGRATION_15_16]).
 * Purely additive, two new tables.
 *
 * **NOT RUN this session** - `connectedAndroidTest` wipes app data and Kevin's real database is on
 * the connected device (CLAUDE.md L11). Compiled only.
 */
@RunWith(AndroidJUnit4::class)
class CarDatabaseMigration15To16Test {
    private val dbName = "migration-test-15-16"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CarDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate15To16_createsBothTablesEmpty() {
        helper.createDatabase(dbName, 15).apply { close() }

        val db = helper.runMigrationsAndValidate(dbName, 16, true, MIGRATION_15_16)

        val goals = db.query("SELECT COUNT(*) FROM goals")
        assertTrue(goals.moveToFirst())
        assertEquals(0, goals.getInt(0))
        goals.close()

        val advice = db.query("SELECT COUNT(*) FROM advisor_advice")
        assertTrue(advice.moveToFirst())
        assertEquals(0, advice.getInt(0))
        advice.close()
    }

    @Test
    fun migrate15To16_goalsAcceptsAProseOnlyRowWithEveryNumberNull() {
        // The answer's call 2: prose-only ("ship the deck") with targetValue/unit/metricKey/
        // deadlineEpoch all null must be a VALID row, not something the schema rejects.
        helper.createDatabase(dbName, 15).apply { close() }
        val db = helper.runMigrationsAndValidate(dbName, 16, true, MIGRATION_15_16)

        db.execSQL(
            "INSERT INTO goals (lineageId, aspect, statement, status, createdAt, updatedAt, syncId) " +
                "VALUES (1, 'cred', 'ship the deck', 'active', 1000, 1000, 'g-1')"
        )

        val cursor = db.query(
            "SELECT statement, targetValue, unit, metricKey, deadlineEpoch FROM goals WHERE lineageId = 1"
        )
        assertTrue(cursor.moveToFirst())
        assertEquals("ship the deck", cursor.getString(0))
        assertTrue("targetValue must be nullable", cursor.isNull(1))
        assertTrue("unit must be nullable", cursor.isNull(2))
        assertTrue("metricKey must be nullable", cursor.isNull(3))
        assertTrue("deadlineEpoch must be nullable", cursor.isNull(4))
        cursor.close()
    }

    @Test
    fun migrate15To16_goalsRevisionTrailSharesLineageAndChainsSupersedesId() {
        // Answer call 4: a material change inserts a NEW row sharing lineageId, chained via
        // supersedesId, with nothing deleted or overwritten.
        helper.createDatabase(dbName, 15).apply { close() }
        val db = helper.runMigrationsAndValidate(dbName, 16, true, MIGRATION_15_16)

        db.execSQL(
            "INSERT INTO goals (lineageId, aspect, statement, targetValue, unit, status, createdAt, updatedAt, syncId) " +
                "VALUES (7, 'bio', 'get to 180 lbs', 180.0, 'lbs', 'active', 1000, 1000, 'g-a')"
        )
        db.execSQL(
            "INSERT INTO goals (lineageId, aspect, statement, targetValue, unit, status, supersedesId, createdAt, updatedAt, syncId) " +
                "VALUES (7, 'bio', 'get to 175 lbs', 175.0, 'lbs', 'active', 1, 2000, 2000, 'g-b')"
        )

        val count = db.query("SELECT COUNT(*) FROM goals WHERE lineageId = 7")
        assertTrue(count.moveToFirst())
        assertEquals("both revisions must survive, nothing overwritten", 2, count.getInt(0))
        count.close()

        val latest = db.query("SELECT statement, supersedesId FROM goals WHERE lineageId = 7 AND id = 2")
        assertTrue(latest.moveToFirst())
        assertEquals("get to 175 lbs", latest.getString(0))
        assertEquals(1, latest.getInt(1))
        latest.close()
    }

    @Test
    fun migrate15To16_advisorAdviceAcceptsANullProposalAndTracksOutcome() {
        helper.createDatabase(dbName, 15).apply { close() }
        val db = helper.runMigrationsAndValidate(dbName, 16, true, MIGRATION_15_16)

        db.execSQL(
            "INSERT INTO advisor_advice (aspect, questionText, gist, adviceText, outcome, createdAt, syncId) " +
                "VALUES ('log', 'how am I doing', 'on track', 'You are on track.', 'pending', 1000, 'a-1')"
        )

        val cursor = db.query("SELECT proposalJson, outcome, resolvedAt FROM advisor_advice WHERE syncId = 'a-1'")
        assertTrue(cursor.moveToFirst())
        assertTrue("proposalJson must be nullable for purely conversational advice", cursor.isNull(0))
        assertEquals("pending", cursor.getString(1))
        assertTrue("resolvedAt must be nullable while pending", cursor.isNull(2))
        cursor.close()
    }

    @Test
    fun migrate15To16_leavesVehicleCapabilitiesAlone() {
        helper.createDatabase(dbName, 15).apply {
            execSQL(
                "INSERT INTO vehicle_capabilities (vehicleId, pid, detectedAt) VALUES ('AA:BB', 92, 1000)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 16, true, MIGRATION_15_16)

        val cursor = db.query("SELECT pid FROM vehicle_capabilities WHERE vehicleId = 'AA:BB'")
        assertTrue(cursor.moveToFirst())
        assertEquals(92, cursor.getInt(0))
        cursor.close()
    }
}
