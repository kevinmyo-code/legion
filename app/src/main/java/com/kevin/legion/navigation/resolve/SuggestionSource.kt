package com.kevin.legion.navigation.resolve

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * One calendar suggestion as the resolver sees it (`calendar/EventSuggestions.kt`). [address] is
 * the street address the source gave, or null; [location] is the free text ("venue, street, city
 * TX") an older or unstructured row may carry instead.
 */
data class SuggestionRow(
    val title: String,
    val startMs: Long,
    val allDay: Boolean,
    val city: String?,
    val venue: String?,
    val address: String?,
    val location: String?,
    /** True when the row carries the structured details, so a null [address] means "the source gave none". */
    val structured: Boolean,
)

sealed interface SuggestionsRead {
    data class Rows(val rows: List<SuggestionRow>) : SuggestionsRead

    data class Unreadable(val why: String) : SuggestionsRead
}

fun interface SuggestionsReader {
    /** Suggestions from the start of today (local) onward. A failed read is Unreadable, never empty. */
    suspend fun read(nowMs: Long): SuggestionsRead
}

/**
 * Kevin, 2026-10-09: *"on the day itself, i say hey navigate to that event in austin > it does
 * that."* Matches the phrase against suggestion titles and venues, with an optional city named in
 * the phrase ("in Austin"), **preferring today's** when the match also covers other days. One match
 * goes; several ask (the resolver's read-back); a match with no street address says so and offers
 * the venue as a search rather than guessing a place.
 *
 * Tried AFTER the user's own calendar ([CalendarSource]): a plan the user made outranks a thing they
 * could do. Finding a suggestion here makes it a destination, never a plan - nothing is written.
 */
class SuggestionSource(
    private val reader: SuggestionsReader,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : DestinationSource {
    override val kind = SourceKind.SUGGESTIONS

    @Suppress("ReturnCount") // guard clauses: unreadable, empty phrase, nothing matched, then the answer
    override suspend fun lookup(query: String, ctx: LookupContext): SourceAnswer {
        val now = nowMs()
        val rows = when (val r = reader.read(now)) {
            is SuggestionsRead.Unreadable -> return SourceAnswer.Unreadable(r.why)
            is SuggestionsRead.Rows -> r.rows
        }
        val q = " ${QueryText.normalize(query)} "
        if (q.isBlank() || rows.isEmpty()) return SourceAnswer.NoMatch
        val city = rows.mapNotNull { it.city }.distinct()
            .sortedByDescending { it.length }
            .firstOrNull { c -> q.contains(" ${QueryText.normalize(c)} ") }
        var rest = q
        city?.let { rest = rest.replace(" ${QueryText.normalize(it)} ", " ") }
        val tokens = rest.trim().split(' ').filter { it.isNotEmpty() && it !in FILLER }
        if (tokens.isEmpty() && city == null) return SourceAnswer.NoMatch

        val matched = rows
            .filter { row -> city == null || row.city.equals(city, ignoreCase = true) }
            .filter { row ->
                val hay = QueryText.normalize(listOfNotNull(row.title, row.venue).joinToString(" "))
                tokens.all { it in hay }
            }
        if (matched.isEmpty()) return SourceAnswer.NoMatch
        val today = Instant.ofEpochMilli(now).atZone(zone()).toLocalDate()
        val todays = matched.filter { dayOf(it) == today }
        val pick = todays.ifEmpty { matched }.sortedBy { it.startMs }

        val items = pick.mapNotNull { row -> searchText(row)?.let { AddressToSearch(label = row.title, text = it) } }
            .distinctBy { it.text.lowercase() }
        if (items.isEmpty()) return SourceAnswer.NoAddress(noAddressSentence(pick))
        return SourceAnswer.NeedsSearch(items, severalMatched = pick.size > 1)
    }

    private fun dayOf(row: SuggestionRow): LocalDate =
        if (row.allDay) {
            Instant.ofEpochMilli(row.startMs).atZone(ZoneOffset.UTC).toLocalDate()
        } else {
            Instant.ofEpochMilli(row.startMs).atZone(zone()).toLocalDate()
        }

    companion object {
        /** Words in "navigate to that event in Austin" that name no suggestion. */
        private val FILLER = setOf(
            "that", "this", "the", "event", "events", "suggestion", "suggestions", "suggested",
            "in", "at", "on", "for", "one", "today", "todays", "today's", "tonight", "thing",
        )

        /**
         * The text to geocode: a stated street address, with its city when the address does not
         * already name it; else, for a row with no structured details, its free-text location.
         * A structured row with no address has nothing to geocode - said, never guessed.
         */
        fun searchText(row: SuggestionRow): String? {
            val address = row.address?.trim()?.takeIf { it.isNotEmpty() }
            val city = row.city?.trim()?.takeIf { it.isNotEmpty() && address?.contains(it, ignoreCase = true) == false }
            return when {
                address != null -> if (city != null) "$address, $city" else address
                row.structured -> null
                else -> row.location?.trim()?.takeIf { it.isNotEmpty() }
            }
        }

        fun noAddressSentence(rows: List<SuggestionRow>): String {
            val top = rows.first()
            val venue = top.venue?.trim()?.takeIf { it.isNotEmpty() }
            val where = listOfNotNull(venue, top.city).joinToString(", ")
            val offer = if (venue != null) {
                " I can search for the venue instead: say \"navigate to $where\"."
            } else {
                ""
            }
            return "\"${top.title}\" is a calendar suggestion with no street address, so I have no " +
                "exact place to take you.$offer"
        }
    }
}
