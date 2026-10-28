package com.kevin.legion.service.turn

/**
 * When the user's turn starts and ends, as a pure fold over Silero windows (32 ms each) and Smart
 * Turn results. No clock, no threads: [TurnTaker] feeds it and acts on what it returns, and
 * `EndOfTurnPolicyTest` walks it window by window.
 *
 * **Smart Turn can only EXTEND a turn, never end one early (Kevin, 2026-10-27, after the A25 timing:
 * inference p95 393-819 ms at 2 threads).** The rule:
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
 * - Without a working Smart Turn ([smartAvailable] false) it is silence only, [Reason.FALLBACK].
 */
internal class EndOfTurnPolicy(var smartAvailable: Boolean) {

    enum class Reason(val wire: String) { BASE("base"), EXTENDED("extended"), CAP("cap"), FALLBACK("fallback") }

    sealed interface Action {
        /** Speech confirmed; it began [onsetWindows] windows ago, counting this one. */
        data class StartActivity(val onsetWindows: Int) : Action
        /** Run Smart Turn on the turn as it stands; answer with [onCheckResult] carrying [checkId]. */
        data class RequestCheck(val checkId: Int, val silenceMs: Int) : Action
        data class EndActivity(val reason: Reason, val silenceMs: Int, val scores: List<Score>) : Action
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
        return Action.StartActivity(0)
    }

    /** A Smart Turn answer. Stale ids (speech resumed, turn ended) are ignored. */
    fun onCheckResult(id: Int, score: Float): List<Action> {
        if (id != inFlight || phase != Phase.SILENCE) return emptyList()
        inFlight = null
        scores += Score(score, inFlightAskedAt, silenceMs)
        val first = !anyResult
        anyResult = true
        val done = score >= THRESHOLD
        return when {
            // The verdict that matters for the base: only a "not done" before BASE_MS holds.
            first && !done && silenceMs < BASE_MS -> {
                hold = true
                listOf(request())
            }
            !hold -> emptyList()
            done && silenceMs < BASE_MS -> {
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
            if (silenceMs >= BASE_MS) return end(Reason.FALLBACK)
        }
        return emptyList()
    }

    /** The caller ended the turn for its own reason (session closing); forget it. */
    fun reset() {
        phase = Phase.IDLE
        candidateWindows = 0
        resetSilence()
        scores.clear()
    }

    private fun confirmIfLongEnough(): List<Action> {
        if (candidateWindows * WINDOW_MS < CONFIRM_MS) return emptyList()
        phase = Phase.SPEAKING
        val onset = candidateWindows
        candidateWindows = 0
        resetSilence()
        scores.clear()
        return listOf(Action.StartActivity(onset))
    }

    private fun advanceSilence(): List<Action> {
        val before = silenceMs
        silenceMs += WINDOW_MS
        val out = mutableListOf<Action>()
        if (smartAvailable && before < GATE_MS && silenceMs >= GATE_MS && inFlight == null && !anyResult) {
            out += request()
        }
        when {
            silenceMs >= CAP_MS -> out += end(Reason.CAP)
            silenceMs >= BASE_MS && !hold -> {
                out += end(if (smartAvailable) Reason.BASE else Reason.FALLBACK)
            }
        }
        return out
    }

    private fun request(): Action.RequestCheck {
        checkId++
        inFlight = checkId
        inFlightAskedAt = silenceMs
        return Action.RequestCheck(checkId, silenceMs)
    }

    private fun end(reason: Reason): List<Action> {
        val action = Action.EndActivity(reason, silenceMs, scores.toList())
        phase = Phase.IDLE
        resetSilence()
        return listOf(action)
    }

    private fun resetSilence() {
        silenceMs = 0
        hold = false
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
        const val CAP_MS = 2_500
        const val THRESHOLD = 0.5f
    }
}
