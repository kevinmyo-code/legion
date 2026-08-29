package com.kevin.legion.ledger

/**
 * **Extracted from the now-deleted `ledger/parsers/LedgerMoney.kt`, backend-erp ticket 25**
 * ("statement ingestion leaves the phone entirely"). That file was a straight port of Project
 * Andromeda's `duo_ledger.bronze.parsers._money`, built for the statement parsers under
 * `ledger/parsers/` - every one of which is gone now that the phone never ingests a bank
 * statement. [parseMoneyCents] itself has a second, unrelated caller that predates none of this
 * deletion touches: [com.kevin.legion.pantry.PantryReceiptAgent] parses the same
 * `[-+]$?digits.cents` shape out of a receipt's OWN printed figures (total, per-item price) as
 * part of the pantry reconciliation gate. Kept as its own small file in the `ledger` package
 * (unchanged from where pantry's import already pointed) rather than duplicated into `pantry/`,
 * since the arithmetic - and its "no float coercion, no guessing at ambiguous separators" posture -
 * has nothing to do with which aspect is calling it.
 *
 * Returns `Long` cents rather than a `Double` - see [com.kevin.legion.data.local.LedgerTransaction]'s
 * doc for why exact integer minor-units matter here (CLAUDE.md §4 rule 3).
 */
private val MONEY_RE = Regex("""^([-+]?)\$?(\d{1,3}(?:,\d{3})*|\d+)\.(\d{2})$""")

/**
 * Parses an exact amount token into signed cents.
 *
 * Rejects anything that isn't `[-+]$?digits.cents` with correct thousands grouping - no float
 * coercion, no guessing at ambiguous separators.
 *
 * **A leading `+` is accepted and means positive.** `+1,025.00` is not ambiguous, it is the same
 * value as `1,025.00` stated explicitly - some sources print it on credits.
 */
fun parseMoneyCents(token: String): Long {
    val match = MONEY_RE.matchEntire(token.trim())
        ?: throw IllegalArgumentException("cannot parse amount: '$token'")
    val (sign, whole, cents) = match.destructured
    val value = whole.replace(",", "").toLong() * 100 + cents.toLong()
    return if (sign == "-") -value else value
}
