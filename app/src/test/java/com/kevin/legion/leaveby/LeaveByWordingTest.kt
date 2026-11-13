package com.kevin.legion.leaveby

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LeaveByWordingTest {
    private val zone: ZoneId = ZoneId.of("UTC")
    private fun at(h: Int, m: Int) = ZonedDateTime.of(2026, 11, 13, h, m, 0, 0, zone).toInstant().toEpochMilli()

    private fun notice(leaveBy: Int, leaveByMin: Int, now: Long = at(15, 30), drive: Double = 1500.0) = LeaveByNotice(
        eventId = 7, title = "Dentist", location = "12 Main St", startMs = at(16, 15),
        leaveByMs = at(leaveBy, leaveByMin), driveSeconds = drive, nowMs = now,
    )

    @Test
    fun `leave-by is the start minus the drive minus ten minutes`() {
        // 16:15 start, a 25 minute drive, a 10 minute buffer: 15:40.
        assertEquals(at(15, 40), LeaveByWording.leaveByMs(at(16, 15), 25 * 60.0))
    }

    @Test
    fun `a drive is rounded up to the second so the leave-by never lands late`() {
        assertEquals(at(16, 15) - 1_001 - LeaveByWording.BUFFER_MS, LeaveByWording.leaveByMs(at(16, 15), 1.0001))
    }

    @Test
    fun `the notification says the clock time, the drive and that it is an estimate`() {
        assertEquals(
            "Leave by 3:40 PM for Dentist, about 25 min with traffic now, an estimate.",
            LeaveByWording.notificationText(notice(15, 40), zone),
        )
    }

    @Test
    fun `a leave-by time already well past reads as leave now with the start clock`() {
        val text = LeaveByWording.notificationText(notice(15, 40, now = at(15, 50)), zone)
        assertEquals("Leave now for Dentist, about 25 min with traffic now, an estimate. It starts at 4:15 PM.", text)
    }

    @Test
    fun `a minute or two late is still leave-by the clock`() {
        val text = LeaveByWording.notificationText(notice(15, 40, now = at(15, 41)), zone)
        assertTrue(text.startsWith("Leave by 3:40 PM"))
    }

    @Test
    fun `drive words never round to zero and switch to hours`() {
        assertEquals("about 1 min", LeaveByWording.driveWords(10.0))
        assertEquals("about 1 h 5 min", LeaveByWording.driveWords(65 * 60.0))
    }

    @Test
    fun `nothing spoken or posted says in N minutes about a moment`() {
        val n = notice(15, 40)
        val all = listOf(
            LeaveByWording.notificationText(n, zone), LeaveByWording.facts(n, zone),
            LeaveByWording.prompt(LeaveByWording.facts(n, zone)), LeaveByWording.reason(n, zone),
        ).joinToString("\n")
        // The prompt names the phrase to forbid it, so strip that one sentence before checking.
        val withoutBan = all.replace("never \"in N minutes\"", "")
        assertFalse(Regex("\\bin \\d+ minutes?\\b").containsMatchIn(withoutBan))
    }

    @Test
    fun `the facts name the estimate, the start and the leave-by`() {
        val f = LeaveByWording.facts(notice(15, 40), zone)
        assertTrue(f.contains("ESTIMATE"))
        assertTrue(f.contains("4:15 PM"))
        assertTrue(f.contains("3:40 PM"))
        assertTrue(f.contains("Dentist"))
    }

    @Test
    fun `an event line with no figure says why, in words`() {
        val event = LeaveByEvent(1, "Dentist", at(16, 15), "12 Main St")
        val line = LeaveByWording.eventLine(LeaveByPlan.Unknown(event, LeaveByWording.NO_FIX), zone)
        assertEquals("No leave-by time: the phone has no recent location fix.", line)
    }

    @Test
    fun `an event line with a figure names the place it measured to and says estimate`() {
        val event = LeaveByEvent(1, "Dentist", at(16, 15), "12 Main St")
        val line = LeaveByWording.eventLine(
            LeaveByPlan.Known(event, at(15, 40), 1500.0, "Smile Dental", at(15, 0)), zone,
        )
        assertEquals("Leave by 3:40 PM (about 25 min with traffic at 3:00 PM, to Smile Dental, an estimate)", line)
    }
}
