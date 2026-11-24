package com.kevin.legion.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A goodbye said as the whole turn closes the conversation in code, before any model call (Kevin,
 * 2026-11-20: "bye bye" sometimes got "night" back and stayed open). Invented utterances only.
 */
class WakePhrasesFarewellTest {

    @Test
    fun `the sleep check closes on every farewell, also right after a question`() {
        assertTrue(FAREWELLS.size >= 30)
        for (u in FAREWELLS) {
            assertTrue(u, WakePhrases.isSleepPhrase(u, NAME))
            // Alone, these can answer a question ("Morning or night?"), and a farewell with an answer
            // word in it is an answer (F1): after a question both go down the answer path.
            val alone = u.lowercase().trim('.', '!', ' ') in setOf("night", "nite", "later")
            val words = u.lowercase().split(Regex("[^a-z0-9]+")).toSet()
            val answer = words.any { it in ANSWER_LEADS }
            for ((state, closed) in awaited(u)) assertTrue("$u, $state", closed == !(alone || answer))
        }
    }

    @Test
    fun `a request that only contains a farewell does not close, whatever is awaited`() {
        assertTrue(REQUESTS.size >= 20)
        for (u in REQUESTS) {
            assertFalse(u, WakePhrases.isSleepPhrase(u, NAME))
            for ((state, closed) in awaited(u)) assertFalse("$u, $state", closed)
        }
    }

    // Review of 546cce67 (F1, blocking): after a question, "yes, bye" closed and dropped his yes. While
    // an answer is awaited, an ASSENTING word sends the turn down the answer path; a bare farewell
    // still closes (and does not do the write).
    @Test
    fun `after a question an answer with a farewell is an answer, a bare farewell still closes`() {
        val closes = listOf("bye", "Goodbye.", "bye bye", "good night", "Thanks, bye.", "Goodbye, Jeeves.")
        val answers = listOf(
            "yes bye", "Yes, bye.", "no, good night", "yeah go ahead, bye", "sure, see you",
            "yep, bye now", "at 7, bye", "tomorrow morning, good night",
            "later", "night", "got it, bye", "sounds good, see you later", "nope, bye",
        )
        for (u in closes) assertTrue(u, WakePhrases.isSleepPhrase(u, NAME, afterQuestion = true))
        for (u in answers) assertFalse(u, WakePhrases.isSleepPhrase(u, NAME, afterQuestion = true))
    }

    /**
     * Kevin's rulings of 2026-11-23, in his words, each row an utterance said right after Alfred asked
     * something:
     *
     * - **Q8, yes:** *"that'll be all" / "that will be all" / "that's all" / "that's it" close even
     *   after a question; they are never an answer.* This reverses F1 for those four phrases.
     * - **Q9, yes:** *"okay bye" closes after a question: a lead-in word alone must not veto a bare
     *   farewell.*
     * - **Q10:** keep the trailing-politeness rule (`WakePhrasesTest`), so it holds here too.
     *
     * What still goes to the model is the other half of the ruling and the reason the close is safe:
     * a word that could AUTHORISE the thing Alfred asked about is an answer, and closing on it would
     * lose work. A filler lead-in authorises nothing. Invented utterances only.
     */
    @Test
    fun `the four dismissals and a filler lead-in close, a real answer does not, in every awaited state`() {
        val closes = listOf(
            // Q8: the four dismissals, bare, with courtesy (Q10), and with the name.
            "that'll be all", "That will be all.", "that's all", "That's it.", "that'll be all, thanks",
            "that's all for now", "Thanks, that's all.", "okay, that'll be all, thanks Jeeves",
            "Jeeves, that's it.", "that will be all then",
            // Q9: a filler lead-in before a bare farewell or dismissal.
            "okay bye", "Okay, bye bye.", "alright, goodbye", "All right, goodnight.", "um, bye",
            "okay then, bye", "right, bye", "well, goodbye", "ok thanks bye", "okay, that's all",
            // Both halves at once, and two ways of saying it in one turn.
            "that's it, bye bye", "that'll be all, goodbye", "okay that's all, thanks",
        )
        val answers = listOf(
            // A bare answer is no close at all: these are not closers in the first place.
            "yes", "okay", "sure", "okay do it", "yes please", "no thanks",
            // An assenting word authorises the thing he was asked about: F1 stands.
            "yes, that's all", "yeah, that's it", "sure, that'll be all", "got it, that's all",
            // A dismissal inside a sentence is a request, question or not.
            "that's all the groceries", "that's it for the groceries, add eggs", "is that all",
            // A farewell that can answer on its own, with and without the freed lead-in.
            "night", "later", "okay, night", "um, later",
            // DECIDED 2026-11-23, and owed to Kevin as a question: a negation is still an answer, so
            // "no, that'll be all" after "anything else?" goes to the model. Kevin ruled on the four
            // phrases and on filler lead-ins, not on "no"; unlike an assent, a negation authorises
            // nothing, so this one is a candidate to free next.
            "no, that'll be all", "nope, that's it",
        )
        for (u in closes) for ((state, closed) in awaited(u)) assertTrue("$u, $state", closed)
        for (u in answers) for ((state, closed) in awaited(u)) assertFalse("$u, $state", closed)
    }

    /**
     * A WRITE DRAFT waiting on his yes is no exception (Kevin, 2026-11-23, asked in those words:
     * while a write draft is pending, should "that'll be all" and "okay bye" close, or go to the
     * model so it can ask again? *they close and the draft is dropped; nothing is sent*). It had a
     * carve-out for a day, on the reasoning that "okay, that's it" might be the yes; he reversed it.
     *
     * What still protects a yes is the other half of his ruling and is unchanged: an ASSENTING word
     * authorises the thing Alfred asked about ([Farewells.ANSWER_WORDS]), so "yes, that's all" goes
     * to the model and the write can still run. A negation is not ruled on and stays an answer too.
     */
    @Test
    fun `while a draft waits the dismissals and a filler goodbye close, an assent is still his answer`() {
        val closes = listOf(
            // The four phrases, bare, with courtesy and with the name.
            "that'll be all", "That will be all.", "that's all", "That's it.", "that's all for now",
            "Jeeves, that's it.", "that'll be all, thanks", "okay, that'll be all, thanks Jeeves",
            // A filler lead-in before a farewell or a dismissal: "okay bye" in his words.
            "okay bye", "Okay, bye bye.", "um, bye", "alright, goodbye", "ok thanks bye",
            "okay, that's it", "right, that's all", "well, that's it", "so that's it",
            // Two ways of saying it in one turn is still one dismissal.
            "that's it, bye bye", "that'll be all, goodbye",
            // A word for stop or goodbye, which closed while the carve-out stood and still does.
            "stop", "Stop.", "goodbye", "Goodbye, Jeeves.", "bye", "bye bye", "Thanks, bye.",
            "Go away.", "stand down", "good night", "see you later", "bye bye, Jeeves, thanks",
        )
        val answers = listOf(
            // An assent authorises the write: F1 stands in this state above all.
            "yes, that's it", "yeah that's all", "perfect, that's it", "sure, that'll be all",
            "got it, that's all", "yes", "okay do it", "yes please", "no thanks", "yes, bye",
            // Not ruled on: a negation authorises nothing but is not freed either.
            "no, that'll be all", "nope, that's it",
            // A farewell that can answer on its own, and a bare answer.
            "night", "later", "sure", "tomorrow", "at 7, bye",
        )
        for (u in closes) assertTrue(u, WakePhrases.isSleepPhrase(u, NAME, draftPending = true))
        for (u in answers) assertFalse(u, WakePhrases.isSleepPhrase(u, NAME, draftPending = true))
        // One rule over both signals: a question mark as well changes nothing either way.
        for (u in closes) assertTrue(u, WakePhrases.isSleepPhrase(u, NAME, afterQuestion = true, draftPending = true))
        for (u in answers) assertFalse(u, WakePhrases.isSleepPhrase(u, NAME, afterQuestion = true, draftPending = true))
    }

    /**
     * Courtesy comes off at the ENDS of the turn and nothing vetoes from the middle (blind review of
     * d15a6953, finding 3: a filler word counted as an answer wherever it sat, so "bye, thanks so
     * much" was held on the "so" inside "thanks so much", and "please", which the tails strip, held
     * "stop please"). Every state, since there is one courtesy rule for all of them.
     */
    @Test
    fun `a goodbye wrapped in courtesy closes in every state, wherever the filler sits`() {
        val closes = listOf(
            "bye, thanks so much", "stop please", "goodbye please", "um, stop", "hey, stop",
            "oh, bye", "well, goodbye then", "please stop", "bye bye, thanks a lot, Jeeves",
        )
        for (u in closes) {
            assertTrue(u, WakePhrases.isSleepPhrase(u, NAME))
            for ((state, closed) in awaited(u)) assertTrue("$u, $state", closed)
        }
        // Not courtesy on its own: a bare courtesy word is a turn of its own and closes nothing.
        for (u in listOf("thanks", "please", "for now", "okay then", "so", "um")) {
            assertFalse(u, WakePhrases.isSleepPhrase(u, NAME))
            for ((state, closed) in awaited(u)) assertFalse("$u, $state", closed)
        }
    }

    /** After a question a lone ambiguous farewell is his answer; the same word in a phrase closes. */
    @Test
    fun `a lone night or later is his answer, the phrases it sits in still close`() {
        for (u in listOf("night", "Later.", "nite")) {
            assertTrue(u, WakePhrases.isSleepPhrase(u, NAME))
            assertFalse(u, WakePhrases.isSleepPhrase(u, NAME, afterQuestion = true))
        }
        for (u in listOf("good night", "night night", "nite nite", "see you later", "catch you later")) {
            assertTrue(u, WakePhrases.isSleepPhrase(u, NAME, afterQuestion = true))
        }
    }

    /**
     * [u] weighed in each state where Alfred awaits an answer: his last clip asked, something he can
     * answer is pending, or both. One rule decides all three (Kevin, 2026-11-23), so every row of
     * every table below is asserted in each of them and a state that disagrees names itself.
     */
    private fun awaited(u: String): List<Pair<String, Boolean>> = listOf(
        "after a question" to WakePhrases.isSleepPhrase(u, NAME, afterQuestion = true),
        "while a draft waits" to WakePhrases.isSleepPhrase(u, NAME, draftPending = true),
        "both" to WakePhrases.isSleepPhrase(u, NAME, afterQuestion = true, draftPending = true),
    )

    companion object {
        const val NAME = "Jeeves"

        /**
         * The table's answer words, for the after-question expectation ("see you tomorrow" answers a
         * when). The filler lead-ins left it on 2026-11-23 (Kevin, Q9), so "OK bye", "alright, bye",
         * "um, bye" and "All right, goodnight." now close after a question; an assenting word
         * ("great") still sends the turn to the model.
         */
        private val ANSWER_LEADS = setOf("great", "yeah", "yes", "sure", "tomorrow")

        val FAREWELLS = listOf(
            "bye", "Bye.", "bye bye", "Bye-bye!", "byebye", "Bye bye bye.", "buh-bye", "bye now", "Goodbye.",
            "good bye", "Good-bye, Jeeves.", "Jeeves, goodbye", "bye for now", "goodbye for now", "Good night.",
            "goodnight", "night night", "Nighty night!", "night", "g'night", "see you", "See ya later!",
            "see you tomorrow", "talk to you later", "catch you later", "later", "ciao", "cheerio", "take care",
            "OK bye", "okay bye bye", "alright, bye", "All right, goodnight.", "thanks, bye", "thank you, goodbye",
            "Thanks Jeeves, bye bye", "bye, thanks", "bye bye Jeeves thanks", "cheers, bye", "thanks a lot, good night",
            "um, bye", "okay then, bye", "great, thanks, see you later", "bye then", "goodbye again",
            "have a good night", "peace out",
        )

        val REQUESTS = listOf(
            "text mom bye bye", "say good night to the kids", "what time is it", "thanks", "thank you",
            "okay", "stop the music", "play goodbye yellow brick road", "add bye bye birdie to my list",
            "is it night yet", "set the night mode", "good night light on", "remind me to say goodbye to Sam",
            "how do you say goodbye in French", "night shift starts at nine", "see you at the gym is the event",
            "later today remind me", "bye the way what's next", "cheers", "goodbye to my old car, I sold it",
            "tell him see you later", "what's the weather tonight", "", "bye bye miss american pie song",
        )
    }
}
