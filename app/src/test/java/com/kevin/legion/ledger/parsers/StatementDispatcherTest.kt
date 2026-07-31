package com.kevin.legion.ledger.parsers

import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.ledger.LedgerIngestResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class StatementDispatcherTest {
    @Before
    fun setup() {
        PdfWords.init(RuntimeEnvironment.getApplication())
    }

    @Test
    fun `recognized DBS layout succeeds via the deterministic path`() = runBlocking {
        val bytes = File("src/test/resources/ledger_fixtures/dbs_happy_path.pdf").readBytes()
        val result = StatementDispatcher.dispatch("dbs_happy_path.pdf", bytes)
        assertTrue(result is LedgerIngestResult.Success)
        val txns = (result as LedgerIngestResult.Success).transactions
        assertTrue(txns.all { it.ingestMethod == IngestMethod.DETERMINISTIC })
    }

    @Test
    fun `a recognized-but-corrupted DBS statement quarantines rather than falling to the LLM path`() = runBlocking {
        val bytes = File("src/test/resources/ledger_fixtures/dbs_balance_mismatch.pdf").readBytes()
        val result = StatementDispatcher.dispatch("dbs_balance_mismatch.pdf", bytes)
        assertTrue(result is LedgerIngestResult.Quarantined)
    }

    @Test
    fun `an unrecognized layout falls through to the LLM path without crashing`() = runBlocking {
        val bytes = File("src/test/resources/ledger_fixtures/unrecognized_layout.pdf").readBytes()
        // No real Gemini key in this test environment, so this exercises the
        // routing (both deterministic parsers correctly decline, dispatch falls
        // to LedgerStatementAgent) - the LLM call itself will fail (no network/
        // key) and must quarantine gracefully, never crash and never silently
        // fabricate a Success.
        val result = StatementDispatcher.dispatch("unrecognized_layout.pdf", bytes)
        assertTrue(result is LedgerIngestResult.Quarantined)
    }
}
