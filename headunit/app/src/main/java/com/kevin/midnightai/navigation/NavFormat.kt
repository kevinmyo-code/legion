package com.kevin.midnightai.navigation

import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Plain words for the NAV face. Imperial: the unit lives in a US car. */
object NavFormat {
    private const val METERS_PER_MILE = 1609.344
    private const val FEET_PER_METER = 3.28084

    fun duration(seconds: Double?): String {
        if (seconds == null || seconds.isNaN() || seconds < 0) return "time unknown"
        val minutes = (seconds / 60.0).roundToLong().coerceAtLeast(1)
        return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
    }

    fun distance(meters: Double?): String {
        if (meters == null || meters.isNaN() || meters < 0) return "distance unknown"
        val miles = meters / METERS_PER_MILE
        return when {
            miles >= 10 -> "${miles.roundToInt()} mi"
            miles >= 0.2 -> "${(miles * 10).roundToInt() / 10.0} mi"
            else -> "${((meters * FEET_PER_METER) / 50).roundToInt().coerceAtLeast(1) * 50} ft"
        }
    }

    /** "In 0.3 mi: Turn left onto Main St", or just the instruction when the distance is unknown. */
    fun turn(turn: NavTurn?): String? {
        turn ?: return null
        val d = turn.distanceM?.let { "In ${distance(it)}: " }.orEmpty()
        return d + turn.text
    }
}
