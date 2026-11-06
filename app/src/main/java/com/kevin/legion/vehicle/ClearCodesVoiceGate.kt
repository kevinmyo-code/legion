package com.kevin.legion.vehicle

/**
 * The voice half of `clear_codes`' confirm gate: the user's own words must say yes before a
 * confirmed call may send Mode 04 (2026-11-05).
 *
 * **Why the `confirmed` boolean alone was not a gate.** The model sets it. On 2026-11-05 at about
 * 20:18 CST, with the Jeep's dongle connected, Kevin asked to clear the codes; `clear_codes
 * (confirmed=false)` recited the warning (freeze frame, readiness monitors) and asked. His next
 * line was "Can you try it again? It's weird." and the model called `clear_codes(confirmed=true)`.
 * His actual "Yeah, yeah, do it." only arrived after. Wiping readiness monitors is not reversible,
 * so "the model decided that was a yes" is not consent.
 *
 * **The gate.** The unconfirmed call that ends in the confirm question records a pending ask, with
 * the user's words from that turn. A confirmed call is refused, and nothing is sent, unless:
 *  1. an ask is pending and younger than [TTL_MS];
 *  2. the words of the turn making the confirmed call are not the asking turn's words (the answer
 *     has to come after the question);
 *  3. those words are a plain yes ([isExplicitYes]).
 * Words that cannot be heard (an empty transcript) are not a yes. The confirmed call consumes the
 * ask whatever it then finds, so one yes buys one clear.
 *
 * Voice only. The Fleet screen's STORED CODES dialog is its own confirmation (a button the person
 * presses) and calls [DtcClearController.dispatchAndRecord] directly, untouched by this. Pure and
 * clock-injected, so `ClearCodesVoiceGateTest` needs no Context.
 */
class ClearCodesVoiceGate(private val clock: () -> Long = System::currentTimeMillis) {

    private data class Ask(val atMs: Long, val askTurnWords: String)

    @Volatile private var pending: Ask? = null

    /** The unconfirmed call just put the confirm question to the user, in the turn whose words were [userText]. */
    fun asked(userText: String?) {
        pending = Ask(clock(), normalise(userText))
    }

    /** A clear can no longer be pending: nothing to clear, the car did not answer, or the user moved on. */
    fun reset() {
        pending = null
    }

    /**
     * Decides a `confirmed=true` call. Null means it may go ahead (and the ask is consumed); a
     * string is the refusal to return, in words that say nothing was sent and what to do instead.
     */
    fun checkConfirmed(userText: String?): String? {
        val ask = pending
        val words = normalise(userText)
        val refusal = when {
            ask == null || clock() - ask.atMs > TTL_MS -> NO_QUESTION
            words.isEmpty() -> UNHEARD
            words == ask.askTurnWords -> NOT_ANSWERED_YET
            !isExplicitYes(userText.orEmpty()) -> notAYes(userText.orEmpty().trim())
            else -> null
        }
        if (refusal == null || refusal == NO_QUESTION) pending = null
        return refusal
    }

    companion object {
        /** How long a confirm question stays answerable. Long enough for a slow answer, short enough
         * that a yes about something else ten minutes later cannot reach back to it. */
        const val TTL_MS = 3 * 60_000L

        const val ASK_AND_WAIT =
            "Say this question and stop. Call clear_codes with confirmed=true only when the user's next " +
                "words are a plain yes to it. A question, \"try again\", or anything but yes is not a yes."

        const val NO_QUESTION =
            "Nothing was cleared: no clear question is waiting for an answer. Call clear_codes with " +
                "confirmed=false first, say its warning, and wait for a yes."

        const val UNHEARD =
            "Nothing was cleared: I could not hear the user's answer. Ask again whether to clear the " +
                "codes, and wait for a yes."

        const val NOT_ANSWERED_YET =
            "Nothing was cleared: the user has not answered the clear question yet. Ask it and wait."

        fun notAYes(words: String) =
            "Nothing was cleared: \"$words\" is not a yes to clearing. Ask again whether to clear the " +
                "codes, which wipes the freeze frame and the readiness monitors, and wait for a yes."

        private val YES_WORDS = setOf(
            "yes", "yeah", "yep", "yup", "yea", "ya", "sure", "ok", "okay", "affirmative",
            "absolutely", "definitely", "please", "correct",
        )
        private val YES_PHRASES = listOf(
            "do it", "go ahead", "go for it", "clear it", "clear them", "clear em", "send it",
        )
        private val NOT_YES_WORDS = setOf(
            "no", "nope", "nah", "not", "don't", "dont", "never", "stop", "wait", "hold", "cancel",
            "later", "actually", "try", "again", "retry", "maybe", "why", "what", "how",
        )
        private val QUESTION_OPENERS = setOf("can", "could", "would", "will", "should", "is", "are", "did", "does")

        /**
         * True only for a plain yes: at least one yes word or phrase, no word that hedges, negates
         * or asks for something else, no question mark, and not phrased as a question. Errs to
         * false - a refused yes costs the user one more "yes", a wrong one costs readiness monitors.
         */
        fun isExplicitYes(text: String): Boolean {
            val tokens = tokens(text)
            val joined = " ${tokens.joinToString(" ")} "
            val askedBack = tokens.firstOrNull() in QUESTION_OPENERS ||
                (tokens.firstOrNull() == "do" && tokens.getOrNull(1) in setOf("you", "we", "i"))
            val hedged = text.contains('?') || askedBack || tokens.any { it in NOT_YES_WORDS }
            val saysYes = tokens.any { it in YES_WORDS } || YES_PHRASES.any { joined.contains(" $it ") }
            return saysYes && !hedged
        }

        private fun tokens(text: String): List<String> =
            text.lowercase().replace('’', '\'').replace(Regex("[^a-z' ]"), " ")
                .split(' ').map { it.trim('\'') }.filter { it.isNotEmpty() }

        private fun normalise(text: String?): String = tokens(text.orEmpty()).joinToString(" ")
    }
}
