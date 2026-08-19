package com.kevin.legion.ledger

import com.kevin.legion.data.local.CategoryRule

/**
 * Pure categorisation logic (`.scratch/legion-shape/issues/07-categorisation.md`, D14-D19). No
 * Room, no Android - unit-testable without Robolectric, matching [buildBudgetVsActual]'s shape.
 */

/**
 * D16: the first rule (by [CategoryRule.createdAt], oldest first) whose [CategoryRule.substring]
 * appears in [description]'s UPPERCASE form wins. This mirrors
 * [com.kevin.legion.data.local.LedgerTransactionDao.applyCategoryRule]'s SQL `LIKE` exactly - kept
 * as a separate pure function (rather than only living in SQL) so the matching RULE itself is
 * unit-testable without a database, and so a future caller that already holds rules in memory
 * (e.g. previewing "what would this rule catch") doesn't have to round-trip through Room to ask.
 *
 * Null when nothing matches - the description stays uncategorised (D11) until a rule exists or a
 * guess is confirmed.
 */
fun matchCategory(description: String, rules: List<CategoryRule>): String? {
    val upper = description.uppercase()
    return rules.sortedBy { it.createdAt }.firstOrNull { upper.contains(it.substring) }?.category
}

/**
 * A store-number-shaped token: a literal `#1234` or a bare run of 3+ digits. Anchors
 * [extractMerchantKey]'s split point - see that function's doc comment for the worked examples
 * this is verified against.
 */
private val STORE_NUMBER_TOKEN = Regex("""#\d+|\b\d{3,}\b""")

/**
 * Bank-generated boilerplate that precedes the real merchant text on a Bank of America card line -
 * `CHECKCARD  0429 TMOBILE PREPD BELLEVUE WA` starts with a transaction TYPE, not a merchant, and
 * neither [extractMerchantKey] nor a hand- or voice-written [CategoryRule] may ever anchor on it
 * alone. Found and fixed 2026-08-13: a stored `category_rules` row with substring `CHECKCARD` had
 * silently confirmed 48 unrelated transactions - Walmart, Panda Express, T-Mobile among them - into
 * "Subscriptions", because [extractMerchantKey] used to split at the first 3+-digit run, which on
 * these lines is the MMDD posting date immediately after this word, not a store number.
 *
 * Exposed `internal` (not `private`) so [com.kevin.legion.ledger.LedgerController.setCategory] and
 * the drill-down recategorise panel can refuse to install a rule whose substring IS one of these -
 * see [isBankNoiseKey].
 */
internal val BANK_NOISE_PREFIXES = listOf("CHECKCARD", "CHKCARD", "PURCHASE")

/**
 * A leading [BANK_NOISE_PREFIXES] word, optionally followed by the 3-to-6 digit date-shaped token
 * BofA prints right after it (`0429`, `010826`) and the whitespace separating either from the real
 * merchant text. Anchored at the START of the uppercased description only, so a merchant whose name
 * happens to CONTAIN one of these words (there is no known real example, but the anchor costs
 * nothing) is left untouched - only the literal leading boilerplate is stripped.
 */
private val BANK_NOISE_PREFIX_PATTERN = Regex(
    "^(?:${BANK_NOISE_PREFIXES.joinToString("|")})\\s+(?:\\d{3,6}\\s+)?"
)

/** Collapses any run of internal whitespace to a single space, leaving other characters untouched. */
private fun String.collapseSpaces(): String = replace(Regex("""\s+"""), " ")

/**
 * True when [key] (already trimmed/uppercased the way [extractMerchantKey]/
 * [com.kevin.legion.ledger.LedgerController.setCategory] both normalise) IS a [BANK_NOISE_PREFIXES]
 * word on its own, or that word followed only by a date-shaped fragment - never a substring match,
 * so a real merchant that happens to contain "PURCHASE" somewhere in its name is not caught by
 * this. This is the systemic half of the 2026-08-13 fix: stripping the prefix in
 * [extractMerchantKey] stops the trap from being auto-guessed again, but a driver or voice command
 * can still type/say the bare noise word directly, and the moment that happens the trap re-arms
 * itself exactly as before. Callers that create a [CategoryRule] must check this BEFORE writing,
 * never after.
 *
 * **Extended 2026-08-13 (`.scratch/car-probe-transfers/`) to also refuse a [TRANSFER_KEYWORDS]-shaped
 * key.** Bank-noise prefixes and transfer wording are refused through the SAME gate on purpose,
 * not two independent checks: both describe text that fails the one question a [CategoryRule]
 * substring must answer - "is this a merchant" - for the same underlying reason, just from opposite
 * ends. `CHECKCARD` names a transaction TYPE with no merchant at all; `PAYMENT TO CRD` names a
 * transaction that never had one to begin with, because moving your own money between your own
 * accounts has no merchant and no category. A rule anchored on either one would confirm whatever it
 * matches into a category that means nothing for that row. This check is a substring match, not the
 * prefix-anchored [BANK_NOISE_PREFIX_PATTERN] used above, because a transfer keyword can appear
 * anywhere in a spoken or typed key ("mobile banking payment to crd", not just a bare leading word).
 */
internal fun isBankNoiseKey(key: String): Boolean {
    val normalized = key.trim().uppercase()
    if (normalized.isBlank()) return false
    // Padded with a trailing space so the pattern's mandatory `\s+` after the noise word can match
    // even when there's nothing else in the key at all (`"CHECKCARD"` alone, no trailing date or
    // merchant text) - what's left after stripping is the real test: blank means the whole key was
    // noise, anything else means real merchant text survived and this is a normal, if odd, key.
    if (BANK_NOISE_PREFIX_PATTERN.replaceFirst("$normalized ", "").isBlank()) return true
    return TRANSFER_KEYWORDS.any { normalized.contains(it.uppercase()) }
}

/**
 * Derives the auto-created rule substring D18 needs from a raw description: strips a leading
 * [BANK_NOISE_PREFIXES] word (and the date-shaped token that follows it, if any) first, then finds
 * everything before the first store-number-shaped token in what remains, uppercased and trimmed,
 * with internal whitespace collapsed to single spaces. This is also the key
 * [com.kevin.legion.ledger.LedgerController.uncategorizedMerchants] groups distinct descriptions
 * by before they're ever sent to [CategoryAgent] - so the SAME chain (`KROGER #115 CYPRESS TX` and
 * `KROGER #122 KATY TX`) is guessed exactly once, not once per store number, closing D18's "a
 * merchant is guessed at most once, ever" at the guessing step, not only at the confirmed-rule
 * step.
 *
 * **Tuned against Kevin's real Bank of America card statements on 2026-08-13** (see
 * [BANK_NOISE_PREFIXES]'s doc comment for the bug this closes), plus ticket 07 §2's original two:
 * `CHECKCARD  0429 TMOBILE PREPD BELLEVUE     WA` -> `TMOBILE PREPD BELLEVUE WA`,
 * `PURCHASE   0108 eBay O*08-12555-3 4083766151   CA` -> `EBAY O*08-` (the store-number split still
 * fires on the `12555` run inside the reference number - a narrow, over-eager key here is a
 * correctable annoyance, never a silently wrong one), `CHECKCARD  0115 WM SUPERCENTER KATY
 * TX` -> `WM SUPERCENTER KATY TX`, `WM SUPERCENTER #4512 KATY TX` -> `WM SUPERCENTER`,
 * `KROGER #115 CYPRESS TX` -> `KROGER`. A description this heuristic can't shorten (no bank-noise
 * prefix and no digit run at all) falls back to the whole trimmed, uppercased, whitespace-collapsed
 * string, which still produces a correct (if possibly over-narrow) rule for that one exact wording -
 * the same "editable, not perfect on day one" posture D16 already accepts for hand-written rules.
 */
internal fun extractMerchantKey(description: String): String {
    val upper = description.uppercase().trim()
    val afterPrefix = BANK_NOISE_PREFIX_PATTERN.replaceFirst(upper, "").trim().ifBlank { upper }
    val match = STORE_NUMBER_TOKEN.find(afterPrefix)
    val key = if (match != null) {
        afterPrefix.substring(0, match.range.first).trim().ifBlank { afterPrefix }
    } else {
        afterPrefix
    }
    return key.collapseSpaces()
}
