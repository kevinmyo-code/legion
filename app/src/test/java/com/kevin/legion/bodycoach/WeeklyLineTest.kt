package com.kevin.legion.bodycoach

import com.kevin.legion.finance.FinanceRead
import com.kevin.legion.service.ProactiveCategory
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** fat-loss ticket 13: the Monday line speaks only on fresh facts, and never about absence. */
class WeeklyLineTest {

    private val facts = "Trend 243.0 lb, down 0.7 lb a week (0.29% of bodyweight), below the 0.5-1.0% band. " +
        "Goal 230 lb: 13.0 lb to go."

    @Test
    fun `say false or no facts raises nothing`() {
        assertNull(WeeklyLine.buildRaise(WeeklyLineWire(say = false, facts = facts, reason = "weekly line is off")))
        assertNull(WeeklyLine.buildRaise(WeeklyLineWire(say = true, facts = " ")))
        assertNull(WeeklyLine.buildRaise(null))
    }

    @Test
    fun `a fresh answer raises one wellbeing line under its own rule id`() {
        val raise = WeeklyLine.raiseFrom(FinanceRead.Fresh(WeeklyLineWire(say = true, facts = facts, reason = "enough data")))
        assertNotNull(raise)
        assertEquals("weekly_weight_line", raise!!.ruleId)
        assertEquals(ProactiveCategory.WELLBEING, raise.category)
        assertTrue(raise.prompt.contains(facts))
    }

    @Test
    fun `a saved copy is last week's facts and never speaks`() {
        val saved = FinanceRead.Saved(WeeklyLineWire(say = true, facts = facts), 0L, "Saved 7 days ago.")
        assertNull(WeeklyLine.raiseFrom(saved))
    }

    @Test
    fun `the prompt forbids absence, streaks and scolding, and names the off instruction`() {
        val prompt = WeeklyLine.buildPrompt(facts)
        assertTrue(prompt.contains("Never mention missed weigh-ins"))
        assertTrue(prompt.contains("any streak"))
        assertTrue(prompt.contains("stop the weekly weight line"))
        assertFalse(prompt.contains("you missed"))
    }

    @Test
    fun `next trigger is the coming Monday at 8, or next week once it has passed`() {
        val zone = ZoneId.of("UTC")
        fun at(y: Int, m: Int, d: Int, h: Int) = LocalDateTime.of(y, m, d, h, 0).atZone(zone).toInstant().toEpochMilli()
        val thursday = at(2026, 11, 5, 21)
        val next = Instant.ofEpochMilli(WeeklyLine.nextMondayEight(thursday, zone)).atZone(zone)
        assertEquals(DayOfWeek.MONDAY, next.dayOfWeek)
        assertEquals(9, next.dayOfMonth)
        assertEquals(8, next.hour)
        val mondayNine = at(2026, 11, 9, 9)
        assertEquals(16, Instant.ofEpochMilli(WeeklyLine.nextMondayEight(mondayNine, zone)).atZone(zone).dayOfMonth)
        val mondaySeven = at(2026, 11, 9, 7)
        assertEquals(9, Instant.ofEpochMilli(WeeklyLine.nextMondayEight(mondaySeven, zone)).atZone(zone).dayOfMonth)
    }
}
