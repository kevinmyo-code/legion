package com.kevin.legion.service.turn

/**
 * When the user's turn starts and ends, as a pure fold over Silero windows (32 ms each) and Smart
 * Turn results. No clock, no threads: [TurnTaker] feeds it and acts on what it returns, and
 * `EndOfTurnPolicyTest` walks it window by window.
 *
 * **For Live, Smart Turn can only EXTEND a turn (2026-10-27, timed then at 2 threads: p95 393-819
 * ms).** The cascade may also end one early (the last two bullets). The rule:
 * - Speech starts after [CONFIRM_MS] of Silero speech (start at >= [START_PROB], held while
 *   >= [END_PROB]); the caller sends `activityStart` with its pre-roll.
 * - Silence is time since the last window >= [END_PROB]; speech resumes at >= [START_PROB] and
 *   cancels everything pending.
 * - At [GATE_MS] of silence one Smart Turn check starts on the whole turn.
 * - At [BASE_MS] of silence the turn ends ([Reason.BASE]) unless a check already said "not done".
 * - A result below [THRESHOLD] that arrives BEFORE [BASE_MS] holds the turn open. Checks then run
 *   back to back (the next starts when the previous returns, never overlapping) and the turn ends at
 *   the first one scoring >= [THRESHOLD] ([Reason.EXTENDED]), or at [CAP_MS] ([Reason.CAP]). A
 *   "done" verdict that lands before [BASE_MS] still waits for [BASE_MS].
 * - A first result that lands after [BASE_MS] changes nothing: the turn already ended.
 * - So the end is max(base, first "done") when held, and never earlier than [BASE_MS]: a "done" that
 *   lands before the base releases the hold and the base ends the turn (seen on the A25, intended).
 * - The reason reports the whole turn: [Reason.EXTENDED] whenever a hold carried ANY silence of this
 *   turn past [BASE_MS] (without Smart Turn it would have ended there), even if the user then spoke
 *   again and the final silence ended at the base. [Action.EndActivity.longestSilenceMs] says how far.
 * - Without a working Smart Turn ([smartAvailable] false) it is silence only, [Reason.FALLBACK].
 * - **Except, for a caller that passes [finishedMs] below the base (the cascade, Kevin 2026-11-19,
 *   the web's rule):** a "done" verdict that lands before the base ends the turn at [finishedMs] of
 *   silence (or at once if that is already past), [Reason.FINISHED]. Without that verdict nothing
 *   changes. Live passes nothing and keeps the rule above.
 * - **The early end (2026-11-21, Pipecat lets the same model end turns):** with [finishedMs] and
 *   [earlyEnd] on (Settings > Assistant, default on), the FIRST verdict of a silence scoring >=
 *   [EARLY_THRESHOLD] that lands before [finishedMs] ends the turn at [EARLY_MS] of silence (or at
 *   once if that is already past), [Reason.EARLY]. On the A25 at 4 threads the first verdict lands at
 *   288-416 ms of silence (2026-11-20 cloud session, n=23), so this saves about 0.1-0.2 s against
 *   [FINISHED_MS]. The threshold sits above the two verdicts talked past in that session (0.94) and
 *   below the clear turns (0.97-0.98). The policy never sees words (the cloud's STT runs after the
 *   end), so the score alone guards a mid-sentence pause: a silence that already
 *   heard "not done" keeps the [finishedMs] rule however high a later check scores, and a verdict
 *   that lands at or after [finishedMs] (a slow phone) ends exactly as before.
 */
@Suppress("TooManyFunctions") // the fold, plus one small step per rule (the finished end added three)
internal class EndOfTurnPolicy(
    var smartAvailable: Boolean,
    /**
     * The silence that ends a turn when nothing holds it. [BASE_MS] for every Live session. The
     * laptop pipeline passes [CAP_MS] when its speech service ends turns itself (Kevin, 2026-11-10:
     * "since kyutai has end of turn built in lets use that first and ditch smart turn"): the phone's
     * silence end is then only the safety net.
     */
    private val baseMs: Int = BASE_MS,
    /** The silence that ends a turn Smart Turn called finished; at or above [baseMs] it is off. */
    private val finishedMs: Int = baseMs,
    /** The early end on top of [finishedMs]; the cascade passes its setting, Live nothing. */
    private val earlyEnd: Boolean = false,
) {

    enum class Reason(val wire: String) {
        BASE("base"), EXTENDED("extended"), CAP("cap"), FALLBACK("fallback"), FINISHED("finished"),
        EARLY("early"),
    }

    sealed interface Action {
        /** Speech confirmed; it began [onsetWindows] windows ago, counting this one. */
        data class StartActivity(val onsetWindows: Int) : Action
        /** Run Smart Turn on the turn as it stands; answer with [onCheckResult] carrying [checkId]. */
        data class RequestCheck(val checkId: Int, val silenceMs: Int) : Action
        /**
         * [silenceMs] is the silence since the user's last speech, the one that ended the turn;
         * [longestSilenceMs] is the longest silence inside the turn, which a hold may have stretched.
         */
        data class EndActivity(
            val reason: Reason,
            val silenceMs: Int,
            val scores: List<Score>,
            val longestSilenceMs: Int = silenceMs,
        ) : Action
        /** Speech came back during the silence; anything pending was cancelled. */
        data class SpeechResumed(val afterSilenceMs: Int) : Action
    }

    /** One Smart Turn answer: [score], asked at [askedAtMs] and back at [arrivedAtMs] of silence. */
    data class Score(val score: Float, val askedAtMs: Int, val arrivedAtMs: Int)

    private enum class Phase { IDLE, CANDIDATE, SPEAKING, SILENCE }

    private var phase = Phase.IDLE
    private var candidateWindows = 0
    private var silenceMs = 0
    private var hold = false
    private var anyResult = false

    /** Smart Turn said "done" before the base, and [finishedMs] is on: the silence that ends the turn. */
    private var finishAtMs: Int? = null
    // This turn: a hold carried a silence past the base at least once, and the longest silence seen.
    private var extendedThisTurn = false
    private var longestSilenceMs = 0
    private var checkId = 0
    private var inFlight: Int? = null
    private var inFlightAskedAt = 0
    private val scores = mutableListOf<Score>()

    val inActivity: Boolean get() = phase == Phase.SPEAKING || phase == Phase.SILENCE
    val currentSilenceMs: Int get() = if (phase == Phase.SILENCE) silenceMs else 0

    /** One Silero window. Returns what to do, in order (usually nothing). */
    fun onWindow(prob: Float): List<Action> = when (phase) {
        Phase.IDLE -> {
            if (prob >= START_PROB) {
                phase = Phase.CANDIDATE
                candidateWindows = 1
                confirmIfLongEnough()
            } else {
                emptyList()
            }
        }
        Phase.CANDIDATE -> {
            if (prob >= END_PROB) {
                candidateWindows++
                confirmIfLongEnough()
            } else {
                phase = Phase.IDLE
                candidateWindows = 0
                emptyList()
            }
        }
        Phase.SPEAKING -> {
            if (prob < END_PROB) {
                phase = Phase.SILENCE
                silenceMs = 0
                advanceSilence()
            } else {
                emptyList()
            }
        }
        Phase.SILENCE -> {
            if (prob >= START_PROB) {
                val was = silenceMs
                resetSilence()
                phase = Phase.SPEAKING
                listOf(Action.SpeechResumed(was))
            } else {
                advanceSilence()
            }
        }
    }

    /** Barge-in: the echo gate already confirmed speech, so the activity starts now. */
    fun forceStart(): Action? {
        if (inActivity) return null
        phase = Phase.SPEAKING
        candidateWindows = 0
        resetSilence()
        scores.clear()
        extendedThisTurn = false
        longestSilenceMs = 0
        return Action.StartActivity(0)
    }

    /** A Smart Turn answer. Stale ids (speech resumed, turn ended) are ignored. */
    @Suppress("ReturnCount") // stale, finished, then the extend-only rule
    fun onCheckResult(id: Int, score: Float): List<Action> {
        if (id != inFlight || phase != Phase.SILENCE) return emptyList()
        inFlight = null
        scores += Score(score, inFlightAskedAt, silenceMs)
        val first = !anyResult
        anyResult = true
        val done = score >= THRESHOLD
        if (done && finishedApplies(first)) return finish(clear = first && score >= EARLY_THRESHOLD)
        return when {
            // The verdict that matters for the base: only a "not done" before baseMs holds.
            first && !done && silenceMs < baseMs -> {
                hold = true
                listOf(request())
            }
            !hold -> emptyList()
            done && silenceMs < baseMs -> {
                hold = false
                emptyList()
            }
            done -> end(Reason.EXTENDED)
            else -> listOf(request())
        }
    }

    /** Smart Turn threw: silence only from here on. */
    fun onCheckFailed(id: Int): List<Action> {
        if (id == inFlight) inFlight = null
        smartAvailable = false
        if (phase == Phase.SILENCE && hold) {
            hold = false
            if (silenceMs >= baseMs) return end(Reason.FALLBACK)
        }
        return emptyList()
    }

    /** The caller ended the turn for its own reason (session closing); forget it. */
    fun reset() {
        phase = Phase.IDLE
        candidateWindows = 0
        resetSilence()
        scores.clear()
        extendedThisTurn = false
        longestSilenceMs = 0
    }

    private fun confirmIfLongEnough(): List<Action> {
        if (candidateWindows * WINDOW_MS < CONFIRM_MS) return emptyList()
        phase = Phase.SPEAKING
        val onset = candidateWindows
        candidateWindows = 0
        resetSilence()
        scores.clear()
        extendedThisTurn = false
        longestSilenceMs = 0
        return listOf(Action.StartActivity(onset))
    }

    private fun advanceSilence(): List<Action> {
        val before = silenceMs
        silenceMs += WINDOW_MS
        if (silenceMs > longestSilenceMs) longestSilenceMs = silenceMs
        if (hold && before < baseMs && silenceMs >= baseMs) extendedThisTurn = true
        val out = mutableListOf<Action>()
        if (crossedGate(before)) out += request()
        silenceEnd()?.let { out += end(it) }
        return out
    }

    /** A "done" now ends the turn at [finishedMs]: on, before the base, and the verdict that counts. */
    private fun finishedApplies(first: Boolean): Boolean =
        finishedMs < baseMs && silenceMs < baseMs && (first || hold)

    /** [clear]: the silence's first verdict, scoring high. It ends early only if it beat [finishedMs]. */
    private fun finish(clear: Boolean): List<Action> {
        hold = false
        val at = if (earlyEnd && clear && silenceMs < finishedMs) EARLY_MS else finishedMs
        finishAtMs = at
        return if (silenceMs >= at) end(finishReason(at)) else emptyList()
    }

    private fun finishReason(at: Int) = if (at < finishedMs) Reason.EARLY else Reason.FINISHED

    /** The silence end that applies now, or null while the turn goes on. */
    private fun silenceEnd(): Reason? {
        val at = finishAtMs
        return when {
            silenceMs >= CAP_MS -> Reason.CAP
            hold -> null
            at != null && silenceMs >= at -> finishReason(at)
            silenceMs < baseMs -> null
            extendedThisTurn -> Reason.EXTENDED
            smartAvailable -> Reason.BASE
            else -> Reason.FALLBACK
        }
    }

    /** This window crossed [GATE_MS] and the one check of this silence has not run yet. */
    private fun crossedGate(before: Int): Boolean {
        val crossed = before < GATE_MS && silenceMs >= GATE_MS
        return crossed && smartAvailable && inFlight == null && !anyResult
    }

    private fun request(): Action.RequestCheck {
        checkId++
        inFlight = checkId
        inFlightAskedAt = silenceMs
        return Action.RequestCheck(checkId, silenceMs)
    }

    private fun end(reason: Reason): List<Action> {
        val action = Action.EndActivity(reason, silenceMs, scores.toList(), maxOf(longestSilenceMs, silenceMs))
        phase = Phase.IDLE
        resetSilence()
        extendedThisTurn = false
        longestSilenceMs = 0
        return listOf(action)
    }

    private fun resetSilence() {
        silenceMs = 0
        hold = false
        finishAtMs = null
        anyResult = false
        inFlight = null
    }

    companion object {
        const val WINDOW_MS = 32
        const val START_PROB = 0.5f
        const val END_PROB = 0.35f
        const val CONFIRM_MS = 200
        const val GATE_MS = 192
        const val BASE_MS = 700

        /** The cascade's end after a Smart Turn "done" (the web's rule, 2026-11-19). */
        const val FINISHED_MS = 500
        const val CAP_MS = 2_500
        const val THRESHOLD = 0.5f

        /** A first verdict at or above this ends the cascade's turn at [EARLY_MS] (Pipecat ends on > 0.5). */
        const val EARLY_THRESHOLD = 0.95f

        /** The least silence an early end takes; a verdict landing sooner waits for it. */
        const val EARLY_MS = 300
    }
}
