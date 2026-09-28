package com.kevin.legion.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.kevin.legion.ledger.AccountCoverage
import com.kevin.legion.ledger.BudgetLine
import com.kevin.legion.ledger.BudgetVsActual
import com.kevin.legion.ledger.ExcludedOwnAccountMovements
import com.kevin.legion.ledger.LedgerEntity
import com.kevin.legion.ledger.UncategorizedSpend
import com.kevin.legion.meals.DailyMealGap
import com.kevin.legion.meals.MacroTotals
import com.kevin.legion.plan.PlanGap
import com.kevin.legion.plan.TrustTier
import com.kevin.legion.ui.fleet.DueRowView
import com.kevin.legion.ui.home.HomeCallbacks
import com.kevin.legion.ui.home.HomeContent
import com.kevin.legion.ui.home.HomeUiState
import com.kevin.legion.ui.home.buildTodayChips
import java.time.YearMonth
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Home-launcher ticket 03's own verification step 4: `HomeContent` at 384 x 636dp (normal; alerts;
 * failures) and the 360 x 520dp fallback. Same runner/graphics-mode/`capture` shape as
 * [StatusLineScreenshotTest]; the qualifier here is the CONTENT BOX itself (the A25's screen minus
 * system bars/status line/talk bar, per this ticket's own "Fit, and the fallback" section), not the
 * whole-phone `w384dp-h832dp` [ScreenshotDeviceConfig] every other test in this package uses -
 * [HomeContent] never renders the chrome around it, so testing it against the whole-screen canvas
 * would be testing a box it never actually gets.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeContentScreenshotTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val callbacks = HomeCallbacks(
        onOpenCalendar = {}, onOpenLists = {}, onOpenMoney = {}, onOpenBody = {}, onOpenFleet = {},
        onOpenRecordings = {}, onOpenNews = {}, onOpenReports = {}, onOpenMedia = {},
        onStartRecording = {}, onStopRecording = {},
    )

    private fun budgetFixture(lines: List<BudgetLine>, uncategorizedCents: Long = 0L): BudgetVsActual =
        BudgetVsActual(
            entity = LedgerEntity.US,
            month = YearMonth.of(2026, 9),
            lines = lines,
            uncategorized = UncategorizedSpend(spentCents = uncategorizedCents, hasProvisionalRows = false),
            coverage = listOf(
                AccountCoverage(
                    "7823", coversWholeMonth = true, coveredFromMs = 0L, coveredToMs = 1L, coveredThroughMs = 1L,
                ),
            ),
            excludedOwnAccountMovements = ExcludedOwnAccountMovements(0, 0L, emptyList()),
        )

    @Config(qualifiers = "w384dp-h636dp")
    @Test
    fun `normal - a real day, nothing breaching`() {
        val state = HomeUiState(
            loading = false,
            weekdayLabel = "Sunday",
            dateLabel = "September 27",
            weatherText = "72F, partly cloudy",
            weatherIconRes = com.kevin.legion.R.drawable.ms_partly_cloudy_day,
            areaAqiLine = "Houston, TX - AQI 42 (Good) - PM2.5, Downtown",
            nextLine = "Next: Team standup - 9:00 AM",
            chips = buildTodayChips(dueTodayCount = 2, overdueCount = 0, calendarReadFailed = false),
            checklistCount = 3,
            budget = budgetFixture(
                listOf(
                    BudgetLine(
                        category = "Dining Out",
                        gap = PlanGap(target = 40_000L, actual = 10_000L, gap = 30_000L, tier = TrustTier.PROVEN),
                        hasProvisionalRows = false,
                        hasPendingCategoryGuesses = false,
                    ),
                ),
            ),
            mealGap = DailyMealGap.Logged(
                PlanGap(
                    target = MacroTotals(2_200, 0.0, 0.0, 0.0),
                    actual = MacroTotals(1_450, 0.0, 0.0, 0.0),
                    gap = MacroTotals(750, 0.0, 0.0, 0.0),
                    tier = TrustTier.PROVEN,
                ),
            ),
            hasMealTarget = true,
            maintenanceRows = listOf(
                DueRowView(label = "Oil change", value = "1,200 mi", sub = "every 5,000 mi", overdue = false),
            ),
            maintenanceUnknownCount = 0,
            voiceNotesCount = 4,
        )
        capture("home-normal.png", state, recording = false, recordRefusal = null)
    }

    @Config(qualifiers = "w384dp-h636dp")
    @Test
    fun `alerts - over budget, overdue maintenance, recording, estimated body`() {
        val state = HomeUiState(
            loading = false,
            weekdayLabel = "Sunday",
            dateLabel = "September 27",
            weatherText = "72F, partly cloudy",
            areaAqiLine = "Houston, TX - AQI 42 (Good) - PM2.5, Downtown",
            nextLine = "Nothing else on the calendar today",
            chips = buildTodayChips(dueTodayCount = 5, overdueCount = 2, calendarReadFailed = false),
            checklistCount = 1,
            budget = budgetFixture(
                listOf(
                    BudgetLine(
                        category = "Dining Out",
                        gap = PlanGap(target = 20_000L, actual = 24_500L, gap = -4_500L, tier = TrustTier.PROVEN),
                        hasProvisionalRows = false,
                        hasPendingCategoryGuesses = false,
                    ),
                ),
                uncategorizedCents = 4_250L,
            ),
            mealGap = DailyMealGap.Logged(
                PlanGap(
                    target = MacroTotals(2_200, 0.0, 0.0, 0.0),
                    actual = MacroTotals(1_450, 0.0, 0.0, 0.0),
                    gap = MacroTotals(750, 0.0, 0.0, 0.0),
                    tier = TrustTier.REPORTED,
                ),
            ),
            hasMealTarget = true,
            maintenanceRows = listOf(
                DueRowView(label = "Oil change", value = "OVERDUE", sub = "was due 200 mi ago", overdue = true),
            ),
            maintenanceUnknownCount = 0,
            voiceNotesCount = 2,
        )
        capture("home-alerts.png", state, recording = true, recordRefusal = null)
    }

    @Config(qualifiers = "w384dp-h636dp")
    @Test
    fun `failures - calendar unreadable, weather unavailable, location refused`() {
        val state = HomeUiState(
            loading = false,
            weekdayLabel = "Sunday",
            dateLabel = "September 27",
            weatherText = "Weather not available yet - no location fix",
            areaAqiLine = "Location permission not granted - grant it to see the area.",
            nextLine = "Couldn't read the calendar",
            chips = buildTodayChips(dueTodayCount = 0, overdueCount = 0, calendarReadFailed = true),
            checklistCount = 0,
            budget = null,
            mealGap = DailyMealGap.NotLogged,
            hasMealTarget = false,
            maintenanceRows = emptyList(),
            maintenanceUnknownCount = 0,
            voiceNotesCount = 0,
        )
        capture("home-failures.png", state, recording = false, recordRefusal = "Microphone permission not granted.")
    }

    @Config(qualifiers = "w360dp-h520dp")
    @Test
    fun `fallback - too small for the fixed grid, scrolls instead of clipping`() {
        val state = HomeUiState(
            loading = false,
            weekdayLabel = "Sunday",
            dateLabel = "September 27",
            weatherText = "72F, partly cloudy",
            areaAqiLine = "Houston, TX - AQI 42 (Good) - PM2.5, Downtown",
            nextLine = "Next: Team standup - 9:00 AM",
            chips = buildTodayChips(dueTodayCount = 2, overdueCount = 0, calendarReadFailed = false),
            checklistCount = 3,
            budget = null,
            mealGap = DailyMealGap.NotLogged,
            hasMealTarget = true,
            maintenanceRows = emptyList(),
            maintenanceUnknownCount = 0,
            voiceNotesCount = 1,
        )
        capture("home-fallback-360x520.png", state, recording = false, recordRefusal = null)
    }

    private fun capture(fileName: String, state: HomeUiState, recording: Boolean, recordRefusal: String?) {
        composeTestRule.setContent {
            HomeContent(
                state = state,
                recording = recording,
                recordRefusal = recordRefusal,
                nowPlaying = null,
                callbacks = callbacks,
            )
        }
        composeTestRule.onRoot().captureRoboImage(fileName)
    }
}
