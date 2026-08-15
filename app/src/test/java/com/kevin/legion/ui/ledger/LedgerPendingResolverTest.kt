package com.kevin.legion.ui.ledger

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure-logic coverage for [LedgerPendingResolver] - no Android dependency, plain JVM test. */
class LedgerPendingResolverTest {

    @Test
    fun `formats a negative (debit) pending delta as a positive magnitude`() {
        assertEquals(
            "includes 123.79 you logged as pending, not yet confirmed by the bank",
            LedgerPendingResolver.balanceNote(-12379L),
        )
    }

    @Test
    fun `formats a positive (credit) pending delta the same way, still a magnitude`() {
        assertEquals(
            "includes 50.00 you logged as pending, not yet confirmed by the bank",
            LedgerPendingResolver.balanceNote(5000L),
        )
    }

    @Test
    fun `zero still formats without throwing, even though callers gate on hasPendingRows first`() {
        assertEquals(
            "includes 0.00 you logged as pending, not yet confirmed by the bank",
            LedgerPendingResolver.balanceNote(0L),
        )
    }
}
