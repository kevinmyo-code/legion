package com.kevin.legion.projects

import com.kevin.legion.projects.ProjectsReachability.Reading
import com.kevin.legion.projects.ProjectsReachability.Unreadable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Guards the projects staleness contract - plain JUnit, no Android types, same posture as
 * [com.kevin.legion.calendar.OpenerCalendarBriefingTest]. All fixtures invented.
 *
 * The cases this file exists for are the three that must never collapse into each other:
 * `cannot see`, `saw and empty`, and `saw but stale`. Collapsing the first into the second is how
 * the app tells Kevin he is clear when it has no idea.
 */
class ProjectsReachabilityTest {

    private val zone: ZoneId = ZoneId.of("America/Chicago")

    private fun at(day: Int, hour: Int): Long =
        LocalDateTime.of(2026, 9, day, hour, 0).atZone(zone).toInstant().toEpochMilli()

    private val now = at(day = 10, hour = 9)

    private fun describe(reading: Reading) =
        ProjectsReachability.describe(reading, nowMs = now, zone = zone)

    // --- the three states, one test each (ticket 07's verification) --------------------------

    @Test
    fun `cannot see never reads as nothing open`() {
        val blind = describe(Reading.CannotSee("Azure DevOps", Unreadable.NO_CREDENTIAL))
        val empty = describe(Reading.Saw("Azure DevOps", count = 0, asOfMs = null))

        assertNotEquals(blind, empty)
        assertTrue("must say it cannot see", blind.contains("cannot see"))
        assertTrue("must name the reason", blind.contains("no access token"))
        // The whole point: an unreadable source must not contain the empty source's claim.
        assertTrue("must not claim nothing is open", !blind.contains("nothing open"))
    }

    @Test
    fun `saw and empty states nothing open as fact`() {
        val text = describe(Reading.Saw("the LEGION board", count = 0, asOfMs = now))

        assertTrue(text.contains("nothing open"))
        assertTrue("a fresh reading is stated flatly", text.contains("checked just now"))
        assertTrue("no age hedge on a fresh reading", !text.contains("old"))
    }

    @Test
    fun `saw but stale carries the age in the same sentence as the count`() {
        val text = describe(
            Reading.Saw("the LEGION board", count = 12, asOfMs = at(day = 7, hour = 6)),
        )

        assertTrue("states the count", text.contains("12 open items"))
        assertTrue("states the age", text.contains("about 3 days old"))
        // Same sentence, not appended after it - a clipped answer must not keep the number and
        // lose the qualifier.
        val countAt = text.indexOf("12 open items")
        val ageAt = text.indexOf("about 3 days old")
        val fullStopBetween = text.substring(minOf(countAt, ageAt), maxOf(countAt, ageAt))
        assertTrue("age and count must share a sentence", !fullStopBetween.contains(". "))
    }

    // --- the fourth case the ticket's table omits ---------------------------------------------

    @Test
    fun `stale and empty is hedged too, not stated flatly`() {
        val text = describe(
            Reading.Saw("the LEGION board", count = 0, asOfMs = at(day = 4, hour = 6)),
        )

        assertTrue(text.contains("nothing open"))
        // "No open work" from a six-day-old file is a claim about today made from last week's
        // evidence. It is the most dangerous of the four and the ticket's row list misses it.
        assertTrue("a stale empty reading must still carry its age", text.contains("old"))
        assertTrue(!text.contains("checked just now"))
    }

    // --- live sources have no age --------------------------------------------------------------

    @Test
    fun `a live read-through source is never stale`() {
        val text = describe(Reading.Saw("Azure DevOps", count = 4, asOfMs = null))

        assertTrue(text.contains("4 open items"))
        assertTrue(text.contains("checked just now"))
        assertTrue("read-through has no cache to age", !text.contains("old"))
    }

    @Test
    fun `one item is singular`() {
        val text = describe(Reading.Saw("Azure DevOps", count = 1, asOfMs = null))

        assertTrue(text.contains("1 open item"))
        assertTrue(!text.contains("1 open items"))
    }

    // --- the HTTP 200 throttle trap ------------------------------------------------------------

    @Test
    fun `a throttled 200 classifies as unreadable, not as an empty result`() {
        // Azure DevOps signals a throttle with HTTP 200 plus Retry-After, not 429. Branching on
        // the status code alone parses the body, finds nothing, and reports "nothing pending".
        assertEquals(Unreadable.THROTTLED, ProjectsReachability.classify(200, retryAfter = "30"))
    }

    @Test
    fun `an ordinary 200 is usable`() {
        assertNull(ProjectsReachability.classify(200, retryAfter = null))
        assertNull(ProjectsReachability.classify(200, retryAfter = ""))
    }

    @Test
    fun `retry-after wins over the status code`() {
        // Ordering matters: the Retry-After check must come first, or a 200 short-circuits it.
        assertEquals(Unreadable.THROTTLED, ProjectsReachability.classify(204, retryAfter = "1"))
    }

    @Test
    fun `a dead PAT is unauthorized, and speaks as cannot see`() {
        assertEquals(Unreadable.UNAUTHORIZED, ProjectsReachability.classify(401, null))
        assertEquals(Unreadable.UNAUTHORIZED, ProjectsReachability.classify(403, null))

        val text = describe(Reading.CannotSee("Azure DevOps", Unreadable.UNAUTHORIZED))
        assertTrue(text.contains("cannot see"))
        assertTrue(text.contains("may have expired"))
    }

    @Test
    fun `a server error is unreachable`() {
        assertEquals(Unreadable.UNREACHABLE, ProjectsReachability.classify(500, null))
        assertEquals(Unreadable.UNREACHABLE, ProjectsReachability.classify(404, null))
    }

    // --- WIQL's silent truncation --------------------------------------------------------------

    @Test
    fun `a result sitting on the WIQL cap refuses to be counted`() {
        assertNull(ProjectsReachability.truncatedIfAtCap(19_999))
        assertEquals(Unreadable.TRUNCATED, ProjectsReachability.truncatedIfAtCap(20_000))
        assertEquals(Unreadable.TRUNCATED, ProjectsReachability.truncatedIfAtCap(20_001))
    }

    // --- every failure reason picks the cannot-see side ----------------------------------------

    @Test
    fun `every unreadable reason produces a cannot-see sentence`() {
        // Guards the contract against a future constant being added and quietly rendering as an
        // empty result - the enum exists so a new failure has to pick a side.
        Unreadable.entries.forEach { reason ->
            val text = describe(Reading.CannotSee("the LEGION board", reason))
            assertTrue("$reason must say it cannot see", text.contains("cannot see"))
            assertTrue("$reason must not claim emptiness", !text.contains("nothing open"))
            assertTrue("$reason must state a reason", text.contains(reason.phrase))
        }
    }

    // --- age wording ---------------------------------------------------------------------------

    @Test
    fun `age is rounded, never falsely precise`() {
        val hour = 60L * 60L * 1000L
        assertEquals("about a day", ProjectsReachability.ageInWords(30 * hour))
        assertEquals("about 3 days", ProjectsReachability.ageInWords(72 * hour))
        assertEquals("over a week", ProjectsReachability.ageInWords(9 * 24 * hour))
        assertEquals("about 3 weeks", ProjectsReachability.ageInWords(21 * 24 * hour))
        assertEquals("months", ProjectsReachability.ageInWords(200 * 24 * hour))
    }

    @Test
    fun `the staleness threshold is 24 hours`() {
        val hour = 60L * 60L * 1000L
        assertEquals(24 * hour, ProjectsReachability.STALE_AFTER_MS)

        // Just inside the threshold is fresh; exactly on it is stale.
        val fresh = describe(Reading.Saw("the LEGION board", 3, now - (23 * hour)))
        val stale = describe(Reading.Saw("the LEGION board", 3, now - (24 * hour)))
        assertTrue(fresh.contains("checked just now"))
        assertTrue(stale.contains("old"))
    }
}
