package com.kevin.legion.advisor.digest

import com.kevin.legion.outstanding.OutstandingItem
import com.kevin.legion.outstanding.OutstandingKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HOME's OUTSTANDING line (chief-of-staff ticket 05).
 *
 * The line exists because [HomeDigestBuilder.logHeadline] reads `list_items` and only `list_items`,
 * so a coursework deadline (`EventKind.TASK`) and a bio-plan line (a checklist tick) never reached
 * the cross-aspect digest at all. On 2026-09-12 that was nine deadlines due the next night plus one
 * two days past, invisible to the advisor that is supposed to be the chief of staff.
 *
 * What is pinned here is the SHAPE of the line, not the ranking - `OutstandingTest` owns that, and
 * these fixtures arrive pre-ranked exactly as `OutstandingController` hands them over.
 */
class HomeOutstandingHeadlineTest {

    private fun item(
        id: String,
        title: String,
        kind: OutstandingKind,
        dueAtMs: Long? = null,
        overdue: Boolean = false,
        done: Boolean = false,
        source: String? = null,
    ) = OutstandingItem(id, title, kind, dueAtMs, overdue, done, source)

    @Test
    fun `names the most overdue thing when anything is late`() {
        val line = HomeDigestBuilder.outstandingHeadline(
            listOf(
                item("d1", "WK02: Bio Video Presentation", OutstandingKind.DEADLINE, 1L, overdue = true, source = "MKTG 3303"),
                item("d2", "Chapter 2 Quiz", OutstandingKind.DEADLINE, 9L, source = "MATH 3391"),
                item("l1", "walk", OutstandingKind.CHECKLIST_LINE, source = "bio"),
            ),
        )
        assertEquals(
            "OUTSTANDING 3 open, 1 past its date; most overdue: MKTG 3303 - WK02: Bio Video Presentation",
            line,
        )
    }

    @Test
    fun `names the next thing when nothing is late`() {
        val line = HomeDigestBuilder.outstandingHeadline(
            listOf(
                item("d2", "Chapter 2 Quiz", OutstandingKind.DEADLINE, 9L, source = "MATH 3391"),
                item("l1", "walk", OutstandingKind.CHECKLIST_LINE, source = "bio"),
            ),
        )
        assertEquals("OUTSTANDING 2 open; next: MATH 3391 - Chapter 2 Quiz", line)
        // No lateness clause at all rather than ", 0 past its date" - a zero invites the reader to
        // wonder what it counted.
        assertFalseContains(line, "past its date")
    }

    @Test
    fun `an undated errand at the top is labelled top, not next`() {
        val line = HomeDigestBuilder.outstandingHeadline(
            listOf(item("r1", "return the library book", OutstandingKind.REMINDER)),
        )
        assertEquals("OUTSTANDING 1 open; top: return the library book", line)
    }

    @Test
    fun `already-ticked lines are not counted as open`() {
        // They are carried in the list so a finished day reads as finished, but "open" must mean
        // open - counting them would make a completed bio plan look like outstanding work.
        val line = HomeDigestBuilder.outstandingHeadline(
            listOf(
                item("l1", "walk", OutstandingKind.CHECKLIST_LINE, done = true, source = "bio"),
                item("l2", "wall sits", OutstandingKind.CHECKLIST_LINE, source = "bio"),
            ),
        )
        assertEquals("OUTSTANDING 1 open; top: bio - wall sits", line)
    }

    @Test
    fun `nothing outstanding says so in words rather than reporting zero`() {
        assertEquals("OUTSTANDING nothing outstanding", HomeDigestBuilder.outstandingHeadline(emptyList()))
        assertEquals(
            "OUTSTANDING nothing outstanding",
            HomeDigestBuilder.outstandingHeadline(
                listOf(item("l1", "walk", OutstandingKind.CHECKLIST_LINE, done = true, source = "bio")),
            ),
        )
    }

    @Test
    fun `it is one line, whatever the volume`() {
        // Ticket 09's ruling: HOME names the cross-aspect connection and defers depth. Twelve rows
        // rolled up here would be LOG's digest in miniature, which is what that ticket rejected.
        val many = (1..12).map {
            item("d$it", "Assignment $it", OutstandingKind.DEADLINE, it.toLong(), source = "COSC 4320")
        }
        val line = HomeDigestBuilder.outstandingHeadline(many)
        assertTrue("must stay a single line", !line.contains("\n"))
        assertTrue("names the count", line.contains("12 open"))
        // Exactly one item named, not twelve.
        assertEquals(1, Regex("Assignment").findAll(line).count())
    }

    @Test
    fun `a source-less item renders without a dangling separator`() {
        val line = HomeDigestBuilder.outstandingHeadline(
            listOf(item("d1", "Renew the registration", OutstandingKind.DEADLINE, 5L, overdue = true)),
        )
        assertEquals("OUTSTANDING 1 open, 1 past its date; most overdue: Renew the registration", line)
    }

    private fun assertFalseContains(haystack: String, needle: String) {
        assertTrue("expected \"$haystack\" not to contain \"$needle\"", !haystack.contains(needle))
    }
}
