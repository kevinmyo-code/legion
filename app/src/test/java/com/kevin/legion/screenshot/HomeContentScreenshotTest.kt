package com.kevin.legion.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.ledger.AccountCoverage
import com.kevin.legion.ledger.CategorySpend
import com.kevin.legion.ledger.CombinedMonthSpend
import com.kevin.legion.ledger.BudgetLine
import com.kevin.legion.ledger.BudgetVsActual
import com.kevin.legion.ledger.ExcludedOwnAccountMovements
import com.kevin.legion.ledger.LedgerEntity
import com.kevin.legion.ledger.UncategorizedSpend
import com.kevin.legion.meals.DailyMealGap
import com.kevin.legion.meals.MacroTotals
import com.kevin.legion.plan.PlanGap
import com.kevin.legion.plan.TrustTier
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import com.kevin.legion.media.NowPlayingInfo
import com.kevin.legion.ui.apps.DockPin
import com.kevin.legion.ui.apps.HomeCategory
import com.kevin.legion.ui.apps.iconKey
import com.kevin.legion.ui.home.CategoryUi
import com.kevin.legion.ui.apps.DrawerApp
import com.kevin.legion.ui.apps.Loaded
import com.kevin.legion.ui.fleet.DueRowView
import com.kevin.legion.ui.home.DockCallbacks
import com.kevin.legion.ui.home.DockSlotUi
import com.kevin.legion.ui.home.HomeCallbacks
import com.kevin.legion.ui.home.HomeTilesContent
import com.kevin.legion.ui.home.HomeUiState
import com.kevin.legion.ui.home.buildDockSlots
import com.kevin.legion.ui.home.buildTodayChips
import java.time.LocalDate
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
            moneyMonth = moneyMonthFixture(currentPeriod = false, categoryCount = 3),
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
        val state = alertsState()
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
    fun `fallback - too small for the fixed grid, scrolls instead of clipping, dock included`() {
        val app = installedApp("com.whatsapp", "WhatsApp")
        val loaded = Loaded(apps = listOf(app), icons = emptyMap(), handles = emptyMap(), workProfile = null, workPaused = false)
        val dockSlots = buildDockSlots(listOf(DockPin(app.packageName, app.profileKey)), loaded)
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
        capture("home-fallback-360x520.png", state, recording = false, recordRefusal = null, dockSlots = dockSlots)
    }

    // ---------------------------------------------------------------------------- ticket 06's dock

    /** Production HOME always shows the five category buttons, so every shot carries them; unset is
     * what a fresh install looks like. */
    private val unsetCategories = HomeCategory.entries.map { CategoryUi(it, emptyList()) }

    private val palette = listOf(
        Color(0xFF7EDBA5), Color(0xFFA8C8FF), Color(0xFFFFD36B),
        Color(0xFFFFB1C3), Color(0xFF77DCE5), Color(0xFFCDBDFF),
    )

    /** A solid-colour square standing in for a launcher icon (real ones need a device). */
    private fun fakeIcon(i: Int): ImageBitmap {
        val bitmap = ImageBitmap(48, 48)
        Canvas(bitmap).drawRect(0f, 0f, 48f, 48f, Paint().apply { color = palette[i % palette.size] })
        return bitmap
    }

    private val noopDock = DockCallbacks(onLaunch = {}, onUnpin = {}, onMoveLeft = {}, onMoveRight = {})

    private fun installedApp(pkg: String, label: String) = DrawerApp(label, pkg, "$pkg.Main", isWork = false, profileKey = 0)

    @Config(qualifiers = "w384dp-h636dp")
    @Test
    fun `dock - five pinned apps, all installed`() {
        val apps = listOf(
            installedApp("com.whatsapp", "WhatsApp"),
            installedApp("com.spotify.music", "Spotify"),
            installedApp("com.google.android.gm", "Gmail"),
            installedApp("com.android.chrome", "Chrome"),
            installedApp("com.google.android.apps.maps", "Maps"),
        )
        val pins = apps.map { DockPin(it.packageName, it.profileKey) }
        val loaded = Loaded(apps = apps, icons = emptyMap(), handles = emptyMap(), workProfile = null, workPaused = false)
        val state = fallbackState()
        capture("home-dock-full.png", state, recording = false, recordRefusal = null, dockSlots = buildDockSlots(pins, loaded))
    }

    @Config(qualifiers = "w384dp-h636dp")
    @Test
    fun `dock - empty, states how to pin an app rather than a blank strip`() {
        val state = fallbackState()
        capture("home-dock-empty.png", state, recording = false, recordRefusal = null, dockSlots = emptyList())
    }

    @Config(qualifiers = "w384dp-h636dp")
    @Test
    fun `dock - one pin no longer installed, dimmed and worded, never dropped`() {
        val installed = installedApp("com.whatsapp", "WhatsApp")
        val pins = listOf(DockPin(installed.packageName, installed.profileKey), DockPin("com.gone.app", 0))
        val loaded = Loaded(apps = listOf(installed), icons = emptyMap(), handles = emptyMap(), workProfile = null, workPaused = false)
        val state = fallbackState()
        capture("home-dock-not-installed.png", state, recording = false, recordRefusal = null, dockSlots = buildDockSlots(pins, loaded))
    }

    @Config(qualifiers = "w384dp-h636dp")
    @Test
    fun `dock and categories - cold start, snapshot not loaded, neutral rather than Not installed`() {
        val pins = listOf(DockPin("com.whatsapp", 0), DockPin("com.spotify.music", 0))
        val categories = HomeCategory.entries.map {
            CategoryUi(it, buildDockSlots(listOf(DockPin("com.spotify.music", 0), DockPin("com.whatsapp", 0)), null))
        }
        capture(
            "home-dock-loading.png", fallbackState(), recording = false, recordRefusal = null,
            dockSlots = buildDockSlots(pins, null), categories = categories,
        )
    }


    // ---------------------------------------------------------------------------- ticket 07's row

    /** Full dock plus a mixed category row on the worst-case tile state: Bank unset, Music one app,
     * Maps two, Mail two (one is a work app), Chat one that is no longer installed. */
    @Config(qualifiers = "w384dp-h636dp")
    @Test
    fun `categories - full dock and a mixed row on the alerts state`() = captureMixed("home-categories-mixed.png")

    /** The same worst case with music playing: the now-playing row takes another ~60dp. */
    @Config(qualifiers = "w384dp-h636dp")
    @Test
    fun `categories - mixed row while music is playing`() = captureMixed(
        "home-categories-now-playing.png",
        NowPlayingInfo(
            title = "Midnight City", artist = "M83", album = "Hurry Up", isPlaying = true, position = 0L, duration = 1L,
        ),
    )

    private fun captureMixed(fileName: String, nowPlaying: NowPlayingInfo? = null) {
        val dockApps = listOf(
            installedApp("com.whatsapp", "WhatsApp"),
            installedApp("com.spotify.music", "Spotify"),
            installedApp("com.google.android.gm", "Gmail"),
            installedApp("com.android.chrome", "Chrome"),
            installedApp("com.google.android.apps.maps", "Maps"),
        )
        val outlookWork = DrawerApp("Outlook", "com.microsoft.office.outlook", "o.Main", isWork = true, profileKey = 10)
        val waze = installedApp("com.waze", "Waze")
        val apps = dockApps + waze + outlookWork
        val icons = apps.mapIndexed { i, a -> iconKey(a.profileKey, a.packageName, a.className) to fakeIcon(i) }.toMap()
        val loaded = Loaded(apps = apps, icons = icons, handles = emptyMap(), workProfile = null, workPaused = false)
        fun pin(a: DrawerApp) = DockPin(a.packageName, a.profileKey)
        val picks = mapOf(
            HomeCategory.MUSIC to listOf(pin(dockApps[1])),
            HomeCategory.MAPS to listOf(pin(dockApps[4]), pin(waze)),
            HomeCategory.MAIL to listOf(pin(dockApps[2]), pin(outlookWork)),
            HomeCategory.CHAT to listOf(DockPin("com.gone.chat", 0)),
        )
        val categories = HomeCategory.entries.map { CategoryUi(it, buildDockSlots(picks[it].orEmpty(), loaded)) }
        capture(
            fileName, alertsState(), recording = true, recordRefusal = null,
            dockSlots = buildDockSlots(dockApps.map(::pin), loaded), categories = categories, nowPlaying = nowPlaying,
        )
    }

    /** The Money tile's combined month: [categoryCount] categories (largest first), the first three become bars. */
    private fun moneyMonthFixture(currentPeriod: Boolean, categoryCount: Int): CombinedMonthSpend {
        val all = listOf(
            "Housing" to 180_000L, "Groceries" to 21_437L, "Dining" to 18_880L, "Fuel" to 4_512L, "Utilities" to 9_850L,
        ).sortedByDescending { it.second }.take(categoryCount)
        val total = all.sumOf { it.second }
        return CombinedMonthSpend(
            currency = LedgerCurrency.USD, month = YearMonth.of(2026, 9), totalCents = total,
            categories = all.map {
                CategorySpend(it.first, it.second, if (currentPeriod) it.second else 0L, hasPendingGuess = false)
            },
            uncategorizedCents = 0L,
            unverifiedTotalCents = if (currentPeriod) total else 0L,
            unreadableNames = emptyList(), hasActivity = true, newest = LocalDate.of(2026, 9, 27),
        )
    }

    /** The phone's real state: newest row last month, nothing this month, so no bars of zero. */
    @Config(qualifiers = "w384dp-h636dp")
    @Test
    fun `money tile - stale data says no data yet for the month`() {
        val stale = CombinedMonthSpend(
            currency = LedgerCurrency.USD, month = YearMonth.of(2026, 10), totalCents = 0L, categories = emptyList(),
            uncategorizedCents = 0L, unverifiedTotalCents = 0L, unreadableNames = emptyList(),
            hasActivity = false, newest = LocalDate.of(2026, 9, 26),
        )
        capture("home-money-stale.png", fallbackState().copy(moneyMonth = stale), recording = false, recordRefusal = null)
    }

    private fun alertsState(): HomeUiState {
        return HomeUiState(
            loading = false,
            weekdayLabel = "Sunday",
            dateLabel = "September 27",
            weatherText = "72F, partly cloudy",
            areaAqiLine = "Houston, TX - AQI 42 (Good) - PM2.5, Downtown",
            nextLine = "Nothing else on the calendar today",
            chips = buildTodayChips(dueTodayCount = 5, overdueCount = 2, calendarReadFailed = false),
            checklistCount = 1,
            moneyMonth = moneyMonthFixture(currentPeriod = true, categoryCount = 5),
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
    }

    private fun fallbackState() = HomeUiState(
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

    private fun capture(
        fileName: String,
        state: HomeUiState,
        recording: Boolean,
        recordRefusal: String?,
        dockSlots: List<DockSlotUi> = emptyList(),
        categories: List<CategoryUi> = unsetCategories,
        nowPlaying: NowPlayingInfo? = null,
    ) {
        composeTestRule.setContent {
            HomeTilesContent(
                state = state,
                recording = recording,
                recordRefusal = recordRefusal,
                nowPlaying = nowPlaying,
                callbacks = callbacks,
                dockSlots = dockSlots,
                dock = noopDock,
                categories = categories,
            )
        }
        composeTestRule.onRoot().captureRoboImage(fileName)
    }
}
