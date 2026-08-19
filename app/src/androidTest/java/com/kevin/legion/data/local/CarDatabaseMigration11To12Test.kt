package com.kevin.legion.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented migration test for v11 -> v12 - the "existing install" half of the fresh-install
 * seeding bug fix (Kevin 2026-08-07, CLAUDE.md §2 clone-and-run). See [CategorySeed]'s and
 * [MIGRATION_11_12]'s own doc comments for the full story: this migration is DATA-ONLY (no
 * `CREATE TABLE`/`CREATE INDEX` at all, `categories` already exists from v6), so unlike every
 * migration test before it in this chain there is no generated-schema-JSON diff to copy verbatim -
 * `MigrationTestHelper.runMigrationsAndValidate` still checks the resulting schema against `12.json`
 * regardless, which is exactly what proves the migration changed no structure.
 *
 * Same shape as [CarDatabaseMigration10To11Test] - see its doc comment for why this needs
 * `androidTest`, not a plain JVM test.
 *
 * **NOT RUN this session** - `connectedAndroidTest` wipes app data and Kevin's real database is on
 * the connected device (CLAUDE.md L11 / this ticket's verification gates). Compiled only.
 */
@RunWith(AndroidJUnit4::class)
class CarDatabaseMigration11To12Test {
    private val dbName = "migration-test-11-12"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CarDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate11To12_addsPetsWithoutDisturbingTheExistingFifteen() {
        // A v11 database with MIGRATION_5_6's original 15 categories already present - exactly
        // what Kevin's real, upgraded-through-every-version install looks like today, plus one
        // category he already hand-edited a budget target against (Groceries), which must survive
        // completely untouched.
        helper.createDatabase(dbName, 11).apply {
            val starterFifteen = listOf(
                "Groceries" to 1, "Dining Out" to 1, "Coffee & Snacks" to 1,
                "Transport" to 0, "Housing" to 0, "Utilities" to 0,
                "Subscriptions" to 0, "Shopping" to 0, "Health" to 0,
                "Travel" to 0, "Entertainment" to 0, "Income" to 0,
                "Fees" to 0, "Insurance" to 0, "Other" to 0,
            )
            for ((name, isFood) in starterFifteen) {
                execSQL("INSERT INTO categories (name, isFoodCategory) VALUES ('$name', $isFood)")
            }
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 12, true, MIGRATION_11_12)

        // Every one of the original 15 survives, completely unchanged.
        val countCursor = db.query("SELECT COUNT(*) FROM categories")
        assertTrue(countCursor.moveToFirst())
        assertEquals(16, countCursor.getInt(0))
        countCursor.close()

        // Pets is now present, and correctly NOT a food category (D15).
        val petsCursor = db.query("SELECT isFoodCategory FROM categories WHERE name = 'Pets'")
        assertTrue("expected a Pets row after the v11->v12 migration", petsCursor.moveToFirst())
        assertEquals(0, petsCursor.getInt(0))
        petsCursor.close()

        // A pre-existing category's own row is byte-for-byte the same, not re-inserted or renumbered.
        val groceriesCursor = db.query("SELECT isFoodCategory FROM categories WHERE name = 'Groceries'")
        assertTrue(groceriesCursor.moveToFirst())
        assertEquals(1, groceriesCursor.getInt(0))
        assertFalse("expected exactly one Groceries row, not a duplicate", groceriesCursor.moveToNext())
        groceriesCursor.close()
    }

    @Test
    fun migrate11To12_isSafeToRunAgainstADatabaseThatSomehowAlreadyHasPets() {
        // The INSERT OR IGNORE guard (MIGRATION_11_12's own doc comment) - if Pets is somehow
        // already present (should never happen through the app's normal migration chain, but the
        // UNIQUE index on `name` makes a naive INSERT throw and crash the whole upgrade instead of
        // silently no-opping), the migration must not fail.
        helper.createDatabase(dbName, 11).apply {
            execSQL("INSERT INTO categories (name, isFoodCategory) VALUES ('Pets', 0)")
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 12, true, MIGRATION_11_12)

        val cursor = db.query("SELECT COUNT(*) FROM categories WHERE name = 'Pets'")
        assertTrue(cursor.moveToFirst())
        assertEquals(1, cursor.getInt(0))
        cursor.close()
    }
}
