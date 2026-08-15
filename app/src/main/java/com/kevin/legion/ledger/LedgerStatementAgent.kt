package com.kevin.legion.ledger

import android.util.Log
import com.kevin.legion.ai.SubAgent
import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.LedgerTransaction
import com.kevin.legion.ledger.parsers.parseMoneyCents
import org.json.JSONObject

/**
 * [LedgerStatementAgent.extract]'s result: the same [LedgerIngestResult]
 * [StatementDispatcher] used to return on its own, plus the measured token
 * counts from Gemini's `usageMetadata` (ticket 06 §6). Tokens are recorded
 * regardless of whether [result] is [LedgerIngestResult.Success] or
 * [LedgerIngestResult.Quarantined] - a call that ran and still failed to
 * reconcile still spent the money, and that must be visible, not silently
 * dropped along with the failed extraction.
 */
data class LedgerLlmOutcome(
    val result: LedgerIngestResult,
    val promptTokens: Int?,
    val responseTokens: Int?,
)

/**
 * LLM fallback for statements that don't match any known deterministic
 * layout (`.claude/plans/wiggly-beaming-quasar.md`, step 3). Extracts
 * transactions from the statement's raw text via a one-shot Gemini call
 * (same [SubAgent] pattern as [com.kevin.legion.vehicle.DiagnosticAgent]),
 * then enforces the SAME reconciliation principle the deterministic parsers
 * do - extracted transactions must sum to the movement the statement's own
 * printed opening and closing balances imply - before anything is accepted. A mismatch quarantines; the LLM's raw output
 * is never trusted on its own. Every row that passes is tagged
 * [IngestMethod.LLM_RECONCILED], never silently indistinguishable from a
 * deterministically-parsed row.
 */
object LedgerStatementAgent {
    private const val TAG = "LedgerStatementAgent"

    private val SYSTEM_INSTRUCTION = "You extract transactions from bank/financial statement text " +
        "verbatim. Never invent, round, or estimate a figure - if a value isn't printed in the text, " +
        "leave it null. You are not being asked for advice or summary, only structured extraction."

    suspend fun extract(fileName: String, statementText: String): LedgerLlmOutcome {
        val prompt = buildString {
            append("Extract every transaction from this bank statement text. Respond with ONLY a raw ")
            append("JSON object (no markdown, no commentary, no code fences) with this exact shape:\n")
            append("{\"accountId\": string, \"currency\": \"SGD\" or \"USD\" (guess from context if not ")
            append("stated), \"openingBalance\": string (the balance the statement says it STARTS at - ")
            append("\"beginning balance\", \"balance brought forward\", \"previous balance\"), ")
            append("\"closingBalance\": string (the balance the statement says it ENDS at - \"ending ")
            append("balance\", \"balance carried forward\", \"new balance total\"). Both as exact ")
            append("decimal strings like \"123.45\" or \"-50.00\", copied verbatim from the statement. ")
            append("Do NOT compute either one by adding up the transactions - if the statement does ")
            append("not print a figure, leave it null, ")
            append("\"printedTotal\": string (ONLY for a statement that prints no balances at all and ")
            append("instead prints a single total of the listed transactions - a \"TOTAL\" or \"GRAND ")
            append("TOTAL\" line. Copy it verbatim as printed. Leave it null if the statement prints ")
            append("balances, or prints no such total), ")
            append("\"transactions\": [{\"date\": string (YYYY-MM-DD), \"description\": string, ")
            append("\"amount\": string (signed exact decimal, negative for a debit/withdrawal, e.g. ")
            append("\"-12.34\")}]}.\n\n")
            append("Statement text:\n")
            append(statementText)
        }

        val outcome = try {
            SubAgent(systemInstruction = SYSTEM_INSTRUCTION, useSearch = false).askWithUsage("", prompt)
        } catch (e: Exception) {
            Log.w(TAG, "extraction request failed: ${e.message}")
            null
        }

        val raw = outcome?.text ?: return LedgerLlmOutcome(
            result = LedgerIngestResult.Quarantined(
                "I couldn't reach the extraction service to read this statement - try again in a sec."
            ),
            // Nulls here, not zeros: a null `text` means the request itself
            // failed or threw, so usageMetadata (if outcome is non-null) still
            // reflects whatever Gemini reported for that response, if any.
            promptTokens = outcome?.promptTokens,
            responseTokens = outcome?.candidatesTokens,
        )

        return LedgerLlmOutcome(
            result = parseAndReconcile(fileName, raw),
            promptTokens = outcome.promptTokens,
            responseTokens = outcome.candidatesTokens,
        )
    }

    /**
     * `internal` rather than private purely so the reconciliation gate is
     * testable without a network call - it is a pure function of [raw], and
     * it is the half of this object worth pinning down. Nothing outside this
     * file calls it in production.
     */
    internal fun parseAndReconcile(fileName: String, raw: String): LedgerIngestResult {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start == -1 || end == -1 || end < start) {
            return LedgerIngestResult.Quarantined(
                "The extraction came back in an unreadable shape - couldn't verify this statement's numbers."
            )
        }

        val root = try {
            JSONObject(raw.substring(start, end + 1))
        } catch (e: Exception) {
            return LedgerIngestResult.Quarantined(
                "The extraction came back malformed - couldn't verify this statement's numbers."
            )
        }

        // Normalized the same way BofaStatementParser normalizes the account
        // number it reads off the page (`\s+` stripped). Without this the SAME
        // card arrives as "4111 1111 1111 4146" from here and
        // "4111111111114146" from the deterministic parser, which splits one
        // account into two everywhere accountId is a grouping key - measured
        // on Kevin's device 2026-08-06, where the ledger's BALANCES block
        // listed one card twice.
        val accountId = root.optString("accountId").replace(Regex("""\s+"""), "").trim()
        val currency = when (root.optString("currency").trim().uppercase()) {
            "SGD" -> LedgerCurrency.SGD
            "USD" -> LedgerCurrency.USD
            else -> return LedgerIngestResult.Quarantined(
                "Couldn't tell which currency this statement is in - couldn't verify its numbers."
            )
        }
        if (accountId.isBlank()) {
            return LedgerIngestResult.Quarantined(
                "Couldn't find an account identifier on this statement - couldn't verify its numbers."
            )
        }

        // TWO NAMED anchors, never one field that could mean either. The
        // original prompt asked for a single "statedTotal", described as "net
        // movement ... or a printed closing total" - two different numbers
        // that differ by exactly the opening balance, so the model was free to
        // answer with either and the gate could not tell which it had been
        // handed.
        //
        // Balance pair is primary and strongest: the movement is derived, not
        // chosen. But it is not universal. A DBS/POSB "Debit Card Statement"
        // prints NO balance anywhere - just the card's transactions and a
        // "TOTAL"/"GRAND TOTAL" line (verified against Kevin's real file,
        // 2026-08-06, which is the whole document apart from boilerplate).
        // Demanding balances quarantined 12 of those, so `printedTotal` is a
        // fallback for exactly that shape.
        //
        // It stays a SEPARATE field rather than widening what openingBalance
        // may contain, because one field meaning two things is the bug this
        // whole comment exists about.
        val openingCents = parseMoneyOrNull(root.optString("openingBalance"))
        val closingCents = parseMoneyOrNull(root.optString("closingBalance"))
        val printedTotalCents = parseMoneyOrNull(root.optString("printedTotal"))

        val anchor = when {
            openingCents != null && closingCents != null ->
                Anchor.BalancePair(openingCents, closingCents)
            printedTotalCents != null -> Anchor.PrintedTotal(printedTotalCents)
            else -> return LedgerIngestResult.Quarantined(
                "This statement doesn't print balances or a total to verify against - refusing to guess."
            )
        }

        val txnArray = root.optJSONArray("transactions")
            ?: return LedgerIngestResult.Quarantined("No transactions found on this statement.")

        val transactions = mutableListOf<LedgerTransaction>()
        for (i in 0 until txnArray.length()) {
            val o = txnArray.optJSONObject(i) ?: continue
            val dateStr = o.optString("date").trim()
            val description = o.optString("description").trim()
            val amountStr = o.optString("amount").trim()
            if (dateStr.isBlank() || description.isBlank() || amountStr.isBlank()) {
                return LedgerIngestResult.Quarantined(
                    "One of the extracted transactions is missing a date, description, or amount - " +
                        "couldn't verify this statement's numbers."
                )
            }
            val txnDate = try {
                java.time.LocalDate.parse(dateStr).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
            } catch (e: Exception) {
                return LedgerIngestResult.Quarantined("Couldn't parse a transaction date: '$dateStr'.")
            }
            val amountCents = try {
                parseMoneyCents(amountStr)
            } catch (e: Exception) {
                return LedgerIngestResult.Quarantined("Couldn't parse a transaction amount: '$amountStr'.")
            }
            transactions.add(
                LedgerTransaction(
                    sourceFile = fileName,
                    accountId = accountId,
                    currency = currency,
                    txnDate = txnDate,
                    description = description,
                    amountCents = amountCents,
                    balanceCents = null,
                    lineRef = "$fileName:llm:$i",
                    ingestMethod = IngestMethod.LLM_RECONCILED,
                )
            )
        }

        if (transactions.isEmpty()) {
            return LedgerIngestResult.Quarantined("No transactions found on this statement.")
        }

        // The actual reconciliation gate: the LLM's own extraction must tie out
        // against a figure IT ALSO read off the same statement. This doesn't
        // prove the extraction is correct against ground truth, but it does
        // prove internal consistency - the one thing we can check without
        // knowing the statement's real layout.
        val actualTotal = transactions.sumOf { it.amountCents }
        when (anchor) {
            is Anchor.BalancePair -> {
                val movement = anchor.closing - anchor.opening
                if (actualTotal != movement) {
                    return LedgerIngestResult.Quarantined(
                        "This statement's extracted transactions (${formatMoney(actualTotal, currency)}) don't " +
                            "move its balance from the ${formatMoney(anchor.opening, currency)} it opens at to " +
                            "the ${formatMoney(anchor.closing, currency)} it closes at - that would need " +
                            "${formatMoney(movement, currency)}. Couldn't verify the numbers, so nothing was " +
                            "saved. Try re-exporting the statement, or check it's the right file."
                    )
                }
            }
            is Anchor.PrintedTotal -> {
                // Compared on MAGNITUDE, and only for a single-signed list.
                //
                // A bare printed "TOTAL" carries no sign convention: DBS prints
                // a card statement's charges as "TOTAL $168.69" while the same
                // rows are extracted as negatives. Requiring signed equality
                // would fail every one of them on a formatting choice rather
                // than on an arithmetic disagreement.
                //
                // Dropping the sign costs nothing HERE because of the
                // same-sign requirement below: on a list where every row
                // pushes the same direction, |sum| == |total| is exactly as
                // strong as sum == total. On a MIXED list it would not be -
                // debits and credits could cancel into a magnitude that
                // matches by luck - so a mixed list is refused outright rather
                // than checked weakly. That errs toward dropping on genuine
                // ambiguity, matching ticket 04's posture, and a mixed-sign
                // document that prints only an unsigned total has no
                // unambiguous anchor to check against anyway.
                val signs = transactions.map { it.amountCents < 0 }.distinct()
                if (signs.size > 1) {
                    return LedgerIngestResult.Quarantined(
                        "This statement prints one total but mixes charges and credits, so that total " +
                            "can't confirm the lines add up. Nothing was saved."
                    )
                }
                if (kotlin.math.abs(actualTotal) != kotlin.math.abs(anchor.total)) {
                    return LedgerIngestResult.Quarantined(
                        "This statement's extracted transactions (${formatMoney(actualTotal, currency)}) don't " +
                            "sum to the ${formatMoney(anchor.total, currency)} total it prints. Couldn't verify " +
                            "the numbers, so nothing was saved. Try re-exporting the statement, or " +
                            "check it's the right file."
                    )
                }
            }
        }

        return LedgerIngestResult.Success(transactions)
    }

    /**
     * The figure this statement is checked against. A sealed type rather than
     * a nullable pair plus a nullable total, so the gate cannot be reached
     * without exactly one of them having been chosen.
     */
    private sealed interface Anchor {
        /** Both ends of the period printed. Movement is derived, never chosen. */
        data class BalancePair(val opening: Long, val closing: Long) : Anchor

        /** No balance printed anywhere; a single "TOTAL" of the listed rows instead. */
        data class PrintedTotal(val total: Long) : Anchor
    }

    /**
     * [parseMoneyCents] or null, for a field the statement is allowed not to
     * print. Absence and unparseable both read as "this anchor is not
     * available" - which is safe only because the caller quarantines when NO
     * anchor is available, so a malformed figure can never silently downgrade
     * the check to nothing.
     */
    private fun parseMoneyOrNull(raw: String): Long? = try {
        parseMoneyCents(raw.trim())
    } catch (e: Exception) {
        null
    }
}
