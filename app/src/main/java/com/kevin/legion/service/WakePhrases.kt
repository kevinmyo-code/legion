package com.kevin.legion.service

/**
 * The two fixed phrases that open and close a conversation (Kevin, 2026-09-10).
 *
 * **"Excelsior" wakes. "That will be all" sleeps.** Kevin asked for a summon/dismiss pair out of
 * pop culture rather than the companion's own name: *"instead of hey x name, we have a secret
 * phrase for waking and stopping voice."* The pair he chose is Stan Lee's sign-off for the summon
 * and the canonical butler's dismissal for the release, which suits a roster whose register band
 * is Alfred/JARVIS (CLAUDE.md sec 1).
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
     * The wake phrase, lowercase because Vosk grammars and its results both are.
     *
     * **A single word, against [WakeWordEngine]'s own two-word rule** (custom-wake-word ticket 07,
     * 2026-07-19 field data: a bare word false-triggers on ordinary conversation, radio and
     * podcasts). The exemption is specific rather than a relaxation of the rule: that finding was
     * about *common short names*, and this is a four-syllable Latinate word that essentially never
     * occurs in speech. The rule's purpose - do not put something the room says all day into the
     * grammar - is satisfied by the word itself instead of by a "hey" prefix.
     */
    const val WAKE = "excelsior"

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
     * The Vosk grammar: the fixed [WAKE] phrase, plus "hey <companion name>" retained behind it.
     *
     * **Retaining the old phrase is deliberate and is meant to be temporary.** Kevin asked for the
     * fixed phrase *instead of* "hey <name>", and dropping it is a one-line change here. It is
     * still in the list because the small Vosk model (`vosk-model-small-en-us-0.15`) compiles its
     * lexicon into the binary FSTs under `graph/`, with no readable word list, so **there is no way
     * to confirm off the device that "excelsior" is a word this model can recognise at all.** If
     * it is not, a
     * grammar containing only [WAKE] listens forever for a phrase nobody can say - the exact
     * silent failure `WakeWordEngine.buildTargetWords` refuses an empty grammar to avoid, and one
     * that would look identical to the wake word simply being broken.
     *
     * Once Kevin confirms on the phone that "excelsior" fires, delete the second entry. Until
     * then a known-working phrase sits behind the new one and costs nothing but a grammar slot.
     *
     * A blank [companionName] yields a list of just [WAKE] rather than a blank second entry;
     * `buildTargetWords` no longer has to treat "no name" as "no grammar", because there is now
     * always a phrase that does not depend on a name.
     */
    fun grammar(companionName: String): List<String> {
        val words = linkedSetOf(WAKE)
        val name = companionName.trim().lowercase()
        if (name.isNotBlank()) words.add("hey $name")
        return words.toList()
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
