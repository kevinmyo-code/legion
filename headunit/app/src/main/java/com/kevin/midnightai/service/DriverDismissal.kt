package com.kevin.midnightai.service

/**
 * Whether the driver's whole turn was a goodbye, so the conversation ends on their words instead
 * of on the idle timer or a tap.
 *
 * **Why the head unit needs this more than the phone does.** Talk is push-to-talk only (wake word
 * frozen-removed 2026-10-11), so every way of ending a conversation before this cost a hand on the
 * screen or ten seconds of silence. "That'll be all" is the hands-free close.
 *
 * **It is the phone's rule, not a second reading of it (ruled 2026-11-24).** The two voices behave
 * the same and only the character differs, so this file is a port of the phone's goodbye pair
 * (`app/.../service/Farewells.kt` and `WakePhrases.isSleepPhrase`): the same phrase data, the same
 * normaliser, the same two rules, and the same two branches. A phrase this unit closes on or holds
 * open is the phrase the phone closes on or holds open, and the parity is pinned case by case in
 * `DriverDismissalPhoneParityTest` from the phone's own tables. What it is NOT is a substring scan:
 * "text mom bye bye" and "say good night to the kids" are requests, and a `contains` check closes
 * on both. The turn is stripped of courtesy at its ENDS and what is left must decompose entirely
 * into closers.
 *
 * **The one branch, and the one signal behind it.** With nothing awaited every closer counts
 * ([CLOSERS]); while an answer is awaited only the hard ones do ([HARD_CLOSERS]) and a turn holding
 * a word that could answer the question is an answer, not a goodbye. The phone reads two signals
 * for that state: its last spoken line ended in a question mark, and a write draft is pending. This
 * unit has the first ([LiveTranscript] gives Moose's own words, and `subtitles` is always on) and
 * NOT the second: the confirm state for `activate_garage` and `log_past_service` lives in the
 * model, not in code, so there is nothing to read. Named as the one gap, and benign here for a
 * reason the phone does not share: the phone decides before any model call, while this reads the
 * transcript at the END of the model's turn, by which point `handleToolCall` has already dispatched
 * whatever the model asked for. A yes that authorises work has therefore already authorised it.
 *
 * **Two things here the phone has no use for.** A two-word companion name is also matched by its
 * last word, because `CompanionProfile.DEFAULT_NAME` is "Uncle Moose" and nobody says the title
 * ([withoutCourtesy]); and a running trivia game narrows the closer set ([TRIVIA_CLOSERS]), because
 * "that's it!" is how a passenger shouts a right answer and the phone has no game to end.
 *
 * **It does NOT mean nothing was sent on the dismissal turn.** What closing guarantees is that
 * there is no FURTHER turn in which the model can act. Nothing here is a write guard, and the
 * engine guards its own doors anyway.
 *
 * **It arms, it does not fire.** [armsDismissal] is read at the END of the model's turn, so Moose's
 * sign-off is already spoken and still draining from the jitter buffer when the conversation parks.
 * Matching mid-utterance would be worse than useless: the transcript only grows within a turn, so a
 * partial that happened to end on "bye" would match and arming is sticky by design.
 */
object DriverDismissal {

    /** The sleep phrase in [normalise]d form, and the one contraction a transcript produces. */
    private const val SLEEP = "that will be all"
    private const val SLEEP_CONTRACTED = "that ll be all"

    /** The phone's `Farewells.FAREWELLS`, unchanged. */
    val FAREWELLS: List<String> = listOf(
        "bye", "bye bye", "byebye", "buh bye", "bye now", "goodbye", "good bye", "bye for now",
        "good night", "goodnight", "night", "night night", "nighty night", "nite", "nite nite", "g night",
        "see you", "see ya", "see you later", "see ya later", "see you tomorrow", "see you soon",
        "talk to you later", "talk later", "talk soon", "catch you later", "later", "ciao", "cheerio",
        "farewell", "peace out", "take care", "have a good night", "have a good one",
    )

    /**
     * Farewells that can also answer a question ("Morning or night?" "Night."): alone they close
     * only when Moose's last line did not ask something. The phone's `AMBIGUOUS_ALONE`. Only ever
     * alone: "good night" and "night night" are phrases of their own and close either way.
     */
    val AMBIGUOUS_ALONE: Set<String> = setOf("night", "nite", "later")

    /** The phone's `WakePhrases.STOP_PHRASES`: whole-turn stops, in [normalise]d form. */
    val STOP_PHRASES: List<String> = listOf(
        "stop", "stop stop", "never mind", "nevermind", "that s all", "that s it", "that s all thanks",
        "that s it thanks", "that will be all", "that ll be all", "go away", "stand down", "bye", "bye bye",
        "goodbye", "good bye", "thanks bye", "thank you bye", "thanks goodbye", "ok bye", "okay bye",
        "we re done", "i m done",
    )

    /** A word for stop or goodbye: never an answer to anything, whatever the model just asked. */
    val PLAIN_STOPS: List<String> = listOf(
        "stop", "goodbye", "good bye", "bye", "bye bye", "go away", "stand down",
    )

    /**
     * The four dismissals of Kevin's ruling Q8 (2026-11-23): these close even while an answer is
     * awaited. In [normalise]d form, where an apostrophe is a space, so "that'll" is "that ll".
     */
    val DISMISSALS: List<String> = listOf(
        "that will be all", "that ll be all", "that s all", "that s it",
    )

    /** The stops that are never an answer to a question. The phone's `HARD_STOPS`. */
    val HARD_STOPS: List<String> = PLAIN_STOPS + DISMISSALS

    /**
     * Lead-ins that open a turn without answering anything: pure discourse filler. One alone never
     * makes a goodbye into an answer (Kevin, 2026-11-23, Q9: "okay bye" closes after a question).
     * They come off at the ends with the rest of the courtesy and veto nothing. "right" sits here
     * rather than in [ASSENT_LEADS] because "all right" reduces to words containing "right", and
     * [answersQuestion] looks for a word anywhere in the turn.
     */
    val FILLER_LEADS: List<String> = listOf(
        "ok", "okay", "alright", "all right", "well", "oh", "so", "hey", "um", "uh", "right",
        "okay then", "ok then", "please",
    )

    /**
     * Lead-ins that assent. These stay in [ANSWER_WORDS]: they can authorise the thing Moose just
     * asked about (the garage relay's second call, a backfilled service mileage), and dropping one
     * while he waits on the answer loses the work.
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
     * Words that answer a question, plus any number. While an answer is awaited a turn holding one
     * is an answer, not a goodbye. [FILLER_LEADS] are deliberately NOT in it (Q9).
     */
    val ANSWER_WORDS: List<String> = ASSENT_LEADS + listOf(
        "yep", "yup", "no", "nope", "nah", "sure", "go ahead", "do it", "fine", "correct",
        "exactly", "tomorrow", "today", "tonight", "morning", "afternoon", "evening", "am", "pm",
        "o clock",
    )

    private val leadWords = phraseWords(FILLER_LEADS + ASSENT_LEADS + THANKS)
    private val tailWords = phraseWords(THANKS + TAILS)
    private val answerWords = phraseWords(ANSWER_WORDS)

    /** Every phrase that can stand alone as a close, with nothing awaited. */
    private val CLOSERS: List<List<String>> = phraseWords(FAREWELLS + STOP_PHRASES)

    /** The closers a turn with an answer awaited may be made of. The phone's `HARD_CLOSERS`. */
    private val HARD_CLOSERS: List<List<String>> =
        phraseWords(HARD_STOPS + (FAREWELLS - AMBIGUOUS_ALONE))

    /**
     * The stop phrases a running trivia game keeps out of its closer set: the four [DISMISSALS] and
     * the two that are a dismissal wearing a thanks. "That's it!" is how a driver or passenger
     * shouts a right answer, and a quiz ended by an exclamation is the one false close this file
     * could plausibly hit in a car. The sleep phrase's own `endsWith` path is held back for the
     * same reason while a game runs. A goodbye still ends it, and so does the tap.
     */
    private val GAME_UNSAFE: Set<String> = DISMISSALS.toSet() + setOf("that s all thanks", "that s it thanks")

    private val TRIVIA_CLOSERS: List<List<String>> = phraseWords(FAREWELLS + (STOP_PHRASES - GAME_UNSAFE))

    /** [HARD_CLOSERS] without the phrases a shouted right answer produces ([GAME_UNSAFE]). */
    private val TRIVIA_HARD_CLOSERS: List<List<String>> =
        phraseWords((HARD_STOPS - GAME_UNSAFE) + (FAREWELLS - AMBIGUOUS_ALONE))

    /**
     * [transcript] is a goodbye and nothing else. [companionName] comes off either end, so "bye
     * Moose" and "Moose, stop" count whatever the driver renamed him to. [answerAwaited]: Moose's
     * last line asked the driver something, so only a hard closer ends the conversation and a turn
     * that could be the answer goes to the model.
     */
    fun isDismissal(
        transcript: String,
        companionName: String = "",
        answerAwaited: Boolean = false,
        inTriviaGame: Boolean = false,
    ): Boolean {
        val t = normalise(transcript)
        if (t.isEmpty()) return false
        val name = normalise(companionName)
        return if (answerAwaited) {
            !answersQuestion(t) && closesAlone(t, name, if (inTriviaGame) TRIVIA_HARD_CLOSERS else HARD_CLOSERS)
        } else {
            (!inTriviaGame && (t.endsWith(SLEEP) || t.endsWith(SLEEP_CONTRACTED))) ||
                closesAlone(t, name, if (inTriviaGame) TRIVIA_CLOSERS else CLOSERS)
        }
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
     *
     * [lastCompanionLine] is the last thing Moose SAID before this turn, which is the line the
     * driver is answering; a blank one (no output transcript yet, or a silent tool-call turn) reads
     * as no question, which is the phone's own default state.
     */
    fun armsDismissal(
        heard: String,
        companionName: String,
        vadMode: Boolean,
        keepWarm: Boolean,
        conversationActive: Boolean,
        inTriviaGame: Boolean,
        lastCompanionLine: String = "",
    ): Boolean =
        vadMode && keepWarm && conversationActive &&
            isDismissal(heard, companionName, asksSomething(lastCompanionLine), inTriviaGame)

    /**
     * [line] asks the driver something: it ends in a question mark, a closing quote or bracket
     * after it aside. The phone's `FollowUpWindow.isQuestion`, which is the signal it reads for the
     * same branch.
     */
    fun asksSomething(line: String?): Boolean =
        line?.trimEnd { it.isWhitespace() || it in "\"')]”’»" }?.lastOrNull()
            ?.let { it == '?' || it == '？' } == true

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

    /** The whole turn is [closers], courtesy aside. The phone's `WakePhrases.closesAlone`. */
    private fun closesAlone(t: String, name: String, closers: List<List<String>>): Boolean {
        val w = withoutCourtesy(t.split(' ').filter { it.isNotEmpty() }, name)
        return w.isNotEmpty() && madeOf(w, closers)
    }

    /**
     * [words] without the companion's [name] at either end, lead-ins and thanks before, thanks and
     * tails after, repeated until nothing more comes off. Never the whole turn: a courtesy word
     * alone ("thanks") is a turn of its own and closes nothing.
     *
     * Courtesy is only ever set aside at the ENDS. That is what keeps the decomposition honest:
     * "that's all the coolant it needs" and "text mom bye bye" have a non-closer in the middle and
     * match nothing.
     *
     * **The name's last word counts as the name.** `CompanionProfile.DEFAULT_NAME` is "Uncle
     * Moose", and nobody says "bye Uncle Moose": they say "bye Moose". The phone never needed this
     * because "Alfred" is one word. Only the LAST word, because that is the one a two-part name is
     * shortened to; the first ("Uncle", "Mr") is the title.
     */
    fun withoutCourtesy(words: List<String>, name: String): List<String> {
        val full = name.split(' ').filter { it.isNotEmpty() }
        val forms = if (full.size > 1) listOf(full, listOf(full.last())) else listOf(full)
        var w = words
        while (true) {
            val before = w
            forms.filter { it.isNotEmpty() }.forEach { n ->
                if (w.take(n.size) == n) w = w.drop(n.size)
                if (w.size >= n.size && w.takeLast(n.size) == n) w = w.dropLast(n.size)
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
