package com.kevin.legion.ledger

import com.kevin.legion.data.local.LedgerTransaction

/**
 * Pure logic behind the three voice tools `log_pending_transaction` / `list_pending_transactions`
 * / `clear_pending_transaction` (`service/LiveToolbox.kt`). Split out from the toolbox the same
 * way [groupAccountBalances] is: no `Context`, no Room, so this is a plain JVM unit test rather
 * than needing Robolectric.
 *
 * These rows are REPORTED tier (CLAUDE.md §4 rule 7's vocabulary) - a driver's own spoken word,
 * never proven by any bank export. See [com.kevin.legion.data.local.LedgerTransaction.pendingLoggedAt]'s
 * doc comment for the full reasoning on why this is distinct from a file-derived UNRECONCILED row.
 */

/** Which account (if any) a spoken `account` string resolves to, out of the accounts on file. */
sealed class PendingAccountResolution {
    data class Resolved(val account: AccountBalance) : PendingAccountResolution()

    /** Nothing on file matched at all - `import a statement first`, never a fabricated accountId. */
    object NoMatch : PendingAccountResolution()

    /** More than one candidate matched - the caller must ask which, listing [candidates] by name. */
    data class Ambiguous(val candidates: List<AccountBalance>) : PendingAccountResolution()
}

/**
 * Resolves a driver's spoken `account` string against the accounts already on file, using the
 * SAME loose matching `get_balance` already uses (`LiveToolbox.getLedgerBalance`): exact,
 * substring (case-insensitive), or the same physical card by last-4 ([sameCard]). Unlike
 * `get_balance`, an empty [account] does NOT mean "every account" here - logging a pending charge
 * needs exactly one target, so a blank name only resolves when precisely one account exists on
 * file at all; two or more on file with nothing named is [PendingAccountResolution.Ambiguous].
 *
 * **Never fabricates an accountId.** This mirrors [com.kevin.legion.ledger.parsers.UnmappedAccountException]'s
 * discipline one layer up: a voice tool that can't confidently name the account a charge belongs
 * to must refuse and ask, not guess.
 */
fun resolveAccountForPending(balances: List<AccountBalance>, account: String): PendingAccountResolution {
    val matched = if (account.isBlank()) {
        balances
    } else {
        balances.filter { balance ->
            balance.accountId.contains(account, ignoreCase = true) || sameCard(balance.accountId, account)
        }
    }
    return when {
        matched.isEmpty() -> PendingAccountResolution.NoMatch
        matched.size == 1 -> PendingAccountResolution.Resolved(matched.first())
        else -> PendingAccountResolution.Ambiguous(matched)
    }
}

/** The absurd-transcription guard (spec: a spoken-number misfire, not a real charge). */
const val PENDING_AMOUNT_MAX_CENTS: Long = 100_000_000L

/**
 * Converts a spoken magnitude (always positive, in the account's own currency) plus an explicit
 * `debit`/`credit` direction into signed `Long` cents (CLAUDE.md §4 rule 3 - money is never
 * `Double`). Direction is a required, explicit parameter - NEVER inferred from the sign of a
 * spoken number, because a driver saying "negative fifty dollars" to mean a $50 charge is exactly
 * the kind of ambiguity a spoken interface invites and a typed form doesn't.
 *
 * Returns null for anything that isn't loggable as money: non-positive, non-finite, or a rounded
 * cents value past [PENDING_AMOUNT_MAX_CENTS] - a transcription guard against a misheard number
 * ("fifty" heard as "fifty thousand"), not a real spending limit.
 */
fun pendingAmountCents(amount: Double, direction: String): Long? {
    if (amount.isNaN() || amount.isInfinite() || amount <= 0.0) return null
    val magnitudeCents = Math.round(amount * 100.0)
    if (magnitudeCents <= 0L || magnitudeCents > PENDING_AMOUNT_MAX_CENTS) return null
    return if (direction.equals("credit", ignoreCase = true)) magnitudeCents else -magnitudeCents
}

/** Which pending row(s), if any, a spoken description matches, for `clear_pending_transaction`. */
sealed class PendingClearMatch {
    data class Resolved(val row: LedgerTransaction) : PendingClearMatch()

    /** Nothing pending matched [query] at all. */
    object NoMatch : PendingClearMatch()

    /** More than one pending row matched - the caller must ask which, listing [candidates]. */
    data class Ambiguous(val candidates: List<LedgerTransaction>) : PendingClearMatch()
}

/**
 * Matches [query] case-insensitively as a substring of a pending row's [LedgerTransaction.description] -
 * only ever called against rows already filtered to `pendingLoggedAt != null` (`pendingRows()`), so
 * this can never resolve to a reconciled or file-derived row by construction, independent of the
 * DAO-layer `AND pendingLoggedAt IS NOT NULL` guard on the delete itself.
 */
fun matchPendingByDescription(pending: List<LedgerTransaction>, query: String): PendingClearMatch {
    val matched = pending.filter { it.description.contains(query, ignoreCase = true) }
    return when {
        matched.isEmpty() -> PendingClearMatch.NoMatch
        matched.size == 1 -> PendingClearMatch.Resolved(matched.first())
        else -> PendingClearMatch.Ambiguous(matched)
    }
}
