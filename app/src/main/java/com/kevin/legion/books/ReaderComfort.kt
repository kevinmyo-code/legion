package com.kevin.legion.books

import androidx.compose.ui.graphics.Color
import com.kevin.legion.navigation.SolarDay
import com.kevin.legion.navigation.SunTimes

/**
 * Reader comfort, pure (ticket books/06): the page colour matrix that carries the theme onto PDF
 * pages, the warm filter's alpha curve and its sunset-to-sunrise window, and which part of the
 * screen a tap lands in. All of it is arithmetic with no Android type, so every case is unit-tested.
 */
object PageColorMatrix {
    /** Night's page: white becomes this lightness, black becomes [NIGHT_INK]; close to the EPUB palette. */
    private const val NIGHT_PAPER = 20f
    private const val NIGHT_INK = 216f
    private const val CHANNEL_MAX = 255f

    /** Sepia multiplies a white page down to the EPUB sepia paper (`0xECE0C8`). */
    private const val SEPIA_R = 236f / CHANNEL_MAX
    private const val SEPIA_G = 224f / CHANNEL_MAX
    private const val SEPIA_B = 200f / CHANNEL_MAX

    /**
     * The 4x5 colour matrix (row-major, Android's layout, offsets on a 0..255 scale) for [theme], or
     * null when the page draws as rendered. Day is null. Comics are never passed here: art is not
     * inverted, so the caller asks only for books.
     */
    fun of(theme: ReaderTheme): FloatArray? = when (theme) {
        ReaderTheme.DAY -> null
        ReaderTheme.SEPIA -> floatArrayOf(
            SEPIA_R, 0f, 0f, 0f, 0f,
            0f, SEPIA_G, 0f, 0f, 0f,
            0f, 0f, SEPIA_B, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
        // Lightness inverts: channel' = INK - (INK - PAPER) * channel / 255, the same line on R, G and B.
        ReaderTheme.NIGHT -> {
            val k = -(NIGHT_INK - NIGHT_PAPER) / CHANNEL_MAX
            floatArrayOf(
                k, 0f, 0f, 0f, NIGHT_INK,
                0f, k, 0f, 0f, NIGHT_INK,
                0f, 0f, k, 0f, NIGHT_INK,
                0f, 0f, 0f, 1f, 0f,
            )
        }
    }

    /** True when [theme] changes how a page draws, so a comic reader can say it leaves art alone. */
    fun changesPage(theme: ReaderTheme): Boolean = of(theme) != null
}

/** The warm filter: an amber multiply overlay, a slider's worth of strength, and an Auto window. */
object WarmFilter {
    const val MAX_STRENGTH = 100
    const val DEFAULT_STRENGTH = 50

    /** The overlay at 100%; the ticket's "max ~35% alpha". */
    const val MAX_ALPHA = 0.35f

    /** The overlay tint, amber. */
    @Suppress("MagicNumber") // an ARGB colour literal, which is its own name
    val TINT = Color(0xFFFF9A2E)

    /** Strength 0..100 to overlay alpha 0..[MAX_ALPHA], linear, clamped. */
    fun alpha(strength: Int): Float = strength.coerceIn(0, MAX_STRENGTH) / MAX_STRENGTH.toFloat() * MAX_ALPHA

    /** Sun is down at [nowMs] on this fix: after sunset or before sunrise. Polar day is never night. */
    fun isNight(nowMs: Long, latitudeDeg: Double, longitudeDeg: Double): Boolean =
        when (val day = SunTimes.solarDay(nowMs, latitudeDeg, longitudeDeg)) {
            SolarDay.AlwaysUp -> false
            SolarDay.AlwaysDown -> true
            is SolarDay.Normal -> nowMs !in day.riseMs..day.setMs
        }

    /**
     * Whether the overlay is on right now. Auto with a fix follows the sun and ignores the on/off;
     * Auto with no fix follows the on/off as set.
     */
    fun isOn(settings: ReaderSettings, nowMs: Long, latitudeDeg: Double?, longitudeDeg: Double?): Boolean =
        if (settings.warmAuto && latitudeDeg != null && longitudeDeg != null) {
            isNight(nowMs, latitudeDeg, longitudeDeg)
        } else {
            settings.warmOn
        }

    /** The overlay alpha to draw: 0 when off. */
    fun effectiveAlpha(settings: ReaderSettings, nowMs: Long, latitudeDeg: Double?, longitudeDeg: Double?): Float =
        if (isOn(settings, nowMs, latitudeDeg, longitudeDeg)) alpha(settings.warmStrength) else 0f
}

/** Where a tap on the page landed; the middle third is the chrome, the outer quarters turn pages. */
enum class TapZone {
    PREVIOUS,
    CHROME,
    NEXT,
    NONE,
    ;

    companion object {
        private const val EDGE = 0.25f
        private const val THIRD_LOW = 1f / 3f
        private const val THIRD_HIGH = 2f / 3f

        fun of(x: Float, width: Float): TapZone {
            if (width <= 0f) return NONE
            val at = x / width
            return when {
                at < EDGE -> PREVIOUS
                at > 1f - EDGE -> NEXT
                at in THIRD_LOW..THIRD_HIGH -> CHROME
                else -> NONE
            }
        }
    }
}
