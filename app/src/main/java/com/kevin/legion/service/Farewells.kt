package com.kevin.legion.service

/**
 * A goodbye said as the whole turn (Kevin, 2026-11-20: "bye bye" sometimes got "night" back and the
 * conversation stayed open). Decided in code on the transcript, before any model call: the turn is
 * ONLY a farewell, optionally with thanks, an interjection, the assistant's name or "for now"
 * around it. Words that merely contain a farewell ("text mom bye bye", "say good night to the kids")
 * are requests and go to the model.
 *
 * Data, not a pattern: [FAREWELLS] are the phrases a goodbye is made of (one or more, so "bye bye bye"
 * and "goodbye good night" count), [LEADS] and [THANKS] may come first, [THANKS] and [TAILS] may come
 * last, the name at either end. Normalisation ([normalise]): lowercase, every non-letter or digit run
 * to one space, so "Bye-bye." and "bye bye" are the same. Pure.
 */
object Farewells {
    val FAREWELLS: List<String> = listOf(
        "bye", "bye bye", "byebye", "buh bye", "bye now", "goodbye", "good bye", "bye for now",
        "good night", "goodnight", "night", "night night", "nighty night", "nite", "nite nite", "g night",
        "see you", "see ya", "see you later", "see ya later", "see you tomorrow", "see you soon",
        "talk to you later", "talk later", "talk soon", "catch you later", "later", "ciao", "cheerio",
        "farewell", "peace out", "take care", "have a good night", "have a good one",
    )

    val LEADS: List<String> = listOf(
        "ok", "okay", "alright", "all right", "well", "oh", "so", "hey", "um", "uh", "yeah", "yes", "right",
        "great", "cool", "perfect", "awesome", "lovely", "sounds good", "got it", "okay then", "ok then",
        "please",
    )

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
     * Words that answer a question (review of 546cce67, F1): the yes/no family, the lead-ins, and times.
     * While Alfred awaits an answer, a turn holding any of these (or a digit) is an answer, not a
     * farewell: "yes, bye" goes down the answer path so the confirmed action runs.
     */
    val ANSWER_WORDS: List<String> = LEADS + listOf(
        "yep", "yup", "no", "nope", "nah", "sure", "go ahead", "do it", "fine", "correct",
        "exactly", "tomorrow", "today", "tonight", "morning", "afternoon", "evening", "am", "pm", "o clock",
    )

    /** [transcript] holds a word that answers a question ([ANSWER_WORDS]) or a number. */
    fun answersQuestion(transcript: String): Boolean {
        val w = normalise(transcript).split(' ').filter { it.isNotEmpty() }
        if (w.any { t -> t.any { it.isDigit() } }) return true
        return words(ANSWER_WORDS).any { phrase -> w.windowed(phrase.size).any { it == phrase } }
    }

    fun normalise(text: String): String =
        text.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("").trim().replace(Regex("\\s+"), " ")

    /** Longest first, so "thanks a lot" is taken whole before "thanks". */
    private fun words(phrases: List<String>) = phraseWords(phrases)

    private val farewellWords = words(FAREWELLS)
    private val leadWords = words(LEADS + THANKS)
    private val tailWords = words(THANKS + TAILS)

    /**
     * Farewells that can also answer a question ("Morning or night?" "Night."): alone, they close only
     * when the last line he heard did not ask him something.
     */
    val AMBIGUOUS_ALONE: Set<String> = setOf("night", "nite", "later")

    /**
     * [transcript] is a farewell and nothing else ([companionName] may stand at either end). After a
     * question ([afterQuestion]) an [AMBIGUOUS_ALONE] word by itself is taken as his answer instead.
     */
    fun isFarewell(transcript: String, companionName: String = "", afterQuestion: Boolean = false): Boolean {
        if (afterQuestion && normalise(transcript) in AMBIGUOUS_ALONE) return false
        val w = withoutCourtesy(normalise(transcript).split(' ').filter { it.isNotEmpty() }, companionName)
        return w.isNotEmpty() && madeOfFarewells(w)
    }

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

    /** [w] is one or more [FAREWELLS] back to back. */
    private fun madeOfFarewells(w: List<String>): Boolean = madeOf(w, farewellWords)

    /**
     * [w] is one or more of [phrases] back to back and nothing else, so "bye bye bye" and
     * "that's it, bye bye" are each a whole turn of closers while "bye bye birdie" is not. The one
     * composition rule: [isFarewell] runs it over the farewells, [WakePhrases.isSleepPhrase] over the
     * farewells and the dismissals together. [phrases] comes from [words].
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
