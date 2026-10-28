package com.kevin.legion.service.turn

/**
 * Whether the user is talking over Alfred, as a pure fold over Silero windows (32 ms) heard while
 * the reply plays (design section 9, A25 call-mode finding section 10).
 *
 * The phone's own echo is speech too, so Silero alone cannot tell it from the user. On the A25 in
 * call mode the echo peaked near 10,000 and Kevin's voice at 19,000-30,000 (one device, one room), so
 * a window counts only when Silero is confident ([PROB]) AND its peak is above the echo floor learned
 * from THIS reply ([ECHO_MARGIN] x the [ECHO_PERCENTILE]th percentile of the reply's windows so far;
 * [DEFAULT_ECHO_FLOOR] until [LEARN_WINDOWS] have been heard). It fires after [SUSTAIN_MS] of such
 * windows, tolerating one stray window, so a backchannel ("mm-hm", "okay") shorter than that never
 * stops the reply.
 *
 * After a fire it watches [VERDICT_MS]. It is a self-interrupt (the echo set it off) only when BOTH
 * hold: Silero hears less than [SELF_SPEECH_MS] of speech after the reply was flushed, AND the run
 * that fired was never [CLEARLY_USER_FACTOR] x its threshold loud. The level clause is from the A25
 * (2026-10-27): a real short "stop" fired at 22,914 against an echo floor near 0, had only 128 ms of
 * speech left after the fire, and was counted as echo. Echo that clears the threshold does so
 * narrowly; a voice at twice it is the user. [MAX_SELF_INTERRUPTS] of those turn barge-in off for
 * the rest of the session.
 */
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

    var disabled = false
        private set
    var selfInterrupts = 0
        private set

    private val levels = IntArray(MAX_LEARN)
    private var learned = 0
    private var qualifyingMs = 0
    private var misses = 0
    private var maxProb = 0f
    private var maxLevel = 0
    private var armed = false
    private var verdictWindowsLeft = 0
    private var speechAfterMs = 0
    private var fireLevel = 0
    private var fireThreshold = 0

    /** A new reply started playing: forget the last reply's echo. */
    fun onReplyStart() {
        learned = 0
        resetRun()
        armed = !disabled
    }

    /** Playback drained or was flushed without a fire. */
    fun onReplyEnd() {
        armed = false
        resetRun()
    }

    fun echoFloor(): Int = if (learned < LEARN_WINDOWS) DEFAULT_ECHO_FLOOR else percentile()

    fun threshold(): Int = maxOf(MIN_LEVEL, (echoFloor() * ECHO_MARGIN).toInt())

    /**
     * One window. [playing] is true while the reply is audible. Returns a [Event.Fire] when the user
     * is talking over it, a [Event.Verdict] when a fire's watch period ends, otherwise null.
     */
    fun onWindow(prob: Float, level: Int, playing: Boolean): Event? = when {
        verdictWindowsLeft > 0 -> watch(prob)
        !armed || !playing || disabled -> null
        else -> listen(prob, level)
    }

    private fun listen(prob: Float, level: Int): Event? {
        val threshold = threshold()
        val qualifies = prob >= PROB && level >= threshold
        if (qualifies) {
            qualifyingMs += WINDOW_MS
            misses = 0
            if (prob > maxProb) maxProb = prob
            if (level > maxLevel) maxLevel = level
        } else {
            learn(level)
            if (qualifyingMs > 0 && ++misses > ALLOWED_MISSES) resetRun()
        }
        if (qualifyingMs < SUSTAIN_MS) return null
        val fire = Event.Fire(maxProb, maxLevel, echoFloor(), threshold, qualifyingMs)
        fireLevel = maxLevel
        fireThreshold = threshold
        armed = false
        resetRun()
        verdictWindowsLeft = VERDICT_MS / WINDOW_MS
        speechAfterMs = 0
        return fire
    }

    private fun watch(prob: Float): Event? {
        if (prob >= SPEECH_PROB) speechAfterMs += WINDOW_MS
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
        qualifyingMs = 0
        misses = 0
        maxProb = 0f
        maxLevel = 0
    }

    companion object {
        const val WINDOW_MS = 32
        const val PROB = 0.8f
        const val SPEECH_PROB = 0.5f
        /** Design 9: "~100-250 ms"; the long end, so a two-syllable "okay" does not stop him. */
        const val SUSTAIN_MS = 256
        const val ALLOWED_MISSES = 1
        const val ECHO_MARGIN = 1.5f
        const val ECHO_PERCENTILE = 90
        private const val PERCENT = 100.0
        /** A25 call mode, 2026-10-27: echo peaked ~10,000. Used until the reply has taught its own. */
        const val DEFAULT_ECHO_FLOOR = 12_000
        const val MIN_LEVEL = 3_000
        const val LEARN_WINDOWS = 10
        private const val MAX_LEARN = 2_000
        const val VERDICT_MS = 1_216
        const val SELF_SPEECH_MS = 160
        const val MAX_SELF_INTERRUPTS = 2
        /** A fire whose peak was at least this many times its threshold was the user, not echo. */
        const val CLEARLY_USER_FACTOR = 2
    }
}
