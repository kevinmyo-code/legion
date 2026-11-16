package com.kevin.legion.calendar

import com.kevin.legion.backend.EventKind
import com.kevin.legion.data.local.Event
import com.kevin.legion.data.local.ListItem
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "What's due" (2026-11-15): calendar tasks and dated to-dos merged in one place, so voice and the
 * home figure count the same. Titles are made up.
 */
class DueListTest {
    private val zone: ZoneId = ZoneId.of("UTC")
    private val today: LocalDate = LocalDate.of(2026, 11, 15)
    private val noon = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    private fun at(day: LocalDate, hour: Int, minute: Int = 0) =
        day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun task(title: String, day: LocalDate, hour: Int = 23, done: Boolean = false, meta: String? = null) =
        Event(serverId = null, title = title, startsAt = at(day, hour, 59), done = done, kind = EventKind.TASK,
            structuredMeta = meta, source = "test", updatedAtMs = 0L)

    private fun todo(title: String, day: LocalDate, done: Boolean = false, repeat: String? = null) =
        ListItem(listId = 1, text = title, done = done, allDay = true,
            startsAt = day.atStartOfDay(zone).toInstant().toEpochMilli(), repeatKind = repeat)

    private fun summary(tasks: List<Event>, todos: List<ListItem>, from: LocalDate = today, to: LocalDate = today) =
        DueReader.summarize(tasks, todos, from, to, today, noon, zone)

    @Test fun `open tasks and dated to-dos are both due, done ones only counted`() {
        val s = summary(
            listOf(
                task("Module quiz", today),
                task("Homework set", today, hour = 22),
                task("Chapter quiz", today, meta = """{"submitted_at":"2026-11-15T10:00:00Z","grade":"95%"}"""),
            ),
            listOf(todo("Renew parking permit", today), todo("Call the bank", today, done = true)),
        )
        assertEquals(listOf("Homework set", "Module quiz", "Renew parking permit"), s.open.map { it.item.title })
        assertEquals(listOf(DueSource.CALENDAR, DueSource.CALENDAR, DueSource.TODO), s.open.map { it.item.source })
        assertEquals(2, s.doneCount)
        assertEquals(0, s.overdueCount)
        val says = DueList.says(s)
        assertTrue(says, says.startsWith("3 open and due, not done, not overdue yet:"))
        assertTrue(says, says.contains("Nothing is overdue."))
        assertTrue(says, says.contains("2 already done."))
    }

    @Test fun `submitted, canvas_submitted and excused tasks are not due`() {
        assertTrue(DueReader.handedIn("""{"canvas_submitted":true}"""))
        assertTrue(DueReader.handedIn("""{"submitted_at":"2026-11-15T10:00:00Z"}"""))
        assertTrue(DueReader.handedIn("""{"excused":true}"""))
        assertFalse(DueReader.handedIn("""{"canvas_submitted":false,"submitted_at":null}"""))
        assertFalse(DueReader.handedIn(null))
        assertFalse(DueReader.handedIn("not json"))
    }

    @Test fun `overdue to-dos are kept and said overdue, tasks only within the look back`() {
        val s = summary(
            listOf(
                task("Old essay", today.minusDays(DueList.TASK_OVERDUE_LOOKBACK_DAYS + 5)),
                task("Lab report", today.minusDays(3)),
            ),
            listOf(todo("Gym waiver", today.minusDays(60))),
        )
        assertEquals(listOf("Gym waiver", "Lab report"), s.open.map { it.item.title })
        assertTrue(s.open.all { it.overdue })
        assertEquals(2, s.overdueCount)
        assertTrue(DueList.says(s).startsWith("2 OVERDUE (past due, not done):"))
        assertTrue(DueList.says(s).contains("Nothing else open is due in this window."))
    }

    @Test fun `a window in the past does not pull in today's overdue items`() {
        val lastWeek = today.minusDays(7)
        val s = summary(emptyList(), listOf(todo("Gym waiver", today.minusDays(20))), lastWeek, lastWeek)
        assertTrue(s.open.isEmpty())
    }

    @Test fun `one thing in both places is listed once, done if either copy is`() {
        val s = summary(
            listOf(task("Essay: Draft 2", today)),
            listOf(todo("essay draft 2", today), todo("essay draft 2", today.plusDays(1))),
            to = today.plusDays(1),
        )
        assertEquals(listOf(DueSource.CALENDAR, DueSource.TODO), s.open.map { it.item.source })
        val both = summary(listOf(task("Essay: Draft 2", today)), listOf(todo("essay draft 2", today, done = true)))
        assertTrue(both.open.isEmpty())
        assertEquals(1, both.doneCount)
    }

    @Test fun `a repeating to-do is never overdue`() {
        val s = summary(emptyList(), listOf(todo("Water plants", today.minusDays(2), repeat = "DAILY")))
        assertTrue(s.open.isEmpty())
    }

    @Test fun `a timed task already past today is due today and overdue`() {
        val s = summary(listOf(task("Morning quiz", today, hour = 9)), emptyList())
        assertEquals(1, s.open.size)
        assertTrue(s.open.single().overdue)
        assertEquals(1, s.dueInWindowCount)
    }

    @Test fun `an all-day calendar task reads its date in UTC, a to-do in the local zone`() {
        val west = ZoneId.of("America/Chicago")
        val utcMidnight = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val allDayTask = Event(serverId = null, title = "Paper", startsAt = utcMidnight, allDay = true,
            kind = EventKind.TASK, source = "test", updatedAtMs = 0L)
        assertEquals(today, DueReader.taskInput(allDayTask, west)!!.date)
        val localMidnight = today.atStartOfDay(west).toInstant().toEpochMilli()
        val allDayTodo = ListItem(listId = 1, text = "Bin day", startsAt = localMidnight, allDay = true)
        assertEquals(today, DueReader.todoInput(allDayTodo, west)!!.date)
    }

    @Test fun `nothing open says so, and an unreadable source is never a clear day`() {
        val clear = summary(emptyList(), emptyList())
        assertEquals("Nothing open is due in this window. Nothing is overdue.", DueList.says(clear))
        val blind = DueList.summarize(emptyList(), today, today, today, noon, listOf("to-dos"))
        assertTrue(DueList.says(blind).contains("Could not read to-dos"))
        assertFalse("an unreadable source never says nothing is overdue", DueList.says(blind).contains("Nothing is overdue"))
    }

    @Test fun `the json block leads with the counts and tags each item's source`() {
        val s = summary(listOf(task("Module quiz", today)), listOf(todo("Old form", today.minusDays(5))))
        val o = DueReader.toJson(s)
        assertEquals(2, o.getInt("open"))
        assertEquals(1, o.getInt("overdue"))
        assertEquals(0, o.getInt("done"))
        val first = o.getJSONArray("open_items").getJSONObject(0)
        assertEquals("Old form", first.getString("title"))
        assertEquals("todo", first.getString("source"))
        assertTrue(first.getBoolean("overdue"))
        assertEquals("calendar", o.getJSONArray("open_items").getJSONObject(1).getString("source"))
    }

    // --- 2026-11-15 voice eval: "is anything overdue?" asked at night -------------------------------

    private val west: ZoneId = ZoneId.of("UTC-06:00")

    private fun nightSummary(nowLocal: java.time.LocalDateTime, from: LocalDate, to: LocalDate): DueSummary {
        val nowMs = nowLocal.atZone(west).toInstant().toEpochMilli()
        val day = nowLocal.toLocalDate()
        fun westTask(title: String, on: LocalDate) = Event(serverId = null, title = title,
            startsAt = on.atTime(23, 59).atZone(west).toInstant().toEpochMilli(), kind = EventKind.TASK,
            source = "test", updatedAtMs = 0L)
        return DueReader.summarize(
            listOf(westTask("Tonight quiz", today), westTask("Last night homework", today.minusDays(1))),
            emptyList(), from, to, day, nowMs, west,
        )
    }

    @Test fun `at 23 30 local, tonight's 23 59 work is due today and not overdue`() {
        // 23:30 at UTC-06:00 is already the next day in UTC.
        val s = nightSummary(today.atTime(23, 30), today, today)
        assertEquals(listOf("Last night homework"), s.open.filter { it.overdue }.map { it.item.title })
        assertEquals(listOf("Tonight quiz"), s.open.filter { !it.overdue }.map { it.item.title })
        val says = DueList.says(s)
        assertTrue(says, says.startsWith("1 OVERDUE (past due, not done): Last night homework (due yesterday, Sat "))
        assertTrue(says, says.contains("not overdue yet: Tonight quiz (due today, Sun "))
        assertEquals("Sunday 2026-11-15", DueReader.toJson(s).getString("today"))
    }

    @Test fun `at 00 30 local, last night's 23 59 work is overdue and tomorrow is the new tomorrow`() {
        val next = today.plusDays(1)
        val s = nightSummary(next.atTime(0, 30), next, next)
        assertEquals(setOf("Tonight quiz", "Last night homework"), s.open.filter { it.overdue }.map { it.item.title }.toSet())
        val says = DueList.says(s)
        assertTrue(says, says.contains("Tonight quiz (due yesterday, Sun "))
        assertEquals("Monday 2026-11-16", DueReader.toJson(s).getString("today"))
    }

    @Test fun `a past window never says nothing is overdue`() {
        val s = nightSummary(today.atTime(21, 0), today.minusDays(7), today.minusDays(2))
        assertFalse(DueList.says(s).contains("Nothing is overdue"))
    }
}
