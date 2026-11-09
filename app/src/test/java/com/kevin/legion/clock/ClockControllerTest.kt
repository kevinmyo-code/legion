package com.kevin.legion.clock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** The one clock controller over fakes (alfred-clock tickets 01, 03, 04): every voice and screen action lands here. */
class ClockControllerTest {
    private val minute = 60_000L

    @Test
    fun `a one-off alarm set at night arms tomorrow morning and says when`() {
        val rig = ClockRig()
        val r = rig.clock.alarms.set(7, 0, emptySet(), "Gym")
        assertTrue(r.ok)
        assertEquals(rig.ms("2026-11-10T07:00"), rig.scheduler.alarms[1])
        assertTrue(r.message, r.message.contains("tomorrow at 07:00"))
        assertTrue(r.message, r.message.contains("in 9 hours"))
    }

    @Test
    fun `a fired alarm rings, snooze re-arms nine minutes out, dismiss of a one-off turns it off`() {
        val rig = ClockRig()
        rig.clock.alarms.set(7, 0, emptySet(), "")
        rig.time.now = rig.ms("2026-11-10T07:00")
        assertNotNull(rig.clock.alarms.fired(1))
        assertEquals(1, rig.ringer.ringing.size)
        rig.clock.alarms.snooze(1)
        assertTrue(rig.ringer.ringing.isEmpty())
        assertEquals(rig.time.now + ClockDefaults.SNOOZE_MIN * minute, rig.scheduler.alarms[1])
        rig.time.advance(9 * minute)
        rig.clock.alarms.fired(1)
        rig.clock.alarms.dismiss(1)
        assertFalse(rig.store.load().alarms.single().enabled)
        assertNull(rig.scheduler.alarms[1])
    }

    @Test
    fun `dismissing a repeating alarm arms its next day`() {
        val rig = ClockRig()
        rig.clock.alarms.set(7, 0, AlarmMath.WEEKDAYS, "")
        rig.time.now = rig.ms("2026-11-10T07:00") // Tuesday
        rig.clock.alarms.fired(1)
        rig.clock.alarms.dismiss(1)
        assertTrue(rig.store.load().alarms.single().enabled)
        assertEquals(rig.ms("2026-11-11T07:00"), rig.scheduler.alarms[1])
    }

    @Test
    fun `an alarm turned off since it was armed does not ring`() {
        val rig = ClockRig()
        rig.clock.alarms.set(7, 0, emptySet(), "")
        rig.clock.alarms.setEnabled(1, false)
        assertNull(rig.clock.alarms.fired(1))
        assertTrue(rig.ringer.ringing.isEmpty())
    }

    @Test
    fun `reboot re-arm reports a one-off missed while the phone was off, and rings one still inside its window`() {
        val store = MemoryClockStore(
            ClockState(
                alarms = listOf(
                    ClockAlarm(id = 1, hour = 6, minute = 0, date = LocalDate.parse("2026-11-10")),
                    ClockAlarm(id = 2, hour = 7, minute = 55, date = LocalDate.parse("2026-11-10")),
                    ClockAlarm(id = 3, hour = 7, minute = 0, days = AlarmMath.EVERY_DAY),
                ),
                nextId = 4,
            ),
        )
        val rig = ClockRig(time = FakeTime.at("2026-11-10T08:00"), store = store)
        rig.clock.rearmAll()
        assertEquals(listOf(1), rig.notices.missed.map { it.id })
        assertFalse(store.load().alarms.first { it.id == 1 }.enabled)
        assertNull(rig.scheduler.alarms[1])
        assertEquals(rig.time.now, rig.scheduler.alarms[2]) // 5 minutes late: rings at once
        assertEquals(rig.ms("2026-11-11T07:00"), rig.scheduler.alarms[3])
    }

    @Test
    fun `a time zone change re-arms the same 7 00 at its new instant`() {
        val rig = ClockRig()
        rig.clock.alarms.set(7, 0, AlarmMath.EVERY_DAY, "")
        val before = rig.scheduler.alarms[1]!!
        rig.time.zoneId = ZoneId.of("America/New_York")
        rig.clock.rearmAll()
        assertEquals(before - 60 * minute, rig.scheduler.alarms[1])
    }

    @Test
    fun `voice cancel deletes a one-off, turns a repeating alarm off, and refuses when several match`() {
        val rig = ClockRig()
        rig.clock.alarms.set(7, 0, emptySet(), "")
        rig.clock.alarms.set(6, 30, AlarmMath.WEEKDAYS, "Work")
        rig.clock.alarms.set(9, 0, AlarmMath.WEEKEND, "Work")
        val several = rig.clock.alarms.cancel(null, "work")
        assertFalse(several.ok)
        assertEquals(3, rig.scheduler.alarms.size)
        assertTrue(rig.clock.alarms.cancel("7:00", null).ok)
        assertEquals(2, rig.store.load().alarms.size)
        assertTrue(rig.clock.alarms.cancel("06:30", null).ok)
        assertFalse(rig.store.load().alarms.first { it.hour == 6 }.enabled)
        assertFalse(rig.clock.alarms.cancel("11:00", null).ok)
    }

    @Test
    fun `alarm list says each alarm and when it rings`() {
        val rig = ClockRig()
        assertEquals("There are no alarms.", rig.clock.alarms.list().message)
        rig.clock.alarms.set(7, 0, AlarmMath.WEEKDAYS, "Gym")
        val m = rig.clock.alarms.list().message
        assertTrue(m, m.contains("07:00 weekdays, Gym") && m.contains("tomorrow at 07:00"))
    }

    @Test
    fun `timers start, pause, resume, say what is left, and ring when done`() {
        val rig = ClockRig()
        assertTrue(rig.clock.timers.start(600, "pasta").ok)
        val armed = rig.scheduler.timers[1]!!
        assertEquals(rig.time.elapsed + 600_000, armed)
        rig.time.advance(4 * minute)
        assertTrue(rig.clock.timers.left(null).message.contains("6 minutes"))
        assertTrue(rig.clock.timers.pause("pasta").ok)
        assertNull(rig.scheduler.timers[1])
        rig.time.advance(10 * minute)
        assertTrue(rig.clock.timers.resume(null).ok)
        assertEquals(rig.time.elapsed + 6 * minute, rig.scheduler.timers[1])
        rig.time.advance(6 * minute)
        assertNotNull(rig.clock.timers.fired(1))
        assertEquals(RingKind.TIMER, rig.ringer.ringing.single().kind)
        rig.clock.timers.remove(1)
        assertTrue(rig.store.load().timers.isEmpty())
        assertTrue(rig.ringer.ringing.isEmpty())
    }

    @Test
    fun `with several timers an unnamed pause changes nothing and names them`() {
        val rig = ClockRig()
        rig.clock.timers.start(60, "eggs")
        rig.clock.timers.start(120, "tea")
        val r = rig.clock.timers.pause(null)
        assertFalse(r.ok)
        assertTrue(r.message, r.message.contains("eggs") && r.message.contains("tea"))
        assertTrue(rig.clock.timers.left(null).message.contains("eggs"))
        assertFalse(rig.clock.timers.cancel("rice").ok)
        assertTrue(rig.clock.timers.cancel("tea").ok)
        assertTrue(rig.clock.timers.start(0, null).message.contains("Nothing was started"))
    }

    @Test
    fun `a running timer survives a reboot on the wall clock`() {
        val rig = ClockRig()
        rig.clock.timers.start(600, "bread")
        rig.time.now += 4 * minute
        rig.time.elapsed = 5_000
        rig.time.boot = 2
        rig.clock.rearmAll()
        assertEquals(5_000 + 6 * minute, rig.scheduler.timers[1])
        assertEquals(2, rig.store.load().timers.single().bootCount)
    }

    @Test
    fun `the stopwatch runs, laps, stops and resets`() {
        val rig = ClockRig()
        assertFalse(rig.clock.stopwatch.lap().ok)
        rig.clock.stopwatch.start()
        rig.time.advance(65_000)
        assertTrue(rig.clock.stopwatch.lap().message.startsWith("Lap 1: 1 minute 5 seconds"))
        rig.time.advance(30_000)
        assertTrue(rig.clock.stopwatch.stop().message.contains("1 minute 35 seconds"))
        rig.time.advance(60_000)
        assertEquals(95_000L, rig.clock.stopwatch.total())
        assertTrue(rig.clock.stopwatch.start().message.contains("resumed"))
        rig.clock.stopwatch.reset()
        assertEquals(0L, rig.clock.stopwatch.total())
    }

    @Test
    fun `bedtime turns on Quiet and the feed block, and the alarm's dismiss turns off only what it turned on`() {
        val rig = ClockRig(effects = FakeEffects(quiet = true, feed = false))
        rig.clock.alarms.set(7, 0, emptySet(), "")
        assertTrue(rig.clock.bedtime.startNow().ok)
        assertTrue(rig.effects.feed)
        assertNotNull(rig.notices.bedtimeSentence)
        assertTrue(rig.notices.bedtimeSentence!!.contains("tomorrow at 07:00"))
        rig.time.now = rig.ms("2026-11-10T07:00")
        rig.clock.alarms.fired(1)
        rig.clock.alarms.dismiss(1)
        assertFalse(rig.store.load().bedtime.active)
        assertFalse("bedtime turned the feed block on, so it goes off", rig.effects.feed)
        assertTrue("Quiet was on before bedtime, so it stays on", rig.effects.quiet)
        assertNull(rig.notices.bedtimeSentence)
    }

    @Test
    fun `snoozing does not end bedtime`() {
        val rig = ClockRig()
        rig.clock.alarms.set(7, 0, emptySet(), "")
        rig.clock.bedtime.startNow()
        rig.time.now = rig.ms("2026-11-10T07:00")
        rig.clock.alarms.snooze(1)
        assertTrue(rig.store.load().bedtime.active)
        assertTrue(rig.effects.quiet)
    }

    @Test
    fun `bedtime ending before the first unlock waits for it, then restores`() {
        val effects = FakeEffects()
        val rig = ClockRig(effects = effects)
        rig.clock.bedtime.startNow()
        effects.isUnlocked = false
        assertTrue(rig.clock.bedtime.end().ok)
        assertTrue("still on until unlock", effects.quiet && effects.feed)
        assertTrue(rig.store.load().bedtime.pendingEnd)
        effects.isUnlocked = true
        rig.clock.rearmAll()
        assertFalse(effects.quiet || effects.feed)
        assertFalse(rig.store.load().bedtime.pendingEnd)
    }

    @Test
    fun `without Do Not Disturb access bedtime still blocks feeds and says why Quiet stayed off`() {
        val rig = ClockRig(effects = FakeEffects(dndGranted = false))
        val r = rig.clock.bedtime.startNow()
        assertTrue(r.message, r.message.contains("Do Not Disturb"))
        assertTrue(rig.effects.feed)
        assertFalse(rig.store.load().bedtime.quietByBedtime)
    }

    @Test
    fun `a bedtime schedule arms the wind-down half an hour before, and voice off turns it off`() {
        val rig = ClockRig(time = FakeTime.at("2026-11-09T12:00"))
        assertTrue(rig.clock.bedtime.voiceOn("22:30").ok)
        assertEquals(rig.ms("2026-11-09T22:00") to rig.ms("2026-11-09T22:30"), rig.scheduler.bedtime)
        assertTrue(rig.clock.bedtime.voiceOff().message.contains("schedule is off"))
        assertNull(rig.scheduler.bedtime)
        assertFalse(rig.clock.bedtime.voiceOff().ok)
    }
}
