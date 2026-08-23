package com.kevin.legion.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented migration test for v32 -> v33 - one additive nullable column (goal-plans ticket
 * 09, "a ticked workout is one act, not two rows"): `workout_set_logs.sourceListItemId` (see
 * [WorkoutSetLog.sourceListItemId]). `createSql` confirmed against the generated
 * `app/schemas/com.kevin.legion.data.local.CarDatabase/33.json` after a real `compileDebugKotlin
 * -Pnokey` run, not hand-derived - same discipline [MIGRATION_31_32]/[MIGRATION_25_26] document.
 *
 * **NOT RUN this session** - `connectedAndroidTest` uninstalls the app and would take Kevin's real
 * data (CLAUDE.md L11, `memory/MEMORY.md`). Compiled only. On-device evidence (the database opening
 * after install, an existing `workout_set_logs` row surviving with the new column reading NULL) is
 * deferred to on-device QA.
 */
@RunWith(AndroidJUnit4::class)
class CarDatabaseMigration32To33Test {
    private val dbName = "migration-test-32-33"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CarDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `migrate32To33_isPurelyAdditive_existingRowSurvivesWithSourceListItemIdNull`() {
        helper.createDatabase(dbName, 32).apply {
            execSQL(
                "INSERT INTO workout_set_logs (exercise, sets, loggedAt, trustTier) " +
                    "VALUES ('Kettlebell swing', 3, 1000, 'REPORTED')"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 33, true, MIGRATION_32_33)

        val cursor = db.query(
            "SELECT exercise, sets, sourceListItemId FROM workout_set_logs WHERE exercise = 'Kettlebell swing'"
        )
        assertTrue(cursor.moveToFirst())
        assertEquals("Kettlebell swing", cursor.getString(0))
        assertEquals(3, cursor.getInt(1))
        assertNull(cursor.getString(2))
        cursor.close()
    }

    @Test
    fun `migrate32To33_sourceListItemIdRoundTripsARealValue`() {
        helper.createDatabase(dbName, 32).apply { close() }
        val db = helper.runMigrationsAndValidate(dbName, 33, true, MIGRATION_32_33)

        db.execSQL(
            "INSERT INTO workout_set_logs (exercise, sets, loggedAt, trustTier, sourceListItemId) " +
                "VALUES ('Squat', 5, 2000, 'REPORTED', 42)"
        )
        val cursor = db.query("SELECT sourceListItemId FROM workout_set_logs WHERE exercise = 'Squat'")
        assertTrue(cursor.moveToFirst())
        assertEquals(42, cursor.getInt(0))
        cursor.close()
    }
}
