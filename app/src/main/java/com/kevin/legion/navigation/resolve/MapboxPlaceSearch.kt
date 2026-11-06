package com.kevin.legion.navigation.resolve

import android.util.Log
import com.kevin.legion.navigation.GeoPoint
import com.kevin.legion.navigation.NavFormat
import com.kevin.legion.navigation.RouteFailure
import com.mapbox.geojson.Point
import com.mapbox.search.ApiType
import com.mapbox.search.CategorySearchOptions
import com.mapbox.search.EtaType
import com.mapbox.search.ForwardSearchOptions
import com.mapbox.search.ResponseInfo
import com.mapbox.search.SearchCallback
import com.mapbox.search.SearchEngine
import com.mapbox.search.SearchEngineSettings
import com.mapbox.search.SearchMultipleSelectionCallback
import com.mapbox.search.SearchNavigationOptions
import com.mapbox.search.SearchOptions
import com.mapbox.search.common.AsyncOperationTask
import com.mapbox.search.common.NavigationProfile
import com.mapbox.search.result.SearchResult
import com.mapbox.search.result.SearchResultType
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
 * **Text search is one `forward` request** (nav-quality ticket 03, Kevin's decision D3: billed per
 * request, 25,000 free a month, instead of per session). It returns full results with coordinates, up
 * to the limit. Only when it finds nothing does a suggest run, and then ONE batched select resolves
 * its suggestions, never one select after another. A "nearest" ask adds a drive-time ETA per result
 * (`eta_type=navigation` from the live fix, billed as Matrix elements).
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
                // Never the result names (ToS 2.7.2): only that the category path did not answer, so a
                // phone run can tell "category search found nothing" from "the text fallback answered".
                Log.w(TAG, "category search '${query.category}' gave ${byCategory::class.java.simpleName}; trying text")
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
            // The category builder has origin and profile but no ETA type; Search Box's own
            // `eta_type` parameter rides through unsafeParameters (reasoned from the API reference,
            // to be confirmed on the phone by an `eta_min` in the NAV_AUDIT top=).
            etaOrigin(query)?.let {
                origin(it)
                navigationProfile(NavigationProfile.DRIVING)
                unsafeParameters(mapOf(ETA_TYPE to ETA_NAVIGATION))
            }
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

    /** One `forward` request; a suggest and one batched select only when it found nothing. */
    private suspend fun textSearch(query: SearchQuery, tasks: MutableList<AsyncOperationTask>): SearchAnswer {
        val done = CompletableDeferred<SearchAnswer>()
        val options = ForwardSearchOptions.Builder().apply {
            query.near?.let { proximity(it.toPoint()) }
            limit(query.limit)
            etaOrigin(query)?.let {
                origin(it)
                navigationOptions(SearchNavigationOptions(NavigationProfile.DRIVING, EtaType.NAVIGATION))
            }
            // No routeOptions: a via's phrase is tried as a category first (SearchSource), which honours
            // the route; text search stays proximity-biased, as before.
        }.build()
        tasks += engine.forward(
            query.text,
            options,
            object : SearchCallback {
                override fun onResults(results: List<SearchResult>, responseInfo: ResponseInfo) {
                    done.complete(hitsOf(results.take(query.limit)))
                }

                override fun onError(e: Exception) {
                    done.complete(failed(e))
                }
            },
        )
        // A transport failure would fail the suggest too: only an empty answer earns the fallback.
        val forward = done.await()
        return if (forward is SearchAnswer.NoMatch) suggestThenSelect(query, tasks) else forward
    }

    /** The fallback: one suggest, then every suggestion resolved in ONE batched select. */
    @Suppress("ReturnCount") // transport error, no suggestion, then the selected results
    private suspend fun suggestThenSelect(query: SearchQuery, tasks: MutableList<AsyncOperationTask>): SearchAnswer {
        val answer = CompletableDeferred<Pair<List<SearchSuggestion>, Exception?>>()
        val options = SearchOptions.Builder().apply {
            query.near?.let { proximity(it.toPoint()) }
            limit(query.limit)
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
        // A suggestion that only refines the query (a category, a "query" type) cannot be selected in a batch.
        val selectable = found.filter { it.isBatchResolveSupported }.take(query.limit)
        if (selectable.isEmpty()) return SearchAnswer.NoMatch
        val picked = CompletableDeferred<List<SearchResult>>()
        tasks += engine.select(
            selectable,
            object : SearchMultipleSelectionCallback {
                override fun onResult(
                    suggestions: List<SearchSuggestion>,
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
        val results = picked.await()
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
                category = r.categories?.firstOrNull { it.isNotBlank() },
                kind = kindOf(r),
                mapboxId = r.mapboxId.takeIf { !it.isNullOrBlank() },
                etaMinutes = r.etaMinutes,
            )
        }
        return if (candidates.isEmpty()) SearchAnswer.NoMatch else SearchAnswer.Hits(candidates)
    }

    private fun kindOf(r: SearchResult): PlaceKind = when {
        SearchResultType.POI in r.types -> PlaceKind.POI
        SearchResultType.ADDRESS in r.types -> PlaceKind.ADDRESS
        else -> PlaceKind.OTHER
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

    /** The live fix as the ETA origin, only for a "nearest" ask (ticket 03); null otherwise. */
    private fun etaOrigin(query: SearchQuery): Point? = query.near?.takeIf { query.nearest }?.toPoint()

    private companion object {
        const val TAG = "MapboxPlaceSearch"
        const val ETA_TYPE = "eta_type"
        const val ETA_NAVIGATION = "navigation"
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
