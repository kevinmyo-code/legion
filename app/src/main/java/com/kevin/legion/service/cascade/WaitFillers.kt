package com.kevin.legion.service.cascade

/**
 * Chained holding lines on a long wait (Kevin, 2026-11-20: another voice client "sometimes two or
 * more" fillers when the answer takes long; he wants that feel). After the backchannel ([Backchannel])
 * and any task line ([ToolFillers]), if no answer audio has started [FIRST_GAP_MS] after the last
 * filler ended, one of these plays; if still nothing [NEXT_GAP_MS] after that one ended, one more.
 * At most [MAX_PER_TURN] fillers in a turn, all kinds counted. Every waiting turn, tool or no tool.
 *
 * Neutral holding lines only: each promises nothing and claims nothing ("Still on it.", never "Almost
 * done."), under about a second, kept in memory in two takes like the backchannels, played by code
 * from memory or not at all. No line twice in one turn, none of the last [ROTATION] wait lines.
 *
 * When not: the same rules as the backchannel ([Backchannel.decide]: not over Alfred's own audio, not
 * on a call, not in Quiet; Bluetooth too, his audio kept through the line), and never once the answer's text or audio
 * has started. A line already playing finishes; the answer follows it, never over it. Pure.
 */
internal object WaitFillers {
    /** The first wait line: this long after the last filler ended (or his end of speech, with none). */
    const val FIRST_GAP_MS = 3_500L

    /** Each further wait line: this long after the one before it ended. */
    const val NEXT_GAP_MS = 4_000L

    /** Fillers of every kind in one turn (backchannel, task line, wait lines). */
    const val MAX_PER_TURN = 3

    /** No wait line again within this many wait lines. */
    const val ROTATION = 5

    /** Renders kept per line, as for the backchannels. */
    const val TAKES = 2

    /** A wait line's measured length must be inside this, or another line is tried. */
    const val MIN_CLIP_MS = 200L
    const val MAX_CLIP_MS = 1_300L

    val LINES = listOf("Still on it.", "Still checking.", "One moment more.", "Bear with me.", "Working on it.")

    /**
     * When the next wait line is due: [FIRST_GAP_MS] after [lastFillerEndAtMs] for the first,
     * [NEXT_GAP_MS] for any later one. Null once [fillersSaid] reached [MAX_PER_TURN].
     */
    fun nextAtMs(lastFillerEndAtMs: Long, waitSaid: Int, fillersSaid: Int): Long? {
        if (fillersSaid >= MAX_PER_TURN) return null
        return lastFillerEndAtMs + if (waitSaid == 0) FIRST_GAP_MS else NEXT_GAP_MS
    }

    fun clipFits(ms: Long): Boolean = ms in MIN_CLIP_MS..MAX_CLIP_MS

    /** A wait line that passed the length gate: line, take, measured length, lines that failed first. */
    data class Chosen(val line: String, val take: Int, val ms: Long, val rejected: Int)

    /**
     * The line to say: none said this turn ([saidThisTurn], the task line's words included), none of
     * the last [ROTATION] wait lines (falling back to anything but the last), only what is in memory,
     * and when its clip fails the length gate another line. Null: nothing fits.
     */
    suspend fun choose(
        recent: RecentLines,
        saidThisTurn: Set<String>,
        index: Int,
        inHand: (String, Int) -> Boolean,
        measure: suspend (String, Int) -> Long,
    ): Chosen? {
        val tried = mutableSetOf<String>()
        while (true) {
            val ready = LINES.filter { line ->
                line !in saidThisTurn && line !in tried && (0 until TAKES).any { t -> inHand(line, t) }
            }
            val options = ready.filterNot { recent.contains(it) }.ifEmpty { ready.filter { it != recent.last() } }
            if (options.isEmpty()) return null
            val line = options[Math.floorMod(index, options.size)]
            val takes = (0 until TAKES).filter { inHand(line, it) }
            val take = takes[Math.floorMod(index, takes.size)]
            val ms = measure(line, take)
            if (clipFits(ms)) return Chosen(line, take, ms, tried.size)
            tried += line
        }
    }

    /**
     * When each wait line would start in a turn whose answer audio is ready at [answerAtMs], each
     * line [clipMs] long, after [fillersBefore] fillers that ended at [lastFillerEndAtMs]. The same
     * rule the session runs ([nextAtMs]), as a timeline: what a test and a reader can check.
     */
    fun timeline(lastFillerEndAtMs: Long, fillersBefore: Int, answerAtMs: Long, clipMs: Long): List<Long> {
        val starts = mutableListOf<Long>()
        var at = nextAtMs(lastFillerEndAtMs, 0, fillersBefore)
        while (at != null && at < answerAtMs) {
            starts += at
            at = nextAtMs(at + clipMs, starts.size, fillersBefore + starts.size)
        }
        return starts
    }
}
