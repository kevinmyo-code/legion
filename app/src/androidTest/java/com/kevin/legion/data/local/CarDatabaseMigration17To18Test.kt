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
 * Instrumented migration test for v17 -> v18 - the `CHECKCARD` bug (Kevin 2026-08-13, found on his
 * own real production data). See [MIGRATION_17_18]'s own doc comment for the full story:
 * [com.kevin.legion.ledger.extractMerchantKey] used to split a Bank of America card line at its own
 * MMDD posting date, collapsing every card purchase's key to the bank's own word "CHECKCARD", and a
 * `category_rules` row on that exact substring had silently confirmed 48 unrelated transactions into
 * "Subscriptions".
 *
 * Data-only, same as [MIGRATION_16_17] before it - no `CREATE TABLE`/`CREATE INDEX` at all, so
 * `18.json`'s `identityHash` is byte-identical to `17.json`'s. `MigrationTestHelper.runMigrationsAndValidate`
 * still checks the resulting schema against `18.json` regardless, which is exactly what proves this
 * migration changed no structure.
 *
 * **NOT RUN this session** - `connectedAndroidTest` wipes app data and Kevin's real database is on
 * the connected device (CLAUDE.md L11 / this ticket's verification gates). Compiled only. The claim
 * that this migration is correct against Kevin's ACTUAL data was instead proven separately, offline,
 * against a scratch copy of his pulled database with sqlite3 via python - see the session report.
 */
@RunWith(AndroidJUnit4::class)
class CarDatabaseMigration17To18Test {
    private val dbName = "migration-test-17-18"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CarDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate17To18_deletesTheCheckcardRuleAndResetsExactlyTheRowsItConfirmed() {
        // Kevin's real shape: a single CHECKCARD -> Subscriptions rule, and a mix of rows it
        // reached (some genuinely unrelated to a subscription) plus rows it must NOT touch.
        helper.createDatabase(dbName, 17).apply {
            execSQL("INSERT INTO categories (name, isFoodCategory) VALUES ('Subscriptions', 0)")
            execSQL("INSERT INTO categories (name, isFoodCategory) VALUES ('Shopping', 0)")
            execSQL(
                "INSERT INTO category_rules (category, substring, createdAt) " +
                    "VALUES ('Subscriptions', 'CHECKCARD', 1000)"
            )
            // Confirmed by the bad rule - a Walmart purchase, not a subscription. Must be reset.
            execSQL(
                "INSERT INTO ledger_transactions (sourceFile, accountId, currency, txnDate, " +
                    "description, amountCents, lineRef, ingestMethod, syncId, category, categoryPending) " +
                    "VALUES ('stmt.pdf', 'acct-1', 'USD', 1000, 'CHECKCARD  0115 WM SUPERCENTER KATY TX', " +
                    "-4599, 'L1', 'DETERMINISTIC', 's-1', 'Subscriptions', 0)"
            )
            // Also confirmed by the bad rule - T-Mobile is a plausible subscription by coincidence,
            // but it was still filed by the same unconditional CHECKCARD match, not by anything that
            // actually looked at the merchant, so it must be reset too - the migration doesn't get to
            // guess which CHECKCARD-confirmed rows "happen to be right".
            execSQL(
                "INSERT INTO ledger_transactions (sourceFile, accountId, currency, txnDate, " +
                    "description, amountCents, lineRef, ingestMethod, syncId, category, categoryPending) " +
                    "VALUES ('stmt.pdf', 'acct-1', 'USD', 1000, 'CHECKCARD  0429 TMOBILE PREPD BELLEVUE WA', " +
                    "-5000, 'L2', 'DETERMINISTIC', 's-2', 'Subscriptions', 0)"
            )
            // A CHECKCARD-prefixed row filed under a DIFFERENT category than the bad rule's own -
            // impossible in practice (setCategoryForMerchant always writes the rule's own category),
            // but the WHERE clause is scoped to the rule's exact category too, so prove it's inert:
            // this row must survive untouched, category and all.
            execSQL(
                "INSERT INTO ledger_transactions (sourceFile, accountId, currency, txnDate, " +
                    "description, amountCents, lineRef, ingestMethod, syncId, category, categoryPending) " +
                    "VALUES ('stmt.pdf', 'acct-1', 'USD', 1000, 'CHECKCARD  0301 PANDA EXPRESS HOUSTON TX', " +
                    "-1200, 'L3', 'DETERMINISTIC', 's-3', 'Shopping', 0)"
            )
            // A row that legitimately belongs in Subscriptions but was never touched by CHECKCARD at
            // all (a driver's own hand-set rule, or a different merchant entirely) - must survive.
            execSQL(
                "INSERT INTO ledger_transactions (sourceFile, accountId, currency, txnDate, " +
                    "description, amountCents, lineRef, ingestMethod, syncId, category, categoryPending) " +
                    "VALUES ('stmt.pdf', 'acct-1', 'USD', 1000, 'NETFLIX.COM', " +
                    "-1599, 'L4', 'DETERMINISTIC', 's-4', 'Subscriptions', 0)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 18, true, MIGRATION_17_18)

        // The bad rule is gone.
        val rules = db.query("SELECT COUNT(*) FROM category_rules WHERE substring = 'CHECKCARD'")
        assertTrue(rules.moveToFirst())
        assertEquals(0, rules.getInt(0))
        rules.close()

        // Total row count is unchanged - this is a repair, not a delete.
        val txnCount = db.query("SELECT COUNT(*) FROM ledger_transactions")
        assertTrue(txnCount.moveToFirst())
        assertEquals(4, txnCount.getInt(0))
        txnCount.close()

        // s-1 (Walmart) and s-2 (T-Mobile) are both reset to uncategorised.
        for (syncId in listOf("s-1", "s-2")) {
            val repaired = db.query(
                "SELECT category, categoryPending FROM ledger_transactions WHERE syncId = '$syncId'"
            )
            assertTrue(repaired.moveToFirst())
            assertTrue("$syncId must be reset to NULL", repaired.isNull(0))
            assertEquals(0, repaired.getInt(1))
            repaired.close()
        }

        // s-3 (different category than the rule's own) is untouched.
        val differentCategory = db.query(
            "SELECT category, categoryPending FROM ledger_transactions WHERE syncId = 's-3'"
        )
        assertTrue(differentCategory.moveToFirst())
        assertEquals("Shopping", differentCategory.getString(0))
        assertEquals(0, differentCategory.getInt(1))
        differentCategory.close()

        // s-4 (never touched by CHECKCARD) is untouched.
        val unrelated = db.query(
            "SELECT category, categoryPending FROM ledger_transactions WHERE syncId = 's-4'"
        )
        assertTrue(unrelated.moveToFirst())
        assertEquals("Subscriptions", unrelated.getString(0))
        assertEquals(0, unrelated.getInt(1))
        unrelated.close()
    }

    @Test
    fun migrate17To18_withNoNoiseRulesAtAll_isANoOp() {
        // The common case for anyone but Kevin: no CHECKCARD/CHKCARD/PURCHASE rule was ever created,
        // so the migration deletes nothing and touches no transaction.
        helper.createDatabase(dbName, 17).apply {
            execSQL("INSERT INTO categories (name, isFoodCategory) VALUES ('Groceries', 1)")
            execSQL(
                "INSERT INTO category_rules (category, substring, createdAt) " +
                    "VALUES ('Groceries', 'KROGER', 1000)"
            )
            execSQL(
                "INSERT INTO ledger_transactions (sourceFile, accountId, currency, txnDate, " +
                    "description, amountCents, lineRef, ingestMethod, syncId, category, categoryPending) " +
                    "VALUES ('stmt.pdf', 'acct-1', 'USD', 1000, 'KROGER #115 CYPRESS TX', " +
                    "-3000, 'L1', 'DETERMINISTIC', 's-1', 'Groceries', 0)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 18, true, MIGRATION_17_18)

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
    fun migrate17To18_isSafeToRunTwice() {
        helper.createDatabase(dbName, 17).apply {
            execSQL("INSERT INTO categories (name, isFoodCategory) VALUES ('Subscriptions', 0)")
            execSQL(
                "INSERT INTO category_rules (category, substring, createdAt) " +
                    "VALUES ('Subscriptions', 'CHECKCARD', 1000)"
            )
            close()
        }

        val first = helper.runMigrationsAndValidate(dbName, 18, true, MIGRATION_17_18)
        first.close()

        // Re-running against an already-repaired database (rule already gone) must not throw.
        val db = helper.runMigrationsAndValidate(dbName, 18, true, MIGRATION_17_18)
        val rules = db.query("SELECT COUNT(*) FROM category_rules WHERE substring = 'CHECKCARD'")
        assertTrue(rules.moveToFirst())
        assertEquals(0, rules.getInt(0))
        rules.close()
    }
}
