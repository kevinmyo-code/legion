package com.kevin.legion.service.cascade

import com.kevin.legion.service.turn.AudioRoute
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * The closing chime's rules (Kevin, 2026-11-20: hear that the conversation has stopped). The sound is
 * [com.kevin.legion.service.ListenCue.closeChimePcm]; the session plays it once in its release, through
 * Alfred's own track, after the mic has closed and before the audio route is let go. Pure.
 *
 * It plays on every close of a conversation he was in ([PLAYS], and a failed voice's close). Not:
 * - a close that is not the end of his conversation: the controller tearing down ("destroyed"), Live
 *   or another session taking over ("preempted"), the crisis teardown, another app holding the mic,
 *   a ring window ending, a speak-only line or check-in statement he never answered, a check-in
 *   dropped before it played, or a close the controller makes only to open the next thing at once
 *   (a tap over a proactive line, Brief me, Look, a test giving way);
 * - Quiet mode; a call on the line ([Backchannel.callOnLine]); a session whose audio never opened;
 *   a voice whose track is not 24 kHz (the chime is made at that rate); a second time for one close.
 */
internal object CloseCue {
    private val PLAYS = setOf(
        "stopped", "sleep_phrase", "follow_up", "dismissed", "noise", "missed_twice", "check_in_no_reply",
        "new conversation",
    )
    private const val VOICE_FAILED = "voice_failed_"

    /** Waiting for the chime to drain never holds the close longer than this. */
    const val HARD_CAP_MS = 1_200L

    enum class Skip(val token: String) {
        PLAYED("played"), NOT_A_CLOSE("reason"), NO_AUDIO("no_audio"), RATE("rate"), ON_CALL("on_call"),
        QUIET("quiet"),
    }

    @Suppress("ReturnCount", "LongParameterList") // one exit per rule, in order
    fun decide(
        reason: String,
        quiet: Boolean,
        onCall: Boolean,
        audioOpened: Boolean,
        alreadyPlayed: Boolean,
        rateMatches: Boolean,
    ): Skip? {
        if (alreadyPlayed) return Skip.PLAYED
        if (reason !in PLAYS && !reason.startsWith(VOICE_FAILED)) return Skip.NOT_A_CLOSE
        if (!audioOpened) return Skip.NO_AUDIO
        if (!rateMatches) return Skip.RATE
        if (onCall) return Skip.ON_CALL
        if (quiet) return Skip.QUIET
        return null
    }

    /**
     * How far the sound heard lags the track's head position, and an idle track's restart (2026-11-21,
     * the chime not heard after "bye bye"): the head position says a frame was handed on, not that it
     * was heard. The wait was the chime plus the route's settle only (350 ms on the speaker for a
     * 290 ms chime), so the release's pause and flush could cut the chime before it was heard.
     */
    const val OUTPUT_LATENCY_MS = 250L

    /** The chime, its output latency and the route's tail (Bluetooth buffers more), never past [HARD_CAP_MS]. */
    fun waitCapMs(chimeMs: Long, settleMs: Long): Long = minOf(chimeMs + OUTPUT_LATENCY_MS + settleMs, HARD_CAP_MS)

    /** The least time from the chime's write to the player's release: its length plus [OUTPUT_LATENCY_MS]. */
    fun minPlayMs(chimeMs: Long): Long = chimeMs + OUTPUT_LATENCY_MS

    /**
     * Writes the chime, waits for it to drain and for at least [minMs] from the write's start (a head
     * position that runs ahead never releases it early), cut at [capMs] however long the drain takes.
     * True: done in time.
     */
    suspend fun playBounded(
        capMs: Long,
        minMs: Long,
        write: suspend () -> Unit,
        drained: suspend () -> Unit,
        now: () -> Long = System::currentTimeMillis,
    ): Boolean = withTimeoutOrNull(capMs) {
        val start = now()
        write()
        drained()
        val left = minMs - (now() - start)
        if (left > 0) delay(left)
        true
    } ?: false

    /** The route the chime went out on, for its audit line. */
    fun routeToken(route: AudioRoute, earpiece: Boolean): String = when {
        route == AudioRoute.BLUETOOTH -> "bt"
        earpiece -> "earpiece"
        route == AudioRoute.WIRED_HEADSET -> "wired"
        else -> "speaker"
    }

    /** `close_cue played ms=<n> reason=<close> route=<speaker|earpiece|bt|wired> drained=<1|0>`: no text. */
    fun playedLine(reason: String, ms: Long, route: String, drained: Boolean): String =
        "close_cue played ms=$ms reason=${token(reason)} route=$route drained=${if (drained) 1 else 0}"

    /** `close_cue skipped reason=<why> close=<close>`: no text. */
    fun skippedLine(reason: String, skip: Skip): String =
        "close_cue skipped reason=${skip.token} close=${token(reason)}"

    /**
     * The reason a voice failure's release is given (review of 546cce67, F2): with Live retired the
     * conversation ends here, so it is a close the chime is for; a background line nobody asked for
     * ([background]) and a hand-over to Live are not.
     */
    fun voiceFailedReason(reason: String, liveRetired: Boolean, background: Boolean): String =
        if (liveRetired && !background) "$VOICE_FAILED$reason" else reason

    /**
     * `close_order mic_released_ms=<n> chime_end_ms=<n|none> route_released_ms=<n>`, each from the
     * release's start (review of 546cce67, F3): whether the wake word re-armed before the route went.
     */
    fun orderLine(micReleasedMs: Long, chimeEndMs: Long?, routeReleasedMs: Long): String =
        "close_order mic_released_ms=$micReleasedMs chime_end_ms=${chimeEndMs ?: "none"} " +
            "route_released_ms=$routeReleasedMs"

    /** `close_cue played=<0|1> reason=<token> skipped=<why>`: fixed tokens only. */
    private fun token(reason: String) = reason.lowercase(Locale.US).replace(Regex("[^a-z0-9_]"), "_")
}
