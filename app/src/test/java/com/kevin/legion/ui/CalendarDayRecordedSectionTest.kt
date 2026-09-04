package com.kevin.legion.ui

import com.kevin.legion.backend.EventKind
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Event
import com.kevin.legion.data.local.VoiceNote
import com.kevin.legion.data.local.VoiceNoteKind
import com.kevin.legion.data.local.activeByKindInLocalWindow
import com.kevin.legion.testutil.RoomTestReset
import com.kevin.legion.ui.notes.DAY_FILTER_WINDOW_MS
import com.kevin.legion.ui.notes.buildInboxRows
import com.kevin.legion.ui.notes.toAppointmentEvent
import com.kevin.legion.voice.VoiceNoteController
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * `ui/CalendarScreen.kt`'s RECORDED section (the calendar-day-view follow-up ticket: "It stays OUT
 * of the completion ratio. That figure counts tasks only. A recording inflating it would make a day
 * read as busier than it was").
 *
 * This repo has no `createComposeRule` harness (see `ui/body/BodyWriteSameFunctionTest.kt`'s own
 * class doc for the same caveat), so this is not a test of [CalendarScreen] the composable - it
 * assembles the day view's `notDone`/`done` split out of the SAME production functions that
 * screen's own `LaunchedEffect` calls ([com.kevin.legion.data.local.activeByKindInLocalWindow],
 * [buildInboxRows]) and the SAME separate [VoiceNoteController.listInRange] query the RECORDED
 * section reads, in the same order that screen calls them. It is the strongest assertion available
 * without a Compose harness that a same-day recording can never enter the task done/not-done split.
 */
@RunWith(RobolectricTestRunner::class)
class CalendarDayRecordedSectionTest {
    private val context = RuntimeEnvironment.getApplication()
    private val zone = ZoneId.systemDefault()

    @Before
    fun setUp() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    @After
    fun tearDown() {
        RoomTestReset.drainArchDiskIoPool()
    }

    @Test
    fun `recordings on the same day never enter the task done-not-done split`() = runTest {
        val db = CarDatabase.getDatabase(context)
        val day = LocalDate.of(2026, 9, 4).atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEndExclusive = day + DAY_FILTER_WINDOW_MS
        val now = System.currentTimeMillis()

        db.eventDao().insert(
            Event(
                id = 0, serverId = null, title = "Homework", startsAt = day + 3_600_000L,
                source = "legion", kind = EventKind.TASK, done = false, updatedAtMs = now, createdAt = now,
            )
        )
        db.eventDao().insert(
            Event(
                id = 0, serverId = null, title = "Quiz", startsAt = day + 7_200_000L,
                source = "legion", kind = EventKind.TASK, done = true, updatedAtMs = now, createdAt = now,
            )
        )
        // Two same-day recordings, inserted straight through the DAO - CalendarScreen's own
        // effect queries these via a wholly SEPARATE call ([VoiceNoteController.listInRange]),
        // never as input to [buildInboxRows] below.
        db.voiceNoteDao().insert(VoiceNote(startedAt = day + 1_000L, kind = VoiceNoteKind.SOLO, audioPath = null))
        db.voiceNoteDao().insert(VoiceNote(startedAt = day + 2_000L, kind = VoiceNoteKind.SOLO, audioPath = null))

        // Same call CalendarScreen's own LaunchedEffect makes for the YET TO DO / DONE split.
        val tasks = db.eventDao().activeByKindInLocalWindow(EventKind.TASK, day, dayEndExclusive - 1, zone)
        val rows = buildInboxRows(emptyList(), now, tasks.map { it.toAppointmentEvent() })
        val dayRows = rows.filter { row -> row.instantMs != null && row.instantMs >= day && row.instantMs < dayEndExclusive }
        val notDone = dayRows.filter { !it.done }
        val done = dayRows.filter { it.done }

        assertEquals("only the two TASK rows belong in the day view - a same-day recording must " +
            "never widen this list", 2, dayRows.size)
        assertEquals(1, notDone.size)
        assertEquals(1, done.size)

        // The RECORDED section's own separate read - confirms the two recordings above are real
        // and visible through THAT query, just never through buildInboxRows' input.
        val recorded = (VoiceNoteController.listInRange(context, day, dayEndExclusive)
            as VoiceNoteController.VoiceNotesForDayResult.Loaded).notes
        assertEquals(2, recorded.size)
    }
}
