package com.kevin.midnightai.service

/**
 * Whether the driver's whole turn was a goodbye, so the conversation ends on their words instead
 * of on the idle timer or a tap.
 *
 * **Why the head unit needs this more than the phone does.** Talk is push-to-talk only (wake word
 * frozen-removed 2026-10-11), so every way of ending a conversation before this cost a hand on the
 * screen or ten seconds of silence. "That'll be all" is the hands-free close.
 *
 * **Shaped on [com.kevin.midnightai.ai.CrisisDetector], ruled by the phone.** The form is the
 * crisis backstop's: a pure object, phrase data, one normaliser, no Android. The RULE is the
 * phone's (`app/.../service/WakePhrases.isSleepPhrase` and `Farewells`), ported as data plus the
 * two rules it decides with, because this is the same capability and a second reading of "did he
 * say goodbye" would drift from it. What it is NOT is a substring scan: "text mom bye bye" and
 * "say good night to the kids" are requests, and a `contains` check closes on both. The turn is
 * stripped of courtesy at its ENDS and what is left must decompose entirely into closers.
 *
 * **Only the phone's strict branch is ported, deliberately.** There the closer set narrows when
 * Alfred has just asked something, which the phone knows from its own pending-draft and
 * question state. The unit has neither: the confirm state for `activate_garage` and
 * `log_past_service` lives in the model, not in code, so a lenient branch here would be a
 * condition nothing can evaluate. Running the strict set always is the safe side of that gap. The
 * cost, named so nobody reads it as a bug: a bare "night", "nite" or "later" does not close
 * (they answer "morning or night?"), and neither do the soft dismissals the phone left soft
 * ("never mind", "we're done", "I'm done").
 *
 * **Nothing gets sent on the way out** (the phone's F1, and ruling A's other half). A turn holding
 * a word that answers a question ([ANSWER_WORDS]) or a number is an answer, in every state: "that'll
 * be all" closes, "yes, that'll be all" goes to the model so the work it authorises still runs.
 * A filler lead-in is courtesy and vetoes nothing, which is why "okay bye" closes (Kevin, Q9).
 *
 * **It arms, it does not fire.** [armsDismissal] is read at the END of the model's turn, so Moose's
 * sign-off is already spoken and still draining from the jitter buffer when the conversation parks.
 * Matching mid-utterance would be worse than useless: the transcript only grows within a turn, so a
 * partial that happened to end on "bye" would match and arming is sticky by design.
 */
object DriverDismissal {

    /**
     * Goodbyes. "night", "nite" and "later" are absent on purpose: alone they answer a question at
     * least as often as they end one, and this file has no way to tell the two apart (see the class
     * doc). "good night" and "night night" are phrases in their own right and close either way.
     */
    val FAREWELLS: List<String> = listOf(
        "bye", "bye bye", "byebye", "buh bye", "bye now", "goodbye", "good bye", "bye for now",
        "good night", "goodnight", "night night", "nighty night", "nite nite", "g night",
        "see you", "see ya", "see you later", "see ya later", "see you tomorrow", "see you soon",
        "talk to you later", "talk later", "talk soon", "catch you later", "ciao", "cheerio",
        "farewell", "peace out", "take care", "have a good night", "have a good one",
    )

    /** A word for stop or goodbye: never an answer to anything, whatever the model just asked. */
    val PLAIN_STOPS: List<String> = listOf(
        "stop", "goodbye", "good bye", "bye", "bye bye", "go away", "stand down",
    )

    /**
     * The four dismissals of Kevin's ruling A: these close even while an answer is awaited. In
     * [normalise]d form, where an apostrophe is a space, so "that'll" is "that ll".
     */
    val DISMISSALS: List<String> = listOf(
        "that will be all", "that ll be all", "that s all", "that s it",
    )

    /**
     * Lead-ins that open a turn without answering anything: pure discourse filler. One alone never
     * makes a goodbye into an answer (Kevin, Q9: "okay bye" closes). They come off at the ends with
     * the rest of the courtesy and veto nothing. "right" sits here rather than in [ASSENT_LEADS]
     * because "all right" reduces to words containing "right", and [answersQuestion] looks for a
     * word anywhere in the turn.
     */
    val FILLER_LEADS: List<String> = listOf(
        "ok", "okay", "alright", "all right", "well", "oh", "so", "hey", "um", "uh", "right",
        "okay then", "ok then", "please",
    )

    /**
     * Lead-ins that assent. These stay in [ANSWER_WORDS]: they can authorise the thing Moose just
     * asked about (the garage relay's second call, a backfilled service mileage), and dropping one
     * loses the work.
     */
    val ASSENT_LEADS: List<String> = listOf(
        "yeah", "yes", "great", "cool", "perfect", "awesome", "lovely", "sounds good", "got it",
    )

    val THANKS: List<String> = listOf(
        "thanks", "thank you", "thanks a lot", "thank you very much", "thanks so much", "cheers",
        "appreciate it", "many thanks",
    )

    val TAILS: List<String> = listOf("for now", "then", "now", "again", "mate", "buddy", "please")

    /**
     * Words that answer a question, plus any number. A turn holding one is an answer, not a
     * goodbye, in every state. This is what keeps a dismissal from swallowing an authorisation.
     * [FILLER_LEADS] are deliberately NOT in it (Kevin, Q9).
     */
    val ANSWER_WORDS: List<String> = ASSENT_LEADS + listOf(
        "yep", "yup", "no", "nope", "nah", "sure", "go ahead", "do it", "fine", "correct",
        "exactly", "tomorrow", "today", "tonight", "morning", "afternoon", "evening", "am", "pm",
        "o clock",
    )

    private val leadWords = phraseWords(FILLER_LEADS + ASSENT_LEADS + THANKS)
    private val tailWords = phraseWords(THANKS + TAILS)
    private val answerWords = phraseWords(ANSWER_WORDS)

    /** Every phrase that can stand alone as a close. */
    private val CLOSERS: List<List<String>> =
        phraseWords(PLAIN_STOPS + DISMISSALS + FAREWELLS)

    /**
     * The closers that may close a running trivia game: the plain stops and the goodbyes, never the
     * four [DISMISSALS]. "That's it!" is how a driver or passenger shouts a right answer, and a
     * quiz ended by an exclamation is the one false close this file could plausibly hit in a car.
     * A goodbye still ends it, and so does the tap.
     */
    private val TRIVIA_CLOSERS: List<List<String>> = phraseWords(PLAIN_STOPS + FAREWELLS)

    /**
     * [transcript] is a goodbye and nothing else. [companionName] comes off either end, so "bye
     * Moose" and "Moose, stop" count whatever the driver renamed him to.
     */
    fun isDismissal(
        transcript: String,
        companionName: String = "",
        inTriviaGame: Boolean = false,
    ): Boolean {
        val t = normalise(transcript)
        if (t.isEmpty()) return false
        if (answersQuestion(t)) return false
        val left = withoutCourtesy(t.split(' ').filter { it.isNotEmpty() }, normalise(companionName))
        return left.isNotEmpty() && madeOf(left, if (inTriviaGame) TRIVIA_CLOSERS else CLOSERS)
    }

    /**
     * Whether this completed turn ends the conversation.
     *
     * The three session flags are the gate, and which sessions they let through is the point.
     * [keepWarm] is true only on the tap-driven chat ([LiveSessionController]); conversational
     * onboarding runs the same session class with [keepWarm] false, and there "that's it" or a
     * cheerful "bye" mid-questionnaire would park the socket and surface to
     * `ui/OnboardingManager` as a failed first run. A speak-only proactive line has no [vadMode]
     * and nobody to hear it.
     */
    fun armsDismissal(
        heard: String,
        companionName: String,
        vadMode: Boolean,
        keepWarm: Boolean,
        conversationActive: Boolean,
        inTriviaGame: Boolean,
    ): Boolean =
        vadMode && keepWarm && conversationActive &&
            isDismissal(heard, companionName, inTriviaGame)

    /**
     * Lowercase, every non-letter/digit run collapsed to one space, trimmed. Punctuation is
     * flattened rather than deleted, so "Bye-bye." and "bye bye" are the same turn and an
     * apostrophe becomes a space ("that'll" -> "that ll", "that's" -> "that s"), which is the form
     * [DISMISSALS] is written in. The curly apostrophe transcripts often carry is not a letter
     * either, so it normalises identically with no special case.
     *
     * This is the phone's `Farewells.normalise`, NOT `CrisisDetector.normalize`, which deletes an
     * apostrophe instead ("thatll"). Phone phrase data read through the other normaliser silently
     * matches nothing, which is why the two live together in one file here.
     */
    fun normalise(text: String): String =
        text.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("").trim().replace(Regex("\\s+"), " ")

    /** [transcript] holds a word that answers a question, or a number. */
    fun answersQuestion(transcript: String): Boolean {
        val w = normalise(transcript).split(' ').filter { it.isNotEmpty() }
        if (w.any { word -> word.any { it.isDigit() } }) return true
        return answerWords.any { phrase -> w.windowed(phrase.size).any { it == phrase } }
    }

    /**
     * [words] without the companion's [name] at either end, lead-ins and thanks before, thanks and
     * tails after, repeated until nothing more comes off. Never the whole turn: a courtesy word
     * alone ("thanks") is a turn of its own and closes nothing.
     *
     * Courtesy is only ever set aside at the ENDS. That is what keeps the decomposition honest:
     * "that's all the coolant it needs" and "text mom bye bye" have a non-closer in the middle and
     * match nothing.
     */
    fun withoutCourtesy(words: List<String>, name: String): List<String> {
        val nameWords = name.split(' ').filter { it.isNotEmpty() }
        var w = words
        while (true) {
            val before = w
            if (nameWords.isNotEmpty() && w.take(nameWords.size) == nameWords) w = w.drop(nameWords.size)
            if (nameWords.isNotEmpty() && w.size >= nameWords.size && w.takeLast(nameWords.size) == nameWords) {
                w = w.dropLast(nameWords.size)
            }
            leadWords.firstOrNull { it.size < w.size && w.take(it.size) == it }?.let { w = w.drop(it.size) }
            tailWords.firstOrNull { it.size < w.size && w.takeLast(it.size) == it }?.let { w = w.dropLast(it.size) }
            if (w == before || w.isEmpty()) return w
        }
    }

    /**
     * [w] is one or more of [phrases] back to back and nothing else, so "that's it, bye bye" and
     * "goodbye good night" are each a whole turn of closers while "bye bye birdie" is not. Two ways
     * of saying one thing in one turn is still one dismissal.
     */
    fun madeOf(w: List<String>, phrases: List<List<String>>): Boolean {
        val ok = BooleanArray(w.size + 1)
        ok[0] = true
        for (i in w.indices) {
            if (!ok[i]) continue
            for (f in phrases) {
                if (i + f.size <= w.size && w.subList(i, i + f.size) == f) ok[i + f.size] = true
            }
        }
        return ok[w.size]
    }

    /** [phrases] as word lists, longest first, so "thanks a lot" is taken whole before "thanks". */
    private fun phraseWords(phrases: Collection<String>): List<List<String>> =
        phrases.distinct().map { it.split(' ') }.sortedByDescending { it.size }
}
