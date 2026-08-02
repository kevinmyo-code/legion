package com.kevin.legion.ledger.parsers

/**
 * Port of Project Andromeda's `duo_ledger.bronze.parsers._money`
 * (`~/PycharmProjects/Andromeda`). Returns `Long` cents rather than Python's
 * `Decimal` - see [com.kevin.legion.data.local.LedgerTransaction]'s doc for why
 * exact integer minor-units, not `Double`, matter here.
 */
private val MONEY_RE = Regex("""^([-+]?)\$?(\d{1,3}(?:,\d{3})*|\d+)\.(\d{2})$""")

/**
 * Deliberately still `-?`, not `[-+]?`. This only LOCATES candidate substrings
 * in free text for [parseMoneyCents] to validate, and a leading `+` simply
 * falls outside the match: `+3,200.00` yields the token `3,200.00`, which
 * parses to the same positive cents. Widening it would change token
 * boundaries in the deterministic parsers to buy nothing.
 */
private val MONEY_TOKEN_RE = Regex("""-?\$?\d[\d,]*\.\d{2}""")

/**
 * Parses an exact statement amount token into signed cents.
 *
 * Rejects anything that isn't `[-+]$?digits.cents` with correct thousands
 * grouping - no float coercion, no guessing at ambiguous separators.
 *
 * **A leading `+` is accepted and means positive.** It used to be rejected,
 * which was a false negative rather than a safety property: `+1,025.00` is not
 * ambiguous, it is the same value as `1,025.00` stated explicitly. Statements
 * genuinely print it on credits, and the LLM path echoes the document's own
 * formatting into `statedTotal`, so any such statement quarantined forever
 * with "doesn't print a clear total to verify against". Found on device
 * 2026-08-02 by the first fixture built to pass the reconciliation gate,
 * which printed its net movement as `+1,025.00` and was refused.
 */
fun parseMoneyCents(token: String): Long {
    val match = MONEY_RE.matchEntire(token.trim())
        ?: throw GenericStatementParseException("cannot parse amount: '$token'")
    val (sign, whole, cents) = match.destructured
    val value = whole.replace(",", "").toLong() * 100 + cents.toLong()
    return if (sign == "-") -value else value
}

/** Locates candidate amount substrings in free text for later [parseMoneyCents] validation. */
fun findMoneyTokens(text: String): List<String> =
    MONEY_TOKEN_RE.findAll(text).map { it.value }.toList()
