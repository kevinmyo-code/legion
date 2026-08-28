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
 * Instrumented migration test for v43 -> v44 - `pantry_receipts` gains
 * `subtotalCents`/`taxCents`/`otherChargesCents`
 * (`.scratch/backend-erp/issues/15-engine-retirement-sequence.md`, the coordinator-authorised
 * follow-up to engine retirement step 2). Three additive `ALTER TABLE ADD COLUMN`s, same shape as
 * [CarDatabaseMigration42To43Test] - see [MIGRATION_43_44]'s own doc comment for why the legacy
 * table genuinely needed these columns (not a cosmetic addition: it is what stops
 * `PantryController.writeReceipt`'s repoint onto this table from silently discarding the
 * reconciliation gate's own inputs - the exact damage CLAUDE.md section 4 rule 7's 2026-08-26
 * amendment, ticket 08, describes for rows that already lost them).
 *
 * **NOT RUN this session** - `connectedAndroidTest` uninstalls the app and would take Kevin's real
 * data (CLAUDE.md L11, `memory/MEMORY.md`). Compiled only, confirmed via
 * `compileDebugAndroidTestKotlin -Pnokey`. On-device evidence is deferred to on-device QA, same
 * posture as [CarDatabaseMigration42To43Test].
 */
@RunWith(AndroidJUnit4::class)
class CarDatabaseMigration43To44Test {
    private val dbName = "migration-test-43-44"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CarDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `migrate43To44_existingRowsBackfillAnchorsToNull`() {
        helper.createDatabase(dbName, 43).apply {
            execSQL(
                "INSERT INTO pantry_receipts (id, store, purchaseDate, currency, totalCents, sourceImagePath, syncId, provenance, unaccountedCents) " +
                    "VALUES (1, 'Trader Joe''s', 1000, 'USD', 500, '/data/pantry_receipts/1.jpg', 'guid-1', 'LLM_RECONCILED', NULL)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 44, true, MIGRATION_43_44)

        val cursor = db.query("SELECT subtotalCents, taxCents, otherChargesCents FROM pantry_receipts WHERE id = 1")
        assertTrue(cursor.moveToFirst())
        // A pre-v44 row never captured these anchors - null must mean "predates this column,"
        // never a fabricated zero (the same "null means not printed, never not checked" posture
        // PantryReceipt's own class doc states for unaccountedCents).
        assertTrue(cursor.isNull(0))
        assertTrue(cursor.isNull(1))
        assertTrue(cursor.isNull(2))
        cursor.close()
    }

    @Test
    fun `migrate43To44_canInsertAReceiptWithAllThreeAnchors`() {
        helper.createDatabase(dbName, 43).apply { close() }

        val db = helper.runMigrationsAndValidate(dbName, 44, true, MIGRATION_43_44)

        db.execSQL(
            "INSERT INTO pantry_receipts (id, store, purchaseDate, currency, totalCents, sourceImagePath, syncId, provenance, unaccountedCents, subtotalCents, taxCents, otherChargesCents) " +
                "VALUES (2, 'Walmart', 2000, 'USD', 12936, '/data/pantry_receipts/2.jpg', 'guid-2', 'LLM_RECONCILED', NULL, 12084, 802, 50)"
        )
        val cursor = db.query("SELECT subtotalCents, taxCents, otherChargesCents FROM pantry_receipts WHERE id = 2")
        assertTrue(cursor.moveToFirst())
        assertEquals(12084L, cursor.getLong(0))
        assertEquals(802L, cursor.getLong(1))
        assertEquals(50L, cursor.getLong(2))
        cursor.close()
    }

    @Test
    fun `migrate43To44_isOtherwiseAdditive_everyOtherColumnUnchanged`() {
        helper.createDatabase(dbName, 43).apply {
            execSQL(
                "INSERT INTO pantry_receipts (id, store, purchaseDate, currency, totalCents, sourceImagePath, syncId, provenance, unaccountedCents) " +
                    "VALUES (1, 'Trader Joe''s', 1000, 'USD', 500, '/data/pantry_receipts/1.jpg', 'guid-1', 'LLM_RECONCILED', NULL)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 44, true, MIGRATION_43_44)

        val cursor = db.query("SELECT store, totalCents, provenance FROM pantry_receipts WHERE id = 1")
        assertTrue(cursor.moveToFirst())
        assertEquals("Trader Joe's", cursor.getString(0))
        assertEquals(500L, cursor.getLong(1))
        assertEquals("LLM_RECONCILED", cursor.getString(2))
        cursor.close()
    }
}
