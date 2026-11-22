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
    // an answer is awaited, any answer-type word sends the turn down the answer path; a bare farewell
    // still closes (and does not do the write).
    @Test
    fun `after a question an answer with a farewell is an answer, a bare farewell still closes`() {
        val closes = listOf("bye", "Goodbye.", "bye bye", "good night", "Thanks, bye.", "Goodbye, Jeeves.")
        val answers = listOf(
            "yes bye", "Yes, bye.", "ok thanks bye", "no, good night", "yeah go ahead, bye", "sure, see you",
            "yep, bye now", "okay bye", "alright, goodbye", "at 7, bye", "tomorrow morning, good night",
            "later", "night", "got it, bye", "sounds good, see you later", "nope, bye",
        )
        for (u in closes) assertTrue(u, WakePhrases.isSleepPhrase(u, NAME, afterQuestion = true))
        for (u in answers) assertFalse(u, WakePhrases.isSleepPhrase(u, NAME, afterQuestion = true))
    }

    companion object {
        const val NAME = "Jeeves"

        /** The table's answer words, for the after-question expectation ("see you tomorrow" answers a when). */
        private val ANSWER_LEADS =
            setOf("ok", "okay", "alright", "all", "um", "great", "yeah", "yes", "sure", "tomorrow")

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
