package com.kevin.legion.ledger

import com.kevin.legion.data.local.LedgerCurrency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [groupAccountBalances] is pure by construction (no Context, no Room) and,
 * per the ticket 12 review, had ZERO tests despite holding the new
 * provisional-delta money math (finding 2) and the sameCard grouping
 * (finding 3). These pin both findings directly, plus finding 4's contract
 * that the function is a pure collapse the caller opts into, never something
 * [LedgerController.accountBalances] applies on its own.
 */
class LedgerControllerTest {

    /**
     * Review finding 2 (BLOCKING at review, now fixed): a cluster where one
     * member states a real printed balance and another is provisional-only
     * must use ONLY the balance-holder's own [AccountBalance.provisionalDeltaCents],
     * never a sibling's - each entry's delta was computed against its own
     * anchor date, and pairing them mismatched would double-count any
     * provisional row dated before the balance's own date.
     *
     * This fixture reproduces the exact failure shape: the printed-balance
     * entry's OWN delta is $30.00 (rows after its own anchor date, already
     * suffix-summed at the DAO layer per the real implementation) and the
     * provisional-only sibling's delta is $75.00 (its anchor is
     * `Long.MIN_VALUE`, so it necessarily sums MORE rows, including ones the
     * printed balance's date already covers). Grouping must produce $30.00,
     * never $75.00 and never their sum.
     */
    @Test
    fun `grouping uses only the balance-holder's own provisional delta, never a sibling's`() {
        val withPrintedBalance = AccountBalance(
            accountId = "4111111111114146",
            currency = LedgerCurrency.USD,
            balanceCents = 50_000L,
            provisionalDeltaCents = 3_000L,
            isProvisional = true,
        )
        val provisionalOnlySibling = AccountBalance(
            accountId = "4146",
            currency = LedgerCurrency.USD,
            balanceCents = null,
            provisionalDeltaCents = 7_500L,
            isProvisional = true,
        )

        val grouped = groupAccountBalances(listOf(withPrintedBalance, provisionalOnlySibling))

        assertEquals(1, grouped.size)
        val result = grouped.first()
        assertEquals(50_000L, result.balanceCents)
        assertEquals(3_000L, result.provisionalDeltaCents)
    }

    /** No provisional rows at all: the representative's delta (zero) survives grouping untouched. */
    @Test
    fun `grouping a cluster with no provisional rows leaves the delta at zero`() {
        val withPrintedBalance = AccountBalance("4111111111114146", LedgerCurrency.USD, 50_000L)
        val provisionalOnlySibling = AccountBalance("4146", LedgerCurrency.USD, null)

        val grouped = groupAccountBalances(listOf(withPrintedBalance, provisionalOnlySibling))

        assertEquals(1, grouped.size)
        assertEquals(0L, grouped.first().provisionalDeltaCents)
        assertEquals(false, grouped.first().isProvisional)
    }

    /**
     * Review finding 3: two accounts sharing a last-4 by pure coincidence of
     * their free-text `accountId` (not a real bank identifier) must NOT
     * merge when their currencies differ. "BOFA-CHECKING" and
     * "DBS-CHECKING" both end "KING" as strings, but they are unrelated
     * accounts in different currencies - collapsing them would both drop an
     * account from the list and mislabel the survivor's currency, which is
     * exactly what BalancesSection's own "Not combined. No exchange rate is
     * applied." promise says never happens.
     */
    @Test
    fun `two accounts with the same last-4 but different currencies never merge`() {
        val usd = AccountBalance("BOFA-CHECKING", LedgerCurrency.USD, 10_000L)
        val sgd = AccountBalance("DBS-CHECKING", LedgerCurrency.SGD, 20_000L)

        val grouped = groupAccountBalances(listOf(usd, sgd))

        assertEquals(2, grouped.size)
        assertTrue(grouped.any { it.accountId == "BOFA-CHECKING" && it.currency == LedgerCurrency.USD })
        assertTrue(grouped.any { it.accountId == "DBS-CHECKING" && it.currency == LedgerCurrency.SGD })
    }

    /** Same last-4, same currency: this is the real ticket 12 §0 case, and it MUST still merge. */
    @Test
    fun `same last-4 and same currency still merge into one row`() {
        val cardPdf = AccountBalance("4111111111114146", LedgerCurrency.USD, 50_000L)
        val cardCsv = AccountBalance("4146", LedgerCurrency.USD, null, provisionalDeltaCents = 0L)

        val grouped = groupAccountBalances(listOf(cardPdf, cardCsv))

        assertEquals(1, grouped.size)
        // The full printed PAN reads better in the UI than a bare last-4 -
        // it's the representative when it carries the printed balance.
        assertEquals("4111111111114146", grouped.first().accountId)
    }

    /** A singleton cluster (no other account shares its last-4) passes through unchanged. */
    @Test
    fun `an account with no matching last-4 passes through as its own cluster`() {
        val solo = AccountBalance("DBS ****8802", LedgerCurrency.SGD, 216_582L)

        val grouped = groupAccountBalances(listOf(solo))

        assertEquals(listOf(solo), grouped)
    }

    /**
     * [AccountBalance.pendingDeltaCents] must NOT be summed across a cluster the way
     * [AccountBalance.provisionalDeltaCents] is - unlike a provisional-delta query, which is
     * anchored per-`accountId` to that row's own statement date and genuinely differs member to
     * member, [com.kevin.legion.data.local.LedgerTransactionDao.pendingDeltaCents] has no date
     * anchor at all: every cluster member sharing the same last-4/currency already carries the
     * IDENTICAL total. Summing them here would multiply a single $75 voice-logged charge by the
     * cluster's own size (2x here) - this pins that it does not.
     */
    @Test
    fun `grouping never multiplies a voice-logged pending delta across cluster members`() {
        val cardPdf = AccountBalance(
            accountId = "4111111111114146",
            currency = LedgerCurrency.USD,
            balanceCents = 50_000L,
            pendingDeltaCents = -7_500L,
            hasPendingRows = true,
        )
        val cardCsv = AccountBalance(
            accountId = "4146",
            currency = LedgerCurrency.USD,
            balanceCents = null,
            pendingDeltaCents = -7_500L,
            hasPendingRows = true,
        )

        val grouped = groupAccountBalances(listOf(cardPdf, cardCsv))

        assertEquals(1, grouped.size)
        assertEquals(-7_500L, grouped.first().pendingDeltaCents)
        assertTrue(grouped.first().hasPendingRows)
    }
}
