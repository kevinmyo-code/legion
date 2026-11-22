package com.kevin.legion.service.cascade

/**
 * Cutting the acknowledgement (2026-11-21): whoever ends it (his speech, a stop, a goodbye) cuts its
 * audio at once, on the caller's turn of the loop, before the next line is written. The cut used to
 * run when the cancelled clip next resumed, after a farewell had already taken the player's generation,
 * and its flush then dropped the farewell's writes as stale. The clip itself never flushes.
 */
internal class AckCut(private val endJobs: () -> Unit, private val flush: () -> Unit) {
    /** The acknowledgement's audio is in the track now. */
    @Volatile var playing = false

    /** Ends the turn's filler jobs and, if the acknowledgement is playing, flushes its audio now. */
    fun cut() {
        endJobs()
        if (playing) {
            playing = false
            flush()
        }
    }
}

/**
 * When a drain wait may give up early (review H1, 2026-11-21): only once the playback head has moved
 * at least once and then stopped for [STALL_MS]. A head that has not moved YET is a track still
 * starting: a Bluetooth sink can take over a second to wake, and giving up then would let the mic open
 * on the reply's own audio. A track that never starts is bounded by the caller's own timeout (an
 * acknowledgement: its length plus a margin). Pure.
 */
internal object DrainRule {
    /** A head that moved, then has not moved for this long, has stalled. */
    const val STALL_MS = 500L

    fun stalled(movedOnce: Boolean, sinceLastMoveMs: Long): Boolean = movedOnce && sinceLastMoveMs >= STALL_MS
}
