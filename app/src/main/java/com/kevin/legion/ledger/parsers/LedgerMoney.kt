package com.kevin.legion.ledger.parsers

/**
 * Port of Project Andromeda's `duo_ledger.bronze.parsers._money`
 * (`~/PycharmProjects/Andromeda`). Returns `Long` cents rather than Python's
 * `Decimal` - see [com.kevin.legion.data.local.LedgerTransaction]'s doc for why
 * exact integer minor-units, not `Double`, matter here.
 */
private val MONEY_RE = Regex("""^(-?)\$?(\d{1,3}(?:,\d{3})*|\d+)\.(\d{2})$""")
private val MONEY_TOKEN_RE = Regex("""-?\$?\d[\d,]*\.\d{2}""")

/**
 * Parses an exact statement amount token into signed cents.
 *
 * Rejects anything that isn't `[-]$?digits.cents` with correct thousands
 * grouping - no float coercion, no guessing at ambiguous separators.
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
