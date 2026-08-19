package com.kevin.legion.ledger

import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.LedgerTransaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure logic behind the voice-logged-pending-transactions tools
 * (`service/LiveToolbox.kt`'s `log_pending_transaction`/`clear_pending_transaction`), pinned here
 * rather than through Robolectric - no Context, no Room, same posture [LedgerControllerTest]
 * already uses for [groupAccountBalances].
 */
class LedgerPendingLogTest {

    // ----------------------------------------------------------- pendingAmountCents

    @Test
    fun `debit rounds to cents and negates`() {
        assertEquals(-4000L, pendingAmountCents(40.0, "debit"))
        assertEquals(-1250L, pendingAmountCents(12.50, "debit"))
    }

    @Test
    fun `credit rounds to cents and stays positive`() {
        assertEquals(4000L, pendingAmountCents(40.0, "credit"))
    }

    @Test
    fun `no direction given defaults to debit (negative)`() {
        // LiveToolbox itself defaults the JSON arg to "debit" before calling this - this pins
        // that an unrecognized/blank direction string also falls through to debit rather than
        // silently reading as a credit, since "not credit" is the only two-way branch this takes.
        assertEquals(-500L, pendingAmountCents(5.0, ""))
    }

    @Test
    fun `direction match is case-insensitive`() {
        assertEquals(500L, pendingAmountCents(5.0, "CREDIT"))
        assertEquals(500L, pendingAmountCents(5.0, "Credit"))
    }

    @Test
    fun `rounds half-cent amounts the same way Math_round does`() {
        // 12.345 -> 1234.5 cents -> Math.round -> 1235 (round-half-up)
        assertEquals(-1235L, pendingAmountCents(12.345, "debit"))
    }

    @Test
    fun `zero is rejected`() {
        assertNull(pendingAmountCents(0.0, "debit"))
    }

    @Test
    fun `negative amount is rejected`() {
        // Direction is the ONLY way to express a credit - a negative "amount" must never be
        // reinterpreted as one.
        assertNull(pendingAmountCents(-40.0, "debit"))
        assertNull(pendingAmountCents(-40.0, "credit"))
    }

    @Test
    fun `NaN and infinite amounts are rejected`() {
        assertNull(pendingAmountCents(Double.NaN, "debit"))
        assertNull(pendingAmountCents(Double.POSITIVE_INFINITY, "debit"))
    }

    @Test
    fun `an absurd transcription-scale amount is rejected`() {
        assertNull(pendingAmountCents(1_000_001.0, "debit")) // 100_000_100 cents > the 100_000_000 guard
        assertTrue(pendingAmountCents(999_999.99, "debit") != null) // just under the guard is fine
    }

    // ------------------------------------------------------- resolveAccountForPending

    private val bofa = AccountBalance("BOFA ****4471", LedgerCurrency.USD, 100_00L)
    private val dbs = AccountBalance("DBS ****8802", LedgerCurrency.SGD, 200_00L)

    @Test
    fun `a unique substring match resolves`() {
        val result = resolveAccountForPending(listOf(bofa, dbs), "bofa")
        assertEquals(PendingAccountResolution.Resolved(bofa), result)
    }

    @Test
    fun `a last-4 match resolves via sameCard`() {
        val result = resolveAccountForPending(listOf(bofa, dbs), "4471")
        assertEquals(PendingAccountResolution.Resolved(bofa), result)
    }

    @Test
    fun `no match at all never fabricates an account`() {
        val result = resolveAccountForPending(listOf(bofa, dbs), "chase")
        assertEquals(PendingAccountResolution.NoMatch, result)
    }

    @Test
    fun `an empty accountId list is always NoMatch`() {
        assertEquals(PendingAccountResolution.NoMatch, resolveAccountForPending(emptyList(), ""))
        assertEquals(PendingAccountResolution.NoMatch, resolveAccountForPending(emptyList(), "bofa"))
    }

    @Test
    fun `a blank account name with exactly one account on file resolves to it`() {
        val result = resolveAccountForPending(listOf(bofa), "")
        assertEquals(PendingAccountResolution.Resolved(bofa), result)
    }

    @Test
    fun `a blank account name with more than one account on file is ambiguous`() {
        val result = resolveAccountForPending(listOf(bofa, dbs), "")
        assertTrue(result is PendingAccountResolution.Ambiguous)
        assertEquals(setOf(bofa, dbs), (result as PendingAccountResolution.Ambiguous).candidates.toSet())
    }

    @Test
    fun `a substring matching two accounts is ambiguous, never picks one silently`() {
        val chaseOne = AccountBalance("CHASE ****1111", LedgerCurrency.USD, 1L)
        val chaseTwo = AccountBalance("CHASE ****2222", LedgerCurrency.USD, 2L)
        val result = resolveAccountForPending(listOf(chaseOne, chaseTwo), "chase")
        assertTrue(result is PendingAccountResolution.Ambiguous)
        assertEquals(2, (result as PendingAccountResolution.Ambiguous).candidates.size)
    }

    // ------------------------------------------------------- matchPendingByDescription

    private fun pendingRow(id: Long, description: String) = LedgerTransaction(
        id = id,
        sourceFile = "voice",
        accountId = "BOFA ****4471",
        currency = LedgerCurrency.USD,
        txnDate = 0L,
        description = description,
        amountCents = -1000L,
        balanceCents = null,
        lineRef = "voice:$id",
        ingestMethod = IngestMethod.UNRECONCILED,
        pendingLoggedAt = 1L,
    )

    @Test
    fun `a unique case-insensitive substring match resolves`() {
        val rows = listOf(pendingRow(1, "Hardware store"), pendingRow(2, "Coffee"))
        val result = matchPendingByDescription(rows, "hardware")
        assertEquals(PendingClearMatch.Resolved(rows[0]), result)
    }

    @Test
    fun `no match at all is NoMatch`() {
        val rows = listOf(pendingRow(1, "Hardware store"))
        assertEquals(PendingClearMatch.NoMatch, matchPendingByDescription(rows, "groceries"))
    }

    @Test
    fun `an empty pending list is always NoMatch`() {
        assertEquals(PendingClearMatch.NoMatch, matchPendingByDescription(emptyList(), "anything"))
    }

    @Test
    fun `two rows matching the same substring is ambiguous`() {
        val rows = listOf(pendingRow(1, "Coffee at Blue Bottle"), pendingRow(2, "Coffee at Peets"))
        val result = matchPendingByDescription(rows, "coffee")
        assertTrue(result is PendingClearMatch.Ambiguous)
        assertEquals(2, (result as PendingClearMatch.Ambiguous).candidates.size)
    }
}
