package com.kevin.legion.ui.ledger

import com.kevin.legion.data.local.LedgerTransaction
import com.kevin.legion.ledger.CategoryGuessResult
import com.kevin.legion.ledger.UncategorizedMerchants
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

    // ----------------------------------------------------------- the 2026-08-18 "44 invisible rows" fix

    /**
     * The CATEGORIZE screen's empty-state sentence, and the reason it used to lie. The old check
     * was `pending.isEmpty() && categoryGuesses.isEmpty()` - neither list is "rows with no
     * category" (see [com.kevin.legion.ui.ledger.CategorizeDrilldownScreen]'s own doc comment for
     * the full account), so it reported "nothing to categorize" while 44 real uncategorised rows
     * sat unexamined. This is CLAUDE.md §4 rule 6's principle ("a check that passes when nothing
     * was examined is not a check") applied to a UI claim rather than a reconciliation gate: this
     * function can only ever return a "nothing" sentence when [uncategorizedRealCount] - the one
     * list the old check never looked at - is ALSO zero.
     *
     * Returns `null` when there is real work to show (any of pending/guesses/uncategorised is
     * non-empty) - the caller renders the three sections instead of a placeholder. Otherwise
     * returns a sentence that is true of what [uncategorizedTransfersCount] actually holds too:
     * plain "nothing to categorize" only when transfers are ALSO zero, since a nonzero transfer
     * count means the transfers section still renders underneath this sentence (see
     * [CategorizeDrilldownScreen]) and a driver reading "nothing" over a visible list of rows is
     * the exact inconsistency this fix exists to close.
     */
    fun categorizeEmptyStateSentence(
        pendingCount: Int,
        categoryGuessCount: Int,
        uncategorizedRealCount: Int,
        uncategorizedTransfersCount: Int,
    ): String? {
        if (pendingCount > 0 || categoryGuessCount > 0 || uncategorizedRealCount > 0) return null
        return if (uncategorizedTransfersCount == 0) {
            "Nothing to categorize right now."
        } else {
            "Nothing left that needs a category. " +
                "$uncategorizedTransfersCount transfer-shaped row${if (uncategorizedTransfersCount == 1) "" else "s"} " +
                "${if (uncategorizedTransfersCount == 1) "stays" else "stay"} uncategorised on purpose - excluded from spend, never a category."
        }
    }

    /** The words under a transfer-shaped row in the new UNCATEGORISED section - never a colour alone (CLAUDE.md §4 rule 7). */
    fun transferRowNote(): String = "transfer-shaped - excluded from spend, not sent for a category"

    /** RUN CATEGORIZATION's free first step (rules only) - always reported, even when it changed nothing. */
    fun rulesRunSentence(rowsFixed: Int): String = when (rowsFixed) {
        0 -> "Rules matched nothing new."
        1 -> "Rules fixed 1 row."
        else -> "Rules fixed $rowsFixed rows."
    }

    /**
     * What's left after rules ran, before any money is spent - shown next to the GUESS CATEGORIES
     * confirm control (CLAUDE.md §7's Gemini-call checklist item: on the user's own key, and never
     * silently). [pool] is [uncategorizedMerchants]'s own return, so [UncategorizedMerchants.transfersSkipped]
     * is reported here too - "how many rows were skipped as transfers" per the fix's own item 3.
     */
    fun guessPoolSentence(pool: UncategorizedMerchants, hasGeminiKey: Boolean): String {
        val transferNote = if (pool.transfersSkipped > 0) {
            " (${pool.transfersSkipped} transfer-shaped row${if (pool.transfersSkipped == 1) "" else "s"} skipped, never guessed)"
        } else ""
        val plural = pool.keys.size != 1
        val nounPhrase = "${pool.keys.size} merchant${if (plural) "s" else ""}"
        val verb = if (plural) "need" else "needs"
        return when {
            pool.keys.isEmpty() -> "Nothing left to guess.$transferNote"
            !hasGeminiKey -> "$nounPhrase still $verb a category, " +
                "but no Gemini key is set up - add one in Settings to guess.$transferNote"
            else -> "$nounPhrase still $verb a category.$transferNote"
        }
    }

    /** The outcome sentence after a confirmed [com.kevin.legion.ledger.LedgerController.applyCategoryGuesses] run. */
    fun guessResultSentence(result: CategoryGuessResult): String = when {
        result.rowsCategorized == 0 -> "The model returned no usable guesses - nothing changed."
        result.rowsCategorized == 1 -> "Guessed 1 category, covering 1 row."
        else -> "Guessed ${result.merchantsCategorized} categor${if (result.merchantsCategorized == 1) "y" else "ies"}, " +
            "covering ${result.rowsCategorized} rows."
    }
}
