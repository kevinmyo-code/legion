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
 * Instrumented migration test for v16 -> v17 - closing the fresh-install-then-upgraded seeding
 * hole [MIGRATION_11_12] left open (Kevin 2026-08-13). See [MIGRATION_16_17]'s own doc comment for
 * the full story: a database created fresh at v11 or later, before this class had a
 * [androidx.room.RoomDatabase.Callback] (added at v12), never ran [MIGRATION_5_6] and so never got
 * the starter fifteen categories - [MIGRATION_11_12]'s "already seeded by MIGRATION_5_6" assumption
 * was false for it. [MIGRATION_16_17] makes no history assumption at all and repairs any
 * `ledger_transactions` rows guessed against the resulting broken list.
 *
 * Data-only, same as [MIGRATION_11_12] and [MIGRATION_12_13] before it - no `CREATE TABLE`/
 * `CREATE INDEX` at all, so `17.json`'s `identityHash` is byte-identical to `16.json`'s.
 * `MigrationTestHelper.runMigrationsAndValidate` still checks the resulting schema against `17.json`
 * regardless, which is exactly what proves this migration changed no structure.
 *
 * **NOT RUN this session** - `connectedAndroidTest` wipes app data and Kevin's real database is on
 * the connected device (CLAUDE.md L11 / this ticket's verification gates). Compiled only. The claim
 * that this migration is correct against Kevin's ACTUAL data was instead proven separately, offline,
 * against a scratch copy of his pulled database with sqlite3 via python - see the session report.
 */
@RunWith(AndroidJUnit4::class)
class CarDatabaseMigration16To17Test {
    private val dbName = "migration-test-16-17"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CarDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate16To17_fromAnEmptyCategoriesTable_insertsAllSixteenStarters() {
        // The exact shape of Kevin's real bug, worst case: a fresh-at-v11 install that never ran
        // MIGRATION_5_6 AND never even got the Pets row from MIGRATION_11_12 (categories = 0 rows).
        helper.createDatabase(dbName, 16).apply { close() }

        val db = helper.runMigrationsAndValidate(dbName, 17, true, MIGRATION_16_17)

        val count = db.query("SELECT COUNT(*) FROM categories")
        assertTrue(count.moveToFirst())
        assertEquals(16, count.getInt(0))
        count.close()

        val pets = db.query("SELECT isFoodCategory FROM categories WHERE name = 'Pets'")
        assertTrue(pets.moveToFirst())
        assertEquals(0, pets.getInt(0))
        pets.close()

        val groceries = db.query("SELECT isFoodCategory FROM categories WHERE name = 'Groceries'")
        assertTrue(groceries.moveToFirst())
        assertEquals(1, groceries.getInt(0))
        groceries.close()
    }

    @Test
    fun migrate16To17_fromKevinsShape_singlePetsRow_insertsTheOtherFifteenAndRepairsPendingRows() {
        // Kevin's actual real-device shape: categories has exactly one row ('Pets', from
        // MIGRATION_11_12's INSERT OR IGNORE, which never had the other 15 to skip against because
        // MIGRATION_5_6 never ran on his install). 497 ledger_transactions rows were guessed to
        // 'Pets' with categoryPending = 1 by CategoryAgent against that one-item list.
        helper.createDatabase(dbName, 16).apply {
            execSQL("INSERT INTO categories (name, isFoodCategory) VALUES ('Pets', 0)")
            execSQL(
                "INSERT INTO ledger_transactions (sourceFile, accountId, currency, txnDate, " +
                    "description, amountCents, lineRef, ingestMethod, syncId, category, categoryPending) " +
                    "VALUES ('stmt.csv', 'acct-1', 'USD', 1000, 'STARBUCKS #123', -500, 'L1', " +
                    "'LLM_RECONCILED', 's-1', 'Pets', 1)"
            )
            // A row a driver already confirmed by hand (categoryPending = 0) must survive untouched,
            // even though its category also happens to be 'Pets' - a real pet expense.
            execSQL(
                "INSERT INTO ledger_transactions (sourceFile, accountId, currency, txnDate, " +
                    "description, amountCents, lineRef, ingestMethod, syncId, category, categoryPending) " +
                    "VALUES ('stmt.csv', 'acct-1', 'USD', 1000, 'PETCO', -3000, 'L2', " +
                    "'DETERMINISTIC', 's-2', 'Pets', 0)"
            )
            // An uncategorised row (category IS NULL, categoryPending = 0) must also survive untouched.
            execSQL(
                "INSERT INTO ledger_transactions (sourceFile, accountId, currency, txnDate, " +
                    "description, amountCents, lineRef, ingestMethod, syncId, category, categoryPending) " +
                    "VALUES ('stmt.csv', 'acct-1', 'USD', 1000, 'UNKNOWN MERCHANT', -1200, 'L3', " +
                    "'DETERMINISTIC', 's-3', NULL, 0)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 17, true, MIGRATION_16_17)

        // All sixteen starter categories now present.
        val count = db.query("SELECT COUNT(*) FROM categories")
        assertTrue(count.moveToFirst())
        assertEquals(16, count.getInt(0))
        count.close()

        // Total transaction row count is unchanged.
        val txnCount = db.query("SELECT COUNT(*) FROM ledger_transactions")
        assertTrue(txnCount.moveToFirst())
        assertEquals(3, txnCount.getInt(0))
        txnCount.close()

        // The pending guess (s-1) is reset to uncategorised.
        val repaired = db.query(
            "SELECT category, categoryPending FROM ledger_transactions WHERE syncId = 's-1'"
        )
        assertTrue(repaired.moveToFirst())
        assertTrue("repaired row's category must be reset to NULL", repaired.isNull(0))
        assertEquals(0, repaired.getInt(1))
        repaired.close()

        // The confirmed fact (s-2) survives byte-for-byte.
        val confirmed = db.query(
            "SELECT category, categoryPending FROM ledger_transactions WHERE syncId = 's-2'"
        )
        assertTrue(confirmed.moveToFirst())
        assertEquals("Pets", confirmed.getString(0))
        assertEquals(0, confirmed.getInt(1))
        confirmed.close()

        // The already-uncategorised row (s-3) is also untouched.
        val uncategorised = db.query(
            "SELECT category, categoryPending FROM ledger_transactions WHERE syncId = 's-3'"
        )
        assertTrue(uncategorised.moveToFirst())
        assertTrue(uncategorised.isNull(0))
        assertEquals(0, uncategorised.getInt(1))
        uncategorised.close()
    }

    @Test
    fun migrate16To17_fromACompleteCategoriesTable_isANoOpOnTransactions() {
        // The common case: every install that went through MIGRATION_5_6 normally. Nothing missing,
        // so nothing should be inserted and no transaction row should be touched, even one with
        // categoryPending = 1 sitting on a real, still-unconfirmed guess.
        helper.createDatabase(dbName, 16).apply {
            val starterSixteen = listOf(
                "Groceries" to 1, "Dining Out" to 1, "Coffee & Snacks" to 1,
                "Transport" to 0, "Housing" to 0, "Utilities" to 0,
                "Subscriptions" to 0, "Shopping" to 0, "Health" to 0,
                "Travel" to 0, "Entertainment" to 0, "Income" to 0,
                "Fees" to 0, "Insurance" to 0, "Other" to 0, "Pets" to 0,
            )
            for ((name, isFood) in starterSixteen) {
                execSQL("INSERT INTO categories (name, isFoodCategory) VALUES ('$name', $isFood)")
            }
            execSQL(
                "INSERT INTO ledger_transactions (sourceFile, accountId, currency, txnDate, " +
                    "description, amountCents, lineRef, ingestMethod, syncId, category, categoryPending) " +
                    "VALUES ('stmt.csv', 'acct-1', 'USD', 1000, 'AMAZON', -2500, 'L1', " +
                    "'LLM_RECONCILED', 's-1', 'Shopping', 1)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 17, true, MIGRATION_16_17)

        val count = db.query("SELECT COUNT(*) FROM categories")
        assertTrue(count.moveToFirst())
        assertEquals(16, count.getInt(0))
        count.close()

        // The real, still-pending guess is left completely alone - categories was already complete,
        // so this migration had nothing to repair.
        val untouched = db.query(
            "SELECT category, categoryPending FROM ledger_transactions WHERE syncId = 's-1'"
        )
        assertTrue(untouched.moveToFirst())
        assertEquals("Shopping", untouched.getString(0))
        assertEquals(1, untouched.getInt(1))
        untouched.close()
    }

    @Test
    fun migrate16To17_isSafeToRunTwice() {
        helper.createDatabase(dbName, 16).apply { close() }

        val first = helper.runMigrationsAndValidate(dbName, 17, true, MIGRATION_16_17)
        first.close()

        // Re-running the same migration's SQL against the now-complete table must not throw or
        // duplicate rows - the INSERT OR IGNORE guard, same precedent as MIGRATION_11_12.
        val db = helper.runMigrationsAndValidate(dbName, 17, true, MIGRATION_16_17)
        val count = db.query("SELECT COUNT(*) FROM categories")
        assertTrue(count.moveToFirst())
        assertEquals(16, count.getInt(0))
        assertFalse(count.moveToNext())
        count.close()
    }
}
