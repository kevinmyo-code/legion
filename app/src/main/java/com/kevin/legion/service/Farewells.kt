package com.kevin.legion.service

/**
 * A goodbye said as the whole turn (Kevin, 2026-11-20: "bye bye" sometimes got "night" back and the
 * conversation stayed open). The data and the two pure rules [WakePhrases.isSleepPhrase] decides a
 * close with, before any model call: the turn is ONLY closers, optionally with thanks, an
 * interjection, the assistant's name or "for now" around it. Words that merely contain a farewell
 * ("text mom bye bye", "say good night to the kids") are requests and go to the model.
 *
 * Data, not a pattern: [FAREWELLS] are the phrases a goodbye is made of (one or more, so "bye bye bye"
 * and "goodbye good night" count, [madeOf]), [LEADS] and [THANKS] may come first, [THANKS] and [TAILS]
 * may come last, the name at either end ([withoutCourtesy]). Normalisation ([normalise]): lowercase,
 * every non-letter or digit run to one space, so "Bye-bye." and "bye bye" are the same. Pure.
 */
object Farewells {
    val FAREWELLS: List<String> = listOf(
        "bye", "bye bye", "byebye", "buh bye", "bye now", "goodbye", "good bye", "bye for now",
        "good night", "goodnight", "night", "night night", "nighty night", "nite", "nite nite", "g night",
        "see you", "see ya", "see you later", "see ya later", "see you tomorrow", "see you soon",
        "talk to you later", "talk later", "talk soon", "catch you later", "later", "ciao", "cheerio",
        "farewell", "peace out", "take care", "have a good night", "have a good one",
    )

    /**
     * Lead-ins that open a turn without answering anything: pure discourse filler. One of these ALONE
     * no longer makes a goodbye into an answer, in any state (Kevin, 2026-11-23, Q9: *"okay bye"
     * closes after a question; a lead-in word alone must not veto a bare farewell*). They are
     * courtesy, so they come off at the ENDS with the rest ([withoutCourtesy]) and veto nothing;
     * [answersQuestion] never counts one. "right" sits here
     * and not in [ASSENT_LEADS] because "all right" reduces to words containing "right", and
     * [answersQuestion] looks for a word anywhere in the turn, so a bare "right" in the answer set
     * would read "all right, goodnight" as an answer.
     */
    val FILLER_LEADS: List<String> = listOf(
        "ok", "okay", "alright", "all right", "well", "oh", "so", "hey", "um", "uh", "right",
        "okay then", "ok then", "please",
    )

    /**
     * Lead-ins that assent. These stay in [ANSWER_WORDS]: they can authorise the action Alfred just
     * asked about, and dropping one loses work ("yes, bye" after "shall I send it?"; review of
     * 546cce67, F1). Kevin's Q9 ruling freed the filler above, not these.
     */
    val ASSENT_LEADS: List<String> = listOf(
        "yeah", "yes", "great", "cool", "perfect", "awesome", "lovely", "sounds good", "got it",
    )

    /** Everything that may open a turn, for [withoutCourtesy]. Both halves, unchanged as one list. */
    val LEADS: List<String> = FILLER_LEADS + ASSENT_LEADS

    val THANKS: List<String> = listOf(
        "thanks", "thank you", "thanks a lot", "thank you very much", "thanks so much", "cheers", "appreciate it",
        "many thanks",
    )

    /**
     * What Alfred says as the conversation closes on his farewell: scripted, kept like the fillers, played
     * from memory or not at all (then the closing chime alone tells him it stopped).
     */
    val SPOKEN: List<String> = listOf("Goodbye.", "Bye for now.", "Talk soon.")

    val TAILS: List<String> = listOf("for now", "then", "now", "again", "mate", "buddy", "please")

    /**
     * Words that answer a question (review of 546cce67, F1): the yes/no family, the assenting
     * lead-ins, and times. While Alfred awaits an answer, a turn holding any of these (or a digit) is
     * an answer, not a farewell: "yes, bye" goes down the answer path so the confirmed action runs.
     *
     * NARROWED 2026-11-23 (Kevin, Q9): [FILLER_LEADS] are no longer in it, so "okay bye" and
     * "alright, goodbye" close after a question instead of being read as a yes.
     */
    val ANSWER_WORDS: List<String> = ASSENT_LEADS + listOf(
        "yep", "yup", "no", "nope", "nah", "sure", "go ahead", "do it", "fine", "correct",
        "exactly", "tomorrow", "today", "tonight", "morning", "afternoon", "evening", "am", "pm", "o clock",
    )

    /**
     * [transcript] holds a word that answers a question ([ANSWER_WORDS]) or a number. One reading for
     * every state. A pending write took a stricter reading on one branch and Kevin reversed it before
     * it ever shipped (2026-11-23): a filler lead-in authorises nothing wherever it is said.
     */
    fun answersQuestion(transcript: String): Boolean {
        val w = normalise(transcript).split(' ').filter { it.isNotEmpty() }
        if (w.any { t -> t.any { it.isDigit() } }) return true
        return answerWords.any { phrase -> w.windowed(phrase.size).any { it == phrase } }
    }

    fun normalise(text: String): String =
        text.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("").trim().replace(Regex("\\s+"), " ")

    /** Longest first, so "thanks a lot" is taken whole before "thanks". */
    private fun words(phrases: List<String>) = phraseWords(phrases)

    private val leadWords = words(LEADS + THANKS)
    private val tailWords = words(THANKS + TAILS)
    private val answerWords = words(ANSWER_WORDS)

    /**
     * Farewells that can also answer a question ("Morning or night?" "Night."): alone, they close only
     * when the last line he heard did not ask him something. [WakePhrases] keeps them out of the
     * after-question closer set, which is why "okay, night" is still his answer now that "okay" no
     * longer vetoes on its own (2026-11-23). Only ever alone: "good night" and "night night" are
     * phrases of their own and close either way.
     */
    val AMBIGUOUS_ALONE: Set<String> = setOf("night", "nite", "later")

    /**
     * [words] without the name at either end, the lead-ins and thanks before, and thanks and tails after.
     * The one courtesy rule: [WakePhrases.isSleepPhrase] strips a dismissal ("that'll be all") with it too,
     * so "okay, that'll be all, thanks Alfred" and "bye bye for now" are the same shape of turn
     * (2026-11-23, goodbye hardening: a goodbye with filler around it sometimes did not close). Never the whole
     * turn: a courtesy word alone ("thanks") stays a turn of its own.
     */
    fun withoutCourtesy(words: List<String>, companionName: String): List<String> {
        val name = normalise(companionName).split(' ').filter { it.isNotEmpty() }
        var w = words
        while (true) {
            val before = w
            if (name.isNotEmpty() && w.take(name.size) == name) w = w.drop(name.size)
            if (name.isNotEmpty() && w.size >= name.size && w.takeLast(name.size) == name) w = w.dropLast(name.size)
            leadWords.firstOrNull { it.size < w.size && w.take(it.size) == it }?.let { w = w.drop(it.size) }
            tailWords.firstOrNull { it.size < w.size && w.takeLast(it.size) == it }?.let { w = w.dropLast(it.size) }
            if (w == before || w.isEmpty()) return w
        }
    }

    /**
     * [w] is one or more of [phrases] back to back and nothing else, so "bye bye bye" and
     * "that's it, bye bye" are each a whole turn of closers while "bye bye birdie" is not. The one
     * composition rule, and [WakePhrases.isSleepPhrase] is its one caller: which closers it may be
     * made of is the only difference between a turn before a question and one after it (2026-11-23).
     * [phrases] comes from [words].
     */
    fun madeOf(w: List<String>, phrases: List<List<String>>): Boolean {
        val ok = BooleanArray(w.size + 1)
        ok[0] = true
        for (i in 0 until w.size) {
            if (!ok[i]) continue
            for (f in phrases) {
                if (i + f.size <= w.size && w.subList(i, i + f.size) == f) ok[i + f.size] = true
            }
        }
        return ok[w.size]
    }

    /** [phrases] as word lists, longest first, for [madeOf]. */
    fun phraseWords(phrases: Collection<String>): List<List<String>> =
        phrases.map { it.split(' ') }.sortedByDescending { it.size }
}
