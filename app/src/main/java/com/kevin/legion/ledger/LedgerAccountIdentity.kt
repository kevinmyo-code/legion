package com.kevin.legion.ledger

/**
 * Ticket 12 §0 - the one flagged collision in the provisional-card-CSV
 * design, absorbed here rather than by rewriting a stored `accountId`.
 *
 * [BofaCardCsvStatementParser] stores `accountId = "7823"` (the filename's
 * last-4, call 2 - the only identity the mid-cycle CSV export states about
 * itself). [BofaCardStatementParser] stores the full printed account,
 * `"5555555555557823"`, from the monthly PDF for the SAME physical card.
 * These are different strings, and every OTHER mechanism in the ledger keys
 * `accountId` on plain equality - so left alone, the provisional rows from
 * one export can never be found, matched, or superseded by the other.
 *
 * [sameCard] is a suffix relation, used in exactly three places (never
 * folded into [dedupKey]'s equality, never used to rewrite a stored
 * `accountId`): the provisional supersede delete (ticket 12 §3, see
 * `LedgerTransactionDao.deleteSupersededProvisional`), the adjusted-balance
 * pairing (ticket 12 §5-6, see `LedgerController.accountBalances`), and
 * `BalancesSection`'s grouping in the UI. **`resolveDedup` in
 * [LedgerDedup] is deliberately NOT changed to use this** - loosening the
 * dedup key to a suffix match would let a checking account whose id happens
 * to end in the same four digits absorb a card's rows, which is a
 * materially worse bug than the one this file exists to fix.
 *
 * **Known weakness, stated rather than hidden**: a last-4 suffix match
 * collides if two accounts genuinely share their last four digits. Kevin has
 * four accounts today (2026-08-06) and no such collision - this is a
 * documented limit accepted at call time (ticket 12 §0), not an oversight
 * the next person should assume was never considered.
 */
fun sameCard(a: String, b: String): Boolean =
    a == b || (a.length >= 4 && b.length >= 4 && a.takeLast(4) == b.takeLast(4))

/**
 * The FOUR bank-printed account-type tokens Kevin's real statements use immediately before an
 * account's digits - `CRD`/`CHK`/`SAV`/`ACCT` - each optionally followed by a `#`, then the digit
 * run itself. Deliberately a closed, hand-picked set rather than "any letters then digits":
 * measured against Kevin's real BofA rows (`.scratch/car-probe-transfers/`, 2026-08-13), that
 * looser shape also matches `PETCO 5421`, `CIRCLE K # 48267`, `HILL 71 RD`, and half a dozen other
 * merchant addresses and store numbers that have nothing to do with an account.
 */
private val ACCOUNT_REFERENCE = Regex("""(?i)\b(?:CRD|CHK|SAV|ACCT)\s*#?\s*(\d{4,})\b""")

/**
 * Whether [description] names an account in [ownAccountIds] by its printed digits - the sharper,
 * evidence-based test [analyzeTransfers]'s own-account pass uses instead of [TRANSFER_KEYWORDS]
 * wording (Kevin, 2026-08-13, reversing the 2026-08-07 "wording alone never excludes spend" call -
 * see [ExclusionReason.OWN_ACCOUNT_MOVEMENT]'s own doc comment for why). A description that names
 * no digits at all - `Zelle payment to JANE DOE` - can never match here by construction, which is
 * the whole point: a person is not an account, and this function has no way to mistake one for one.
 *
 * **Deliberately conservative.** [ownAccountIds] must be accounts that have ACTUALLY had a
 * statement imported ([com.kevin.legion.data.local.LedgerTransactionDao.accountIdsForCurrency]),
 * never every account a description happens to NAME. Kevin's real checking statement references a
 * savings account ("SAV 8267") that has never itself had a statement imported - no accountId on
 * file ends in 1490 - so a row naming it returns `false` here, even though it is obviously his own
 * account in reality. Per this repo's own instruction for this call: the failure that matters is
 * wrongly EXCLUDING real spend, and an unmatched reference only falls back to whatever
 * [TRANSFER_KEYWORDS] already decided for it (the pre-existing, unchanged status quo) - never
 * worse than that.
 */
fun referencesOwnAccount(description: String, ownAccountIds: Set<String>): Boolean {
    if (ownAccountIds.isEmpty()) return false
    return ACCOUNT_REFERENCE.findAll(description).any { match ->
        val digits = match.groupValues[1]
        ownAccountIds.any { sameCard(it, digits) }
    }
}

/**
 * An `accountId` as it should be SHOWN, never as it is stored.
 *
 * A stored id is whatever the statement stated about itself, and for
 * [BofaCardStatementParser] that is the full printed card number - a real
 * 16-digit PAN. Mission-control ticket 16 put a BALANCES tile on the CRED
 * root, which rendered `primary.accountId` directly, and the number appeared
 * in full on a surface reached by one tap from the app's home. Caught by
 * screenshotting the real device rather than by reading the diff; the
 * previews used `"BOFA ****4471"` and so looked correct in every mock.
 *
 * The rule this applies: **a stored identifier and a displayed one are not
 * the same string.** Matching, dedup and supersede logic all keep reading the
 * stored value through [sameCard] and friends - masking here changes nothing
 * about identity, only about what is painted.
 *
 * A run of five or more digits is masked to its last four. Shorter ids are
 * returned unchanged: `BofaCardCsvStatementParser` already stores a bare
 * last-4 (`"7823"`), which is not a secret and reads worse as `"****7823"`.
 * Non-digit labels (`"DBS Multiplier"`) pass through untouched.
 */
fun maskedAccountLabel(accountId: String): String {
    val digits = accountId.filter { it.isDigit() }
    if (digits.length < 5) return accountId
    return Regex("""\d{5,}""").replace(accountId) { m -> "****" + m.value.takeLast(4) }
}
