package com.kevin.legion.navigation

/** A point on the map. **Latitude first here**; the Mapbox seam swaps to lng,lat (Midnight AI's lesson). */
data class GeoPoint(val latitude: Double, val longitude: Double)

/**
 * What the user can ask a route to avoid. [excludeValue] is Directions' own `exclude` token
 * (research 01 section 3); [spoken] is the word the assistant and the tile use.
 */
enum class AvoidKind(val excludeValue: String, val spoken: String) {
    TOLLS("toll", "tolls"),
    HIGHWAYS("motorway", "highways"),
    FERRIES("ferry", "ferries"),
    ;

    companion object {
        /** The kinds named in a Directions `exclude` string. Unknown tokens (unpaved, point(...)) are ignored. */
        fun fromExclude(exclude: String?): Set<AvoidKind> {
            val tokens = exclude.orEmpty().split(',').map { it.trim() }
            return entries.filter { it.excludeValue in tokens }.toSet()
        }
    }
}

/**
 * Where the map camera should be. [FREE] is only ever set by the map itself, when the user pans
 * away from the guided camera; nothing in the controller sets it and the map does not act on it.
 */
enum class NavCameraMode { FOLLOWING, OVERVIEW, FREE }

/**
 * One route as the controller reasons about it: no Mapbox types, so the controller is unit-tested
 * against a fake [NavSdk]. Unknown is null, never zero (a route with no typical duration has none).
 */
data class NavRouteInfo(
    /** The SDK's own id, stable across a reorder; how the controller checks which route is primary. */
    val id: String,
    val durationS: Double,
    val distanceM: Double,
    /** Duration with no live traffic, when the SDK reports one; the "usual" in "N min slower than usual". */
    val typicalDurationS: Double?,
    /** The roads it uses ("I-69 S, Westheimer Rd"), or null when the SDK gave no summary. */
    val via: String?,
    /** Whether it uses a toll road. Null is unknown, not "no". */
    val hasTolls: Boolean?,
    /** The coordinates the route was requested through AFTER the origin: stops, then the destination. */
    val waypoints: List<GeoPoint>,
    /** The avoid kinds the route was requested with, read back from the route itself. */
    val excluded: Set<AvoidKind>,
    /** One sentence about traffic on this route, or null when the SDK reported nothing to say. */
    val trafficSummary: String?,
)

/** The next maneuver. [type] and [modifier] are Directions' own words ("turn" / "left"), for the icon. */
data class NavTurn(
    val text: String,
    val distanceM: Double?,
    val type: String?,
    val modifier: String?,
)

enum class SpeedUnit { MPH, KPH }

data class SpeedLimit(val value: Int, val unit: SpeedUnit)

/**
 * Live guidance numbers. **Every field except the two route totals may be null, and null means the
 * SDK does not have it (a road with no posted limit, no banner yet) - never zero.**
 */
data class GuidanceSnapshot(
    val durationLeftS: Double?,
    val distanceLeftM: Double?,
    /** Wall-clock arrival, computed from the clock at the last update plus [durationLeftS]. */
    val arrivalAtMs: Long?,
    val turn: NavTurn?,
    /** The step after [turn], for the "Then:" strip. */
    val then: String?,
    val road: String?,
    val speedLimit: SpeedLimit?,
    val traffic: String?,
)

/** Where a trip is. See [NavTripGuard] for the transitions and the billing invariant. */
enum class NavPhase { NOT_SET_UP, TOKEN_REFUSED, IDLE, REQUESTING, PREVIEW, GUIDING, ARRIVED, ENDED, FAILED }

/**
 * What the controller reports. [message] is always a sentence a person can read, never blank. The
 * screen is a pure function of this plus its own input state; so is every voice result.
 */
data class NavState(
    val phase: NavPhase,
    val message: String,
    val destination: NavDestination? = null,
    /** Stops between here and [destination], in order. Passed stops drop off as the trip goes. */
    val stops: List<NavDestination> = emptyList(),
    /** Routes in play, primary first while guiding; in preview the requested order with [selectedRoute]. */
    val routes: List<NavRouteInfo> = emptyList(),
    val selectedRoute: Int = 0,
    val avoid: Set<AvoidKind> = emptySet(),
    /** Turn cues muted: `NavCueSpeaker` speaks no cue while true; the assistant is untouched. Survives a trip. */
    val muted: Boolean = false,
    val camera: NavCameraMode = NavCameraMode.FOLLOWING,
    val rerouting: Boolean = false,
    /** A warning about the trip that is not its state (a reroute that found nothing); null when there is none. */
    val notice: String? = null,
    /** What the last change did to the route that the user should read (e.g. a picked alternative was replaced). */
    val note: String? = null,
    val guidance: GuidanceSnapshot? = null,
)

/**
 * What a mutating controller call did. [ok] means the state the caller asked for now holds in the
 * SDK, read back AFTER the call (CLAUDE.md sec 7, ticket 04's honesty table); [message] says what
 * happened or did not, and is safe to read aloud. A voice wrapper may say an outcome verb only when
 * [ok] is true.
 */
data class NavResult(val ok: Boolean, val message: String)

/**
 * The read-only answer behind `trip_status`. **[NotNavigating] is its own value, never a
 * [Navigating] full of zeros** (ticket 04).
 */
sealed interface TripStatus {
    data class NotNavigating(val message: String) : TripStatus

    data class Navigating(
        val destination: NavDestination,
        val stops: List<NavDestination>,
        val guidance: GuidanceSnapshot,
    ) : TripStatus
}
