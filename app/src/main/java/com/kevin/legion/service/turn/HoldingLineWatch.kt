package com.kevin.legion.service.turn

/**
 * A model turn that is only a holding line, with nothing behind it to wait for (async-lookups
 * ticket 07). Three shapes from ticket 04 run 3 (A25, gemini-3.8-live, 2026-10-28):
 *  - F1: an `ask_fleet` result went back `WHEN_IDLE` 0.7 s after a silent tool-call turn; the
 *    model's next turn said only "Checking." and ended. The answer never came.
 *  - F2: the result went back, the user said "what?" before the model spoke, the model said "Let me
 *    look." and nothing more.
 *  - 11:47:13 (run 3c): a general question; the model said "Checking." and ended the turn with NO
 *    tool call and no lookup pending. There was nothing to check.
 * Each time the 8 s follow-up window then closed the conversation, so the user lost the answer
 * AND the conversation.
 *
 * [onTurnComplete] classifies each completed model turn:
 *  - [Outcome.LATE_HOLDING]: a result was sent, and the first turn that STARTED after it is a
 *    holding line ([isHoldingLine]) with no new tool behind it. Once per result.
 *  - [Outcome.EMPTY_HOLDING]: no result pending, no tool called in the turn, no lookup in flight,
 *    and the turn is a holding line. Once, until a turn with substance or a tool call.
 *  - [Outcome.ANSWERED]: a turn with substance (or a tool call) after either of the above, or any
 *    substantive turn after a result: the follow-up window's hold is released.
 *  - [Outcome.NONE]: everything else, including the ordinary holding line a result arrived during
 *    (it started before the result; the answer is the NEXT turn) and a turn with no speech.
 * The session nudges on the two holding outcomes and holds the follow-up window open
 * ([com.kevin.legion.service.FollowUpWindow.Event.HoldingLineTurn]) until [Outcome.ANSWERED] or the
 * window's own cap. Pure and clock-free, so every rule is a JVM test.
 */
internal class HoldingLineWatch {
    enum class Outcome { NONE, LATE_HOLDING, EMPTY_HOLDING, ANSWERED }

    private var tool: String? = null
    private var sentAtMs = 0L
    private var nudgedForResult = false
    private var emptyHold = false

    /** The tool whose `WHEN_IDLE` result was just sent; the newest result replaces an older one. */
    @Synchronized
    fun onAsyncResultSent(name: String, atMs: Long) {
        tool = name
        sentAtMs = atMs
        nudgedForResult = false
    }

    /** The tool whose result is still waiting for an answer, if any. */
    @Synchronized
    fun pendingTool(): String? = tool

    /**
     * A model turn completed. [turnStartedAtMs] is its first audio (0 for a turn with none); [said]
     * is its output transcript, blank when there was none or captions are off (speech with no
     * transcript cannot be classified and counts as substance, so a session with captions off is
     * never held open for a word it cannot read). [toolCalled] is whether the model called any tool
     * in this turn; [lookupInFlight] whether any tool call is still unanswered.
     */
    @Synchronized
    fun onTurnComplete(turnStartedAtMs: Long, said: String, toolCalled: Boolean, lookupInFlight: Boolean): Outcome {
        val spoke = turnStartedAtMs > 0L
        val holding = spoke && said.isNotBlank() && isHoldingLine(said)
        val busy = toolCalled || lookupInFlight
        return when {
            tool != null -> afterResult(turnStartedAtMs, holding, busy)
            emptyHold -> afterEmptyHold(answered = toolCalled || (spoke && !holding))
            holding && !busy -> Outcome.EMPTY_HOLDING.also { emptyHold = true }
            else -> Outcome.NONE
        }
    }

    private fun afterEmptyHold(answered: Boolean): Outcome =
        if (answered) Outcome.ANSWERED.also { emptyHold = false } else Outcome.NONE

    private fun afterResult(turnStartedAtMs: Long, holding: Boolean, busy: Boolean): Outcome = when {
        turnStartedAtMs <= 0L || turnStartedAtMs < sentAtMs -> Outcome.NONE
        !holding -> Outcome.ANSWERED.also { clear() }
        busy || nudgedForResult -> Outcome.NONE
        else -> Outcome.LATE_HOLDING.also { nudgedForResult = true }
    }

    @Synchronized
    fun clear() {
        tool = null
        sentAtMs = 0L
        nudgedForResult = false
        emptyHold = false
    }

    companion object {
        private const val MAX_WORDS = 5
        private val HOLDING_WORDS = listOf("check", "look", "moment", "second")
        private val WORD = Regex("[\\p{L}\\p{N}']+")

        /**
         * A holding line, not an answer: at most [MAX_WORDS] words, no digit, and one of "check",
         * "look", "moment", "second" inside a word ("Checking." counts). The run's real shapes:
         * "Checking." and "Let me look." are holding lines; "No codes." is an answer. Known gap,
         * kept on purpose: the prompt's own six-word example "Give me a second with that." is over
         * the cap and reads as an answer.
         */
        fun isHoldingLine(said: String): Boolean {
            val words = WORD.findAll(said).map { it.value.lowercase() }.toList()
            return words.size in 1..MAX_WORDS &&
                said.none { it.isDigit() } &&
                words.any { w -> HOLDING_WORDS.any { w.contains(it) } }
        }

        /** The system note for [Outcome.LATE_HOLDING], framed like [StillCheckingNudge.instruction]. */
        fun lateInstruction(tool: String): String =
            "(System note, not spoken by the user: the result of `$tool` has already arrived and " +
                "is in front of you. Answer the user's question from it now, with no holding line.)"

        /** The system note for [Outcome.EMPTY_HOLDING]. */
        const val EMPTY_INSTRUCTION =
            "(System note, not spoken by the user: you said you would check, but you called no " +
                "tool and nothing is running. Either call the tool you meant now, or answer the " +
                "question directly, or say plainly that you have no tool for it.)"
    }
}
