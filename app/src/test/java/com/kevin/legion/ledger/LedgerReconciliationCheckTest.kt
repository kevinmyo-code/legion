package com.kevin.legion.ledger

import com.kevin.legion.data.local.IngestMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for [LedgerReconciliationCheck]'s 2026-08-27 amendment (ticket 12's "RULED"
 * section): a DETERMINISTIC statement may commit on two read anchors (opening, closing) plus the
 * balance-delta check when its bank prints no combined total. The shared corpus
 * (`app/src/test/resources/gate-corpus.json`, exercised by `GateCorpusTest`) is the cross-checked
 * source of truth this arithmetic must agree with; these tests exist to pin the branch's edges
 * directly, including the scope guard that is the amendment's load-bearing half.
 *
 * No Robolectric here - [LedgerReconciliationCheck] is a pure function over `Long`s with no
 * Android surface, unlike the CSV parser it backs.
 */
class LedgerReconciliationCheckTest {

    private val threeGoodLines = listOf(-450L, 300_000L, -150_000L) // sums to 149_550

    @Test
    fun `deterministic statement with no stated total commits when the delta ties out`() {
        val outcome = LedgerReconciliationCheck.check(
            amountsCents = threeGoodLines,
            statedTotalCents = null,
            openingBalanceCents = 500_000L,
            closingBalanceCents = 649_550L, // 649550 - 500000 = 149550 = sum
            provenance = IngestMethod.DETERMINISTIC,
        )
        assertTrue(outcome is LedgerGateOutcome.Committed)
        assertEquals(149_550L, (outcome as LedgerGateOutcome.Committed).sumCents)
    }

    @Test
    fun `deterministic statement with no stated total still quarantines on a bad delta`() {
        // With no printed total, the balance delta is the ONLY anchor left. It must still be
        // enforced - "two anchors is a floor, not a discount" means opening and closing are not
        // optional just because the third figure never existed.
        val outcome = LedgerReconciliationCheck.check(
            amountsCents = threeGoodLines,
            statedTotalCents = null,
            openingBalanceCents = 500_000L,
            closingBalanceCents = 1L, // does not match 500000 + 149550
            provenance = IngestMethod.DETERMINISTIC,
        )
        assertTrue(outcome is LedgerGateOutcome.Quarantined)
    }

    @Test
    fun `llm reconciled statement missing its stated total is quarantined regardless of the delta`() {
        // THE SCOPE GUARD. Same numbers as the passing deterministic case above - the delta ties
        // out perfectly - but the provenance is LLM_RECONCILED, so the two-anchor allowance never
        // applies. Ruling 4 stands exactly as written for the path it was written about.
        val outcome = LedgerReconciliationCheck.check(
            amountsCents = threeGoodLines,
            statedTotalCents = null,
            openingBalanceCents = 500_000L,
            closingBalanceCents = 649_550L,
            provenance = IngestMethod.LLM_RECONCILED,
        )
        assertTrue(outcome is LedgerGateOutcome.Quarantined)
        assertEquals(
            "This statement states no printed total, and only a deterministically parsed " +
                "statement can qualify without one. Nothing was imported.",
            (outcome as LedgerGateOutcome.Quarantined).reason,
        )
    }

    @Test
    fun `unreconciled statement missing its stated total is quarantined the same as llm reconciled`() {
        // The scope guard names DETERMINISTIC as the only qualifying provenance, not "anything but
        // LLM_RECONCILED" - UNRECONCILED must fail the same way. (In practice rule-7 provisional
        // rows never reach this function with an UNRECONCILED provenance and a null total is moot
        // for them since they carry no header at all, but the function's own contract should not
        // depend on a caller never trying.)
        val outcome = LedgerReconciliationCheck.check(
            amountsCents = threeGoodLines,
            statedTotalCents = null,
            openingBalanceCents = 500_000L,
            closingBalanceCents = 649_550L,
            provenance = IngestMethod.UNRECONCILED,
        )
        assertTrue(outcome is LedgerGateOutcome.Quarantined)
    }

    @Test
    fun `deterministic statement that DOES print a total is still held to it`() {
        // A DETERMINISTIC statement that carries a printed total is not exempted from checking it
        // just because the amendment allows others to omit it. Anchor 1 still runs whenever the
        // number is actually present.
        val outcome = LedgerReconciliationCheck.check(
            amountsCents = threeGoodLines,
            statedTotalCents = 999L, // wrong on purpose; real sum is 149550
            openingBalanceCents = 500_000L,
            closingBalanceCents = 649_550L,
            provenance = IngestMethod.DETERMINISTIC,
        )
        assertTrue(outcome is LedgerGateOutcome.Quarantined)
    }

    @Test
    fun `rule 6 still runs before the no-total branch`() {
        // Empty lines with a null total and a self-satisfying delta (closing = opening) must not
        // sneak past the non-empty check just because the amendment introduced a new way to have
        // fewer anchors to check.
        val outcome = LedgerReconciliationCheck.check(
            amountsCents = emptyList(),
            statedTotalCents = null,
            openingBalanceCents = 500_000L,
            closingBalanceCents = 500_000L,
            provenance = IngestMethod.DETERMINISTIC,
        )
        assertTrue(outcome is LedgerGateOutcome.Quarantined)
    }

    @Test
    fun `three-anchor behaviour is unchanged for a statement that prints all three`() {
        val outcome = LedgerReconciliationCheck.check(
            amountsCents = threeGoodLines,
            statedTotalCents = 149_550L,
            openingBalanceCents = 500_000L,
            closingBalanceCents = 649_550L,
            provenance = IngestMethod.LLM_RECONCILED,
        )
        assertTrue(outcome is LedgerGateOutcome.Committed)
    }
}
