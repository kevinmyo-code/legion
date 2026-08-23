package com.kevin.legion.ui.ledger

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Command-center ticket 11: pure logic behind [AddPendingTransactionDialog] - `log_pending_transaction`
 * by hand. [signedPendingCents] is the one bit of arithmetic this dialog owns that the voice tool's
 * own pure helper ([com.kevin.legion.ledger.pendingAmountCents]) doesn't directly cover (that
 * function takes a `Double`; this dialog parses straight to `Long` cents via [parseDollarsToCents],
 * already pinned by [LedgerSetTargetParserTest]) - pinned here against the SAME sign convention
 * [com.kevin.legion.ledger.LedgerPendingLogTest] already asserts for [com.kevin.legion.ledger.pendingAmountCents],
 * so the two can never silently disagree on which direction is negative.
 */
class LedgerWriteDialogsTest {

    @Test
    fun `debit is negative`() {
        assertEquals(-4250L, signedPendingCents(4250L, "debit"))
    }

    @Test
    fun `credit is positive`() {
        assertEquals(4250L, signedPendingCents(4250L, "credit"))
    }

    @Test
    fun `direction match is case-insensitive, matching pendingAmountCents own convention`() {
        assertEquals(500L, signedPendingCents(500L, "CREDIT"))
        assertEquals(500L, signedPendingCents(500L, "Credit"))
    }

    @Test
    fun `an unrecognised direction defaults to debit, same as pendingAmountCents`() {
        assertEquals(-500L, signedPendingCents(500L, ""))
        assertEquals(-500L, signedPendingCents(500L, "not a direction"))
    }
}
