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
     * A trailing "please", "thanks" or "for now" therefore does NOT match. That is the wrong side
     * to err on for a hard-stop backstop, but the right one for a backstop that sits behind a
     * model already told to recognise all of those (`end_conversation`'s description): a missed
     * backstop costs one more turn, a false one hangs up on someone mid-sentence.
     */
    fun isSleepPhrase(transcript: String): Boolean {
        val t = normalise(transcript)
        if (t.isEmpty()) return false
        return t.endsWith(SLEEP) || t.endsWith(SLEEP_CONTRACTED)
    }

    /**
     * Lowercase, every non-letter/digit run collapsed to a single space, trimmed. Punctuation is
     * flattened rather than deleted so "that will be all." and "that'll be all" both reduce to
     * something [isSleepPhrase]'s two literals can match, and so a transcript's stray commas
     * cannot break an otherwise exact phrase.
     */
    private fun normalise(text: String): String =
        text.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("").trim().replace(Regex("\\s+"), " ")
}
