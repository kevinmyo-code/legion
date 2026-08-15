package com.kevin.legion.ledger.parsers

import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.LedgerTransaction
import com.kevin.legion.ledger.formatCents
import java.io.InputStream
import java.time.DateTimeException
import java.time.LocalDate
import java.time.Month
import java.time.ZoneOffset
import java.util.Locale

/**
 * Bank of America **credit card** monthly statement PDF - a sibling to
 * [BofaStatementParser] (checking) with a different layout and a different
 * set of recognition anchors, so the two never collide. Kevin's 2026-08-03
 * decision: daily spend is moving to debit, so this statement becomes the
 * ONLY ingestion path for the card account - its mid-cycle CSV export
 * ([BofaCardCsvStatementParser]) is explicitly rejected, never accepted,
 * because it states no balance or total to reconcile against (CLAUDE.md
 * §4 rule 2).
 *
 * Layout, verified against Kevin's real July statement 2026-08-03 (a
 * synthetic fixture with invented figures ships in
 * `src/test/resources/ledger_fixtures` - the real file never enters the
 * repo, same discipline as [BofaStatementParser]'s multiline fixtures):
 * ```
 * Account# 4111 1111 1111 7823
 * June 6 - July 5, 2026
 * New Balance Total $634.56                     <- marketing header, NOT the summary total
 * Account Summary/Payment Information
 * Previous Balance $1,575.24
 * Payments and Other Credits -$3,970.38
 * Purchases and Adjustments $3,029.70
 * Fees Charged $0.00
 * Interest Charged $0.00
 * New Balance Total $634.56                     <- the real one, inside the summary block
 * ...
 * Transactions
 * Transaction / Date / Posting / Date Description / Reference / Number / Account / Number Amount  Total
 * Payments and Other Credits
 * 06/08 06/09 PAYMENT FROM CHK 5042 CONF#9qz4rmxbf 1656 7823 -350.00
 * ...
 * TOTAL PAYMENTS AND OTHER CREDITS FOR THIS PERIOD -$3,970.38
 * Purchases and Adjustments
 * ...
 * TOTAL PURCHASES AND ADJUSTMENTS FOR THIS PERIOD $3,029.70
 * Interest Charged
 * ...
 * TOTAL INTEREST CHARGED FOR THIS PERIOD $0.00
 * Transactions Continued                        <- the trap: repeats the column headers
 * ... 2026 Totals Year-to-Date ...               <- and this, neither of them a transaction
 * ```
 *
 * **"New Balance Total" prints TWICE.** [parse] reads it (and every other
 * summary figure) out of [summaryText], the substring of the raw text
 * starting at `Account Summary/Payment Information` - the marketing header
 * sits BEFORE that marker in document order, so it is structurally
 * excluded, not filtered by a heuristic.
 *
 * **A section header is told apart from its own summary-block echo by
 * EXACT line equality, not `startsWith`.** The summary block prints
 * "Payments and Other Credits -$3,970.38" (label and amount on the SAME
 * line); the real transaction-table header is a bare line with nothing
 * else on it. [extractSection] only accepts the bare form, which is what
 * lets it skip straight past the summary occurrence without
 * [BofaStatementParser.extractSection]'s "does the next line say Date"
 * lookahead trick - this layout doesn't need it because the two
 * occurrences are textually different, not just positionally different.
 *
 * **The "Transactions Continued" / YTD trap never gets a chance to leak
 * in**, not because it's filtered, but because [extractSection] only ever
 * scans between a section's own header and its own `TOTAL ... FOR THIS
 * PERIOD` line - nothing after the last section's total line is ever
 * examined. Parsing "every date-led line in the document" (which is what
 * [BofaStatementParser] effectively did before its section-total fix) is
 * exactly the bug this structure avoids by construction.
 *
 * **"Fees Charged" is a real reconciliation anchor (gate layer 2) but is
 * NOT guaranteed a transaction section of its own** - a cycle with no fees
 * commonly omits the section from the Transactions region entirely rather
 * than printing an empty one with a $0.00 total. [SECTIONS] therefore
 * treats every section as OPTIONAL: a missing section contributes zero
 * rows silently. Gate layer 3 (the cross-check against the summary's own
 * net movement) does not depend on which sections happened to print, so a
 * real amount hiding behind a section that vanished from the text is still
 * caught there.
 *
 * **Every stored `amountCents` sign is FLIPPED from what the statement
 * prints.** Kevin's 2026-08-07 decision: money leaving Kevin is negative on
 * EVERY account, not just checking. This card's own paper prints the
 * opposite of that - a purchase positive, a payment negative (see the class
 * example above: `PAYMENT FROM CHK ... -350.00`, purchases with no sign at
 * all) - so every reconciliation check in [parse] (all three gate layers,
 * plus [parseSectionBody]'s own positive/negative-agnostic sum) runs
 * entirely in the DOCUMENT's own printed convention, comparing like for
 * like against the statement's own totals. The negation happens exactly
 * once, at the very end of [parse], strictly AFTER every gate has already
 * passed - so the flip itself never gets a chance to fool a check it never
 * participates in.
 */
object BofaCardStatementParser {
    private const val SUMMARY_MARKER = "Account Summary/Payment Information"
    private const val TRANSACTIONS_MARKER = "Transactions"

    // Deliberately `Account\s*#\s*`, not BofaStatementParser's `Account
    // (?:number|#)` - this layout prints "Account#" with no space before
    // the `#`, which that regex's mandatory literal space would reject.
    private val ACCOUNT_RE = Regex("""Account\s*#\s*([\d ]{4,})""")
    private val PERIOD_RE = Regex("""^(\p{Alpha}+) (\d{1,2}) - (\p{Alpha}+) (\d{1,2}), (\d{4})$""")

    // Group 1/2: transaction date, posting date. Group 3: description
    // (greedy - see this file's KDoc on why greedy, not lazy, is the
    // correct choice here). Group 4/5: reference number, last-4 account
    // digits. Group 6: signed amount. Anchored both ends so the only valid
    // split is the rightmost one where the trailing three tokens actually
    // look like ref/acct/amount.
    private val ROW_RE = Regex(
        """^(\d{2}/\d{2})\s+(\d{2}/\d{2})\s+(.+)\s+(\d+)\s+(\d{4})\s+(-?\$?[\d,]+\.\d{2})$"""
    )

    /**
     * Interest Charged rows (and, per the same real-statement finding,
     * likely Fees Charged rows whenever a fee actually posts) carry NO
     * reference number and NO account number at all -
     * `MM/DD MM/DD <description> <amount>`, not [ROW_RE]'s ref/acct-bearing
     * form. Confirmed on Kevin's real July 2026 statement: that month's
     * interest happened to total $0.00, which meant the four dropped rows
     * reconciled vacuously (0 parsed rows == a $0.00 stated total) instead
     * of tripping gate layer 1 - see [parseSectionBody]'s hard-failure
     * behavior, which is what actually closes that hole; this second row
     * shape only stops the FIRST cause of the drop, not the vacuous-pass
     * risk itself. [parse] always tries [ROW_RE] before this one, so a
     * bare-form description that happens to end in digit tokens is never
     * mis-split into a fabricated ref/acct pair.
     */
    private val BARE_ROW_RE = Regex(
        """^(\d{2}/\d{2})\s+(\d{2}/\d{2})\s+(.+)\s+(-?\$?[\d,]+\.\d{2})$"""
    )

    private data class Section(val name: String, val totalPrefix: String)

    private val SECTIONS = listOf(
        Section("Payments and Other Credits", "TOTAL PAYMENTS AND OTHER CREDITS FOR THIS PERIOD"),
        Section("Purchases and Adjustments", "TOTAL PURCHASES AND ADJUSTMENTS FOR THIS PERIOD"),
        Section("Fees Charged", "TOTAL FEES CHARGED FOR THIS PERIOD"),
        Section("Interest Charged", "TOTAL INTEREST CHARGED FOR THIS PERIOD"),
    )

    /**
     * The statement period's month/year mapping. [startMonth] and
     * [startYear] answer "what year does this month belong to if it's at
     * or after the period's start month"; every other month gets [endYear]
     * (the year BofA actually prints, at the tail of the period line).
     *
     * Normal case (no year boundary crossed): [startMonth] <= end month,
     * so [startYear] == [endYear] and every transaction gets the same
     * year regardless of which branch [yearForMonth] takes - the formula
     * degrades to "everyone gets the printed year" for free.
     *
     * December -> January case: [startMonth] (12) > end month (1), so
     * [startYear] is [endYear] - 1. A December transaction (month 12 >=
     * startMonth 12) gets [startYear]; a January transaction (month 1 <
     * startMonth 12) gets [endYear]. This is the one case that silently
     * corrupts a year of data if got wrong, per this ticket - tested
     * explicitly with a Dec-Jan fixture.
     */
    private data class YearBounds(val startMonth: Int, val startYear: Int, val endYear: Int)

    fun parse(fileName: String, input: InputStream): List<LedgerTransaction> {
        val text = PdfText.extractText(input)
        val lines = text.lines().map { it.trim() }

        // Recognition: unambiguous against both other deterministic
        // parsers. BofaStatementParser keys on "Account number"/"Account #"
        // plus "Beginning balance on ..."; DbsStatementParser keys on
        // "Account No.". Neither of those markers appears on a real DBS or
        // BofA-checking statement, and this layout's markers don't appear
        // on either of those either - verified by inspection of both other
        // parsers' own anchor strings, not just by absence of collision in
        // testing.
        if (!text.contains(SUMMARY_MARKER) || !text.contains("New Balance Total")) {
            throw UnrecognizedLayoutException("no '$SUMMARY_MARKER' summary block found in $fileName")
        }

        val accountMatch = ACCOUNT_RE.find(text)
            ?: throw UnrecognizedLayoutException("no Bank of America card account number found in $fileName")
        val accountId = accountMatch.groupValues[1].replace(Regex("""\s+"""), "")

        val periodMatch = lines.firstNotNullOfOrNull { PERIOD_RE.matchEntire(it) }
            ?: throw UnrecognizedLayoutException("no statement period line found in $fileName")
        val yearBounds = periodYearBounds(periodMatch, fileName)

        // Everything below is a structural failure of a layout this
        // function has already committed to recognizing, so from here on
        // out every throw is GenericStatementParseException or
        // BalanceContinuityException (quarantine), never
        // UnrecognizedLayoutException (try-the-next-parser).
        val summaryStart = text.indexOf(SUMMARY_MARKER)
        val summaryText = text.substring(summaryStart)

        val previousBalance = summaryAmount(summaryText, "Previous Balance", fileName)
        val paymentsCredits = summaryAmount(summaryText, "Payments and Other Credits", fileName)
        val purchasesAdjustments = summaryAmount(summaryText, "Purchases and Adjustments", fileName)
        val feesCharged = summaryAmount(summaryText, "Fees Charged", fileName)
        val interestCharged = summaryAmount(summaryText, "Interest Charged", fileName)
        val newBalanceTotal = summaryAmount(summaryText, "New Balance Total", fileName)

        // Gate layer 2: the summary identity. Every figure is already
        // signed as printed (payments print negative), so plain addition
        // is correct - no separate credit/debit bookkeeping needed.
        val summaryComputed = previousBalance + paymentsCredits + purchasesAdjustments + feesCharged + interestCharged
        if (summaryComputed != newBalanceTotal) {
            throw BalanceContinuityException(
                "previous $previousBalance + payments $paymentsCredits + purchases $purchasesAdjustments " +
                    "+ fees $feesCharged + interest $interestCharged = $summaryComputed != " +
                    "stated new balance $newBalanceTotal",
                userMessage = "This statement's own summary doesn't add up: previous balance " +
                    "${formatCents(previousBalance)}, plus payments ${formatCents(paymentsCredits)}, " +
                    "purchases ${formatCents(purchasesAdjustments)}, fees ${formatCents(feesCharged)}, " +
                    "and interest ${formatCents(interestCharged)}, comes to " +
                    "${formatCents(summaryComputed)}, not the ${formatCents(newBalanceTotal)} it states " +
                    "as the new balance. Nothing was imported.",
            )
        }

        val transactionsIdx = lines.indexOfFirst { it == TRANSACTIONS_MARKER }
        if (transactionsIdx < 0) {
            throw GenericStatementParseException(
                "no '$TRANSACTIONS_MARKER' section header found in $fileName",
                userMessage = "This statement doesn't have a transactions section LEGION recognizes. " +
                    "Nothing was imported.",
            )
        }

        val transactions = mutableListOf<LedgerTransaction>()
        var netMovement = 0L
        for (section in SECTIONS) {
            val extracted = extractSection(lines, transactionsIdx, section, fileName) ?: continue
            val (body, statedTotal) = extracted
            val sectionTxns = parseSectionBody(body, fileName, accountId, yearBounds)
            val actualTotal = sectionTxns.sumOf { it.amountCents }
            // Gate layer 1: this section's own rows against its own
            // printed total.
            if (actualTotal != statedTotal) {
                throw BalanceContinuityException(
                    "${section.totalPrefix}: statement says $statedTotal, transactions sum to $actualTotal",
                    userMessage = "The \"${section.name}\" section doesn't add up. The statement says " +
                        "${formatCents(statedTotal)}, but its own lines sum to ${formatCents(actualTotal)}. " +
                        "Nothing was imported.",
                )
            }
            transactions.addAll(sectionTxns)
            netMovement += actualTotal
        }

        // Gate layer 3: every parsed row, across every section that
        // actually printed one, sums to the summary's own net movement -
        // everything except Previous Balance and New Balance Total, which
        // are the opening/closing anchors rather than period activity.
        val expectedNet = paymentsCredits + purchasesAdjustments + feesCharged + interestCharged
        if (netMovement != expectedNet) {
            throw BalanceContinuityException(
                "all parsed rows sum to $netMovement, summary's own net movement is $expectedNet",
                userMessage = "This statement's transactions sum to ${formatCents(netMovement)}, but its " +
                    "own summary states ${formatCents(expectedNet)} in net movement for the period. " +
                    "Nothing was imported.",
            )
        }

        // The sign flip (see this object's class-level KDoc): every gate
        // above ran in the document's own printed convention (purchase
        // positive, payment negative) and already passed, so this is purely
        // a display/storage transform - never a value the gate itself
        // depended on. `money leaving Kevin is negative` (CLAUDE.md, Kevin's
        // 2026-08-07 decision) now holds for this account exactly as it
        // already did for checking (BofaStatementParser stores a debit
        // negative already, no flip needed there).
        return transactions.map { it.copy(amountCents = -it.amountCents) }
    }

    /**
     * Locates one section's body and its own stated total, scoped to
     * lines strictly after [afterIdx] (the "Transactions" header) so a
     * bare match can never land inside the summary block, which always
     * precedes it. Returns null - not an error - when the section's bare
     * header line never appears at all: see this object's KDoc on why a
     * missing section (most commonly "Fees Charged") is expected, not
     * corrupt.
     */
    private fun extractSection(
        lines: List<String>, afterIdx: Int, section: Section, fileName: String,
    ): Pair<List<String>, Long>? {
        val startIdx = (afterIdx + 1 until lines.size).firstOrNull { lines[it] == section.name } ?: return null
        val totalIdx = (startIdx + 1 until lines.size).firstOrNull { lines[it].startsWith(section.totalPrefix) }
            ?: throw GenericStatementParseException(
                "section '${section.name}' never closed with '${section.totalPrefix}' in $fileName",
                userMessage = "The \"${section.name}\" section on this statement never states its own " +
                    "total. Nothing was imported.",
            )
        val body = lines.subList(startIdx + 1, totalIdx)
        val totalToken = findMoneyTokens(lines[totalIdx]).lastOrNull()
            ?: throw GenericStatementParseException(
                "no amount found on '${lines[totalIdx]}' in $fileName",
                userMessage = "The \"${section.name}\" section's total line on this statement doesn't " +
                    "show an amount. Nothing was imported.",
            )
        return body to parseMoneyCents(totalToken)
    }

    /**
     * Every row on this layout is single-line (unlike [BofaStatementParser],
     * which needs continuation accumulation for wrapped wires) - confirmed
     * on Kevin's real statement, not assumed. But every NON-BLANK line
     * inside a section's own bounds MUST parse as a transaction row - full
     * form ([ROW_RE], tried first) or bare form ([BARE_ROW_RE]) - or this
     * throws. That is a deliberate reversal from this function's first cut,
     * which silently dropped any line matching neither: a $0.00 (or
     * otherwise coincidentally-zero) section total made that drop
     * reconcile VACUOUSLY - an empty row set against a stated total of
     * zero passes gate layer 1 with nothing to check against at all, which
     * is exactly the "gate can be satisfied by parsing nothing" failure
     * CLAUDE.md §4 exists to rule out. Real bug, real statement: the four
     * bare Interest Charged rows above vanished this way, undetected,
     * because that cycle's interest happened to total $0.00. A month that
     * actually carries a balance would have silently dropped real interest
     * charges with no error at all. Skipping is no longer a legal outcome
     * for a body line - it is either a transaction or a quarantine.
     */
    private fun parseSectionBody(
        body: List<String>, fileName: String, accountId: String, yearBounds: YearBounds,
    ): List<LedgerTransaction> {
        val transactions = mutableListOf<LedgerTransaction>()
        for (line in body) {
            if (line.isEmpty()) continue

            val fullMatch = ROW_RE.matchEntire(line)
            val bareMatch = if (fullMatch == null) BARE_ROW_RE.matchEntire(line) else null
            if (fullMatch == null && bareMatch == null) {
                throw GenericStatementParseException(
                    "line inside a recognized section matches no known transaction row form: '$line' in $fileName",
                    userMessage = "One of this statement's transaction lines doesn't look like a " +
                        "transaction LEGION knows how to read (\"$line\"). Nothing was imported.",
                )
            }

            val txnDateToken: String
            val description: String
            val amountToken: String
            if (fullMatch != null) {
                txnDateToken = fullMatch.groupValues[1]
                description = fullMatch.groupValues[3].trim()
                amountToken = fullMatch.groupValues[6]
            } else {
                txnDateToken = bareMatch!!.groupValues[1]
                description = bareMatch.groupValues[3].trim()
                amountToken = bareMatch.groupValues[4]
            }

            val amount = parseMoneyCents(amountToken)
            val txnDate = parseDate(txnDateToken, yearBounds, fileName)
            transactions.add(
                LedgerTransaction(
                    sourceFile = fileName,
                    accountId = accountId,
                    currency = LedgerCurrency.USD,
                    txnDate = txnDate,
                    description = description,
                    amountCents = amount,
                    balanceCents = null,
                    lineRef = "$fileName:'${line.take(60)}'",
                    ingestMethod = IngestMethod.DETERMINISTIC,
                )
            )
        }
        return transactions
    }

    /**
     * Reads one `<label> <amount>` summary line out of [summaryText].
     * Label and amount must be on the SAME textual line (only whitespace,
     * including a single newline, between them) - see this object's KDoc
     * for why that's what tells the true summary occurrence apart from a
     * section's bare transaction-table header further down the same
     * substring, which is never immediately followed by a money token.
     */
    private fun summaryAmount(summaryText: String, label: String, fileName: String): Long {
        val re = Regex(Regex.escape(label) + """\s+(-?\$?[\d,]+\.\d{2})""")
        val match = re.find(summaryText)
            ?: throw GenericStatementParseException(
                "no '$label' line found in the summary block of $fileName",
                userMessage = "This statement's summary is missing its \"$label\" line. Nothing was imported.",
            )
        return parseMoneyCents(match.groupValues[1])
    }

    private fun periodYearBounds(match: MatchResult, fileName: String): YearBounds {
        val startMonth = monthNumber(match.groupValues[1], fileName)
        val endMonth = monthNumber(match.groupValues[3], fileName)
        val printedYear = match.groupValues[5].toInt()
        val startYear = if (startMonth > endMonth) printedYear - 1 else printedYear
        return YearBounds(startMonth, startYear, printedYear)
    }

    private fun monthNumber(name: String, fileName: String): Int = try {
        Month.valueOf(name.uppercase(Locale.ROOT)).value
    } catch (e: IllegalArgumentException) {
        throw GenericStatementParseException(
            "unrecognized month name '$name' in $fileName",
            userMessage = "This statement's period line names a month LEGION doesn't recognize " +
                "(\"$name\"). Nothing was imported.",
        )
    }

    private fun yearForMonth(month: Int, bounds: YearBounds): Int =
        if (month >= bounds.startMonth) bounds.startYear else bounds.endYear

    private fun parseDate(monthDayToken: String, bounds: YearBounds, fileName: String): Long {
        val parts = monthDayToken.split("/")
        val month = parts.getOrNull(0)?.toIntOrNull()
        val day = parts.getOrNull(1)?.toIntOrNull()
        if (month == null || day == null) {
            throw GenericStatementParseException(
                "unrecognized date: '$monthDayToken' in $fileName",
                userMessage = "One of this statement's transaction dates (\"$monthDayToken\") doesn't " +
                    "look like a date LEGION knows how to read. Nothing was imported.",
            )
        }
        return try {
            LocalDate.of(yearForMonth(month, bounds), month, day).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        } catch (e: DateTimeException) {
            throw GenericStatementParseException(
                "unrecognized date: '$monthDayToken' in $fileName",
                userMessage = "One of this statement's transaction dates (\"$monthDayToken\") doesn't " +
                    "look like a real date. Nothing was imported.",
            )
        }
    }
}
