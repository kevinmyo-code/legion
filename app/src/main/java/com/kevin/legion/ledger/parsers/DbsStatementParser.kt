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
 * Port of Project Andromeda's `duo_ledger.bronze.parsers.dbs`
 * (`~/PycharmProjects/Andromeda`), same column-classification-by-x-coordinate
 * logic and the same [BalanceContinuityException] checks (brought-forward /
 * carried-forward / closing-total ties, per-row running-balance
 * verification). [com.kevin.legion.ledger.parsers.PdfWords] replaces
 * `pdfplumber`'s `.extract_words()` - see its doc for why `y` matches
 * Python's "top" semantics.
 */
object DbsStatementParser {
    private val DATE_RE = Regex("""^\d{2}/\d{2}/\d{4}$""")
    private val ACCOUNT_HEADER_RE = Regex("""Account No\.\s*(\d[\w-]*)""")
    private const val TOP_TOLERANCE = 2.0f
    private val DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    private data class Boundaries(val descAmt: Float, val wdDep: Float, val depBal: Float)

    fun parse(fileName: String, input: InputStream): ParsedStatement {
        val pageWords = PdfWords.extractWords(input)
        val boundaries = findBoundaries(pageWords, fileName)

        val transactions = mutableListOf<LedgerTransaction>()
        var sectionTransactions = mutableListOf<LedgerTransaction>()
        var accountId: String? = null
        var runningBalanceCents: Long? = null
        var currentTxnIndex = -1
        var sectionOpen = false

        // Anchor tracking (ticket 12): DBS prints a "Balance Brought Forward" line at the start
        // of every account section and a "Total Balance Carried Forward" line at its close -
        // both already read and reconciled below, just never returned before this ticket. A
        // consolidated statement CAN carry more than one account section (see the account-header
        // handling below), and a single opening/closing pair would misattribute one account's
        // balance to another's - so this is only kept when every section closed under the SAME
        // account id, same reasoning ReingestDryRun's own (now-retired) workaround used.
        val sectionAccountIds = mutableSetOf<String>()
        var firstOpeningBalanceCents: Long? = null
        var lastClosingBalanceCents: Long? = null

        for ((pageIdx, words) in pageWords.withIndex()) {
            for (rawLine in groupLines(words)) {
                val line = rawLine.sortedBy { it.x0 }
                val text = line.joinToString(" ") { it.text }
                val firstText = line[0].text

                val accountMatch = ACCOUNT_HEADER_RE.find(text)
                if (accountMatch != null) {
                    val newId = accountMatch.groupValues[1]
                    if (sectionOpen) {
                        if (newId != accountId) {
                            throw GenericStatementParseException(
                                "account changed from '$accountId' to '$newId' without closing the prior section"
                            )
                        }
                        continue
                    }
                    accountId = newId
                    sectionAccountIds.add(newId)
                    runningBalanceCents = null
                    sectionOpen = true
                    sectionTransactions = mutableListOf()
                    currentTxnIndex = -1
                    continue
                }

                if (!sectionOpen) continue

                if (firstText == "Date" && text.contains("Description")) continue

                if (text.contains("Total Balance Carried Forward")) {
                    val tokens = findMoneyTokens(text)
                    if (tokens.size != 3) {
                        throw GenericStatementParseException("expected 3 totals in closing line: '$text'")
                    }
                    val (totalWithdrawal, totalDeposit, finalBalance) = tokens.map { parseMoneyCents(it) }
                    val actualWithdrawal = -sectionTransactions.filter { it.amountCents < 0 }
                        .sumOf { it.amountCents }
                    val actualDeposit = sectionTransactions.filter { it.amountCents > 0 }
                        .sumOf { it.amountCents }
                    if (actualWithdrawal != totalWithdrawal || actualDeposit != totalDeposit) {
                        throw BalanceContinuityException(
                            "account $accountId: statement totals withdrawal=$totalWithdrawal " +
                                "deposit=$totalDeposit do not match transactions " +
                                "withdrawal=$actualWithdrawal deposit=$actualDeposit",
                            // Reports EVERY side that mismatched, not the first
                            // one - both can be wrong at once, and naming only
                            // half of it understates the problem.
                            userMessage = buildString {
                                append("This statement's own totals don't match its transactions. ")
                                if (actualWithdrawal != totalWithdrawal) {
                                    append("It states ${formatCents(totalWithdrawal)} withdrawn, ")
                                    append("but the lines add up to ${formatCents(actualWithdrawal)}. ")
                                }
                                if (actualDeposit != totalDeposit) {
                                    append("It states ${formatCents(totalDeposit)} deposited, ")
                                    append("but the lines add up to ${formatCents(actualDeposit)}. ")
                                }
                                append("Nothing was imported.")
                            },
                        )
                    }
                    if (runningBalanceCents != finalBalance) {
                        throw BalanceContinuityException(
                            "account $accountId: closing balance $finalBalance does not match " +
                                "running balance $runningBalanceCents",
                            // NOT `runningBalanceCents ?: 0L` - null here means
                            // no running balance was ever recorded for this
                            // section, and printing 0.00 would state a figure
                            // the statement never gives. CLAUDE.md §4 rule 5.
                            userMessage = "This statement's closing balance doesn't match its own " +
                                "transactions. It ends at ${formatCents(finalBalance)}, but " +
                                (runningBalanceCents?.let { "the lines run to ${formatCents(it)}. " }
                                    ?: "no running balance was ever recorded for this section. ") +
                                "Nothing was imported.",
                        )
                    }
                    sectionOpen = false
                    currentTxnIndex = -1
                    // Anchor tracking: this section's own printed closing figure, read straight
                    // off the "Total Balance Carried Forward" line - always the LAST one seen
                    // wins, so a multi-section file's tracker ends at the final section's close.
                    lastClosingBalanceCents = finalBalance
                    continue
                }

                if (text.contains("Balance Brought Forward")) {
                    val stated = singleMoney(text, context = "Balance Brought Forward line")
                    if (runningBalanceCents != null && stated != runningBalanceCents) {
                        throw BalanceContinuityException(
                            "account $accountId: brought-forward balance $stated does not " +
                                "match the prior carried-forward balance $runningBalanceCents",
                            // The guard above already established this branch
                            // is only reached when runningBalanceCents != null.
                            userMessage = "This statement's sections don't join up. One opens at " +
                                "${formatCents(stated)}, but the previous one closed at " +
                                "${formatCents(runningBalanceCents!!)}. Nothing was imported.",
                        )
                    }
                    // Anchor tracking: this is the true section-opening figure, printed on the
                    // document, only the first time it is observed per file - a later section's
                    // own "Balance Brought Forward" is that section's own opening, not the
                    // whole file's, and (per this function's own KDoc on multi-section files)
                    // gets dropped below anyway unless every section shares one account.
                    if (runningBalanceCents == null && firstOpeningBalanceCents == null) {
                        firstOpeningBalanceCents = stated
                    }
                    runningBalanceCents = stated
                    currentTxnIndex = -1
                    continue
                }

                if (text.contains("Balance Carried Forward")) {
                    val stated = singleMoney(text, context = "Balance Carried Forward line")
                    if (runningBalanceCents == null || stated != runningBalanceCents) {
                        throw BalanceContinuityException(
                            "account $accountId: page-break balance $stated does not match " +
                                "running balance $runningBalanceCents",
                            // Same null rule as the closing-balance site above.
                            userMessage = "This statement's balance doesn't carry over correctly " +
                                "across a page break. A page opens at ${formatCents(stated)}, but " +
                                (runningBalanceCents?.let { "the previous page ran to ${formatCents(it)}. " }
                                    ?: "no running balance was ever recorded for the previous page. ") +
                                "Nothing was imported.",
                        )
                    }
                    currentTxnIndex = -1
                    continue
                }

                if (DATE_RE.matches(firstText)) {
                    if (runningBalanceCents == null || accountId == null) {
                        throw GenericStatementParseException("transaction before an opening balance: '$text'")
                    }
                    val txnDate = parseDate(firstText)
                    val descWords = line.drop(1).filter { it.x0 < boundaries.descAmt }
                    val amtWords = line.filter { it.x0 >= boundaries.descAmt }
                    val (withdrawal, deposit, balance) = classifyAmounts(amtWords, boundaries)
                    if (balance == null) {
                        throw GenericStatementParseException("transaction row missing a balance: '$text'")
                    }
                    if ((withdrawal == null) == (deposit == null)) {
                        throw GenericStatementParseException(
                            "transaction row must have exactly one of withdrawal/deposit: '$text'"
                        )
                    }
                    val amount = withdrawal?.let { -it } ?: deposit!!
                    val expectedBalance = runningBalanceCents + amount
                    if (expectedBalance != balance) {
                        throw BalanceContinuityException(
                            "$txnDate: expected $expectedBalance, statement shows $balance",
                            // The highest-traffic gate site: one bad line item
                            // is a likelier real failure than a whole section
                            // mistotalling, so this is the message a user is
                            // most likely to actually see.
                            userMessage = "A line on $txnDate doesn't add up. After it, the " +
                                "balance should be ${formatCents(expectedBalance)}, but the " +
                                "statement shows ${formatCents(balance)}. Nothing was imported.",
                        )
                    }
                    val description = descWords.joinToString(" ") { it.text }
                    if (description.isBlank()) {
                        throw GenericStatementParseException("transaction row missing a description: '$text'")
                    }
                    runningBalanceCents = balance
                    val txn = LedgerTransaction(
                        sourceFile = fileName,
                        accountId = accountId,
                        currency = LedgerCurrency.SGD,
                        txnDate = txnDate,
                        description = description,
                        amountCents = amount,
                        balanceCents = balance,
                        lineRef = "$fileName:p$pageIdx:${"%.1f".format(line[0].y)}",
                        ingestMethod = IngestMethod.DETERMINISTIC,
                    )
                    transactions.add(txn)
                    sectionTransactions.add(txn)
                    currentTxnIndex = transactions.size - 1
                    continue
                }

                // The terminator checks above (`Total Balance Carried
                // Forward:`, `Balance Carried Forward`, `Balance Brought
                // Forward`) all `continue` BEFORE this point and reset
                // `currentTxnIndex = -1` - that is the structural guard: a
                // real section/page boundary can never fall through to the
                // continuation accumulator below it. What it does NOT catch
                // is a stray line that sits INSIDE a transaction's
                // continuation range without matching any of those markers -
                // see [isArtifactLine]'s doc for the real case that slipped
                // through.
                if (currentTxnIndex >= 0) {
                    if (isArtifactLine(text)) continue
                    val current = transactions[currentTxnIndex]
                    val updated = current.copy(description = "${current.description} $text".trim())
                    transactions[currentTxnIndex] = updated
                    val sectionIdx = sectionTransactions.indexOfFirst { it === current }
                    if (sectionIdx >= 0) sectionTransactions[sectionIdx] = updated
                }
            }
        }

        if (sectionOpen) {
            throw GenericStatementParseException("account $accountId section never closed with a totals line")
        }
        if (accountId == null) {
            throw UnrecognizedLayoutException("no DBS account section found in $fileName")
        }

        // Single-account files (the overwhelming common case, and every fixture this ticket
        // covers) get their real printed opening/closing balance. A consolidated statement
        // spanning more than one account has no single opening/closing to name - see the field
        // declarations' own doc for why reporting one account's figures under a mixed file would
        // misattribute them, the same reasoning ReingestDryRun's retired workaround used.
        //
        // statedTotalCents is always null: DBS prints separate withdrawal and deposit totals on
        // its closing line, never one combined "total" or "net movement" figure, so there is
        // nothing here that qualifies as a stated total per this ticket's rule.
        val singleAccount = sectionAccountIds.size == 1
        val anchors = StatementAnchors(
            statedTotalCents = null,
            openingBalanceCents = if (singleAccount) firstOpeningBalanceCents else null,
            closingBalanceCents = if (singleAccount) lastClosingBalanceCents else null,
        )
        return ParsedStatement(transactions, anchors)
    }

    /**
     * True for a PDF-artifact line: every whitespace-separated token on it is
     * exactly one character (`"4 4 4 4 4"`, `"S"`, `"1 4 8 A"`). Found on
     * Kevin's real consolidated DBS/POSB statement - PdfBox-Android emits
     * rotated/sidebar watermark text as its own line, and when one of those
     * lines happens to land between a transaction row and the next section
     * marker, the description-continuation accumulator has no other way to
     * tell it apart from a real multi-line description. A legitimate DBS
     * description continuation (reference numbers, approval codes, payee
     * names) is never all one-character tokens, so this is a safe
     * discriminator without touching the reconciliation arithmetic at all.
     *
     * Deliberately narrow: it skips ONLY the offending line, not the rest of
     * the accumulation range, so a genuine continuation line sandwiched on
     * the far side of an artifact line is still captured (the real
     * statement's watermark could in principle land mid-description on a
     * future page, not just right before the totals line where this one
     * happened to fall - "luck, not safety" per the bug report).
     */
    private fun isArtifactLine(text: String): Boolean {
        val tokens = text.trim().split(Regex("\\s+"))
        return tokens.isNotEmpty() && tokens.all { it.length == 1 }
    }

    /** Groups words into lines by near-equal `top` (y), sorted top-to-bottom then left-to-right. */
    private fun groupLines(words: List<PdfWord>): List<List<PdfWord>> {
        val lines = mutableListOf<MutableList<PdfWord>>()
        var current: MutableList<PdfWord>? = null
        var currentTop: Float? = null
        for (word in words.sortedWith(compareBy({ it.y }, { it.x0 }))) {
            if (currentTop == null || kotlin.math.abs(word.y - currentTop) > TOP_TOLERANCE) {
                current?.let { lines.add(it) }
                current = mutableListOf(word)
                currentTop = word.y
            } else {
                current!!.add(word)
            }
        }
        current?.let { lines.add(it) }
        return lines
    }

    private fun findBoundaries(pageWords: List<List<PdfWord>>, fileName: String): Boundaries {
        for (words in pageWords) {
            val withdrawal = words.firstOrNull { it.text == "Withdrawal" } ?: continue
            val headerTop = withdrawal.y
            fun sameRow(w: PdfWord) = kotlin.math.abs(w.y - headerTop) < TOP_TOLERANCE
            val deposit = words.firstOrNull { it.text == "Deposit" && sameRow(it) } ?: continue
            val balance = words.firstOrNull { it.text == "Balance" && sameRow(it) } ?: continue
            return Boundaries(
                descAmt = withdrawal.x0 - 5,
                wdDep = (withdrawal.x1 + deposit.x0) / 2,
                depBal = (deposit.x1 + balance.x0) / 2,
            )
        }
        throw UnrecognizedLayoutException("no Withdrawal/Deposit/Balance headers found in $fileName")
    }

    private fun singleMoney(text: String, context: String): Long {
        val tokens = findMoneyTokens(text)
        if (tokens.size != 1) {
            throw GenericStatementParseException("expected exactly one amount in $context: '$text'")
        }
        return parseMoneyCents(tokens[0])
    }

    /** Returns (withdrawal, deposit, balance), each null if not present on this row. */
    private fun classifyAmounts(words: List<PdfWord>, boundaries: Boundaries): Triple<Long?, Long?, Long?> {
        var withdrawal: Long? = null
        var deposit: Long? = null
        var balance: Long? = null
        for (word in words) {
            val value = parseMoneyCents(word.text)
            when {
                word.x0 < boundaries.wdDep -> {
                    if (withdrawal != null) throw GenericStatementParseException("multiple withdrawal amounts on one row: '${word.text}'")
                    withdrawal = value
                }
                word.x0 < boundaries.depBal -> {
                    if (deposit != null) throw GenericStatementParseException("multiple deposit amounts on one row: '${word.text}'")
                    deposit = value
                }
                else -> {
                    if (balance != null) throw GenericStatementParseException("multiple balance amounts on one row: '${word.text}'")
                    balance = value
                }
            }
        }
        return Triple(withdrawal, deposit, balance)
    }

    private fun parseDate(token: String): Long {
        return try {
            LocalDate.parse(token, DATE_FORMAT).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            throw GenericStatementParseException("unrecognized date: '$token'")
        }
    }
}
