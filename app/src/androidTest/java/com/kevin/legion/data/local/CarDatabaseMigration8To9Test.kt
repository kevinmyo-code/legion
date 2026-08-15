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
 * Instrumented migration test for v8 -> v9 (`sleep_targets` + `sleep_logs`, the sleep aspect).
 * Same shape as [CarDatabaseMigration7To8Test] - see its doc comment for why this needs
 * `androidTest`, not a plain JVM unit test (PdfBox-Android's Robolectric requirement doesn't apply
 * here, but Room's real `MigrationTestHelper` needs a real SQLite implementation either way).
 */
@RunWith(AndroidJUnit4::class)
class CarDatabaseMigration8To9Test {
    private val dbName = "migration-test-8-9"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CarDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate8To9_createsSleepTablesAndPreservesExistingData() {
        // Create the v8 database and insert one representative pre-existing row in an UNRELATED
        // table, to confirm the migration is purely additive and touches nothing else.
        helper.createDatabase(dbName, 8).apply {
            execSQL(
                "INSERT INTO ledger_transactions " +
                    "(id, sourceFile, accountId, currency, txnDate, description, amountCents, " +
                    "balanceCents, lineRef, ingestMethod, syncId, sourceFileId, category, categoryPending, pendingLoggedAt) VALUES " +
                    "(1, 'eStmt.pdf', 'BOFA-1234', 'USD', 1733356800000, 'KROGER #115 CYPRESS TX', " +
                    "-4200, NULL, '1', 'DETERMINISTIC', 'sync-1', NULL, NULL, 0, NULL)"
            )
            close()
        }

        // Run the real migration under test.
        val db = helper.runMigrationsAndValidate(dbName, 9, true, MIGRATION_8_9)

        // The pre-existing, unrelated row survived untouched.
        val existing = db.query("SELECT description FROM ledger_transactions WHERE id = 1")
        assertTrue("expected the pre-migration row to still exist", existing.moveToFirst())
        assertEquals("KROGER #115 CYPRESS TX", existing.getString(0))
        existing.close()

        // A sleep target can actually be written and read back through the new table.
        db.execSQL(
            "INSERT INTO sleep_targets (id, targetMinutes, effectiveFromDateEpoch, updatedAt) VALUES " +
                "(1, 480, 1733356800000, 1733356800000)"
        )
        val target = db.query("SELECT targetMinutes FROM sleep_targets WHERE id = 1")
        assertTrue(target.moveToFirst())
        assertEquals(480, target.getInt(0))
        target.close()

        // A sleep log can actually be written and read back through the new table, including its
        // nullable quality/notes columns.
        db.execSQL(
            "INSERT INTO sleep_logs (id, sleepDate, durationMinutes, quality, notes, loggedAt, trustTier, syncId) VALUES " +
                "(1, 1733356800000, 450, 4, 'woke up once', 1733360000000, 'REPORTED', 'sync-sleep-1')"
        )
        val log = db.query("SELECT durationMinutes, quality, notes FROM sleep_logs WHERE id = 1")
        assertTrue(log.moveToFirst())
        assertEquals(450, log.getInt(0))
        assertEquals(4, log.getInt(1))
        assertEquals("woke up once", log.getString(2))
        log.close()
    }
}
