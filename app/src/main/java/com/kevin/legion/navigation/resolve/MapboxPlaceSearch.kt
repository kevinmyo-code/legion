package com.kevin.legion.navigation.resolve

import com.kevin.legion.navigation.GeoPoint
import com.kevin.legion.navigation.NavFormat
import com.kevin.legion.navigation.RouteFailure
import com.mapbox.geojson.Point
import com.mapbox.search.ApiType
import com.mapbox.search.CategorySearchOptions
import com.mapbox.search.ResponseInfo
import com.mapbox.search.SearchCallback
import com.mapbox.search.SearchEngine
import com.mapbox.search.SearchEngineSettings
import com.mapbox.search.SearchOptions
import com.mapbox.search.SearchSelectionCallback
import com.mapbox.search.common.AsyncOperationTask
import com.mapbox.search.result.SearchResult
import com.mapbox.search.result.SearchSuggestion
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit
import com.mapbox.search.RouteOptions as SearchRouteOptions

/**
 * Mapbox search through the **Search SDK only** (ToS 2.9.1 forbids raw REST from a phone; research
 * 01 section 6). [SearchEngine.createSearchEngine] is used on purpose, NOT
 * `createSearchEngineWithBuiltInDataProviders`: the built-in providers keep a local history and
 * favourites store, and a Mapbox result must never be stored (ToS 2.7.2 / 2.10.1).
 *
 * Proximity is always passed from the live fix (Search Box otherwise defaults to the IP). Nothing
 * returned is kept past the caller. Main-thread: the SDK delivers its callbacks on the main looper.
 *
 * Verified by compiling against the 2.32 jars and, for behaviour, on the phone: the SDK needs its
 * native library so no unit test constructs this. The resolver above it is tested with a fake.
 */
class MapboxPlaceSearch : PlaceSearch {
    private val engine: SearchEngine by lazy {
        SearchEngine.createSearchEngine(ApiType.SEARCH_BOX, SearchEngineSettings())
    }

    override suspend fun search(query: SearchQuery): SearchAnswer {
        val tasks = mutableListOf<AsyncOperationTask>()
        try {
            if (query.category != null) {
                // Only a hit ends it: an unknown category id (a via's phrase tried as one) errors, and
                // a text search of the phrase is the right next step either way.
                val byCategory = categorySearch(query, tasks)
                if (byCategory is SearchAnswer.Hits) return byCategory
            }
            return textSearch(query, tasks)
        } catch (e: CancellationException) {
            tasks.forEach { it.cancel() }
            throw e
        }
    }

    private suspend fun categorySearch(query: SearchQuery, tasks: MutableList<AsyncOperationTask>): SearchAnswer {
        val done = CompletableDeferred<SearchAnswer>()
        val options = CategorySearchOptions.Builder().apply {
            query.near?.let { proximity(it.toPoint()) }
            limit(query.limit)
            routeOptionsFor(query)?.let { routeOptions(it) }
        }.build()
        tasks += engine.search(
            query.category.orEmpty(),
            options,
            object : SearchCallback {
                override fun onResults(results: List<SearchResult>, responseInfo: ResponseInfo) {
                    done.complete(hitsOf(results))
                }

                override fun onError(e: Exception) {
                    done.complete(failed(e))
                }
            },
        )
        return done.await()
    }

    @Suppress("ReturnCount") // transport error, no suggestion, then the picked results
    private suspend fun textSearch(query: SearchQuery, tasks: MutableList<AsyncOperationTask>): SearchAnswer {
        val answer = CompletableDeferred<Pair<List<SearchSuggestion>, Exception?>>()
        val options = SearchOptions.Builder().apply {
            query.near?.let { proximity(it.toPoint()) }
            limit(query.limit)
            // No routeOptions here: the SDK marks along-route as unsupported for forward text search,
            // so a via whose phrase is not a category degrades to a proximity-biased search.
        }.build()
        tasks += engine.search(
            query.text,
            options,
            object : com.mapbox.search.SearchSuggestionsCallback {
                override fun onSuggestions(suggestions: List<SearchSuggestion>, responseInfo: ResponseInfo) {
                    answer.complete(suggestions to null)
                }

                override fun onError(e: Exception) {
                    answer.complete(emptyList<SearchSuggestion>() to e)
                }
            },
        )
        val (found, error) = answer.await()
        if (error != null) return failed(error)
        if (found.isEmpty()) return SearchAnswer.NoMatch
        val results = mutableListOf<SearchResult>()
        for (suggestion in found.take(query.limit.coerceAtMost(MAX_SELECTS))) {
            val picked = CompletableDeferred<List<SearchResult>>()
            tasks += engine.select(
                suggestion,
                object : SearchSelectionCallback {
                    // A suggestion that needs refining instead of resolving: nothing to use, never hang on it.
                    override fun onSuggestions(suggestions: List<SearchSuggestion>, responseInfo: ResponseInfo) {
                        picked.complete(emptyList())
                    }

                    override fun onResult(
                        suggestion: SearchSuggestion,
                        result: SearchResult,
                        responseInfo: ResponseInfo,
                    ) {
                        picked.complete(listOf(result))
                    }

                    override fun onResults(
                        suggestion: SearchSuggestion,
                        results: List<SearchResult>,
                        responseInfo: ResponseInfo,
                    ) {
                        picked.complete(results)
                    }

                    override fun onError(e: Exception) {
                        picked.complete(emptyList())
                    }
                },
            )
            results += picked.await()
            if (results.size >= query.limit) break
        }
        return if (results.isEmpty()) SearchAnswer.NoMatch else hitsOf(results.take(query.limit))
    }

    private fun hitsOf(results: List<SearchResult>): SearchAnswer {
        val candidates = results.mapNotNull { r ->
            val p = r.coordinate
            Candidate(
                name = r.name,
                detail = r.address?.formattedAddress()?.takeIf { it.isNotBlank() } ?: r.descriptionText,
                latitude = p.latitude(),
                longitude = p.longitude(),
                distanceM = r.distanceMeters,
                source = SourceKind.SEARCH,
            )
        }
        return if (candidates.isEmpty()) SearchAnswer.NoMatch else SearchAnswer.Hits(candidates)
    }

    private fun failed(e: Exception): SearchAnswer.Failed {
        val kind = NavFormat.classifyFailure(null, e.message, e::class.java.simpleName)
        return SearchAnswer.Failed(
            when (kind) {
                RouteFailure.OFFLINE -> "no connection"
                RouteFailure.AUTH -> "Mapbox refused the token"
                else -> e.message ?: e::class.java.simpleName
            },
        )
    }

    private fun routeOptionsFor(query: SearchQuery): SearchRouteOptions? {
        val route = query.alongRoute?.takeIf { it.size >= 2 } ?: return null
        val sampled = RouteSampler.sample(route, MAX_ROUTE_POINTS).map { it.toPoint() }
        return SearchRouteOptions(sampled, SearchRouteOptions.Deviation.Time(MAX_DETOUR_MIN, TimeUnit.MINUTES))
    }

    private fun GeoPoint.toPoint(): Point = Point.fromLngLat(longitude, latitude)

    private companion object {
        const val MAX_SELECTS = 3
        const val MAX_ROUTE_POINTS = 100
        const val MAX_DETOUR_MIN = 10L
    }
}

/** Thins a route to at most [max] evenly spaced points (endpoints kept), so a long route is a sane request. */
object RouteSampler {
    fun sample(points: List<GeoPoint>, max: Int): List<GeoPoint> {
        if (points.size <= max || max < 2) return points
        val step = (points.size - 1).toDouble() / (max - 1)
        // The last point is taken explicitly: floating-point stepping can land one short of it.
        return List(max) { i -> if (i == max - 1) points.last() else points[(i * step).toInt()] }
    }
}
