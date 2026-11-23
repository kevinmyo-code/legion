package com.kevin.legion.service

/**
 * The two fixed phrases that open and close a conversation (Kevin, 2026-09-10).
 *
 * **"Hey <companion name>" wakes. "That will be all" sleeps.** Kevin tried a fixed pop-culture
 * wake word ("Excelsior", 2026-09-10) and dropped it 2026-10-03: *"just keep hey alfred."* On the
 * A25 "excelsior" never fired, under Vosk alone (11:40-12:33) or the two-stage spotter (12:34:01,
 * VAD on, no KWS hit), while "hey alfred" fired first try (12:34:13: KWS hit, confirm accept,
 * Gemini opened). The dismissal "that will be all" is unchanged and suits the register band
 * (CLAUDE.md sec 1).
 *
 * **The two halves are NOT symmetrical, and cannot be.** This is the fact that shapes the whole
 * file, so it is stated before the code rather than discovered later:
 *
 * - **Wake is deterministic, in Vosk.** [WakeWordEngine] holds the microphone while the app is
 *   idle, so a grammar phrase is exactly the right mechanism and the match never involves a model.
 * - **Sleep CANNOT be a Vosk phrase.** [MicArbiter] grants the microphone to one claimant at a
 *   time, and `LIVE_TURN` outranks `WAKE_WORD` - the moment a conversation starts, the wake engine
 *   is preempted and releases its capture. During the only window in which "that will be all"
 *   means anything, **Vosk is not listening at all.** A sleep phrase in [grammar] would be dead
 *   code that reads like a feature, which is precisely the failure `WakeWordEngine`'s own history
 *   keeps recording ("hey moose", and the empty-grammar refusal).
 *
 * So sleep runs on the transcript instead, and it runs twice on purpose:
 *
 * 1. **The model's own [LiveToolbox] `end_conversation` tool** is the primary path and the one
 *    that sounds right - it lets the companion answer "Very good, sir" before the socket closes,
 *    which is the entire charm of saying "that will be all" to a butler. Its description names the
 *    phrase explicitly.
 * 2. **[isSleepPhrase] is the deterministic backstop**, matched against the same accumulated
 *    `inputAudioTranscription` text [com.kevin.legion.ai.CrisisDetector] already runs on. It
 *    exists because path 1 is the model choosing to obey, and a dismissal that works only when the
 *    model feels like it is not a dismissal.
 *
 * **The backstop ARMS, it does not fire.** It sets the same `dismissAfterTurn` flag the tool sets,
 * consumed at the next `TurnComplete`. Stopping the socket the instant the phrase is recognised
 * would cut the sign-off off mid-word - the reasoning is already written out at
 * `LiveSessionController.dismissAfterTurn` and applies here unchanged. Both paths setting one
 * idempotent boolean is also why they cannot fight: if the model calls the tool AND the transcript
 * matches, that is one dismissal, not two.
 */
object WakePhrases {

    /**
     * The sleep phrase, in the normalised form [normalise] produces, so
     * [isSleepPhrase] compares like with like.
     */
    const val SLEEP = "that will be all"

    /**
     * "That'll be all" after [normalise] has stripped the apostrophe. Kept as a second literal
     * rather than as a regex: this is the one contraction a transcript realistically produces for
     * [SLEEP], and two constants read better than a pattern nobody can eyeball.
     */
    private const val SLEEP_CONTRACTED = "that ll be all"

    /**
     * The Vosk grammar: exactly one phrase, "hey <companion name>", lowercase because Vosk
     * grammars and its results both are. The two-word form is the weak-name guardrail from
     * custom-wake-word ticket 07 (2026-07-19 field data: a bare short name false-triggers on
     * ordinary conversation, radio and podcasts).
     *
     * **A blank [companionName] yields an EMPTY list, deliberately** (wake-word ticket 09):
     * there is no phrase that does not depend on a name, so [WakeWordEngine.start] and
     * [WakeKeywords.build] refuse rather than listen forever for a phrase nobody can say.
     */
    fun grammar(companionName: String): List<String> {
        val name = companionName.trim().lowercase()
        return if (name.isBlank()) emptyList() else listOf("hey $name")
    }

    /**
     * Whether [transcript] ends with the sleep phrase.
     *
     * **Ends with, not contains.** "That will be all I need from the pantry" contains the phrase
     * and is plainly not a dismissal. Anchoring at the end is what separates saying the phrase
     * from using those words in a sentence, and it is safe to anchor here because the caller
     * evaluates the COMPLETE turn transcript once at `turnComplete`, never the partial buffer
     * mid-utterance - a per-delta check would match the moment the buffer happened to end on
     * "...that will be all" and then never un-match, since arming is sticky by design.
     *
     * A trailing "please", "thanks" or "for now" IS set aside (Kevin, 2026-11-23: "that'll be all"
     * and "bye bye" sometimes did not close the conversation). It used to be left to the model, on
     * the reasoning that a missed backstop costs one more turn while a false one hangs up on someone
     * mid-sentence. The field answered that: the model is the unreliable half, and the courtesy is
     * only ever set aside at the ENDS of the turn, so the anchoring above is untouched and a request
     * wrapped in courtesy ("that's all the milk we need") still matches nothing.
     *
     * [afterQuestion]: the last line he heard asked him something, so "that's it" or "never mind"
     * may be his answer; only a [HARD_STOPS] phrase ends the conversation in code, the rest go to the
     * model (2026-11-18 review: "That's it." said as a yes ended the conversation without acting).
     */
    fun isSleepPhrase(transcript: String, companionName: String = "", afterQuestion: Boolean = false): Boolean {
        val t = normalise(transcript)
        val name = normalise(companionName)
        return when {
            t.isEmpty() -> false
            // A farewell said as the whole turn ([Farewells], 2026-11-20: "bye bye" got "night" back).
            // While an answer is awaited, a turn with an answer word in it is his answer (F1).
            afterQuestion -> !Farewells.answersQuestion(transcript) &&
                (isStopPhrase(t, name, HARD_STOPS) || Farewells.isFarewell(transcript, companionName, true))
            else -> t.endsWith(SLEEP) || t.endsWith(SLEEP_CONTRACTED) || isStopPhrase(t, name, STOP_PHRASES) ||
                Farewells.isFarewell(transcript, companionName)
        }
    }

    /**
     * The stop and goodbye phrases (2026-11-18: goodbye ends the conversation, as in the dashboard
     * voice app), in [normalise]d form. Unlike [SLEEP] these must be the WHOLE turn: "stop the music"
     * and "that's all I need from the pantry" are requests.
     */
    val STOP_PHRASES: Set<String> = setOf(
        "stop", "stop stop", "never mind", "nevermind", "that s all", "that s it", "that s all thanks",
        "that s it thanks", "that will be all", "that ll be all", "go away", "stand down", "bye", "bye bye",
        "goodbye", "good bye", "thanks bye", "thank you bye", "thanks goodbye", "ok bye", "okay bye",
        "we re done", "i m done",
    )

    /** The stops that are never an answer to a question: these end the conversation even after one. */
    val HARD_STOPS: Set<String> = setOf("stop", "goodbye", "good bye", "bye", "bye bye", "go away", "stand down")

    /**
     * [t] (normalised) is a stop phrase once the courtesy around it is set aside
     * ([Farewells.withoutCourtesy]: the companion's [name] at either end, lead-ins and thanks before,
     * thanks and tails after): "Goodbye, Alfred", "Alfred, stop", "ok bye", "Thanks, Alfred, that's all",
     * "okay, that'll be all, thanks Alfred", "that's all for now".
     *
     * **A trailing courtesy used to miss**, and said so in words in [isSleepPhrase]'s comment and its
     * test: only leading words were set aside, so "that'll be all, thanks" matched no phrase and the
     * close rested on the model calling `end_conversation`. Kevin, 2026-11-23: a goodbye with filler
     * around it must close. What makes it safe is unchanged: courtesy is only ever set aside at the
     * ends, and what is left must BE a phrase, so "that's all the milk we need" and "text mom bye bye"
     * still match nothing.
     */
    private fun isStopPhrase(t: String, name: String, phrases: Set<String>): Boolean {
        if (t in phrases) return true
        val words = Farewells.withoutCourtesy(t.split(' ').filter { it.isNotEmpty() }, name)
        return words.joinToString(" ") in phrases
    }

    /**
     * Lowercase, every non-letter/digit run collapsed to a single space, trimmed: [Farewells.normalise],
     * the one copy. Punctuation is flattened rather than deleted so "that will be all." and
     * "that'll be all" both reduce to something [isSleepPhrase]'s two literals can match, and so a
     * transcript's stray commas cannot break an otherwise exact phrase.
     */
    private fun normalise(text: String): String = Farewells.normalise(text)
}
