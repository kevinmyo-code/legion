@file:Suppress("MaxLineLength") // fixtures read best on one line

package com.kevin.legion.calendar

import com.kevin.legion.backend.EventKind
import com.kevin.legion.data.local.Event
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Canvas marks a discussion submitted at its FIRST post, but the discussion row's due date is the
 * replies'. Its own row stays due until it is ticked or its due time passes; the first-post sub-row
 * and every other assignment still take Canvas's word. Titles and times are invented.
 */
class DueReaderDiscussionTest {
    private val chicago = ZoneId.of("America/Chicago")
    private val tuesday = LocalDate.of(2027, 3, 9)
    private val dueLate = tuesday.atTime(22, 30).atZone(chicago).toInstant().toEpochMilli()
    private val evening = tuesday.atTime(21, 40).atZone(chicago).toInstant().toEpochMilli()

    private val firstPostIn = """{"canvas_assignment_id":7,"submission_types":"discussion_topic","canvas_submitted":true,"submitted_at":"2027-03-07T09:30:00Z","submission_state":"submitted"}"""

    private fun task(meta: String?, at: Long = dueLate, done: Boolean = false) = Event(
        serverId = "s",
        title = "Invented course - Week 9 discussion",
        startsAt = at,
        endsAt = at,
        source = "canvas",
        kind = EventKind.TASK,
        done = done,
        updatedAtMs = at,
        structuredMeta = meta,
    )

    @Test fun `a discussion with only its first post in is not handed in`() {
        assertFalse(DueReader.handedIn(firstPostIn))
        assertFalse(DueReader.handedIn("""{"submission_types":"online_text_entry,discussion_topic","canvas_submitted":true}"""))
    }

    @Test fun `the first-post sub-row, an ordinary assignment and an excused discussion keep Canvas's word`() {
        assertTrue(DueReader.handedIn("""{"parent_canvas_assignment_id":7,"sub_deadline":"first_post","canvas_submitted":true}"""))
        assertTrue(DueReader.handedIn("""{"submission_types":"online_upload","canvas_submitted":true}"""))
        assertTrue(DueReader.handedIn("""{"submission_types":"online_upload","submitted_at":"2027-03-07T09:30:00Z"}"""))
        assertTrue(DueReader.handedIn("""{"submission_types":"discussion_topic","excused":true}"""))
        assertFalse(DueReader.handedIn("""{"submission_types":"online_upload","canvas_submitted":false}"""))
    }

    @Test fun `a discussion due later this evening is open today, and not on tomorrow's list`() {
        val input = DueReader.taskInput(task(firstPostIn), chicago, evening)!!
        assertEquals(tuesday, input.date)
        assertFalse(input.done)
        val s = DueReader.summarize(listOf(task(firstPostIn)), emptyList(), tuesday, tuesday, tuesday, evening, chicago)
        assertEquals(1, s.open.size)
        assertEquals(0, s.doneCount)
        assertFalse(s.open.single().overdue)
        val wednesday = tuesday.plusDays(1)
        val next = DueReader.summarize(listOf(task(firstPostIn)), emptyList(), wednesday, wednesday, tuesday, evening, chicago)
        assertTrue(next.open.isEmpty())
    }

    @Test fun `a discussion past its due time with its post in is settled, not overdue`() {
        val lastWeek = tuesday.minusDays(7).atTime(22, 30).atZone(chicago).toInstant().toEpochMilli()
        assertTrue(DueReader.taskInput(task(firstPostIn, at = lastWeek), chicago, evening)!!.done)
        val s = DueReader.summarize(listOf(task(firstPostIn, at = lastWeek)), emptyList(), tuesday, tuesday, tuesday, evening, chicago)
        assertTrue(s.open.isEmpty())
        // Not posted at all: still overdue.
        val notIn = """{"submission_types":"discussion_topic","canvas_submitted":false}"""
        val late = DueReader.summarize(listOf(task(notIn, at = lastWeek)), emptyList(), tuesday, tuesday, tuesday, evening, chicago)
        assertTrue(late.open.single().overdue)
    }

    @Test fun `a tick still settles it`() {
        assertTrue(DueReader.taskInput(task(firstPostIn, done = true), chicago, evening)!!.done)
    }
}
