package com.kevin.legion.navigation

import android.content.Context
import android.util.Log
import com.mapbox.api.directions.v5.DirectionsCriteria
import com.mapbox.api.directions.v5.models.RouteOptions
import com.mapbox.geojson.Point
import com.mapbox.geojson.utils.PolylineUtils
import com.mapbox.navigation.base.extensions.applyDefaultNavigationOptions
import com.mapbox.navigation.base.formatter.DistanceFormatterOptions
import com.mapbox.navigation.base.formatter.UnitType
import com.mapbox.navigation.base.options.NavigationOptions
import com.mapbox.navigation.base.route.NavigationRoute
import com.mapbox.navigation.base.route.NavigationRouterCallback
import com.mapbox.navigation.base.route.RouterFailure
import com.mapbox.navigation.base.route.RouterOrigin
import com.mapbox.navigation.base.trip.model.RouteLegProgress
import com.mapbox.navigation.base.trip.model.RouteProgress
import com.mapbox.navigation.base.trip.model.RouteProgressState
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
import com.mapbox.navigation.core.trip.session.VoiceInstructionsObserver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.mapbox.common.location.Location as MapboxLocation
import com.mapbox.navigation.base.speed.model.SpeedUnit as MapboxSpeedUnit

/**
 * The raw SDK objects the MAP needs and the controller's rules must not see: the routes to draw
 * and the live progress / location for the camera. One instance, owned by the Application beside
 * the controller; [MapboxNavSdk] writes it, the nav screen's map reads it. In-memory only (ToS
 * 2.10.1: route results are never stored).
 */
class NavMapFeed {
    private val _routes = MutableStateFlow<List<NavigationRoute>>(emptyList())

    /** Routes to draw: the preview's routes while previewing, the navigator's while guiding. */
    val routes: StateFlow<List<NavigationRoute>> = _routes.asStateFlow()

    private val _progress = MutableStateFlow<RouteProgress?>(null)
    val progress: StateFlow<RouteProgress?> = _progress.asStateFlow()

    private val _location = MutableStateFlow<MapboxLocation?>(null)

    /** The map-matched location while a session runs; null otherwise (the puck uses the device fix). */
    val location: StateFlow<MapboxLocation?> = _location.asStateFlow()

    internal fun setRoutes(routes: List<NavigationRoute>) {
        _routes.value = routes
    }

    internal fun setProgress(progress: RouteProgress?) {
        _progress.value = progress
    }

    internal fun setLocation(location: MapboxLocation?) {
        _location.value = location
    }

    internal fun clear() {
        _routes.value = emptyList()
        _progress.value = null
        _location.value = null
    }
}

/**
 * The one real [NavSdk]: a single `MapboxNavigation`, created here and **kept for the process**
 * (destroyed in [destroy] only when the token changes: the token is baked into the instance).
 * Billing is the trip session alone, [startSession] to [stopSession]; destroying one per trip
 * leaked a native `OnboardRouter` each time (second device run, 2026-10-03).
 * All the SDK-typed work the controller must not see lives in this file; the
 * rules (billing order, honesty, epochs) live in [MapboxNavController] and are tested against a fake.
 *
 * **Nothing here has run against a real SDK in a unit test** (the SDK needs its native library); it
 * is verified by compiling and, for behaviour, on the phone. Where the SDK's contract was read from
 * its jars rather than observed, the comment says so.
 */
@Suppress("TooManyFunctions") // one SDK wrapper: the seam's verbs plus the observers it registers
class MapboxNavSdk(appContext: Context, private val feed: NavMapFeed) : NavSdk {
    override var listener: NavSdkListener? = null

    private val units = UnitSystem.device()

    // Observers are registered once in `init` and unregistered once in [destroy], so a second trip
    // on the same instance can never double them (a doubled voice observer would speak each cue twice).
    // One unit system everywhere (device-run defect 9): the SDK's own distance formatter and, below,
    // the route options' voice units, so ticket 11's spoken cues come out in the same units as the
    // screen. Imperial for a US locale, metric otherwise (see [UnitSystem]).
    private val nav: MapboxNavigation = MapboxNavigationProvider.create(
        NavigationOptions.Builder(appContext)
            .distanceFormatterOptions(
                DistanceFormatterOptions.Builder(appContext)
                    .unitType(if (units == UnitSystem.IMPERIAL) UnitType.IMPERIAL else UnitType.METRIC)
                    .build(),
            )
            .build(),
    ).also { Log.d(TAG, "MapboxNavigation created; live instances ${LIVE.incrementAndGet()}") }

    private var destroyed = false

    private val progressObserver = RouteProgressObserver { progress ->
        feed.setProgress(progress)
        listener?.onProgress(toProgressInfo(progress))
    }

    private val locationObserver = object : LocationObserver {
        override fun onNewRawLocation(rawLocation: MapboxLocation) = Unit

        override fun onNewLocationMatcherResult(locationMatcherResult: LocationMatcherResult) {
            feed.setLocation(locationMatcherResult.enhancedLocation)
            val road = locationMatcherResult.road.components.firstOrNull { !it.text.isNullOrBlank() }?.text
            val limit = locationMatcherResult.speedLimitInfo.let { info ->
                // A null speed is "no posted limit on this road": unknown, passed on as null, never 0.
                info.speed?.let {
                    SpeedLimit(
                        it,
                        if (info.unit == MapboxSpeedUnit.MILES_PER_HOUR) SpeedUnit.MPH else SpeedUnit.KPH,
                    )
                }
            }
            listener?.onLocation(road, limit)
        }
    }

    private val routesObserver = RoutesObserver { result ->
        feed.setRoutes(result.navigationRoutes)
        if (result.navigationRoutes.isNotEmpty()) listener?.onRoutesChanged(result.navigationRoutes.map(::toInfo))
    }

    private val arrivalObserver = object : ArrivalObserver {
        override fun onFinalDestinationArrival(routeProgress: RouteProgress) {
            listener?.onArrival()
        }

        override fun onNextRouteLegStart(routeLegProgress: RouteLegProgress) = Unit

        override fun onWaypointArrival(routeProgress: RouteProgress) = Unit
    }

    private val rerouteObserver = RerouteController.RerouteStateObserver { state ->
        when (state) {
            is RerouteState.FetchingRoute -> listener?.onReroute(RerouteStatus.FETCHING, null)
            is RerouteState.Failed -> listener?.onReroute(RerouteStatus.FAILED, state.message)
            is RerouteState.Idle, is RerouteState.RouteFetched, is RerouteState.Interrupted ->
                listener?.onReroute(RerouteStatus.IDLE, null)
        }
    }

    // Ticket 05: the SDK decides WHAT and WHEN; LEGION's own speech path says it (`NavCueSpeaker`).
    // `announcement()` is plain text, in the voice units the route options asked for. Mapbox's own
    // voice player is never created, so there is no second, uncoordinated voice.
    private val voiceObserver = VoiceInstructionsObserver { voice ->
        voice.announcement()?.takeIf { it.isNotBlank() }?.let { listener?.onVoiceInstruction(it) }
    }

    init {
        nav.registerRouteProgressObserver(progressObserver)
        nav.registerVoiceInstructionsObserver(voiceObserver)
        nav.registerLocationObserver(locationObserver)
        nav.registerRoutesObserver(routesObserver)
        nav.registerArrivalObserver(arrivalObserver)
        nav.getRerouteController()?.registerRerouteStateObserver(rerouteObserver)
    }

    override fun requestRoutes(request: RouteRequest, onResult: (RouteRequestResult) -> Unit): Long {
        val coordinates = buildList {
            add(Point.fromLngLat(request.origin.longitude, request.origin.latitude))
            request.stops.forEach { add(Point.fromLngLat(it.longitude, it.latitude)) }
            add(Point.fromLngLat(request.destination.longitude, request.destination.latitude))
        }
        val names = buildList<String?> {
            add(null)
            request.stops.forEach { add(it.name) }
            add(request.destination.name)
        }
        val builder = RouteOptions.builder()
            .applyDefaultNavigationOptions()
            .alternatives(true)
            .voiceUnits(if (units == UnitSystem.IMPERIAL) DirectionsCriteria.IMPERIAL else DirectionsCriteria.METRIC)
            .coordinatesList(coordinates)
            .waypointNamesList(names)
        if (request.avoid.isNotEmpty()) builder.exclude(request.avoid.joinToString(",") { it.excludeValue })
        return nav.requestRoutes(builder.build(), object : NavigationRouterCallback {
            override fun onRoutesReady(routes: List<NavigationRoute>, @RouterOrigin routerOrigin: String) {
                onResult(RouteRequestResult.Ready(routes, routes.map(::toInfo)))
            }

            override fun onFailure(reasons: List<RouterFailure>, routeOptions: RouteOptions) {
                val first = reasons.firstOrNull()
                val thrown = first?.throwable?.let { it::class.java.simpleName }
                val kind = NavFormat.classifyFailure(first?.type, first?.message, thrown)
                onResult(RouteRequestResult.Failed(kind, first?.message))
            }

            override fun onCanceled(routeOptions: RouteOptions, @RouterOrigin routerOrigin: String) {
                onResult(RouteRequestResult.Canceled)
            }
        })
    }

    override fun cancelRequest(id: Long) = nav.cancelRouteRequest(id)

    @Suppress("UNCHECKED_CAST") // the token is always the List<NavigationRoute> this class put in Ready
    override fun showPreview(token: Any) = feed.setRoutes(token as List<NavigationRoute>)

    @Suppress("UNCHECKED_CAST") // the token is always the List<NavigationRoute> this class put in Ready
    override fun setRoutes(token: Any, primaryIndex: Int, onDone: (Boolean) -> Unit) {
        val routes = token as List<NavigationRoute>
        val target = routes.getOrNull(primaryIndex)
        if (target == null) {
            onDone(false)
            return
        }
        val ordered = listOf(target) + routes.filterIndexed { i, _ -> i != primaryIndex }
        nav.setNavigationRoutes(ordered, 0, RoutesSetCallback { onDone(it.isValue) })
    }

    override fun switchPrimary(index: Int, onDone: (Boolean) -> Unit) {
        val current = nav.getNavigationRoutes()
        val target = current.getOrNull(index)
        if (target == null) {
            onDone(false)
            return
        }
        // Re-setting the same routes with another primary, rather than `switchToAlternativeRoute`
        // (an opt-in preview API): same waypoints, so the same billed trip (research 01 section 5).
        val ordered = listOf(target) + current.filterIndexed { i, _ -> i != index }
        nav.setNavigationRoutes(ordered, 0, RoutesSetCallback { onDone(it.isValue) })
    }

    override fun startSession() = nav.startTripSession()

    override fun stopSession() = nav.stopTripSession()

    override fun clearRoutes() {
        nav.setNavigationRoutes(emptyList())
        // The instance outlives the trip now, so nothing else wipes a PREVIEW's routes off the map:
        // they were put there by showPreview, not by the navigator, and an already-empty navigator
        // fires no routes observer. Destroy used to do this through feed.clear().
        feed.clear()
    }

    override fun destroy() {
        // Exactly once per instance (token change only): a second call must neither unregister twice
        // nor destroy a NEWER instance the provider may already hold (it is a process-wide singleton).
        if (destroyed) return
        destroyed = true
        nav.unregisterRouteProgressObserver(progressObserver)
        nav.unregisterLocationObserver(locationObserver)
        nav.unregisterRoutesObserver(routesObserver)
        nav.unregisterArrivalObserver(arrivalObserver)
        nav.unregisterVoiceInstructionsObserver(voiceObserver)
        nav.getRerouteController()?.unregisterRerouteStateObserver(rerouteObserver)
        feed.clear()
        listener = null
        MapboxNavigationProvider.destroy()
        Log.d(TAG, "MapboxNavigation destroyed; live instances ${LIVE.decrementAndGet()}")
    }

    override fun isSessionRunning(): Boolean = nav.getTripSessionState() == TripSessionState.STARTED

    override fun activeRoutes(): List<NavRouteInfo> = nav.getNavigationRoutes().map(::toInfo)

    override fun primaryGeometry(): List<GeoPoint> {
        val geometry = nav.getNavigationRoutes().firstOrNull()?.directionsRoute?.geometry() ?: return emptyList()
        return PolylineUtils.decode(geometry, POLYLINE_PRECISION).map { GeoPoint(it.latitude(), it.longitude()) }
    }

    private fun toInfo(route: NavigationRoute): NavRouteInfo {
        val dr = route.directionsRoute
        val options = dr.routeOptions()
        val legs = dr.legs().orEmpty()
        val congestion = legs.flatMap { it.annotation()?.congestionNumeric().orEmpty() }
        val distances = legs.flatMap { it.annotation()?.distance().orEmpty() }
        val typical = dr.durationTypical()
        // Toll status: any step intersection that names a toll collection. If the response carried
        // no intersections at all it is unknown (null), not "no tolls".
        val intersections = legs.flatMap { it.steps().orEmpty() }.flatMap { it.intersections().orEmpty() }
        val tolls = if (intersections.isEmpty()) null else intersections.any { it.tollCollection() != null }
        return NavRouteInfo(
            id = route.id,
            durationS = dr.duration(),
            distanceM = dr.distance(),
            typicalDurationS = typical,
            via = legs.firstOrNull()?.summary()?.takeIf { it.isNotBlank() },
            hasTolls = tolls,
            waypoints = options?.coordinatesList().orEmpty().drop(1).map { GeoPoint(it.latitude(), it.longitude()) },
            excluded = AvoidKind.fromExclude(options?.exclude()),
            trafficSummary = TrafficSummary.of(
                dr.duration(),
                typical,
                congestion.takeIf { it.isNotEmpty() },
                distances.takeIf { it.size == congestion.size },
            ),
        )
    }

    private fun toProgressInfo(progress: RouteProgress): NavProgressInfo {
        val leg = progress.currentLegProgress
        val banner = progress.bannerInstructions?.primary()
        val toTurn = leg?.currentStepProgress?.distanceRemaining?.toDouble()
        val turn = banner?.text()?.takeIf { it.isNotBlank() }?.let {
            NavTurn(text = it, distanceM = toTurn, type = banner.type(), modifier = banner.modifier())
        }
        // Step N is the one being driven; its end is the maneuver the banner names; the maneuver
        // AFTER that one is at the end of step N+1. Read from the leg's own step list (reasoned
        // from the Directions step model, not observed on a device).
        val steps = leg?.routeLeg?.steps().orEmpty()
        val stepIndex = leg?.currentStepProgress?.stepIndex ?: -1
        val then = steps.getOrNull(stepIndex + 2)?.maneuver()?.instruction()?.takeIf { it.isNotBlank() }
        return NavProgressInfo(
            durationLeftS = progress.durationRemaining,
            distanceLeftM = progress.distanceRemaining.toDouble(),
            turn = turn,
            then = then,
            remainingWaypoints = progress.remainingWaypoints,
            complete = progress.currentState == RouteProgressState.COMPLETE,
        )
    }

    private companion object {
        const val POLYLINE_PRECISION = 6
        const val TAG = "MapboxNavSdk"

        /**
         * Instances created minus destroyed, logged on each. Since the second device run it must read
         * live 1 for the whole process and move only on a token change (device-run defect 13: the
         * SDK's "Too many OnboardRouter instances" warning came from creating one per trip).
         */
        val LIVE = java.util.concurrent.atomic.AtomicInteger()
    }
}
