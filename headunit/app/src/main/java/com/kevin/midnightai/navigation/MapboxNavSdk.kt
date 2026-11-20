package com.kevin.midnightai.navigation

import android.content.Context
import com.mapbox.api.directions.v5.DirectionsCriteria
import com.mapbox.api.directions.v5.models.RouteOptions
import com.mapbox.geojson.Point
import com.mapbox.navigation.base.extensions.applyDefaultNavigationOptions
import com.mapbox.navigation.base.formatter.DistanceFormatterOptions
import com.mapbox.navigation.base.formatter.UnitType
import com.mapbox.navigation.base.options.LocationOptions
import com.mapbox.navigation.base.options.NavigationOptions
import com.mapbox.navigation.base.route.NavigationRoute
import com.mapbox.navigation.base.route.NavigationRouterCallback
import com.mapbox.navigation.base.route.RouterFailure
import com.mapbox.navigation.base.route.RouterOrigin
import com.mapbox.navigation.base.trip.model.RouteLegProgress
import com.mapbox.navigation.base.trip.model.RouteProgress
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.core.MapboxNavigationProvider
import com.mapbox.navigation.core.RoutesSetCallback
import com.mapbox.navigation.core.arrival.ArrivalObserver
import com.mapbox.navigation.core.directions.session.RoutesObserver
import com.mapbox.navigation.core.reroute.RerouteController
import com.mapbox.navigation.core.reroute.RerouteState
import com.mapbox.navigation.core.trip.session.LocationMatcherResult
import com.mapbox.navigation.core.trip.session.LocationObserver
import com.mapbox.navigation.core.trip.session.RouteProgressObserver
import com.mapbox.navigation.core.trip.session.TripSessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.mapbox.common.location.Location as MapboxLocation

/**
 * What the MAP needs and the rules must not see: the routes to draw and the matched location. In
 * memory only (Mapbox ToS: route results are never stored).
 */
class NavMapFeed {
    private val _routes = MutableStateFlow<List<NavigationRoute>>(emptyList())
    val routes: StateFlow<List<NavigationRoute>> = _routes.asStateFlow()

    private val _matched = MutableStateFlow<MapboxLocation?>(null)

    /** The map-matched location while a session runs; null otherwise (the puck uses the raw fix). */
    val matched: StateFlow<MapboxLocation?> = _matched.asStateFlow()

    internal fun setRoutes(routes: List<NavigationRoute>) {
        _routes.value = routes
    }

    internal fun setMatched(location: MapboxLocation?) {
        _matched.value = location
    }

    internal fun clear() {
        _routes.value = emptyList()
        _matched.value = null
    }
}

/**
 * The one real [NavSdk] (HU-1 T5): a single `MapboxNavigation` for the process, located by
 * [BeaconLocationProvider] (the phone-fed fixes, REAL type), never by the unit's own GPS. Ported in
 * shape from the phone's `navigation/MapboxNavSdk.kt`, cut to one route, no stops, no avoid, no
 * voice (v1 shows plain turn directions). Nothing here runs in a JVM test (the SDK needs its native
 * library); the rules live in [NavController] and are tested against a fake.
 */
class MapboxNavSdk(appContext: Context, provider: BeaconLocationProvider, private val feed: NavMapFeed) : NavSdk {
    override var listener: NavSdkListener? = null

    private val nav: MapboxNavigation = MapboxNavigationProvider.create(
        NavigationOptions.Builder(appContext)
            .distanceFormatterOptions(
                DistanceFormatterOptions.Builder(appContext).unitType(UnitType.IMPERIAL).build(),
            )
            .locationOptions(
                LocationOptions.Builder()
                    .locationProviderFactory(
                        BeaconLocationProvider.Factory(provider),
                        LocationOptions.LocationProviderType.REAL,
                    )
                    .build(),
            )
            .build(),
    )

    private val progressObserver = RouteProgressObserver { progress ->
        listener?.onProgress(toProgress(progress))
    }

    private val locationObserver = object : LocationObserver {
        override fun onNewRawLocation(rawLocation: MapboxLocation) = Unit

        override fun onNewLocationMatcherResult(locationMatcherResult: LocationMatcherResult) {
            feed.setMatched(locationMatcherResult.enhancedLocation)
        }
    }

    private val routesObserver = RoutesObserver { result -> feed.setRoutes(result.navigationRoutes) }

    private val arrivalObserver = object : ArrivalObserver {
        override fun onFinalDestinationArrival(routeProgress: RouteProgress) {
            listener?.onArrival()
        }

        override fun onNextRouteLegStart(routeLegProgress: RouteLegProgress) = Unit

        override fun onWaypointArrival(routeProgress: RouteProgress) = Unit
    }

    private val rerouteObserver = RerouteController.RerouteStateObserver { state ->
        listener?.onRerouting(state is RerouteState.FetchingRoute)
    }

    init {
        nav.registerRouteProgressObserver(progressObserver)
        nav.registerLocationObserver(locationObserver)
        nav.registerRoutesObserver(routesObserver)
        nav.registerArrivalObserver(arrivalObserver)
        nav.getRerouteController()?.registerRerouteStateObserver(rerouteObserver)
    }

    override fun requestRoute(origin: GeoPoint, destination: GeoPoint, onResult: (RouteResult) -> Unit) {
        val options = RouteOptions.builder()
            .applyDefaultNavigationOptions()
            .alternatives(false)
            .voiceUnits(DirectionsCriteria.IMPERIAL)
            .coordinatesList(
                listOf(
                    Point.fromLngLat(origin.longitude, origin.latitude),
                    Point.fromLngLat(destination.longitude, destination.latitude),
                ),
            )
            .build()
        nav.requestRoutes(options, object : NavigationRouterCallback {
            override fun onRoutesReady(routes: List<NavigationRoute>, @RouterOrigin routerOrigin: String) {
                val first = routes.firstOrNull()
                if (first == null) {
                    onResult(RouteResult.Failed(RouteFailure.NO_ROUTE))
                    return
                }
                feed.setRoutes(listOf(first)) // the preview line
                val dr = first.directionsRoute
                onResult(
                    RouteResult.Ready(
                        listOf(first),
                        NavRoute(dr.duration(), dr.distance(), dr.legs()?.firstOrNull()?.summary()?.takeIf { it.isNotBlank() }),
                    ),
                )
            }

            override fun onFailure(reasons: List<RouterFailure>, routeOptions: RouteOptions) {
                onResult(RouteResult.Failed(classify(reasons.firstOrNull())))
            }

            override fun onCanceled(routeOptions: RouteOptions, @RouterOrigin routerOrigin: String) {
                onResult(RouteResult.Failed(RouteFailure.OTHER))
            }
        })
    }

    /** The SDK's failure, reduced to a kind; its text never leaves this file (review item 7). */
    private fun classify(failure: RouterFailure?): RouteFailure {
        val text = failure?.message.orEmpty()
        val thrown = failure?.throwable
        return when {
            thrown is java.io.IOException || text.contains("UnknownHost", true) || text.contains("timeout", true) ||
                text.contains("network", true) -> RouteFailure.OFFLINE
            text.contains("401") || text.contains("403") || text.contains("token", true) -> RouteFailure.TOKEN_REFUSED
            text.contains("NoRoute", true) || text.contains("no route", true) -> RouteFailure.NO_ROUTE
            else -> RouteFailure.OTHER
        }
    }

    @Suppress("UNCHECKED_CAST") // the token is always the List<NavigationRoute> put in Ready above
    override fun setRoute(token: Any, onDone: (Boolean) -> Unit) {
        nav.setNavigationRoutes(token as List<NavigationRoute>, 0, RoutesSetCallback { onDone(it.isValue) })
    }

    override fun startSession() = nav.startTripSession()

    override fun stopAndClear() {
        nav.stopTripSession()
        nav.setNavigationRoutes(emptyList())
        feed.clear()
    }

    override fun isSessionRunning(): Boolean = nav.getTripSessionState() == TripSessionState.STARTED

    private fun toProgress(progress: RouteProgress): NavProgress {
        val banner = progress.bannerInstructions?.primary()
        val toTurn = progress.currentLegProgress?.currentStepProgress?.distanceRemaining?.toDouble()
        val turn = banner?.text()?.takeIf { it.isNotBlank() }?.let {
            NavTurn(text = it, distanceM = toTurn, type = banner.type(), modifier = banner.modifier())
        }
        return NavProgress(progress.durationRemaining, progress.distanceRemaining.toDouble(), turn)
    }
}
