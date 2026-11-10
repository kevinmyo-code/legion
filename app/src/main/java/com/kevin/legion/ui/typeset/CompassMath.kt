package com.kevin.legion.ui.typeset

import java.util.Locale
import kotlin.math.roundToInt

/** The compass's arithmetic, pure. Pinned by `CompassMathTest`. */
object CompassMath {
    private const val FULL_TURN = 360f
    private const val MAJOR_EVERY = 30
    private const val CARDINAL_EVERY = 90

    /** Any angle to 0 until 360. */
    fun normalize(deg: Float): Float = ((deg % FULL_TURN) + FULL_TURN) % FULL_TURN

    /** Where the waypoint lies relative to where the phone points: 0 is dead ahead, 90 to the right. */
    fun relative(bearing: Float, heading: Float): Float = normalize(bearing - heading)

    /** "042°": three figures, as a bearing is written on a map. */
    fun degrees(deg: Float): String =
        String.format(Locale.US, "%03d°", normalize(deg).roundToInt() % FULL_TURN.toInt())

    /** The letter at a cardinal point, or null between them. */
    @Suppress("MagicNumber") // the four cardinal points, in degrees
    fun cardinal(deg: Int): String? = when (deg) {
        0 -> "N"
        90 -> "E"
        180 -> "S"
        270 -> "W"
        else -> null
    }

    /** Tick weight: 2 at a cardinal, 1 every 30 degrees, 0 for the 10 degree marks. */
    fun tickWeight(deg: Int): Int = when {
        deg % CARDINAL_EVERY == 0 -> 2
        deg % MAJOR_EVERY == 0 -> 1
        else -> 0
    }
}
