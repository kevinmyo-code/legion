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
 * Instrumented migration test for v31 -> v32 - two additive nullable columns (goal-plans ticket
 * 08, "the checklist prescribes a day, and a ticked day logs itself"):
 * `workout_plan_items.repsPerSet` (see [WorkoutPlanItem.repsPerSet]) and `list_items.loggedAt`
 * (see [ListItem.loggedAt]). `createSql` confirmed against the generated
 * `app/schemas/com.kevin.legion.data.local.CarDatabase/32.json` after a real `compileDebugKotlin
 * -Pnokey` run, not hand-derived - same discipline [MIGRATION_25_26]/[MIGRATION_24_25] document.
 *
 * **NOT RUN this session** - `connectedAndroidTest` uninstalls the app and would take Kevin's real
 * data (CLAUDE.md L11, `memory/MEMORY.md`). Compiled only. On-device evidence (the database opening
 * after install, an existing `workout_plan_items`/`list_items` row surviving with both new columns
 * reading NULL) is deferred to on-device QA.
 */
@RunWith(AndroidJUnit4::class)
class CarDatabaseMigration31To32Test {
    private val dbName = "migration-test-31-32"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CarDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `migrate31To32_isPurelyAdditive_existingRowsSurviveWithBothNewColumnsNull`() {
        helper.createDatabase(dbName, 31).apply {
            execSQL(
                "INSERT INTO workout_plan_items " +
                    "(exercise, targetSetsPerWeek, effectiveFromWeekEpoch, updatedAt) " +
                    "VALUES ('Kettlebell swing', 12, 1000, 1000)"
            )
            execSQL(
                "INSERT INTO list_items " +
                    "(listId, text, done, sortOrder, createdAt, updatedAt, allDay) " +
                    "VALUES (1, 'Plan: Kettlebell swing: 12 sets this week', 0, 0, 1000, 1000, 1)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 32, true, MIGRATION_31_32)

        val itemRow = db.query(
            "SELECT exercise, targetSetsPerWeek, repsPerSet FROM workout_plan_items WHERE exercise = 'Kettlebell swing'"
        )
        assertTrue(itemRow.moveToFirst())
        assertEquals("Kettlebell swing", itemRow.getString(0))
        assertEquals(12, itemRow.getInt(1))
        assertNull(itemRow.getString(2))
        itemRow.close()

        val listRow = db.query(
            "SELECT text, done, loggedAt FROM list_items WHERE text = 'Plan: Kettlebell swing: 12 sets this week'"
        )
        assertTrue(listRow.moveToFirst())
        assertEquals(0, listRow.getInt(1))
        assertNull(listRow.getString(2))
        listRow.close()
    }

    @Test
    fun `migrate31To32_repsPerSetRoundTripsARealValue`() {
        helper.createDatabase(dbName, 31).apply { close() }
        val db = helper.runMigrationsAndValidate(dbName, 32, true, MIGRATION_31_32)

        db.execSQL(
            "INSERT INTO workout_plan_items " +
                "(exercise, targetSetsPerWeek, effectiveFromWeekEpoch, updatedAt, repsPerSet) " +
                "VALUES ('Squat', 9, 2000, 2000, 5)"
        )
        val cursor = db.query("SELECT repsPerSet FROM workout_plan_items WHERE exercise = 'Squat'")
        assertTrue(cursor.moveToFirst())
        assertEquals(5, cursor.getInt(0))
        cursor.close()
    }

    @Test
    fun `migrate31To32_loggedAtRoundTripsARealValue`() {
        helper.createDatabase(dbName, 31).apply { close() }
        val db = helper.runMigrationsAndValidate(dbName, 32, true, MIGRATION_31_32)

        db.execSQL(
            "INSERT INTO list_items (listId, text, done, sortOrder, createdAt, updatedAt, allDay, loggedAt) " +
                "VALUES (1, 'Plan: Squat: 9 sets this week', 1, 0, 3000, 3000, 1, 3600)"
        )
        val cursor = db.query("SELECT loggedAt FROM list_items WHERE text = 'Plan: Squat: 9 sets this week'")
        assertTrue(cursor.moveToFirst())
        assertEquals(3600, cursor.getLong(0))
        cursor.close()
    }
}
