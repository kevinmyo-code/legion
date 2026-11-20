package com.kevin.midnightai.navigation

sealed interface RouteResult {
    /** [token] is opaque: the real seam keeps the SDK's own route objects in it. */
    data class Ready(val token: Any, val route: NavRoute) : RouteResult
    data class Failed(val message: String) : RouteResult
}

/** What the SDK reports on its own while a trip runs. Main thread. */
interface NavSdkListener {
    fun onProgress(progress: NavProgress)
    fun onRerouting(active: Boolean)
    fun onArrival()
}

/**
 * The seam between [NavController]'s rules and the Mapbox Navigation SDK ([MapboxNavSdk] is the one
 * real implementation), so the rules run against a fake. Nothing Mapbox-typed crosses it. Every
 * read ([isSessionRunning]) is how the controller checks an outcome AFTER a call.
 */
interface NavSdk {
    var listener: NavSdkListener?

    fun requestRoute(origin: GeoPoint, destination: GeoPoint, onResult: (RouteResult) -> Unit)

    /** Sets the route from a [RouteResult.Ready] token; [onDone] says whether the navigator took it. */
    fun setRoute(token: Any, onDone: (Boolean) -> Unit)

    /** Starts the trip session (guidance on the set route). May throw (permissions, native). */
    fun startSession()

    /** Stops the session, then clears the routes (in that order: routes cleared under a live session start Free Drive). Idempotent. */
    fun stopAndClear()

    fun isSessionRunning(): Boolean
}
