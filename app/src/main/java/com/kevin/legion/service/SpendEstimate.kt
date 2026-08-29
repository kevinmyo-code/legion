package com.kevin.legion.service

/**
 * **Survives backend-erp ticket 25's statement-ingestion deletion** even though it lived in the
 * now-deleted `service/ScanState.kt` (ticket 06's folder-scan LLM spend gate, `AwaitingApproval`).
 * This one class has a second, unrelated caller that predates that gate:
 * [com.kevin.legion.ledger.LedgerController.categoryGuessEstimate] reuses this same "no dollar
 * figure, just token counts and whether they're measured or reasoned" shape for the CATEGORIZE
 * drilldown's own Gemini spend estimate, before batching a merchant-name guess through
 * [com.kevin.legion.ledger.CategoryAgent]. Kept as its own file, in its original package, so that
 * caller's import needed no change for a deletion that has nothing to do with it.
 *
 * **Deliberately carries no price.** ticket 06's cost-model call
 * (`.scratch/ledger-drive-ingestion/issues/06-llm-spend-gate.md` §2) found no verified current
 * Gemini price and explicitly refused to ship one derived from stale training-era figures -
 * CLAUDE.md §4 rule 5 forbids presenting an unverified figure as fact. [fileCount] here is
 * "merchant count" for the categorize caller, not a file count - the field name is unchanged from
 * the scan-gate original to avoid a second near-identical type.
 * [estimatedPromptTokensPerFile]/[estimatedResponseTokensPerFile] are "typical" reasoned constants
 * unless [basedOnMeasuredAverage] is true, in which case they are measured from real prior calls.
 */
data class SpendEstimate(
    val fileCount: Int,
    val estimatedPromptTokensPerFile: Int,
    val estimatedResponseTokensPerFile: Int,
    val basedOnMeasuredAverage: Boolean,
)
