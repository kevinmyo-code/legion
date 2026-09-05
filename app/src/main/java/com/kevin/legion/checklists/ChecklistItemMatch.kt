package com.kevin.legion.checklists

import com.kevin.legion.data.local.ChecklistItem

/**
 * The pure half of `manage_checklist`'s item addressing (`service/LiveToolbox.kt`) - no `Context`,
 * no Room, same split [com.kevin.legion.grocery.GroceryLogic]/`notes/NotesLogic.kt` keep beside
 * their own controllers so this is a plain JUnit test.
 *
 * Same three-tier shape as [com.kevin.legion.notes.matchItem]/[com.kevin.legion.grocery.matchGroceryItem]
 * (exact, then substring either direction, then best word-overlap), refusing rather than guessing
 * on a tie - a checklist item is addressed the same way a persistent-list item or a grocery item
 * is, so a fourth near-identical matcher would only be a fourth place to drift from the other
 * three. No singular/plural fold here ([com.kevin.legion.grocery.normalizeGroceryName]'s own
 * reasoning is grocery-specific): "3 sets goblet squats" has no plural form worth collapsing.
 */
sealed class ChecklistItemMatch {
    data class Resolved(val item: ChecklistItem) : ChecklistItemMatch()
    object NoMatch : ChecklistItemMatch()
    /** More than one candidate matched at the same confidence tier - refuse and name them, never guess. */
    data class Ambiguous(val candidates: List<ChecklistItem>) : ChecklistItemMatch()
}

/** Matches [query] against [items] (a single checklist's live items) by fuzzy text only, never by
 * position - see this file's own doc comment for the three tiers. */
fun matchChecklistItem(query: String, items: List<ChecklistItem>): ChecklistItemMatch {
    val q = query.trim().lowercase()
    if (q.isBlank() || items.isEmpty()) return ChecklistItemMatch.NoMatch

    val exact = items.filter { it.text.trim().lowercase() == q }
    if (exact.isNotEmpty()) return resolveOrAmbiguous(exact)

    val substring = items.filter { it.text.lowercase().contains(q) || q.contains(it.text.trim().lowercase()) }
    if (substring.isNotEmpty()) return resolveOrAmbiguous(substring)

    val qWords = contentWords(q)
    if (qWords.isEmpty()) return ChecklistItemMatch.NoMatch
    val scored = items
        .map { it to (contentWords(it.text.lowercase()) intersect qWords).size }
        .filter { it.second > 0 }
    if (scored.isEmpty()) return ChecklistItemMatch.NoMatch
    val bestScore = scored.maxOf { it.second }
    return resolveOrAmbiguous(scored.filter { it.second == bestScore }.map { it.first })
}

private fun resolveOrAmbiguous(matches: List<ChecklistItem>): ChecklistItemMatch =
    if (matches.size == 1) ChecklistItemMatch.Resolved(matches.first()) else ChecklistItemMatch.Ambiguous(matches)

private fun contentWords(s: String): Set<String> =
    s.split(Regex("\\W+")).filter { it.length > 2 }.toSet()
