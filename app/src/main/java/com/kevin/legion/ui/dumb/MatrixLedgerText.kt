package com.kevin.legion.ui.dumb

import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.data.local.LedgerTransaction
import com.kevin.legion.data.local.MerchantAlias
import com.kevin.legion.ledger.AccountBalance
import com.kevin.legion.ledger.displayDescription
import com.kevin.legion.ledger.formatMoney
import com.kevin.legion.ledger.maskedAccountLabel
import com.kevin.legion.ui.ledger.LedgerPendingResolver
import com.kevin.legion.util.documentDateCompact

/** One recent transaction, already worded. [unverified] only drives the tone: the words are in [detail]. */
internal data class MatrixTxn(val title: String, val detail: String, val unverified: Boolean)

/** One account's balance, worded. [unconfirmed] means the figure includes unconfirmed activity. */
internal data class MatrixBalance(val title: String, val detail: String, val unconfirmed: Boolean)

/**
 * The words on the Matrix ledger lookups (matrix-open ticket 06), pure so they are tested. CLAUDE.md
 * section 4 rule 7: a row no statement has confirmed says so in WORDS on its own row ("Unverified"),
 * a balance that includes unconfirmed activity says which kind, and a category that is still a guess
 * says "guess" - the tone only reinforces. Unreadable and empty are different sentences: a failed read
 * says "this is not zero" and never shows a balance of nothing.
 */
internal object MatrixLedgerText {
    const val LOADING = "Reading your accounts..."
    const val BALANCES_UNREADABLE = "Couldn't read the balances. This is not zero: nothing could be read."
    const val RECENT_UNREADABLE =
        "Couldn't read the recent transactions. This is not an empty list: nothing could be read."
    const val NO_ACCOUNTS = "No accounts yet. Statements reach LEGION through the household's engine."
    const val NO_TRANSACTIONS = "No transactions yet."
    const val UNVERIFIED_ROW = "Unverified - no bank statement has confirmed this row yet"
    const val PENDING_ROW = "Pending - you logged this yourself and the bank has not confirmed it"

    /** "ACCT ****1234  SGD 440.68", or "not stated" when no figure was ever printed - never 0.00. */
    fun balanceOf(balance: AccountBalance): MatrixBalance {
        val figure = if (balance.hasAnyFigure) formatMoney(balance.availableCents, balance.currency) else "not stated"
        val provisional = when {
            !balance.isProvisional -> null
            balance.balanceCents != null -> "includes pending transactions not yet on a statement"
            balance.hasReconciledRows -> "pending card activity only - this account's statements never print a balance"
            else -> "no statement yet - pending transactions only, unverified"
        }
        val lines = listOfNotNull(
            if (balance.asOfMs != null) {
                "as of ${documentDateCompact(balance.asOfMs)}"
            } else {
                "no balance ever printed for this account"
            },
            provisional,
            if (balance.hasPendingRows) LedgerPendingResolver.balanceNote(balance.pendingDeltaCents) else null,
        )
        return MatrixBalance(
            title = "${maskedAccountLabel(balance.accountId)}  $figure",
            detail = lines.joinToString("\n"),
            unconfirmed = balance.isUnconfirmed,
        )
    }

    fun txnOf(txn: LedgerTransaction, aliases: List<MerchantAlias>): MatrixTxn {
        val pending = txn.pendingLoggedAt != null
        val unverified = !pending && txn.ingestMethod == IngestMethod.UNRECONCILED
        val category = when {
            txn.category == null -> "Uncategorized"
            txn.categoryPending -> "${txn.category} (a guess, not confirmed)"
            else -> txn.category
        }
        val status = when {
            pending -> PENDING_ROW
            unverified -> UNVERIFIED_ROW
            else -> null
        }
        return MatrixTxn(
            title = "${documentDateCompact(txn.txnDate)}  " +
                "${formatMoney(txn.amountCents, txn.currency)}  ${displayDescription(txn.description, aliases)}",
            detail = listOfNotNull(maskedAccountLabel(txn.accountId), category, status).joinToString(" - "),
            unverified = pending || unverified,
        )
    }

    const val BALANCES_STALE = "Couldn't refresh the balances. These are the last ones read and may be out of date."
    const val RECENT_STALE = "Couldn't refresh the transactions. These are the last ones read and may be out of date."

    /** The line above the balances, or null when there are rows and the read was fine. */
    fun balancesNote(state: MatrixLedgerUiState): MatrixTextLine? = when {
        state.loading -> MatrixTextLine(LOADING, MatrixTone.DIM)
        state.balancesFailed && state.balances.isEmpty() -> MatrixTextLine(BALANCES_UNREADABLE, MatrixTone.ALERT)
        state.balancesFailed -> MatrixTextLine(BALANCES_STALE, MatrixTone.WARNING)
        state.balances.isEmpty() -> MatrixTextLine(NO_ACCOUNTS, MatrixTone.DIM)
        else -> null
    }

    fun recentNote(state: MatrixLedgerUiState): MatrixTextLine? = when {
        state.loading -> null
        state.recentFailed && state.recent.isEmpty() -> MatrixTextLine(RECENT_UNREADABLE, MatrixTone.ALERT)
        state.recentFailed -> MatrixTextLine(RECENT_STALE, MatrixTone.WARNING)
        state.recent.isEmpty() -> MatrixTextLine(NO_TRANSACTIONS, MatrixTone.DIM)
        else -> null
    }
}
