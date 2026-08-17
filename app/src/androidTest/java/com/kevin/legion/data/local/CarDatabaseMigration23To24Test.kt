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
 * Instrumented migration test for v23 -> v24 - closing the `categoryPending` default drift, ticket
 * 13 (`.scratch/ledger-drive-ingestion/issues/13-categorypending-default-drift.md`). See
 * [MIGRATION_23_24]'s own doc comment for the full story: [LedgerTransaction.categoryPending] never
 * carried `@ColumnInfo(defaultValue = ...)`, even though [MIGRATION_5_6] has written `DEFAULT 0` on
 * this exact column since v6. Every migrated device has therefore had `DEFAULT 0` on disk all
 * along; only a fresh install's DDL was ever missing it. This migration's body is empty - there is
 * nothing to change on a migrated device's DDL, only on a fresh one's, and a fresh install builds
 * its schema straight from the corrected `@Entity`, never by replaying migrations.
 *
 * `MigrationTestHelper.runMigrationsAndValidate` validates the post-migration `PRAGMA table_info`
 * against `24.json` regardless of the empty body, which is exactly what proves the empty body is
 * sufficient. The second test below goes one step further and proves the `DEFAULT 0` clause is
 * real at the SQLite level (not just present in the schema JSON metadata) by inserting a row that
 * omits `categoryPending` entirely and reading back `0`.
 *
 * **NOT RUN this session** - `connectedAndroidTest` uninstalls the app and would take Kevin's real
 * data (CLAUDE.md L11, `memory/MEMORY.md`). Compiled only.
 */
@RunWith(AndroidJUnit4::class)
class CarDatabaseMigration23To24Test {
    private val dbName = "migration-test-23-24"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CarDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate23To24_isPurelyAdditive_everyExistingRowSurvivesWithIdenticalValues() {
        // A mix of categoryPending = 0 and = 1 rows, exactly the shape a real ledger has:
        // uncategorised/confirmed rows next to pending AI guesses. Nothing about this migration
        // should touch a single value in any of them.
        helper.createDatabase(dbName, 23).apply {
            execSQL(
                "INSERT INTO ledger_transactions (sourceFile, accountId, currency, txnDate, " +
                    "description, amountCents, lineRef, ingestMethod, syncId, category, categoryPending) " +
                    "VALUES ('stmt.pdf', 'acct-1', 'USD', 1000, 'NETFLIX.COM', -1599, 'L1', " +
                    "'DETERMINISTIC', 's-1', 'Subscriptions', 0)"
            )
            execSQL(
                "INSERT INTO ledger_transactions (sourceFile, accountId, currency, txnDate, " +
                    "description, amountCents, lineRef, ingestMethod, syncId, category, categoryPending) " +
                    "VALUES ('stmt.pdf', 'acct-1', 'USD', 1000, 'SHELL OIL 12345', -4000, 'L2', " +
                    "'DETERMINISTIC', 's-2', 'Transport', 1)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 24, true, MIGRATION_23_24)

        val count = db.query("SELECT COUNT(*) FROM ledger_transactions")
        assertTrue(count.moveToFirst())
        assertEquals(2, count.getInt(0))
        count.close()

        val row1 = db.query(
            "SELECT category, categoryPending FROM ledger_transactions WHERE syncId = 's-1'"
        )
        assertTrue(row1.moveToFirst())
        assertEquals("Subscriptions", row1.getString(0))
        assertEquals(0, row1.getInt(1))
        row1.close()

        val row2 = db.query(
            "SELECT category, categoryPending FROM ledger_transactions WHERE syncId = 's-2'"
        )
        assertTrue(row2.moveToFirst())
        assertEquals("Transport", row2.getString(0))
        assertEquals(1, row2.getInt(1))
        row2.close()
    }

    @Test
    fun migrate23To24_categoryPendingDefaultsToZeroWhenOmittedOnInsert() {
        // Proves DEFAULT 0 is real SQLite DDL post-migration, not just schema-JSON metadata -
        // mirrors MIGRATION_22_23's own "fresh insert after migration" precedent for syncId.
        helper.createDatabase(dbName, 23).apply { close() }
        val db = helper.runMigrationsAndValidate(dbName, 24, true, MIGRATION_23_24)

        db.execSQL(
            "INSERT INTO ledger_transactions (sourceFile, accountId, currency, txnDate, " +
                "description, amountCents, lineRef, ingestMethod, syncId) " +
                "VALUES ('stmt.pdf', 'acct-1', 'USD', 2000, 'KROGER #115', -3000, 'L3', " +
                "'DETERMINISTIC', 's-3')"
        )
        val cursor = db.query(
            "SELECT categoryPending FROM ledger_transactions WHERE syncId = 's-3'"
        )
        assertTrue(cursor.moveToFirst())
        assertEquals(0, cursor.getInt(0))
        cursor.close()
    }
}
