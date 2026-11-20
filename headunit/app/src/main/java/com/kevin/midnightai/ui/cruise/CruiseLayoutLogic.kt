package com.kevin.midnightai.ui.cruise

import com.kevin.midnightai.media.MusicDeckLogic
import com.kevin.midnightai.ui.GaugeReading

/**
 * The HU-1 Cruise layout in numbers (dp as Floats, so it is JVM-tested at 800x480 and 1024x600):
 * console rail on the left, the CAR / NAV panel in the middle, the music card on the right, all
 * sized from u = 1% of the frame width (capped by height), the unit the approved prototype was
 * drawn in at 800x480: 8 dp padding and gaps, a 124 dp rail, a 280 dp music card.
 */
object CruiseLayoutLogic {
    /** Height cap on u: a 1024x600 frame (58.6 u tall), same as the split deck before it. */
    const val HEIGHT_UNITS = 58.6f

    fun unit(widthDp: Float, heightDp: Float): Float = minOf(widthDp / 100f, heightDp / HEIGHT_UNITS)

    data class Columns(val pad: Float, val gap: Float, val rail: Float, val middle: Float, val music: Float) {
        /** One rail tile: two per row. */
        val tileWidth: Float get() = (rail - gap) / 2f
    }

    fun columns(widthDp: Float, heightDp: Float): Columns {
        val u = unit(widthDp, heightDp)
        val pad = u
        val gap = u
        // Two drive-size tiles side by side, never narrower.
        val rail = maxOf(u * 15.5f, 2 * MusicDeckLogic.TOUCH_MIN + gap)
        val music = u * 35f
        val middle = widthDp - 2 * pad - 2 * gap - rail - music
        return Columns(pad, gap, rail, middle, music)
    }

    /**
     * Height left for the music card's face (art + title, or the playlist tiles) once the header,
     * transport and volume rows are placed. [cardHeight] is the card's outer height.
     */
    fun musicFaceHeight(cardHeight: Float, u: Float): Float {
        val keys = MusicDeckLogic.heroKeys(contentWidth = u * 35f - 2 * u, u = u)
        return cardHeight - 2 * u - MusicDeckLogic.TOUCH_MIN - keys.height - keys.volumeHeight - 3 * u
    }

    /** Three rows of drive-size playlist tiles: the least the PLAYLISTS face must show without scrolling. */
    fun playlistFaceMin(u: Float): Float = 3 * MusicDeckLogic.TOUCH_MIN + 2 * u
}

/** What the middle panel's words say. Plain values in, strings out. */
internal object MiddlePanelText {

    /** The GPS readout under the CAR / NAV switch, and whether it should read as a warning. */
    data class Gps(val source: String, val age: String, val stale: Boolean)

    /** Older than this and the readout says the fix is lost (plan: "lost Ns" after 3 s). */
    const val STALE_MS = 3_000L

    /**
     * [provider] is the fix's provider ([com.kevin.midnightai.location.LocationController.BEACON_PROVIDER]
     * for the phone beacon), [ageMs] how old it is on the monotonic clock; null = no fix at all.
     */
    fun gps(provider: String?, ageMs: Long?, beaconProvider: String): Gps {
        if (provider == null || ageMs == null) return Gps("No GPS", "no fix yet", stale = true)
        val source = if (provider == beaconProvider) "GPS from phone" else "GPS on this unit"
        val age = ageMs.coerceAtLeast(0L)
        val words = when {
            age <= STALE_MS -> "fix ${age / 1000} s ago"
            age < 60_000L -> "lost ${age / 1000} s"
            age < 3_600_000L -> "lost ${age / 60_000} min"
            else -> "lost over an hour"
        }
        return Gps(source, words, stale = age > STALE_MS)
    }

    /**
     * The CAR face's gauge cards: the driver's Cruise picks (Settings gauge picker), in the order the
     * poll produced them, at most [max]. No new PIDs: RPM and other fast ones stay out because
     * polling them stutters Bluetooth music (headunit/CLAUDE.md §12).
     */
    fun carGauges(all: List<GaugeReading>, cruiseKeys: Set<String>, max: Int = 4): List<GaugeReading> =
        all.filter { it.key in cruiseKeys }.take(max)

    /** "1 stored code · P1282 Fuel pump relay", or null with none. [names] maps a code to its short name. */
    fun storedCodes(codes: List<String>, names: Map<String, String>): String? {
        if (codes.isEmpty()) return null
        val first = codes.first()
        val count = if (codes.size == 1) "1 stored code" else "${codes.size} stored codes"
        val name = names[first]?.takeIf { it.isNotBlank() }
        return "$count · $first" + (name?.let { " $it" } ?: "")
    }
}
