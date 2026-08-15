package com.kevin.legion.ui.ledger

import com.kevin.legion.ledger.formatCents

/**
 * Pure copy-building logic for the voice-logged-pending-transactions surface (the section
 * [com.kevin.legion.ledger.AccountBalance.hasPendingRows] gates). Kept Compose-free, same
 * "pure builder, thin composable wrapper" split [LedgerEmptyStateResolver] and
 * `ui.TodayGapResolvers` already use - a plain JUnit test, not Robolectric.
 */
object LedgerPendingResolver {
    /**
     * The plain-words line CLAUDE.md §4 rule 7 requires whenever a rendered figure includes
     * unconfirmed activity - "words, never colour alone". [pendingDeltaCents] is the account's
     * signed sum ([com.kevin.legion.data.local.LedgerTransactionDao.pendingDeltaCents]); the
     * MAGNITUDE is what's spoken/shown ("includes $123.79...", never a signed "-$123.79" reading
     * like a typo). Callers only show this when
     * [com.kevin.legion.ledger.AccountBalance.hasPendingRows] is true - this function does not
     * itself decide whether to render, only what to say once that's already decided.
     */
    fun balanceNote(pendingDeltaCents: Long): String =
        "includes ${formatCents(kotlin.math.abs(pendingDeltaCents))} you logged as pending, not yet confirmed by the bank"
}
