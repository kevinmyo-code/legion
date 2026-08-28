package com.kevin.legion.ui.settings

import com.kevin.legion.ledger.ReingestDryRun
import com.kevin.legion.ledger.formatCents

/**
 * Pure UI-state derivation for `ui/settings/ReingestDryRunScreen.kt`, same posture as
 * [BackendMigrationResolver]'s own doc comment: the screen owns SAF I/O and coroutine plumbing,
 * this object only turns an already-computed [ReingestDryRun.AggregateReport] into worded lines,
 * so every branch here is a plain JVM unit test.
 *
 * **Every count is rendered as a labelled sentence, never a bare number** - same reasoning
 * [BackendMigrationResolver]'s own doc comment gives: a number with no explanation of what it
 * means is not something Kevin can act on.
 */
object ReingestDryRunResolver {

    /** The summary block at the top of the report - the aggregate counts, worded. */
    fun renderSummary(report: ReingestDryRun.AggregateReport): List<String> = buildList {
        add("Read-only dry run over ${report.totalFiles} statement ${plural(report.totalFiles, "file")} " +
            "previously ingested from a connected Drive folder. Nothing was written.")
        add(
            "${report.completeAnchors} would recover all three anchors (opening balance, closing " +
                "balance, stated total) and unblock ticket 12 for that file.",
        )
        if (report.incompleteAnchors > 0) {
            add(
                "${report.incompleteAnchors} parsed but recovered fewer than three anchors - a " +
                    "rule-7 provisional candidate each, not a failure. Missing: " +
                    report.missingAnchorCounts.entries.joinToString("; ") { (anchor, count) ->
                        "$count ${plural(count, "file")} missing the $anchor"
                    } + ".",
            )
        }
        if (report.resolvedBySavedLink > 0 || report.resolvedByContentMatch > 0) {
            add(
                "${report.resolvedBySavedLink} ${plural(report.resolvedBySavedLink, "file")} resolved " +
                    "through its saved folder link. ${report.resolvedByContentMatch} " +
                    "${plural(report.resolvedByContentMatch, "file")} needed the content-hash fallback - " +
                    "found by matching bytes in the folder that IS connected, because that file's saved " +
                    "link points at a folder that is not.",
            )
        }
        if (report.unreachable > 0) {
            add(
                "${report.unreachable} ${plural(report.unreachable, "file")} not reachable by either " +
                    "route - most likely sitting in a Drive folder that isn't the one connected right " +
                    "now, and no byte-identical copy of it turned up in the one that is.",
            )
        }
        if (report.unparseable > 0) {
            add("${report.unparseable} ${plural(report.unparseable, "file")} quarantined on re-read - the numbers no longer reconcile.")
        }
        if (report.needsAccount > 0) {
            add("${report.needsAccount} ${plural(report.needsAccount, "file")} reconcile but need an account mapping this dry run doesn't have.")
        }
        if (report.needsLlm > 0) {
            add("${report.needsLlm} ${plural(report.needsLlm, "file")} would need the LLM path - not attempted by this deterministic-only dry run.")
        }
        add(
            "Raw rows re-parsed: ${report.rawRowsParsed}. PROJECTED row count after replaying " +
                "dedup in memory: ${report.projectedRowCount}. This is a projection, not a " +
                "promise - it doesn't model the replace flow or rule-7 provisional supersession, " +
                "and its replay order is a best-effort reconstruction, not the true ingestion " +
                "order. See ReingestDryRun's own class doc for the full list of what it can't " +
                "account for.",
        )
    }

    /** One line per file, for the detail list - reachability, parse outcome, anchors, row count, resolution route, all named. */
    fun renderFileLine(report: ReingestDryRun.FileReport): String {
        val prefix = "${report.displayName}: "
        val viaSuffix = when (report.resolvedVia) {
            ReingestDryRun.ResolvedVia.CONTENT_MATCH -> " (found by content match, not its saved folder link)"
            ReingestDryRun.ResolvedVia.SAVED_LINK, null -> ""
        }
        val body = when (val outcome = report.outcome) {
            is ReingestDryRun.FileOutcome.Unreachable -> "UNREACHABLE - ${outcome.reason}"
            is ReingestDryRun.FileOutcome.Unparseable -> "UNPARSEABLE - ${outcome.reason}"
            is ReingestDryRun.FileOutcome.NeedsAccount -> "NEEDS ACCOUNT MAPPING - ${outcome.reason}"
            ReingestDryRun.FileOutcome.NeedsLlm -> "NEEDS THE LLM PATH - not attempted by this dry run"
            is ReingestDryRun.FileOutcome.Parsed -> {
                val rows = "${outcome.rowCount} ${plural(outcome.rowCount, "row")} parsed"
                if (outcome.anchors.isComplete) {
                    "PARSED, all 3 anchors recovered - $rows. " +
                        "Opening ${formatCents(outcome.anchors.openingBalanceCents!!)}, " +
                        "closing ${formatCents(outcome.anchors.closingBalanceCents!!)}, " +
                        "total ${formatCents(outcome.anchors.statedTotalCents!!)}."
                } else {
                    "PARSED, provisional candidate - $rows. Missing: ${outcome.anchors.missing.joinToString(", ")}."
                }
            }
        }
        return prefix + body + viaSuffix
    }

    private fun plural(count: Int, noun: String): String = if (count == 1) noun else "${noun}s"
}
