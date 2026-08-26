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
 * Instrumented migration test for v38 -> v39 - `pantry_receipts` gains `provenance` and
 * `unaccountedCents` (CLAUDE.md section 4 rule 7's 2026-08-26 amendment, ticket 08 -
 * `.scratch/backend-erp/issues/08-receipts-whose-anchors-were-never-stored.md`; see
 * [PantryReceipt]'s and [MIGRATION_38_39]'s own doc comments). `createSql` confirmed against the
 * generated `app/schemas/com.kevin.legion.data.local.CarDatabase/39.json` after a real
 * `compileDebugKotlin -Pnokey` run, byte for byte - not hand-derived, same discipline
 * [CarDatabaseMigration37To38Test] documents.
 *
 * **NOT RUN this session** - `connectedAndroidTest` uninstalls the app and would take Kevin's real
 * data (CLAUDE.md L11, `memory/MEMORY.md`; a prior run in this exact session already destroyed
 * `files/pantry_receipts/`, the very photos ticket 08 is about). Compiled only, confirmed via
 * `compileDebugAndroidTestKotlin -Pnokey`. On-device evidence is deferred to on-device QA.
 */
@RunWith(AndroidJUnit4::class)
class CarDatabaseMigration38To39Test {
    private val dbName = "migration-test-38-39"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CarDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `migrate38To39_existingReceiptRow_backfillsProvenanceLLM_RECONCILED_andLeavesUnaccountedNull`() {
        helper.createDatabase(dbName, 38).apply {
            execSQL(
                "INSERT INTO pantry_receipts (id, store, purchaseDate, currency, totalCents, sourceImagePath, syncId) " +
                    "VALUES (1, 'Costco', 1000, 'USD', 5000, '', 'sync-1')"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 39, true, MIGRATION_38_39)

        val cursor = db.query("SELECT provenance, unaccountedCents FROM pantry_receipts WHERE id = 1")
        assertTrue(cursor.moveToFirst())
        assertEquals("LLM_RECONCILED", cursor.getString(0))
        assertTrue("a pre-existing row must never gain a fabricated unaccounted figure", cursor.isNull(1))
        cursor.close()
    }

    @Test
    fun `migrate38To39_newRowCanCarryUnreconciledProvenanceAndAResidual`() {
        helper.createDatabase(dbName, 38).apply { close() }

        val db = helper.runMigrationsAndValidate(dbName, 39, true, MIGRATION_38_39)

        db.execSQL(
            "INSERT INTO pantry_receipts (id, store, purchaseDate, currency, totalCents, sourceImagePath, syncId, provenance, unaccountedCents) " +
                "VALUES (2, 'Walmart', 2000, 'USD', 12886, '', 'sync-2', 'UNRECONCILED', 802)"
        )

        val cursor = db.query("SELECT provenance, unaccountedCents FROM pantry_receipts WHERE id = 2")
        assertTrue(cursor.moveToFirst())
        assertEquals("UNRECONCILED", cursor.getString(0))
        assertEquals(802L, cursor.getLong(1))
        cursor.close()
    }

    @Test
    fun `migrate38To39_isOtherwiseAdditive_lineItemsTableUntouched`() {
        helper.createDatabase(dbName, 38).apply {
            execSQL(
                "INSERT INTO pantry_receipts (id, store, purchaseDate, currency, totalCents, sourceImagePath, syncId) " +
                    "VALUES (1, 'Costco', 1000, 'USD', 5000, '', 'sync-1')"
            )
            execSQL(
                "INSERT INTO pantry_line_items (receiptId, name, quantity, totalPriceCents) " +
                    "VALUES (1, 'bulk rice', 1.0, 5000)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 39, true, MIGRATION_38_39)

        val cursor = db.query("SELECT name, totalPriceCents FROM pantry_line_items WHERE receiptId = 1")
        assertTrue(cursor.moveToFirst())
        assertEquals("bulk rice", cursor.getString(0))
        assertEquals(5000L, cursor.getLong(1))
        cursor.close()
    }
}
