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
 * do - extracted transactions must sum to the statement's own stated total -
 * before anything is accepted. A mismatch quarantines; the LLM's raw output
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
            append("stated), \"statedTotal\": string (the statement's own printed net movement or ")
            append("total for the period - e.g. ending balance minus beginning balance, or a printed ")
            append("closing total - as an exact decimal string like \"123.45\" or \"-50.00\"), ")
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

    private fun parseAndReconcile(fileName: String, raw: String): LedgerIngestResult {
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

        val accountId = root.optString("accountId").trim()
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

        val statedTotalCents = try {
            parseMoneyCents(root.optString("statedTotal").trim())
        } catch (e: Exception) {
            return LedgerIngestResult.Quarantined(
                "This statement doesn't print a clear total to verify against - refusing to guess."
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

        // The actual reconciliation gate: the LLM's own extraction must sum to
        // the total IT ALSO read off the same statement. This doesn't prove the
        // extraction is correct against ground truth, but it does prove
        // internal consistency - the one thing we can check without knowing
        // the statement's real layout.
        val actualTotal = transactions.sumOf { it.amountCents }
        if (actualTotal != statedTotalCents) {
            return LedgerIngestResult.Quarantined(
                "This statement's extracted transactions ($actualTotal cents) don't sum to its own " +
                    "stated total ($statedTotalCents cents) - couldn't verify the numbers, so nothing " +
                    "was saved. Try re-exporting the statement, or check it's the right file."
            )
        }

        return LedgerIngestResult.Success(transactions)
    }
}
