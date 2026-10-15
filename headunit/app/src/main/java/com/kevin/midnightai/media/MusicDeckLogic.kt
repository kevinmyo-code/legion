package com.kevin.midnightai.media

/**
 * One cell of Cruise's pinned-playlist grid: a playlist, or an empty slot that opens the pin
 * picker. The grid always has exactly [SpotifyPinLogic.MAX_PINS] cells, so the layout never
 * reflows as pins come and go.
 */
sealed class PinSlot {
    data class Pinned(val playlist: SpotifyPlaylist) : PinSlot()
    data object Empty : PinSlot()
}

/**
 * Pure rules behind Cruise's split music deck (now playing card + pinned grid). No Android
 * types, so they run in plain JVM unit tests.
 */
object MusicDeckLogic {

    /** [shown] in order, padded with [PinSlot.Empty] (or cut) to exactly [size] cells. */
    fun slots(shown: List<SpotifyPlaylist>, size: Int = SpotifyPinLogic.MAX_PINS): List<PinSlot> =
        List(size) { i -> shown.getOrNull(i)?.let { PinSlot.Pinned(it) } ?: PinSlot.Empty }

    /** The word after "NOW PLAYING" on the card's label row. */
    fun sourceLabel(source: Source): String = when (source) {
        Source.SPOTIFY -> "SPOTIFY"
        Source.PHONE -> "PHONE"
        Source.MIXTAPE -> "MIXTAPE"
    }

    /**
     * Shuffle and like only exist on head-unit Spotify (App Remote); phone Bluetooth and the
     * mixtape player have no such calls, so their keys render dimmed and do nothing.
     */
    fun spotifyExtrasEnabled(source: Source, spotifyConnected: Boolean): Boolean =
        source == Source.SPOTIFY && spotifyConnected

    /** What the PHONE | SPOTIFY switch in the NOW PLAYING label row shows. */
    enum class SwitchMode {
        /** Nothing to switch between (a mixtape is loaded, or Spotify is not set up): plain "NOW PLAYING ・ SOURCE". */
        HIDDEN,

        /** App Remote is up: SPOTIFY switches straight over. */
        LIVE,

        /**
         * Head-unit Spotify is set up (client ID + Web grant, app installed) but App Remote is
         * down right now, e.g. after a drop. The switch stays so a hands path exists
         * (ADR 0035 spirit); its SPOTIFY side says RECONNECT instead of pretending to be live.
         */
        RECONNECT,
    }

    /**
     * 2026-10-15 car report: the switch vanished the moment App Remote dropped, leaving no way by
     * hand back to head-unit Spotify. A mixtape still hides it (a loaded tape owns the deck).
     */
    fun switchMode(spotifyConnected: Boolean, spotifySetUp: Boolean, source: Source): SwitchMode = when {
        source == Source.MIXTAPE -> SwitchMode.HIDDEN
        spotifyConnected -> SwitchMode.LIVE
        spotifySetUp -> SwitchMode.RECONNECT
        else -> SwitchMode.HIDDEN
    }

    /** The word on the SPOTIFY side of the switch: says what a tap will do, never "SPOTIFY" while it is down. */
    fun spotifySwitchLabel(mode: SwitchMode, connecting: Boolean): String = when {
        mode != SwitchMode.RECONNECT -> "SPOTIFY"
        connecting -> "CONNECTING"
        else -> "RECONNECT"
    }

    /**
     * The toast when a RECONNECT tap ends without a live link. [timedOut]: the wait ran out with the
     * attempt unfinished, so [reason] (the previous failure) is stale and is not spoken as the cause.
     */
    fun reconnectFailureMessage(reason: String, timedOut: Boolean): String =
        if (timedOut) {
            "Spotify on the head unit is still connecting. Try again in a few seconds."
        } else {
            "Spotify did not connect: $reason. Still on the current source."
        }

    /** How long a RECONNECT tap waits for App Remote before saying it is still connecting. */
    const val RECONNECT_TAP_BUDGET_MS = 10_000L

    /** A progress bar needs a known length; Bluetooth AVRCP often sends none. */
    fun showProgress(durationMs: Long): Boolean = durationMs > 0

    /**
     * Where playback is now, run forward from the session's last sample. MediaSession only
     * pushes a position on state changes, so without this the bar would sit still between
     * tracks. Only advances while [playing]; clamped to 0..[durationMs] when the length is known.
     */
    fun livePosition(
        positionMs: Long,
        sampledAtMs: Long,
        nowMs: Long,
        speed: Float,
        playing: Boolean,
        durationMs: Long,
    ): Long {
        val run = if (playing && sampledAtMs > 0 && nowMs > sampledAtMs) {
            ((nowMs - sampledAtMs) * speed.coerceAtLeast(0f)).toLong()
        } else {
            0L
        }
        val p = (positionMs + run).coerceAtLeast(0L)
        return if (durationMs > 0) p.coerceAtMost(durationMs) else p
    }

    /** 0..1 fill for the bar; 0 when the length is unknown. */
    fun fraction(positionMs: Long, durationMs: Long): Float =
        if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)

    /** "m:ss", or "h:mm:ss" from an hour up (podcasts, long mixes). Negative reads as 0:00. */
    fun formatClock(ms: Long): String {
        val total = (ms.coerceAtLeast(0L)) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** Time left as "-m:ss" (the right-hand readout under the bar). */
    fun formatRemaining(positionMs: Long, durationMs: Long): String =
        "-" + formatClock((durationMs - positionMs).coerceAtLeast(0L))

    /**
     * The line under the title: "artist ・ where it's from". [contextName] is the pinned playlist
     * the driver started, else the album; blanks drop out so there is never a dangling dot.
     */
    fun subtitle(artist: String?, contextName: String?): String =
        listOfNotNull(artist?.trim()?.ifBlank { null }, contextName?.trim()?.ifBlank { null })
            .joinToString(" ・ ")

    /**
     * Which grid tile wears the "playing" ring: the one whose URI is the context App Remote says
     * Spotify is playing from (so a playlist started by voice, from the Spotify app, or by
     * autoplay is marked too), and only while Spotify owns the speakers. Null when nothing
     * matches - an album or radio context marks no tile.
     */
    fun playingPlaylistId(source: Source, contextUri: String?, shown: List<SpotifyPlaylist>): String? {
        if (source != Source.SPOTIFY || contextUri.isNullOrBlank()) return null
        return shown.firstOrNull { samePlaylist(contextUri, it.uri) }?.id
    }

    /**
     * True when two Spotify URIs name the same playlist. Compares the id after the last
     * ":playlist:", so the legacy `spotify:user:<owner>:playlist:<id>` form a context can report
     * still matches the `spotify:playlist:<id>` the library returns.
     */
    fun samePlaylist(a: String, b: String): Boolean {
        if (a == b) return true
        val marker = ":playlist:"
        val ia = a.lastIndexOf(marker)
        val ib = b.lastIndexOf(marker)
        if (ia < 0 || ib < 0) return false
        val idA = a.substring(ia + marker.length)
        return idA.isNotEmpty() && idA == b.substring(ib + marker.length)
    }

    // --- Transport layout: fits by construction ---------------------------------------------
    // All in dp as Floats so the arithmetic is JVM-testable. A key's HIT area is never below
    // [TOUCH_MIN] (AriaDimens.driveTouchMin); the play key's visual is never below [BIG_MIN].

    const val TOUCH_MIN = 56f
    const val BIG_MIN = 64f
    const val KEY_GAP = 4f
    /** The volume meter's drawn width (10 bars of 4dp + 9 gaps of 2dp). */
    const val METER_WIDTH = 58f

    /**
     * How the NOW PLAYING keys are arranged in a card [width] dp wide:
     *  - ONE_ROW: shuffle, prev, play, next, like on one line; volume on its own line below.
     *  - TWO_ROWS: prev, play, next; then shuffle, volume -, (meter), volume +, like.
     *  - THREE_ROWS: prev, play, next; then shuffle ... like; then volume -, (meter), +.
     * Each mode is chosen only when every key in each of its rows fits at full hit size.
     */
    enum class TransportMode { ONE_ROW, TWO_ROWS, THREE_ROWS }

    fun transportMode(width: Float): TransportMode = when {
        width >= 4 * TOUCH_MIN + BIG_MIN + 4 * KEY_GAP -> TransportMode.ONE_ROW
        width >= 4 * TOUCH_MIN + 3 * KEY_GAP -> TransportMode.TWO_ROWS
        else -> TransportMode.THREE_ROWS
    }

    /** Whether the volume meter fits between [keys] full-size keys on a row [width] wide. */
    fun meterFits(width: Float, keys: Int): Boolean = width >= keys * TOUCH_MIN + METER_WIDTH + keys * KEY_GAP

    data class KeySizes(val mid: Float, val big: Float)

    /**
     * Sizes for previous/next ([KeySizes.mid]) and play ([KeySizes.big]) sharing [avail] dp:
     * the prototype's proportions ([idealMid], [idealBig]) scaled down to fit, but mid never
     * below [TOUCH_MIN] and big never below [BIG_MIN]. 2 * mid + big <= avail whenever
     * avail >= 2 * TOUCH_MIN + BIG_MIN.
     */
    fun keySizes(avail: Float, idealMid: Float, idealBig: Float): KeySizes {
        val ideal = 2 * idealMid + idealBig
        val s = if (ideal <= 0f) 1f else minOf(1f, avail / ideal)
        val mid = maxOf(TOUCH_MIN, idealMid * s)
        val big = maxOf(BIG_MIN, minOf(idealBig, avail - 2 * mid))
        return KeySizes(mid, big)
    }

    /** The tile's count line: "1 TRACK", "42 TRACKS". */
    fun trackCountLabel(n: Int): String = if (n == 1) "1 TRACK" else "$n TRACKS"

    /** What the idle NOW PLAYING card suggests, by who owns the speakers. */
    fun idleCaption(source: Source): String = when (source) {
        Source.PHONE -> "Play from your phone ・ フォンから再生"
        Source.SPOTIFY -> "Pick a playlist or press play"
        Source.MIXTAPE -> "Tape loaded ・ press play"
    }
}
