package com.kevin.legion.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import com.kevin.legion.ui.apps.DockPin
import com.kevin.legion.ui.apps.DrawerApp
import com.kevin.legion.ui.apps.HomeCategory
import com.kevin.legion.ui.home.CategoryUi
import com.kevin.legion.ui.home.DockSlotUi
import com.kevin.legion.ui.typeset.TypesetDockTile
import com.kevin.legion.ui.typeset.TypesetGroupCell
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.kevin.legion.assistant.cards.CardProvenance
import com.kevin.legion.assistant.cards.ChecklistLine
import com.kevin.legion.assistant.cards.ConversationCard
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.theme.soft.SoftTheme
import com.kevin.legion.ui.typeset.ControlsActions
import com.kevin.legion.ui.typeset.ControlsPanel
import com.kevin.legion.ui.typeset.ControlsUi
import com.kevin.legion.ui.typeset.OrbPhase
import com.kevin.legion.ui.typeset.TypesetFigure
import com.kevin.legion.ui.typeset.TypesetGlance
import com.kevin.legion.ui.typeset.TypesetHomeActions
import com.kevin.legion.ui.typeset.TypesetHomeContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Typeset front page under the shell's StatusLine ([ShellHome], as on the phone) and the
 * Controls panel (ADR 0081), committed baselines. `SoftTheme` with no
 * provided edition follows the phone's theme, so the `-night` qualifier below is what renders the
 * night edition: Robolectric's default is not-night. The orb is still, so the shot is stable.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TypesetHomeScreenshotTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun shot(file: String, content: @Composable () -> Unit) {
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            SoftTheme { Column(Modifier.fillMaxSize().background(SoftColors.ground)) { content() } }
        }
        composeTestRule.mainClock.advanceTimeBy(FRAME_MS)
        composeTestRule.onRoot().captureRoboImage(file)
    }

    @Test
    @Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS)
    fun `front page - the glance, day`() = shot("typeset-home-day.png") {
        Home(cards = emptyList(), night = false)
    }

    @Test
    @Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS + "-night")
    fun `front page - the glance, night`() = shot("typeset-home-night.png") {
        Home(cards = emptyList(), night = true)
    }

    @Test
    @Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS)
    fun `front page - answering, a card under the orb`() = shot("typeset-home-answer.png") {
        Home(cards = listOf(CARD), night = false, phase = OrbPhase.SPEAKING, status = "Alfred is talking")
    }

    /**
     * Home-ui 2026-11-17: the contents, the app groups and the pinned apps as small icons with their
     * names under them, each state shown: a group with one app wears that app's icon, one with
     * several its glyph, one with nothing picked reads in italic meta, one whose only app is paused
     * is dimmed; a paused work pin is dimmed, one with no icon yet shows its monogram.
     */
    @Test
    @Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS)
    fun `front page - icons with their names, every state, day`() = shot("typeset-home-icons-day.png") {
        Home(cards = emptyList(), night = false, full = true)
    }

    @Test
    @Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS + "-night")
    fun `front page - icons with their names, every state, night`() = shot("typeset-home-icons-night.png") {
        Home(cards = emptyList(), night = true, full = true)
    }

    /** A web app Chrome pinned to LEGION (2026-11-18) sits on the home row after the pinned apps. */
    @Test
    @Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS)
    fun `front page - a pinned web app shortcut on the home row, day`() = shot("typeset-home-shortcut-day.png") {
        Home(cards = emptyList(), night = false, full = true, dock = DOCK.take(4) + SHORTCUT)
    }

    @Test
    @Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS)
    fun `controls panel, day`() = shot("typeset-controls-day.png") { Controls() }

    @Test
    @Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS + "-night")
    fun `controls panel, night`() = shot("typeset-controls-night.png") { Controls() }

    @Composable
    private fun Home(
        cards: List<ConversationCard>,
        night: Boolean,
        phase: OrbPhase = OrbPhase.IDLE,
        status: String = "Tap to talk",
        full: Boolean = false,
        dock: List<TypesetDockTile> = DOCK,
    ) {
        ShellHome {
            if (full) {
                TypesetHomeContent(
                    glance = GLANCE,
                    phase = phase,
                    status = status,
                    night = night,
                    cards = cards,
                    actions = TypesetHomeActions(),
                    dock = dock,
                    groups = {
                        Row(Modifier.fillMaxWidth()) {
                            GROUPS.forEach { TypesetGroupCell(it, onClick = {}, onLongClick = {}) }
                        }
                    },
                )
            } else {
                TypesetHomeContent(
                    glance = GLANCE,
                    phase = phase,
                    status = status,
                    night = night,
                    cards = cards,
                    actions = TypesetHomeActions(),
                )
            }
        }
    }

    @Composable
    private fun Controls() {
        ControlsPanel(
            ControlsUi(
                quietOn = false,
                feedBlockOn = true,
                feedNote = "Feed apps blocked. Off needs the 60 s wait and the sentence.",
                feedGateOpen = false,
                clockNote = "Next alarm 06:30",
                claudeNote = "1 running",
            ),
            ControlsActions(),
        )
    }

    private companion object {
        const val FRAME_MS = 800L

        private fun icon(color: Color): ImageBitmap {
            val bitmap = ImageBitmap(48, 48)
            // A disc, not a full square: a mark is a shape on transparent, tinted with the ink.
            Canvas(bitmap).drawCircle(Offset(24f, 24f), 16f, Paint().apply { this.color = color })
            return bitmap
        }

        // Made-up apps; discs stand in for monochrome marks (real ones need a device). Whatever their
        // colour, every mark draws in the screen ink (Kevin, 2026-11-18: monochrome icons).
        private val GREEN = icon(Color(0xFF3F7D5C))
        private val BLUE = icon(Color(0xFF4A6AA8))
        private val AMBER = icon(Color(0xFFB3862A))

        private fun slot(
            label: String,
            pkg: String,
            icon: ImageBitmap?,
            paused: Boolean = false,
            work: Boolean = false,
        ) =
            DockSlotUi(
                pin = DockPin(pkg, if (work) 1 else 0),
                app = DrawerApp(label, pkg, "$pkg.Main", isWork = work, profileKey = if (work) 1 else 0),
                label = label,
                icon = icon,
                paused = paused,
            )

        val GROUPS = listOf(
            CategoryUi(HomeCategory.BANK, listOf(slot("Ledgerly", "test.ledgerly", GREEN))),
            CategoryUi(
                HomeCategory.MUSIC,
                listOf(slot("Tunes", "test.tunes", AMBER), slot("Radio", "test.radio", BLUE)),
            ),
            CategoryUi(HomeCategory.MAPS, emptyList()),
            CategoryUi(HomeCategory.MAIL, listOf(slot("Workmail", "test.workmail", BLUE, paused = true, work = true))),
            CategoryUi(HomeCategory.CHAT, emptyList()),
        )
        /** A pinned shortcut: its mark over its name, and who made it under that (review 2026-11-18). */
        val SHORTCUT = TypesetDockTile("We", "Weather board", icon = GREEN, note = "Browser", isShortcut = true)
        val DOCK = listOf(
            TypesetDockTile("Ch", "Chatter", icon = GREEN),
            TypesetDockTile("Wo", "Workmail", icon = BLUE, status = "Paused"),
            TypesetDockTile("Pi", "Pictures", icon = AMBER),
            TypesetDockTile("Tu", "Tunes"),
            TypesetDockTile("Ca", "Camera with a long name", icon = GREEN),
        )
        val GLANCE = TypesetGlance(
            dateline = "Monday, 9 November",
            time = "14:20",
            next = TypesetFigure("15:30", null, "Study group", listOf("in 1 h 10 min, the library")),
            due = TypesetFigure("3", null, "due today", listOf("Essay draft", "Pick up the parcel", "and 1 more")),
            left = TypesetFigure("\$1,240", null, "left for November", listOf("of \$2,000, 21 days left")),
        )
        val CARD = ConversationCard.Checklist(
            title = "Due today",
            listName = "Due today",
            items = listOf(ChecklistLine("Essay draft", false), ChecklistLine("Pick up the parcel", false)),
            // A fixed coverage date, never a read time: an age from the real clock would rot the baseline.
            provenance = CardProvenance(source = "Calendar", throughMs = 1_794_700_800_000L),
        )
    }
}
