package com.kevin.legion.ui.home

import com.kevin.legion.backend.EventKind
import com.kevin.legion.data.local.Event
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pure derivation behind HOME's calendar (HOME speed pass, 2026-10-09): one month read holds
 * events, tasks and suggestions mixed, and every count and agenda is split back out BY KIND. The
 * rule it guards is `EventReadsAreKindFilteredTest`'s: a suggestion is never a plan.
 */
class HomeCalendarDeriveTest {
    private val zone = ZoneId.of("UTC")
    private val day = LocalDate.of(2026, 10, 10)
    private val now = day.atTime(8, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun ev(id: Long, kind: String, allDay: Boolean = false) = Event(
        id = id,
        serverId = null,
        title = "t$id",
        startsAt = if (allDay) {
            day.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        } else {
            day.atTime(15, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        },
        allDay = allDay,
        source = "test",
        updatedAtMs = 0,
        kind = kind,
    )

    private val read = MonthRead(
        YearMonth.of(2026, 10),
        rows = listOf(
            ev(1, EventKind.SUGGESTION),
            ev(2, EventKind.SUGGESTION, allDay = true),
            ev(3, EventKind.EVENT),
            ev(4, EventKind.TASK),
        ),
        reminders = emptyList(),
    )

    @Test
    fun `rows are split by kind by name`() {
        assertEquals(listOf(3L), read.events().map { it.id })
        assertEquals(listOf(4L), read.tasks().map { it.id })
        assertEquals(listOf(1L, 2L), read.suggestions().map { it.id })
    }

    @Test
    fun `a suggestion is never counted as an event or a to-do in the month markers`() {
        val markers = deriveMonthMarkers(read, inboxOf(read, now), zone).byDay.getValue(day)
        assertEquals(DayMarkers(events = 1, todos = 1, suggestions = 2), markers)
    }

    @Test
    fun `a suggestion is a suggestion row in the day, after every plan, and not a plan`() {
        val dayRead = deriveDay(read, inboxOf(read, now), day, zone)
        assertEquals(listOf(AgendaKind.EVENT, AgendaKind.TODO), dayRead.rows.take(2).map { it.kind })
        assertEquals(
            listOf(AgendaKind.SUGGESTION, AgendaKind.SUGGESTION),
            dayRead.rows.drop(2).map { it.kind },
        )
        val state = HomeCalendarUiState(day, YearMonth.of(2026, 10), day, dayRows = dayRead.rows)
        assertEquals(2, state.planCount)
        assertEquals(2, state.suggestionCount)
    }

    @Test
    fun `a day with nothing on it derives an empty agenda from the same read`() {
        val other = deriveDay(read, inboxOf(read, now), LocalDate.of(2026, 10, 11), zone)
        assertEquals(emptyList<AgendaRowUi>(), other.rows)
    }

    @Test
    fun `an unreadable kind travels with the derived month and day`() {
        val gap = read.copy(unreadable = listOf("suggestions"))
        assertEquals(listOf("suggestions"), deriveMonthMarkers(gap, inboxOf(gap, now), zone).unreadable)
        assertEquals(listOf("suggestions"), deriveDay(gap, inboxOf(gap, now), day, zone).unreadable)
    }
}
