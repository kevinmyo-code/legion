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
 * Bank of America's mid-cycle CSV export ("Download activity" from the
 * account page, any time, not just at statement close) - born-digital and
 * structured, unlike [BofaStatementParser]'s end-of-cycle PDF, so this is a
 * genuine second DETERMINISTIC path for the same bank rather than an LLM
 * fallback (CLAUDE.md §4 rule 1). Verified against Kevin's real export
 * 2026-08-02/03 (facts in the doc comments below are `tested`, not assumed).
 *
 * Layout, exactly as BofA prints it:
 * ```
 * Description,,Summary Amt.
 * Beginning balance as of 07/01/2026,,"-6.31"
 * Total credits,,"6,460.05"
 * Total debits,,"-107.14"
 * Ending balance as of 08/03/2026,,"6,346.60"
 * <blank line>
 * Date,Description,Amount,Running Bal.
 * 07/01/2026,Beginning balance as of 07/01/2026,,"-6.31"
 * 07/01/2026,"Online Banking transfer from SAV 8267 Confirmation# 2245981037","30.00","23.69"
 * ...
 * ```
 * The first transaction-table row repeats the beginning balance with an
 * EMPTY Amount field - it is not a transaction, it is the anchor the
 * per-row running-balance check starts from. Skipped, never parsed as a
 * zero-amount row.
 *
 * **`accountId` is never derived from the file - unlike the PDF statement,
 * this CSV export prints no account number anywhere, verified on the real
 * file, not assumed.** [accountHint] is the resolved answer from the
 * per-account Drive subfolder this file was found in
 * ([com.kevin.legion.ledger.LedgerAccountMappingPreferences], threaded down
 * from [StatementDispatcher.dispatchDeterministic]). **No guessing, no
 * silent placeholder**: if [accountHint] is null once every reconciliation
 * anchor below has already passed, this throws [UnmappedAccountException]
 * rather than writing a row under a fabricated account id - CLAUDE.md §4's
 * "never silently accept" applied to account identity, not just money. See
 * that exception's doc comment for why the numeric checks run first.
 */
object BofaCsvStatementParser {
    /**
     * Internal-only placeholder used while building rows below, before
     * [accountHint] is known to be non-null. Never returned to a caller -
     * either every row is remapped to [accountHint] at the very end, or
     * [UnmappedAccountException] is thrown first and nothing is returned at
     * all.
     */
    private const val PENDING_ACCOUNT = "PENDING-ACCOUNT-RESOLUTION"

    private const val SUMMARY_HEADER = "Description,,Summary Amt."
    private const val TXN_HEADER = "Date,Description,Amount,Running Bal."
    private val DATE_FORMAT = DateTimeFormatter.ofPattern("MM/dd/yyyy")

    fun parse(fileName: String, input: InputStream, accountHint: String? = null): List<LedgerTransaction> {
        // String(bytes, Charsets.UTF_8) never throws on malformed input (it
        // substitutes the replacement character), which matters here: this is
        // tried against every file [StatementDispatcher] sees, including PDF
        // binaries, and recognition must fail cleanly rather than crash on
        // bytes that are simply the wrong format.
        val text = input.readBytes().toString(Charsets.UTF_8)
        val lines = text.split("\r\n", "\n", "\r").map { it.trimEnd() }

        if (lines.isEmpty() || lines[0].trim() != SUMMARY_HEADER) {
            throw UnrecognizedLayoutException("first line is not '$SUMMARY_HEADER' in $fileName")
        }

        // Summary block: exactly rows 1-4 (beginning balance, total credits,
        // total debits, ending balance), each a 3-field CSV row.
        if (lines.size < 5) {
            throw GenericStatementParseException("summary block is incomplete in $fileName")
        }
        val beginningBalance = summaryAmount(lines[1], "Beginning balance", fileName)
        val totalCredits = summaryAmount(lines[2], "Total credits", fileName)
        val totalDebits = summaryAmount(lines[3], "Total debits", fileName)
        val endingBalance = summaryAmount(lines[4], "Ending balance", fileName)

        val txnHeaderIdx = (5 until lines.size).firstOrNull { lines[it].trim() == TXN_HEADER }
            ?: throw GenericStatementParseException(
                "recognized the '$SUMMARY_HEADER' summary block but never found the " +
                    "'$TXN_HEADER' transaction table in $fileName"
            )

        val rows = (txnHeaderIdx + 1 until lines.size)
            .map { lines[it] }
            .takeWhile { it.isNotBlank() }

        if (rows.isEmpty()) {
            throw GenericStatementParseException("transaction table has no rows in $fileName")
        }

        // Row 1 repeats the beginning balance with an empty Amount - it is
        // the running-balance anchor, not a transaction. See this object's
        // doc comment.
        val firstFields = parseCsvLine(rows[0])
        if (firstFields.size != 4) {
            throw GenericStatementParseException("expected 4 fields in transaction row: '${rows[0]}'")
        }
        if (firstFields[2].isNotBlank()) {
            throw GenericStatementParseException(
                "expected the first transaction-table row to repeat the beginning balance with an " +
                    "empty amount, got '${rows[0]}'"
            )
        }
        var runningBalanceCents = parseMoneyCents(firstFields[3])

        val transactions = mutableListOf<LedgerTransaction>()
        var sumCredits = 0L
        var sumDebits = 0L
        var netMovement = 0L

        for (row in rows.drop(1)) {
            val fields = parseCsvLine(row)
            if (fields.size != 4) {
                throw GenericStatementParseException("expected 4 fields in transaction row: '$row'")
            }
            val (dateToken, descriptionRaw, amountToken, balanceToken) = fields
            val description = descriptionRaw.trim()
            if (description.isBlank()) {
                throw GenericStatementParseException("transaction row missing a description: '$row'")
            }
            if (amountToken.isBlank()) {
                throw GenericStatementParseException("transaction row missing an amount: '$row'")
            }
            val amount = parseMoneyCents(amountToken)
            val statedBalance = parseMoneyCents(balanceToken)
            val txnDate = parseDate(dateToken)

            // Anchor 3: per-row running-balance continuity.
            val expectedBalance = runningBalanceCents + amount
            if (expectedBalance != statedBalance) {
                throw BalanceContinuityException(
                    "$dateToken: expected running balance $expectedBalance, statement shows $statedBalance",
                    userMessage = "A line on $dateToken doesn't add up. After it, the running balance " +
                        "should be ${formatCents(expectedBalance)}, but the statement shows " +
                        "${formatCents(statedBalance)}. Nothing was imported.",
                )
            }

            transactions.add(
                LedgerTransaction(
                    sourceFile = fileName,
                    accountId = PENDING_ACCOUNT,
                    currency = LedgerCurrency.USD,
                    txnDate = txnDate,
                    description = description,
                    amountCents = amount,
                    balanceCents = statedBalance,
                    lineRef = "$fileName:'${row.take(60)}'",
                    ingestMethod = IngestMethod.DETERMINISTIC,
                )
            )

            if (amount > 0) sumCredits += amount else sumDebits += amount
            netMovement += amount
            runningBalanceCents = statedBalance
        }

        // Anchor 2: the summary block's own credit/debit totals. Reports
        // every side that mismatched, not just the first, same shape as
        // DbsStatementParser's closing-totals check - both sides can be
        // wrong at once and naming only half of it understates the problem.
        if (sumCredits != totalCredits || sumDebits != totalDebits) {
            throw BalanceContinuityException(
                "credits: statement says $totalCredits, transactions sum to $sumCredits; " +
                    "debits: statement says $totalDebits, transactions sum to $sumDebits",
                userMessage = buildString {
                    append("This statement's own totals don't match its transactions. ")
                    if (sumCredits != totalCredits) {
                        append("It states ${formatCents(totalCredits)} in credits, ")
                        append("but the lines add up to ${formatCents(sumCredits)}. ")
                    }
                    if (sumDebits != totalDebits) {
                        append("It states ${formatCents(totalDebits)} in debits, ")
                        append("but the lines add up to ${formatCents(sumDebits)}. ")
                    }
                    append("Nothing was imported.")
                },
            )
        }

        // Anchor 1: beginning balance + net movement == ending balance.
        val computedEnding = beginningBalance + netMovement
        if (computedEnding != endingBalance) {
            throw BalanceContinuityException(
                "beginning balance $beginningBalance + net movement $netMovement " +
                    "($computedEnding) != stated ending balance $endingBalance",
                userMessage = "This statement's balances don't tie out. It opens at " +
                    "${formatCents(beginningBalance)} and moves ${formatCents(netMovement)}, which " +
                    "lands at ${formatCents(computedEnding)}, not the ${formatCents(endingBalance)} " +
                    "it states. Nothing was imported.",
            )
        }

        // Account resolution is LAST, after every numeric anchor above has
        // already passed - see UnmappedAccountException's doc comment for
        // why that ordering matters (a numbers problem is reported before a
        // mapping problem, since it's the more likely real error).
        if (accountHint == null) {
            throw UnmappedAccountException(
                "no accountHint supplied for $fileName and this export prints no account number of its own",
                userMessage = "This file doesn't say which account it's for, and its folder isn't mapped " +
                    "to one yet. Map the folder to an account in the ledger tab, then scan again. " +
                    "Nothing was imported.",
            )
        }
        return transactions.map { it.copy(accountId = accountHint) }
    }

    /** Parses one 3-field summary row ("label,,\"amount\""), validating the label prefix. */
    private fun summaryAmount(line: String, expectedLabelPrefix: String, fileName: String): Long {
        val fields = parseCsvLine(line)
        if (fields.size != 3) {
            throw GenericStatementParseException("expected 3 fields in summary row: '$line' in $fileName")
        }
        if (!fields[0].trim().startsWith(expectedLabelPrefix)) {
            throw GenericStatementParseException(
                "expected a '$expectedLabelPrefix' row, got '${fields[0]}' in $fileName"
            )
        }
        return parseMoneyCents(fields[2].trim())
    }

    private fun parseDate(token: String): Long = try {
        LocalDate.parse(token.trim(), DATE_FORMAT).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    } catch (e: DateTimeParseException) {
        throw GenericStatementParseException("unrecognized date: '$token'")
    }
}

/**
 * Minimal RFC-4180-shaped splitter: handles quoted fields, commas inside
 * quotes, and `""` as an escaped literal quote. BofA quotes every amount
 * field and any description containing a comma, exactly the two things
 * this needs to get right.
 *
 * Promoted from a private member of [BofaCsvStatementParser] to a top-level
 * function in this package (ticket 12 §2) so [BofaCardCsvStatementParser]
 * can reuse it for its own `Payee`/`Address` fields instead of duplicating
 * the same quoting logic a second time.
 */
internal fun parseCsvLine(line: String): List<String> {
    val fields = mutableListOf<String>()
    val current = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        if (inQuotes) {
            if (c == '"') {
                if (i + 1 < line.length && line[i + 1] == '"') {
                    current.append('"')
                    i++
                } else {
                    inQuotes = false
                }
            } else {
                current.append(c)
            }
        } else {
            when (c) {
                '"' -> inQuotes = true
                ',' -> {
                    fields.add(current.toString())
                    current.setLength(0)
                }
                else -> current.append(c)
            }
        }
        i++
    }
    fields.add(current.toString())
    return fields
}
