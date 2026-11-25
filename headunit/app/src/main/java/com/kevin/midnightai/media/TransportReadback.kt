package com.kevin.midnightai.media

/**
 * Did a transport command actually take effect?
 *
 * Exists because `control_music` reported success the instant a command was DISPATCHED. A play
 * that reached a dead session, or resumed something other than what was meant, came back
 * `success: true` with no state at all, and Alfred said an outcome verb over it
 * (`music-suggestions-1124` P4; CLAUDE.md section 7 and ADR 0031: an outcome verb may follow only
 * a result that observed the outcome). A dispatch that did not throw is not an observation.
 *
 * Pure and JVM-testable on purpose: no Android, no media session. The caller takes the two
 * observations off whichever player it dispatched to; this file only judges them.
 */
internal object TransportReadback {

    /** The four commands that can be read back. The rest of `control_music` reads its own state. */
    enum class Move { PLAY, PAUSE, NEXT, PREVIOUS }

    /**
     * What a player reported at one moment. [trackKey] is whatever identifies the current item to
     * that player (a media id, a URI); [positionMs] is where it is inside that item. Any of the
     * three may be null, because a player is allowed to publish none of them - and when it does,
     * the honest verdict is [Verdict.CANNOT_TELL] rather than a guess.
     *
     * [playing] is three-valued on purpose. Only an explicitly paused or stopped state is false;
     * buffering, connecting or a session that reports nothing are UNKNOWN, not paused. A play that
     * worked and is still buffering, or a Bluetooth bridge that reports no state while it plays,
     * would otherwise be answered with "it still reports not playing".
     */
    data class Observation(val playing: Boolean?, val trackKey: String? = null, val positionMs: Long? = null)

    enum class Verdict {
        /** The player's own state moved the way the command asked. */
        CONFIRMED,

        /** The player answered, and it did not do it. */
        CONTRADICTED,

        /** Nothing readable came back, so neither claim can be made. */
        CANNOT_TELL,
    }

    /**
     * [before] and [after] must come from the SAME player, read either side of the dispatch.
     *
     * Play and pause are judged on the playing flag alone, which is exactly what they ask for.
     * Next and previous are not: the flag is unchanged by a successful skip, so they need either a
     * different item or a position that moved backwards (which is also what "previous" does to a
     * track already past its first seconds). With neither identity nor position published, a skip
     * cannot be confirmed at all, and saying so is the point of this function.
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod") // one worded exit per case, six cases
    fun verdict(move: Move, before: Observation?, after: Observation?): Verdict {
        if (after == null) return Verdict.CANNOT_TELL
        when (move) {
            Move.PLAY -> return when (after.playing) {
                true -> Verdict.CONFIRMED
                false -> Verdict.CONTRADICTED
                null -> Verdict.CANNOT_TELL
            }
            Move.PAUSE -> return when (after.playing) {
                false -> Verdict.CONFIRMED
                true -> Verdict.CONTRADICTED
                null -> Verdict.CANNOT_TELL
            }
            Move.NEXT, Move.PREVIOUS -> Unit
        }
        if (before == null) return Verdict.CANNOT_TELL
        val keysKnown = before.trackKey != null && after.trackKey != null
        if (keysKnown && before.trackKey != after.trackKey) return Verdict.CONFIRMED
        val movedBack = before.positionMs != null && after.positionMs != null && after.positionMs < before.positionMs
        if (movedBack) return Verdict.CONFIRMED
        // Same item and no position evidence either way: nothing was observed to happen. With the
        // identity published it is a real contradiction; without it, it is simply unreadable.
        return if (keysKnown) Verdict.CONTRADICTED else Verdict.CANNOT_TELL
    }

    /** How long to keep reading the player back before giving up, and how often. */
    const val BUDGET_MS = 900L
    const val STEP_MS = 100L
}
