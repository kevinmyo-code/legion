package com.kevin.legion.service

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.LedgerTransaction
import com.kevin.legion.engine.RecordStore
import com.kevin.legion.engine.ledger.LedgerAspectSeeder
import com.kevin.legion.engine.ledger.LedgerRecordBridge
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * `get_monthly_spend` (2026-08-13) - the voice path's own copy of the Money screen's US BUDGET
 * disclosure. Pins that the tool's response carries the SAME own-account-movements caveat the
 * screen states (CLAUDE.md §4 rule 7), never a total that quietly omits what the screen discloses.
 * Same Robolectric-plus-Room shape as [LiveToolboxCurrencyTest].
 */
@RunWith(RobolectricTestRunner::class)
class LiveToolboxMonthlySpendTest {
    private val context = RuntimeEnvironment.getApplication()
    private var nextId = 1L

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    private fun txn(accountId: String, amountCents: Long, description: String, category: String? = null) =
        LedgerTransaction(
            sourceFile = "stmt.pdf",
            accountId = accountId,
            currency = LedgerCurrency.USD,
            txnDate = System.currentTimeMillis(),
            description = description,
            amountCents = amountCents,
            lineRef = "line-${nextId++}",
            ingestMethod = IngestMethod.DETERMINISTIC,
            category = category,
        )

    /** Cutover 3: [LedgerController] reads through the engine now - see
     * [com.kevin.legion.advisor.digest.CredDigestBuilderTest]'s identical helper for the reasoning. */
    private suspend fun insertEngineTransactions(vararg transactions: LedgerTransaction) {
        val db = CarDatabase.getDatabase(context)
        val schema = LedgerAspectSeeder.ensureSeeded(context)
        val recordStore = RecordStore(db.engineRecordDao(), db.fieldDefDao(), db.recordTypeDao())
        for (t in transactions) {
            recordStore.create(
                recordTypeId = schema.transaction.recordTypeId,
                fieldValues = LedgerRecordBridge.fieldValuesFor(t, schema.transaction.fieldIds),
                provenance = LedgerRecordBridge.provenanceFor(t.ingestMethod),
                now = t.txnDate,
                guid = t.syncId,
            )
        }
    }

    @After
    fun drainRoomInvalidationTracker() {
        // A DAO write anywhere in this test can schedule a Room InvalidationTracker refresh
        // on ArchTaskExecutor's disk-IO pool. If that refresh is still queued or running when
        // this test method returns, it races Robolectric's own per-@Test-METHOD native SQLite
        // reset and throws "Illegal connection pointer" on a background thread - uncaught, and
        // blamed by kotlinx-coroutines-test on whatever runTest starts next, not on this class.
        // Draining here, before Robolectric ever gets a chance to reset, is the fix - see
        // RoomTestReset's own class doc comment
        // (.scratch/hardening/issues/13-the-suite-is-green-by-luck.md) for the full account.
        RoomTestReset.drainArchDiskIoPool()
    }


    @Test
    fun `an own-account card payment is excluded from the total and disclosed in the note`() = runBlocking {
        insertEngineTransactions(
            txn("4111111111117823", -5_000, "WALMART SUPERCENTER", category = "Shopping"),
            txn("4111111115042", -1300_00, "Online Banking payment to CRD 7823 Confirmation# 0649409616", category = "Shopping"),
        )

        val result = LiveToolbox.dispatch(context, "get_monthly_spend", JSONObject())!!

        assertTrue(result.getBoolean("success"))
        assertEquals(50_00L, result.getLong("total_spent_cents"))
        assertEquals(1, result.getInt("excluded_own_account_movements_count"))
        assertEquals(1300_00L, result.getLong("excluded_own_account_movements_cents"))
        assertTrue(result.getString("note").contains("1 transaction"))
        assertTrue(result.getString("note").contains("excluded from spend"))
    }

    @Test
    fun `a Zelle payment to a person is never excluded and the disclosure reads zero`() = runBlocking {
        insertEngineTransactions(
            txn("4111111111117823", -5_000, "WALMART SUPERCENTER", category = "Shopping"),
            txn("4111111115042", -40_000, "Zelle payment to  R Alan Cole US Conf# b4nb0qacg", category = "Shopping"),
        )

        val result = LiveToolbox.dispatch(context, "get_monthly_spend", JSONObject())!!

        assertTrue(result.getBoolean("success"))
        // Both rows are real spend - the Zelle payment must still be counted.
        assertEquals(450_00L, result.getLong("total_spent_cents"))
        assertEquals(0, result.getInt("excluded_own_account_movements_count"))
        assertTrue(result.getString("note").contains("No own-account movements"))
    }

    @Test
    fun `the response always carries a currency code, matching the currency audit`() = runBlocking {
        val result = LiveToolbox.dispatch(context, "get_monthly_spend", JSONObject())!!
        assertTrue(result.getBoolean("success"))
        assertEquals("USD", result.getString("currency"))
    }

    @Test
    fun `an empty month is reported verified with nothing excluded, never a lie about hidden data`() = runBlocking {
        val result = LiveToolbox.dispatch(context, "get_monthly_spend", JSONObject())!!
        assertTrue(result.getBoolean("success"))
        assertEquals(0L, result.getLong("total_spent_cents"))
        assertFalse(result.isNull("verified"))
    }
}
