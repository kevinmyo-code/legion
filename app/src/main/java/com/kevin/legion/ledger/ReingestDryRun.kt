package com.kevin.legion.ledger

import com.kevin.legion.data.local.LedgerTransaction
import com.kevin.legion.ledger.parsers.DeterministicResult
import com.kevin.legion.ledger.parsers.StatementAnchors
import com.kevin.legion.ledger.parsers.StatementDispatcher

/**
 * Ticket 12's dry run (`.scratch/backend-erp/issues/12-ledger-rows-have-no-statement-header.md`) -
 * option 1, "check the Drive folder first", turned into code that actually checks it. **Writes
 * nothing.** It re-reads every [com.kevin.legion.data.local.IngestState.INGESTED] file that still
 * carries a [com.kevin.legion.data.local.IngestedFile.treeUri], re-parses it through the SAME
 * deterministic parsers [StatementDispatcher] already uses (no second parse path), and reports
 * what a real re-ingestion pass would find - never touches [IngestPipeline], the
 * `ingested_files` table, or [com.kevin.legion.engine.RecordStore].
 *
 * ## Why this can only be a plain, testable class, not a UI action that "just re-runs the scan"
 *
 * [IngestPipeline.stage] skips a file whose size/mtime still match what is on record - it exists
 * to make an ordinary rescan cheap, and it would make this dry run report almost nothing, because
 * every one of these 107 files is byte-identical to what is already stored. This class bypasses
 * `stage()` entirely: given a file's identity, it reads the bytes and parses, always, regardless
 * of what `ingested_files` already says.
 *
 * ## What "recovering an anchor" means here, and why it is often NOT all three
 *
 * Ticket 12's root cause was that [StatementDispatcher]'s deterministic parsers reconciled a
 * statement's own printed total/opening/closing balance **inside the parse**, then threw those
 * numbers away - only the individual [LedgerTransaction] rows survived as the public return
 * value. **That is now fixed at the source**: [DeterministicResult.Success] carries a
 * [StatementAnchors] read directly off the document by the parser itself, and this class simply
 * relays it - see each parser's own KDoc for exactly which anchors its bank format prints.
 * [AnchorRecovery] mirrors [StatementAnchors]'s three fields one-for-one; it stays a separate type
 * here (rather than reusing [StatementAnchors] directly) only because [missing]/[isComplete] are
 * dry-run-report concerns, not something every caller of [StatementAnchors] needs.
 *
 * **None of the three is ever a sum of this class's own recovered rows.** A parser's
 * `statedTotalCents` is null whenever its bank format prints no single combined total line -
 * true for every PDF/CSV bank statement parser today, since each of them prints opening/closing
 * balances but only per-SECTION totals, never one figure for the whole statement. Reporting
 * `sum(amountCents)` in that null's place would make this dry run's own reconciliation report
 * pass by construction (CLAUDE.md §4 rule 6's failure shape) - this class used to do exactly that
 * before the parsers themselves started returning real anchors, and does not any more.
 *
 * A file recovering fewer than three anchors is not a failure of THIS dry run, and not a failure
 * of the file - see [AnchorRecovery.missing] and [FileOutcome.Parsed]'s own doc for the wording
 * this maps to: a rule-7 provisional candidate, reported as its own category.
 *
 * ## The row-count projection is a SIMULATION, not a promise
 *
 * [projectRowCount] replays [resolveDedup] - the exact pure function [IngestPipeline.commit] calls
 * - across every successfully re-parsed file, in an order this class picks (grouped by account,
 * each account's files ordered by that file's own earliest transaction date). It is offered as the
 * best available explanation for the 1,349-vs-168 gap, not as what re-ingesting would actually
 * produce. Three things it does NOT model, all stated so nobody mistakes the number for a
 * guarantee:
 *
 * 1. **No replace-flow.** [IngestPipeline.commit]'s `isReplace` branch (deleting a file's own
 *    stale prior rows before re-inserting) never runs here - this dry run's set has no
 *    "previously ingested under different bytes" file, since every file re-reads identically.
 * 2. **No rule-7 provisional supersession.** The live account also holds 7 `UNRECONCILED` rows
 *    (Bank of America's mid-cycle card CSV, per CLAUDE.md §4 rule 7) that a reconciled statement
 *    landing over the same window would delete. None of those 7 came from a `treeUri` file in
 *    this dry run's scope, so this simulation never encounters that deletion and cannot report on
 *    it either way.
 * 3. **The replay order is a best-effort reconstruction, not the true historical scan order.**
 *    [resolveDedup]'s exact-match pass (pass one) is order-independent - two files claiming the
 *    identical transaction produce the same result regardless of which is "first". Its loose-match
 *    pass (pass two, the wording-differs-across-exports case) is NOT: it only credits a match
 *    against a window some EARLIER file (in replay order) already enumerated. If the real Drive
 *    folder's files were ingested in a materially different order than earliest-transaction-date-
 *    per-account, [projectedRowCount] can differ from what a real re-ingest would produce, in
 *    either direction.
 */
object ReingestDryRun {

    /**
     * Reads one file's bytes given its stored identity. Returns null for "could not read" (the
     * file moved, the folder permission lapsed, a stream error) - same "null means unreachable,
     * never a thrown exception" contract [com.kevin.legion.service.IngestScanner]'s own
     * `openBytes` uses, so a screen wiring this to real SAF I/O can reuse that exact shape.
     * Injected so this whole class stays a plain JVM unit test with no Android, no SAF, no
     * Robolectric - mirrors [com.kevin.legion.ui.settings.BackendMigrationResolver]'s posture of
     * keeping decision logic separate from the I/O a composable owns.
     */
    fun interface ByteReader {
        suspend fun read(driveFileId: String, treeUri: String, displayName: String): ByteArray?
    }

    /** One file's stored identity - the exact fields [ByteReader.read] and re-dispatch need, no more. */
    data class FileInput(
        val driveFileId: String,
        val treeUri: String,
        val displayName: String,
    )

    /**
     * The three numbers a `statements` header needs (CLAUDE.md §4's server-schema amendment).
     * A null field means NOT recovered through this path - never zero, never guessed. See this
     * object's own class doc for which parsers can and cannot supply each one.
     */
    data class AnchorRecovery(
        val openingBalanceCents: Long?,
        val closingBalanceCents: Long?,
        val statedTotalCents: Long?,
    ) {
        /** Named individually, in the order the ticket asks for them - never collapsed to a count alone. */
        val missing: List<String>
            get() = buildList {
                if (openingBalanceCents == null) add("opening balance")
                if (closingBalanceCents == null) add("closing balance")
                if (statedTotalCents == null) add("stated total")
            }

        val isComplete: Boolean get() = missing.isEmpty()
    }

    /** One file's outcome. Every case is something that would ALSO happen on a real re-ingest - this never invents a category the pipeline itself does not have. */
    sealed class FileOutcome {
        /** [ByteReader.read] returned null - the file's saved folder link no longer resolves. */
        data class Unreachable(val reason: String) : FileOutcome()

        /** Read fine, but [StatementDispatcher.dispatchDeterministic] quarantined it - the numbers on THIS read did not reconcile, an unexpected regression worth flagging since the same bytes reconciled once already. */
        data class Unparseable(val reason: String) : FileOutcome()

        /** The numbers reconcile; only the account mapping is missing (`accountHint` not supplied to this dry run - see [run]'s own parameter doc). Not attempted further here. */
        data class NeedsAccount(val reason: String) : FileOutcome()

        /** No deterministic parser recognized the layout - would need the LLM path, which this dry run deliberately never runs (CLAUDE.md §4's "deterministic first, LLM only as fallback" - a dry run for the DETERMINISTIC re-ingest plan has nothing to say about a file that never used it). */
        data object NeedsLlm : FileOutcome()

        /**
         * Parsed and reconciled. [transactions] is carried on the outcome (not just a count)
         * because [projectRowCount] needs the real rows to replay [resolveDedup] against - see
         * this object's own class doc for what that projection can and cannot claim.
         */
        data class Parsed(
            val rowCount: Int,
            val anchors: AnchorRecovery,
            val transactions: List<LedgerTransaction>,
        ) : FileOutcome()
    }

    /** One file's identity plus what re-reading it found. */
    data class FileReport(
        val driveFileId: String,
        val displayName: String,
        val outcome: FileOutcome,
    )

    /** The whole dry run's findings - see each field's own doc for what it does and does not promise. */
    data class AggregateReport(
        val totalFiles: Int,
        /** Files that would yield all three anchors - the ones ticket 12 is unblocked by. */
        val completeAnchors: Int,
        /** Parsed, but missing at least one anchor - a rule-7 provisional candidate, not a failure. */
        val incompleteAnchors: Int,
        /** Which anchor was missing, and how many files were missing it - a file missing two anchors counts once under each. */
        val missingAnchorCounts: Map<String, Int>,
        val unreachable: Int,
        val unparseable: Int,
        val needsAccount: Int,
        val needsLlm: Int,
        /** Sum of [FileOutcome.Parsed.rowCount] across every parsed file - the raw re-read total, BEFORE dedup. */
        val rawRowsParsed: Int,
        /**
         * [projectRowCount]'s output. **A projection, not a promise** - see this object's own
         * class doc, section "The row-count projection is a SIMULATION, not a promise", for what
         * it does and does not model. Rendered on any surface with that same qualifier attached,
         * never as a bare number.
         */
        val projectedRowCount: Int,
    )

    /**
     * Runs the dry run over [files]. [accountHint] mirrors
     * [StatementDispatcher.dispatchDeterministic]'s own optional parameter (defaults to "no
     * hint" for every file) - this dry run has no [com.kevin.legion.ledger.LedgerAccountMappingPreferences]
     * folder context to draw one from unless a caller supplies it, so a file whose account can only
     * be resolved via a folder mapping surfaces as [FileOutcome.NeedsAccount] here exactly as it
     * would on a real scan with no mapping set.
     */
    suspend fun run(
        files: List<FileInput>,
        reader: ByteReader,
        accountHint: (FileInput) -> String? = { null },
    ): List<FileReport> = files.map { input ->
        val bytes = reader.read(input.driveFileId, input.treeUri, input.displayName)
        val outcome: FileOutcome = if (bytes == null) {
            FileOutcome.Unreachable(
                "Couldn't read this file through its saved folder link - it may have moved, " +
                    "been deleted, or the folder permission may have lapsed."
            )
        } else {
            when (val det = StatementDispatcher.dispatchDeterministic(input.displayName, bytes, accountHint(input))) {
                is DeterministicResult.Success -> {
                    val anchors = AnchorRecovery(
                        openingBalanceCents = det.anchors.openingBalanceCents,
                        closingBalanceCents = det.anchors.closingBalanceCents,
                        statedTotalCents = det.anchors.statedTotalCents,
                    )
                    FileOutcome.Parsed(det.transactions.size, anchors, det.transactions)
                }
                is DeterministicResult.Quarantined -> FileOutcome.Unparseable(det.reason)
                is DeterministicResult.NeedsAccount -> FileOutcome.NeedsAccount(det.reason)
                is DeterministicResult.NeedsLlm -> FileOutcome.NeedsLlm
            }
        }
        FileReport(input.driveFileId, input.displayName, outcome)
    }

    /** Aggregates [reports] into the counts and projection [AggregateReport] carries. Pure. */
    fun aggregate(reports: List<FileReport>): AggregateReport {
        var complete = 0
        var incomplete = 0
        val missingCounts = mutableMapOf<String, Int>()
        var unreachable = 0
        var unparseable = 0
        var needsAccount = 0
        var needsLlm = 0
        var rawRows = 0

        for (report in reports) {
            when (val outcome = report.outcome) {
                is FileOutcome.Unreachable -> unreachable++
                is FileOutcome.Unparseable -> unparseable++
                is FileOutcome.NeedsAccount -> needsAccount++
                FileOutcome.NeedsLlm -> needsLlm++
                is FileOutcome.Parsed -> {
                    rawRows += outcome.rowCount
                    if (outcome.anchors.isComplete) {
                        complete++
                    } else {
                        incomplete++
                        for (missing in outcome.anchors.missing) {
                            missingCounts[missing] = (missingCounts[missing] ?: 0) + 1
                        }
                    }
                }
            }
        }

        return AggregateReport(
            totalFiles = reports.size,
            completeAnchors = complete,
            incompleteAnchors = incomplete,
            missingAnchorCounts = missingCounts,
            unreachable = unreachable,
            unparseable = unparseable,
            needsAccount = needsAccount,
            needsLlm = needsLlm,
            rawRowsParsed = rawRows,
            projectedRowCount = projectRowCount(reports),
        )
    }

    /**
     * Replays [resolveDedup] - the SAME pure function [IngestPipeline.commit] calls, not a
     * reimplementation of it - across every [FileOutcome.Parsed] file in [reports], grouped by
     * account and ordered within an account by that file's own earliest transaction date (a
     * best-effort stand-in for "the order these statements were originally ingested in", since
     * that real order is not available to this dry run). See this object's own class doc, section
     * "The row-count projection is a SIMULATION, not a promise", for exactly what this does and
     * does not account for.
     */
    fun projectRowCount(reports: List<FileReport>): Int {
        val parsedFiles = reports.mapNotNull { (it.outcome as? FileOutcome.Parsed)?.transactions }
            .filter { it.isNotEmpty() }
            .sortedWith(compareBy({ it.first().accountId }, { txns -> txns.minOf { it.txnDate } }))

        val accumulator = mutableListOf<LedgerTransaction>()
        val windowsByAccount = mutableMapOf<String, MutableList<LedgerCoveredWindow>>()

        for (txns in parsedFiles) {
            val accountId = txns.first().accountId
            val minDate = txns.minOf { it.txnDate }
            val maxDate = txns.maxOf { it.txnDate }

            val existing = accumulator.filter { it.accountId == accountId && it.txnDate in minDate..maxDate }
            val enumerated = windowsByAccount[accountId].orEmpty()

            val resolution = resolveDedup(existing, txns, enumerated)
            accumulator += resolution.toInsert
            windowsByAccount.getOrPut(accountId) { mutableListOf() }.add(LedgerCoveredWindow(minDate, maxDate))
        }

        return accumulator.size
    }
}
