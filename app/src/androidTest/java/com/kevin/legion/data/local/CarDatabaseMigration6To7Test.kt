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
 * Instrumented migration test for v6 -> v7 (the workouts and meals aspects,
 * `.scratch/legion-shape/issues/08-workouts-domain.md` and `09-meals-domain.md`). Same shape as
 * [CarDatabaseMigration5To6Test] - see its doc comment for why this needs `androidTest`, not a
 * plain JVM unit test.
 */
@RunWith(AndroidJUnit4::class)
class CarDatabaseMigration6To7Test {
    private val dbName = "migration-test-6-7"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CarDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate6To7_addsSixNewTablesAndPreservesExistingData() {
        // Create the v6 database and insert one representative pre-existing row (a
        // ledger_transactions record) to confirm the migration is purely additive
        // and leaves every existing table/column untouched.
        helper.createDatabase(dbName, 6).apply {
            execSQL(
                "INSERT INTO ledger_transactions " +
                    "(id, sourceFile, accountId, currency, txnDate, description, amountCents, " +
                    "balanceCents, lineRef, ingestMethod, syncId, sourceFileId, category, categoryPending) VALUES " +
                    "(1, 'eStmt.pdf', 'BOFA-1234', 'USD', 1733356800000, 'KROGER #115 CYPRESS TX', " +
                    "-4200, NULL, '1', 'DETERMINISTIC', 'sync-1', NULL, NULL, 0)"
            )
            close()
        }

        // Run the real migration under test.
        val db = helper.runMigrationsAndValidate(dbName, 7, true, MIGRATION_6_7)

        // The pre-existing row survived untouched.
        val existing = db.query("SELECT description FROM ledger_transactions WHERE id = 1")
        assertTrue("expected the pre-migration row to still exist", existing.moveToFirst())
        assertEquals("KROGER #115 CYPRESS TX", existing.getString(0))
        existing.close()

        // All six new tables exist, are queryable, and start empty - nothing
        // pre-migration wrote to any of them, and no destructive fallback ran.
        for (table in listOf(
            "workout_plans", "workout_plan_items", "workout_set_logs",
            "bodyweight_logs", "meal_targets", "meal_logs",
        )) {
            val count = db.query("SELECT COUNT(*) FROM $table")
            assertTrue(count.moveToFirst())
            assertEquals("expected $table to start empty", 0, count.getInt(0))
            count.close()
        }

        // A row can actually be written and read back through every new table
        // via the new schema, including the nullable optional-detail columns.
        db.execSQL(
            "INSERT INTO workout_plans (sessionsPerWeek, effectiveFromWeekEpoch, updatedAt) " +
                "VALUES (3, 1733356800000, 1733356800000)"
        )
        db.execSQL(
            "INSERT INTO workout_plan_items (exercise, targetSetsPerWeek, effectiveFromWeekEpoch, updatedAt) " +
                "VALUES ('Squat', 12, 1733356800000, 1733356800000)"
        )
        db.execSQL(
            "INSERT INTO workout_set_logs (exercise, sets, reps, weightValue, weightUnit, loggedAt, trustTier) " +
                "VALUES ('Squat', 3, 5, 225.0, 'lbs', 1733356800000, 'REPORTED')"
        )
        db.execSQL(
            "INSERT INTO bodyweight_logs (weightValue, weightUnit, loggedAt, trustTier) " +
                "VALUES (180.0, 'lbs', 1733356800000, 'REPORTED')"
        )
        db.execSQL(
            "INSERT INTO meal_targets (caloriesKcal, proteinG, carbsG, fatG, effectiveFromDateEpoch, updatedAt) " +
                "VALUES (2200, 150.0, 200.0, 70.0, 1733356800000, 1733356800000)"
        )
        db.execSQL(
            "INSERT INTO meal_logs (description, caloriesKcal, proteinG, carbsG, fatG, loggedAt, sourceImagePath, trustTier) " +
                "VALUES ('Chicken burrito bowl', 650, 45.0, 60.0, 20.0, 1733356800000, NULL, 'REPORTED')"
        )

        val set = db.query("SELECT exercise, sets, weightValue FROM workout_set_logs WHERE exercise = 'Squat'")
        assertTrue(set.moveToFirst())
        assertEquals(3, set.getInt(1))
        assertEquals(225.0, set.getDouble(2), 0.001)
        set.close()

        val meal = db.query("SELECT caloriesKcal FROM meal_logs WHERE description = 'Chicken burrito bowl'")
        assertTrue(meal.moveToFirst())
        assertEquals(650, meal.getInt(0))
        meal.close()
    }
}
