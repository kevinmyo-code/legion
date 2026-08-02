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
 * Result of resolving [LedgerDedupKey]-grouped [resolveDedup] input: which
 * incoming rows actually get inserted, and how many were dropped as
 * duplicates. [duplicatesSkipped] is what `IngestedFile.duplicatesSkipped`
 * (ticket 03 amendment 2) persists per file, so the "errs toward dropping"
 * behaviour below is auditable after the fact rather than silent.
 */
data class LedgerDedupResolution(
    val toInsert: List<LedgerTransaction>,
    val duplicatesSkipped: Int,
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
 */
fun resolveDedup(
    existing: List<LedgerTransaction>,
    incoming: List<LedgerTransaction>,
): LedgerDedupResolution {
    val existingCredits = existing.groupingBy(::dedupKey).eachCount().toMutableMap()
    val toInsert = mutableListOf<LedgerTransaction>()
    for (txn in incoming) {
        val key = dedupKey(txn)
        val credit = existingCredits[key] ?: 0
        if (credit > 0) {
            existingCredits[key] = credit - 1
        } else {
            toInsert += txn
        }
    }
    return LedgerDedupResolution(toInsert, incoming.size - toInsert.size)
}
