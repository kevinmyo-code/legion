package com.kevin.legion.ledger

import com.kevin.legion.ledger.parsers.PdfWords
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * The two [ReingestDryRun.run] cases that need a real PDF read
 * ([com.kevin.legion.ledger.parsers.DbsStatementParser] success, and a genuine
 * [ReingestDryRun.FileOutcome.NeedsLlm] fallthrough) - same split
 * [com.kevin.legion.ledger.parsers.StatementDispatcherTest] uses, and for the same reason:
 * PdfBox-Android ships its fonts as Android assets, unreachable from a plain JVM test.
 */
@RunWith(RobolectricTestRunner::class)
class ReingestDryRunRobolectricTest {
    @Before
    fun setup() {
        PdfWords.init(RuntimeEnvironment.getApplication())
    }

    private fun fixture(name: String) = File("src/test/resources/ledger_fixtures/$name")

    @Test
    fun `run recovers opening and closing balance for a real DBS statement, but no single stated total`() = runBlocking {
        val bytes = fixture("dbs_happy_path.pdf").readBytes()
        val input = ReingestDryRun.FileInput("dbs-1", "tree://x", "dbs_happy_path.pdf")
        val reports = ReingestDryRun.run(listOf(input), ReingestDryRun.ByteReader { _, _, _ -> bytes })

        val outcome = reports.single().outcome
        assertTrue(outcome is ReingestDryRun.FileOutcome.Parsed)
        outcome as ReingestDryRun.FileOutcome.Parsed
        // DbsStatementParser prints "Balance Brought Forward"/"Total Balance Carried Forward" -
        // real opening/closing balances - but only separate withdrawal and deposit totals on its
        // closing line, never one combined "stated total". See DbsStatementParserTest for the
        // figures this reads: opening 100000, closing 290000 (dbs_happy_path.pdf's real numbers).
        assertFalse(outcome.anchors.isComplete)
        assertEquals(listOf("stated total"), outcome.anchors.missing)
        assertEquals(100000L, outcome.anchors.openingBalanceCents)
        assertEquals(290000L, outcome.anchors.closingBalanceCents)
        assertNull(outcome.anchors.statedTotalCents)
    }

    @Test
    fun `run reports NeedsLlm without ever attempting the LLM path`() = runBlocking {
        val bytes = fixture("unrecognized_reconciling.pdf").readBytes()
        val input = ReingestDryRun.FileInput("fallthrough-1", "tree://x", "unrecognized_reconciling.pdf")
        val reports = ReingestDryRun.run(listOf(input), ReingestDryRun.ByteReader { _, _, _ -> bytes })

        assertEquals(ReingestDryRun.FileOutcome.NeedsLlm, reports.single().outcome)
    }
}
