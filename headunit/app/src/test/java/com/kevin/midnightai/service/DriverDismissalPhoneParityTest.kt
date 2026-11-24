@file:Suppress("MaxLineLength") // the phone's phrase tables read best one row to a line

package com.kevin.midnightai.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Moose closes a conversation on the same words Alfred does (ruled 2026-11-24: the two voices
 * behave the same and only the character differs).
 *
 * **Every table below is the phone's own, copied case for case** from
 * `app/src/test/.../service/WakePhrasesFarewellTest.kt` and `WakePhrasesTest.kt` as they stand on
 * `dev` (the goodbye hardening of 2026-11-23, Kevin's rulings Q8, Q9 and Q10). A phone case that
 * closes must close here, and one that does not must not. That is the whole point of the file: the
 * phrase lists are a port, so a drift between the two readings of "did he say goodbye" shows up as
 * a failing row here rather than as a conversation in a car that will not end.
 *
 * **The one mapping.** The phone weighs a turn in three awaited states: its last spoken line ended
 * in a question, a write draft is pending, or both. One rule decides all three, so they are one
 * state here, [AWAITED]: this unit has the question signal (Moose's own transcript) and has no
 * draft signal at all, because the confirm state for `activate_garage` and `log_past_service` lives
 * in the model rather than in code. That is the only phone behaviour this file cannot assert, and
 * it is benign here for the reason [DriverDismissal]'s own doc gives: the dismissal is read after
 * the model's turn, so a yes has already authorised whatever it authorises.
 *
 * Two head-unit-only differences are tested in [DriverDismissalTest] instead, because the phone has
 * nothing to compare them against: a two-word companion name matched by its last word, and the
 * narrowed closer set while a trivia game runs.
 *
 * Invented utterances and invented names only.
 */
class DriverDismissalPhoneParityTest {

    // --- WakePhrasesFarewellTest: the farewell and request tables -------------------------

    @Test
    fun `the phone's farewells close, and after a question all but the ambiguous and the answers`() {
        assertTrue(PHONE_FAREWELLS.size >= 30)
        for (u in PHONE_FAREWELLS) {
            assertTrue(u, closes(u))
            // Alone, these can answer a question ("Morning or night?"), and a farewell with an
            // answer word in it is an answer: after a question both go down the answer path.
            val alone = u.lowercase().trim('.', '!', ' ') in DriverDismissal.AMBIGUOUS_ALONE
            val answer = u.lowercase().split(Regex("[^a-z0-9]+")).toSet().any { it in ANSWER_LEADS }
            assertTrue("$u, $AWAITED", closes(u, awaited = true) == !(alone || answer))
        }
    }

    @Test
    fun `the phone's requests never close, whatever is awaited`() {
        assertTrue(PHONE_REQUESTS.size >= 20)
        for (u in PHONE_REQUESTS) {
            assertFalse(u, closes(u))
            assertFalse("$u, $AWAITED", closes(u, awaited = true))
        }
    }

    /**
     * The phone's F1 table (review of 546cce67): while an answer is awaited an ASSENTING word sends
     * the turn down the answer path, and a bare farewell still closes.
     */
    @Test
    fun `after a question an answer holding a farewell is an answer, a bare farewell still closes`() {
        val ends = listOf("bye", "Goodbye.", "bye bye", "good night", "Thanks, bye.", "Goodbye, Jeeves.")
        val answers = listOf(
            "yes bye", "Yes, bye.", "no, good night", "yeah go ahead, bye", "sure, see you",
            "yep, bye now", "at 7, bye", "tomorrow morning, good night",
            "later", "night", "got it, bye", "sounds good, see you later", "nope, bye",
        )
        for (u in ends) assertTrue(u, closes(u, awaited = true))
        for (u in answers) assertFalse(u, closes(u, awaited = true))
    }

    /**
     * Kevin's rulings of 2026-11-23, each row an utterance said right after the companion asked
     * something: Q8, the four dismissals close even then; Q9, a filler lead-in alone never vetoes a
     * bare farewell; Q10, the trailing politeness is set aside. What still goes to the model is the
     * other half: a word that could AUTHORISE the thing he asked about is an answer.
     */
    @Test
    fun `the four dismissals and a filler lead-in close after a question, a real answer does not`() {
        val ends = listOf(
            "that'll be all", "That will be all.", "that's all", "That's it.", "that'll be all, thanks",
            "that's all for now", "Thanks, that's all.", "okay, that'll be all, thanks Jeeves",
            "Jeeves, that's it.", "that will be all then",
            "okay bye", "Okay, bye bye.", "alright, goodbye", "All right, goodnight.", "um, bye",
            "okay then, bye", "right, bye", "well, goodbye", "ok thanks bye", "okay, that's all",
            "that's it, bye bye", "that'll be all, goodbye", "okay that's all, thanks",
            // From the phone's draft-pending table, which maps to the same state here.
            "okay, that's it", "right, that's all", "well, that's it", "so that's it",
            "stop", "Stop.", "goodbye", "Goodbye, Jeeves.", "Go away.", "stand down",
            "see you later", "bye bye, Jeeves, thanks",
            // `WakePhrasesTest`'s own after-a-question rows, in the same state.
            "Bye bye.", "Good bye, Jeeves.", "Stand down.", "That'll be all.", "That's all.",
        )
        val answers = listOf(
            "yes", "okay", "sure", "okay do it", "yes please", "no thanks",
            "yes, that's all", "yes, that's it", "yeah, that's it", "sure, that'll be all", "got it, that's all",
            "perfect, that's it", "yeah that's all", "yes, bye",
            "that's all the groceries", "that's it for the groceries, add eggs", "is that all",
            "night", "later", "okay, night", "um, later", "tomorrow", "at 7, bye",
            // Not ruled on: a negation authorises nothing but was not freed either.
            "no, that'll be all", "nope, that's it",
        )
        for (u in ends) assertTrue(u, closes(u, awaited = true))
        for (u in answers) assertFalse(u, closes(u, awaited = true))
    }

    /**
     * Courtesy comes off at the ENDS and nothing vetoes from the middle (blind review of d15a6953,
     * finding 3: "bye, thanks so much" was held on the "so" inside "thanks so much", and "please",
     * which the tails strip, held "stop please"). Both states, one courtesy rule.
     */
    @Test
    fun `a goodbye wrapped in courtesy closes in both states, wherever the filler sits`() {
        val wrapped = listOf(
            "bye, thanks so much", "stop please", "goodbye please", "um, stop", "hey, stop",
            "oh, bye", "well, goodbye then", "please stop", "bye bye, thanks a lot, Jeeves",
        )
        for (u in wrapped) {
            assertTrue(u, closes(u))
            assertTrue("$u, $AWAITED", closes(u, awaited = true))
        }
        for (u in listOf("thanks", "please", "for now", "okay then", "so", "um")) {
            assertFalse(u, closes(u))
            assertFalse("$u, $AWAITED", closes(u, awaited = true))
        }
    }

    /** After a question a lone ambiguous farewell is his answer; the phrases it sits in still close. */
    @Test
    fun `a lone night or later is his answer, the phrases it sits in still close`() {
        for (u in listOf("night", "Later.", "nite")) {
            assertTrue(u, closes(u))
            assertFalse("$u, $AWAITED", closes(u, awaited = true))
        }
        for (u in listOf("good night", "night night", "nite nite", "see you later", "catch you later")) {
            assertTrue("$u, $AWAITED", closes(u, awaited = true))
        }
    }

    // --- WakePhrasesTest: the sleep phrase, the stops, and the courtesy tables -------------

    @Test
    fun `the sleep phrase and its contraction match, inside a sentence they do not`() {
        for (u in listOf(
            "that will be all", "That will be all.", "  That will be all!  ", "that'll be all",
            "Thank you, that'll be all.", "ok great, that will be all",
        )) {
            assertTrue(u, closes(u))
        }
        for (u in listOf(
            "that will be all I need from the pantry", "that will be all the milk we have",
            "is that all", "what will it all cost", "add milk to the list", "", "   ",
        )) {
            assertFalse(u, closes(u))
        }
    }

    @Test
    fun `every phone stop phrase ends the conversation when it is the whole turn`() {
        for (p in PHONE_STOPS) {
            assertTrue(p, closes(p, name = ALFRED))
            assertTrue("$p.", closes("${p.replaceFirstChar { it.uppercase() }}.", name = ALFRED))
        }
    }

    /**
     * The phone's `LoneWordWriteGuardTest` walks its own stop lists rather than a sample of them
     * ("the stop words never reach the guard, they end the turn before the model"), so this walks
     * ours. A phrase that sits in the data and does not close is dead data, which reads as covered.
     */
    @Test
    fun `every phrase in the stop data closes, as a whole turn, in the state it is meant for`() {
        for (p in DriverDismissal.STOP_PHRASES + DriverDismissal.FAREWELLS) {
            assertTrue(p, closes(p, name = ALFRED))
        }
        for (p in DriverDismissal.HARD_STOPS + (DriverDismissal.FAREWELLS - DriverDismissal.AMBIGUOUS_ALONE)) {
            // One row of the data is a farewell that is also an answer: "see you tomorrow" holds
            // "tomorrow". The veto wins there, on the phone too ("tomorrow morning, good night" is
            // in its own answer table), so the walk asserts the veto rather than skipping it.
            val closed = closes(p, name = ALFRED, awaited = true)
            assertTrue("$p, $AWAITED", closed == !DriverDismissal.answersQuestion(p))
        }
    }

    @Test
    fun `the phone's courtesy table is set aside, and courtesy around a request changes nothing`() {
        for (u in COURTESY_CLOSES) assertTrue(u, closes(u, name = ALFRED))
        for (u in COURTESY_REQUESTS) assertFalse(u, closes(u, name = ALFRED))
    }

    @Test
    fun `the name and a leading hey or ok are not part of the phrase`() {
        for (u in listOf(
            "Goodbye, Alfred.", "Alfred, stop.", "ok bye", "Okay, goodbye.", "Hey Alfred, that's all.",
            "Bye Alfred!", "OK, never mind.", "Thanks, Alfred, that's all.", "Thank you Alfred, that's it.",
            "Cheers, Alfred, we're done.", "Okay, thanks, never mind.", "Thank you, that's all.",
            "Thanks Alfred. That's all.",
        )) {
            assertTrue(u, closes(u, name = ALFRED))
        }
        for (u in listOf("Thanks, Alfred.", "Thank you.", "Thanks, Alfred, add eggs.", "Thanks, that's all the milk")) {
            assertFalse(u, closes(u, name = ALFRED))
        }
        // Another companion's name is just a name: the rule follows the name the driver gave him.
        assertTrue(closes("Goodbye, Jarvis.", name = "Jarvis"))
        assertFalse(closes("Goodbye, Jarvis.", name = ALFRED))
    }

    @Test
    fun `after a question a thanks before a hard stop ends it, before a soft one it does not`() {
        for (u in listOf(
            "Thanks, goodbye.", "Thank you, Alfred, stop.", "Cheers, bye.",
            "Thanks, that's all.", "Thank you, Alfred, that's it.",
        )) {
            assertTrue(u, closes(u, name = ALFRED, awaited = true))
        }
        // "never mind", "we're done" and "I'm done" were not ruled on and stay soft: after a
        // question they may be his answer, with nothing awaited they close.
        for (u in listOf("Thanks, never mind.", "Thanks, Alfred, we're done.", "Never mind.", "We're done.", "I'm done.")) {
            assertFalse("$u, $AWAITED", closes(u, name = ALFRED, awaited = true))
            assertTrue(u, closes(u, name = ALFRED))
        }
    }

    @Test
    fun `sentences that only use those words never end it`() {
        for (u in listOf(
            "stop the music", "that's all I need from the pantry", "thanks", "thank you", "nothing",
            "stop the timer", "never mind the calendar, what's the weather", "say goodbye to my sister for me",
            "is that it?", "that's it for the groceries, add eggs", "bye bye birdie is on tonight",
        )) {
            assertFalse(u, closes(u, name = ALFRED))
        }
    }

    @Test
    fun `the phone review's missing stops end it too`() {
        for (u in listOf(
            "Nevermind.", "Bye bye!", "Stop, stop.", "Thank you, bye.", "Thanks, goodbye.",
            "That's all, thanks.", "That's it, thanks.",
        )) {
            assertTrue(u, closes(u, name = ALFRED))
        }
    }

    // --- the data itself is the phone's ---------------------------------------------------

    /**
     * The phrase lists are a copy, so they are pinned as one: the literals below are a SNAPSHOT of
     * the phone's lists as they stand on `dev` on 2026-11-24, and nothing here reads the phone's
     * own source (separate Gradle projects). So this catches an edit on THIS side, not one on the
     * phone's; keeping the two in step is a job for whoever changes the phone's list, and the
     * snapshot is what tells them what this unit was built against.
     */
    @Test
    fun `the phrase lists are the phone's, phrase for phrase`() {
        assertSameList(PHONE_FAREWELL_DATA, DriverDismissal.FAREWELLS, "FAREWELLS")
        assertSameList(PHONE_STOP_PHRASE_DATA, DriverDismissal.STOP_PHRASES, "STOP_PHRASES")
        assertSameList(PHONE_PLAIN_STOPS, DriverDismissal.PLAIN_STOPS, "PLAIN_STOPS")
        assertSameList(PHONE_DISMISSALS, DriverDismissal.DISMISSALS, "DISMISSALS")
        assertSameList(PHONE_FILLER_LEADS, DriverDismissal.FILLER_LEADS, "FILLER_LEADS")
        assertSameList(PHONE_ASSENT_LEADS, DriverDismissal.ASSENT_LEADS, "ASSENT_LEADS")
        assertSameList(PHONE_THANKS, DriverDismissal.THANKS, "THANKS")
        assertSameList(PHONE_TAILS, DriverDismissal.TAILS, "TAILS")
        assertSameList(PHONE_ANSWER_WORDS, DriverDismissal.ANSWER_WORDS, "ANSWER_WORDS")
        assertSameList(PHONE_AMBIGUOUS_ALONE.toList(), DriverDismissal.AMBIGUOUS_ALONE.toList(), "AMBIGUOUS_ALONE")
    }

    private fun assertSameList(phone: List<String>, here: List<String>, what: String) {
        assertTrue("$what missing here: ${phone - here.toSet()}", (phone - here.toSet()).isEmpty())
        assertTrue("$what not on the phone: ${here - phone.toSet()}", (here - phone.toSet()).isEmpty())
    }

    private fun closes(turn: String, name: String = JEEVES, awaited: Boolean = false) =
        DriverDismissal.isDismissal(turn, name, answerAwaited = awaited)

    companion object {
        private const val JEEVES = "Jeeves"
        private const val ALFRED = "Alfred"

        /** The phone's three awaited states collapse to this one; the mapping is in the class doc. */
        private const val AWAITED = "while an answer is awaited"

        /** The phone table's answer words, for the after-question expectation. */
        private val ANSWER_LEADS = setOf("great", "yeah", "yes", "sure", "tomorrow")

        val PHONE_FAREWELLS = listOf(
            "bye", "Bye.", "bye bye", "Bye-bye!", "byebye", "Bye bye bye.", "buh-bye", "bye now", "Goodbye.",
            "good bye", "Good-bye, Jeeves.", "Jeeves, goodbye", "bye for now", "goodbye for now", "Good night.",
            "goodnight", "night night", "Nighty night!", "night", "g'night", "see you", "See ya later!",
            "see you tomorrow", "talk to you later", "catch you later", "later", "ciao", "cheerio", "take care",
            "OK bye", "okay bye bye", "alright, bye", "All right, goodnight.", "thanks, bye", "thank you, goodbye",
            "Thanks Jeeves, bye bye", "bye, thanks", "bye bye Jeeves thanks", "cheers, bye", "thanks a lot, good night",
            "um, bye", "okay then, bye", "great, thanks, see you later", "bye then", "goodbye again",
            "have a good night", "peace out",
        )

        val PHONE_REQUESTS = listOf(
            "text mom bye bye", "say good night to the kids", "what time is it", "thanks", "thank you",
            "okay", "stop the music", "play goodbye yellow brick road", "add bye bye birdie to my list",
            "is it night yet", "set the night mode", "good night light on", "remind me to say goodbye to Sam",
            "how do you say goodbye in French", "night shift starts at nine", "see you at the gym is the event",
            "later today remind me", "bye the way what's next", "cheers", "goodbye to my old car, I sold it",
            "tell him see you later", "what's the weather tonight", "", "bye bye miss american pie song",
        )

        /** `WakePhrasesTest`'s whole-turn stop table. */
        val PHONE_STOPS = listOf(
            "stop", "never mind", "that's all", "that's it", "that will be all", "that'll be all", "go away",
            "stand down", "bye", "goodbye", "good bye", "thanks bye", "ok bye", "we're done", "I'm done",
        )

        val COURTESY_CLOSES = listOf(
            "okay, that'll be all, thanks Alfred", "that's all for now", "that will be all, thanks",
            "that will be all for now", "alright, bye bye", "okay that's all, thanks", "bye bye, please",
            "Thanks Alfred, that'll be all then", "that's it for now", "alright, that'll be all",
            "goodbye for now, please", "Okay. Bye bye, Alfred. Thanks.", "that's all, thanks Alfred",
            "that's it, bye bye", "that'll be all, goodbye", "bye bye, that's all", "okay that's all, thanks, bye",
        )

        val COURTESY_REQUESTS = listOf(
            "text mom bye bye", "say good night to the kids", "thanks, that's all the milk we need",
            "okay, add eggs for now", "please stop the music", "thanks, remind me to say goodbye then",
            "alright, what's the plan for now", "okay, play bye bye birdie please", "that's all I need, thanks",
            "stop the timer please", "goodbye for now is the title of the song", "thanks", "please", "for now",
        )

        // --- the phone's phrase data, as it stands in app/.../service/Farewells.kt and WakePhrases.kt ---

        val PHONE_FAREWELL_DATA = listOf(
            "bye", "bye bye", "byebye", "buh bye", "bye now", "goodbye", "good bye", "bye for now",
            "good night", "goodnight", "night", "night night", "nighty night", "nite", "nite nite", "g night",
            "see you", "see ya", "see you later", "see ya later", "see you tomorrow", "see you soon",
            "talk to you later", "talk later", "talk soon", "catch you later", "later", "ciao", "cheerio",
            "farewell", "peace out", "take care", "have a good night", "have a good one",
        )

        val PHONE_STOP_PHRASE_DATA = listOf(
            "stop", "stop stop", "never mind", "nevermind", "that s all", "that s it", "that s all thanks",
            "that s it thanks", "that will be all", "that ll be all", "go away", "stand down", "bye", "bye bye",
            "goodbye", "good bye", "thanks bye", "thank you bye", "thanks goodbye", "ok bye", "okay bye",
            "we re done", "i m done",
        )

        val PHONE_PLAIN_STOPS = listOf("stop", "goodbye", "good bye", "bye", "bye bye", "go away", "stand down")

        val PHONE_DISMISSALS = listOf("that will be all", "that ll be all", "that s all", "that s it")

        val PHONE_FILLER_LEADS = listOf(
            "ok", "okay", "alright", "all right", "well", "oh", "so", "hey", "um", "uh", "right",
            "okay then", "ok then", "please",
        )

        val PHONE_ASSENT_LEADS = listOf(
            "yeah", "yes", "great", "cool", "perfect", "awesome", "lovely", "sounds good", "got it",
        )

        val PHONE_THANKS = listOf(
            "thanks", "thank you", "thanks a lot", "thank you very much", "thanks so much", "cheers",
            "appreciate it", "many thanks",
        )

        val PHONE_TAILS = listOf("for now", "then", "now", "again", "mate", "buddy", "please")

        val PHONE_ANSWER_WORDS = PHONE_ASSENT_LEADS + listOf(
            "yep", "yup", "no", "nope", "nah", "sure", "go ahead", "do it", "fine", "correct",
            "exactly", "tomorrow", "today", "tonight", "morning", "afternoon", "evening", "am", "pm",
            "o clock",
        )

        val PHONE_AMBIGUOUS_ALONE = setOf("night", "nite", "later")
    }
}
