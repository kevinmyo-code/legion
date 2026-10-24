package com.kevin.legion.ui.dumb

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatrixCalendarTextTest {
    private val zone = ZoneId.of("America/Chicago")
    private val today = LocalDate.of(2026, 10, 24)

    private fun at(day: LocalDate, hour: Int) = day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun event(title: String, day: LocalDate, hour: Int) =
        MatrixAgendaRow(1, MatrixAgendaKind.EVENT, title, at(day, hour), at(day, hour) + 3_600_000L, false)

    @Test
    fun `loading and unreadable are different sentences and neither shows days`() {
        val loading = MatrixCalendarUiState(today = today)
        assertEquals(MatrixCalendarText.LOADING, MatrixCalendarText.note(loading))
        assertTrue(MatrixCalendarText.days(loading, zone).isEmpty())
        val failed = loading.copy(loading = false, readFailed = true)
        assertEquals(MatrixCalendarText.UNREADABLE, MatrixCalendarText.note(failed))
        assertTrue(MatrixCalendarText.days(failed, zone).isEmpty())
    }

    @Test
    fun `a successful empty read shows eight days each honestly empty`() {
        val empty = MatrixCalendarUiState(today = today, loading = false)
        assertNull(MatrixCalendarText.note(empty))
        val days = MatrixCalendarText.days(empty, zone)
        assertEquals(8, days.size)
        assertTrue(days.all { it.rows.isEmpty() })
        assertEquals("Today - Sat 24 Oct", days[0].heading)
        assertEquals("Tomorrow - Sun 25 Oct", days[1].heading)
        assertEquals("Mon 26 Oct", days[2].heading)
    }

    @Test
    fun `rows land on their own day, an all-day event on its UTC date not the local one`() {
        val utcMidnight = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val allDay = MatrixAgendaRow(2, MatrixAgendaKind.EVENT, "Holiday", utcMidnight, utcMidnight + 86_400_000L, true)
        val state = MatrixCalendarUiState(
            today = today, loading = false,
            rows = listOf(event("Standup", today, 9), allDay),
        )
        val days = MatrixCalendarText.days(state, zone)
        assertEquals(listOf("Standup"), days[0].rows.map { it.title })
        assertEquals(listOf("Holiday"), days[1].rows.map { it.title })
    }

    @Test
    fun `row labels carry the time, a tick box only for tasks and reminders`() {
        assertEquals("09:00  Standup", MatrixCalendarText.rowLabel(event("Standup", today, 9), zone))
        val allDay = MatrixAgendaRow(3, MatrixAgendaKind.EVENT, "Holiday", 0L, 0L, true)
        assertEquals("all day  Holiday", MatrixCalendarText.rowLabel(allDay, zone))
        val task = MatrixAgendaRow(4, MatrixAgendaKind.TASK, "Essay", at(today, 17), null, false, tickable = true)
        assertEquals("[ ] 17:00  Essay", MatrixCalendarText.rowLabel(task, zone))
        assertEquals("[x] 17:00  Essay", MatrixCalendarText.rowLabel(task.copy(done = true), zone))
    }

    @Test
    fun `overdue and repeats are said in words on the row`() {
        val overdue = MatrixAgendaRow(
            5, MatrixAgendaKind.REMINDER, "Call", at(today, 8), null, false, tickable = true, overdue = true,
        )
        assertEquals("reminder - Overdue", MatrixCalendarText.rowDetail(overdue))
        assertEquals(MatrixTone.WARNING, MatrixCalendarText.rowTone(overdue))
        val repeats = MatrixAgendaRow(6, MatrixAgendaKind.REMINDER, "Pills", at(today, 8), null, false, repeats = true)
        assertTrue(MatrixCalendarText.rowDetail(repeats)!!.contains("can't be ticked off here"))
        assertEquals("tap to edit", MatrixCalendarText.rowDetail(event("E", today, 9)))
    }

    @Test
    fun `ordering is by day, then all-day before timed, then start`() {
        val allDay = MatrixAgendaRow(1, MatrixAgendaKind.EVENT, "A", at(today, 0), null, true)
        val later = event("C", today.plusDays(1), 8)
        val morning = event("B", today, 8)
        val ordered = MatrixCalendarText.ordered(listOf(later, morning, allDay), zone)
        assertEquals(listOf("A", "B", "C"), ordered.map { it.title })
    }
}
