package com.kevin.legion.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.theme.soft.SoftTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import com.kevin.legion.assistant.cards.ChecklistLine
import com.kevin.legion.assistant.cards.ConversationCard
import com.kevin.legion.ui.apps.DockPin
import com.kevin.legion.ui.apps.DrawerApp
import com.kevin.legion.ui.apps.HomeCategory
import com.kevin.legion.ui.home.CategoryUi
import com.kevin.legion.ui.home.DockSlotUi
import com.kevin.legion.ui.typeset.TypesetGroupSheet
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.typeset.OrbPhase
import com.kevin.legion.ui.typeset.TypesetDockTile
import com.kevin.legion.ui.typeset.TypesetFigure
import com.kevin.legion.ui.typeset.TypesetGlance
import com.kevin.legion.ui.typeset.TypesetGroupCell
import com.kevin.legion.ui.typeset.TypesetHomeActions
import com.kevin.legion.ui.typeset.TypesetHomeContent
import com.kevin.legion.ui.typeset.TypesetPage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The front page fits one screen with no scroll (Kevin, 2026-11-10), at 360x640, 412x915 (A25-like)
 * and 412x915 at font 1.3, day and night, idle, listening and answering. Each test renders a
 * baseline AND asserts: nothing placed below the viewport, and every tap target at least 44dp tall.
 * The page sits under the shell's StatusLine ([ShellHome]), as on the phone: the bar is in the shot
 * and its height comes out of the page's.
 *
 * The page is drawn through [TypesetPage] under [LegionTheme], the way `MainActivity` draws HOME, with
 * NO SoftTheme of the test's own: the light-mode render is what caught "the home screen is still
 * black" (the page had no SoftTheme, so it drew the soft-dark palette whatever the phone said).
 * Fake apps and letter tiles only: the repo is public.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TypesetFitScreenshotTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun page(file: String, fontScale: Float = 1f, phase: OrbPhase = OrbPhase.IDLE, answering: Boolean = false) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                LegionTheme { ShellHome { TypesetPage { Front(phase, answering) } } }
            }
        }
        rule.mainClock.advanceTimeBy(FRAME_MS)
        rule.onRoot().captureRoboImage(file)
        assertFits(file)
    }

    private fun assertFits(file: String) {
        val root = rule.onRoot().fetchSemanticsNode()
        val height = root.boundsInRoot.bottom
        val minTarget = with(root.layoutInfo.density) { MIN_TARGET_DP * density }
        // The 44dp check covers the page. The StatusLine above it is the shell's, drawn exactly as on
        // All apps (its own tests own it); its pills and 40dp icon buttons are reported, not changed.
        val pageTop = rule.onNodeWithTag(PAGE_TAG).fetchSemanticsNode().boundsInRoot.top
        val problems = mutableListOf<String>()
        fun walk(n: SemanticsNode) {
            if (!n.layoutInfo.isPlaced) return
            val b = n.boundsInRoot
            if (b.bottom > height + SLACK_PX) problems += "${n.describe()} ends at ${b.bottom}, viewport $height"
            val onPage = b.top + SLACK_PX >= pageTop
            if (onPage && n.config.contains(SemanticsActions.OnClick) && b.height + SLACK_PX < minTarget) {
                problems += "${n.describe()} is ${b.height}px tall, under 44dp ($minTarget px)"
            }
            n.children.forEach(::walk)
        }
        walk(rule.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        assertTrue("$file:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    private fun SemanticsNode.describe(): String {
        val words = config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
            ?: config.getOrNull(SemanticsProperties.Text)?.joinToString()
            ?: generateSequence(this) { it.parent }.drop(1)
                .firstNotNullOfOrNull { p ->
                    p.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
                }
                ?.let { "inside '$it'" }
            ?: config.toString().take(DESCRIBE_CHARS)
        return "'$words' at $boundsInRoot"
    }

    // 360 x 640, a small phone
    @Test @Config(qualifiers = SMALL)
    fun `small, day`() = page("typeset-fit-360x640-day.png")

    @Test @Config(qualifiers = "$SMALL-night")
    fun `small, night`() = page("typeset-fit-360x640-night.png")

    @Test @Config(qualifiers = SMALL)
    fun `small, listening`() = page("typeset-fit-360x640-listening.png", phase = OrbPhase.LISTENING)

    @Test @Config(qualifiers = SMALL)
    fun `small, answering`() = page("typeset-fit-360x640-answer.png", phase = OrbPhase.SPEAKING, answering = true)

    // 412 x 915, A25-like
    @Test @Config(qualifiers = A25)
    fun `a25, day`() = page("typeset-fit-412x915-day.png")

    @Test @Config(qualifiers = "$A25-night")
    fun `a25, night`() = page("typeset-fit-412x915-night.png")

    @Test @Config(qualifiers = A25)
    fun `a25, listening`() = page("typeset-fit-412x915-listening.png", phase = OrbPhase.LISTENING)

    @Test @Config(qualifiers = "$A25-night")
    fun `a25, answering, night`() =
        page("typeset-fit-412x915-answer-night.png", phase = OrbPhase.SPEAKING, answering = true)

    // 412 x 915 at the largest font scale the brief names
    @Test @Config(qualifiers = A25)
    fun `a25 font 1_3, day`() = page("typeset-fit-412x915-font13-day.png", fontScale = 1.3f)

    @Test @Config(qualifiers = "$A25-night")
    fun `a25 font 1_3, night`() = page("typeset-fit-412x915-font13-night.png", fontScale = 1.3f)

    @Test @Config(qualifiers = SMALL)
    fun `small font 1_3, day`() = page("typeset-fit-360x640-font13-day.png", fontScale = 1.3f)

    // 412 x 820: roughly what the A25 leaves the page once its status and nav bars are drawn.
    @Test @Config(qualifiers = A25_VIEW)
    fun `a25 viewport, day`() = page("typeset-fit-412x820-day.png")

    @Test @Config(qualifiers = "$A25_VIEW-night")
    fun `a25 viewport font 1_3, night`() = page("typeset-fit-412x820-font13-night.png", fontScale = 1.3f)

    /**
     * A soft screen inside the shell: LegionTheme's Surface hands down a light-on-dark content colour.
     * Before the fix an uncoloured Text kept it, cream on paper by day; SoftTheme now swaps in ink.
     */
    @Test @Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS)
    fun `soft screen in the shell, day`() = shell("typeset-shell-ink-day.png")

    @Test @Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS + "-night")
    fun `soft screen in the shell, night`() = shell("typeset-shell-ink-night.png")

    private fun shell(file: String) {
        rule.setContent {
            LegionTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    SoftTheme {
                        Column(Modifier.fillMaxSize().background(SoftColors.ground).padding(16.dp)) {
                            Text("Claude sessions")
                            Text("A line with no colour of its own reads in this edition's ink.")
                        }
                    }
                }
            }
        }
        rule.onRoot().captureRoboImage(file)
    }

    /** A group opened: two apps picked, so it asks which (the old row's own sheet, in the edition). */
    @Test @Config(qualifiers = A25)
    fun `group sheet, day`() = sheet("typeset-group-sheet-day.png")

    @Test @Config(qualifiers = "$A25-night")
    fun `group sheet, night`() = sheet("typeset-group-sheet-night.png")

    private fun sheet(file: String) {
        rule.setContent {
            LegionTheme {
                TypesetPage { TypesetGroupSheet(BANK, onLaunch = {}, onChoose = {}, onOpenNavigation = null) }
            }
        }
        rule.onRoot().captureRoboImage(file)
    }

    @Composable
    private fun Front(phase: OrbPhase, answering: Boolean) {
        TypesetHomeContent(
            glance = GLANCE,
            phase = phase,
            status = when (phase) {
                OrbPhase.LISTENING -> "Listening"
                OrbPhase.SPEAKING -> "Alfred is talking"
                else -> "Tap to talk"
            },
            night = isSystemInDarkTheme(),
            cards = if (answering) listOf(CARD) else emptyList(),
            actions = TypesetHomeActions(),
            dock = DOCK,
            groups = {
                Row(Modifier.fillMaxWidth()) {
                    GROUPS.forEach { TypesetGroupCell(it, onClick = {}, onLongClick = {}) }
                }
            },
        )
    }

    private companion object {
        const val FRAME_MS = 800L
        const val MIN_TARGET_DP = 44f
        const val SLACK_PX = 1f
        const val DESCRIBE_CHARS = 160
        const val SMALL = "w360dp-h640dp"
        const val A25 = "w412dp-h915dp"
        const val A25_VIEW = "w412dp-h820dp"

        /** The long money value from Kevin's report: it wrapped onto two lines before. */
        val GLANCE = TypesetGlance(
            dateline = "Monday, 9 November",
            time = "14:20",
            next = TypesetFigure("15:30", null, "Study group", listOf("in 1 h 10 min, the library")),
            due = TypesetFigure("3", null, "due today", listOf("Essay draft", "Pick up the parcel", "and 1 more")),
            left = TypesetFigure("\$12,345.67", null, "left for November", listOf("of \$20,000.00, 21 days left")),
        )
        val CARD = ConversationCard.Checklist(
            title = "Due today",
            listName = "Due today",
            items = listOf(ChecklistLine("Essay draft", false), ChecklistLine("Pick up the parcel", false)),
        )
        val DOCK = listOf(
            TypesetDockTile("Ch", "Chatter"),
            TypesetDockTile("Ma", "Mailbox"),
            TypesetDockTile("Pi", "Pictures"),
            TypesetDockTile("Tu", "Tunes"),
            TypesetDockTile("Ca", "Camera"),
        )

        private fun slot(label: String, pkg: String) = DockSlotUi(
            pin = DockPin(pkg, 0),
            app = DrawerApp(label, pkg, "$pkg.Main", isWork = false, profileKey = 0),
            label = label,
            icon = null,
            paused = false,
        )

        val BANK = CategoryUi(
            HomeCategory.BANK,
            listOf(slot("Ledgerly", "test.ledgerly"), slot("Coinbook", "test.coinbook")),
        )
        val GROUPS = listOf(
            BANK,
            CategoryUi(HomeCategory.MUSIC, listOf(slot("Tunes", "test.tunes"))),
            CategoryUi(HomeCategory.MAPS, emptyList()),
            CategoryUi(HomeCategory.MAIL, listOf(slot("Mailbox", "test.mailbox"))),
            CategoryUi(HomeCategory.CHAT, emptyList()),
        )
    }
}
