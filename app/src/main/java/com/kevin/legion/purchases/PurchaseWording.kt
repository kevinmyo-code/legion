package com.kevin.legion.purchases

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The sentences the bought log says when a read or a write SUCCEEDED, in one place, so the voice
 * tools and the screens cannot drift into two phrasings of one fact (ADR 0035: two paths, one
 * controller, one wording). What it says when a call FAILED is [PurchaseFailures]; the Groceries
 * item label is [GroceriesLabel].
 *
 * **Rules these sentences carry (purchase-log tickets 02 and 04):** an answer names the entry it
 * matched in the entry's OWN words and its date, never the query's; no match is an absent RECORD
 * ("I have no record of buying X"), never "you have never bought it"; a price says it was entered
 * by hand.
 */
object PurchaseWording {

    private val SAME_YEAR = DateTimeFormatter.ofPattern("MMM d", Locale.US)
    private val OTHER_YEAR = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

    /** "Sep 20", or "Sep 20, 2025" when it is not this year. [today] is injected for tests. */
    fun date(epochDay: Int, today: Int): String {
        val day = LocalDate.ofEpochDay(epochDay.toLong())
        val now = LocalDate.ofEpochDay(today.toLong())
        return day.format(if (day.year == now.year) SAME_YEAR else OTHER_YEAR)
    }

    /** "Mia", or "not recorded" for an entry whose logger was never stored. */
    fun who(entry: Purchase): String = entry.loggedBy ?: "not recorded"

    /** "$4.99" from whole cents. */
    fun money(cents: Long): String = "$" + "%d.%02d".format(cents / CENTS, cents % CENTS)

    /** "$4.99, entered by hand" - the engine's own [Purchase.priceNote] when it sent one. */
    fun price(entry: Purchase): String? = entry.priceCents?.let {
        money(it) + ", " + (entry.priceNote ?: "entered by hand")
    }

    /** The answer to "when did we last buy [query]?", from a successful read. */
    fun lastBoughtAnswer(query: String, matches: List<PurchaseMatch>, today: Int): String {
        val first = matches.firstOrNull()?.entry
            ?: return "I have no record of buying $query. That is an absent record, not proof you never did."
        val head = "You logged \"${first.item}\" on ${date(first.boughtOn, today)} (${whoPhrase(first)})."
        val others = matches.drop(1).take(MAX_LISTED - 1).joinToString("; ") {
            "\"${it.entry.item}\" on ${date(it.entry.boughtOn, today)} (${whoPhrase(it.entry)})"
        }
        return if (others.isEmpty()) head else "$head Other matches: $others."
    }

    private fun whoPhrase(entry: Purchase): String =
        entry.loggedBy ?: "who logged it was not recorded"

    /** What a successful write says: only ever called from a [PurchaseOutcome.Ok] entry. */
    fun logged(entry: Purchase, today: Int): String {
        val head = buildList {
            add("Logged \"${entry.item}\" as bought on ${date(entry.boughtOn, today)}")
            entry.store?.let { add("at $it") }
        }.joinToString(" ")
        val price = price(entry)?.let { ". Price $it." } ?: "."
        val priv = if (entry.isPrivate) " Only you can see it." else ""
        return head + price + priv
    }

    /** What a successful edit says: only ever called from a [PurchaseOutcome.Ok] entry. */
    fun edited(entry: Purchase, today: Int): String =
        "Saved \"${entry.item}\", bought on ${date(entry.boughtOn, today)}."

    /** What a successful delete says: only ever called from a [PurchaseOutcome.Ok]. */
    fun deleted(item: String): String = "Deleted \"$item\" from the bought log."

    /** The recent-purchases read-back. */
    fun recentAnswer(listing: PurchaseListing, today: Int): String {
        if (listing.entries.isEmpty()) return listing.message ?: "The bought log has no entries yet."
        val lines = listing.entries.joinToString("; ") {
            "\"${it.item}\" on ${date(it.boughtOn, today)} (${whoPhrase(it)})"
        }
        return "Most recent first: $lines." + if (listing.truncated) " There are more." else ""
    }

    private const val CENTS = 100
    private const val MAX_LISTED = 5
}
