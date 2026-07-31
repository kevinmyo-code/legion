package com.kevin.legion.ledger.parsers

import com.kevin.legion.ledger.LedgerIngestResult
import com.kevin.legion.ledger.LedgerStatementAgent
import java.io.ByteArrayInputStream

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
 */
object StatementDispatcher {
    suspend fun dispatch(fileName: String, bytes: ByteArray): LedgerIngestResult {
        try {
            return LedgerIngestResult.Success(
                DbsStatementParser.parse(fileName, ByteArrayInputStream(bytes))
            )
        } catch (e: UnrecognizedLayoutException) {
            // fall through to the next parser
        } catch (e: StatementParseException) {
            return LedgerIngestResult.Quarantined(e.message ?: "This statement's numbers didn't reconcile.")
        }

        try {
            return LedgerIngestResult.Success(
                BofaStatementParser.parse(fileName, ByteArrayInputStream(bytes))
            )
        } catch (e: UnrecognizedLayoutException) {
            // fall through to the LLM path
        } catch (e: StatementParseException) {
            return LedgerIngestResult.Quarantined(e.message ?: "This statement's numbers didn't reconcile.")
        }

        val text = PdfText.extractText(ByteArrayInputStream(bytes))
        return LedgerStatementAgent.extract(fileName, text)
    }
}
