package com.kevin.legion.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A goodbye said as the whole turn closes the conversation in code, before any model call (Kevin,
 * 2026-11-20: "bye bye" sometimes got "night" back and stayed open). Invented utterances only; the
 * table is shared with [FarewellsTest].
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
            assertTrue(u, WakePhrases.isSleepPhrase(u, NAME, afterQuestion = true) == !(alone || answer))
        }
    }

    @Test
    fun `a request that only contains a farewell does not close`() {
        assertTrue(REQUESTS.size >= 20)
        for (u in REQUESTS) assertFalse(u, WakePhrases.isSleepPhrase(u, NAME))
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
    fun `after a question the four dismissals and a filler lead-in close, a real answer does not`() {
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
        for (u in closes) assertTrue(u, WakePhrases.isSleepPhrase(u, NAME, afterQuestion = true))
        for (u in answers) assertFalse(u, WakePhrases.isSleepPhrase(u, NAME, afterQuestion = true))
    }

    /**
     * While a WRITE DRAFT waits on his yes, the strictest rule applies (blind review of 56e9404e,
     * finding 4): only a word for stop or goodbye closes, the four dismissals of Q8 are soft again,
     * and a filler lead-in vetoes again, because "okay, that's it" may be the yes that commits the
     * draft and a close discards it unanswered. Q8 and Q9 are unchanged for every turn with no draft
     * waiting, which the test above pins. The costs decide it: a close too few costs one more turn,
     * a close too many destroys what he just asked for.
     */
    @Test
    fun `while a draft waits a dismissal is his answer, a goodbye still closes`() {
        val answers = listOf(
            // The three the second round was asked to save, and the blind review's own three.
            "yes, that's it", "yeah that's all", "perfect, that's it",
            "right, that's all", "well, that's it", "so that's it",
            // A bare dismissal: F1 verbatim ("That's it." said as a yes committed nothing).
            "That's it.", "that's all", "that'll be all", "That will be all.", "that's all for now",
            // Q9's lead-ins veto again here: "okay" may be the yes.
            "okay bye", "okay, that's it", "um, bye", "alright, goodbye", "ok thanks bye",
            // A dismissal anywhere in the turn keeps it out: the whole turn must be plain closers.
            "that's it, bye bye", "that'll be all, goodbye",
        )
        val closes = listOf(
            "stop", "Stop.", "goodbye", "Goodbye, Jeeves.", "bye", "bye bye", "Thanks, bye.",
            "Go away.", "stand down", "good night", "see you later", "bye bye, Jeeves, thanks",
        )
        for (u in answers) assertFalse(u, WakePhrases.isSleepPhrase(u, NAME, draftPending = true))
        for (u in closes) assertTrue(u, WakePhrases.isSleepPhrase(u, NAME, draftPending = true))
        // The draft branch wins over the question mark: both true is still the strict rule.
        assertFalse(WakePhrases.isSleepPhrase("that's it", NAME, afterQuestion = true, draftPending = true))
        assertTrue(WakePhrases.isSleepPhrase("that's it", NAME, afterQuestion = true))
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
