package com.kevin.legion.grocery

import com.kevin.legion.data.local.GroceryItem

/**
 * The pure half of the grocery aspect - no Room, no `Context`, no Compose, so every branch here is
 * a plain JUnit test (`GroceryLogicTest`). Same split `notes/NotesLogic.kt` uses beside
 * `notes/NotesController.kt`.
 */

/** What one completed trip contributed - see [GroceryController.completeTrip]. */
data class TripSummary(
    val bought: Int,
    /** On the list at DONE but never ticked. Deleted with the rest, and SAID so, never dropped silently. */
    val skipped: Int,
    val boughtNames: List<String>,
)

sealed class GroceryMatch {
    data class Resolved(val item: GroceryItem) : GroceryMatch()
    object NoMatch : GroceryMatch()
    /** More than one candidate matched at the same confidence tier - refuse and name them, never guess. */
    data class Ambiguous(val candidates: List<GroceryItem>) : GroceryMatch()
}

/**
 * The key two spellings of the same thing collapse onto: trimmed, lowercased, and stripped of a
 * trailing plural "s".
 *
 * The plural rule is the one non-obvious part and it is deliberately crude: "egg"/"eggs" and
 * "banana"/"bananas" are the same staple, and without it a staples memory splits its own count
 * across both spellings and neither ever looks frequent enough to suggest. It is applied only to
 * words of four letters or more, so "gas" does not become "ga". This is a heuristic, not
 * linguistics - it will not fold "tomato"/"tomatoes", and that is an accepted miss rather than a
 * reason to pull in a stemmer for a shopping list.
 */
fun normalizeGroceryName(raw: String): String {
    val s = raw.trim().lowercase()
    if (s.length >= 4 && s.endsWith("s") && !s.endsWith("ss")) return s.dropLast(1)
    return s
}

/**
 * Matches [query] against the trip's [items] by fuzzy text only, **never by position** - the same
 * three-tier shape and the same refuse-rather-than-guess posture as
 * [com.kevin.legion.notes.matchItem], with one addition at the front: grocery names collapse
 * singular/plural ([normalizeGroceryName]), so "tick off the eggs" resolves an item written "egg".
 */
fun matchGroceryItem(query: String, items: List<GroceryItem>): GroceryMatch {
    val q = query.trim().lowercase()
    if (q.isBlank() || items.isEmpty()) return GroceryMatch.NoMatch

    val exact = items.filter { it.text.trim().lowercase() == q }
    if (exact.isNotEmpty()) return resolveOrAmbiguous(exact)

    val nq = normalizeGroceryName(query)
    val normalized = items.filter { normalizeGroceryName(it.text) == nq }
    if (normalized.isNotEmpty()) return resolveOrAmbiguous(normalized)

    val substring = items.filter { it.text.lowercase().contains(q) || q.contains(it.text.trim().lowercase()) }
    if (substring.isNotEmpty()) return resolveOrAmbiguous(substring)

    val qWords = contentWords(q)
    if (qWords.isEmpty()) return GroceryMatch.NoMatch
    val scored = items
        .map { it to (contentWords(it.text.lowercase()) intersect qWords).size }
        .filter { it.second > 0 }
    if (scored.isEmpty()) return GroceryMatch.NoMatch
    val bestScore = scored.maxOf { it.second }
    return resolveOrAmbiguous(scored.filter { it.second == bestScore }.map { it.first })
}

private fun resolveOrAmbiguous(matches: List<GroceryItem>): GroceryMatch =
    if (matches.size == 1) GroceryMatch.Resolved(matches.first()) else GroceryMatch.Ambiguous(matches)

private fun contentWords(s: String): Set<String> =
    s.split(Regex("\\W+")).filter { it.length > 2 }.toSet()

/**
 * Rows for the grocery screen: **unticked first, ticked sunk to the bottom**, each half keeping the
 * order things were added.
 *
 * The opposite of `buildInboxRows`' rule, on purpose. There, a ticked item stays in place so a
 * mis-tap is undone where it happened. Here the list is read one-handed walking round a shop, and
 * what matters is what is still to find - a bought item that stayed in place would keep occupying
 * the top of the screen for the rest of the trip. Ticked items are sunk rather than hidden, so an
 * accidental tick is still visible and reversible.
 */
fun buildGroceryRows(items: List<GroceryItem>): List<GroceryRowView> {
    val (done, todo) = items.partition { it.done }
    return (todo + done).map { GroceryRowView(id = it.id, text = it.text, done = it.done) }
}

data class GroceryRowView(val id: Long, val text: String, val done: Boolean)
