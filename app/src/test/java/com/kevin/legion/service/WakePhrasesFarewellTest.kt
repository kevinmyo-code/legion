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
            // Alone, these can answer a question ("Morning or night?"): after one they go to the model.
            val alone = u.lowercase().trim('.', '!', ' ') in setOf("night", "nite", "later")
            assertTrue(u, WakePhrases.isSleepPhrase(u, NAME, afterQuestion = true) != alone)
        }
    }

    @Test
    fun `a request that only contains a farewell does not close`() {
        assertTrue(REQUESTS.size >= 20)
        for (u in REQUESTS) assertFalse(u, WakePhrases.isSleepPhrase(u, NAME))
    }

    companion object {
        const val NAME = "Jeeves"

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
