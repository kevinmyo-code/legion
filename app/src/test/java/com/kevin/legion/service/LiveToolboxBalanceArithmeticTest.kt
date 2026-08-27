package com.kevin.legion.service

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.LedgerTransaction
import com.kevin.legion.engine.RecordStore
import com.kevin.legion.engine.ledger.LedgerAspectSeeder
import com.kevin.legion.engine.ledger.LedgerRecordBridge
import com.kevin.legion.ledger.LedgerController
import com.kevin.legion.ledger.formatMoney
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Ticket 30 (`.scratch/hands-and-senses/issues/30-balance-arithmetic-in-three-places.md`): the
 * guard the 2026-08-07 fix never got. `AccountBalance.availableCents`
 * (`ledger/LedgerController.kt`) exists precisely so no surface re-derives
 * `balanceCents + provisionalDeltaCents + pendingDeltaCents` by hand - the screen's own copy
 * dropped a term once already. `get_balance` and `log_pending_transaction`'s spoken new balance
 * both used to add the terms themselves; this pins both against the property instead.
 *
 * All three terms below are non-zero AND mutually distinct (100_000, 4_321, -777) so that
 * dropping any one term, or transposing two, changes the asserted total - a fixture with a zero
 * or repeated term could pass by luck on a formula missing or duplicating a term.
 */
@RunWith(RobolectricTestRunner::class)
class LiveToolboxBalanceArithmeticTest {
    private val context = RuntimeEnvironment.getApplication()
    private val accountId = "BOFA ****9911"

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    /**
     * Seeds all three terms of the formula distinctly:
     * - a posted balance of 100_000 cents, from a DETERMINISTIC row dated day 1 (the anchor)
     * - a provisional delta of 4_321 cents, from an UNRECONCILED row dated AFTER the anchor with
     *   no [LedgerTransaction.pendingLoggedAt] set
     * - a pending delta, logged through [LedgerController.logPendingTransaction] itself (the same
     *   path `log_pending_transaction` calls), of -777 cents
     */
    private suspend fun seedThreeDistinctTerms() {
        val db = CarDatabase.getDatabase(context)
        val schema = LedgerAspectSeeder.ensureSeeded(context)
        val recordStore = RecordStore(db.engineRecordDao(), db.fieldDefDao(), db.recordTypeDao())
        for (t in listOf(
            LedgerTransaction(
                sourceFile = "eStmt.pdf",
                accountId = accountId,
                currency = LedgerCurrency.USD,
                txnDate = 1_000_000L,
                description = "OPENING ANCHOR",
                amountCents = 100_000L,
                balanceCents = 100_000L,
                lineRef = "1",
                ingestMethod = IngestMethod.DETERMINISTIC,
            ),
            LedgerTransaction(
                sourceFile = "cardExport.csv",
                accountId = accountId,
                currency = LedgerCurrency.USD,
                txnDate = 2_000_000L,
                description = "PROVISIONAL CARD ACTIVITY",
                amountCents = 4_321L,
                balanceCents = null,
                lineRef = "2",
                ingestMethod = IngestMethod.UNRECONCILED,
            ),
        )) {
            // Cutover 3: LedgerController reads through the engine now - a fixture written straight
            // to the legacy ledgerTransactionDao() is invisible to it. Writes through RecordStore
            // instead, the same door IngestPipeline itself now writes through.
            recordStore.create(
                recordTypeId = schema.transaction.recordTypeId,
                fieldValues = LedgerRecordBridge.fieldValuesFor(t, schema.transaction.fieldIds),
                provenance = LedgerRecordBridge.provenanceFor(t.ingestMethod),
                now = t.txnDate,
                guid = t.syncId,
            )
        }
        LedgerController.logPendingTransaction(
            context = context,
            accountId = accountId,
            currency = LedgerCurrency.USD,
            description = "Coffee shop",
            amountCents = -777L,
            txnDate = 3_000_000L,
        )
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
    fun `get_balance's available_balance_cents equals AccountBalance availableCents, never a hand sum`() = runBlocking {
        seedThreeDistinctTerms()

        val expected = LedgerController.accountBalances(context).single { it.accountId == accountId }.availableCents
        // Sanity: the fixture really is non-luck-provable - all three terms distinct and non-zero.
        assertEquals(100_000L + 4_321L - 777L, expected)

        val result = LiveToolbox.dispatch(context, "get_balance", JSONObject())!!
        val entry = result.getJSONObject("balances").getJSONObject(accountId)
        assertEquals(expected, entry.getLong("available_balance_cents"))
    }

    @Test
    fun `log_pending_transaction's spoken new balance equals AccountBalance availableCents plus only the new amount`() =
        runBlocking {
            seedThreeDistinctTerms()
            // The pre-log AccountBalance already carries the -777 pending term seeded above -
            // logging one MORE pending amount must add only that new amount to availableCents,
            // never re-sum balanceCents/provisionalDeltaCents/pendingDeltaCents from scratch.
            val beforeAvailable =
                LedgerController.accountBalances(context).single { it.accountId == accountId }.availableCents
            val newAmountCents = 2_500L

            // direction=credit so pendingAmountCents keeps the sign positive - a distinct,
            // unambiguous addend rather than one that could be confused with a dropped term.
            val result = LiveToolbox.dispatch(
                context,
                "log_pending_transaction",
                JSONObject()
                    .put("account", accountId)
                    .put("description", "Newly logged charge")
                    .put("amount", newAmountCents / 100.0)
                    .put("direction", "credit")
                    .put("date", "01/12/2026"),
            )!!

            assertEquals(true, result.getBoolean("success"))
            val expectedNewAvailable = beforeAvailable + newAmountCents
            val expectedFigure = formatMoney(expectedNewAvailable, LedgerCurrency.USD)
            assertEquals(
                "spoken figure must read AccountBalance.availableCents plus only the new amount",
                " New available balance for $accountId: $expectedFigure. This is your own report, not yet confirmed by the bank.",
                result.getString("message").substringAfter("pending on $accountId."),
            )
        }
}
