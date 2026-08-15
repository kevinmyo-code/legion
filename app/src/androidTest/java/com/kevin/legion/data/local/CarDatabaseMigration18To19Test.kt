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
 * Instrumented migration test for v18 -> v19 - the transfer/category defect
 * (`.scratch/car-probe-transfers/`, 2026-08-13, Kevin's direct request). See [MIGRATION_18_19]'s own
 * doc comment for the full story: [com.kevin.legion.ledger.analyzeTransfers] correctly flagged a
 * transfer row but was never wired into the merchant-categorisation pipeline, so a row that moved
 * Kevin's own money between his own accounts could still acquire a category and a
 * [CategoryRule].
 *
 * Data-only, same as [MIGRATION_16_17]/[MIGRATION_17_18] before it - no `CREATE TABLE`/`CREATE
 * INDEX` at all, so `19.json`'s `identityHash` is byte-identical to `18.json`'s.
 * `MigrationTestHelper.runMigrationsAndValidate` still checks the resulting schema against
 * `19.json` regardless, which is exactly what proves this migration changed no structure.
 *
 * Mirrors [CarDatabaseMigration17To18Test]'s structure exactly, per this ticket's own instruction.
 */
@RunWith(AndroidJUnit4::class)
class CarDatabaseMigration18To19Test {
    private val dbName = "migration-test-18-19"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CarDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate18To19_resetsCategorisedTransferRowsAndDeletesTransferShapedRules() {
        helper.createDatabase(dbName, 18).apply {
            execSQL("INSERT INTO categories (name, isFoodCategory) VALUES ('Subscriptions', 0)")
            execSQL("INSERT INTO categories (name, isFoodCategory) VALUES ('Groceries', 1)")
            // A rule that reached transfer wording by accident - must be deleted.
            execSQL(
                "INSERT INTO category_rules (category, substring, createdAt) " +
                    "VALUES ('Subscriptions', 'PAYMENT TO CRD', 1000)"
            )
            // Confirmed by that bad rule - a transfer row wearing a category it should never have
            // gotten. Must be reset.
            execSQL(
                "INSERT INTO ledger_transactions (sourceFile, accountId, currency, txnDate, " +
                    "description, amountCents, lineRef, ingestMethod, syncId, category, categoryPending) " +
                    "VALUES ('stmt.pdf', 'checking-119', 'USD', 1000, 'MOBILE BANKING PAYMENT TO CRD', " +
                    "-315000, 'L1', 'DETERMINISTIC', 's-1', 'Subscriptions', 0)"
            )
            // A DIFFERENT transfer-shaped row, categorised some other way (hand-set, no rule
            // involved) - must ALSO be reset. Unlike MIGRATION_17_18, this repair is deliberately
            // unscoped to any one rule's category.
            execSQL(
                "INSERT INTO ledger_transactions (sourceFile, accountId, currency, txnDate, " +
                    "description, amountCents, lineRef, ingestMethod, syncId, category, categoryPending) " +
                    "VALUES ('stmt.pdf', 'checking-119', 'USD', 1000, 'ONLINE BANKING TRANSFER FROM SAV', " +
                    "500000, 'L2', 'DETERMINISTIC', 's-2', 'Groceries', 0)"
            )
            // A genuine merchant row - must survive untouched.
            execSQL(
                "INSERT INTO ledger_transactions (sourceFile, accountId, currency, txnDate, " +
                    "description, amountCents, lineRef, ingestMethod, syncId, category, categoryPending) " +
                    "VALUES ('stmt.pdf', 'checking-119', 'USD', 1000, 'KROGER #115 CYPRESS TX', " +
                    "-3000, 'L3', 'DETERMINISTIC', 's-3', 'Groceries', 0)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 19, true, MIGRATION_18_19)

        // The transfer-shaped rule is gone.
        val rules = db.query("SELECT COUNT(*) FROM category_rules WHERE substring = 'PAYMENT TO CRD'")
        assertTrue(rules.moveToFirst())
        assertEquals(0, rules.getInt(0))
        rules.close()

        // Total row count is unchanged - this is a repair, not a delete.
        val txnCount = db.query("SELECT COUNT(*) FROM ledger_transactions")
        assertTrue(txnCount.moveToFirst())
        assertEquals(3, txnCount.getInt(0))
        txnCount.close()

        // s-1 and s-2 are both reset to uncategorised, regardless of which rule (if any) filed them.
        for (syncId in listOf("s-1", "s-2")) {
            val repaired = db.query(
                "SELECT category, categoryPending FROM ledger_transactions WHERE syncId = '$syncId'"
            )
            assertTrue(repaired.moveToFirst())
            assertTrue("$syncId must be reset to NULL", repaired.isNull(0))
            assertEquals(0, repaired.getInt(1))
            repaired.close()
        }

        // s-3 (a real merchant, never transfer-shaped) is untouched.
        val untouched = db.query(
            "SELECT category, categoryPending FROM ledger_transactions WHERE syncId = 's-3'"
        )
        assertTrue(untouched.moveToFirst())
        assertEquals("Groceries", untouched.getString(0))
        assertEquals(0, untouched.getInt(1))
        untouched.close()
    }

    @Test
    fun migrate18To19_withNoTransferShapedDataAtAll_isANoOp() {
        helper.createDatabase(dbName, 18).apply {
            execSQL("INSERT INTO categories (name, isFoodCategory) VALUES ('Groceries', 1)")
            execSQL(
                "INSERT INTO category_rules (category, substring, createdAt) " +
                    "VALUES ('Groceries', 'KROGER', 1000)"
            )
            execSQL(
                "INSERT INTO ledger_transactions (sourceFile, accountId, currency, txnDate, " +
                    "description, amountCents, lineRef, ingestMethod, syncId, category, categoryPending) " +
                    "VALUES ('stmt.pdf', 'checking-119', 'USD', 1000, 'KROGER #115 CYPRESS TX', " +
                    "-3000, 'L1', 'DETERMINISTIC', 's-1', 'Groceries', 0)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 19, true, MIGRATION_18_19)

        val rules = db.query("SELECT COUNT(*) FROM category_rules")
        assertTrue(rules.moveToFirst())
        assertEquals(1, rules.getInt(0))
        rules.close()

        val untouched = db.query(
            "SELECT category, categoryPending FROM ledger_transactions WHERE syncId = 's-1'"
        )
        assertTrue(untouched.moveToFirst())
        assertEquals("Groceries", untouched.getString(0))
        assertEquals(0, untouched.getInt(1))
        untouched.close()
    }

    @Test
    fun migrate18To19_isSafeToRunTwice() {
        helper.createDatabase(dbName, 18).apply {
            execSQL("INSERT INTO categories (name, isFoodCategory) VALUES ('Subscriptions', 0)")
            execSQL(
                "INSERT INTO category_rules (category, substring, createdAt) " +
                    "VALUES ('Subscriptions', 'TRANSFER TO', 1000)"
            )
            close()
        }

        val first = helper.runMigrationsAndValidate(dbName, 19, true, MIGRATION_18_19)
        first.close()

        // Re-running against an already-repaired database (rule already gone) must not throw.
        val db = helper.runMigrationsAndValidate(dbName, 19, true, MIGRATION_18_19)
        val rules = db.query("SELECT COUNT(*) FROM category_rules WHERE substring = 'TRANSFER TO'")
        assertTrue(rules.moveToFirst())
        assertEquals(0, rules.getInt(0))
        rules.close()
    }
}
