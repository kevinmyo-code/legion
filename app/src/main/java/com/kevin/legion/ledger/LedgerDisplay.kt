package com.kevin.legion.ledger

import com.kevin.legion.data.local.LedgerCurrency

/**
 * Display-time-only formatting for [com.kevin.legion.data.local.LedgerTransaction].
 * Everything here is a pure function of already-committed data - no I/O, no
 * Compose - specifically so it is unit-testable without Robolectric and so
 * ticket 08's UI layer never has to re-derive this logic per composable.
 *
 * **Nothing here ever touches the stored `description` column.** Ticket 08
 * resolution §4 fix 2 is explicit that the strip is display-time only, the
 * same rule ticket 04's amount/date normalization already follows: the
 * stored string is the row's provenance, an exact copy of what the bank
 * printed, and a future re-parse or audit has to compare against what was
 * actually on the statement, not a cleaned-up version of it.
 */

/**
 * `CHECKCARD 0701 ` - BofA's debit-card prefix, where the four digits are the
 * transaction's own posting date (MMDD), not a card number. Anchored to the
 * start of the string: this is a genuine leading prefix in BofA's format.
 */
private val CHECKCARD_PREFIX = Regex("""CHECKCARD \d{4} """)

/**
 * `DES:` / `INDN:` - ACH/NACHA batch descriptor tokens (payroll, GIRO, Zelle).
 * Unlike [CHECKCARD_PREFIX] these are not anchored to the start of the string
 * - BofA's ACH lines read `PAYROLL DES:DIRECT DEP ID:9928471 INDN:K MYO`, so
 * the tokens sit mid-string. The ticket 08 resolution still calls them
 * "prefixes" because each one prefixes its own sub-field (the descriptor,
 * the individual name); stripping the label and leaving the value it
 * labelled is what "removes noise from the dominant line" means in practice.
 */
private val ACH_LABEL_TOKENS = listOf("DES:", "INDN:")

/** Collapses runs of whitespace left behind by a strip back down to single spaces, and trims the ends. */
private val EXTRA_WHITESPACE = Regex(""" {2,}""")

/**
 * Strips the three known noise prefixes from [raw] for display, never
 * mutating [raw] itself. Safe to call on a description that matches none of
 * the three patterns - it is returned unchanged (modulo whitespace collapse,
 * which only fires if a strip actually happened).
 */
fun displayDescription(raw: String): String {
    var result = raw
    var stripped = false

    if (CHECKCARD_PREFIX.containsMatchIn(result)) {
        result = CHECKCARD_PREFIX.replace(result, "")
        stripped = true
    }
    for (token in ACH_LABEL_TOKENS) {
        if (result.contains(token)) {
            result = result.replace(token, "")
            stripped = true
        }
    }

    if (!stripped) return raw
    return EXTRA_WHITESPACE.replace(result, " ").trim()
}

/**
 * Formats signed cents as `"-1,234.56"` / `"41.00"` - grouped thousands, always
 * two decimal places, sign only for negative. No currency symbol or code;
 * see [formatMoney] for the currency-labelled form a mixed-currency list
 * needs (CLAUDE.md §4 rule three: this never touches `Double`).
 */
fun formatCents(cents: Long): String {
    val sign = if (cents < 0) "-" else ""
    val absolute = kotlin.math.abs(cents)
    val whole = absolute / 100
    val fraction = (absolute % 100).toString().padStart(2, '0')
    val grouped = whole.toString().reversed().chunked(3).joinToString(",").reversed()
    return "$sign$grouped.$fraction"
}

/**
 * Formats signed cents with a leading currency code (`"USD -87.34"`). The
 * ledger stream mixes SGD and USD rows in one flat list (ticket 08 resolution
 * §5 refuses to combine them into one number), so a bare amount is ambiguous
 * per row in a way it wouldn't be inside a single-currency statement view.
 * The code, not a symbol, because `$` alone doesn't disambiguate SGD from USD
 * and this app never invents an exchange rate to justify picking one.
 */
fun formatMoney(cents: Long, currency: LedgerCurrency): String = "${currency.name} ${formatCents(cents)}"
