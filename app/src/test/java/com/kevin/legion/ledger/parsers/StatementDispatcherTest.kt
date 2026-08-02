package com.kevin.legion.ledger.parsers

import com.kevin.legion.data.local.IngestMethod
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
    fun `recognized DBS layout succeeds via the deterministic path`() {
        val bytes = File("src/test/resources/ledger_fixtures/dbs_happy_path.pdf").readBytes()
        val result = StatementDispatcher.dispatchDeterministic("dbs_happy_path.pdf", bytes)
        assertTrue(result is DeterministicResult.Success)
        val txns = (result as DeterministicResult.Success).transactions
        assertTrue(txns.all { it.ingestMethod == IngestMethod.DETERMINISTIC })
    }

    @Test
    fun `a recognized-but-corrupted DBS statement quarantines rather than falling to the LLM path`() {
        val bytes = File("src/test/resources/ledger_fixtures/dbs_balance_mismatch.pdf").readBytes()
        val result = StatementDispatcher.dispatchDeterministic("dbs_balance_mismatch.pdf", bytes)
        assertTrue(result is DeterministicResult.Quarantined)
    }

    @Test
    fun `an unrecognized layout falls through to NeedsLlm without crashing, never quietly succeeding`() {
        // dispatchDeterministic never touches Gemini (that's the whole point
        // of the split - see StatementDispatcher's doc comment) - both
        // deterministic parsers correctly decline this layout and it comes
        // back NeedsLlm, carrying the already-extracted text.
        val bytes = File("src/test/resources/ledger_fixtures/unrecognized_layout.pdf").readBytes()
        val result = StatementDispatcher.dispatchDeterministic("unrecognized_layout.pdf", bytes)
        assertTrue(result is DeterministicResult.NeedsLlm)
        assertTrue((result as DeterministicResult.NeedsLlm).statementText.isNotBlank())
        // Deliberately NOT exercising StatementDispatcher.runLlm here - it
        // makes a real HTTP call to the Gemini endpoint, which this repo's
        // rule is to never do from a test (every reachable call bills
        // Kevin's own key, even a request that later fails). runLlm's
        // graceful-failure behavior on a bad/missing key or no network is
        // exercised at the SubAgent.askWithUsage level instead (same
        // try/catch/HttpOutcome shape as the already-tested ask()).
    }
}
