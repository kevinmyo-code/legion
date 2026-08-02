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
 * Instrumented migration test for v3 -> v4 (ticket 03, `ingested_files` +
 * `ledger_transactions.sourceFileId`). Room's `MigrationTestHelper` needs a
 * real SQLite implementation from the framework, hence `androidTest` rather
 * than a JVM unit test - see CLAUDE.md's PdfBox/Robolectric note for the
 * parallel reasoning on why some Room-adjacent tests can't be plain JUnit.
 */
@RunWith(AndroidJUnit4::class)
class CarDatabaseMigration3To4Test {
    private val dbName = "migration-test-3-4"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CarDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate3To4_preservesExistingRowAndAddsIngestedFilesTable() {
        // Create the v3 database and insert one representative
        // ledger_transactions row exactly as v3's schema shapes it (no
        // sourceFileId column exists yet at this version).
        helper.createDatabase(dbName, 3).apply {
            execSQL(
                "INSERT INTO ledger_transactions " +
                    "(id, sourceFile, accountId, currency, txnDate, description, amountCents, " +
                    "balanceCents, lineRef, ingestMethod, syncId) VALUES " +
                    "(1, 'eStmt_2025-12-05.pdf', 'dbs-checking', 'SGD', 1733356800000, " +
                    "'NETS PURCHASE', -5250, 123456, 'line-42', 'DETERMINISTIC', 'sync-abc')"
            )
            close()
        }

        // Run the real migration under test.
        val db = helper.runMigrationsAndValidate(dbName, 4, true, MIGRATION_3_4)

        // The pre-existing row survived, with sourceFileId defaulting to NULL.
        val cursor = db.query("SELECT sourceFileId FROM ledger_transactions WHERE id = 1")
        assertTrue("expected the pre-migration row to still exist", cursor.moveToFirst())
        val sourceFileIdColumn = cursor.getColumnIndex("sourceFileId")
        assertTrue(cursor.isNull(sourceFileIdColumn))
        cursor.close()

        // ingested_files exists and is queryable (empty, since nothing wrote to it pre-migration).
        val ingestedCursor = db.query("SELECT COUNT(*) FROM ingested_files")
        assertTrue(ingestedCursor.moveToFirst())
        assertEquals(0, ingestedCursor.getInt(0))
        ingestedCursor.close()
    }
}
