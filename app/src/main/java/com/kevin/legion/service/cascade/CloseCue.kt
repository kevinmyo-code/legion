package com.kevin.legion.service.cascade

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

    /** The chime plus the route's tail (Bluetooth buffers past the head position), never past [HARD_CAP_MS]. */
    fun waitCapMs(chimeMs: Long, settleMs: Long): Long = minOf(chimeMs + settleMs, HARD_CAP_MS)

    /** Writes the chime and waits for it to drain, cut at [capMs] however long the drain takes. True: done in time. */
    suspend fun playBounded(capMs: Long, write: suspend () -> Unit, drained: suspend () -> Unit): Boolean =
        withTimeoutOrNull(capMs) {
            write()
            drained()
            true
        } ?: false

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
    fun auditLine(reason: String, skip: Skip?): String =
        "close_cue played=${if (skip == null) 1 else 0} reason=${token(reason)} skipped=${skip?.token ?: "none"}"

    private fun token(reason: String) = reason.lowercase(Locale.US).replace(Regex("[^a-z0-9_]"), "_")
}
