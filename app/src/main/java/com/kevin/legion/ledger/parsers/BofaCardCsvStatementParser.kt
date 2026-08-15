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
 * Bank of America's mid-cycle card "Download activity" CSV export
 * (`currentTransaction_<last4>.csv`, header
 * `Posted Date,Reference Number,Payee,Address,Amount`), verified against
 * Kevin's real export 2026-08-06 (facts in ticket 12's doc comments are
 * `tested`, not assumed).
 *
 * **This file carries zero reconciliation anchors, and always will**: no
 * beginning/ending balance, no stated total of any kind. Every other
 * deterministic path in this package either reconciles against a printed
 * total ([BofaStatementParser], [BofaCardStatementParser], [DbsStatementParser])
 * or against a printed running balance ([BofaCsvStatementParser]'s checking
 * CSV) - this file has neither, so it can never pass CLAUDE.md §4 rule 2,
 * and it must never be handed to an LLM fallback either: [LedgerStatementAgent]'s
 * `PrintedTotal` path also refuses mixed-sign lists, so a hypothetical
 * printed total would not have saved this file even if one existed, and
 * without one there is nothing for a model to reconcile against regardless
 * of how well it reads the rows. That reasoning used to make this object a
 * named rejection that always threw.
 *
 * Ticket 12 changes what "commit" means here without weakening what
 * "verified" means: every row this parser produces is tagged
 * [IngestMethod.UNRECONCILED] and is never asserted as fact anywhere it
 * renders (CLAUDE.md §4 rule 7). The extraction itself needs no model at
 * all - it is a fixed 5-column CSV - so it stays fully DETERMINISTIC, no
 * token spend, no nondeterminism layered on top of rows that were already
 * unverifiable before a single byte was read.
 *
 * `accountId` is the last-4 parsed from the FILENAME (`currentTransaction_4146.csv`
 * -> `"4146"`) - call 2 of ticket 12's resolution. This export prints no
 * account number anywhere in its body, so the filename is the only
 * self-describing identity available; the per-account Drive folder hint
 * ([BofaCsvStatementParser]'s `accountHint` mechanism) is deliberately NOT
 * reused here, because Kevin's real `USA Bank Statements/` folder mixes this
 * card's mid-cycle export with the checking account's own CSV, and a folder
 * hint would silently file card rows under checking. See [sameCard]
 * (`ledger/LedgerAccountIdentity.kt`) for how the resulting mismatch against
 * [BofaCardStatementParser]'s full printed account id is reconciled without
 * rewriting either stored value.
 */
object BofaCardCsvStatementParser {
    private const val HEADER = "Posted Date,Reference Number,Payee,Address,Amount"
    private val DATE_FORMAT = DateTimeFormatter.ofPattern("MM/dd/yyyy")

    /** `currentTransaction_4146.csv` -> `"4146"`. Case-insensitive on the extension only, per real SAF listings. */
    private val FILENAME_LAST4 = Regex("""currentTransaction_(\d{4})\.csv$""", RegexOption.IGNORE_CASE)

    fun parse(fileName: String, input: InputStream): List<LedgerTransaction> {
        // String(bytes, UTF_8) never throws on malformed input, same
        // reasoning as BofaCsvStatementParser.parse: this runs against
        // every file StatementDispatcher sees, including real PDFs, and
        // recognition must fail cleanly rather than crash on the wrong
        // format.
        val text = input.readBytes().toString(Charsets.UTF_8)
        val lines = text.split("\r\n", "\n", "\r")
        val firstLine = lines.firstOrNull()?.trim()
        if (firstLine != HEADER) {
            throw UnrecognizedLayoutException("first line is not '$HEADER' in $fileName")
        }

        // No placeholder, no guess (same posture as UnmappedAccountException):
        // this file states no account of its own anywhere in its body, so a
        // filename that doesn't match the expected shape leaves nothing to
        // resolve an accountId from at all.
        val accountId = FILENAME_LAST4.find(fileName)?.groupValues?.get(1)
            ?: throw GenericStatementParseException(
                "'$fileName' doesn't match the expected 'currentTransaction_<4 digits>.csv' filename shape",
                userMessage = "This looks like Bank of America's mid-cycle card CSV export, but its " +
                    "filename doesn't end in 'currentTransaction_<the card's last 4 digits>.csv', which " +
                    "is the only place this export states which card it's for. Nothing was imported.",
            )

        // Rows after the header, dropping a possible trailing blank line -
        // CRLF splitting on "\r\n" leaves an empty final element when the
        // file ends in a line terminator, and that empty element must not be
        // counted as a malformed data row.
        val rows = lines.drop(1).let { rest ->
            if (rest.isNotEmpty() && rest.last().isBlank()) rest.dropLast(1) else rest
        }

        // Zero data rows is a hard failure, not a successful import of
        // nothing - CLAUDE.md §4 rule 6 applied to a file with no
        // reconciliation anchor at all: an empty extraction must be
        // unsatisfiable by construction, the same as a partial one.
        if (rows.isEmpty()) {
            throw GenericStatementParseException(
                "'$fileName' has a recognized header but no data rows",
                userMessage = "This file has Bank of America's card export header but no transaction " +
                    "rows underneath it. Nothing was imported.",
            )
        }

        return rows.map { row -> parseRow(fileName, row, accountId) }
    }

    /**
     * A row that does not parse is a hard failure for the WHOLE file, never
     * a skip - CLAUDE.md §4 rule 6's "silently dropping a row you did not
     * recognize is the same sin as accepting one you could not verify",
     * applied here even though nothing this parser produces is ever
     * reconciled: an ingest that silently drops rows corrupts the mid-cycle
     * total the moment it starts differing from what the file actually says,
     * which is the entire reason it exists.
     */
    private fun parseRow(fileName: String, row: String, accountId: String): LedgerTransaction {
        val fields = parseCsvLine(row)
        if (fields.size != 5) {
            throw GenericStatementParseException(
                "expected 5 fields (Posted Date,Reference Number,Payee,Address,Amount), got ${fields.size}: '$row' in $fileName",
                userMessage = "A line in this card export doesn't have the 5 columns LEGION expects. " +
                    "Nothing was imported.",
            )
        }
        // Reference Number and Address are read only to reject blank Payee/Amount's neighbours by
        // position - neither is stored. Address is whitespace-padded city/state already folded into
        // Payee on BofA's own export, and Reference Number has no column of its own; see this
        // object's build-spec doc (ticket 12 §2) for why neither is worth one.
        val (_, _, payeeRaw, _, amountToken) = fields
        val description = payeeRaw.trim()
        if (description.isBlank()) {
            // userMessage set (review finding 7): without one,
            // StatementDispatcher's catch chain falls back to `message`,
            // which embeds the raw CSV row verbatim - exactly the "a raw
            // transaction line reaches a user-facing quarantine string"
            // failure that file's own comment warns about.
            throw GenericStatementParseException(
                "row missing a Payee: '$row' in $fileName",
                userMessage = "A line in this card export is missing the merchant name. Nothing was imported.",
            )
        }
        if (amountToken.isBlank()) {
            throw GenericStatementParseException(
                "row missing an Amount: '$row' in $fileName",
                userMessage = "A line in this card export is missing an amount. Nothing was imported.",
            )
        }

        return LedgerTransaction(
            sourceFile = fileName,
            accountId = accountId,
            currency = LedgerCurrency.USD,
            txnDate = parseDate(fileName, fields[0]),
            description = description,
            // Signs are already correct in the file (debits negative,
            // credits positive) - do not negate, unlike some other bank
            // exports this package parses.
            amountCents = parseMoneyCents(amountToken),
            balanceCents = null,
            lineRef = "$fileName:'${row.take(60)}'",
            ingestMethod = IngestMethod.UNRECONCILED,
        )
    }

    private fun parseDate(fileName: String, token: String): Long = try {
        LocalDate.parse(token.trim(), DATE_FORMAT).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    } catch (e: DateTimeParseException) {
        // userMessage set (review finding 7) - same reasoning as the blank
        // Payee/Amount sites above: no userMessage means the raw diagnostic
        // (which embeds the unparseable token, effectively a fragment of the
        // real row) reaches the quarantine UI verbatim.
        throw GenericStatementParseException(
            "unrecognized date: '$token' in $fileName",
            userMessage = "A line in this card export has a date LEGION doesn't recognize. Nothing was imported.",
        )
    }
}
