package com.kevin.legion.service.cascade

/**
 * Every filler timing in one place (2026-11-21: Kevin wants the first sound the moment his turn is
 * over, not after a wait). Measured from the phone's end-of-turn decision (eot):
 *
 * | What | When |
 * |---|---|
 * | Acknowledgement ([Backchannel]) | at eot ([ACK_DELAY_MS]), from memory only |
 * | First wait line ([WaitFillers]) | [FIRST_WAIT_GAP_MS] after the last filler ended (~2.0 s from eot) |
 * | Each further wait line | [NEXT_WAIT_GAP_MS] after the one before ended |
 * | Task line ([ToolFillers]) | not within [SPACING_MS] of the acknowledgement's end |
 * | Cap, all kinds | [MAX_PER_TURN] a turn |
 *
 * Before (shipped 263250616): acknowledgement at eot + 600 ms, first wait line 1.8 s after the last
 * filler ended. When no acknowledgement played, the first sound was the wait line at 1.8 s.
 */
internal object FillerTiming {
    /** The acknowledgement starts as soon as his turn is decided over. */
    const val ACK_DELAY_MS = 0L

    /**
     * The latest the acknowledgement's first sample may reach the player, from eot: what "instant"
     * means. Guarded by `AckTimingTest` (the whole end-of-turn path, timed) and read off every turn's
     * `first_sound_ms` line ([TurnGap]).
     */
    const val ACK_MAX_START_MS = 150L

    /** The first wait line: this long after the last filler ended (or eot, with none). */
    const val FIRST_WAIT_GAP_MS = 1_500L

    /** Each further wait line: this long after the one before ended. */
    const val NEXT_WAIT_GAP_MS = 2_500L

    /** Never two clips back to back: a task line waits this long after the acknowledgement's end. */
    const val SPACING_MS = 400L

    /** Fillers of every kind in one turn. */
    const val MAX_PER_TURN = 6
}
