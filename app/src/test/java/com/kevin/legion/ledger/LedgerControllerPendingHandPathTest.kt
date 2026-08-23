package com.kevin.legion.ledger

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Command-center ticket 11: `log_pending_transaction` by hand. `ui/ledger/LedgerWriteDialogs.kt`'s
 * `AddPendingTransactionDialog` calls [LedgerController.logPendingTransaction] directly - the SAME
 * function `service/LiveToolbox.kt`'s `logPendingTransaction` dispatch calls after its own account
 * resolution and cents conversion. This pins that shared function's own write shape (Robolectric,
 * same fixture shape [com.kevin.legion.service.LiveToolboxAdvisorTest]/
 * [com.kevin.legion.data.local.AdvisorAdviceDaoTest] already use for a Room-backed hands-path test)
 * so a change to what gets written is caught regardless of which caller exercises it.
 */
@RunWith(RobolectricTestRunner::class)
class LedgerControllerPendingHandPathTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    @Test
    fun `logPendingTransaction writes an UNRECONCILED voice-sourced row with the given sign and account`() = runBlocking {
        LedgerController.logPendingTransaction(
            context = context,
            accountId = "BOFA ****4471",
            currency = LedgerCurrency.USD,
            description = "Coffee shop",
            amountCents = -450L,
            txnDate = 1_000_000L,
        )

        val rows = CarDatabase.getDatabase(context).ledgerTransactionDao().pendingRows()
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals("BOFA ****4471", row.accountId)
        assertEquals(LedgerCurrency.USD, row.currency)
        assertEquals("Coffee shop", row.description)
        assertEquals(-450L, row.amountCents)
        // The tags every voice-logged row carries, unaffected by which caller (voice or hand)
        // supplied the arguments - see LedgerController.logPendingTransaction's own doc comment.
        assertEquals("voice", row.sourceFile)
        assertEquals(IngestMethod.UNRECONCILED, row.ingestMethod)
        assertNull("nothing printed this row, so balanceCents must stay null", row.balanceCents)
        assertTrue("a voice-logged pending row is never mistaken for a file-derived one", row.lineRef.startsWith("voice:"))
    }

    @Test
    fun `a credit (signedPendingCents applied by the caller) lands positive`() = runBlocking {
        // AddPendingTransactionDialog applies the sign itself via `signedPendingCents` before
        // calling this function (see ui/ledger/LedgerWriteDialogsTest.kt for that half) - this
        // pins that logPendingTransaction stores whatever signed Long it is given, unmodified.
        LedgerController.logPendingTransaction(
            context = context,
            accountId = "BOFA ****4471",
            currency = LedgerCurrency.USD,
            description = "Refund",
            amountCents = 4250L,
            txnDate = 1_000_000L,
        )

        val row = CarDatabase.getDatabase(context).ledgerTransactionDao().pendingRows().single()
        assertEquals(4250L, row.amountCents)
    }
}
