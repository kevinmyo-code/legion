package com.kevin.legion.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.kevin.legion.finance.FinanceFixtures
import com.kevin.legion.finance.BudgetTotalWire
import com.kevin.legion.finance.FinanceRead
import com.kevin.legion.ui.home.AgendaKind
import com.kevin.legion.ui.home.AgendaRowUi
import com.kevin.legion.ui.home.DayMarkers
import com.kevin.legion.ui.home.HomeCalendarArea
import com.kevin.legion.ui.home.HomeCalendarCallbacks
import com.kevin.legion.ui.home.HomeCalendarUiState
import com.kevin.legion.ui.home.HomeContent
import com.kevin.legion.ui.home.HomeMoneyUiState
import com.kevin.legion.ui.home.MoneyGlanceCard
import com.kevin.legion.ui.home.buildHomeCells
import com.kevin.legion.ui.home.homeMoneyPanel
import com.kevin.legion.ui.money.MoneyMonthPage
import com.kevin.legion.ui.money.MoneyTxnRow
import com.kevin.legion.ui.money.MoneyViewContent
import com.kevin.legion.ui.money.MoneyViewHost
import com.kevin.legion.ui.money.MoneyViewCallbacks
import com.kevin.legion.ui.money.MoneyViewUiState
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.theme.soft.SoftTheme
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Household-finance tickets 13 and 14, against the approved prototype
 * (`legion-money-prototypes/money-screens.html`), cut to the budget on Kevin's 2026-11-05 run: HOME's
 * Money panel (fresh, font 1.3, saved copy, engine down, over budget), the More card, the Calendar
 * sheet, and the Money view's current month top to bottom. Synthetic figures only.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MoneyScreensScreenshotTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val today = FinanceFixtures.today
    private val nov = YearMonth.of(2026, 11)

    private val calendar = HomeCalendarUiState(
        today = today,
        month = nov,
        selectedDay = today,
        cells = buildHomeCells(
            nov, today, today,
            mapOf(today to DayMarkers(events = 1, todos = 1), today.plusDays(2) to DayMarkers(events = 2)),
            firstDayOfWeek = DayOfWeek.SUNDAY,
        ),
        dayRows = listOf(
            AgendaRowUi("1", AgendaKind.EVENT, "10:00 AM", "COSC 4320 lecture", "Event"),
            AgendaRowUi("2", AgendaKind.EVENT, "2:30 PM", "Team standup", "Event"),
            AgendaRowUi("3", AgendaKind.TODO, "Due today", "Pay the card", "To-do", reminderId = 3),
        ),
        loading = false,
    )

    private fun home(file: String, money: HomeMoneyUiState, fontScale: Float = 1f) {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                HomeContent(
                    calendar = calendar,
                    calendarCallbacks = HomeCalendarCallbacks(),
                    nowPlaying = null,
                    onOpenMedia = {},
                    money = money,
                )
            }
        }
        rule.onRoot().captureRoboImage(file)
    }

    @Config(qualifiers = "w384dp-h636dp")
    @Test
    fun `home - money panel, fresh`() = home("home-money-panel.png", FinanceFixtures.homeFresh)

    @Config(qualifiers = "w384dp-h636dp")
    @Test
    fun `home - money panel, font 1_3, the bars scroll under the header`() =
        home("home-money-panel-font13.png", FinanceFixtures.homeFresh, 1.3f)

    @Config(qualifiers = "w384dp-h636dp")
    @Test
    fun `home - engine down, the saved copy says how old it is`() {
        val note = "Saved 3 hours ago. The engine could not be reached."
        home(
            "home-money-panel-saved.png",
            FinanceFixtures.homeFresh.copy(budgets = FinanceRead.Saved(FinanceFixtures.budgets, 0L, note)),
        )
    }

    @Config(qualifiers = "w384dp-h636dp")
    @Test
    fun `home - over the whole budget, said in words in the warning colour`() = home(
        "home-money-panel-over.png",
        FinanceFixtures.homeFresh.copy(
            budgets = FinanceRead.Fresh(
                FinanceFixtures.budgets.copy(
                    total = BudgetTotalWire(
                        spentCents = 171_250,
                        capCents = 160_000,
                        percent = 107,
                        paceCents = 26_667,
                    ),
                ),
            ),
        ),
    )

    @Config(qualifiers = "w384dp-h832dp")
    @Test
    fun `more - the Money card, total bar and the three closest`() {
        rule.setContent {
            SoftTheme {
                Box(Modifier.background(SoftColors.card).padding(16.dp)) {
                    MoneyGlanceCard(homeMoneyPanel(FinanceFixtures.homeFresh)) {}
                }
            }
        }
        rule.onRoot().captureRoboImage("more-money-card.png")
    }

    @Config(qualifiers = "w384dp-h636dp")
    @Test
    fun `home - engine down and nothing saved`() = home(
        "home-money-panel-unavailable.png",
        HomeMoneyUiState(loading = false, budgets = FinanceRead.Unavailable("The engine could not be reached.")),
    )

    @Config(qualifiers = "w384dp-h700dp")
    @Test
    fun `calendar sheet - the month grid and the day, as the sheet draws it`() {
        rule.setContent {
            SoftTheme {
                Box(Modifier.fillMaxSize().background(SoftColors.ground).padding(horizontal = 12.dp, vertical = 8.dp)) {
                    HomeCalendarArea(calendar, HomeCalendarCallbacks(), Modifier.fillMaxSize())
                }
            }
        }
        rule.onRoot().captureRoboImage("home-calendar-sheet.png")
    }

    private val txns = listOf(
        MoneyTxnRow(
            1,
            LocalDate.of(2026, 11, 4),
            "Grocery store",
            -3_210,
            "USD",
            "Groceries",
            pending = true,
            currentPeriod = false,
        ),
        MoneyTxnRow(
            2,
            LocalDate.of(2026, 11, 3),
            "A subscription",
            -10_700,
            "USD",
            "Subscriptions",
            pending = false,
            currentPeriod = false,
        ),
        MoneyTxnRow(
            3,
            LocalDate.of(2026, 11, 2),
            "Another grocery store",
            -6_390,
            "USD",
            "Groceries",
            pending = false,
            currentPeriod = true,
        ),
        MoneyTxnRow(
            4,
            LocalDate.of(2026, 11, 1),
            "Something uncategorised",
            -1_500,
            "USD",
            null,
            pending = false,
            currentPeriod = false,
        ),
    )

    private fun moneyView(file: String, state: MoneyViewUiState) {
        rule.setContent {
            SoftTheme {
                MoneyViewContent(
                    state = state,
                    host = MoneyViewHost(
                        months = listOf(YearMonth.of(2026, 9), YearMonth.of(2026, 10), nov),
                        pickedMonth = nov,
                        toCategorizeCount = 2,
                        showSwipeHint = true,
                    ),
                    callbacks = MoneyViewCallbacks(),
                )
            }
        }
        rule.onRoot().captureRoboImage(file)
    }

    private val fresh = MoneyViewUiState(
        today = today,
        safe = FinanceRead.Fresh(FinanceFixtures.safe),
        netWorth = FinanceRead.Fresh(FinanceFixtures.netWorth),
        accounts = FinanceRead.Fresh(FinanceFixtures.accounts),
        pages = mapOf(
            nov to MoneyMonthPage(
                FinanceRead.Fresh(FinanceFixtures.budgets),
                FinanceRead.Fresh(FinanceFixtures.cashFlow),
                txns,
            ),
        ),
    )

    // Tall on purpose: the whole page, every section, in one image.
    @Config(qualifiers = "w384dp-h3000dp")
    @Test
    fun `money view - this month, every section`() = moneyView("money-view-month.png", fresh)

    @Config(qualifiers = "w384dp-h832dp")
    @Test
    fun `money view - budgets unreadable, transactions still loading`() = moneyView(
        "money-view-budgets-unavailable.png",
        fresh.copy(
            pages = mapOf(
                nov to MoneyMonthPage(FinanceRead.Unavailable("The engine could not be reached."), null, null),
            ),
        ),
    )
}
