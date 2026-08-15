package com.kevin.legion.ledger

import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.LedgerTransaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises [analyzeTransfers] against the nine numbered cases in
 * `.scratch/ledger-pnl/issues/01-entity-profit-and-loss.md`'s Tests section.
 * Plain JUnit, no Room, no Android - [analyzeTransfers] is pure by
 * construction, same posture as [LedgerDedupTest] against [resolveDedup].
 *
 * Every fixture below is INVENTED (never Kevin's real rows), per this
 * ticket's own instruction - the shapes mirror the real trap (a checking
 * account paying a card, worded differently by each bank) without using any
 * of his actual numbers.
 */
class LedgerTransfersTest {

    private var nextId = 1L

    /** One transaction fixture with a fresh, auto-incrementing id - [analyzeTransfers]'s tie-break is keyed on id, so every fixture needs a real, distinct one, unlike [LedgerDedupTest]'s fixtures. */
    private fun txn(
        accountId: String,
        amountCents: Long,
        txnDate: Long = DAY_1,
        description: String = "GENERIC",
    ) = LedgerTransaction(
        id = nextId++,
        sourceFile = "statement.pdf",
        accountId = accountId,
        currency = LedgerCurrency.USD,
        txnDate = txnDate,
        description = description,
        amountCents = amountCents,
        lineRef = "line",
        ingestMethod = IngestMethod.DETERMINISTIC,
    )

    @Test
    fun `1 - two rows, different accounts, opposite equal amounts, two days apart, both excluded matched`() {
        val a = txn("checking", 1300_00, txnDate = DAY_1, description = "PAYMENT TO CARD")
        val b = txn("card", -1300_00, txnDate = DAY_1 + 2 * DAY_MS, description = "PAYMENT FROM CHK")

        val result = analyzeTransfers(inPeriod = listOf(a, b), pairingWindow = listOf(a, b))

        assertEquals(0, result.operating.size)
        assertEquals(2, result.excluded.size)
        assertTrue(result.excluded.all { it.reason == ExclusionReason.MATCHED_TRANSFER })
        assertEquals(b.id, result.excluded.first { it.txn.id == a.id }.pairedWith)
        assertEquals(a.id, result.excluded.first { it.txn.id == b.id }.pairedWith)
    }

    @Test
    fun `2 - same amount, same account, is not a pair`() {
        val a = txn("checking", 500_00, description = "MOVE")
        val b = txn("checking", -500_00, description = "MOVE BACK")

        val result = analyzeTransfers(inPeriod = listOf(a, b), pairingWindow = listOf(a, b))

        // Neither row has a partner (same account is excluded from pairing),
        // and neither description matches a transfer keyword, so both remain
        // operating rows.
        assertEquals(2, result.operating.size)
        assertEquals(0, result.excluded.size)
    }

    @Test
    fun `3 - same amount, opposite sign, 30 days apart, not paired at the 5-day default`() {
        val a = txn("checking", 200_00, txnDate = DAY_1, description = "GENERIC A")
        val b = txn("card", -200_00, txnDate = DAY_1 + 30 * DAY_MS, description = "GENERIC B")

        val result = analyzeTransfers(inPeriod = listOf(a, b), pairingWindow = listOf(a, b))

        assertEquals(2, result.operating.size)
        assertEquals(0, result.excluded.size)
    }

    @Test
    fun `4 - three rows plus50 minus50 minus50, exactly one pair, one row survives`() {
        val a = txn("checking", 50_00, txnDate = DAY_1)
        val b = txn("card", -50_00, txnDate = DAY_1)
        val c = txn("savings", -50_00, txnDate = DAY_1)

        val result = analyzeTransfers(inPeriod = listOf(a, b, c), pairingWindow = listOf(a, b, c))

        assertEquals(1, result.operating.size)
        assertEquals(2, result.excluded.size)
        assertTrue(result.excluded.all { it.reason == ExclusionReason.MATCHED_TRANSFER })
    }

    @Test
    fun `5 - two candidate partners at 1 day and 4 days, pairs with the 1-day one`() {
        val source = txn("checking", 100_00, txnDate = DAY_1)
        val near = txn("card", -100_00, txnDate = DAY_1 + 1 * DAY_MS)
        val far = txn("savings", -100_00, txnDate = DAY_1 + 4 * DAY_MS)

        val result = analyzeTransfers(inPeriod = listOf(source, near, far), pairingWindow = listOf(source, near, far))

        val sourceExclusion = result.excluded.first { it.txn.id == source.id }
        assertEquals(near.id, sourceExclusion.pairedWith)
        // The far candidate is left unpaired and survives as operating.
        assertEquals(1, result.operating.size)
        assertEquals(far.id, result.operating.single().id)
    }

    @Test
    fun `6 - a partner outside the month but inside the pairing window - the in-period row excludes, the out-of-period row never appears`() {
        val inPeriodRow = txn("checking", 1300_00, txnDate = DAY_1, description = "TRANSFER TO SAV")
        // Outside the reporting period (a different, later month) but still
        // within a maxDaysApart=5 pairing window of inPeriodRow.
        val outOfPeriodPartner = txn("savings", -1300_00, txnDate = DAY_1 + 3 * DAY_MS, description = "TRANSFER FROM CHK")

        val result = analyzeTransfers(
            inPeriod = listOf(inPeriodRow),
            pairingWindow = listOf(inPeriodRow, outOfPeriodPartner),
        )

        assertEquals(0, result.operating.size)
        assertEquals(1, result.excluded.size)
        assertEquals(ExclusionReason.MATCHED_TRANSFER, result.excluded.single().reason)
        assertEquals(outOfPeriodPartner.id, result.excluded.single().pairedWith)
        // The out-of-period row must never surface in either list - only
        // inPeriod rows are ever returned.
        assertTrue(result.operating.none { it.id == outOfPeriodPartner.id })
        assertTrue(result.excluded.none { it.txn.id == outOfPeriodPartner.id })
    }

    /**
     * Kevin's 2026-08-07 decision, reversing this case's old assertion: a wording-only guess with no
     * confirming partner is FLAGGED, never dropped from spend. The other statement not being imported
     * yet is routine, not proof the dollar didn't leave - silently excluding it on wording alone would
     * understate spend with no signal, exactly the failure mode CLAUDE.md §4 rule 6 names for a
     * reconciliation gate and this repeats here for a transfer guess.
     */
    @Test
    fun `7 - unpaired PAYMENT FROM SAV is flagged as a suspected transfer but still counts as spend`() {
        val unpaired = txn("card", 1300_00, description = "PAYMENT FROM SAV 1490 CONF#v1ikbyqeg")

        val result = analyzeTransfers(inPeriod = listOf(unpaired), pairingWindow = listOf(unpaired))

        // Still counted - not silently dropped just because it looks like a transfer.
        assertEquals(1, result.operating.size)
        assertEquals(unpaired.id, result.operating.single().id)
        // But flagged, so a screen CAN say "looks like a transfer, unconfirmed".
        assertEquals(1, result.excluded.size)
        assertEquals(ExclusionReason.SUSPECTED_TRANSFER, result.excluded.single().reason)
        assertNull(result.excluded.single().pairedWith)
    }

    @Test
    fun `8 - KROGER is not excluded - no keyword, no pair`() {
        val groceries = txn("checking", -84_37, description = "KROGER #115")

        val result = analyzeTransfers(inPeriod = listOf(groceries), pairingWindow = listOf(groceries))

        assertEquals(1, result.operating.size)
        assertEquals(0, result.excluded.size)
    }

    /**
     * Kevin's 2026-08-13 decision, reversing 2026-08-07 for THIS shape only: an unpaired row whose
     * description names an account Kevin actually holds is proven evidence, not a wording guess, so
     * it IS pulled out of `operating` even with no partner row in [pairingWindow] at all.
     */
    @Test
    fun `10 - an unpaired row naming an account Kevin actually holds is excluded as OWN_ACCOUNT_MOVEMENT`() {
        val cardPayment = txn("checking", -1300_00, description = "PAYMENT TO CRD 4146 Confirmation# 0649409616")

        val result = analyzeTransfers(
            inPeriod = listOf(cardPayment),
            pairingWindow = listOf(cardPayment),
            ownAccountIds = setOf("4111111111114146"),
        )

        assertEquals(0, result.operating.size)
        assertEquals(1, result.excluded.size)
        assertEquals(ExclusionReason.OWN_ACCOUNT_MOVEMENT, result.excluded.single().reason)
        assertNull(result.excluded.single().pairedWith)
    }

    /**
     * The exact reason this refinement exists: a Zelle payment to a PERSON matches the same
     * `payment to` keyword a card payment does, but never names an account, so it must stay counted
     * as real spend even when [ownAccountIds] is non-empty.
     */
    @Test
    fun `11 - a Zelle payment to a person is never OWN_ACCOUNT_MOVEMENT and still counts as spend`() {
        val zelle = txn("checking", -40000, description = "Zelle payment to  U Naing Win US Conf# b4nb0qacg")

        val result = analyzeTransfers(
            inPeriod = listOf(zelle),
            pairingWindow = listOf(zelle),
            ownAccountIds = setOf("4111111111114146", "411111113119"),
        )

        assertEquals(1, result.operating.size)
        assertEquals(zelle.id, result.operating.single().id)
        assertEquals(1, result.excluded.size)
        assertEquals(ExclusionReason.SUSPECTED_TRANSFER, result.excluded.single().reason)
    }

    /**
     * [ownAccountIds] is deliberately conservative (see [referencesOwnAccount]'s doc comment) - a
     * reference to an account NOT in the set falls back to whatever the keyword pass already
     * decided, never a stricter or looser answer than 2026-08-07's original behaviour for that row.
     */
    @Test
    fun `12 - a reference to an account NOT in ownAccountIds falls back to SUSPECTED_TRANSFER, still counted`() {
        val toUnknownSavings = txn("checking", -50000, description = "Online Banking transfer to SAV 1490 Confirmation# 1771219781")

        val result = analyzeTransfers(
            inPeriod = listOf(toUnknownSavings),
            pairingWindow = listOf(toUnknownSavings),
            ownAccountIds = setOf("4111111111114146", "411111113119"), // no account ending 1490
        )

        assertEquals(1, result.operating.size)
        assertEquals(ExclusionReason.SUSPECTED_TRANSFER, result.excluded.single().reason)
    }

    /** A matched pair still wins over an own-account reference - MATCHED_TRANSFER is checked first and is the stronger, second-row-confirmed claim. */
    @Test
    fun `13 - a matched pair is reported as MATCHED_TRANSFER, not OWN_ACCOUNT_MOVEMENT, even when both legs also name a known account`() {
        val out = txn("checking", -1300_00, txnDate = DAY_1, description = "PAYMENT TO CRD 4146")
        val inLeg = txn("card", 1300_00, txnDate = DAY_1 + 2 * DAY_MS, description = "PAYMENT FROM CHK 3119")

        val result = analyzeTransfers(
            inPeriod = listOf(out, inLeg),
            pairingWindow = listOf(out, inLeg),
            ownAccountIds = setOf("4111111111114146", "411111113119"),
        )

        assertEquals(0, result.operating.size)
        assertTrue(result.excluded.all { it.reason == ExclusionReason.MATCHED_TRANSFER })
    }

    /** The default (no [ownAccountIds] argument) behaves exactly as before 2026-08-13 - existing callers that never pass it are unaffected. */
    @Test
    fun `14 - omitting ownAccountIds entirely reproduces the pre-2026-08-13 behaviour`() {
        val cardPayment = txn("checking", -1300_00, description = "PAYMENT TO CRD 4146 Confirmation# 0649409616")

        val result = analyzeTransfers(inPeriod = listOf(cardPayment), pairingWindow = listOf(cardPayment))

        assertEquals(1, result.operating.size)
        assertEquals(ExclusionReason.SUSPECTED_TRANSFER, result.excluded.single().reason)
    }

    @Test
    fun `9 - reversing input list order changes nothing`() {
        val a = txn("checking", 50_00, txnDate = DAY_1)
        val b = txn("card", -50_00, txnDate = DAY_1)
        val c = txn("savings", -50_00, txnDate = DAY_1)
        val forward = listOf(a, b, c)
        val reversed = listOf(c, b, a)

        val forwardResult = analyzeTransfers(inPeriod = forward, pairingWindow = forward)
        val reversedResult = analyzeTransfers(inPeriod = reversed, pairingWindow = reversed)

        assertEquals(forwardResult.operating.map { it.id }.toSet(), reversedResult.operating.map { it.id }.toSet())
        assertEquals(
            forwardResult.excluded.map { it.txn.id to it.pairedWith }.toSet(),
            reversedResult.excluded.map { it.txn.id to it.pairedWith }.toSet(),
        )
    }

    companion object {
        private const val DAY_1 = 1_753_920_000_000L // 2025-07-31T00:00:00Z-ish, an arbitrary UTC-midnight-aligned instant
        private const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
