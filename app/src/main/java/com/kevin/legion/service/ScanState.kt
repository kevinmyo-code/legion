package com.kevin.legion.service

/**
 * [IngestScanner]'s progress contract - ticket 05 resolution §7, amended by
 * ticket 06 (the LLM gate moves between phase 2a and 2b, and both parse
 * phases report progress separately since they have very different costs).
 * One `StateFlow<ScanState>` rather than a phase flow plus an event flow: a
 * collector attaching late (screen rotation) gets current state immediately
 * with nothing lost. Genuinely one-shot things go on [ScanEvent] instead -
 * see the repo's vendored `kotlin-flow-state-event-modeling` guidance.
 */
sealed interface ScanState {
    data object Idle : ScanState
    data class Listing(val folderCount: Int) : ScanState
    data class Staging(val done: Int, val total: Int) : ScanState
    /** Phase 2a - deterministic parse over every staged file. Never touches Gemini. */
    data class ParsingDeterministic(val done: Int, val total: Int, val currentName: String) : ScanState
    /** The gate. Sits between 2a and 2b (ticket 06 amendment to ticket 05) so the LLM count is exact, not a worst-case guess, and spend so far is zero. */
    data class AwaitingApproval(val newFiles: Int, val estimate: SpendEstimate) : ScanState
    /** Phase 2b - LLM extraction, only for the approved set. */
    data class ParsingLlm(val done: Int, val total: Int, val currentName: String) : ScanState
    data class Finished(val results: FileResults) : ScanState
}

/** One-shot notifications that don't belong in [ScanState] - a snackbar-class failure, never routine progress (a stale/empty listing is NOT one of these - see [FileResults]/ticket 05 §9). */
sealed interface ScanEvent {
    data class Failed(val message: String) : ScanEvent
}

/**
 * Ticket 06's spend estimate. **Deliberately carries no price.** The
 * resolution's cost-model call (`.scratch/ledger-drive-ingestion/issues/06-llm-spend-gate.md`
 * §2) found no verified current `gemini-3.5-flash-lite` price and explicitly
 * refused to ship one derived from stale training-era figures - CLAUDE.md §4
 * rule 5 forbids presenting an unverified figure as fact. [fileCount] is
 * exact (§1's whole point - deterministic parsing is free, so the fallthrough
 * count costs nothing to know before asking). [estimatedPromptTokensPerFile]/
 * [estimatedResponseTokensPerFile] are the "typical" reasoned constants from
 * the resolution's cost model UNLESS [basedOnMeasuredAverage] is true, in
 * which case they are measured from `IngestedFile.llmPromptTokens`/
 * `llmResponseTokens` across every real LLM call so far (§6's "stops being a
 * guess derived from a guess" payoff).
 *
 * TODO(pricing): once a verified, dated price-per-token for
 * `gemini-3.5-flash-lite` exists, a dollar figure can be derived from these
 * token counts * [fileCount]. Do not hardcode one before then.
 */
data class SpendEstimate(
    val fileCount: Int,
    val estimatedPromptTokensPerFile: Int,
    val estimatedResponseTokensPerFile: Int,
    val basedOnMeasuredAverage: Boolean,
)

/**
 * Accumulated per-file outcomes for a finished (or in-progress) scan, so a
 * future quarantine-review UI can observe the scan that produced an outcome
 * rather than re-querying the database for it (ticket 05 §7). All-zero is a
 * normal, expected outcome (an empty or stale-empty folder listing, or a
 * folder with nothing new) - ticket 05 §9 is explicit that this must never be
 * surfaced as "the folder is empty" or as an error.
 */
data class FileResults(
    val ingested: Int = 0,
    val quarantined: Int = 0,
    val unreadable: Int = 0,
    val duplicate: Int = 0,
    val skipped: Int = 0,
    /** Declined at the gate this scan - stays [com.kevin.legion.data.local.IngestState.NEEDS_LLM], re-offered next scan. */
    val needsLlmDeclined: Int = 0,
    val llmPromptTokensUsed: Int = 0,
    val llmResponseTokensUsed: Int = 0,
)
