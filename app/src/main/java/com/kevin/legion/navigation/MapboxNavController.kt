package com.kevin.legion.navigation

import android.content.Context
import android.location.Location
import android.util.Log
import com.mapbox.api.directions.v5.models.RouteOptions
import com.mapbox.geojson.Point
import com.mapbox.navigation.base.extensions.applyDefaultNavigationOptions
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
import com.mapbox.navigation.core.arrival.ArrivalObserver
import com.mapbox.navigation.core.trip.session.RouteProgressObserver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the Mapbox Navigation SDK for one guided trip (`.scratch/mapbox-nav/`, ADR 0054; spike).
 * A plain class with constructor injection and no `object` (CLAUDE.md sec 8). **There is no Hilt in
 * the tree yet** (migration step 2 has not landed), so nothing `@Inject`s this: the spike's
 * ViewModel constructs it, the same stopgap `HomeViewModel` documents. Adding `@Inject constructor`
 * later changes nothing in here.
 *
 * Lives in `navigation/`, not `location/`: `location/NavigationController` is the Google Maps
 * hand-off that stays live until ticket 11, and two things named alike in one package is how the
 * wrong one gets called.
 *
 * **Billing (ticket 07).** A trip is billed from `startTripSession()`; with no route that is a Free
 * Drive trip. So the [MapboxNavigation] instance is created only when guidance is requested, the
 * session starts only after routes arrive ([NavTripGuard.routesReady]), and [stop], arrival and
 * [onScreenLeft] all tear the instance down with `MapboxNavigationProvider.destroy()`. The session is
 * stopped BEFORE anything clears the routes, because clearing routes under a running session starts
 * a Free Drive trip. Route requests made before the session starts are billed per request by
 * Mapbox; that is the one cost outside a trip and it is unavoidable.
 *
 * **No token:** [state] says [NavFormat.NOT_SET_UP] in words and no SDK object is ever created.
 * Main-thread only: every Mapbox callback and every call here is on the main looper.
 */
class MapboxNavController(
    private val appContext: Context,
    private val accessToken: () -> String,
) {
    private val guard = NavTripGuard()
    private var navigation: MapboxNavigation? = null
    private var requestId: Long? = null

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<NavState> = _state.asStateFlow()

    private val _routes = MutableStateFlow<List<NavigationRoute>>(emptyList())

    /** The routes in play, for the map's route line. Empty whenever no trip is requested or running. */
    val routes: StateFlow<List<NavigationRoute>> = _routes.asStateFlow()

    private fun initialState(): NavState =
        if (NavFormat.hasToken(accessToken())) {
            NavState(NavPhase.IDLE, "Ready. Nothing is running and no trip is billed.")
        } else {
            NavState(NavPhase.NOT_SET_UP, NavFormat.NOT_SET_UP)
        }

    /**
     * Requests a route from [origin] to the destination and, when it arrives, starts active
     * guidance. Mapbox points are **lng, lat**, in that order (Midnight AI's lesson).
     */
    private fun refusalFor(origin: Location?): NavState? = when {
        !NavFormat.hasToken(accessToken()) -> NavState(NavPhase.NOT_SET_UP, NavFormat.NOT_SET_UP)
        origin == null -> NavState(
            NavPhase.FAILED,
            "No location fix yet, so no route was requested. Wait for a fix and try again.",
        )
        else -> null
    }

    // SDK init can throw anything (bad token, native load); report it in words, never crash the screen.
    @Suppress("TooGenericExceptionCaught")
    fun startGuidance(origin: Location?, destLat: Double, destLng: Double, destName: String) {
        refusalFor(origin)?.let {
            _state.value = it
            return
        }
        if (origin == null || !guard.requestStarted()) return
        _state.value = NavState(NavPhase.REQUESTING, "Asking Mapbox for a route to $destName.")
        try {
            val nav = MapboxNavigationProvider.create(NavigationOptions.Builder(appContext).build())
            navigation = nav
            nav.registerRouteProgressObserver(progressObserver)
            nav.registerArrivalObserver(arrivalObserver)
            val options = RouteOptions.builder()
                .applyDefaultNavigationOptions()
                .coordinatesList(
                    listOf(
                        Point.fromLngLat(origin.longitude, origin.latitude),
                        Point.fromLngLat(destLng, destLat),
                    ),
                )
                .build()
            requestId = nav.requestRoutes(options, routerCallback(nav, destName))
        } catch (t: Throwable) {
            Log.w(TAG, "route request failed to start", t)
            guard.requestFailed()
            teardown()
            val why = t.message ?: t::class.java.simpleName
            _state.value = NavState(NavPhase.FAILED, "No route was requested: $why.")
        }
    }

    /** Stop button. Ends the trip session and destroys the SDK instance; safe to call when idle. */
    fun stop() {
        guard.stopRequested()
        teardown()
        _state.value = NavState(NavPhase.IDLE, "Stopped. No trip is running.")
    }

    /** The screen left composition. Same teardown as [stop], and must run so a trip never outlives its screen. */
    fun onScreenLeft() {
        guard.screenLeft()
        teardown()
        _state.value = initialState()
    }

    private fun routerCallback(nav: MapboxNavigation, destName: String) = object : NavigationRouterCallback {
        override fun onRoutesReady(routes: List<NavigationRoute>, @RouterOrigin routerOrigin: String) {
            if (!guard.routesReady(routes.size)) {
                // Cancelled while in flight (phase IDLE: nothing to report) or an empty answer
                // (phase FAILED). Either way no session starts, so nothing is billed as a trip.
                if (guard.phase == NavPhase.FAILED) {
                    teardown()
                    _state.value = NavState(NavPhase.FAILED, "Mapbox returned no route to $destName.")
                }
                return
            }
            _routes.value = routes
            nav.setNavigationRoutes(routes)
            nav.startTripSession()
            _state.value = NavState(NavPhase.GUIDING, "Guiding to $destName.")
        }

        override fun onFailure(reasons: List<RouterFailure>, routeOptions: RouteOptions) {
            guard.requestFailed()
            teardown()
            val why = reasons.firstOrNull()?.message ?: "unknown reason"
            _state.value = NavState(NavPhase.FAILED, "No route: $why.")
        }

        override fun onCanceled(routeOptions: RouteOptions, @RouterOrigin routerOrigin: String) {
            guard.requestFailed()
            teardown()
        }
    }

    private val progressObserver = RouteProgressObserver { progress: RouteProgress ->
        if (!guard.sessionShouldRun) return@RouteProgressObserver
        _state.value = NavState(
            phase = NavPhase.GUIDING,
            message = "Guiding.",
            progress = NavProgress(
                distanceRemainingM = progress.distanceRemaining.toDouble(),
                durationRemainingS = progress.durationRemaining,
                nextManeuver = progress.bannerInstructions?.primary()?.text(),
            ),
        )
        // Backup to the arrival observer: a COMPLETE state with no waypoints left is arrival.
        if (progress.currentState == RouteProgressState.COMPLETE && progress.remainingWaypoints == 0) {
            onArrived()
        }
    }

    private val arrivalObserver = object : ArrivalObserver {
        override fun onFinalDestinationArrival(routeProgress: RouteProgress) = onArrived()
        override fun onNextRouteLegStart(routeLegProgress: RouteLegProgress) = Unit
        override fun onWaypointArrival(routeProgress: RouteProgress) = Unit
    }

    private fun onArrived() {
        guard.arrived()
        teardown()
        _state.value = NavState(NavPhase.ARRIVED, "You have arrived. The trip has ended.")
    }

    /** Stops the session FIRST (clearing routes under a live session would start Free Drive), then destroys. */
    // Every step is best-effort; the destroy in `finally` is what ends billing.
    @Suppress("TooGenericExceptionCaught")
    private fun teardown() {
        val nav = navigation
        navigation = null
        _routes.value = emptyList()
        if (nav == null) return
        try {
            requestId?.let { nav.cancelRouteRequest(it) }
            nav.unregisterRouteProgressObserver(progressObserver)
            nav.unregisterArrivalObserver(arrivalObserver)
            // Idempotent on a session that never started, and a leaked Free Drive trip is billed.
            nav.stopTripSession()
        } catch (t: Throwable) {
            Log.w(TAG, "teardown step failed; destroying anyway", t)
        } finally {
            requestId = null
            MapboxNavigationProvider.destroy()
        }
    }

    private companion object {
        const val TAG = "MapboxNavController"
    }
}
