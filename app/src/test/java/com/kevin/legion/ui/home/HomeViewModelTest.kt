package com.kevin.legion.ui.home

import com.kevin.legion.ledger.BudgetVsActual
import com.kevin.legion.meals.DailyMealGap
import com.kevin.legion.ui.fleet.DueRowView
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit finding 1 (home-launcher tickets 03/04's own "HOME must never crash" clause, re-checked):
 * five of [HomeViewModel]'s own reads - checklist count, budget, meal gap/target, maintenance,
 * voice-notes count - each sat behind a `catch` that silently replaced a thrown read with the SAME
 * value a genuinely empty read produces (0, `null`, [DailyMealGap.NotLogged], an empty list). "No
 * lists yet" and "Couldn't read lists" are different sentences (CLAUDE.md sec 1); before this fix
 * the app could only ever say the first one, even when the read had thrown.
 *
 * [assembleHomeState] takes every one of those five reads as an injected suspend lambda
 * specifically so this class can feed each one a throwing fake without a `Context`, Robolectric or
 * a real [com.kevin.legion.data.local.CarDatabase] - [HomeViewModel.refresh]/`loadState` wire the
 * real controllers; this file proves the assembly step alone never lets a throw disappear into an
 * empty-looking tile.
 */
class HomeViewModelTest {

    private val today: LocalDate = LocalDate.of(2026, 9, 27)
    private val okCalendar = CalendarReading(
        nextLine = "Nothing else on the calendar today",
        chips = buildTodayChips(dueTodayCount = 0, overdueCount = 0, calendarReadFailed = false),
    )
    private val boom = RuntimeException("boom")

    private suspend fun assemble(
        readChecklistCount: suspend () -> Int = { 0 },
        readBudget: suspend () -> BudgetVsActual? = { null },
        readMealGap: suspend () -> Pair<DailyMealGap, Boolean> = { DailyMealGap.NotLogged to false },
        readMaintenance: suspend () -> Pair<List<DueRowView>, Int> = { emptyList<DueRowView>() to 0 },
        readVoiceNotesCount: suspend () -> Int = { 0 },
    ): HomeUiState = assembleHomeState(
        today = today,
        calendar = okCalendar,
        weatherText = "72F, partly cloudy",
        weatherIconResId = com.kevin.legion.R.drawable.ms_sunny,
        areaAqi = "Houston, TX - AQI 42 (Good)",
        readChecklistCount = readChecklistCount,
        readBudget = readBudget,
        readMealGap = readMealGap,
        readMaintenance = readMaintenance,
        readVoiceNotesCount = readVoiceNotesCount,
    )

    @Test
    fun `a throwing checklist-count read fails the Lists tile in words, not as zero`() = runBlocking {
        val state = assemble(readChecklistCount = { throw boom })
        assertTrue(state.listsFailed)
        assertEquals(0, state.checklistCount)
        assertEquals("Couldn't read lists", listsTileStatus(state.checklistCount, state.listsFailed).text)
        assertFalse(state.moneyFailed || state.bodyFailed || state.fleetFailed || state.recordingsFailed)
    }

    @Test
    fun `a throwing budget read fails the Money tile in words, not as no spending`() = runBlocking {
        val state = assemble(readBudget = { throw boom })
        assertTrue(state.moneyFailed)
        assertEquals(null, state.budget)
        assertEquals("Couldn't read spending", moneyTileStatus(state.budget, state.moneyFailed).text)
    }

    @Test
    fun `a throwing meal-gap read fails the Body tile in words, not as no calorie target`() = runBlocking {
        val state = assemble(readMealGap = { throw boom })
        assertTrue(state.bodyFailed)
        assertEquals(DailyMealGap.NotLogged, state.mealGap)
        assertFalse(state.hasMealTarget)
        assertEquals(
            "Couldn't read meals",
            bodyTileStatus(state.mealGap, state.hasMealTarget, state.bodyFailed).text,
        )
    }

    @Test
    fun `a throwing maintenance read fails the Fleet tile in words, not as no schedule`() = runBlocking {
        val state = assemble(readMaintenance = { throw boom })
        assertTrue(state.fleetFailed)
        assertTrue(state.maintenanceRows.isEmpty())
        assertEquals(
            "Couldn't read maintenance",
            fleetTileStatus(state.maintenanceRows, state.maintenanceUnknownCount, state.fleetFailed).text,
        )
    }

    @Test
    fun `a throwing voice-notes read fails the Recordings tile in words, not as zero saved`() = runBlocking {
        val state = assemble(readVoiceNotesCount = { throw boom })
        assertTrue(state.recordingsFailed)
        assertEquals(0, state.voiceNotesCount)
        assertEquals(
            "Couldn't read recordings",
            recordingsTileStatus(state.voiceNotesCount, recording = false, failed = state.recordingsFailed).text,
        )
    }

    @Test
    fun `every read succeeding leaves every failed flag false and the state still builds`() = runBlocking {
        val state = assemble(
            readChecklistCount = { 2 },
            readVoiceNotesCount = { 3 },
        )
        assertFalse(state.listsFailed)
        assertFalse(state.moneyFailed)
        assertFalse(state.bodyFailed)
        assertFalse(state.fleetFailed)
        assertFalse(state.recordingsFailed)
        assertFalse(state.loading)
        assertEquals(2, state.checklistCount)
        assertEquals(3, state.voiceNotesCount)
    }
}
