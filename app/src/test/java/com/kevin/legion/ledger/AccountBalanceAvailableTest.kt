package com.kevin.legion.ledger

import com.kevin.legion.data.local.LedgerCurrency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for [AccountBalance.availableCents], added 2026-08-07 after
 * the figure was wrong ON KEVIN'S PHONE while every existing test passed.
 *
 * `get_balance` and `ui.ledger.AccountBalanceRow` each computed the available
 * figure independently, and the UI's copy omitted [AccountBalance.pendingDeltaCents].
 * Kevin logged three pending charges totalling 123.79; the note beneath the
 * figure changed to mention them and the headline stayed at 440.68 while his
 * bank showed 316.89. The data was correct throughout - only one of the two
 * call sites was short a term.
 *
 * The real fix is that there is now ONE definition with two callers. These
 * tests pin that definition.
 */
class AccountBalanceAvailableTest {

    private fun balance(
        posted: Long?,
        provisional: Long = 0L,
        pending: Long = 0L,
    ) = AccountBalance(
        accountId = "Kevin debit",
        currency = LedgerCurrency.USD,
        balanceCents = posted,
        provisionalDeltaCents = provisional,
        isProvisional = provisional != 0L,
        hasReconciledRows = posted != null,
        pendingDeltaCents = pending,
        hasPendingRows = pending != 0L,
    )

    @Test
    fun `the exact case from Kevin's phone`() {
        // Posted 440.68 (the real 6 August closing balance), minus three
        // voice-logged pending charges: VPN24 8.99, Great Clips 29.00,
        // Petco 85.80 = 123.79. His bank showed 316.89.
        val b = balance(posted = 44068, pending = -12379)
        assertEquals(31689L, b.availableCents)
        assertTrue(b.hasAnyFigure)
        assertTrue("pending activity must force the unconfirmed wording", b.isUnconfirmed)
    }

    @Test
    fun `both deltas are included, not just one`() {
        // The bug was silent because with only ONE kind of unposted activity
        // present, a sum missing the other term still looked plausible.
        val b = balance(posted = 100_00, provisional = -10_00, pending = -5_00)
        assertEquals(85_00L, b.availableCents)
    }

    @Test
    fun `a posted balance with nothing unposted is not marked unconfirmed`() {
        val b = balance(posted = 44068)
        assertEquals(44068L, b.availableCents)
        assertTrue(b.hasAnyFigure)
        assertFalse(b.isUnconfirmed)
    }

    @Test
    fun `a null posted balance returns the unposted movement alone`() {
        // Bank of America's card layout never prints a running balance, so a
        // fully reconciled card account carries balanceCents == null forever.
        // That is NOT zero, and the movement still has to be reported.
        val b = balance(posted = null, pending = -2500)
        assertEquals(-2500L, b.availableCents)
        assertTrue(b.hasAnyFigure)
        assertTrue(b.isUnconfirmed)
    }

    @Test
    fun `nothing stated at all is a distinct state from zero`() {
        val b = balance(posted = null)
        assertFalse("must render 'not stated', never 0.00", b.hasAnyFigure)
    }

    @Test
    fun `deltas that net to zero read as no pending rows - known accepted edge case`() {
        // AccountBalance's doc comment already calls this out: hasPendingRows
        // is `pendingDeltaCents != 0`, so a refund exactly cancelling a charge
        // reads as "none". Pinned as a test so the behaviour is deliberate and
        // visible rather than discovered later as a surprise.
        val b = balance(posted = 44068, pending = 0L)
        assertFalse(b.hasPendingRows)
        assertEquals(44068L, b.availableCents)
    }
}
