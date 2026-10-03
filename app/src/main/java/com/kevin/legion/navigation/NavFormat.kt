package com.kevin.legion.navigation

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Why a route request came back empty-handed, as far as the SDK's wording lets us tell. */
enum class RouteFailure { AUTH, OFFLINE, NO_ROUTE, OTHER }

/** Pure text for navigation state, so every sentence the user hears or reads is unit-testable. */
@Suppress("TooManyFunctions") // one object of small pure sentence builders; splitting it would scatter one vocabulary
object NavFormat {
    const val NOT_SET_UP = "Navigation isn't set up. Add a Mapbox token in Setup."

    const val TOKEN_REFUSED = "Mapbox refused the token. Check it in Setup."

    const val NOTHING_NAVIGATING = "Nothing is navigating."

    /**
     * The state the token alone forces, or null when the token is fine and the trip decides.
     * No token wins over a stale rejection: with nothing to refuse, "not set up" is the true answer.
     */
    fun stateForToken(token: MapboxTokenState): NavState? = when {
        !token.isSet -> NavState(NavPhase.NOT_SET_UP, NOT_SET_UP)
        token.rejected -> NavState(NavPhase.TOKEN_REFUSED, TOKEN_REFUSED)
        else -> null
    }

    /**
     * True when text from an SDK failure (a route request's message, a map load error's message)
     * reads as an authorization failure: Mapbox answers a bad token with HTTP 401 and wording like
     * "Not Authorized - Invalid Token". **Matched on wording because the SDK surfaces no typed auth
     * code** (RouterFailure carries only `type`/`message`; MapLoadingError only a coarse type), so
     * this is a heuristic and a miss degrades to the plain failure sentence, never to a false trip.
     */
    fun isAuthFailure(message: String?): Boolean {
        val m = message?.lowercase() ?: return false
        return AUTH_MARKERS.any { it in m }
    }

    private val AUTH_MARKERS =
        listOf("401", "unauthorized", "not authorized", "invalid token", "access token", "tokeninvalid")

    private val OFFLINE_MARKERS = listOf(
        "unknownhost", "unable to resolve", "no address associated", "network", "timeout", "timed out",
        "failed to connect", "connection", "offline", "unreachable", "ssl", "socket",
    )

    private val NO_ROUTE_MARKERS = listOf("noroute", "no route", "no_route", "nosegment", "profilenotfound")

    /**
     * Buckets a route failure. **Wording-based, the same heuristic as [isAuthFailure]**: the SDK's
     * `RouterFailure` carries a free-text type and message and the throwable, not a typed reason, so
     * a miss falls to [RouteFailure.OTHER] and the plain sentence carries the SDK's own words.
     */
    fun classifyFailure(type: String?, message: String?, throwableName: String?): RouteFailure {
        val all = listOfNotNull(type, message, throwableName).joinToString(" ").lowercase()
        return when {
            isAuthFailure(all) -> RouteFailure.AUTH
            NO_ROUTE_MARKERS.any { it in all } -> RouteFailure.NO_ROUTE
            OFFLINE_MARKERS.any { it in all } -> RouteFailure.OFFLINE
            else -> RouteFailure.OTHER
        }
    }

    /** The sentence for a failed route request, saying in words what did not happen. */
    fun failureSentence(kind: RouteFailure, message: String?, destination: String?): String {
        val to = destination?.let { " to $it" }.orEmpty()
        return when (kind) {
            RouteFailure.AUTH -> TOKEN_REFUSED
            RouteFailure.OFFLINE -> "No connection, so no route$to was found."
            RouteFailure.NO_ROUTE -> "Mapbox found no drivable route$to."
            RouteFailure.OTHER -> "No route$to was found: ${message?.takeIf { it.isNotBlank() } ?: "unknown reason"}."
        }
    }

    /** A blank or whitespace-only token is "not set up", never a token to try. */
    fun hasToken(token: String?): Boolean = !token.isNullOrBlank()

    fun distance(meters: Double): String = when {
        meters < 0 -> "unknown distance"
        meters < SHORT_HOP_M -> "${(meters / ROUND_TO_M).roundToInt() * ROUND_TO_M.toInt()} m"
        else -> {
            val miles = meters / METERS_PER_MILE
            if (miles < DECIMAL_MILES_BELOW) {
                "%.1f mi".format(Locale.US, miles)
            } else {
                "${miles.roundToInt()} mi"
            }
        }
    }

    fun duration(seconds: Double): String {
        if (seconds < 0) return "unknown time"
        val minutes = (seconds / SECONDS_PER_MINUTE).roundToInt()
        return when {
            minutes < 1 -> "under 1 min"
            minutes < MINUTES_PER_HOUR -> "$minutes min"
            else -> "${minutes / MINUTES_PER_HOUR} h ${minutes % MINUTES_PER_HOUR} min"
        }
    }

    /** "6:42 PM". [zone] is a parameter so a test can pin it; the screen passes the device zone. */
    fun arrivalClock(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMs).atZone(zone).format(CLOCK)

    private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

    fun speedLimit(limit: SpeedLimit?): String? = limit?.let {
        "${it.value} ${if (it.unit == SpeedUnit.MPH) "mph" else "km/h"}"
    }

    /**
     * Names for a preview's routes: the first is "Fastest"; a later one that avoids tolls the first
     * does not is "No tolls"; the shortest remaining is "Shortest"; anything else "Alternative".
     * Unknown toll status (null) never earns "No tolls": an unknown is not a claim.
     */
    fun routeLabels(routes: List<NavRouteInfo>): List<String> {
        if (routes.isEmpty()) return emptyList()
        val primary = routes.first()
        val shortest = routes.drop(1).minByOrNull { it.distanceM }
        var alternativeCount = 0
        return routes.mapIndexed { i, r ->
            when {
                i == 0 -> "Fastest"
                primary.hasTolls == true && r.hasTolls == false -> "No tolls"
                r === shortest && r.distanceM < primary.distanceM -> "Shortest"
                else -> {
                    alternativeCount++
                    if (alternativeCount > 1) "Alternative $alternativeCount" else "Alternative"
                }
            }
        }
    }

    /** The grey line under a route's name: its roads, and tolls only when the SDK says it has them. */
    fun routeDetail(route: NavRouteInfo): String {
        val via = route.via?.takeIf { it.isNotBlank() }?.let { "via $it" } ?: "roads unknown"
        return if (route.hasTolls == true) "$via · has tolls" else via
    }

    /**
     * Clockwise degrees to turn the neutral up-arrow for a maneuver's [modifier]. Roundabouts and
     * unrecognised maneuvers draw straight: an arrow that points somewhere wrong is worse than a
     * plain one beside the street name, which is always shown.
     */
    fun turnRotation(modifier: String?): Float = when (modifier?.lowercase()) {
        "uturn" -> U_TURN_DEG
        "sharp right" -> SHARP_DEG
        "right" -> RIGHT_DEG
        "slight right" -> SLIGHT_DEG
        "slight left" -> -SLIGHT_DEG
        "left" -> -RIGHT_DEG
        "sharp left" -> -SHARP_DEG
        else -> 0f
    }

    private const val METERS_PER_MILE = 1609.344

    /** Under this, say metres rounded to [ROUND_TO_M]; a quarter-mile figure at 80 m is silly. */
    private const val SHORT_HOP_M = 160.0
    private const val ROUND_TO_M = 10.0
    private const val DECIMAL_MILES_BELOW = 10
    private const val SECONDS_PER_MINUTE = 60
    private const val MINUTES_PER_HOUR = 60
    private const val U_TURN_DEG = 180f
    private const val SHARP_DEG = 135f
    private const val RIGHT_DEG = 90f
    private const val SLIGHT_DEG = 45f
}

/**
 * One sentence about traffic on a route, from the numbers the Directions response carries (leg
 * annotations and `duration_typical`). **Null when the response carried nothing to base a sentence
 * on: unknown is never "no traffic".** Pure so the thresholds are tested rather than assumed.
 *
 * `congestion_numeric` is 0..100; Mapbox's own classes put heavy at 60 and severe at 80.
 */
object TrafficSummary {
    private const val HEAVY = 60
    private const val SEVERE = 80
    private const val SECONDS_PER_MINUTE = 60.0
    private const val NOTICEABLE_MIN = 3

    fun of(
        durationS: Double,
        typicalS: Double?,
        congestionNumeric: List<Int?>?,
        segmentDistancesM: List<Double>?,
    ): String? {
        val parts = mutableListOf<String>()
        if (typicalS != null) {
            val deltaMin = ((durationS - typicalS) / SECONDS_PER_MINUTE).roundToInt()
            parts += when {
                deltaMin >= NOTICEABLE_MIN -> "About $deltaMin min slower than usual"
                deltaMin <= -NOTICEABLE_MIN -> "About ${abs(deltaMin)} min faster than usual"
                else -> "About as long as usual"
            }
        }
        val known = congestionNumeric?.filterNotNull().orEmpty()
        if (known.isNotEmpty()) parts += congestionSentence(congestionNumeric.orEmpty(), segmentDistancesM)
        return if (parts.isEmpty()) null else parts.joinToString(". ") + "."
    }

    private fun congestionSentence(congestion: List<Int?>, distances: List<Double>?): String {
        var heavyM = 0.0
        var severeM = 0.0
        var heavyCount = 0
        congestion.forEachIndexed { i, level ->
            if (level != null && level >= HEAVY) {
                heavyCount++
                val d = distances?.getOrNull(i) ?: 0.0
                heavyM += d
                if (level >= SEVERE) severeM += d
            }
        }
        return when {
            heavyCount == 0 -> "No heavy traffic reported on the route"
            distances == null || heavyM <= 0.0 -> "Heavy traffic reported on part of the route"
            severeM > 0.0 -> "Heavy traffic for about ${NavFormat.distance(heavyM)}, severe in places"
            else -> "Heavy traffic for about ${NavFormat.distance(heavyM)}"
        }
    }
}
