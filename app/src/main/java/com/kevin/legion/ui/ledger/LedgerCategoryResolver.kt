package com.kevin.legion.ui.ledger

import com.kevin.legion.data.local.LedgerTransaction
import com.kevin.legion.ledger.extractMerchantKey

/**
 * Pure copy/grouping logic for ticket B2's "see and confirm pending category guesses" surface -
 * same "pure builder, thin composable wrapper" split [LedgerPendingResolver]/[LedgerEmptyStateResolver]
 * already use, kept Compose-free so the grouping is a plain JUnit test, never a Robolectric one.
 */
object LedgerCategoryResolver {
    /**
     * One merchant's pending guess, folded down from however many individual
     * [LedgerTransaction] rows that merchant produced - the guess was made ONCE per merchant key
     * ([com.kevin.legion.ledger.LedgerController.applyCategoryGuesses], not once per row), so the
     * confirm surface shows ONE row per merchant too, not one per transaction.
     */
    data class MerchantGuess(val merchantKey: String, val category: String, val transactionCount: Int)

    /**
     * Groups [rows] (expected to already be filtered to `categoryPending = true`, i.e.
     * [com.kevin.legion.ledger.LedgerController.pendingCategoryGuesses]'s own return) by
     * [extractMerchantKey]'s merchant key AND category - the same key the guess was written under,
     * so a row whose guessed category later changed underneath it (shouldn't happen in practice,
     * D18's "guessed at most once" rule) still surfaces as its own distinct line rather than being
     * silently merged with a different category's count. A row with a null category is impossible
     * for a genuinely `categoryPending = true` row (the write always sets both together), but is
     * still dropped defensively rather than crashing on a bad row.
     */
    fun groupPendingGuesses(rows: List<LedgerTransaction>): List<MerchantGuess> =
        rows.filter { it.category != null }
            .groupBy { extractMerchantKey(it.description) to it.category!! }
            .map { (key, group) -> MerchantGuess(merchantKey = key.first, category = key.second, transactionCount = group.size) }
            .sortedByDescending { it.transactionCount }

    /**
     * The plain-words note a category drill-down row shows underneath its date/amount (Kevin
     * 2026-08-07, "I want to be able to drill down into a category and see the transactions in
     * there"). CLAUDE.md §4 rule 7: never a colour alone - a row that is unverified, a voice-logged
     * pending log, or still carrying an unconfirmed AI category guess must say so IN WORDS, and more
     * than one of those can be true of the same row at once, so this returns every applicable phrase
     * joined rather than picking just one.
     *
     * [com.kevin.legion.data.local.LedgerTransaction.pendingLoggedAt] takes precedence over the bare
     * [com.kevin.legion.data.local.IngestMethod.UNRECONCILED] wording - a pending-logged row is
     * ALSO tagged UNRECONCILED (see that field's own doc comment for why), and "logged by voice" is
     * the more specific, more useful claim of the two.
     */
    fun rowNote(txn: LedgerTransaction): String? {
        val parts = mutableListOf<String>()
        when {
            txn.pendingLoggedAt != null -> parts += "logged by voice, not yet confirmed by the bank"
            txn.ingestMethod == com.kevin.legion.data.local.IngestMethod.UNRECONCILED -> parts += "pending, not verified"
        }
        if (txn.categoryPending) parts += "category guessed, not confirmed"
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" - ")
    }

    /**
     * Thin re-export of [com.kevin.legion.ledger.validateNewCategoryName] under this resolver's
     * name, so the Compose call site (`LedgerScreen`) reads the same "state holder calls a pure
     * resolver in `ui.ledger`" shape every other decision on this screen follows, without actually
     * duplicating the logic - the real rule lives in the `ledger` package (business logic, no
     * Compose types) because [com.kevin.legion.ledger.LedgerController.addCategory] needs the exact
     * same check and `ledger` must never depend on `ui` (the reverse dependency `ui.ledger` already
     * has on `ledger`, e.g. [extractMerchantKey] above, is the correct direction; `ledger` importing
     * from `ui.ledger` would not be).
     */
    fun validateNewCategoryName(raw: String, existingNames: List<String>) =
        com.kevin.legion.ledger.validateNewCategoryName(raw, existingNames)
}
