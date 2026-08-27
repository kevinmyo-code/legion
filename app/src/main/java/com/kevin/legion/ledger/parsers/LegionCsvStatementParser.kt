package com.kevin.legion.ledger.parsers

import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.LedgerTransaction
import com.kevin.legion.ledger.LedgerGateOutcome
import com.kevin.legion.ledger.LedgerReconciliationCheck
import java.io.InputStream
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import org.json.JSONArray
import org.json.JSONObject

/**
 * One already-parsed, already-reconciled LEGION CSV line. Kept separate from
 * [LedgerTransaction] because that entity is Room-shaped (an `id`, a `syncId`, a `sourceFileId`)
 * and this is wire-shaped - the exact per-row fields `public.commit_statement`
 * (`supabase/migrations/20260825000600_commit_statement_rpc.sql`) expects inside `lines[]`.
 */
data class LegionCsvLine(
    val txnDate: String, // already validated ISO-8601 (YYYY-MM-DD)
    val description: String,
    val amountCents: Long,
)

/**
 * The fully-parsed, gate-passed shape of one LEGION-format statement CSV
 * (`docs/ledger-csv-import-format.md`). Everything [public.commit_statement] needs is here in
 * typed form; [toCommitPayload] is the only thing that turns it into the actual jsonb-shaped
 * request body, and [toLedgerTransactions] is the only thing that turns it into today's Room
 * write shape (there is no `LedgerBackend`/Supabase wiring for statements yet - see
 * `.scratch/backend-erp/issues/05-migration-path.md`'s phase 5, "before the Ledger cutover" -
 * so this is what actually lands in `IngestPipeline.commit` today).
 */
data class LegionCsvStatement(
    val accountLast4: String,
    val accountNickname: String,
    val currency: LedgerCurrency,
    val statedTotalCents: Long,
    val openingBalanceCents: Long,
    val closingBalanceCents: Long,
    val lines: List<LegionCsvLine>,
) {
    /**
     * The exact payload shape `public.commit_statement(payload jsonb)` accepts, per that
     * migration's own reading of the object (`content_sha256`, `account_last4`,
     * `account_nickname`, `currency`, `provenance`, `stated_total_cents`, `opening_balance_cents`,
     * `closing_balance_cents`, `lines[]` of `txn_date`/`description`/`amount_cents`).
     *
     * [provenance] defaults to `LLM_RECONCILED` (ruling 6 - an LLM sat upstream of this
     * deterministic parse, so the row's origin is never `DETERMINISTIC`), but is a parameter
     * rather than a hardcoded literal so a caller cannot accidentally assert a stronger tier than
     * the data earned by copy-pasting this function without reading it.
     */
    fun toCommitPayload(
        contentSha256: String,
        sourceFileId: String?,
        displayName: String,
        sizeBytes: Long,
        provenance: IngestMethod = IngestMethod.LLM_RECONCILED,
    ): JSONObject = JSONObject().apply {
        put("content_sha256", contentSha256)
        put("source_file_id", sourceFileId)
        put("display_name", displayName)
        put("size_bytes", sizeBytes)
        put("account_last4", accountLast4)
        put("account_nickname", accountNickname)
        put("currency", currency.name)
        put("provenance", provenance.name)
        put("stated_total_cents", statedTotalCents)
        put("opening_balance_cents", openingBalanceCents)
        put("closing_balance_cents", closingBalanceCents)
        put(
            "lines",
            JSONArray(
                lines.map { line ->
                    JSONObject().apply {
                        put("txn_date", line.txnDate)
                        put("description", line.description)
                        put("amount_cents", line.amountCents)
                    }
                },
            ),
        )
    }

    /**
     * Today's actual write shape - see this class's own doc comment for why. `accountId` is
     * [accountLast4] alone, never `accountLast4 + nickname`: every existing matcher
     * ([com.kevin.legion.ledger.sameCard], dedup, the balances screen) already keys `accountId` on
     * exact-or-suffix equality against other parsers' full-PAN or bare-last-4 strings, and folding
     * the nickname into it would make this format's rows unmatchable against everything else on
     * the same account. The nickname is carried nowhere on [LedgerTransaction] today - it exists
     * for the RPC payload's disambiguation (ruling 5), which this Room-only path does not need
     * because [LedgerTransaction] has no separate concept of "two accounts sharing a last four".
     */
    fun toLedgerTransactions(fileName: String, sourceFileId: String?): List<LedgerTransaction> =
        lines.mapIndexed { index, line ->
            LedgerTransaction(
                sourceFile = fileName,
                accountId = accountLast4,
                currency = currency,
                txnDate = LocalDate.parse(line.txnDate).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                description = line.description,
                amountCents = line.amountCents,
                balanceCents = null, // this format states no per-line running balance, only the two anchors
                lineRef = "$fileName:$index",
                ingestMethod = IngestMethod.LLM_RECONCILED, // ruling 6: an LLM sat upstream, even though this parse is deterministic
                sourceFileId = sourceFileId,
            )
        }
}

/**
 * Parses `docs/ledger-csv-import-format.md`'s format - the CSV a user's own LLM produces from
 * their bank statement, per ticket 03 ruling 3
 * (`.scratch/backend-erp/issues/03-the-gate-server-side.md`). This is LEGION's OWN format, not a
 * bank's, so unlike every other parser in this package there is nothing bank-specific to
 * recognize - recognition is "does the first line match this format's own fixed header exactly".
 *
 * **Extraction here is fully deterministic** - fixed columns, strict integer/date grammars, no
 * inference - even though an LLM produced the bytes this function reads. That is why every row
 * this produces is tagged [IngestMethod.LLM_RECONCILED] rather than [IngestMethod.DETERMINISTIC]
 * (ruling 6): provenance names where the DATA came from, not the last step that touched it.
 *
 * Ordered first in [StatementDispatcher.dispatchDeterministic] (ahead of the bank-specific CSV
 * parsers) because ruling 3 makes this the format going forward, and because its header line is a
 * closed, fixed string that cannot collide with any bank's own CSV header - order between the CSV
 * recognizers is otherwise immaterial, exactly as [BofaCsvStatementParser]'s own doc comment notes
 * for the PDF/CSV ordering question.
 */
object LegionCsvStatementParser {
    private const val HEADER_ROW =
        "account_last4,account_nickname,currency,stated_total_cents,opening_balance_cents,closing_balance_cents"
    private const val LINES_HEADER_ROW = "txn_date,description,amount_cents"

    /** Exactly what [docs/ledger-csv-import-format.md] specifies: a bare signed integer, no `$`,
     * no thousands separator, no decimal point. Anything else is REJECTED, never coerced - see
     * that doc's "Field rules" section for why a "helpful" reinterpretation is unsafe here in a
     * way it is not for the retired bank-specific parsers. */
    private val CENTS_RE = Regex("""-?[0-9]+""")
    private val DATE_RE = Regex("""\d{4}-\d{2}-\d{2}""")
    private val LAST4_RE = Regex("""\d{4}""")
    private val CURRENCIES = LedgerCurrency.entries.map { it.name }.toSet()

    fun parse(fileName: String, input: InputStream, sourceFileId: String? = null): List<LedgerTransaction> =
        parseStatement(fileName, input).toLedgerTransactions(fileName, sourceFileId)

    /**
     * The full typed result, for callers that need the anchors and the account nickname (the
     * eventual Supabase upload path) rather than just [LedgerTransaction] rows. [parse] is a thin
     * wrapper over this for [StatementDispatcher]'s existing shape.
     */
    fun parseStatement(fileName: String, input: InputStream): LegionCsvStatement {
        // String(bytes, UTF_8) never throws on malformed input - this runs against every file
        // StatementDispatcher sees, including real PDFs and other banks' CSVs, and recognition
        // must fail cleanly (UnrecognizedLayoutException) rather than crash on the wrong format.
        val text = input.readBytes().toString(Charsets.UTF_8)
        val allLines = text.split("\r\n", "\n", "\r")

        val headerLine = allLines.getOrNull(0)?.trim()
        if (headerLine != HEADER_ROW) {
            throw UnrecognizedLayoutException("first line is not '$HEADER_ROW' in $fileName")
        }

        val anchorRow = allLines.getOrNull(1)
            ?: throw GenericStatementParseException(
                "'$fileName' has the LEGION CSV header but no anchor data row under it",
                userMessage = "This file has the LEGION CSV header but no data row under it. Nothing was imported.",
            )
        val anchorFields = parseCsvLine(anchorRow)
        if (anchorFields.size != 6) {
            throw GenericStatementParseException(
                "expected 6 anchor fields, got ${anchorFields.size}: '$anchorRow' in $fileName",
                userMessage = "The first data row of this LEGION CSV doesn't have the 6 columns the " +
                    "format expects (account_last4, account_nickname, currency, stated_total_cents, " +
                    "opening_balance_cents, closing_balance_cents). Nothing was imported.",
            )
        }
        // Plain indexing, not destructuring: Kotlin's stdlib only defines componentN for List up
        // to component5, and this row has 6 fields.
        val last4Raw = anchorFields[0]
        val nicknameRaw = anchorFields[1]
        val currencyRaw = anchorFields[2]
        val statedTotalRaw = anchorFields[3]
        val openingRaw = anchorFields[4]
        val closingRaw = anchorFields[5]

        val last4 = last4Raw.trim()
        if (!LAST4_RE.matches(last4)) {
            throw GenericStatementParseException(
                "account_last4 is not exactly 4 digits: '$last4Raw' in $fileName",
                userMessage = "This LEGION CSV's account_last4 field isn't exactly 4 digits ('$last4Raw'). " +
                    "Nothing was imported.",
            )
        }

        val nickname = nicknameRaw.trim()
        if (nickname.isEmpty()) {
            throw GenericStatementParseException(
                "account_nickname is blank in $fileName",
                userMessage = "This LEGION CSV's account_nickname is blank - two accounts can share a " +
                    "last-4, so a nickname is required. Nothing was imported.",
            )
        }

        val currencyToken = currencyRaw.trim()
        if (currencyToken !in CURRENCIES) {
            throw GenericStatementParseException(
                "currency is not one of $CURRENCIES: '$currencyRaw' in $fileName",
                userMessage = "This LEGION CSV's currency field ('$currencyRaw') isn't SGD or USD. Nothing was imported.",
            )
        }
        val currency = LedgerCurrency.valueOf(currencyToken)

        // Each of the three anchors is checked and named individually - CLAUDE.md's "not a
        // silent default to zero" applied literally: a caller must be told WHICH anchor is
        // missing or malformed, not handed a downstream arithmetic mismatch to puzzle out.
        val statedTotalCents = parseAnchorCents("stated_total_cents", statedTotalRaw, fileName)
        val openingBalanceCents = parseAnchorCents("opening_balance_cents", openingRaw, fileName)
        val closingBalanceCents = parseAnchorCents("closing_balance_cents", closingRaw, fileName)

        // The blank separator line (index 2) is required by the format but not itself validated
        // beyond "the lines table's header is the very next non-anchor row we look for" - a
        // stray blank line is harmless, a missing one means index 2 IS the lines header, which
        // still matches below.
        val afterAnchor = allLines.drop(2)
        val linesHeaderIndex = afterAnchor.indexOfFirst { it.trim() == LINES_HEADER_ROW }
        if (linesHeaderIndex == -1) {
            throw GenericStatementParseException(
                "no '$LINES_HEADER_ROW' header found after the anchor row in $fileName",
                userMessage = "This LEGION CSV's second table (txn_date,description,amount_cents) is " +
                    "missing or malformed. Nothing was imported.",
            )
        }

        val dataRows = afterAnchor.drop(linesHeaderIndex + 1).let { rest ->
            // Drop a single trailing blank line from a file ending in a line terminator - not a
            // malformed row, the same accommodation every other CSV parser in this package makes.
            if (rest.isNotEmpty() && rest.last().isBlank()) rest.dropLast(1) else rest
        }

        // Rule 6, applied before the reconciliation arithmetic runs: an empty extraction must be
        // unsatisfiable by construction, never a successful import of nothing.
        if (dataRows.isEmpty()) {
            throw GenericStatementParseException(
                "'$fileName' has a recognized lines header but no data rows",
                userMessage = "This LEGION CSV has no transaction rows. Nothing was imported.",
            )
        }

        val parsedLines = dataRows.map { row -> parseLine(fileName, row) }

        // The phone-side pre-check (ticket 03 ruling 2) - the SAME arithmetic
        // `public.commit_statement` runs server-side, proven to agree via the shared corpus
        // (`app/src/test/resources/gate-corpus.json`). This is a fast, local, worded failure; the
        // server's own check is the one that is actually authoritative.
        when (
            val outcome = LedgerReconciliationCheck.check(
                amountsCents = parsedLines.map { it.amountCents },
                statedTotalCents = statedTotalCents,
                openingBalanceCents = openingBalanceCents,
                closingBalanceCents = closingBalanceCents,
            )
        ) {
            is LedgerGateOutcome.Quarantined -> throw GenericStatementParseException(
                "reconciliation failed for $fileName: ${outcome.reason}",
                userMessage = outcome.reason,
            )
            is LedgerGateOutcome.Committed -> Unit
        }

        return LegionCsvStatement(
            accountLast4 = last4,
            accountNickname = nickname,
            currency = currency,
            statedTotalCents = statedTotalCents,
            openingBalanceCents = openingBalanceCents,
            closingBalanceCents = closingBalanceCents,
            lines = parsedLines,
        )
    }

    /** One named anchor field, parsed as a bare signed integer or rejected - never coerced. */
    private fun parseAnchorCents(fieldName: String, raw: String, fileName: String): Long {
        val token = raw.trim()
        if (!CENTS_RE.matches(token)) {
            throw GenericStatementParseException(
                "$fieldName is not a plain signed integer: '$raw' in $fileName",
                userMessage = "This LEGION CSV's $fieldName isn't a plain whole number of cents ('$raw' - " +
                    "no \$, no commas, no decimal point are allowed). Nothing was imported.",
            )
        }
        return token.toLong()
    }

    /**
     * A row that does not parse is a hard failure for the WHOLE file, never a silent skip -
     * CLAUDE.md section 4 rule 6's "silently dropping a row you did not recognize is the same sin
     * as accepting one you could not verify."
     */
    private fun parseLine(fileName: String, row: String): LegionCsvLine {
        val fields = parseCsvLine(row)
        if (fields.size != 3) {
            throw GenericStatementParseException(
                "expected 3 fields (txn_date,description,amount_cents), got ${fields.size}: '$row' in $fileName",
                userMessage = "A line in this LEGION CSV doesn't have the 3 columns the format expects. " +
                    "Nothing was imported.",
            )
        }
        val (dateRaw, descriptionRaw, amountRaw) = fields

        val date = dateRaw.trim()
        if (!DATE_RE.matches(date)) {
            throw GenericStatementParseException(
                "unrecognized date: '$dateRaw' in $fileName",
                userMessage = "A line in this LEGION CSV has a date that isn't YYYY-MM-DD ('$dateRaw'). " +
                    "Nothing was imported.",
            )
        }
        // A genuine calendar check, not just the regex shape - "2026-02-30" matches DATE_RE but
        // isn't a real day, and LocalDate.parse is what LEGION actually keys transactions on.
        try {
            LocalDate.parse(date)
        } catch (e: DateTimeParseException) {
            throw GenericStatementParseException(
                "not a real calendar date: '$dateRaw' in $fileName",
                userMessage = "A line in this LEGION CSV has a date that isn't a real calendar day " +
                    "('$dateRaw'). Nothing was imported.",
            )
        }

        val description = descriptionRaw.trim()
        if (description.isEmpty()) {
            throw GenericStatementParseException(
                "row missing a description: '$row' in $fileName",
                userMessage = "A line in this LEGION CSV is missing its description. Nothing was imported.",
            )
        }

        val amountToken = amountRaw.trim()
        if (!CENTS_RE.matches(amountToken)) {
            throw GenericStatementParseException(
                "amount_cents is not a plain signed integer: '$amountRaw' in $fileName",
                userMessage = "A line in this LEGION CSV has an amount that isn't a plain whole number of " +
                    "cents ('$amountRaw' - no \$, no commas, no decimal point are allowed). Nothing was imported.",
            )
        }

        return LegionCsvLine(txnDate = date, description = description, amountCents = amountToken.toLong())
    }
}
