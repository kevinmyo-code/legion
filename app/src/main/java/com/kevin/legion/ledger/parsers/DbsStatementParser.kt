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

    fun parse(fileName: String, input: InputStream): List<LedgerTransaction> {
        val pageWords = PdfWords.extractWords(input)
        val boundaries = findBoundaries(pageWords, fileName)

        val transactions = mutableListOf<LedgerTransaction>()
        var sectionTransactions = mutableListOf<LedgerTransaction>()
        var accountId: String? = null
        var runningBalanceCents: Long? = null
        var currentTxnIndex = -1
        var sectionOpen = false

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

                if (currentTxnIndex >= 0) {
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

        return transactions
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
