package com.kevin.legion.ledger.parsers

import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.LedgerTransaction
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
                    "${section.totalMarker}: statement says $statedTotal, transactions sum to $actualTotal"
                )
            }
            transactions.addAll(sectionTxns)
            netTotal += actualTotal
        }

        if (beginningBalance + netTotal != endingBalance) {
            throw BalanceContinuityException(
                "beginning balance $beginningBalance + net movement $netTotal != ending balance $endingBalance"
            )
        }

        return transactions
    }

    private fun extractSection(
        lines: List<String>, startMarker: String, totalMarker: String, fileName: String,
    ): Pair<List<String>, String> {
        val startIdx = lines.indexOfFirst { it.startsWith(startMarker) }
        if (startIdx < 0) throw UnrecognizedLayoutException("section '$startMarker' not found in $fileName")
        val endIdx = (startIdx + 1 until lines.size).firstOrNull { lines[it].startsWith(totalMarker) }
            ?: throw GenericStatementParseException("section '$startMarker' never closed with '$totalMarker' in $fileName")
        val body = lines.subList(startIdx + 1, endIdx).filter { it.isNotEmpty() && !it.startsWith("Date") }
        return body to lines[endIdx]
    }

    private fun parseSectionBody(
        body: List<String>, fileName: String, accountId: String, positive: Boolean, sectionName: String,
    ): List<LedgerTransaction> {
        val transactions = mutableListOf<LedgerTransaction>()
        var currentIndex = -1
        for (line in body) {
            if (line in IGNORED_LINES) continue
            val firstToken = line.split(" ", limit = 2)[0]
            if (DATE_RE.matches(firstToken)) {
                val moneyTokens = findMoneyTokens(line)
                if (moneyTokens.isEmpty()) {
                    throw GenericStatementParseException("transaction row missing an amount: '$line'")
                }
                val amountToken = moneyTokens.last()
                val amount = parseMoneyCents(amountToken)
                if (positive && amount < 0) {
                    throw GenericStatementParseException("expected a positive amount in '$sectionName': '$line'")
                }
                if (!positive && amount > 0) {
                    throw GenericStatementParseException("expected a negative amount in '$sectionName': '$line'")
                }
                val txnDate = parseDate(firstToken)
                val afterDate = line.substring(firstToken.length)
                val lastAmountIdx = afterDate.lastIndexOf(amountToken)
                val description = afterDate.substring(0, lastAmountIdx).trim()
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
                currentIndex = transactions.size - 1
            } else {
                if (currentIndex < 0) continue
                val current = transactions[currentIndex]
                transactions[currentIndex] = current.copy(description = "${current.description} $line".trim())
            }
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
