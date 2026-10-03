package com.kevin.legion.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.kevin.legion.data.local.BodyweightLog
import com.kevin.legion.data.local.VoiceNote
import com.kevin.legion.data.local.VoiceNoteKind
import com.kevin.legion.data.local.WorkoutSetLog
import com.kevin.legion.data.local.WorkoutSetLogDao
import com.kevin.legion.plan.TrustTier
import com.kevin.legion.ui.BodyExerciseProgressionDrilldown
import com.kevin.legion.ui.BodyMassDrilldown
import com.kevin.legion.ui.BodyTrainingExerciseListDrilldown
import com.kevin.legion.ui.agenda.MonthCalendar
import com.kevin.legion.ui.common.DeckPoint
import com.kevin.legion.ui.common.DeckRange
import com.kevin.legion.ui.notes.buildMonthCells
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.theme.soft.SoftTheme
import com.kevin.legion.ui.voicenotes.VoiceNoteDetail
import com.kevin.legion.ui.voicenotes.VoiceNoteRow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.YearMonth

/**
 * Drill-down soft-restyle (ADR 0051) coverage for the drill-downs whose content is reachable
 * without a controller: Body (the three chart drill-downs; the panel list opens real Room panels that leak a connection
 * into later Robolectric tests, so it has no golden), the shared month
 * calendar, and the voice-note row and detail. Fixed epochs keep the goldens from drifting daily.
 * The other restyled screens (Fleet, Cars, Telemetry, Pantry, Ledger statements, News, Ask, Media)
 * build their state inside the screen and have no state-only seam; the device walk covers them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS)
class DrilldownSoftScreenshotTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val now = 1_790_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun shot(name: String, content: @Composable () -> Unit) {
        composeTestRule.setContent {
            SoftTheme { Surface(Modifier.fillMaxSize(), color = SoftColors.ground) { content() } }
        }
        composeTestRule.onRoot().captureRoboImage(name)
    }

    @Test
    fun `body mass drilldown`() = shot("drill-body-mass.png") {
        BodyMassDrilldown(
            latest = BodyweightLog(id = 3, weightValue = 183.5, weightUnit = "lbs", loggedAt = now, trustTier = TrustTier.REPORTED),
            series = (0 until 10).map { i -> if (i == 3) null else DeckPoint(xMs = now - (9 - i) * day, y = 186f - i * 0.3f) },
            history = listOf(
                BodyweightLog(id = 3, weightValue = 183.5, weightUnit = "lbs", loggedAt = now, trustTier = TrustTier.REPORTED),
                BodyweightLog(id = 2, weightValue = 184.0, weightUnit = "lbs", loggedAt = now - day, trustTier = TrustTier.REPORTED),
            ),
            range = DeckRange.THIRTY_DAY,
            loading = false,
            onRangeChange = {},
            onBack = {},
            onDelete = { "" },
            onDeleted = {},
        )
    }

    @Test
    fun `body training exercises`() = shot("drill-body-exercises.png") {
        BodyTrainingExerciseListDrilldown(
            exercises = listOf(WorkoutSetLogDao.ExerciseRecency("Squat", now), WorkoutSetLogDao.ExerciseRecency("Bench Press", now - 2 * day)),
            loading = false,
            onSelect = {},
            onBack = {},
        )
    }

    @Test
    fun `body exercise progression`() = shot("drill-body-progression.png") {
        BodyExerciseProgressionDrilldown(
            exercise = "Squat",
            series = (0 until 6).map { i -> DeckPoint(xMs = now - (5 - i) * day, y = 205f + i * 5f) },
            sets = listOf(
                WorkoutSetLog(id = 1, exercise = "Squat", sets = 3, reps = 5, weightValue = 225.0, weightUnit = "lbs", loggedAt = now, trustTier = TrustTier.REPORTED),
                WorkoutSetLog(id = 2, exercise = "Squat", sets = 5, reps = null, weightValue = null, weightUnit = null, loggedAt = now - day, trustTier = TrustTier.REPORTED),
            ),
            range = DeckRange.THIRTY_DAY,
            loading = false,
            onRangeChange = {},
            onBack = {},
            onDelete = { "" },
            onDeleted = {},
        )
    }

    @Test
    fun `month calendar`() = shot("drill-month-calendar.png") {
        val month = YearMonth.of(2026, 9)
        MonthCalendar(
            calendarLinked = true,
            month = month,
            cells = buildMonthCells(month, emptyMap()),
            collapsed = false,
            selectedDayStart = null,
            onToggleCollapsed = {},
            onPrevMonth = {},
            onNextMonth = {},
            onSelectDay = {},
            onGrantCalendar = {},
        )
    }

    @Test
    fun `voice notes rows and detail with the derived and interrupted wording`() = shot("drill-voicenotes.png") {
        val summarised = VoiceNote(
            id = 1, startedAt = now, endedAt = now + 60_000, title = "Standup", summary = "Shipped the parser.",
            transcript = "We shipped the parser today.", kind = VoiceNoteKind.MEETING,
        )
        val interrupted = summarised.copy(id = 2, title = "Cut short", interrupted = true)
        Column {
            VoiceNoteRow(summarised, onClick = {})
            VoiceNoteRow(interrupted, onClick = {})
            VoiceNoteDetail(note = summarised, playing = false, onTogglePlayback = {})
        }
    }
}
