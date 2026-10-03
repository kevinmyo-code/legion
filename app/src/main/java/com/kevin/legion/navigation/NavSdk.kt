package com.kevin.legion.navigation

/** One route request: where from, through which stops, to where, avoiding what. */
data class RouteRequest(
    val origin: GeoPoint,
    val stops: List<NavDestination>,
    val destination: NavDestination,
    val avoid: Set<AvoidKind>,
)

sealed interface RouteRequestResult {
    /**
     * [token] is opaque to the controller: the production seam keeps the SDK's own route objects
     * in it and takes it back in [NavSdk.setRoutes]. [routes] are the summaries, in the SDK's order.
     */
    data class Ready(val token: Any, val routes: List<NavRouteInfo>) : RouteRequestResult

    data class Failed(val kind: RouteFailure, val message: String?) : RouteRequestResult

    /** The SDK reported the request cancelled (we asked it to, or it was superseded). */
    data object Canceled : RouteRequestResult
}

/** What the SDK tells the controller on its own, as the trip goes. All on the main thread. */
interface NavSdkListener {
    fun onProgress(progress: NavProgressInfo)

    /** Road name and speed limit from map matching. Null is unknown, and is passed through as unknown. */
    fun onLocation(road: String?, speedLimit: SpeedLimit?)

    /** The navigator's routes changed (a reroute, a refresh, a new alternative), primary first. */
    fun onRoutesChanged(routes: List<NavRouteInfo>)

    fun onReroute(state: RerouteStatus, message: String?)

    fun onArrival()
}

enum class RerouteStatus { IDLE, FETCHING, FAILED }

/** One route-progress tick, already in plain units. */
data class NavProgressInfo(
    val durationLeftS: Double,
    val distanceLeftM: Double,
    val turn: NavTurn?,
    val then: String?,
    /** Waypoints still ahead, the final destination included. */
    val remainingWaypoints: Int,
    /** The SDK reports this progress state as route-complete. */
    val complete: Boolean,
)

/**
 * The seam between [MapboxNavController]'s rules and the Mapbox Navigation SDK
 * (`MapboxNavSdk` is the one real implementation). It exists so the billing guard and the honesty
 * table can be unit-tested with a fake: nothing Mapbox-typed crosses it, and **every read here
 * ([isSessionRunning], [activeRoutes]) is how the controller checks an outcome AFTER a call**
 * (ticket 04: a result is derived from SDK state, never from the call having been made).
 *
 * Callbacks arrive on the main thread; so does every call. One instance is one `MapboxNavigation`.
 */
// The seam mirrors the SDK's verbs one for one; fewer methods would hide a billing-relevant step.
@Suppress("TooManyFunctions")
interface NavSdk {
    var listener: NavSdkListener?

    /** Asks for routes. Returns a request id for [cancelRequest]. Billed per request when no session runs. */
    fun requestRoutes(request: RouteRequest, onResult: (RouteRequestResult) -> Unit): Long

    fun cancelRequest(id: Long)

    /**
     * Puts a [RouteRequestResult.Ready] token's routes on the map as the standing preview. Called by
     * the controller only for a preview it ACCEPTED, so a request that is then refused (a stop the
     * route ignores, an avoid it did not apply) never leaves its routes drawn.
     */
    fun showPreview(token: Any)

    /** Sets the navigator's routes from a [RouteRequestResult.Ready] token with [primaryIndex] first. */
    fun setRoutes(token: Any, primaryIndex: Int, onDone: (Boolean) -> Unit)

    /** Makes the route at [index] of the navigator's current routes the primary one. */
    fun switchPrimary(index: Int, onDone: (Boolean) -> Unit)

    /** Starts the trip session (Active Guidance when routes are set). */
    fun startSession()

    /**
     * Stops the trip session. Idempotent. MUST be called before [clearRoutes]: clearing routes under
     * a live session starts a Free Drive trip.
     */
    fun stopSession()

    fun clearRoutes()

    /** Ends billing: destroys the SDK instance. The seam is dead afterwards. */
    fun destroy()

    fun isSessionRunning(): Boolean

    /** The navigator's routes right now, primary first; empty when none are set. */
    fun activeRoutes(): List<NavRouteInfo>

    /** The primary route's shape, for along-route search. Empty when none. */
    fun primaryGeometry(): List<GeoPoint>
}
