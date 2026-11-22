@file:Suppress("MaxLineLength") // fixtures and phrase lists read best on one line

package com.kevin.legion.service.tools.day

import java.time.LocalDate
import java.time.LocalTime
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-22: after a read of tomorrow, a follow-up add at a clock time with no day went on today.
 * The next turn's clock-timed write that names no day goes on the later day the last turn was about;
 * never a relative time, never the past, never a turn after that. All wording invented.
 */
class DateCarryTest {
    private val today = LocalDate.of(2027, 3, 9)
    private val tomorrow = today.plusDays(1)

    /** A turn's second date call (a refused try came first), carrying [about]. */
    private fun turn(about: LocalDate?) = RelativeDay.TurnDates(today, LocalTime.of(10, 0), onlyDateCall = false, aboutDay = about)

    private fun add(date: String, time: String? = "19:00", kind: String = "appointment") = JSONObject()
        .put("action", "add").put("kind", kind).put("item", "Team supper").put("date", date)
        .apply { if (time != null) put("time", time) }

    @Test
    fun `a clock-timed add on today with no day said goes on the later day the last turn was about`() {
        val (fixed, note) = DateCarry.carried("manage_item", add(today.toString()), "also book a table for two at 7:00", turn(tomorrow))!!
        assertEquals(tomorrow.toString(), fixed.getString("date"))
        assertEquals("19:00", fixed.getString("time"))
        assertTrue(note, note.contains("Wednesday 2027-03-10"))
        // Reached through the resolved door too, and a timed reminder carries like an appointment.
        val reminder = add("today", time = "18:00", kind = "reminder")
        assertEquals(tomorrow.toString(), DateCarry.resolved("manage_item", reminder, "remind me at six to pack the bag", turn(tomorrow))!!.first.getString("date"))
    }

    @Test
    fun `a relative time is never carried`() {
        for (words in listOf(
            "remind me in 20 minutes to check the oven", "remind me in an hour", "remind me now", "remind me later at 5",
            "remind me soon", "book it for tonight at 8", "book it this evening at 7", "book it at 7 in the morning",
        )) {
            assertNull(words, DateCarry.carried("manage_item", add(today.toString(), kind = "reminder"), words, turn(tomorrow)))
        }
    }

    @Test
    fun `no clock time, a named day, the past or no later day changes nothing`() {
        assertNull(DateCarry.carried("manage_item", add(today.toString(), time = null), "add the team supper", turn(tomorrow)))
        assertNull(DateCarry.carried("manage_item", add(today.toString()), "add the team supper", turn(tomorrow)))
        for (words in listOf("book it today at 7", "book it Friday at 7", "book it on the 12th at 7", "book it tomorrow at 7")) {
            assertNull(words, DateCarry.carried("manage_item", add(today.toString()), words, turn(tomorrow)))
        }
        assertNull("never into the past", DateCarry.carried("manage_item", add(today.toString()), "book it at 7", turn(today.minusDays(1))))
        assertNull(DateCarry.carried("manage_item", add(today.toString()), "book it at 7", turn(today)))
        assertNull(DateCarry.carried("manage_item", add(today.toString()), "book it at 7", turn(null)))
        assertNull(DateCarry.carried("manage_item", add("2027-03-12"), "book it at 7", turn(tomorrow)))
        assertNull(DateCarry.carried("manage_item", add(today.toString()), null, turn(tomorrow)))
        val tick = JSONObject().put("action", "tick").put("item", "supper").put("date", today.toString()).put("time", "19:00")
        assertNull(DateCarry.carried("manage_item", tick, "tick supper at 7", turn(tomorrow)))
    }

    @Test
    fun `a clock time is words or digits, never a relative span`() {
        assertTrue(DateCarry.clockTimeOnly("put coffee with Kim at 7:00"))
        assertTrue(DateCarry.clockTimeOnly("add lunch at half past twelve"))
        assertTrue(DateCarry.clockTimeOnly("add lunch at noon"))
        assertFalse(DateCarry.clockTimeOnly("add lunch"))
        assertFalse(DateCarry.clockTimeOnly("add lunch in two hours"))
        assertFalse(DateCarry.clockTimeOnly("add lunch on March 3rd at one"))
        assertFalse(DateCarry.clockTimeOnly(""))
    }

    @Test
    fun `the carried day lasts one turn, its retries included`() {
        val day = ConversationDay()
        day.note("read_calendar", JSONObject().put("from", "tomorrow").put("to", "tomorrow"), ok = true, today = today)
        assertNull("not in the turn that set it", day.day)

        day.startTurn()
        assertEquals(tomorrow, day.day)
        day.note("manage_item", JSONObject().put("action", "add").put("item", "x"), ok = false, today = today)
        assertEquals("a refused try leaves the turn's day for the retry", tomorrow, day.day)

        day.startTurn()
        assertNull("stale two turns on", day.day)
    }

    @Test
    fun `a dated add sets the next turn's day, a range or another kind of call does not`() {
        val day = ConversationDay()
        day.note("manage_item", add("2027-03-12"), ok = true, today = today)
        day.startTurn()
        assertEquals(LocalDate.of(2027, 3, 12), day.day)

        day.note("read_calendar", JSONObject().put("from", today.toString()).put("to", today.plusDays(6).toString()), ok = true, today = today)
        day.startTurn()
        assertNull(day.day)

        day.note("manage_item", JSONObject().put("action", "tick").put("item", "x").put("date", "2027-03-12"), ok = true, today = today)
        day.note("get_weather", JSONObject(), ok = true, today = today)
        day.startTurn()
        assertNull(day.day)
    }
}
