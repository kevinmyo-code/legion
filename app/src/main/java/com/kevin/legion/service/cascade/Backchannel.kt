package com.kevin.legion.service.cascade

/**
 * A content-free acknowledgement while he waits (Kevin, 2026-11-19, speed plan item 3: a filler as
 * soon as the thinking starts, on turns without a tool too). The task line ([ToolFillers]) needs a
 * tool call, so before this a turn
 * with no tool (58 of 111 on the 11-19 drive) could never get one, and none could start before
 * speech-to-text and the model's first round (median first filler 2.3 s after his last word).
 *
 * Two families of clips, both kept in memory in the companion's voice ([ScriptedLines.KEEP]):
 * - a backchannel ([LINES]): "Mm.", "Right." and the like. It promises nothing, so it can play before
 *   anything is known about the turn;
 * - the task line, only when a tool is actually running ([ToolFillers]).
 *
 * **When** ([DELAY_MS]): a timer starts at the end of his speech. If no answer audio has started by
 * then, one backchannel plays. The task line keeps its own rule, and never starts within
 * [SPACING_MS] of a backchannel's end ([spacingWaitMs]). At most one of each a turn.
 *
 * **When not** ([decide], all from signals code already has; never his words or the model's):
 * - his speech was short (under [MIN_SPEECH_MS]: "yes", "stop", "no"): the answer is quick and an
 *   "Mm." before it is noise;
 * - the turn before asked him something through its tool fields (`needs_answer`,
 *   `needs_confirmation`, a draft): his answer is followed by the action, not an "Mm.";
 * - he started over a reply or a turn still in flight (a barge-in, a merge): he is correcting, not
 *   waiting;
 * - a backchannel played on the last turn and this one started within [TIC_GAP_MS] of it (a tic);
 * - Quiet mode;
 * - not a turn of his (a proactive line, the greeting).
 * Which clip ([pick]): never the one before, and none of the last [ROTATION] (a [RecentLines] window).
 *
 * Pure: no clock, no Android.
 */
internal object Backchannel {
    /** After his speech ends: the end-of-speech wait (~0.7 s) is already behind him, so ~1.3 s after his last word. */
    const val DELAY_MS = 600L

    /** Shorter speech gets none ("yes", "no", "stop"). */
    const val MIN_SPEECH_MS = 1_200L

    /** A backchannel on the last turn and this one starting within this: none (it would be a tic). */
    const val TIC_GAP_MS = 20_000L

    /** The task line waits this long after a backchannel's end: never two clips back to back. */
    const val SPACING_MS = 400L

    /** No clip twice within this many backchannels. */
    const val ROTATION = 3

    /** A clip outside this measured length is not played (the voice may render "Mm." as nothing, or as a sigh). */
    const val MIN_CLIP_MS = 120L
    const val MAX_CLIP_MS = 700L

    val LINES = listOf("Mm.", "Right.", "Yeah.", "Hmm.", "Okay.")

    /** What the session knows about his turn at the end of his speech. */
    data class Turn(
        val fromUser: Boolean,
        /** From his first voiced window to his last ([com.kevin.legion.service.turn.TurnTaker]'s local speech). */
        val speechMs: Long,
        /** The turn before asked him something through its tool fields ([LoneWordWriteGuard.asks]). */
        val askedBefore: Boolean,
        /** He started over an audible reply or a turn still running. */
        val overReplyOrTurn: Boolean,
        val quiet: Boolean,
        /** When this turn's speech started, and when the last backchannel played (0: none yet). */
        val startedAtMs: Long,
        val lastBackchannelAtMs: Long,
        /** The turn just before this one played a backchannel. */
        val lastTurnHadOne: Boolean,
    )

    enum class Skip(val token: String) {
        NOT_HIS("not_his"), SHORT("short"), ASKED("answer_to_ask"), OVER_REPLY("over_reply"), TIC("tic"),
        QUIET("quiet"),
    }

    /** Null: play one (if one is in memory and the answer has not started). Otherwise why not. */
    @Suppress("ReturnCount") // one exit per rule, in the order above
    fun decide(t: Turn): Skip? {
        if (!t.fromUser) return Skip.NOT_HIS
        if (t.quiet) return Skip.QUIET
        if (t.overReplyOrTurn) return Skip.OVER_REPLY
        if (t.askedBefore) return Skip.ASKED
        if (t.speechMs < MIN_SPEECH_MS) return Skip.SHORT
        if (t.lastTurnHadOne && t.lastBackchannelAtMs > 0 && t.startedAtMs - t.lastBackchannelAtMs < TIC_GAP_MS) {
            return Skip.TIC
        }
        return null
    }

    /** The clip: one of [LINES] not in [recent] (falling back to anything but the last), only if [inHand]. */
    fun pick(recent: RecentLines, index: Int, inHand: (String) -> Boolean): String? {
        val ready = LINES.filter(inHand)
        val options = ready.filterNot { recent.contains(it) }.ifEmpty { ready.filter { it != recent.last() } }
        return if (options.isEmpty()) null else options[Math.floorMod(index, options.size)]
    }

    /** Whether a clip of [ms] measured audio may play. */
    fun clipFits(ms: Long): Boolean = ms in MIN_CLIP_MS..MAX_CLIP_MS

    /** How long the task line waits so it never starts within [SPACING_MS] of a backchannel's end. */
    fun spacingWaitMs(nowMs: Long, backchannelEndAtMs: Long?): Long =
        backchannelEndAtMs?.let { (it + SPACING_MS - nowMs).coerceAtLeast(0) } ?: 0L

    /**
     * The backchannel's timer fired: it plays only if the answer's audio has not started and
     * nothing else is audible. The answer arriving first always wins.
     */
    fun stillDue(answerStarted: Boolean, somethingAudible: Boolean, sameTurn: Boolean): Boolean =
        sameTurn && !answerStarted && !somethingAudible
}

/**
 * The last [size] lines said, newest last: a line in it is not said again until it falls out
 * (greetings and backchannels alike, 2026-11-19). Pure.
 */
internal class RecentLines(private val size: Int, initial: List<String> = emptyList()) {
    private val lines = ArrayDeque(initial.takeLast(size))

    fun contains(line: String): Boolean = lines.any { it.equals(line, ignoreCase = true) }

    fun last(): String? = lines.lastOrNull()

    fun add(line: String) {
        lines.addLast(line)
        while (lines.size > size) lines.removeFirst()
    }

    fun toList(): List<String> = lines.toList()
}
