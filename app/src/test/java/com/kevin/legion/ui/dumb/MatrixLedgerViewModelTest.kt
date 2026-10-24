package com.kevin.legion.ui.dumb

import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.LedgerTransaction
import com.kevin.legion.ledger.AccountBalance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Matrix ledger: loading, empty, unreadable apart from empty, and unverified said in words. */
class MatrixLedgerViewModelTest {
    private class Source(
        var balances: List<MatrixBalance> = emptyList(),
        var recent: List<MatrixTxn> = emptyList(),
        var balancesThrow: Boolean = false,
        var recentThrow: Boolean = false,
        var sync: String? = null,
    ) : MatrixLedgerSource {
        override suspend fun balances(): List<MatrixBalance> {
            if (balancesThrow) error("Room is closed")
            return balances
        }

        override suspend fun recent(): List<MatrixTxn> {
            if (recentThrow) error("Room is closed")
            return recent
        }

        override fun syncLine(): String? = sync
    }

    private fun model(source: Source) =
        MatrixLedgerViewModel(source, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)

    private fun balance(
        balanceCents: Long? = 44_068,
        provisional: Long = 0,
        pending: Long = 0,
        reconciled: Boolean = true,
        asOf: Long? = 1_792_000_000_000L,
    ) = AccountBalance(
        accountId = "DBS-123456789", currency = LedgerCurrency.SGD, balanceCents = balanceCents, asOfMs = asOf,
        provisionalDeltaCents = provisional, isProvisional = provisional != 0L, hasReconciledRows = reconciled,
        pendingDeltaCents = pending, hasPendingRows = pending != 0L,
    )

    private fun txn(method: IngestMethod, pendingAt: Long? = null, category: String? = "Food", guess: Boolean = false) =
        LedgerTransaction(
            sourceFile = "f", accountId = "DBS-123456789", currency = LedgerCurrency.SGD, txnDate = 1_792_000_000_000L,
            description = "CHECKCARD 0701 COFFEE", amountCents = -450, lineRef = "1", ingestMethod = method,
            category = category, categoryPending = guess, pendingLoggedAt = pendingAt,
        )

    @Test
    fun `loading, empty and unreadable are different sentences for balances and for transactions`() {
        val vm = model(Source())
        assertEquals(MatrixLedgerText.LOADING, MatrixLedgerText.balancesNote(vm.state.value)?.text)
        assertNull(MatrixLedgerText.recentNote(vm.state.value))
        vm.refresh()
        assertEquals(MatrixLedgerText.NO_ACCOUNTS, MatrixLedgerText.balancesNote(vm.state.value)?.text)
        assertEquals(MatrixLedgerText.NO_TRANSACTIONS, MatrixLedgerText.recentNote(vm.state.value)?.text)
        val broken = model(Source(balancesThrow = true, recentThrow = true))
        broken.refresh()
        assertEquals(MatrixLedgerText.BALANCES_UNREADABLE, MatrixLedgerText.balancesNote(broken.state.value)?.text)
        assertEquals(MatrixLedgerText.RECENT_UNREADABLE, MatrixLedgerText.recentNote(broken.state.value)?.text)
        assertEquals(MatrixTone.ALERT, MatrixLedgerText.balancesNote(broken.state.value)?.tone)
    }

    @Test
    fun `the two reads fail apart and a failed refresh keeps the old rows with a stale warning`() {
        val source = Source(balances = listOf(MatrixLedgerText.balanceOf(balance())))
        val vm = model(source)
        vm.refresh()
        source.balancesThrow = true
        source.recent = listOf(MatrixLedgerText.txnOf(txn(IngestMethod.DETERMINISTIC), emptyList()))
        vm.refresh()
        assertTrue(vm.state.value.balancesFailed)
        assertFalse(vm.state.value.recentFailed)
        assertEquals(1, vm.state.value.balances.size)
        assertEquals(MatrixLedgerText.BALANCES_STALE, MatrixLedgerText.balancesNote(vm.state.value)?.text)
        assertNull(MatrixLedgerText.recentNote(vm.state.value))
    }

    @Test
    fun `the engine copy sentence is carried through`() {
        val vm = model(Source(sync = "Showing the phone's copy: the engine could not be reached."))
        vm.refresh()
        assertEquals("Showing the phone's copy: the engine could not be reached.", vm.state.value.syncLine)
    }

    @Test
    fun `a stated balance is shown, with its date, and is not marked unconfirmed`() {
        val shown = MatrixLedgerText.balanceOf(balance())
        assertTrue(shown.title.endsWith("SGD 440.68"))
        assertTrue(shown.title.contains("****6789"))
        assertTrue(shown.detail.startsWith("as of "))
        assertFalse(shown.unconfirmed)
    }

    @Test
    fun `a balance with no printed figure says not stated and never 0 point 00`() {
        val shown = MatrixLedgerText.balanceOf(balance(balanceCents = null, asOf = null))
        assertTrue(shown.title.endsWith("not stated"))
        assertFalse(shown.title.contains("0.00"))
        assertTrue(shown.detail.contains("no balance ever printed for this account"))
    }

    @Test
    fun `provisional and pending activity are each said in words and mark the figure unconfirmed`() {
        val shown = MatrixLedgerText.balanceOf(balance(provisional = -1_000, pending = -12_379))
        assertTrue(shown.unconfirmed)
        assertTrue(shown.detail.contains("includes pending transactions not yet on a statement"))
        assertTrue(shown.detail.contains("you logged as pending, not yet confirmed by the bank"))
        val bare = balance(balanceCents = null, provisional = -1_000, reconciled = false)
        val onlyPending = MatrixLedgerText.balanceOf(bare)
        assertTrue(onlyPending.detail.contains("no statement yet - pending transactions only, unverified"))
    }

    @Test
    fun `an UNRECONCILED row says Unverified in words on its own row`() {
        val row = MatrixLedgerText.txnOf(txn(IngestMethod.UNRECONCILED), emptyList())
        assertTrue(row.unverified)
        assertTrue(row.detail.contains(MatrixLedgerText.UNVERIFIED_ROW))
        assertTrue(row.title.contains("SGD -4.50"))
        assertTrue(row.title.contains("COFFEE"))
        assertFalse(row.title.contains("CHECKCARD"))
    }

    @Test
    fun `a pending row says it is the user's own report and a reconciled row says neither`() {
        val pending = MatrixLedgerText.txnOf(txn(IngestMethod.UNRECONCILED, pendingAt = 5L), emptyList())
        assertTrue(pending.detail.contains(MatrixLedgerText.PENDING_ROW))
        assertFalse(pending.detail.contains(MatrixLedgerText.UNVERIFIED_ROW))
        val clean = MatrixLedgerText.txnOf(txn(IngestMethod.DETERMINISTIC), emptyList())
        assertFalse(clean.unverified)
        assertFalse(clean.detail.contains("Unverified"))
        assertFalse(clean.detail.contains("Pending"))
    }

    @Test
    fun `a category that is still a guess says so, and a missing one says Uncategorized`() {
        val guessed = txn(IngestMethod.DETERMINISTIC, category = "Food", guess = true)
        val guess = MatrixLedgerText.txnOf(guessed, emptyList())
        assertTrue(guess.detail.contains("Food (a guess, not confirmed)"))
        val none = MatrixLedgerText.txnOf(txn(IngestMethod.DETERMINISTIC, category = null), emptyList())
        assertTrue(none.detail.contains("Uncategorized"))
    }
}
