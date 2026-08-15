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
 * Instrumented migration test for v7 -> v8 (nullable `ledger_transactions.pendingLoggedAt`,
 * voice-logged pending transactions). Same shape as [CarDatabaseMigration6To7Test] - see its doc
 * comment for why this needs `androidTest`, not a plain JVM unit test.
 */
@RunWith(AndroidJUnit4::class)
class CarDatabaseMigration7To8Test {
    private val dbName = "migration-test-7-8"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CarDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate7To8_addsPendingLoggedAtAndPreservesExistingData() {
        // Create the v7 database and insert one representative pre-existing row to confirm the
        // migration is purely additive and leaves every existing column untouched.
        helper.createDatabase(dbName, 7).apply {
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
        val db = helper.runMigrationsAndValidate(dbName, 8, true, MIGRATION_7_8)

        // The pre-existing row survived untouched, and its new column defaults to NULL - "no file
        // and no driver ever logged this as pending" is exactly what an old row must read as.
        val existing = db.query("SELECT description, pendingLoggedAt FROM ledger_transactions WHERE id = 1")
        assertTrue("expected the pre-migration row to still exist", existing.moveToFirst())
        assertEquals("KROGER #115 CYPRESS TX", existing.getString(0))
        assertTrue("pendingLoggedAt must default to NULL for a pre-existing row", existing.isNull(1))
        existing.close()

        // A new voice-logged pending row can actually be written and read back through the new column.
        db.execSQL(
            "INSERT INTO ledger_transactions " +
                "(id, sourceFile, accountId, currency, txnDate, description, amountCents, " +
                "balanceCents, lineRef, ingestMethod, syncId, sourceFileId, category, categoryPending, pendingLoggedAt) VALUES " +
                "(2, 'voice', 'BOFA-1234', 'USD', 1733356800000, 'Hardware store, pending', " +
                "-4000, NULL, 'voice:abc', 'UNRECONCILED', 'sync-2', NULL, NULL, 0, 1733360000000)"
        )
        val pending = db.query("SELECT description, pendingLoggedAt FROM ledger_transactions WHERE id = 2")
        assertTrue(pending.moveToFirst())
        assertEquals("Hardware store, pending", pending.getString(0))
        assertEquals(1733360000000L, pending.getLong(1))
        pending.close()
    }
}
