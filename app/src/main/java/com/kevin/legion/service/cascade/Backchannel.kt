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
 * - not a turn of his (a proactive line, the greeting);
 * - half-duplex (barge-in off: Bluetooth, a call): the mic is deaf while a clip plays, so a clip
 *   about a second after his last word would swallow the rest of a sentence he was still saying
 *   (blind review M1, 2026-11-19). Allowed there only behind [halfDuplexAllowed] (off), which keeps
 *   his audio through the clip and hands it on ([ClipListen]).
 * Which clip ([pick]): none of the last [ROTATION] words (a [RecentLines] window, seeded with the last
 * word of the last conversation so it is not the first of the next), and of that word one of its
 * [TAKES] renders (the cloud voice renders the same word differently each time; measured 2026-11-19).
 *
 * **What may be a backchannel ([LINES]), and why:** it plays about a second after his last word,
 * before the transcript exists, so it must promise nothing and claim nothing. "Got it.", "I see.",
 * "Sure.", "On it." or "One sec." would be wrong before a "didn't catch that" or a refusal; only
 * neutral sounds of listening go here.
 * The same skips apply to every word.
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
    const val ROTATION = 5

    /** Renders kept per word, played in turn. */
    const val TAKES = 2

    /** A clip outside this measured length is not played (the voice may render "Mm." as nothing, or as a sigh). */
    const val MIN_CLIP_MS = 120L
    const val MAX_CLIP_MS = 700L

    // "Let's see." was tried (2026-11-19) and dropped: trimmed, 3 of 10 renders fit the length gate.
    val LINES = listOf("Mm.", "Right.", "Yeah.", "Hmm.", "Okay.", "Mhm.", "Uh-huh.", "Alright.")

    /**
     * The cloud voice pads a one-word render with 80 to 520 ms of silence each side (measured
     * 2026-11-19: 760 to 1,680 ms raw for 230 to 1,040 ms of voice), so every raw clip failed the
     * length gate. The clip is trimmed to its voice ([trimSilence]) with this much kept each side.
     */
    const val EDGE_PAD_MS = 40L

    /** Each cut of a trimmed clip fades over this long, so it never starts or stops on a step (a click). */
    const val FADE_MS = 5L

    /** A 16-bit sample louder than this is voice, for [trimSilence]. */
    private const val SILENCE_LEVEL = 600

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
        /** Barge-in is off for this conversation (Bluetooth route, call audio): the mic is deaf during a clip. */
        val halfDuplex: Boolean = false,
        /** The setting that lets a half-duplex conversation have one, with [ClipListen] keeping his audio (off). */
        val halfDuplexAllowed: Boolean = false,
    )

    enum class Skip(val token: String) {
        NOT_HIS("not_his"), SHORT("short"), ASKED("answer_to_ask"), OVER_REPLY("over_reply"), TIC("tic"),
        QUIET("quiet"), HALF_DUPLEX("half_duplex"), ANSWER_TEXT("answer_text"),
    }

    /** Null: play one (if one is in memory and the answer has not started). Otherwise why not. */
    @Suppress("ReturnCount") // one exit per rule, in the order above
    fun decide(t: Turn): Skip? {
        if (!t.fromUser) return Skip.NOT_HIS
        if (t.quiet) return Skip.QUIET
        if (t.halfDuplex && !t.halfDuplexAllowed) return Skip.HALF_DUPLEX
        if (t.overReplyOrTurn) return Skip.OVER_REPLY
        if (t.askedBefore) return Skip.ASKED
        if (t.speechMs < MIN_SPEECH_MS) return Skip.SHORT
        if (t.lastTurnHadOne && t.lastBackchannelAtMs > 0 && t.startedAtMs - t.lastBackchannelAtMs < TIC_GAP_MS) {
            return Skip.TIC
        }
        return null
    }

    /**
     * The clip: a word of [LINES] not in [recent] (falling back to anything but the last) and one of
     * its takes, only what is in memory ([inHand] of word and take). Null: nothing to play.
     */
    fun pick(recent: RecentLines, index: Int, inHand: (String, Int) -> Boolean): Pair<String, Int>? {
        val takes = LINES.associateWith { w -> (0 until TAKES).filter { inHand(w, it) } }
        val ready = LINES.filter { takes.getValue(it).isNotEmpty() }
        val options = ready.filterNot { recent.contains(it) }.ifEmpty { ready.filter { it != recent.last() } }
        if (options.isEmpty()) return null
        val word = options[Math.floorMod(index, options.size)]
        val own = takes.getValue(word)
        return word to own[Math.floorMod(index, own.size)]
    }

    /** [pcm] (16-bit mono at [sampleRate]) cut to its voice plus [EDGE_PAD_MS] each side; empty when silent. */
    fun trimSilence(pcm: ByteArray, sampleRate: Int): ByteArray {
        val samples = pcm.size / 2
        fun loud(i: Int): Boolean {
            val sample = (pcm[2 * i].toInt() and BYTE_MASK) or (pcm[2 * i + 1].toInt() shl BITS)
            return kotlin.math.abs(sample) > SILENCE_LEVEL
        }
        val first = (0 until samples).firstOrNull(::loud) ?: return ByteArray(0)
        val last = (samples - 1 downTo 0).first(::loud)
        val pad = (EDGE_PAD_MS * sampleRate / MS_PER_S).toInt()
        val from = (first - pad).coerceAtLeast(0)
        val to = (last + pad).coerceAtMost(samples - 1)
        return fadeEdges(pcm.copyOfRange(2 * from, 2 * (to + 1)), (FADE_MS * sampleRate / MS_PER_S).toInt())
    }

    /** Linear fade over [fade] samples at both ends of [pcm] (in place), from silence to full and back. */
    private fun fadeEdges(pcm: ByteArray, fade: Int): ByteArray {
        val n = pcm.size / 2
        val span = minOf(fade, n / 2)
        for (i in 0 until span) {
            scale(pcm, i, i, span)
            scale(pcm, n - 1 - i, i, span)
        }
        return pcm
    }

    private fun scale(pcm: ByteArray, at: Int, step: Int, span: Int) {
        val v = (pcm[2 * at].toInt() and BYTE_MASK) or (pcm[2 * at + 1].toInt() shl BITS)
        val scaled = v * step / span
        pcm[2 * at] = (scaled and BYTE_MASK).toByte()
        pcm[2 * at + 1] = (scaled shr BITS).toByte()
    }

    private const val BYTE_MASK = 0xff
    private const val BITS = 8
    private const val MS_PER_S = 1_000L

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
