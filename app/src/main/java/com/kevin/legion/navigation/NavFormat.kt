package com.kevin.legion.navigation

import kotlin.math.roundToInt

/** Route progress as the spike screen renders it. Raw SI units in, words out via [NavFormat]. */
data class NavProgress(
    val distanceRemainingM: Double,
    val durationRemainingS: Double,
    /** The banner's primary text, e.g. "Turn left onto Main Street". Null before the first banner. */
    val nextManeuver: String?,
)

/** What the controller reports. [message] is always a sentence a person can read, never blank. */
data class NavState(
    val phase: NavPhase,
    val message: String,
    val progress: NavProgress? = null,
)

/** Pure text for navigation state, so the no-token and formatting paths are unit-testable. */
object NavFormat {
    const val NOT_SET_UP =
        "Navigation isn't set up. Add a Mapbox token (Setup, once it lands; for now the build's MAPBOX_ACCESS_TOKEN)."

    /** A blank or whitespace-only token is "not set up", never a token to try. */
    fun hasToken(token: String?): Boolean = !token.isNullOrBlank()

    fun distance(meters: Double): String = when {
        meters < 0 -> "unknown distance"
        meters < SHORT_HOP_M -> "${(meters / ROUND_TO_M).roundToInt() * ROUND_TO_M.toInt()} m"
        else -> {
            val miles = meters / METERS_PER_MILE
            if (miles < DECIMAL_MILES_BELOW) {
                "%.1f mi".format(java.util.Locale.US, miles)
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

    fun progressLine(p: NavProgress): String =
        "${distance(p.distanceRemainingM)} left, ${duration(p.durationRemainingS)}"

    private const val METERS_PER_MILE = 1609.344

    /** Under this, say metres rounded to [ROUND_TO_M]; a quarter-mile figure at 80 m is silly. */
    private const val SHORT_HOP_M = 160.0
    private const val ROUND_TO_M = 10.0
    private const val DECIMAL_MILES_BELOW = 10
    private const val SECONDS_PER_MINUTE = 60
    private const val MINUTES_PER_HOUR = 60
}
