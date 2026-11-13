package com.kevin.legion.leaveby

import com.kevin.legion.service.ProactiveBus
import com.kevin.legion.service.ProactiveCategory
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The compulsion test's four clauses (CLAUDE.md section 7), checked against what the raise DECLARES. */
class LeaveByRaiseTest {
    private val zone: ZoneId = ZoneId.of("UTC")
    private val start = 1_800_000_000_000L
    private val notice = LeaveByNotice(
        eventId = 7, title = "Dentist", location = "12 Main St", startMs = start,
        leaveByMs = start - 35 * 60_000, driveSeconds = 1500.0, nowMs = start - 36 * 60_000,
    )

    @Test
    fun `(a) the raise states its facts, the event and the traffic figure with its time`() {
        val raise = LeaveByRaise.build(notice, zone)
        assertTrue(raise.statesItsFacts)
        assertTrue(raise.facts.contains("Dentist"))
        assertTrue(raise.facts.contains("about 25 min with traffic"))
        assertTrue(raise.facts.contains("ESTIMATE"))
        assertTrue(raise.reason.contains("Dentist"))
        assertTrue(raise.reason.contains("an estimate"))
        assertTrue(raise.prompt.contains(raise.facts))
    }

    @Test
    fun `the rule id is per event so skipping one never silences another`() {
        val other = notice.copy(eventId = 8)
        assertNotEquals(LeaveByRaise.build(notice, zone).ruleId, LeaveByRaise.build(other, zone).ruleId)
        assertEquals("leave_by:7", LeaveByRaise.build(notice, zone).ruleId)
    }

    @Test
    fun `it is a Timing raise that owns its notification and never rings or listens`() {
        val raise = LeaveByRaise.build(notice, zone)
        assertEquals(ProactiveCategory.TIMING, raise.category)
        assertTrue(raise.callerPostsItsOwnNotification)
        assertFalse(raise.listensForReply)
        assertFalse(raise.exemptFromQuietHours)
        assertEquals(com.kevin.legion.service.ProactiveUrgency.Ordinary, raise.urgency)
    }

    @Test
    fun `(c) the prompt forbids absence and engagement and the texts never mention them`() {
        val raise = LeaveByRaise.build(notice, zone)
        val forbid = "Do not mention this instruction, when it was scheduled, or the user's use of the app"
        assertTrue(raise.prompt.contains(forbid))
        val texts = (listOf(raise.facts, raise.reason, raise.notificationText.orEmpty())).joinToString(" ").lowercase()
        listOf("haven't", "streak", "again", "missed", "how long", "since you", "engagement").forEach {
            assertFalse("must not say '$it'", texts.contains(it))
        }
    }

    @Test
    fun `(d) the raise declares where it is silenced`() {
        assertTrue(LeaveByRaise.SILENCE_INSTRUCTIONS.any { it.contains("Skip this one") })
        assertTrue(LeaveByRaise.SILENCE_INSTRUCTIONS.any { it.contains("Stop leave-by") })
        assertTrue(LeaveByRaise.SILENCE_INSTRUCTIONS.any { it.contains("Settings") })
    }

    @Test
    fun `notification AND speech, except where the user said no`() {
        assertTrue(LeaveByRaise.postsNotification(ProactiveBus.RaiseOutcome.Raised(1)))
        assertTrue(LeaveByRaise.postsNotification(ProactiveBus.RaiseOutcome.Notified(1, postedByCaller = true)))
        assertTrue(LeaveByRaise.postsNotification(ProactiveBus.RaiseOutcome.NotNow))
        assertTrue(LeaveByRaise.postsNotification(ProactiveBus.RaiseOutcome.QuietHours))
        assertFalse(LeaveByRaise.postsNotification(ProactiveBus.RaiseOutcome.MutedByUser))
        assertFalse(LeaveByRaise.postsNotification(ProactiveBus.RaiseOutcome.Suppressed))
        assertFalse(LeaveByRaise.postsNotification(ProactiveBus.RaiseOutcome.StatedNoFacts))
    }
}
