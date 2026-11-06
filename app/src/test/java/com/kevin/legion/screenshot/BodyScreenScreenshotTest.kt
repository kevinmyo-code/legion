package com.kevin.legion.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
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
        sessionsDone = 2,
        sessionsPlanned = 3,
    )

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
        shot("body-a-first-screen.png") { BodyContent(state, BodyCoachUi(loaded = true, trend = trend, energy = energy)) }

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
            BodyWeekCard(2, 3, 44.5, -0.25) {}
            BodyMoreList({}, {}, {}, {}, {})
        }
    }

    @Config(qualifiers = "w384dp-h760dp")
    @Test
    fun `body - empty states, no profile, nothing logged, engine down`() = shot("body-a-empty.png") {
        OnGround {
            BodyTrendCard(null, note = "The engine could not be reached.", fallback = null) {}
            BodyTodayCard(TodayIntake(null, null, null, null), {}, {}, {})
            BodyEnergyCard(
                EnergyWire(missing = listOf("height", "birth year", "activity level"), words = "No estimate yet."),
                note = "Saved 2 hours ago. The engine could not be reached.",
                busy = false, result = null, onAccept = {}, onDismiss = {}, onEditProfile = {},
            )
        }
    }
}
