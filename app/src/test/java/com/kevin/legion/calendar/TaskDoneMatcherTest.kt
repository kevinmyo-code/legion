package com.kevin.legion.calendar

import com.kevin.legion.backend.EventKind
import com.kevin.legion.calendar.TaskDoneMatcher.Resolution
import com.kevin.legion.data.local.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskDoneMatcherTest {

    private var nextId = 1L

    private fun task(title: String, done: Boolean = false, kind: String = EventKind.TASK, at: Long = 1_000L * nextId) =
        Event(
            id = nextId++, serverId = null, title = title, startsAt = at, source = "canvas",
            kind = kind, done = done, updatedAtMs = 0L,
        )

    private val dateOf: (Event) -> String? = { "Oct 5" }

    private val webassign = task("MATH 2413 WebAssign 4.3")
    private val webassign2 = task("MATH 2413 WebAssign 4.4")
    private val calc = task("MATH 3391 Quiz 2")
    private val python = task("COSC 3318 Python Programming · Module 3 Quiz + Homework 3")
    private val all = listOf(webassign, webassign2, calc, python)

    private fun resolve(q: String, target: Boolean = true, allMatches: Boolean = false, list: List<Event> = all) =
        TaskDoneMatcher.resolve(list, list, q, target, allMatches)

    @Test fun courseCodeNarrowsToOneCourse() {
        val r = resolve("MATH 3391") as Resolution.Apply
        assertEquals(listOf(calc), r.toChange)
    }

    @Test fun mathsAssignmentsFindsMathRowsAndAsksWhich() {
        val r = resolve("maths assignments") as Resolution.Ambiguous
        assertEquals(listOf(webassign, webassign2, calc), r.candidates)
    }

    @Test fun allMatchesAppliesToEvery() {
        val r = resolve("math 2413", allMatches = true) as Resolution.Apply
        assertEquals(listOf(webassign, webassign2), r.toChange)
    }

    @Test fun titleWordsMatchAcrossPunctuation() {
        val r = resolve("python module 3") as Resolution.Apply
        assertEquals(listOf(python), r.toChange)
    }

    @Test fun decimalSectionMatches() {
        val r = resolve("webassign 4.4") as Resolution.Apply
        assertEquals(listOf(webassign2), r.toChange)
    }

    @Test fun eventsAreNeverCompletable() {
        val klass = task("MATH 2413 Lecture", kind = EventKind.EVENT)
        val r = resolve("lecture", list = listOf(klass))
        assertTrue(r is Resolution.NoMatch)
    }

    @Test fun noMatchNamesNearestTitlesAndChangesNothing() {
        val r = resolve("physics 1301") as Resolution.NoMatch
        assertTrue(r.nearest.isEmpty())
        val near = resolve("math 9999") as Resolution.NoMatch
        assertEquals(setOf(webassign, webassign2, calc), near.nearest.toSet())
        val text = TaskDoneMatcher.noMatchText(near.nearest, dateOf)
        assertTrue(text.startsWith("Nothing was changed"))
        assertTrue(text.contains("MATH 3391 Quiz 2 (due Oct 5)"))
    }

    @Test fun fillerOnlyQueryIsRefused() {
        assertEquals(Resolution.EmptyQuery, resolve("all my assignments", allMatches = true))
    }

    @Test fun alreadyDoneIsNotRewritten() {
        val d = task("MATH 3391 Quiz 2", done = true)
        val r = resolve("math 3391", list = listOf(d))
        assertTrue(r is Resolution.AlreadyThere)
        assertTrue(TaskDoneMatcher.alreadyText(true, listOf(d), dateOf).startsWith("Nothing was changed"))
    }

    @Test fun untickOnlyTargetsDoneRows() {
        val d = task("MATH 3391 Quiz 2", done = true)
        val open = task("MATH 3391 Quiz 3")
        val r = resolve("math 3391", target = false, list = listOf(d, open)) as Resolution.Apply
        assertEquals(listOf(d), r.toChange)
        assertEquals(listOf(open), r.alreadyThere)
    }

    @Test fun resultSaysOnlyWhatCommitted() {
        val text = TaskDoneMatcher.resultText(
            target = true, committed = listOf(webassign), failed = listOf(calc),
            queued = emptySet(), alreadyThere = listOf(webassign2), dateOf = dateOf,
        )
        assertTrue(text.startsWith("Marked done: MATH 2413 WebAssign 4.3 (due Oct 5)."))
        assertTrue(text.contains("NOT changed, the write failed: MATH 3391 Quiz 2"))
        assertTrue(text.contains("Already done, left alone: MATH 2413 WebAssign 4.4"))
    }

    @Test fun queuedWriteSaysSavedOnPhoneWillSync() {
        val text = TaskDoneMatcher.resultText(
            true, listOf(webassign), emptyList(), setOf(webassign.id), emptyList(), dateOf,
        )
        assertTrue(text.contains("saved on the phone and will sync"))
        val plain = TaskDoneMatcher.resultText(true, listOf(webassign), emptyList(), emptySet(), emptyList(), dateOf)
        assertTrue(!plain.contains("sync"))
    }

    @Test fun unmarkWording() {
        val text = TaskDoneMatcher.resultText(false, listOf(webassign), emptyList(), emptySet(), emptyList(), dateOf)
        assertTrue(text.startsWith("Marked not done:"))
    }

    @Test fun ambiguousListsCandidatesWithDates() {
        val text = TaskDoneMatcher.ambiguousText(listOf(webassign, webassign2), dateOf)
        assertTrue(text.startsWith("Nothing was changed. 2 tasks match"))
        assertTrue(text.contains("all_matches"))
    }
}
