package com.kevin.legion.ledger

import com.kevin.legion.data.local.LedgerTransaction

/**
 * The comparison-time identity two transaction rows are deduped on. Never
 * persisted - [LedgerTransaction.description] stays exactly as printed on the
 * source document; this key exists only so two rows that differ solely in
 * whitespace or case (BofA's continuation-line appending makes this a real
 * case, not a theoretical one) still compare as the same transaction.
 * `.scratch/ledger-drive-ingestion/issues/04-twin-transactions.md`.
 */
data class LedgerDedupKey(
    val accountId: String,
    val txnDate: Long,
    val amountCents: Long,
    val normalizedDescription: String,
)

/**
 * Derives [txn]'s comparison key. Comparison-time only - this never writes
 * back to [LedgerTransaction.description]; the stored row keeps the text
 * exactly as printed, for display and audit.
 */
fun dedupKey(txn: LedgerTransaction): LedgerDedupKey = LedgerDedupKey(
    accountId = txn.accountId,
    txnDate = txn.txnDate,
    amountCents = txn.amountCents,
    normalizedDescription = txn.description.trim().replace(Regex("\\s+"), " ").uppercase(),
)

/**
 * The same transaction with the DESCRIPTION dropped.
 *
 * Bank of America words one transaction differently depending on which export
 * it comes out of. The same -$8.99 on 07/06 reads
 * `PURCHASE   0706 VPN24.ME EDINBURGH    00` in the checking PDF and
 * `VPN24.ME 07/06 PURCHASE EDINBURGH 00` in the CSV export - same date, same
 * cents, same account, and [LedgerDedupKey] calls them two different
 * transactions because normalising whitespace and case cannot reorder words.
 *
 * This key is NOT a general-purpose relaxation of [LedgerDedupKey]. Used
 * alone it would collapse two genuinely separate $4.50 coffees bought on the
 * same day, which is exactly the bug ticket 04 existed to fix. It is only ever
 * consulted inside a window another reconciled statement has already
 * enumerated - see [resolveDedup].
 */
internal data class LedgerLooseKey(
    val accountId: String,
    val txnDate: Long,
    val amountCents: Long,
)

internal fun looseKey(txn: LedgerTransaction): LedgerLooseKey =
    LedgerLooseKey(txn.accountId, txn.txnDate, txn.amountCents)

/**
 * A date span some already-committed file enumerated COMPLETELY - in practice
 * `[IngestedFile.minTxnDate, IngestedFile.maxTxnDate]` of a file that passed
 * the reconciliation gate.
 *
 * The completeness claim is what the whole overlap rule rests on, and it comes
 * from CLAUDE.md §4 rather than from optimism: a statement is only committed
 * when its line items sum exactly to its own printed total, so within the
 * dates it covers there is no such thing as a transaction it left out. The
 * bounds are the file's own FIRST and LAST transaction dates rather than the
 * period printed on it, because that is the span the committed rows actually
 * demonstrate - a printed period can run past the last transaction in it, and
 * claiming completeness over that tail would be claiming something no row
 * supports.
 */
data class LedgerCoveredWindow(val fromMs: Long, val toMs: Long) {
    operator fun contains(txnDate: Long): Boolean = txnDate in fromMs..toMs
}

/**
 * Result of resolving [LedgerDedupKey]-grouped [resolveDedup] input: which
 * incoming rows actually get inserted, and how many were dropped as
 * duplicates. [duplicatesSkipped] is what `IngestedFile.duplicatesSkipped`
 * (ticket 03 amendment 2) persists per file, so the "errs toward dropping"
 * behaviour below is auditable after the fact rather than silent.
 *
 * [restatementsSkipped] is the subset of [duplicatesSkipped] that matched only
 * on [LedgerLooseKey] inside a [LedgerCoveredWindow] - i.e. the same
 * transaction worded differently by a second export, not a byte-identical
 * re-import. Reported separately because it is the weaker of the two claims
 * and the one worth being able to point at when a figure looks wrong. It is
 * NOT persisted: that would need a new column on `ingested_files` and a Room
 * migration for a number the totals already account for.
 */
data class LedgerDedupResolution(
    val toInsert: List<LedgerTransaction>,
    val duplicatesSkipped: Int,
    val restatementsSkipped: Int = 0,
)

/**
 * Replaces the old boolean existence check (`LedgerTransactionDao.countMatching`
 * / `LedgerController.isDuplicate`, both retired by this change) with per-tuple
 * counting. Existence checking collapsed two genuinely separate identical
 * purchases on the same day into one and silently dropped the second - the bug
 * this function exists to fix.
 *
 * For each [LedgerDedupKey] group: N = count of that key in [incoming], M =
 * count of that key already in [existing]. Insert `max(0, N - M)` of the
 * group. Concretely this walks [incoming] once, consuming one "already exists"
 * credit per matching [existing] row before any new row of that key is kept -
 * mechanically the mirror of "insert the first N-M, drop the trailing M" from
 * the ticket's resolution, and equivalent in outcome because rows sharing a
 * key are, by construction, indistinguishable by anything the key does not
 * already capture.
 *
 * Deliberately pure - no Room, no Android, no coroutines. [existing] is
 * expected to already be scoped to the account and date range of [incoming]
 * (see [LedgerController]'s caller), so this function only has to group,
 * count and compare, which is what makes it unit-testable without a database.
 *
 * This errs toward DROPPING a rare true twin over double-counting a routine
 * overlapping restatement (a monthly PDF and a YTD PDF both attesting the same
 * transaction) - see the ticket's resolution §5 for why that is the correct
 * default, not an oversight.
 *
 * ## The second pass, and why it is narrow (2026-08-04)
 *
 * The strict key alone was not enough for Kevin's real folder. His July
 * checking PDF covers 06/05-07/06 and his mid-cycle CSV export covers
 * 07/01-07/31, so six days are stated twice - and Bank of America words the
 * same transaction differently in the two exports (`PURCHASE   0706 VPN24.ME
 * EDINBURGH    00` against `VPN24.ME 07/06 PURCHASE EDINBURGH 00`). Three rows
 * overlapped, the strict key caught two, and the third was counted twice.
 * This recurs every month by construction.
 *
 * [enumeratedWindows] closes it WITHOUT weakening the key everywhere. A second
 * pass runs only over incoming rows that both survived pass one AND fall inside
 * a window some other already-committed file enumerated completely; only there
 * is [LedgerLooseKey] consulted. Outside those windows nothing changes - two
 * genuine same-day, same-amount purchases in a fresh statement still both
 * insert, because there is no prior statement claiming to have listed them.
 *
 * **Twins inside a window survive too**, which is the part that makes the
 * relaxation safe rather than merely narrow. If the driver really did buy two
 * identical coffees on a day an earlier statement covers, that earlier
 * statement enumerated BOTH of them, so there are two existing rows, two loose
 * credits, and the counting drops exactly two - never three. The pass can only
 * ever match an incoming row against a prior row that actually exists.
 *
 * Both passes decrement the same pool of existing rows, so one committed row
 * can absorb exactly one incoming row, never two.
 */
fun resolveDedup(
    existing: List<LedgerTransaction>,
    incoming: List<LedgerTransaction>,
    enumeratedWindows: List<LedgerCoveredWindow> = emptyList(),
): LedgerDedupResolution {
    val strictCredits = existing.groupingBy(::dedupKey).eachCount().toMutableMap()
    val looseCredits = existing.groupingBy(::looseKey).eachCount().toMutableMap()
    val toInsert = mutableListOf<LedgerTransaction>()
    val survivedStrict = mutableListOf<LedgerTransaction>()

    // Pass one: exact matches first, so a row that CAN be matched precisely
    // never spends a loose credit some other row needs.
    for (txn in incoming) {
        val key = dedupKey(txn)
        val credit = strictCredits[key] ?: 0
        if (credit > 0) {
            strictCredits[key] = credit - 1
            val loose = looseKey(txn)
            looseCredits[loose] = ((looseCredits[loose] ?: 0) - 1).coerceAtLeast(0)
        } else {
            survivedStrict += txn
        }
    }

    // Pass two: same transaction, different wording, inside a span another
    // reconciled file already enumerated.
    var restatements = 0
    for (txn in survivedStrict) {
        val inEnumeratedWindow = enumeratedWindows.any { txn.txnDate in it }
        val loose = looseKey(txn)
        val credit = looseCredits[loose] ?: 0
        if (inEnumeratedWindow && credit > 0) {
            looseCredits[loose] = credit - 1
            restatements++
        } else {
            toInsert += txn
        }
    }

    return LedgerDedupResolution(
        toInsert = toInsert,
        duplicatesSkipped = incoming.size - toInsert.size,
        restatementsSkipped = restatements,
    )
}
