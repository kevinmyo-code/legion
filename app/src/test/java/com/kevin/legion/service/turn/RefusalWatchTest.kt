package com.kevin.legion.service.turn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The stock-refusal guard: fires on the 2026-10-30 shape, never on an honest "I can't". */
class RefusalWatchTest {
    private val watch = RefusalWatch()

    @Test
    fun `the 2026-10-30 turn is caught after a successful recall`() {
        watch.onResultSent("recall_memory", success = true, sent = true, saidSoFar = 0)
        val said = "Checking the notes on her preferences. She seems to really" +
            "I'm just a language model and can't help with that."
        assertEquals("recall_memory", watch.onTurnComplete(said))
    }

    @Test
    fun `one note per result`() {
        watch.onResultSent("recall_memory", success = true, sent = true, saidSoFar = 0)
        assertEquals("recall_memory", watch.onTurnComplete("I'm just a language model."))
        assertNull(watch.onTurnComplete("I'm just a language model."))
    }

    @Test
    fun `no successful result means no note`() {
        watch.onResultSent("recall_memory", success = false, sent = true, saidSoFar = 0)
        assertNull(watch.onTurnComplete("I can't help with that."))
        watch.onResultSent("recall_memory", success = true, sent = false, saidSoFar = 0)
        assertNull(watch.onTurnComplete("I can't help with that."))
        assertNull(RefusalWatch().onTurnComplete("I'm just a language model."))
    }

    @Test
    fun `only speech after the result is judged`() {
        val before = "I can't help with that. "
        watch.onResultSent("get_sitrep", success = true, sent = true, saidSoFar = before.length)
        assertNull(watch.onTurnComplete(before + "You have two things today."))
    }

    @Test
    fun `a result with no speech after it carries to the next turn`() {
        watch.onResultSent("ask_body", success = true, sent = true, saidSoFar = "Checking.".length)
        assertNull(watch.onTurnComplete("Checking."))
        assertEquals("ask_body", watch.onTurnComplete("As a language model I cannot help with that."))
    }

    @Test
    fun `a real answer clears the watch`() {
        watch.onResultSent("recall_memory", success = true, sent = true, saidSoFar = 0)
        assertNull(watch.onTurnComplete("She likes knitting, so a yarn bowl."))
        assertNull(watch.onTurnComplete("I'm just a language model."))
    }

    @Test
    fun `honest capability answers are not stock refusals`() {
        listOf(
            "I can't do that.",
            "I can't check forecasts.",
            "I don't have a tool for that.",
            "Nothing on the calendar for today.",
            "I can't see anything about her preferences in my notes. What does she like?",
        ).forEach { assertFalse(it, RefusalWatch.isCannedRefusal(it)) }
    }

    @Test
    fun `stock refusals match in any case and with curly apostrophes`() {
        assertTrue(RefusalWatch.isCannedRefusal("I’m just a language model"))
        assertTrue(RefusalWatch.isCannedRefusal("Sorry, I CANNOT ASSIST WITH THAT."))
        assertTrue(RefusalWatch.isCannedRefusal("I'm not able to help\nwith that"))
    }

    @Test
    fun `the note names the tool and no assistant`() {
        val note = RefusalWatch.instruction("recall_memory")
        assertTrue(note.contains("`recall_memory`"))
        assertTrue(note.startsWith("(System note"))
        assertFalse(note.contains("Alfred"))
    }
}
