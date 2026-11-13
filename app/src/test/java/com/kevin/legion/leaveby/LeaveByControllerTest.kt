package com.kevin.legion.leaveby

import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LeaveByControllerTest {
    private val zone: ZoneId = ZoneId.of("UTC")
    private val min = 60_000L
    private var now = 1_800_000_000_000L

    private val eventList = mutableListOf<LeaveByEvent>()
    private var calendarThrows = false
    private var driveAnswer: LeaveByDrive = LeaveByDrive.Ok(25 * 60.0, "Smile Dental")
    private var driveCalls = 0

    private class Armed(val atMs: Long, val stage: LeaveByAlarm.Stage)
    private var armed: Armed? = null
    private var cancels = 0
    private val delivered = mutableListOf<LeaveByNotice>()

    private val store = object : LeaveByStore {
        var on = true
        val silenced = mutableSetOf<Long>()
        val firedKeys = mutableSetOf<String>()
        var pend: LeaveByPending? = null
        override fun enabled() = on
        override fun setEnabled(on: Boolean) { this.on = on }
        override fun silenced(eventId: Long) = eventId in silenced
        override fun silence(eventId: Long) { silenced += eventId }
        override fun fired(key: String) = key in firedKeys
        override fun markFired(key: String, startMs: Long) { firedKeys += key }
        override fun pending() = pend
        override fun setPending(pending: LeaveByPending?) { pend = pending }
    }

    private val controller = LeaveByController(
        events = LeaveByEvents { from, to ->
            if (calendarThrows) error("unreadable")
            eventList.filter { it.startMs in from..to }
        },
        drive = LeaveByDriveSource { driveCalls++; driveAnswer },
        store = store,
        alarm = object : LeaveByAlarm {
            override fun arm(atMs: Long, stage: LeaveByAlarm.Stage) { armed = Armed(atMs, stage) }
            override fun cancel() { armed = null; cancels++ }
        },
        deliver = { delivered += it },
        nowMs = { now },
        zone = { zone },
    )

    private fun event(id: Long = 1, startInMin: Long, location: String = "12 Main St") =
        LeaveByEvent(id, "Dentist", now + startInMin * min, location).also { eventList += it }

    @Test
    fun `an event two hours out arms a recheck five minutes before its leave-by time`() = runBlocking {
        val e = event(startInMin = 120)
        controller.refresh()
        // 25 min drive + 10 min buffer: leave at start - 35 min; recheck 5 min earlier.
        assertEquals(e.startMs - 40 * min, armed?.atMs)
        assertEquals(LeaveByAlarm.Stage.RECHECK, armed?.stage)
        assertTrue(controller.lines.value.getValue(1).startsWith("Leave by "))
        assertTrue(delivered.isEmpty())
    }

    @Test
    fun `inside the lead the figures are the recheck and fire is armed for the leave-by time`() = runBlocking {
        val e = event(startInMin = 38)
        controller.refresh()
        assertEquals(LeaveByAlarm.Stage.FIRE, armed?.stage)
        assertEquals(e.startMs - 35 * min, armed?.atMs)
        assertNotNull(store.pend)
    }

    @Test
    fun `fire delivers once, with the figures, and never again for the same start`() = runBlocking {
        val e = event(startInMin = 38)
        controller.refresh()
        now = e.startMs - 35 * min
        controller.onAlarm(LeaveByAlarm.Stage.FIRE)
        assertEquals(1, delivered.size)
        assertEquals(e.startMs - 35 * min, delivered.single().leaveByMs)
        assertEquals(25 * 60.0, delivered.single().driveSeconds, 0.0)
        // Another refresh and another fire with the same start: still one.
        controller.refresh()
        controller.onAlarm(LeaveByAlarm.Stage.FIRE)
        assertEquals(1, delivered.size)
        assertNull("nothing left to arm", armed)
    }

    @Test
    fun `a rescheduled event is a new start and may be raised once more`() = runBlocking {
        val e = event(startInMin = 38)
        controller.refresh(); now = e.startMs - 35 * min
        controller.onAlarm(LeaveByAlarm.Stage.FIRE)
        eventList.clear()
        val moved = e.copy(startMs = e.startMs + 60 * min).also { eventList += it }
        now = moved.startMs - 38 * min
        controller.refresh()
        now = moved.startMs - 35 * min
        controller.onAlarm(LeaveByAlarm.Stage.FIRE)
        assertEquals(2, delivered.size)
    }

    @Test
    fun `no figure means no alarm, no raise and a line that says why`() = runBlocking {
        event(startInMin = 38)
        driveAnswer = LeaveByDrive.Unavailable(LeaveByWording.NO_FIX)
        controller.refresh()
        assertNull(armed)
        assertNull(store.pend)
        assertEquals("No leave-by time: the phone has no recent location fix.", controller.lines.value.getValue(1))
        now += 40 * min
        controller.onAlarm(LeaveByAlarm.Stage.FIRE)
        assertTrue(delivered.isEmpty())
    }

    @Test
    fun `a blank location, an all-day style past start, and events beyond three hours get nothing`() = runBlocking {
        event(id = 1, startInMin = 60, location = "  ")
        event(id = 2, startInMin = 200)
        eventList += LeaveByEvent(3, "Past", now - 5 * min, "Somewhere")
        controller.refresh()
        assertEquals(0, driveCalls)
        assertTrue(controller.lines.value.isEmpty())
        assertNull(armed)
    }

    @Test
    fun `a silenced event is not estimated, not armed and says so`() = runBlocking {
        event(startInMin = 120)
        store.silence(1)
        controller.refresh()
        assertEquals(0, driveCalls)
        assertEquals(LeaveByWording.SILENCED_LINE, controller.lines.value.getValue(1))
        assertNull(armed)
    }

    @Test
    fun `silencing after the arm stops the fire`() = runBlocking {
        val e = event(startInMin = 38)
        controller.refresh()
        controller.silenceEvent(1)
        now = e.startMs - 35 * min
        controller.onAlarm(LeaveByAlarm.Stage.FIRE)
        assertTrue(delivered.isEmpty())
    }

    @Test
    fun `turned off asks for nothing, clears everything and cancels the alarm`() = runBlocking {
        event(startInMin = 120)
        controller.refresh()
        assertNotNull(armed)
        controller.setEnabled(false)
        driveCalls = 0
        controller.refresh()
        assertEquals(0, driveCalls)
        assertNull(armed)
        assertTrue(controller.lines.value.isEmpty())
    }

    @Test
    fun `an unreadable calendar claims nothing new and keeps the alarm that was armed`() = runBlocking {
        event(startInMin = 120)
        controller.refresh()
        val before = armed
        val cancelsBefore = cancels
        calendarThrows = true
        controller.refresh()
        assertEquals(before, armed)
        assertEquals(cancelsBefore, cancels)
    }

    @Test
    fun `a fire that arrives far too late is dropped`() = runBlocking {
        val e = event(startInMin = 38)
        controller.refresh()
        now = e.startMs - 35 * min + LeaveByController.LATE_GRACE_MS + min
        controller.onAlarm(LeaveByAlarm.Stage.FIRE)
        assertTrue(delivered.isEmpty())
    }

    @Test
    fun `an event that moved or vanished since the recheck is not raised`() = runBlocking {
        val e = event(startInMin = 38)
        controller.refresh()
        eventList.clear()
        now = e.startMs - 35 * min
        controller.onAlarm(LeaveByAlarm.Stage.FIRE)
        assertTrue(delivered.isEmpty())
    }

    @Test
    fun `a drive under two minutes means he is already there and nothing is raised`() = runBlocking {
        val e = event(startInMin = 15)
        driveAnswer = LeaveByDrive.Ok(60.0, "Here")
        controller.refresh()
        now = e.startMs - 11 * min
        controller.onAlarm(LeaveByAlarm.Stage.FIRE)
        assertTrue(delivered.isEmpty())
    }

    @Test
    fun `the estimate is not asked for more often than every fifteen minutes`() = runBlocking {
        event(startInMin = 120)
        assertTrue(controller.maybeRefresh())
        val calls = driveCalls
        now += 14 * min
        assertFalse(controller.maybeRefresh())
        assertEquals(calls, driveCalls)
        now += 2 * min
        assertTrue(controller.maybeRefresh())
    }

    @Test
    fun `at most three events cost a drive-time request`() = runBlocking {
        (1L..5L).forEach { event(id = it, startInMin = 60 + it) }
        controller.refresh()
        assertEquals(3, driveCalls)
    }
}
