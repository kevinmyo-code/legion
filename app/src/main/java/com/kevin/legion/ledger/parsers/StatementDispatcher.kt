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
     *
     * [accountHint] is the per-account Drive subfolder's mapped account
     * (null when the file sat directly in the connected root, or its folder
     * has no mapping yet) - [com.kevin.legion.service.IngestScanner] resolves
     * it via [com.kevin.legion.ledger.LedgerAccountMappingPreferences] before
     * calling this. Threaded to [BofaCsvStatementParser] to fill in the
     * account a CSV never prints, and checked against every OTHER parser's
     * own printed account via [accountConflict] - a PDF's own account always
     * wins; a hint can only fill a gap, never override one (see
     * [accountConflict]'s doc comment).
     */
    fun dispatchDeterministic(fileName: String, bytes: ByteArray, accountHint: String? = null): DeterministicResult {
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
            val transactions = BofaCsvStatementParser.parse(fileName, ByteArrayInputStream(bytes), accountHint)
            accountConflict(accountHint, transactions)?.let { return it }
            return DeterministicResult.Success(transactions)
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

        // Everything from here on is PDF-only (DbsStatementParser/
        // BofaStatementParser call PdfWords/PdfText, which both call
        // PDDocument.load() under the hood), and the LLM fallback below
        // extracts PLAIN TEXT FROM A PDF too. Widening file acceptance to
        // include CSV (this ticket) means bytes reaching this point are no
        // longer guaranteed to be a PDF - a CSV that isn't BofA's exact
        // export layout falls through the block above with nothing else to
        // try. PDDocument.load() throws a raw, non-StatementParseException
        // IOException on non-PDF bytes, which this catch chain does not
        // handle - so this magic-byte check must run BEFORE any of them do,
        // or a malformed/unrecognized CSV crashes the batch instead of
        // quarantining cleanly.
        if (!looksLikePdf(bytes)) {
            return DeterministicResult.Quarantined(
                "This isn't a statement format LEGION recognizes yet - only PDF statements and Bank " +
                    "of America's CSV export are supported right now. Nothing was imported."
            )
        }

        try {
            val transactions = DbsStatementParser.parse(fileName, ByteArrayInputStream(bytes))
            accountConflict(accountHint, transactions)?.let { return it }
            return DeterministicResult.Success(transactions)
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
            val transactions = BofaStatementParser.parse(fileName, ByteArrayInputStream(bytes))
            accountConflict(accountHint, transactions)?.let { return it }
            return DeterministicResult.Success(transactions)
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
     * A PDF's own printed account always wins over a folder mapping - the
     * mapping only fills a gap for a file that states no account of its own
     * (CLAUDE.md §4: never silently trust a guess over a stated fact, and
     * never silently trust one source over another either). Returns null
     * (no conflict) when [accountHint] is null (folder unmapped - nothing to
     * disagree with) or matches [transactions]' own account - the common
     * case, since for a CSV [accountHint] is exactly what filled the account
     * in ([BofaCsvStatementParser] already remapped every row to it, so the
     * two are trivially equal there).
     *
     * When they DO disagree, that means this file is most likely sitting in
     * the wrong per-account subfolder - silently trusting either side would
     * misfile money into the wrong account, so this quarantines instead of
     * picking one.
     */
    private fun accountConflict(accountHint: String?, transactions: List<LedgerTransaction>): DeterministicResult.Quarantined? {
        if (accountHint == null || transactions.isEmpty()) return null
        val stated = transactions.first().accountId
        if (stated == accountHint) return null
        return DeterministicResult.Quarantined(
            "This statement's own account ($stated) doesn't match the account its folder is mapped " +
                "to ($accountHint). It's probably filed in the wrong folder. Nothing was imported."
        )
    }

    /**
     * True when [bytes] start with the PDF file signature (`%PDF`). See the
     * comment at its call site in [dispatchDeterministic] for why this
     * became load-bearing once CSV acceptance widened this function's input
     * beyond "always a PDF".
     */
    private fun looksLikePdf(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0x25.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x44.toByte() && bytes[3] == 0x46.toByte()

    /**
     * The paid half: hands [statementText] (already extracted by
     * [dispatchDeterministic]) to [LedgerStatementAgent]. Only ever called
     * for files the ticket 06 spend gate has actually approved.
     */
    suspend fun runLlm(fileName: String, statementText: String): LedgerLlmOutcome =
        LedgerStatementAgent.extract(fileName, statementText)
}
