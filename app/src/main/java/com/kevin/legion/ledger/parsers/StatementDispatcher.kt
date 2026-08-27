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

    /**
     * The file parsed and reconciled perfectly - **every numeric anchor passed**
     * - and the only thing missing is which account it belongs to. Distinct
     * from [Quarantined] because it is not a failure of the document: nothing
     * is wrong with the numbers, and re-running with an account is all it needs.
     *
     * It is a separate case because **the right thing to say depends on how the
     * file arrived**, and the parser cannot know that. On a folder scan the
     * answer is "map the folder to an account". On a hand-picked single file
     * there IS no folder, and sending the user to a folder-mapping screen is
     * nonsense - the caller asks which account instead. Encoding that as a
     * string message inside the parser produced exactly that wrong instruction
     * on the pick path (reported on device, 2026-08-07).
     */
    data class NeedsAccount(val reason: String) : DeterministicResult()
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
     * calling this. **It fills a gap, it never overrides a stated fact.**
     * Kevin's real Drive layout mixes multiple accounts' files in one
     * subfolder (`USA Bank Statements/` holds BofA checking PDFs, BofA card
     * PDFs, AND the checking CSV together), so [accountHint] cannot be
     * assumed to describe every file it is threaded past. Only
     * [BofaCsvStatementParser] actually consumes it - that CSV export prints
     * no account of its own anywhere, verified on Kevin's real file, so the
     * hint is the ONLY source for `accountId` there. Every PDF parser
     * (`DbsStatementParser`, `BofaStatementParser`, `BofaCardStatementParser`)
     * derives `accountId` from the document's own printed account and is
     * never handed [accountHint] at all - a stated, falsifiable fact in the
     * document always wins, and a folder mapping that happens to describe a
     * *different* account in the same mixed folder is simply irrelevant to
     * it, not a conflict to quarantine over.
     */
    fun dispatchDeterministic(fileName: String, bytes: ByteArray, accountHint: String? = null): DeterministicResult {
        // LegionCsvStatementParser runs first of all (ticket 03 ruling 3 - this is the format
        // going forward, ahead of every bank-specific parser this dispatcher still carries). Its
        // recognition is an exact match against this format's own fixed header line
        // (docs/ledger-csv-import-format.md), a string no bank's own export or PDF can contain, so
        // it cannot shadow any parser below it - same "closed, distinct header" reasoning as
        // BofaCsvStatementParser's own comment just below, and the reverse is equally true: none
        // of those recognizers can match THIS format's header either, so the ordering among the
        // CSV recognizers is immaterial and this one is simply listed first as the one ruling 3
        // means to keep.
        try {
            val transactions = LegionCsvStatementParser.parse(fileName, ByteArrayInputStream(bytes))
            return DeterministicResult.Success(transactions)
        } catch (e: UnrecognizedLayoutException) {
            // fall through to the next parser
        } catch (e: StatementParseException) {
            return DeterministicResult.Quarantined(
                e.userMessage ?: e.message ?: "This statement's numbers didn't reconcile."
            )
        }

        // BofaCsvStatementParser MUST run before either PDF parser
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
            return DeterministicResult.Success(transactions)
        } catch (e: UnrecognizedLayoutException) {
            // fall through to the next parser
        } catch (e: UnmappedAccountException) {
            // MUST be caught before StatementParseException below, which it
            // subclasses - otherwise it collapses into a generic quarantine and
            // the caller loses the one distinction that matters here: the
            // numbers were fine, only the account is unknown.
            return DeterministicResult.NeedsAccount(e.userMessage ?: e.message ?: "Which account is this for?")
        } catch (e: StatementParseException) {
            // userMessage first: `message` is the diagnostic, and it went in
            // front of a user verbatim on the first device render of a
            // quarantine row. See StatementParseException.userMessage.
            return DeterministicResult.Quarantined(
                e.userMessage ?: e.message ?: "This statement's numbers didn't reconcile."
            )
        }

        // BofaCardCsvStatementParser (ticket 12): a real parser now, not a
        // named rejection - the mid-cycle card export states no balance or
        // total to reconcile against, so its rows commit UNRECONCILED
        // (CLAUDE.md §4 rule 7) rather than quarantining outright. It must
        // still run here, before looksLikePdf, on the same "distinct exact
        // header line, cannot shadow the other CSV parser or a PDF" footing
        // as BofaCsvStatementParser above.
        try {
            val transactions = BofaCardCsvStatementParser.parse(fileName, ByteArrayInputStream(bytes))
            return DeterministicResult.Success(transactions)
        } catch (e: UnrecognizedLayoutException) {
            // fall through to the next parser
        } catch (e: StatementParseException) {
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
            return DeterministicResult.Success(transactions)
        } catch (e: UnrecognizedLayoutException) {
            // fall through to BofaCardStatementParser
        } catch (e: StatementParseException) {
            // userMessage first: `message` is the diagnostic, and it went in
            // front of a user verbatim on the first device render of a
            // quarantine row. See StatementParseException.userMessage.
            return DeterministicResult.Quarantined(
                e.userMessage ?: e.message ?: "This statement's numbers didn't reconcile."
            )
        }

        // Ordered strictly AFTER BofaStatementParser (checking), never
        // before it - BofaStatementParser's own recognition anchors
        // ("Account number"/"Account #" plus "Beginning balance on ...")
        // never appear on a card statement, so this ordering is what
        // guarantees BofaCardStatementParser can never shadow the checking
        // parser, not the reverse.
        try {
            val transactions = BofaCardStatementParser.parse(fileName, ByteArrayInputStream(bytes))
            return DeterministicResult.Success(transactions)
        } catch (e: UnrecognizedLayoutException) {
            // fall through to the LLM path
        } catch (e: StatementParseException) {
            return DeterministicResult.Quarantined(
                e.userMessage ?: e.message ?: "This statement's numbers didn't reconcile."
            )
        }

        val text = PdfText.extractText(ByteArrayInputStream(bytes))
        return DeterministicResult.NeedsLlm(text)
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
