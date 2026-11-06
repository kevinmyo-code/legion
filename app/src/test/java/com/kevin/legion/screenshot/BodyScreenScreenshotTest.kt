package com.kevin.legion.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.kevin.legion.bodycoach.CurrentTargetWire
import com.kevin.legion.bodycoach.EnergyWire
import com.kevin.legion.bodycoach.MilestoneWire
import com.kevin.legion.bodycoach.ProposalWire
import com.kevin.legion.bodycoach.TrendPointWire
import com.kevin.legion.bodycoach.TrendWire
import com.kevin.legion.bodycoach.WaistWire
import com.kevin.legion.meals.DailyMealGap
import com.kevin.legion.meals.MacroTotals
import com.kevin.legion.plan.PlanGap
import com.kevin.legion.plan.TrustTier
import com.kevin.legion.ui.BodyCoachUi
import com.kevin.legion.ui.BodyContent
import com.kevin.legion.ui.BodyUiState
import com.kevin.legion.ui.body.BodyEnergyCard
import com.kevin.legion.ui.body.BodyMoreList
import com.kevin.legion.ui.body.BodyTodayCard
import com.kevin.legion.ui.body.BodyTrendCard
import com.kevin.legion.ui.body.BodyWeekCard
import com.kevin.legion.ui.body.TodayIntake
import com.kevin.legion.ui.body.BodyWorkoutCard
import com.kevin.legion.ui.body.WorkoutSessionScreen
import com.kevin.legion.ui.body.weekSubtitle
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.theme.soft.SoftTheme
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fat-loss ticket 21: the Body screen in direction A (Kevin picked it 2026-11-05), against
 * `body-prototypes/direction-a.html`. **A synthetic person only** (the prototype's own numbers),
 * never anyone's real weight: the repo is public.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BodyScreenScreenshotTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val start = LocalDate.of(2026, 9, 25)

    /** 42 days falling 247.0 to 243.0, raw dots scattered around the line, a few gap days. */
    private val points = (0 until 42).map { i ->
        val trend = 247.0 - 4.0 * i / 41
        val wobble = listOf(0.9, -0.6, 1.3, -1.1, 0.2, -0.4, 1.0)[i % 7]
        TrendPointWire(
            day = start.plusDays(i.toLong()).toString(),
            weightLbs = if (i % 9 == 4) null else trend + wobble,
            trendLbs = trend,
        )
    }

    private val trend = TrendWire(
        points = points,
        latestTrendLbs = 243.0,
        ratePctPerWeek = -0.29,
        rateLbsPerWeek = -0.7,
        inBand = false,
        milestone = MilestoneWire(statement = "Reach the first milestone", targetLbs = 230.0, startLbs = 247.0, remainingLbs = 13.0, progressPct = 24),
        waist = listOf(WaistWire(measuredOn = "2026-11-02", waistIn = 44.5)),
        waistChangeIn28d = -0.25,
        words = "Trend 243.0 lb.",
    )

    private val energy = EnergyWire(
        basis = "trend", basisDays = 14, basisWords = "from your own trend, 14 days",
        maintenanceKcal = 2610, suggestedTargetKcal = 2250, targetRatePct = 0.75,
        proteinG = 190, proteinGPerKg = 2.0, proteinBasis = "goal weight",
        currentTarget = CurrentTargetWire(caloriesKcal = 2250, proteinG = 190.0),
        proposal = ProposalWire(
            id = "p1", currentTargetKcal = 2250, proposedTargetKcal = 2100,
            words = "Your trend is losing 0.3% a week, below your 0.5-1.0% band.",
        ),
        words = "Every figure here is an estimate.",
    )

    private val state = BodyUiState(
        loading = false,
        mealGap = DailyMealGap.Logged(
            PlanGap(
                target = MacroTotals(2250, 190.0, 0.0, 0.0),
                actual = MacroTotals(1420, 118.0, 0.0, 0.0),
                gap = MacroTotals(830, 72.0, 0.0, 0.0),
                tier = TrustTier.REPORTED,
            ),
        ),
        hasMealTarget = true,
        mealTargetKcal = 2250,
        mealTargetProteinG = 190.0,
    )

    // workouts, 2026-11-06: a synthetic Day A; the weights are made up for the picture.
    private val plan = com.kevin.legion.bodycoach.PlanWire(
        dayKey = "A",
        sessionNumber = 4,
        setCount = 13,
        exercises = listOf(
            com.kevin.legion.bodycoach.TargetWire("back_squat", "Back squat", sets = 3, repsLow = 6, repsHigh = 8, weightLbs = 155.0, rule = "add", words = "+10 lb: every set reached 8 last time."),
            com.kevin.legion.bodycoach.TargetWire("bench_press", "Bench press", sets = 3, repsLow = 6, repsHigh = 8, weightLbs = 125.0, rule = "hold", words = "Same weight: 8, 7, 6 last time. Add weight when every set reaches 8."),
            com.kevin.legion.bodycoach.TargetWire("barbell_row", "Barbell row", sets = 3, repsLow = 8, repsHigh = 10, weightLbs = 105.0, rule = "hold"),
            com.kevin.legion.bodycoach.TargetWire("lateral_raise", "Lateral raise", sets = 2, repsLow = 12, repsHigh = 15, weightLbs = 15.0, rule = "hold"),
            com.kevin.legion.bodycoach.TargetWire("plank", "Plank", kind = "timed", sets = 2, repsLow = 30, repsHigh = 45, unit = "seconds", rule = "reps"),
        ),
    )
    private val week = com.kevin.legion.bodycoach.WeekWire(done = 1, planned = 3)
    private val workout = com.kevin.legion.bodycoach.WorkoutTodayWire(trainingDay = true, week = week, next = plan)

    private fun shot(file: String, content: @Composable () -> Unit) {
        rule.setContent { SoftTheme { content() } }
        rule.onRoot().captureRoboImage(file)
    }

    @Composable
    private fun OnGround(content: @Composable () -> Unit) {
        Column(Modifier.background(SoftColors.ground).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }

    @Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS)
    @Test
    fun `body - first screen, quick log and the trend`() =
        shot("body-a-first-screen.png") { BodyContent(state, BodyCoachUi(loaded = true, trend = trend, energy = energy, workout = workout)) }

    @Config(qualifiers = "w384dp-h900dp")
    @Test
    fun `body - today and energy with a pending proposal`() = shot("body-a-today-energy.png") {
        OnGround {
            BodyTodayCard(TodayIntake(1420, 2250, 118, 190), {}, {}, {})
            BodyEnergyCard(energy, note = null, busy = false, result = null, onAccept = {}, onDismiss = {}, onEditProfile = {})
        }
    }

    @Config(qualifiers = "w384dp-h700dp")
    @Test
    fun `body - this week and more`() = shot("body-a-week-more.png") {
        OnGround {
            BodyWeekCard(1, 3, 44.5, -0.25, weekSubtitle(week)) {}
            BodyMoreList({}, {}, {}, {}, {})
        }
    }

    @Config(qualifiers = "w384dp-h760dp")
    @Test
    fun `body - empty states, no profile, nothing logged, engine down`() = shot("body-a-empty.png") {
        OnGround {
            BodyTrendCard(null, note = "The engine could not be reached.", fallback = null) {}
            BodyWorkoutCard(null, note = "The engine could not be reached.", busy = false, message = null, onStart = {}, onOpen = {})
            BodyTodayCard(TodayIntake(null, null, null, null), {}, {}, {})
            BodyEnergyCard(
                EnergyWire(missing = listOf("height", "birth year", "activity level"), words = "No estimate yet."),
                note = "Saved 2 hours ago. The engine could not be reached.",
                busy = false, result = null, onAccept = {}, onDismiss = {}, onEditProfile = {},
            )
        }
    }

    @Config(qualifiers = "w384dp-h900dp")
    @Test
    fun `body - todays workout, training day and calibration`() = shot("body-workout-card.png") {
        OnGround {
            BodyWorkoutCard(workout, note = null, busy = false, message = null, onStart = {}, onOpen = {})
            BodyWorkoutCard(
                workout.copy(next = plan.copy(calibrating = true, exercises = plan.exercises.map { if (it.kind == "weighted") it.copy(weightLbs = null, calibrating = true, rule = "calibrate") else it })),
                note = null, busy = false, message = null, onStart = {}, onOpen = {},
            )
        }
    }

    /**
     * Kevin, 2026-11-06: "i cant see all the workouts on the screen, i only see day A". The card's
     * "See all workouts" opened: every day with its targets, then the next sessions. Synthetic.
     */
    @Config(qualifiers = "w384dp-h1400dp")
    @Test
    fun `body - see all workouts, opened`() {
        val dayB = listOf(
            com.kevin.legion.bodycoach.TargetWire("deadlift", "Romanian deadlift", sets = 3, repsLow = 8, repsHigh = 10, weightLbs = null, calibrating = true),
            com.kevin.legion.bodycoach.TargetWire("ohp", "Overhead press", sets = 3, repsLow = 6, repsHigh = 8, weightLbs = 75.0),
        )
        val all = workout.copy(
            days = listOf(
                com.kevin.legion.bodycoach.ProgrammeDayWire(dayKey = "A", dayLabel = "Day A", exercises = plan.exercises),
                com.kevin.legion.bodycoach.ProgrammeDayWire(dayKey = "B", dayLabel = "Day B", calibrating = true, exercises = dayB),
            ),
            upcoming = listOf(
                com.kevin.legion.bodycoach.UpcomingWire(dayKey = "A", dayLabel = "Day A", date = "", isToday = true),
                com.kevin.legion.bodycoach.UpcomingWire(dayKey = "B", dayLabel = "Day B", date = ""),
                com.kevin.legion.bodycoach.UpcomingWire(dayKey = "A", dayLabel = "Day A", date = ""),
            ),
        )
        rule.setContent { SoftTheme { OnGround { BodyWorkoutCard(all, note = null, busy = false, message = null, onStart = {}, onOpen = {}) } } }
        rule.onNodeWithText("See all workouts (2 days)").performClick()
        rule.onNodeWithText("Romanian deadlift").assertExists()
        rule.onNodeWithText("3 x 6-8 @ 75 lb").assertExists()
        rule.onNodeWithText("Next sessions").assertExists()
        rule.onNodeWithText("Hide all workouts").assertExists()
        rule.onRoot().captureRoboImage("body-workout-all-days.png")
    }

    @Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS)
    @Test
    fun `body - workout session, mid way`() = shot("body-workout-session.png") {
        val base = com.kevin.legion.bodycoach.WorkoutSessionController.localSession(plan, "shot", "2026-11-06")
        val s = listOf(1 to 8, 2 to 8, 3 to 7).fold(base) { acc, (n, r) ->
            com.kevin.legion.bodycoach.WorkoutSessionController.applyTick(acc, "back_squat", n, true, r, 155.0)
        }.let { com.kevin.legion.bodycoach.WorkoutSessionController.applyTick(it, "bench_press", 1, true, 8, 125.0) }
        WorkoutSessionScreen(s, note = null, message = null, busy = false, onBack = {}, onTick = { _, _ -> }, onEdit = { _, _, _ -> }, onSwap = {}, onFinish = {}, onCancel = {})
    }
}
