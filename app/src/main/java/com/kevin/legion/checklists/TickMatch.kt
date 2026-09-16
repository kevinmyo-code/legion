package com.kevin.legion.checklists

/**
 * "When did I last buy toothpaste" (web-calendar-and-lists map, ticket 04, Kevin 2026-09-16: *"i
 * tick off toothpaste today after my trip. > i ask the ai, hey when was the last time i bought
 * toothpaste > it looks back at when it was ticked."*). This is the JUDGEMENT half - pure and
 * tested here, same split [com.kevin.legion.outstanding.Outstanding] already uses on this
 * codebase: [TickHistoryController] fetches, this file only matches and ranks.
 *
 * **A tick is not a purchase.** It is evidence Kevin tapped a line - CLAUDE.md §4 rule 5 and
 * ticket 04's own wording table bind here. Nothing in this file, its caller, or any surface built
 * on [TickMatch] may say "bought"; every one of them says "ticked".
 *
 * **The matching rule is ticket 03's, resolved, not this file's to improve on**: lower-case, trim,
 * collapse internal whitespace. No stemming, no fuzzy distance, no synonyms - a near-match that is
 * WRONG is worse than a miss, because a miss says "I have no record" and a wrong match asserts a
 * tap that never happened.
 */

/**
 * One past tick, resolved back to the checklist it lived on and the item's text at read time.
 *
 * [checklistName] and [itemText] are read THROUGH tombstones (ticket 03) - [TickHistoryController]
 * walks every checklist and every item that ever existed, soft-deleted included, so a list Kevin
 * cleared after finishing it (ticket 02's whole premise: deleting a checklist does not delete its
 * ticks) still answers.
 */
data class TickMatch(
    val itemId: Long,
    val itemText: String,
    val checklistName: String,
    val tickedAt: Long,
)

/**
 * Ticket 03's exact normalisation - lower-case, trim, collapse internal whitespace. "Toothpaste",
 * "toothpaste ", "TOOTHPASTE" all reduce to the same string; "Colgate toothpaste" deliberately does
 * NOT match "toothpaste" - that narrowness is 03's own ruling, pinned by a test so a later
 * "improvement" has to argue with a red one rather than drift in quietly.
 */
fun normalizeForTickMatch(text: String): String =
    text.trim().lowercase().replace(Regex("\\s+"), " ")

/**
 * Every [TickMatch] whose item text normalizes to the same string as [query], newest tick first -
 * "when did I last buy X" wants the most recent tap. [candidates] arrives unsorted and may span
 * many checklists and many items; [TickHistoryController] is the only caller.
 */
fun matchTickHistory(query: String, candidates: List<TickMatch>): List<TickMatch> {
    val target = normalizeForTickMatch(query)
    return candidates
        .filter { normalizeForTickMatch(it.itemText) == target }
        .sortedByDescending { it.tickedAt }
}
