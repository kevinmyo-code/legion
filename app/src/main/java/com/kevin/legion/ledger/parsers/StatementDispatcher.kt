package com.kevin.legion.ledger.parsers

import com.kevin.legion.data.local.LedgerTransaction
import com.kevin.legion.ledger.LedgerLlmOutcome
import com.kevin.legion.ledger.LedgerStatementAgent
import java.io.ByteArrayInputStream

/**
 * Outcome of [StatementDispatcher.dispatchDeterministic] - the free half of
 * the pipeline, per `.scratch/ledger-drive-ingestion/issues/06-llm-spend-gate.md`
 * §1: "split the dispatcher, do not add recognition." [NeedsLlm] carries the
 * already-extracted plain text (not the raw bytes) so [StatementDispatcher.runLlm]
 * never has to re-run PDF extraction.
 */
sealed class DeterministicResult {
    data class Success(val transactions: List<LedgerTransaction>) : DeterministicResult()
    data class Quarantined(val reason: String) : DeterministicResult()
    data class NeedsLlm(val statementText: String) : DeterministicResult()
}

/**
 * Port of Project Andromeda's `duo_ledger.bronze.parsers.parse_statement`
 * (`~/PycharmProjects/Andromeda`): try each known deterministic parser in
 * order. **Behavioral difference from Andromeda, which has no fallback**: when
 * every deterministic parser reports [UnrecognizedLayoutException], fall
 * through to the LLM path ([LedgerStatementAgent]) instead of raising.
 *
 * A [BalanceContinuityException] (or any other [StatementParseException]) from
 * a deterministic parser that DID recognize the layout is NOT retried against
 * other parsers or the LLM - it means this is a known bank format whose
 * numbers don't reconcile (a corrupted export, a real accounting problem), and
 * that's reported as a quarantine, not silently escalated to a fuzzier path.
 *
 * **Split into two entry points** (ticket 06 amendment to ticket 05):
 * [dispatchDeterministic] runs the free half only - it NEVER touches Gemini,
 * which is exactly what makes the batch pipeline's fallthrough count exact
 * and free (`.scratch/ledger-drive-ingestion/issues/05-batch-ingestion-mechanics.md`
 * amendment 1). [runLlm] is the paid half, called only for the approved set
 * after the spend gate. The two used to be one function (`dispatch`) that
 * discovered fallthrough by catching [UnrecognizedLayoutException] and
 * immediately paying for it; that shape made it impossible to know the LLM
 * count before spending on the first file.
 */
object StatementDispatcher {
    /**
     * Runs only the deterministic parsers. Pure CPU work (PDF text/word
     * extraction + layout parsing) - no network, no coroutine suspension
     * needed, which is itself part of the "free and exact" property the
     * batch gate depends on.
     */
    fun dispatchDeterministic(fileName: String, bytes: ByteArray): DeterministicResult {
        // BofaCsvStatementParser MUST run first, before either PDF parser
        // touches these bytes. Its recognition step is pure text matching
        // (String(bytes, UTF_8) never throws), so handing it a real PDF is
        // safe - the fixed CSV header string cannot appear in a BofA/DBS
        // PDF's raw bytes, so it always falls through cleanly via
        // UnrecognizedLayoutException and never shadows the PDF parsers. The
        // reverse order would be unsafe: DbsStatementParser/BofaStatementParser
        // call PdfText/PdfWords, which call PDDocument.load() - handed real
        // CSV bytes, that throws a raw (non-StatementParseException) IOException
        // that this catch chain does not handle, instead of falling through.
        try {
            return DeterministicResult.Success(
                BofaCsvStatementParser.parse(fileName, ByteArrayInputStream(bytes))
            )
        } catch (e: UnrecognizedLayoutException) {
            // fall through to the next parser
        } catch (e: StatementParseException) {
            // userMessage first: `message` is the diagnostic, and it went in
            // front of a user verbatim on the first device render of a
            // quarantine row. See StatementParseException.userMessage.
            return DeterministicResult.Quarantined(
                e.userMessage ?: e.message ?: "This statement's numbers didn't reconcile."
            )
        }

        try {
            return DeterministicResult.Success(
                DbsStatementParser.parse(fileName, ByteArrayInputStream(bytes))
            )
        } catch (e: UnrecognizedLayoutException) {
            // fall through to the next parser
        } catch (e: StatementParseException) {
            // userMessage first: `message` is the diagnostic, and it went in
            // front of a user verbatim on the first device render of a
            // quarantine row. See StatementParseException.userMessage.
            return DeterministicResult.Quarantined(
                e.userMessage ?: e.message ?: "This statement's numbers didn't reconcile."
            )
        }

        try {
            return DeterministicResult.Success(
                BofaStatementParser.parse(fileName, ByteArrayInputStream(bytes))
            )
        } catch (e: UnrecognizedLayoutException) {
            // fall through to the LLM path
        } catch (e: StatementParseException) {
            // userMessage first: `message` is the diagnostic, and it went in
            // front of a user verbatim on the first device render of a
            // quarantine row. See StatementParseException.userMessage.
            return DeterministicResult.Quarantined(
                e.userMessage ?: e.message ?: "This statement's numbers didn't reconcile."
            )
        }

        val text = PdfText.extractText(ByteArrayInputStream(bytes))
        return DeterministicResult.NeedsLlm(text)
    }

    /**
     * The paid half: hands [statementText] (already extracted by
     * [dispatchDeterministic]) to [LedgerStatementAgent]. Only ever called
     * for files the ticket 06 spend gate has actually approved.
     */
    suspend fun runLlm(fileName: String, statementText: String): LedgerLlmOutcome =
        LedgerStatementAgent.extract(fileName, statementText)
}
