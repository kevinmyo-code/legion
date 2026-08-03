package com.kevin.legion.ledger.parsers

import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.LedgerTransaction
import com.kevin.legion.ledger.formatCents
import java.io.InputStream
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Port of Project Andromeda's `duo_ledger.bronze.parsers.bofa`
 * (`~/PycharmProjects/Andromeda`), same section-based parsing (Deposits/
 * ATM-debit/Other subtractions/Service fees) and the same section-total +
 * beginning-plus-net-equals-ending [BalanceContinuityException] checks.
 */
object BofaStatementParser {
    private val DATE_RE = Regex("""^\d{2}/\d{2}/\d{2}$""")
    private val ACCOUNT_RE = Regex("""Account (?:number|#)\s*:?\s*([\d ]{4,})""")
    private val BEGIN_BAL_RE = Regex("""Beginning balance on [^$]*\$([\d,]+\.\d{2})""")
    private val END_BAL_RE = Regex("""Ending balance on [^$]*\$([\d,]+\.\d{2})""")
    private val DATE_FORMAT = DateTimeFormatter.ofPattern("MM/dd/yy")

    private data class Section(val startMarker: String, val totalMarker: String, val positive: Boolean)

    private val SECTIONS = listOf(
        Section("Deposits and other additions", "Total deposits and other additions", true),
        Section("ATM and debit card subtractions", "Total ATM and debit card subtractions", false),
        Section("Other subtractions", "Total other subtractions", false),
        Section("Service fees", "Total service fees", false),
    )

    private val IGNORED_LINES = setOf("continued on the next page")

    /**
     * Per-page furniture that can turn up INSIDE a section's body when a
     * section spans a page break - not a transaction row and not part of
     * one, so it must be dropped rather than folded into whichever
     * transaction happens to be mid-accumulation when it appears. Found on
     * Kevin's real BofA statement (2026-08-02): a multi-line wire's
     * description lines are indistinguishable from ordinary prose, so
     * without this filter a "Page 2 of 6" footer or a reprinted account
     * header landing between them would silently become part of the
     * description text.
     *
     * The account line is matched as a SUBSTRING, not a whole-line anchor.
     * The first cut anchored `^Account ...$` end to end, which matched the
     * account line at the top of the statement but missed the reprinted one
     * that shows up after a page break, which interleaves a name before and
     * a statement date range after it on the same physical line - e.g.
     * "KEVIN MYO NAING WIN ! Account # 4881 3004 3119 ! June 5, 2026 to July
     * 8, 2026" (measured on Kevin's real statement, 2026-08-02). "Page N of
     * M" stays a whole-line match, since on that same statement it really is
     * the entire line with nothing else on it - loosening it too would risk
     * eating a real description line that merely mentions a page number.
     */
    private val PAGE_FOOTER_RE = Regex("""^Page \d+ of \d+$""")
    private val ACCOUNT_LINE_RE = Regex("""Account (?:number|#)\s*:?\s*[\d ]{4,}""")

    private fun isPageFurniture(line: String): Boolean =
        line in IGNORED_LINES || PAGE_FOOTER_RE.matches(line) || ACCOUNT_LINE_RE.containsMatchIn(line)

    /**
     * Real BofA wires wrap over at most 3 description lines plus one
     * amount-alone line (confirmed against Kevin's real statement,
     * 2026-08-02 - not a guess). This caps continuation accumulation at
     * roughly double that, so a genuinely malformed row (no amount ever
     * printed) fails fast with the original "missing an amount" error
     * instead of a runaway loop swallowing the rest of the section looking
     * for a decimal that will never appear.
     */
    private const val MAX_CONTINUATION_LINES = 6

    fun parse(fileName: String, input: InputStream): List<LedgerTransaction> {
        val text = PdfText.extractText(input)
        val lines = text.lines().map { it.trim() }

        val accountMatch = ACCOUNT_RE.find(text)
            ?: throw UnrecognizedLayoutException("no Bank of America account number found in $fileName")
        val accountId = accountMatch.groupValues[1].replace(Regex("""\s+"""), "")

        val beginMatch = BEGIN_BAL_RE.find(text)
        val endMatch = END_BAL_RE.find(text)
        if (beginMatch == null || endMatch == null) {
            throw UnrecognizedLayoutException("no beginning/ending balance summary found in $fileName")
        }
        val beginningBalance = parseMoneyCents(beginMatch.groupValues[1])
        val endingBalance = parseMoneyCents(endMatch.groupValues[1])

        val transactions = mutableListOf<LedgerTransaction>()
        var netTotal = 0L

        for (section in SECTIONS) {
            val (body, totalLine) = extractSection(lines, section.startMarker, section.totalMarker, fileName)
            val sectionTxns = parseSectionBody(
                body, fileName = fileName, accountId = accountId,
                positive = section.positive, sectionName = section.startMarker,
            )
            val statedTotal = parseMoneyCents(singleMoneyToken(totalLine, context = section.totalMarker))
            val actualTotal = sectionTxns.sumOf { it.amountCents }
            if (actualTotal != statedTotal) {
                throw BalanceContinuityException(
                    "${section.totalMarker}: statement says $statedTotal, transactions sum to $actualTotal",
                    userMessage = "The \"${section.totalMarker}\" section doesn't add up. The " +
                        "statement says ${formatCents(statedTotal)}, but its own lines sum to " +
                        "${formatCents(actualTotal)}. Nothing was imported.",
                )
            }
            transactions.addAll(sectionTxns)
            netTotal += actualTotal
        }

        if (beginningBalance + netTotal != endingBalance) {
            throw BalanceContinuityException(
                "beginning balance $beginningBalance + net movement $netTotal != ending balance $endingBalance",
                userMessage = "This statement's balances don't tie out. It opens at " +
                    "${formatCents(beginningBalance)} and moves ${formatCents(netTotal)}, which " +
                    "lands at ${formatCents(beginningBalance + netTotal)}, not the " +
                    "${formatCents(endingBalance)} it states. Nothing was imported.",
            )
        }

        return transactions
    }

    /**
     * Every section name on a real BofA statement appears TWICE: once in the
     * front-page SUMMARY block, immediately followed by that same section's
     * own total (e.g. "ATM and debit card subtractions" then, on the next
     * line, "-17.98"), and once as the real transaction-table header,
     * immediately followed by a "Date ..." column header line. The old
     * `indexOfFirst` always locked onto the summary occurrence, since it
     * comes first, which made the body span from the summary block straight
     * through to the wrong section's real header - measured on Kevin's real
     * statement, 2026-08-02, where it made the "ATM and debit card
     * subtractions" body swallow every row of "Deposits and other
     * additions". The summary line is never followed by a "Date" line; the
     * real header always is. That is the only reliable signal, so this locks
     * onto the FIRST occurrence of the marker that satisfies it, not the
     * first occurrence full stop.
     *
     * A section's body can also arrive in multiple physical pieces when a
     * page break falls inside it: BofA reprints both the section name and
     * the "Date ..." header right before the second piece starts (seen on
     * "Other subtractions" on Kevin's statement, which is followed by a
     * "Date" header twice - once before each half of its rows). Rather than
     * detect and stitch pieces explicitly, this takes the body as everything
     * between the FIRST real header and the section's "Total ..." line, and
     * filters out every "Date ..." line and every reprinted header-plus-Date
     * pair found in between - which naturally concatenates all the pieces in
     * between into one body, in document order, before the total check ever
     * runs.
     */
    private fun extractSection(
        lines: List<String>, startMarker: String, totalMarker: String, fileName: String,
    ): Pair<List<String>, String> {
        val startIdx = lines.indices.firstOrNull { idx ->
            lines[idx].startsWith(startMarker) && idx + 1 < lines.size && lines[idx + 1].startsWith("Date")
        } ?: throw UnrecognizedLayoutException("section '$startMarker' not found in $fileName")

        val endIdx = (startIdx + 1 until lines.size).firstOrNull { lines[it].startsWith(totalMarker) }
            ?: throw GenericStatementParseException("section '$startMarker' never closed with '$totalMarker' in $fileName")

        val body = (startIdx + 1 until endIdx).mapNotNull { idx ->
            val line = lines[idx]
            when {
                line.isEmpty() -> null
                line.startsWith("Date") -> null
                // A reprinted section header (page break mid-section): drop
                // the title line itself. Its trailing "Date" line is already
                // dropped by the branch above on its own turn through this
                // loop.
                line.startsWith(startMarker) && idx + 1 < endIdx && lines[idx + 1].startsWith("Date") -> null
                else -> line
            }
        }
        return body to lines[endIdx]
    }

    /**
     * A transaction row starts at a date-led line, but the amount is not
     * always on it - real BofA wires wrap their description over several
     * lines and print the amount alone on a trailing line (see this file's
     * KDoc pointer, and the class comment on [MAX_CONTINUATION_LINES]).
     * This accumulates lines belonging to one row - the date line first,
     * then any following non-date, non-furniture lines - until a money
     * token turns up somewhere in them, then joins the non-amount text into
     * one description. A date-led line with no amount before the next
     * date-led line (or the section end) still throws exactly as before -
     * this widens WHERE the amount can be found, not whether one is
     * required.
     */
    private fun parseSectionBody(
        body: List<String>, fileName: String, accountId: String, positive: Boolean, sectionName: String,
    ): List<LedgerTransaction> {
        val transactions = mutableListOf<LedgerTransaction>()
        var i = 0
        while (i < body.size) {
            val line = body[i]
            if (isPageFurniture(line)) {
                i++
                continue
            }
            val firstToken = line.split(" ", limit = 2)[0]
            if (!DATE_RE.matches(firstToken)) {
                // Stray non-date line with no open transaction to attach to
                // (e.g. trailing boilerplate after a section's last row).
                // Silently dropping it is a deliberate change from the old
                // behaviour of gluing it onto whichever transaction was
                // built last - that could stretch a prior row's description
                // across unrelated text with no bound at all.
                i++
                continue
            }

            // rawLines[0] is the date row with its date token stripped;
            // further entries are wrapped continuation lines, added below
            // until one of them carries the amount.
            val rawLines = mutableListOf(line.substring(firstToken.length).trim())
            var amountLineIdx = if (findMoneyTokens(rawLines[0]).isNotEmpty()) 0 else -1

            var j = i + 1
            while (amountLineIdx < 0 && j < body.size && (j - i) <= MAX_CONTINUATION_LINES) {
                val next = body[j]
                val nextFirstToken = next.split(" ", limit = 2)[0]
                if (DATE_RE.matches(nextFirstToken)) break // next transaction started; this one never got an amount
                if (isPageFurniture(next)) {
                    j++
                    continue
                }
                rawLines.add(next)
                if (findMoneyTokens(next).isNotEmpty()) amountLineIdx = rawLines.size - 1
                j++
            }

            if (amountLineIdx < 0) {
                throw GenericStatementParseException(
                    "transaction row missing an amount: '$line'",
                    // Real bug (Kevin's BofA statement, 2026-08-02): before
                    // continuation accumulation existed, EVERY wire row hit
                    // this because its amount sits on a later line, and this
                    // site's raw diagnostic was the only string a device ever
                    // rendered for it. Now it only fires when a row truly
                    // never states an amount within MAX_CONTINUATION_LINES,
                    // but it can still reach a user, so it needs plain
                    // language same as every other quarantine site.
                    userMessage = "One of this statement's transaction lines doesn't show an " +
                        "amount. Nothing was imported.",
                )
            }

            val amountLine = rawLines[amountLineIdx]
            val amountToken = findMoneyTokens(amountLine).last()
            val amount = parseMoneyCents(amountToken)
            if (positive && amount < 0) {
                throw GenericStatementParseException("expected a positive amount in '$sectionName': '$line'")
            }
            if (!positive && amount > 0) {
                throw GenericStatementParseException("expected a negative amount in '$sectionName': '$line'")
            }
            val txnDate = parseDate(firstToken)

            // Strip the amount out of whichever line it lived on, then join
            // every remaining line with a single space - preserves reading
            // order for both the single-line case (amount trimmed off the
            // tail of rawLines[0]) and the multi-line case (amount was
            // alone on its own line, which then contributes nothing).
            val lastAmountIdx = amountLine.lastIndexOf(amountToken)
            rawLines[amountLineIdx] = (amountLine.substring(0, lastAmountIdx) +
                amountLine.substring(lastAmountIdx + amountToken.length)).trim()
            val description = rawLines.filter { it.isNotBlank() }.joinToString(" ")
            if (description.isBlank()) {
                throw GenericStatementParseException("transaction row missing a description: '$line'")
            }

            val txn = LedgerTransaction(
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
            transactions.add(txn)
            i = j
        }
        return transactions
    }

    private fun singleMoneyToken(text: String, context: String): String {
        val tokens = findMoneyTokens(text)
        if (tokens.size != 1) {
            throw GenericStatementParseException("expected exactly one amount in $context: '$text'")
        }
        return tokens[0]
    }

    private fun parseDate(token: String): Long = try {
        LocalDate.parse(token, DATE_FORMAT).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    } catch (e: DateTimeParseException) {
        throw GenericStatementParseException("unrecognized date: '$token'")
    }
}
