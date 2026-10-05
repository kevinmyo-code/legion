package com.kevin.legion.navigation.resolve

import com.kevin.legion.navigation.GeoPoint
import com.kevin.legion.navigation.NavDestination
import com.kevin.legion.navigation.NavFormat
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Where an answer came from, in the order the resolver tries them (ticket 03). */
enum class SourceKind(val label: String) {
    SAVED_PLACE("saved places"),
    CALENDAR("calendar"),
    CONTACTS("contacts"),
    SEARCH("Mapbox search"),
}

/** What kind of thing a search hit is; the ambiguity rule compares like with like (device-run defect 12). */
enum class PlaceKind { ADDRESS, POI, OTHER }

/**
 * One place a lookup could mean. **Held in memory only**: a search hit is a temporary geocode (ToS
 * 2.7.2 / 2.10.1) and is never written to Room, prefs or a log. [detail] is the address text, shown
 * instead of coordinates (ToS 2.7.1: a Mapbox result's lat/lng is never displayed).
 */
data class Candidate(
    val name: String,
    val detail: String?,
    val latitude: Double,
    val longitude: Double,
    val distanceM: Double?,
    val source: SourceKind,
    /** The result's own category ("gas station"), when the search gave one. Null is unknown. */
    val category: String? = null,
    val kind: PlaceKind = PlaceKind.OTHER,
) {
    /** The line under the name: its category (when known) and its address, so a hit can be told apart. */
    fun subtitle(): String? =
        listOfNotNull(category?.replaceFirstChar { it.uppercase() }, detail?.takeIf { it.isNotBlank() })
            .joinToString(" · ").ifEmpty { null }

    fun toDestination() = NavDestination(name, latitude, longitude, subtitle())
}

/** What a lookup knows about the world: the live fix (for proximity) and, for a via, the route so far. */
data class LookupContext(val fix: GeoPoint?, val alongRoute: List<GeoPoint>? = null)

/** An address string a source found and search must still turn into coordinates (calendar, contacts). */
data class AddressToSearch(val label: String, val text: String)

/** What one source says about a phrase. Unreadable and empty are different answers (CLAUDE.md sec 1). */
sealed interface SourceAnswer {
    data class Hits(val candidates: List<Candidate>, val ambiguous: Boolean) : SourceAnswer

    /** The source matched but holds only address TEXT: search resolves it. Several means ambiguous. */
    data class NeedsSearch(val items: List<AddressToSearch>) : SourceAnswer

    /** The source was readable and nothing in it matched. Fall through. */
    data object NoMatch : SourceAnswer

    /** The source could not be read (permission, I/O, network). NOT the same as no match. Fall through, but say so. */
    data class Unreadable(val why: String) : SourceAnswer

    /**
     * The source matched something with no address (an event with no location). Terminal: said in
     * words, never guessed.
     */
    data class NoAddress(val why: String) : SourceAnswer
}

interface DestinationSource {
    val kind: SourceKind

    suspend fun lookup(query: String, ctx: LookupContext): SourceAnswer
}

/** The line a source left in the story, for the "I couldn't find it" sentence. */
data class SourceReport(val kind: SourceKind, val line: String, val unreadable: Boolean)

sealed interface Resolution {
    /**
     * A destination was found. [ambiguous] means several plausible hits: the caller names
     * [candidates].first() with its distance and waits for a yes (ticket 03's read-back rule); when
     * false it starts and says where it is going. [notes] are sources that could not be read, worth
     * a word even on success ("I couldn't read your calendar").
     */
    data class Resolved(
        val destination: NavDestination,
        val candidates: List<Candidate>,
        val ambiguous: Boolean,
        val source: SourceKind,
        val notes: List<String>,
    ) : Resolution {
        /** The sentence to read back. */
        fun sentence(): String {
            val top = candidates.first()
            if (!ambiguous) return "Going to ${describe(top)}."
            val others = candidates.drop(1).joinToString("; ") { describe(it) }
            val more = if (others.isEmpty()) "" else " Others: $others."
            return "Several places match. Top pick: ${describe(top)}.$more Is that the one?"
        }

        private fun describe(c: Candidate): String {
            val where = c.detail?.takeIf { it.isNotBlank() && !c.name.contains(it) }?.let { ", $it" }.orEmpty()
            val far = c.distanceM?.let { " (${NavFormat.distance(it)} away)" }.orEmpty()
            return "${c.name}$where$far"
        }
    }

    /** Nothing usable. [message] says in words what was and was not found, and what could not be read. */
    data class NotFound(val message: String, val reports: List<SourceReport>) : Resolution
}

/**
 * Turns words into a destination (mapbox-nav ticket 03). Sources are tried in order: saved places,
 * calendar, contacts (injected, so this class is unit-tested with fakes), then Mapbox search through
 * [placeSearch]. **The first source that MATCHES decides**: one clear match goes, several is
 * ambiguous and asks. A source that matched nothing or could not be read falls through, and the
 * unreadable ones are remembered so a miss is never reported as "nothing there".
 *
 * Reading of ticket 03's "first match that is unambiguous wins": an ambiguous earlier source is
 * returned as ambiguous rather than skipped for a later source that is unambiguous. Two saved
 * places both called "gym" resolving silently to a stranger's gym from search is the expensive
 * failure the read-back rule exists to stop. If Kevin meant the other reading it is the `return`
 * on the ambiguous branch below.
 */
class DestinationResolver(
    private val sources: List<DestinationSource>,
    private val placeSearch: PlaceSearch,
) {
    private val searchSource = SearchSource(placeSearch)

    // Each early return is a verdict (resolved, or a terminal "no address"); the loop reads top to bottom.
    @Suppress("ReturnCount")
    suspend fun resolve(text: String, ctx: LookupContext): Resolution {
        val query = text.trim()
        if (query.isEmpty()) return Resolution.NotFound("I didn't catch where to go.", emptyList())
        val reports = mutableListOf<SourceReport>()
        for (source in sources + searchSource) {
            val answer = when (val a = source.lookup(query, ctx)) {
                is SourceAnswer.NeedsSearch -> addressesToHits(a, ctx, reports, source.kind)
                else -> a
            }
            when (answer) {
                SourceAnswer.NoMatch -> reports += SourceReport(source.kind, "nothing matched", unreadable = false)
                is SourceAnswer.Unreadable -> reports += SourceReport(source.kind, answer.why, unreadable = true)
                is SourceAnswer.NoAddress ->
                    return Resolution.NotFound(answer.why, reports + SourceReport(source.kind, answer.why, false))
                is SourceAnswer.Hits -> if (answer.candidates.isNotEmpty()) {
                    return Resolution.Resolved(
                        destination = answer.candidates.first().toDestination(),
                        candidates = answer.candidates,
                        ambiguous = answer.ambiguous,
                        source = source.kind,
                        notes = reports.filter { it.unreadable }.map { noteFor(it) },
                    )
                } else {
                    reports += SourceReport(source.kind, "nothing matched", unreadable = false)
                }
                is SourceAnswer.NeedsSearch -> error("addressesToHits never returns NeedsSearch")
            }
        }
        return Resolution.NotFound(notFoundSentence(query, reports), reports)
    }

    /** Resolves calendar / contact address text through search; failures become an Unreadable, never a guess. */
    private suspend fun addressesToHits(
        needs: SourceAnswer.NeedsSearch,
        ctx: LookupContext,
        reports: MutableList<SourceReport>,
        from: SourceKind,
    ): SourceAnswer {
        val found = mutableListOf<Candidate>()
        val failures = mutableListOf<String>()
        for (item in needs.items) {
            when (val r = placeSearch.search(SearchQuery(text = item.text, near = ctx.fix, limit = 1))) {
                is SearchAnswer.Hits -> r.candidates.firstOrNull()?.let {
                    found += it.copy(name = item.label, detail = it.detail ?: item.text, source = from)
                }
                SearchAnswer.NoMatch -> failures += "Mapbox found no match for the address \"${item.text}\""
                is SearchAnswer.Failed -> failures += "the address \"${item.text}\" could not be looked up (${r.why})"
            }
        }
        if (found.isEmpty()) {
            // Terminal on purpose: the source DID match, so falling through to a generic search of
            // the original phrase would be guessing a place for "my next appointment".
            val label = needs.items.first().label
            return SourceAnswer.NoAddress(
                "I found \"$label\" in your ${from.label} but could not turn its address into a place: " +
                    "${failures.firstOrNull() ?: "no result"}.",
            )
        }
        if (failures.isNotEmpty()) reports += SourceReport(from, failures.first(), unreadable = true)
        return SourceAnswer.Hits(found, ambiguous = needs.items.size > 1)
    }

    private fun noteFor(r: SourceReport) = "Couldn't read ${r.kind.label}: ${r.line}."

    private fun notFoundSentence(query: String, reports: List<SourceReport>): String {
        val unread = reports.filter { it.unreadable }
        val tried = reports.filter { !it.unreadable }.map { it.kind.label }
        val head = "I couldn't find \"$query\"."
        val read = if (tried.isEmpty()) "" else " Nothing matched in ${tried.joinToString(", ")}."
        val cant = if (unread.isEmpty()) "" else " " + unread.joinToString(" ") { noteFor(it) }
        return head + read + cant
    }
}

/** Great-circle distance in metres; pure so the sort order of candidates is testable. */
object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    fun distanceM(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLng = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
    }
}

/** Lower-cases, strips punctuation and spoken filler ("take me to the"), for name matching. */
object QueryText {
    private val LEADING =
        Regex("^(take me to|take me|navigate to|navigate|go to|drive to|directions to|route to|to)\\s+")
    private val ARTICLES = setOf("the", "my", "a", "an")

    fun normalize(text: String): String {
        var t = text.lowercase().replace(Regex("[^a-z0-9' ]"), " ").replace(Regex("\\s+"), " ").trim()
        while (true) {
            val stripped = LEADING.replace(t, "").trim()
            if (stripped == t) break
            t = stripped
        }
        return t.split(' ').filter { it.isNotEmpty() && it !in ARTICLES }.joinToString(" ")
    }
}
