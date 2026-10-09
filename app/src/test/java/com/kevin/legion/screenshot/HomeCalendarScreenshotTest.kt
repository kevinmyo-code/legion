package com.kevin.legion.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import com.kevin.legion.ui.apps.DockPin
import com.kevin.legion.ui.apps.DrawerApp
import com.kevin.legion.ui.apps.HomeCategory
import com.kevin.legion.ui.apps.Loaded
import com.kevin.legion.ui.apps.iconKey
import com.kevin.legion.ui.home.AgendaKind
import com.kevin.legion.ui.home.AgendaRowUi
import com.kevin.legion.ui.home.CategoryUi
import com.kevin.legion.ui.home.DayMarkers
import com.kevin.legion.ui.home.HomeCalendarCallbacks
import com.kevin.legion.ui.home.HomeCalendarUiState
import com.kevin.legion.ui.home.HomeContent
import com.kevin.legion.ui.home.HomePanelSheetContent
import com.kevin.legion.ui.home.IdeaRowUi
import com.kevin.legion.ui.home.ListRowUi
import com.kevin.legion.ui.home.PanelSheet
import com.kevin.legion.ui.home.TodoRef
import com.kevin.legion.ui.home.TodoRowUi
import com.kevin.legion.ui.home.buildDockSlots
import com.kevin.legion.ui.home.buildHomeCells
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
 * One-home ticket 11: HOME's calendar at the A25's 384dp width, at font scale 1.0 and 1.3, in the
 * realistic content box (the screen minus the status line and talk bar, 384 x 636 as the earlier HOME
 * baselines used) and in the full 384 x 832 the brief asked for. Look at the PNGs: the point is that
 * the agenda, the three buttons, the dock and the category row are all on screen and none is clipped.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeCalendarScreenshotTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val today = LocalDate.of(2026, 10, 9)
    private val oct = YearMonth.of(2026, 10)

    private fun row(kind: AgendaKind, whenLabel: String, title: String, reminderId: Long? = null) = AgendaRowUi(
        key = "$kind-$title", kind = kind, whenLabel = whenLabel, title = title,
        typeLabel = when (kind) {
            AgendaKind.EVENT -> "Event"
            AgendaKind.TODO -> "To-do"
            AgendaKind.SUGGESTION -> "Suggestion · not a plan"
        },
        reminderId = reminderId,
    )

    private val markers = mapOf(
        LocalDate.of(2026, 10, 9) to DayMarkers(todos = 1, suggestions = 2),
        LocalDate.of(2026, 10, 10) to DayMarkers(events = 1, suggestions = 4),
        LocalDate.of(2026, 10, 11) to DayMarkers(todos = 1, suggestions = 1),
        LocalDate.of(2026, 10, 12) to DayMarkers(events = 1),
        LocalDate.of(2026, 10, 13) to DayMarkers(events = 2),
        LocalDate.of(2026, 10, 15) to DayMarkers(events = 3),
        LocalDate.of(2026, 10, 17) to DayMarkers(suggestions = 2),
        LocalDate.of(2026, 10, 31) to DayMarkers(events = 1, suggestions = 1),
    )

    private val friday = listOf(
        row(AgendaKind.TODO, "Due 11:59 PM", "Ch. 6 quiz · Python", reminderId = 4),
        row(AgendaKind.SUGGESTION, "All day", "Cuero Turkeyfest"),
        row(AgendaKind.SUGGESTION, "7:00 PM", "Terror on Tate Road"),
    )

    private val saturday = listOf(
        row(AgendaKind.EVENT, "9:00 AM", "Oil change · Cherokee"),
        row(AgendaKind.SUGGESTION, "All day", "Katy Rice Harvest Festival"),
        row(AgendaKind.SUGGESTION, "All day", "Korean Festival · Houston"),
        row(AgendaKind.SUGGESTION, "7:00 PM", "IceRays vs Rhinos · Corpus"),
        row(AgendaKind.SUGGESTION, "All day", "Texas Renaissance Festival"),
    )

    private val todos = listOf(
        TodoRowUi("1", "Ch. 6 quiz · Python", "Due today 11:59 PM", false, TodoRef.Reminder(4)),
        TodoRowUi("2", "Sprint 2 report · Software Eng", "Due Sun, Oct 11", false, TodoRef.Reminder(5)),
        TodoRowUi("3", "Follow up with financial aid", "No date", true, TodoRef.Reminder(6)),
        TodoRowUi("4", "Fix Cherokee fuel pump relay", "No date", false, TodoRef.Reminder(7)),
    )

    private fun state(selected: LocalDate, rows: List<AgendaRowUi>, sheet: PanelSheet? = null) = HomeCalendarUiState(
        today = today,
        month = oct,
        selectedDay = selected,
        cells = buildHomeCells(oct, today, selected, markers, firstDayOfWeek = DayOfWeek.SUNDAY),
        dayRows = rows,
        openTodoCount = todos.count { !it.done },
        todos = todos,
        lists = listOf(
            ListRowUi(1, "Groceries", "6 items"), ListRowUi(2, "Todo", "4 items"),
            ListRowUi(3, "Costco", "3 items"), ListRowUi(4, "Packing", "8 items"),
        ),
        ideas = listOf(
            IdeaRowUi(1, "Cuero Turkeyfest", "Oct 9 · Cuero"),
            IdeaRowUi(2, "Terror on Tate Road", "7:00 PM · Victoria"),
            IdeaRowUi(3, "Katy Rice Harvest Festival", "Oct 10 · Katy"),
            IdeaRowUi(4, "ACL Weekend 2", "Oct 11 · Austin"),
        ),
        sheet = sheet,
        loading = false,
    )

    // A solid-colour square standing in for a launcher icon (real ones need a device).
    private fun fakeIcon(color: Color): ImageBitmap {
        val bitmap = ImageBitmap(48, 48)
        Canvas(bitmap).drawRect(0f, 0f, 48f, 48f, Paint().apply { this.color = color })
        return bitmap
    }

    private fun app(label: String, pkg: String) = DrawerApp(label, pkg, "$pkg.Main", isWork = false, profileKey = 0)

    private val dockApps = listOf(
        app("WhatsApp", "com.whatsapp"), app("Spotify", "com.spotify.music"), app("Gmail", "com.google.android.gm"),
        app("Chrome", "com.android.chrome"), app("Google Maps", "com.google.android.apps.maps"),
    )
    private val waze = app("Waze", "com.waze")
    private val outlook = app("Outlook", "com.microsoft.office.outlook")
    private val palette = listOf(
        Color(0xFF25D366), Color(0xFF1DB954), Color(0xFFEA4335), Color(0xFF4285F4), Color(0xFF34A853),
        Color(0xFF33CCFF), Color(0xFF0078D4),
    )

    private val allApps = dockApps + waze + outlook
    private val loaded = Loaded(
        apps = allApps,
        icons = allApps.mapIndexed { i, a ->
            iconKey(a.profileKey, a.packageName, a.className) to fakeIcon(palette[i % palette.size])
        }.toMap(),
        handles = emptyMap(), workProfile = null, workPaused = false,
    )

    private fun pin(a: DrawerApp) = DockPin(a.packageName, a.profileKey)

    /** Bank unset, Music ONE app (wears its icon), Maps one app (Google Maps), Mail two, Chat unset. */
    private val categories = HomeCategory.entries.map {
        val picks = when (it) {
            HomeCategory.MUSIC -> listOf(pin(dockApps[1]))
            HomeCategory.MAPS -> listOf(pin(dockApps[4]))
            HomeCategory.MAIL -> listOf(pin(dockApps[2]), pin(outlook))
            else -> emptyList()
        }
        CategoryUi(it, buildDockSlots(picks, loaded))
    }

    private fun capture(fileName: String, fontScale: Float, state: HomeCalendarUiState) {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                HomeContent(
                    calendar = state,
                    calendarCallbacks = HomeCalendarCallbacks(),
                    nowPlaying = null,
                    onOpenMedia = {},
                    dockSlots = buildDockSlots(dockApps.map(::pin), loaded),
                    categories = categories,
                )
            }
        }
        rule.onRoot().captureRoboImage(fileName)
    }

    // ------------------------------------------------------------------------- 384 x 832

    @Config(qualifiers = "w384dp-h832dp")
    @Test
    fun `832 - friday, three rows, font 1_0`() = capture("home-calendar-832-fri.png", 1.0f, state(today, friday))

    @Config(qualifiers = "w384dp-h832dp")
    @Test
    fun `832 - saturday overflows to plus N more, font 1_3`() =
        capture("home-calendar-832-sat-font13.png", 1.3f, state(LocalDate.of(2026, 10, 10), saturday))

    // ------------------------------------------------------------------------- 384 x 636 (content box)

    @Config(qualifiers = "w384dp-h636dp")
    @Test
    fun `636 - saturday overflows to plus N more, font 1_0`() =
        capture("home-calendar-636-sat.png", 1.0f, state(LocalDate.of(2026, 10, 10), saturday))

    @Config(qualifiers = "w384dp-h636dp")
    @Test
    fun `636 - friday, font 1_3`() = capture("home-calendar-636-fri-font13.png", 1.3f, state(today, friday))

    @Config(qualifiers = "w384dp-h636dp")
    @Test
    fun `636 - saturday, font 1_3, the tightest case`() =
        capture("home-calendar-636-sat-font13.png", 1.3f, state(LocalDate.of(2026, 10, 10), saturday))

    @Config(qualifiers = "w384dp-h636dp")
    @Test
    fun `636 - a day with nothing on it`() =
        capture("home-calendar-636-empty.png", 1.0f, state(LocalDate.of(2026, 10, 14), emptyList()))

    // ------------------------------------------------------------------------- sheets

    private fun captureSheet(fileName: String, fontScale: Float, sheet: PanelSheet) {
        val s = state(today, friday, sheet)
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                SoftTheme {
                    Box(Modifier.fillMaxSize().background(SoftColors.card)) {
                        HomePanelSheetContent(sheet, s, HomeCalendarCallbacks())
                    }
                }
            }
        }
        rule.onRoot().captureRoboImage(fileName)
    }

    @Config(qualifiers = "w384dp-h600dp")
    @Test
    fun `sheet - to-dos, font 1_3`() = captureSheet("home-calendar-sheet-todos-font13.png", 1.3f, PanelSheet.TODOS)

    @Config(qualifiers = "w384dp-h600dp")
    @Test
    fun `sheet - lists`() = captureSheet("home-calendar-sheet-lists.png", 1.0f, PanelSheet.LISTS)

    @Config(qualifiers = "w384dp-h600dp")
    @Test
    fun `sheet - ideas`() = captureSheet("home-calendar-sheet-ideas.png", 1.0f, PanelSheet.IDEAS)
}
