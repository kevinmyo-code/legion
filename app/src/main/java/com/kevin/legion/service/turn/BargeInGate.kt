package com.kevin.legion.service.turn

/**
 * Whether the user is talking over Alfred, as a pure fold over Silero windows (32 ms) heard while
 * the reply plays (design section 9, A25 call-mode finding section 10).
 *
 * The phone's own echo is speech too, so Silero alone cannot tell it from the user. On the A25 in
 * call mode the echo peaked near 10,000 and Kevin's voice at 19,000-30,000 (one device, one room), so
 * a window counts only when Silero is confident ([PROB]) AND its peak is above the echo floor learned
 * from THIS reply ([ECHO_MARGIN] x the [ECHO_PERCENTILE]th percentile of the reply's windows so far;
 * until [LEARN_WINDOWS] have been heard, the floor the previous reply learned, or
 * [DEFAULT_ECHO_FLOOR] when none has). It fires when at least [SUSTAIN_MS]
 * of such windows ([SUSTAIN_WINDOWS]) fall within the last [SPAN_WINDOWS] (512 ms), in any order, so
 * a backchannel ("mm-hm", "okay") shorter than that never stops the reply.
 *
 * Sliding window, not a consecutive run (A25, 2026-10-28, run 5): Kevin said "stop" into the mic at
 * prob 1.00 and level 30,560 against a threshold of 3,000; 27 windows of the reply qualified, but
 * Silero's prob jitters across a short plosive word, so the longest consecutive run (one miss
 * tolerated) was 224 ms, one window short, and the reply never stopped. [ReplyStats.longestRunMs]
 * is still kept, now strictly consecutive; [ReplyStats.bestSpanWindows] is the figure that fires.
 *
 * After a fire it watches [VERDICT_MS]. It is a self-interrupt (the echo set it off) only when BOTH
 * hold: Silero hears less than [SELF_SPEECH_MS] of speech after the reply was flushed, AND the span
 * that fired was never [CLEARLY_USER_FACTOR] x its threshold loud. The level clause is from the A25
 * (2026-10-27): a real short "stop" fired at 22,914 against an echo floor near 0, had only 128 ms of
 * speech left after the fire, and was counted as echo. Echo that clears the threshold does so
 * narrowly; a voice at twice it is the user. [MAX_SELF_INTERRUPTS] of those turn barge-in off for
 * the rest of the session.
 *
 * The floor must learn the echo, not the user (A25, 2026-10-29, run 6). Two rules:
 *  - **No learning from the user's own voice.** For [USER_GUARD_WINDOWS] (1 s) after the user was
 *    last known to be talking (the gate's own fire, speech heard during a verdict watch, or
 *    [onUserTurnEnd]), a window Silero calls speech ([SPEECH_PROB]) is not learned. Run 6: the reply
 *    after a barge-in ("Understood.", 40 windows) began while Kevin was still talking, learned his
 *    voice as echo (`echo_floor=9785 threshold=14677`), and his next words reached `best_span=7`,
 *    one short, at five times the threshold that had just fired. The guard is a window count, so
 *    it runs on in real time across the gap between replies. Under server VAD there is no
 *    activity end, so only a fire arms it.
 *  - **A reply starts from the last reply's floor.** Until a reply has learned [LEARN_WINDOWS], its
 *    floor is the percentile the last reply that learned that many ended on; [DEFAULT_ECHO_FLOOR]
 *    only before any has. Run 5 and 6 replies learned 400 to 550 on the A25 in call mode, so the
 *    first 320 ms of every reply ran at a threshold of 18,000, thirty times the real echo. One gate
 *    per [TurnTaker], one taker per socket: the seed never outlives the session.
 *
 * The gate says nothing unless it fires, so each reply also keeps a [ReplyStats] (A25,
 * 2026-10-28: armed on every reply, zero fires, and no way to tell why). [onReplyEnd] hands it back
 * once per reply; it is null when no reply was open, so a second end (flush, then drain) is silent.
 */
@Suppress("TooManyFunctions") // one pure fold; onUserTurnEnd (run 6) is its twelfth input
internal class BargeInGate {

    sealed interface Event {
        data class Fire(
            val prob: Float,
            val level: Int,
            val echoFloor: Int,
            val threshold: Int,
            val sustainMs: Int,
        ) : Event
        data class Verdict(
            val selfInterrupt: Boolean,
            val speechMsAfter: Int,
            val fireLevel: Int,
            val fireThreshold: Int,
            val selfInterrupts: Int,
            val nowDisabled: Boolean,
        ) : Event
    }

    /**
     * What the gate heard over one reply, counting only windows it listened to (armed, playing, not
     * watching a verdict). [windows] 0 means it never heard the reply at all.
     */
    data class ReplyStats(
        val fired: Boolean,
        val windows: Int,
        /** prob >= [PROB]. */
        val speechWindows: Int,
        /** level >= the threshold at that window. */
        val loudWindows: Int,
        /** Both: the windows a fire is built from. */
        val qualifyingWindows: Int,
        val maxProb: Float,
        val maxLevel: Int,
        /** Longest strictly consecutive run of qualifying windows. */
        val longestRunMs: Int,
        /** Most qualifying windows seen in any [SPAN_WINDOWS] span; it fires at [SUSTAIN_WINDOWS]. */
        val bestSpanWindows: Int,
        val echoFloor: Int,
        val threshold: Int,
    )

    var disabled = false
        private set
    var selfInterrupts = 0
        private set

    private val levels = IntArray(MAX_LEARN)
    private var learned = 0
    /** The floor the last reply that learned [LEARN_WINDOWS] ended on; null before one has. */
    private var seedFloor: Int? = null
    /** Windows left in which a speech window is the user's, not echo, and is not learned. */
    private var userGuardLeft = 0
    /** Bit i set: the window i windows ago qualified. Only the low [SPAN_WINDOWS] bits are kept. */
    private var span = 0
    /** The last [SPAN_WINDOWS] windows' prob and level, so a fire reports the peak of its span. */
    private val spanProb = FloatArray(SPAN_WINDOWS)
    private val spanLevel = IntArray(SPAN_WINDOWS)
    private var spanPos = 0
    private var runMs = 0
    private var armed = false
    private var verdictWindowsLeft = 0
    private var speechAfterMs = 0
    private var fireLevel = 0
    private var fireThreshold = 0

    private var replyOpen = false
    private var fired = false
    private var windows = 0
    private var speechWindows = 0
    private var loudWindows = 0
    private var qualifyingWindows = 0
    private var replyMaxProb = 0f
    private var replyMaxLevel = 0
    private var longestRunMs = 0
    private var bestSpanWindows = 0

    /** Listening for the user over the current reply. */
    val armedNow: Boolean get() = armed

    /** A new reply started playing: forget the last reply's echo. */
    fun onReplyStart() {
        learned = 0
        resetRun()
        armed = !disabled
        replyOpen = true
        fired = false
        windows = 0
        speechWindows = 0
        loudWindows = 0
        qualifyingWindows = 0
        replyMaxProb = 0f
        replyMaxLevel = 0
        longestRunMs = 0
        bestSpanWindows = 0
    }

    /**
     * Playback drained or was flushed (with or without a fire). Returns the reply's stats, or null
     * when no reply was open (already ended, or never started).
     */
    fun onReplyEnd(): ReplyStats? {
        armed = false
        resetRun()
        if (!replyOpen) return null
        replyOpen = false
        if (learned >= LEARN_WINDOWS) seedFloor = percentile()
        return ReplyStats(
            fired, windows, speechWindows, loudWindows, qualifyingWindows,
            replyMaxProb, replyMaxLevel, longestRunMs, bestSpanWindows, echoFloor(), threshold(),
        )
    }

    /**
     * The user's turn just ended (the caller sent `activityEnd`): they were talking a moment ago and
     * may still be, so for [USER_GUARD_WINDOWS] their speech is not learned as echo.
     */
    fun onUserTurnEnd() {
        userGuardLeft = USER_GUARD_WINDOWS
    }

    fun echoFloor(): Int = if (learned < LEARN_WINDOWS) seedFloor ?: DEFAULT_ECHO_FLOOR else percentile()

    fun threshold(): Int = maxOf(MIN_LEVEL, (echoFloor() * ECHO_MARGIN).toInt())

    /**
     * One window. [playing] is true while the reply is audible. Returns a [Event.Fire] when the user
     * is talking over it, a [Event.Verdict] when a fire's watch period ends, otherwise null.
     */
    fun onWindow(prob: Float, level: Int, playing: Boolean): Event? {
        if (userGuardLeft > 0) userGuardLeft--
        return when {
            verdictWindowsLeft > 0 -> watch(prob)
            !armed || !playing || disabled -> null
            else -> listen(prob, level)
        }
    }

    private fun listen(prob: Float, level: Int): Event? {
        val threshold = threshold()
        val qualifies = prob >= PROB && level >= threshold
        count(prob, level, threshold, qualifies)
        span = ((span shl 1) or (if (qualifies) 1 else 0)) and SPAN_MASK
        spanProb[spanPos] = prob
        spanLevel[spanPos] = level
        spanPos = (spanPos + 1) % SPAN_WINDOWS
        if (qualifies) {
            runMs += WINDOW_MS
            if (runMs > longestRunMs) longestRunMs = runMs
        } else {
            if (userGuardLeft == 0 || prob < SPEECH_PROB) learn(level)
            runMs = 0
        }
        val inSpan = Integer.bitCount(span)
        if (inSpan > bestSpanWindows) bestSpanWindows = inSpan
        if (inSpan < SUSTAIN_WINDOWS) return null
        var maxProb = 0f
        var maxLevel = 0
        for (ago in 0 until SPAN_WINDOWS) {
            if (span and (1 shl ago) == 0) continue
            val i = (spanPos - 1 - ago + SPAN_WINDOWS) % SPAN_WINDOWS
            if (spanProb[i] > maxProb) maxProb = spanProb[i]
            if (spanLevel[i] > maxLevel) maxLevel = spanLevel[i]
        }
        val fire = Event.Fire(maxProb, maxLevel, echoFloor(), threshold, inSpan * WINDOW_MS)
        fireLevel = maxLevel
        fireThreshold = threshold
        fired = true
        armed = false
        resetRun()
        verdictWindowsLeft = VERDICT_MS / WINDOW_MS
        speechAfterMs = 0
        userGuardLeft = USER_GUARD_WINDOWS
        return fire
    }

    /** The reply's [ReplyStats] counters for one listened window. */
    private fun count(prob: Float, level: Int, threshold: Int, qualifies: Boolean) {
        windows++
        if (prob >= PROB) speechWindows++
        if (level >= threshold) loudWindows++
        if (qualifies) qualifyingWindows++
        if (prob > replyMaxProb) replyMaxProb = prob
        if (level > replyMaxLevel) replyMaxLevel = level
    }

    private fun watch(prob: Float): Event? {
        if (prob >= SPEECH_PROB) {
            speechAfterMs += WINDOW_MS
            userGuardLeft = USER_GUARD_WINDOWS
        }
        if (--verdictWindowsLeft > 0) return null
        val clearlyUser = fireLevel >= fireThreshold * CLEARLY_USER_FACTOR
        val self = speechAfterMs < SELF_SPEECH_MS && !clearlyUser
        if (self) selfInterrupts++
        if (selfInterrupts >= MAX_SELF_INTERRUPTS) disabled = true
        return Event.Verdict(self, speechAfterMs, fireLevel, fireThreshold, selfInterrupts, disabled)
    }

    private fun learn(level: Int) {
        if (learned < MAX_LEARN) levels[learned++] = level
    }

    private fun percentile(): Int {
        val s = levels.copyOf(learned).also { it.sort() }
        val idx = ((ECHO_PERCENTILE / PERCENT) * (s.size - 1)).toInt()
        return s[idx]
    }

    private fun resetRun() {
        span = 0
        runMs = 0
    }

    companion object {
        const val WINDOW_MS = 32
        const val PROB = 0.8f
        const val SPEECH_PROB = 0.5f
        /** Design 9: "~100-250 ms"; the long end, so a two-syllable "okay" does not stop him. */
        const val SUSTAIN_MS = 256
        const val SUSTAIN_WINDOWS = SUSTAIN_MS / WINDOW_MS
        /** 512 ms: the span [SUSTAIN_WINDOWS] must fall within (A25 run 5, 2026-10-28). */
        const val SPAN_WINDOWS = 16
        private const val SPAN_MASK = (1 shl SPAN_WINDOWS) - 1
        const val ECHO_MARGIN = 1.5f
        const val ECHO_PERCENTILE = 90
        private const val PERCENT = 100.0
        /** A25 call mode, 2026-10-27: echo peaked ~10,000. Used until the reply has taught its own. */
        const val DEFAULT_ECHO_FLOOR = 12_000
        const val MIN_LEVEL = 3_000
        const val LEARN_WINDOWS = 10
        /** 1 s after the user was last heard: their speech is not the reply's echo (run 6). */
        const val USER_GUARD_MS = 1_000
        const val USER_GUARD_WINDOWS = USER_GUARD_MS / WINDOW_MS
        private const val MAX_LEARN = 2_000
        const val VERDICT_MS = 1_216
        const val SELF_SPEECH_MS = 160
        const val MAX_SELF_INTERRUPTS = 2
        /** A fire whose peak was at least this many times its threshold was the user, not echo. */
        const val CLEARLY_USER_FACTOR = 2
    }
}
