package com.kevin.legion.navigation.resolve

import com.kevin.legion.navigation.GeoPoint

/**
 * One search the Mapbox Search SDK is asked to run. [category] is a canonical Mapbox category id
 * when the phrase was "nearest X" and X is one we know; [alongRoute] makes the search favour
 * results near that route (a `via`). [near] is the live fix: **always passed, because Search Box's
 * proximity defaults to the device's IP when omitted** (research 01).
 */
data class SearchQuery(
    val text: String,
    val category: String? = null,
    val near: GeoPoint? = null,
    val alongRoute: List<GeoPoint>? = null,
    val limit: Int = 5,
)

sealed interface SearchAnswer {
    data class Hits(val candidates: List<Candidate>) : SearchAnswer

    data object NoMatch : SearchAnswer

    data class Failed(val why: String) : SearchAnswer
}

/**
 * The Search SDK behind a seam so the resolver is tested with a fake. The one real implementation,
 * `MapboxPlaceSearch`, goes through the SDK only (ToS 2.9.1: no raw REST from the phone) and
 * stores nothing (ToS 2.7.2 / 2.10.1).
 */
interface PlaceSearch {
    suspend fun search(query: SearchQuery): SearchAnswer
}

/** "nearest gas station" taken apart. [term] is what to search; [category] the Mapbox id when known. */
data class ParsedSearch(val term: String, val nearest: Boolean, val category: String?)

object SearchPhrase {
    private val NEAREST = Regex("\\b(nearest|closest|nearby|near me|close by)\\b")

    /** The handful of categories worth mapping; anything else falls to a plain text search, which handles it fine. */
    private val CATEGORIES = mapOf(
        "gas station" to "gas_station",
        "gas" to "gas_station",
        "petrol station" to "gas_station",
        "coffee shop" to "coffee",
        "coffee" to "coffee",
        "cafe" to "coffee",
        "pharmacy" to "pharmacy",
        "drug store" to "pharmacy",
        "grocery store" to "grocery",
        "grocery" to "grocery",
        "supermarket" to "grocery",
        "atm" to "atm",
        "bank" to "bank",
        "hospital" to "hospital",
        "parking" to "parking",
        "parking lot" to "parking",
        "restaurant" to "restaurant",
        "hotel" to "hotel",
        "ev charger" to "ev_charging_station",
        "charging station" to "ev_charging_station",
    )

    fun parse(text: String): ParsedSearch {
        val normalized = QueryText.normalize(text)
        val nearest = NEAREST.containsMatchIn(normalized)
        val term = normalized.replace(NEAREST, " ").replace(Regex("\\s+"), " ").trim().ifEmpty { normalized }
        return ParsedSearch(term, nearest, CATEGORIES[term])
    }
}

/** Decides whether a set of search hits is one clear answer or several plausible ones (ticket 03's read-back rule). */
object SearchAmbiguity {
    private const val SAME_NAME_RUN = 3

    /**
     * Not ambiguous: a single hit; a "nearest X" or along-route ask (the user asked for the closest,
     * so the top hit IS the answer, and it is still read back with its distance); a top hit whose
     * name is the phrase and no other hit shares it; the top few hits all the same name (a chain:
     * "Starbucks" means the nearest one). Everything else is several plausible places. A heuristic,
     * reasoned rather than observed.
     */
    fun isAmbiguous(parsed: ParsedSearch, alongRoute: Boolean, hits: List<Candidate>): Boolean {
        val names = hits.map { QueryText.normalize(it.name) }
        val top = names.firstOrNull()
        return when {
            hits.size <= 1 || parsed.nearest || alongRoute -> false
            names.take(SAME_NAME_RUN).all { it == top } -> false
            else -> !(top == parsed.term && names.drop(1).none { it == top })
        }
    }
}

/** Mapbox search as the last source. Failures are [SourceAnswer.Unreadable] (said in words), never "no match". */
class SearchSource(private val search: PlaceSearch) : DestinationSource {
    override val kind = SourceKind.SEARCH

    override suspend fun lookup(query: String, ctx: LookupContext): SourceAnswer {
        val parsed = SearchPhrase.parse(query)
        val along = ctx.alongRoute?.takeIf { it.isNotEmpty() }
        if ((parsed.nearest || along != null) && ctx.fix == null) {
            return SourceAnswer.Unreadable("I don't have your location yet, so I can't find the nearest one")
        }
        // Along a route only a CATEGORY search honours the route (the SDK marks it unsupported for
        // forward text search), so a via tries the phrase as a category id before falling back.
        val category = parsed.category ?: if (along != null) parsed.term.replace(' ', '_') else null
        val answer = search.search(
            SearchQuery(text = parsed.term, category = category, near = ctx.fix, alongRoute = along),
        )
        return when (answer) {
            is SearchAnswer.Failed -> SourceAnswer.Unreadable("search couldn't be reached: ${answer.why}")
            SearchAnswer.NoMatch -> SourceAnswer.NoMatch
            is SearchAnswer.Hits -> {
                val hits = ctx.fix?.let { fix ->
                    answer.candidates.map { c ->
                        c.copy(distanceM = c.distanceM ?: Geo.distanceM(fix, GeoPoint(c.latitude, c.longitude)))
                    }
                } ?: answer.candidates
                SourceAnswer.Hits(hits, SearchAmbiguity.isAmbiguous(parsed, along != null, hits))
            }
        }
    }
}
