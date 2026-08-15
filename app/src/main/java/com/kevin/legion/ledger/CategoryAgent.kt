package com.kevin.legion.ledger

import android.util.Log
import com.kevin.legion.ai.SubAgent
import org.json.JSONObject

/**
 * [CategoryAgent.guessBatch]'s result. Tokens are recorded even when [guesses] comes back empty -
 * same reasoning [LedgerLlmOutcome] states: a call that ran and produced nothing still spent the
 * money, and that must stay visible (`.scratch/ledger-drive-ingestion/issues/06-llm-spend-gate.md`
 * §6), not silently dropped along with a bad response.
 */
data class CategoryGuessOutcome(
    /** Merchant key ([extractMerchantKey]'s output) -> guessed [com.kevin.legion.data.local.Category.name]. Only keys the model answered with a category from the fixed list appear here. */
    val guesses: Map<String, String>,
    val promptTokens: Int?,
    val responseTokens: Int?,
)

/**
 * Ticket 07 D17: "The AI guesses only for merchants with no rule, batched, behind the existing
 * spend gate. Never per-import, never silently." Same [SubAgent] one-shot shape
 * [LedgerStatementAgent] already uses for statement extraction - reused rather than reinvented,
 * per this ticket's own instruction not to build a second gate mechanism.
 *
 * The caller ([com.kevin.legion.ledger.LedgerController.applyCategoryGuesses]) is responsible for
 * having already shown the driver an estimate and gotten an explicit approval before calling this
 * - [guessBatch] itself does not gate anything; it is the "spend" half of the gate, not the
 * "ask first" half.
 */
object CategoryAgent {
    private const val TAG = "CategoryAgent"

    private val SYSTEM_INSTRUCTION = "You assign ONE category from a fixed list to each bank " +
        "merchant name. Never invent a category outside the list given to you. If genuinely " +
        "unsure, pick the closest fit from the list rather than leaving an entry out - every " +
        "merchant must get exactly one category, spelled exactly as given."

    suspend fun guessBatch(merchantKeys: List<String>, categories: List<String>): CategoryGuessOutcome {
        if (merchantKeys.isEmpty()) return CategoryGuessOutcome(emptyMap(), null, null)

        // CLAUDE.md §4 rule 6: a check that passes when nothing real was there is not a check.
        // A fixed list of one (or zero) is not a real choice - the model has no alternative to
        // pick, so "every merchant must get exactly one category" (this object's own system
        // instruction) degenerates into "assign the only entry to everything". That is exactly how
        // a fresh-install seeding bug (categories table down to a single leftover row) turned into
        // 497 of Kevin's ledger_transactions rows silently reading `category = 'Pets'`
        // (2026-08-13, see MIGRATION_16_17's doc comment) - the model complied faithfully with a
        // prompt built from a broken premise, and nothing downstream noticed because every answer
        // it gave was individually "valid" (present in the fixed list). Refuse before spending
        // anything on a call that cannot produce a meaningful answer.
        if (categories.size < 2) {
            Log.w(
                TAG,
                "refusing to guess against an implausible category list " +
                    "(${categories.size} entr${if (categories.size == 1) "y" else "ies"}: $categories) - " +
                    "a fixed list this small cannot be a real choice, only a seeding bug"
            )
            return CategoryGuessOutcome(emptyMap(), null, null)
        }

        val prompt = buildString {
            append("Categories (choose EXACTLY one per merchant, spelled exactly as listed here): ")
            append(categories.joinToString(", "))
            append("\n\nAssign a category to each merchant below. Respond with ONLY a raw JSON ")
            append("object (no markdown, no commentary, no code fences) mapping each merchant ")
            append("string EXACTLY as given below to its chosen category string.\n\nMerchants:\n")
            merchantKeys.forEach { append("- $it\n") }
        }

        val outcome = try {
            SubAgent(systemInstruction = SYSTEM_INSTRUCTION, useSearch = false).askWithUsage("", prompt)
        } catch (e: Exception) {
            Log.w(TAG, "category guess request failed: ${e.message}")
            null
        }

        val raw = outcome?.text
            ?: return CategoryGuessOutcome(emptyMap(), outcome?.promptTokens, outcome?.candidatesTokens)

        val allowed = categories.toSet()
        val guesses = try {
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            if (start == -1 || end == -1 || end < start) {
                emptyMap()
            } else {
                val obj = JSONObject(raw.substring(start, end + 1))
                val result = mutableMapOf<String, String>()
                for (key in obj.keys()) {
                    val category = obj.optString(key)
                    // D14: a category outside the fixed list is refused even
                    // from the model's own output - a hallucinated category
                    // would poison the fixed set exactly like a freeform field
                    // would, just introduced from a different direction.
                    if (category in allowed) result[key] = category
                }
                result
            }
        } catch (e: Exception) {
            Log.w(TAG, "category guess response malformed: ${e.message}")
            emptyMap<String, String>()
        }

        return CategoryGuessOutcome(guesses, outcome.promptTokens, outcome.candidatesTokens)
    }
}
