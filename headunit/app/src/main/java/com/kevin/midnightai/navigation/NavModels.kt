package com.kevin.midnightai.navigation

/** A point on the map. Latitude first here; the Mapbox seam swaps to lng,lat. */
data class GeoPoint(val latitude: Double, val longitude: Double)

/** Where a trip goes: a saved place on this unit (v1), by its label. */
data class NavDestination(val name: String, val point: GeoPoint)

/** The next maneuver. [type] and [modifier] are Directions' own words ("turn" / "left"). */
data class NavTurn(val text: String, val distanceM: Double?, val type: String?, val modifier: String?)

/** One route-progress tick, in plain units. Null is unknown, never zero. */
data class NavProgress(val durationLeftS: Double?, val distanceLeftM: Double?, val turn: NavTurn?)

/** A route as the controller reasons about it: no Mapbox types. */
data class NavRoute(val durationS: Double, val distanceM: Double, val via: String?)

/**
 * Where navigation is. NO_TOKEN: the phone has not handed over a map token. UNAVAILABLE: this unit
 * cannot run the map (old GPU, missing library, a previous map crash, the SDK failed); the car face
 * stays. Everything else is a trip's life.
 */
enum class NavPhase { NO_TOKEN, UNAVAILABLE, IDLE, REQUESTING, PREVIEW, GUIDING, ARRIVED, FAILED }

/** What the NAV face shows. [message] is always a sentence a person can read. */
data class NavState(
    val phase: NavPhase,
    val message: String,
    val destination: NavDestination? = null,
    val route: NavRoute? = null,
    val progress: NavProgress? = null,
    val rerouting: Boolean = false,
    /** The camera follows the car; false after the driver moved the map, until RECENTER. */
    val following: Boolean = true,
)

/** What a request did, read back from the SDK; [message] is safe to read aloud. */
data class NavResult(val ok: Boolean, val message: String)
