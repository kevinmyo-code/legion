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
 * Instrumented migration test for v5 -> v6 (ledger categorisation and budget-versus-actual,
 * `.scratch/legion-shape/issues/06-budget-versus-actual.md` and `07-categorisation.md`). Same
 * shape as [CarDatabaseMigration4To5Test] - see its doc comment for why this needs `androidTest`,
 * not a plain JVM unit test.
 */
@RunWith(AndroidJUnit4::class)
class CarDatabaseMigration5To6Test {
    private val dbName = "migration-test-5-6"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CarDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate5To6_addsCategoryAndBudgetTablesAndCategoryColumnsAndPreservesExistingData() {
        // Create the v5 database and insert one representative pre-existing
        // row (a ledger_transactions record) to confirm the migration is
        // purely additive and leaves other tables/columns untouched.
        helper.createDatabase(dbName, 5).apply {
            execSQL(
                "INSERT INTO ledger_transactions " +
                    "(id, sourceFile, accountId, currency, txnDate, description, amountCents, " +
                    "balanceCents, lineRef, ingestMethod, syncId, sourceFileId) VALUES " +
                    "(1, 'eStmt.pdf', 'BOFA-1234', 'USD', 1733356800000, 'KROGER #115 CYPRESS TX', " +
                    "-4200, NULL, '1', 'DETERMINISTIC', 'sync-1', NULL)"
            )
            close()
        }

        // Run the real migration under test.
        val db = helper.runMigrationsAndValidate(dbName, 6, true, MIGRATION_5_6)

        // The pre-existing row in ledger_transactions survived untouched, and
        // its new `category`/`categoryPending` columns default to
        // null/false rather than erroring or requiring a rewrite.
        val existing = db.query("SELECT description, category, categoryPending FROM ledger_transactions WHERE id = 1")
        assertTrue("expected the pre-migration row to still exist", existing.moveToFirst())
        assertEquals("KROGER #115 CYPRESS TX", existing.getString(0))
        assertTrue("expected category to default to NULL", existing.isNull(1))
        assertEquals(0, existing.getInt(2))
        existing.close()

        // categories was seeded with the D14/D15 starter set - not empty,
        // and Groceries is marked a food category as promised.
        val categoryCount = db.query("SELECT COUNT(*) FROM categories")
        assertTrue(categoryCount.moveToFirst())
        assertTrue("expected the starter category set to be seeded", categoryCount.getInt(0) > 0)
        categoryCount.close()

        val groceries = db.query("SELECT isFoodCategory FROM categories WHERE name = 'Groceries'")
        assertTrue(groceries.moveToFirst())
        assertEquals(1, groceries.getInt(0))
        groceries.close()

        // category_rules and budget_targets exist, are queryable, and start
        // empty - nothing pre-migration wrote to either, and no destructive
        // fallback should have run.
        val rules = db.query("SELECT COUNT(*) FROM category_rules")
        assertTrue(rules.moveToFirst())
        assertEquals(0, rules.getInt(0))
        rules.close()

        val budgets = db.query("SELECT COUNT(*) FROM budget_targets")
        assertTrue(budgets.moveToFirst())
        assertEquals(0, budgets.getInt(0))
        budgets.close()

        // A row can actually be written and read back through every new
        // table via the new schema.
        db.execSQL("INSERT INTO category_rules (category, substring, createdAt) VALUES ('Groceries', 'KROGER', 1733356800000)")
        db.execSQL(
            "INSERT INTO budget_targets (category, currency, amountCents, effectiveFromMonthEpoch, updatedAt) " +
                "VALUES ('Groceries', 'USD', 60000, 1733356800000, 1733356800000)"
        )
        db.execSQL("UPDATE ledger_transactions SET category = 'Groceries', categoryPending = 0 WHERE id = 1")

        val written = db.query("SELECT category FROM ledger_transactions WHERE id = 1")
        assertTrue(written.moveToFirst())
        assertEquals("Groceries", written.getString(0))
        written.close()
    }
}
