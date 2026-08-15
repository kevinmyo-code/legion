package com.kevin.legion.ledger

import com.kevin.legion.data.local.IngestMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [LedgerStatementAgent.parseAndReconcile] is a pure function of the model's
 * raw response text - no network, no Android state. Robolectric only because
 * it parses with `org.json`, which is a throw-on-call stub in a plain JVM unit
 * test. Covers the LLM path's reconciliation gate (CLAUDE.md §4 rules 2 and
 * 4) after the anchor changed from a single ambiguous `statedTotal` to two
 * separately-named ones: the statement's printed opening and closing balances
 * where it has them, and a printed "TOTAL" of the listed rows where it prints
 * no balance at all.
 *
 * The regression these exist for was measured on Kevin's device 2026-08-06:
 * the old prompt asked for "net movement ... or a printed closing total", the
 * model answered with the closing BALANCE, and the transactions summed to net
 * MOVEMENT. Those differ by exactly the opening balance, so every statement
 * that reached this path quarantined. The figures below are the real ones off
 * that device, taken from three consecutive card statements whose reported
 * "totals" chained as balances rather than as movements: 255.80 + 246.79 =
 * 502.59, and 502.59 - 171.69 = 330.90. That chain is what identified the
 * anchor as the bug rather than the extraction.
 */
@RunWith(RobolectricTestRunner::class)
class LedgerStatementAgentTest {
    private fun response(
        opening: String,
        closing: String,
        txns: String,
        accountId: String = "4111111111114146",
        currency: String = "USD",
    ) = """
        {"accountId": "$accountId", "currency": "$currency",
         "openingBalance": "$opening", "closingBalance": "$closing",
         "transactions": [$txns]}
    """.trimIndent()

    private fun txn(date: String, desc: String, amount: String) =
        """{"date": "$date", "description": "$desc", "amount": "$amount"}"""

    @Test
    fun `accepts an extraction that moves the balance exactly from opening to closing`() {
        val raw = response(
            opening = "255.80", closing = "502.59",
            txns = listOf(
                txn("2025-10-14", "PAYROLL", "500.00"),
                txn("2025-10-20", "GROCERY", "-253.21"),
            ).joinToString(","),
        )

        val result = LedgerStatementAgent.parseAndReconcile("eStmt.pdf", raw)

        assertTrue("expected Success, got $result", result is LedgerIngestResult.Success)
        val txns = (result as LedgerIngestResult.Success).transactions
        assertEquals(2, txns.size)
        assertEquals(24679L, txns.sumOf { it.amountCents })
        // Provenance is never indistinguishable from a deterministic row (§4 rule 4).
        assertTrue(txns.all { it.ingestMethod == IngestMethod.LLM_RECONCILED })
    }

    @Test
    fun `rejects a closing balance handed back where net movement was meant`() {
        // The exact shape of the device regression: the transactions are
        // CORRECT (they sum to 246.79, the real month's movement) but the
        // anchor is the closing balance, so the gate must refuse rather than
        // accept a set it cannot tie out.
        val raw = response(
            opening = "0.00", closing = "502.59",
            txns = txn("2025-11-03", "PAYROLL", "246.79"),
        )

        val result = LedgerStatementAgent.parseAndReconcile("eStmt.pdf", raw)

        assertTrue(result is LedgerIngestResult.Quarantined)
    }

    @Test
    fun `a negative-balance card statement reconciles on the difference, not the closing figure`() {
        // A card statement's balances are both negative and its movement is
        // the difference between them - the case a single "total" anchor got
        // wrong most often, since the closing figure alone looks plausible.
        val raw = response(
            opening = "-1218.90", closing = "-1318.90",
            txns = txn("2026-02-01", "PURCHASE", "-100.00"),
        )

        val result = LedgerStatementAgent.parseAndReconcile("eStmt.pdf", raw)

        assertTrue("expected Success, got $result", result is LedgerIngestResult.Success)
    }

    @Test
    fun `quarantines when the statement prints no anchor of any kind`() {
        val raw = """
            {"accountId": "4111111111114146", "currency": "USD",
             "closingBalance": "502.59",
             "transactions": [${txn("2025-11-03", "PAYROLL", "246.79")}]}
        """.trimIndent()

        val result = LedgerStatementAgent.parseAndReconcile("eStmt.pdf", raw)

        // Half a balance pair is not an anchor: without the opening figure
        // there is no movement to derive, and nothing else was printed.
        assertTrue(result is LedgerIngestResult.Quarantined)
    }

    // ---------------------------------------------------------- printedTotal fallback

    /**
     * A DBS/POSB "Debit Card Statement" prints no balance anywhere - just the
     * card's transactions and a "TOTAL"/"GRAND TOTAL" line. Verified against
     * Kevin's real Jan2024 file 2026-08-06, whose entire body is 20 charge
     * rows and `TOTAL $ 168.69`. Demanding balances quarantined 12 of these on
     * device, which is what this fallback exists to undo.
     */
    @Test
    fun `a statement with no balances reconciles against its printed total`() {
        val raw = """
            {"accountId": "5555555555557391", "currency": "SGD",
             "printedTotal": "168.69",
             "transactions": [${listOf(
                 txn("2024-01-05", "NTUC FP-RIVERVALE MALL", "-9.48"),
                 txn("2024-01-07", "BUS/MRT 369222175", "-6.10"),
                 txn("2023-12-30", "UNITY BY FAIRPRICE", "-17.00"),
                 txn("2023-12-28", "NTUC FP-RIVERVALE MALL", "-45.26"),
                 txn("2023-12-27", "WATSON'S", "-27.10"),
                 txn("2023-12-28", "BUS/MRT 364805594", "-14.47"),
                 txn("2023-12-26", "GROCER", "-25.20"),
                 txn("2024-01-04", "BUS/MRT 367213345", "-13.08"),
                 txn("2023-12-20", "MISC", "-11.00"),
             ).joinToString(",")}]}
        """.trimIndent()

        val result = LedgerStatementAgent.parseAndReconcile("Debit Card Statement_Jan2024.pdf", raw)

        assertTrue("expected Success, got $result", result is LedgerIngestResult.Success)
        val txns = (result as LedgerIngestResult.Success).transactions
        assertEquals(-16869L, txns.sumOf { it.amountCents })
    }

    @Test
    fun `the printed total still has to match`() {
        val raw = """
            {"accountId": "5555555555557391", "currency": "SGD",
             "printedTotal": "168.69",
             "transactions": [${txn("2024-01-05", "NTUC", "-9.48")}]}
        """.trimIndent()

        val result = LedgerStatementAgent.parseAndReconcile("Debit Card Statement_Jan2024.pdf", raw)

        assertTrue(result is LedgerIngestResult.Quarantined)
    }

    @Test
    fun `a printed total is refused outright on a list mixing charges and credits`() {
        // An unsigned total cannot confirm a mixed list: debits and credits can
        // cancel into a magnitude that matches by luck. -50 and +30 sum to -20,
        // whose magnitude equals a printed "20.00" while the real arithmetic
        // is anything but confirmed.
        val raw = """
            {"accountId": "5555555555557391", "currency": "SGD",
             "printedTotal": "20.00",
             "transactions": [${listOf(
                 txn("2024-01-05", "PURCHASE", "-50.00"),
                 txn("2024-01-06", "REFUND", "30.00"),
             ).joinToString(",")}]}
        """.trimIndent()

        val result = LedgerStatementAgent.parseAndReconcile("Debit Card Statement_Jan2024.pdf", raw)

        assertTrue(result is LedgerIngestResult.Quarantined)
    }

    @Test
    fun `the balance pair wins when a statement prints both anchors`() {
        // Balances are the stronger anchor - the movement is derived rather
        // than chosen - so a printedTotal alongside them must not displace it.
        val raw = """
            {"accountId": "4111111111114146", "currency": "USD",
             "openingBalance": "255.80", "closingBalance": "502.59",
             "printedTotal": "999.99",
             "transactions": [${txn("2025-11-03", "PAYROLL", "246.79")}]}
        """.trimIndent()

        val result = LedgerStatementAgent.parseAndReconcile("eStmt.pdf", raw)

        assertTrue("expected Success, got $result", result is LedgerIngestResult.Success)
    }

    @Test
    fun `an empty extraction never passes, even when the balances did not move`() {
        // §4 rule 6: a check satisfiable by the empty set is not a gate. An
        // unchanged balance implies zero net movement, and an empty
        // transaction list also sums to zero - so this is precisely the case
        // where the arithmetic alone would wave the document through.
        val raw = response(opening = "502.59", closing = "502.59", txns = "")

        val result = LedgerStatementAgent.parseAndReconcile("eStmt.pdf", raw)

        assertTrue(result is LedgerIngestResult.Quarantined)
    }

    @Test
    fun `a spaced account number is normalized to match the deterministic parsers`() {
        // Without this, the same card arrives as "4111 1111 1111 4146" here
        // and "4111111111114146" from BofaCardStatementParser, and every
        // accountId grouping key splits one account into two.
        val raw = response(
            opening = "0.00", closing = "100.00",
            txns = txn("2026-01-02", "PAYROLL", "100.00"),
            accountId = "4111 1111 1111 4146",
        )

        val result = LedgerStatementAgent.parseAndReconcile("eStmt.pdf", raw)

        assertTrue("expected Success, got $result", result is LedgerIngestResult.Success)
        assertEquals(
            "4111111111114146",
            (result as LedgerIngestResult.Success).transactions.first().accountId,
        )
    }
}
