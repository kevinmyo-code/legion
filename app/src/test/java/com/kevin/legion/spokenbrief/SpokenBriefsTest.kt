package com.kevin.legion.spokenbrief

import com.kevin.legion.calendar.OpenerCalendarBriefing
import com.kevin.legion.clock.RingKind
import com.kevin.legion.service.ProactiveCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * ADR 0082's two spoken briefs on their pure halves: the compulsion-test registry, the spoken stop,
 * when the wake-up brief may try, when an arrival home may brief, and what each raise is allowed to
 * say. Made-up events only.
 */
class SpokenBriefsTest {

    // ---------------------------------------------------------------- the registry (clauses a, d)

    @Test
    fun `every spoken brief declares an anchor and a one-instruction silence`() {
        SpokenBrief.entries.forEach {
            assertTrue("${it.name} anchor", it.anchor.isNotBlank())
            assertTrue("${it.name} silence", it.silenceInstruction.startsWith("stop the "))
        }
    }

    @Test
    fun `each brief's own silence instruction stops that brief and no other`() {
        SpokenBrief.entries.forEach { assertEquals(it, SpokenBriefs.match(it.silenceInstruction)) }
    }

    @Test
    fun `the spoken stop tolerates the usual phrasings`() {
        assertEquals(SpokenBrief.WAKE, SpokenBriefs.match("Stop the morning brief."))
        assertEquals(SpokenBrief.WAKE, SpokenBriefs.match("the wake-up brief"))
        assertEquals(SpokenBrief.HOME, SpokenBriefs.match("home brief"))
        assertNull(SpokenBriefs.match("the weekly weight line"))
        assertNull(SpokenBriefs.match("  "))
    }

    @Test
    fun `rule ids and setting keys are stable and cannot collide with a category key`() {
        assertEquals("wake_brief", SpokenBrief.WAKE.ruleId)
        assertEquals("home_brief", SpokenBrief.HOME.ruleId)
        val categoryKeys = ProactiveCategory.entries.map { it.key }.toSet()
        SpokenBrief.entries.forEach { assertFalse(it.settingKey in categoryKeys) }
    }

    @Test
    fun `both briefs raise as Digest`() {
        assertEquals(ProactiveCategory.DIGEST, WakeBrief.buildRaise("x", LocalTime.of(7, 0)).category)
        assertEquals(ProactiveCategory.DIGEST, HomeBrief.buildRaise("x").category)
    }

    // ---------------------------------------------------------------- the wake-up brief

    private val today = LocalDate.of(2026, 3, 2)

    @Test
    fun `a morning alarm dismissed after unlock may brief once a day`() {
        assertTrue(WakeBrief.shouldTry(RingKind.ALARM, true, LocalTime.of(6, 30), today, null))
        assertTrue(WakeBrief.shouldTry(RingKind.ALARM, true, LocalTime.of(4, 0), today, today.minusDays(1)))
        val again = WakeBrief.shouldTry(RingKind.ALARM, true, LocalTime.of(6, 30), today, today)
        assertFalse("already briefed today", again)
    }

    @Test
    fun `never a timer, an emergency ring, before the first unlock, or outside 4 to 11`() {
        assertFalse(WakeBrief.shouldTry(RingKind.TIMER, true, LocalTime.of(7, 0), today, null))
        assertFalse(WakeBrief.shouldTry(RingKind.ALERT, true, LocalTime.of(7, 0), today, null))
        assertFalse(WakeBrief.shouldTry(RingKind.ALARM, false, LocalTime.of(7, 0), today, null))
        assertFalse(WakeBrief.shouldTry(RingKind.ALARM, true, LocalTime.of(3, 59), today, null))
        assertFalse(WakeBrief.shouldTry(RingKind.ALARM, true, LocalTime.of(11, 0), today, null))
        assertFalse(WakeBrief.shouldTry(RingKind.ALARM, true, LocalTime.of(14, 0), today, null))
    }

    @Test
    fun `the wake-up raise carries the brief as its facts and is marked read-through`() {
        val raise = WakeBrief.buildRaise("BRIEF built at 06:00.\nCALENDAR \"Standup\" at 09:30", LocalTime.of(6, 45))
        assertTrue(raise.statesItsFacts)
        assertTrue(raise.prompt.contains("\"Standup\" at 09:30"))
        assertTrue("newsletters may ride in it", raise.carriesReadThroughContent)
        assertTrue(raise.reason.contains("06:45"))
        assertFalse("it never opens the mic", raise.listensForReply)
    }

    @Test
    fun `the wake-up prompt forbids invention and every engagement subject`() {
        val p = WakeBrief.prompt("facts").lowercase()
        assertTrue(p.contains("use only these facts"))
        assertTrue(p.contains("how long they slept"))
        assertTrue(p.contains("snoozed"))
        assertTrue(p.contains("how often they use the app"))
        assertTrue(p.contains("streak"))
    }

    // ---------------------------------------------------------------- the home brief

    @Test
    fun `an arrival home briefs only after a departure, at most once in 6 hours`() {
        val h = 60L * 60 * 1000
        val now = 100 * h
        assertFalse("no departure ever seen (a restart at home)", HomeBrief.mayBrief(null, null, now))
        assertTrue(HomeBrief.mayBrief(now - h, null, now))
        assertTrue(HomeBrief.mayBrief(now - h, now - 7 * h, now))
        assertFalse("too soon", HomeBrief.mayBrief(now - h, now - 2 * h, now))
        assertFalse("no departure since the last brief", HomeBrief.mayBrief(now - 8 * h, now - 7 * h, now))
    }

    private val zone = ZoneId.of("UTC")
    private fun at(hour: Int, minute: Int = 0, day: LocalDate = today) =
        day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
    private fun event(title: String, start: Long) =
        OpenerCalendarBriefing.BriefingEvent(title, start, start + 3_600_000)

    @Test
    fun `home facts name what is left today, tomorrow's first event and what is waiting`() {
        val facts = HomeBrief.composeFacts(
            stillToday = listOf(event("Call the garage", at(19))),
            tomorrow = listOf(
                event("Lab", at(13, day = today.plusDays(1))),
                event("Gym", at(8, day = today.plusDays(1))),
            ),
            undatedTodos = listOf("Order filters"),
            homeReminders = listOf("Water the plants"),
            zone = zone,
            today = today,
        )
        assertTrue(facts, facts.contains("STILL TODAY \"Call the garage\" at"))
        assertTrue("tomorrow's FIRST event, not the first listed", facts.contains("TOMORROW FIRST \"Gym\""))
        assertFalse(facts.contains("Lab"))
        assertTrue(facts.contains("reminders left for home: \"Water the plants\""))
        assertTrue(facts.contains("to-dos with no date: \"Order filters\""))
    }

    @Test
    fun `unreadable is never said as empty`() {
        val facts = HomeBrief.composeFacts(null, null, null, emptyList(), zone, today)
        assertTrue(facts.contains("STILL TODAY the calendar could not be read"))
        assertTrue(facts.contains("TOMORROW FIRST the calendar could not be read"))
        assertTrue(facts.contains("could not be read"))
        assertFalse(facts.contains("nothing else on the calendar"))
    }

    @Test
    fun `empty is said as empty`() {
        val facts = HomeBrief.composeFacts(emptyList(), emptyList(), emptyList(), emptyList(), zone, today)
        assertTrue(facts.contains("nothing else on the calendar today"))
        assertTrue(facts.contains("nothing on the calendar tomorrow"))
        assertTrue(facts.contains("no reminders left for home and no undated to-dos"))
    }

    @Test
    fun `a long list is capped and the rest counted, never silently dropped`() {
        val many = (1..8).map { event("Item $it", at(18, it)) }
        val facts = HomeBrief.composeFacts(many, emptyList(), emptyList(), emptyList(), zone, today)
        assertTrue(facts.contains("and 3 more"))
    }

    @Test
    fun `the home raise posts its facts, not just the reason, when it cannot speak`() {
        val raise = HomeBrief.buildRaise("STILL TODAY nothing")
        assertEquals("STILL TODAY nothing", raise.notificationText)
        assertFalse(raise.carriesReadThroughContent)
    }

    @Test
    fun `the home prompt forbids remarking on his absence`() {
        val p = HomeBrief.prompt("facts").lowercase()
        assertTrue(p.contains("do not welcome them back"))
        assertTrue(p.contains("how long they were out"))
        assertTrue(p.contains("never that they are free"))
        assertTrue(p.contains("streak"))
    }
}
