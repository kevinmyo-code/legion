package com.kevin.legion.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import com.kevin.legion.assistant.cards.CardProvenance
import com.kevin.legion.assistant.cards.ChecklistLine
import com.kevin.legion.assistant.cards.ConversationCard
import com.kevin.legion.clock.AlarmMath
import com.kevin.legion.clock.Bedtime
import com.kevin.legion.clock.ClockAlarm
import com.kevin.legion.clock.ClockState
import com.kevin.legion.clock.FakeTime
import com.kevin.legion.ui.clock.ClockActions
import com.kevin.legion.ui.clock.ClockScreen
import com.kevin.legion.ui.clock.ClockUi
import com.kevin.legion.ui.theme.soft.LocalTypesetEdition
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.theme.soft.SoftTheme
import com.kevin.legion.ui.theme.soft.TypesetEdition
import com.kevin.legion.ui.theme.soft.TypesetType
import com.kevin.legion.ui.typeset.BoxBreath
import com.kevin.legion.ui.typeset.BoxBreathingContent
import com.kevin.legion.ui.typeset.ControlsActions
import com.kevin.legion.ui.typeset.ControlsPanel
import com.kevin.legion.ui.typeset.ControlsUi
import com.kevin.legion.ui.typeset.OrbPhase
import com.kevin.legion.ui.typeset.TypesetFigure
import com.kevin.legion.ui.typeset.TypesetGlance
import com.kevin.legion.ui.typeset.TypesetHomeActions
import com.kevin.legion.ui.typeset.TypesetHomeContent
import com.kevin.legion.ui.typeset.TypesetCompass
import com.kevin.legion.ui.typeset.TypesetOrb
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Review renders for the Typeset build (ADR 0081), NOT baselines: written as plain PNGs to a
 * directory outside the repo, named by the `TYPESET_SHOTS_DIR` environment variable or, since a
 * Gradle daemon keeps the environment it started with, by the one line in `~/.legion/typeset-shots.dir`.
 * Skipped when neither is set (so the normal suite never runs them and Roborazzi never records them). Covers what the
 * committed screenshot tests cannot show: the night edition (Robolectric is not-night) and the orb.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS)
class TypesetShotsTest {

    private companion object {
        val MARKER = File(System.getProperty("user.home"), ".legion/typeset-shots.dir")
        const val FRAMES_MS = 800L
    }

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val dir: File? = (
        System.getenv("TYPESET_SHOTS_DIR")
            ?: MARKER.takeIf { it.isFile }?.readText()?.trim()
        )?.takeIf { it.isNotBlank() }?.let(::File)

    private fun shot(name: String, content: @Composable () -> Unit) {
        assumeTrue("TYPESET_SHOTS_DIR unset: review renders skipped", dir != null)
        // The orb animates forever, so the clock is driven by hand: never idle otherwise.
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent(content)
        composeTestRule.mainClock.advanceTimeBy(FRAMES_MS)
        dir!!.mkdirs()
        // Roborazzi draws through Robolectric's native graphics (Compose's captureToImage waits on a
        // PixelCopy that never answers here). Record mode on an ABSOLUTE file outside the repo, so
        // nothing lands in src/test/snapshots whatever -Proborazzi.* the run was given.
        composeTestRule.onRoot().captureRoboImage(
            File(dir, name),
            roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record),
        )
    }

    @Composable
    private fun edition(e: TypesetEdition, content: @Composable () -> Unit) =
        CompositionLocalProvider(LocalTypesetEdition provides e) { SoftTheme(content) }

    private val time = FakeTime.at("2026-11-09T22:00")
    private val clockState = ClockState(
        alarms = listOf(
            ClockAlarm(1, 6, 30, AlarmMath.WEEKDAYS, "Work"),
            ClockAlarm(2, 9, 0, AlarmMath.WEEKEND, "", enabled = false),
        ),
        bedtime = Bedtime(enabled = true, hour = 22, minute = 30, active = true),
        nextId = 3,
    )

    @Test
    fun `clock alarms, night edition`() = shot("after-clock-alarms-night.png") {
        edition(TypesetEdition.NIGHT) {
            ClockScreen(ClockUi.from(clockState, time, 0, emptyList(), null, notice = null), ClockActions())
        }
    }

    @Test
    fun `orb phases, day`() = shot("after-orb-day.png") { edition(TypesetEdition.DAY) { OrbRow() } }

    @Test
    fun `orb phases, night`() = shot("after-orb-night.png") { edition(TypesetEdition.NIGHT) { OrbRow() } }

    @Test
    fun `type scale, day`() = shot("after-type-scale-day.png") {
        edition(TypesetEdition.DAY) {
            Column(Modifier.background(SoftColors.ground).padding(16.dp)) {
                Text("10:42", style = TypesetType.time, color = SoftColors.text)
                Text("\$1,240", style = TypesetType.hero, color = SoftColors.text)
                Text("3", style = TypesetType.fig, color = SoftColors.text)
                Text("Checking the calendar", style = TypesetType.lead, color = SoftColors.text)
                Text("Dentist at 3:30 PM, the clinic on Main", style = TypesetType.body, color = SoftColors.text)
                Text("Bank feed, 2 h ago", style = TypesetType.smallItalic, color = SoftColors.text2)
                Text("DUE TODAY", style = TypesetType.caps, color = SoftColors.text2)
                Text("Estimate: 4,200 mi", style = TypesetType.small, color = SoftColors.caution)
            }
        }
    }

    private val glance = TypesetGlance(
        dateline = "Monday, 9 November",
        time = "14:20",
        next = TypesetFigure("15:30", null, "Study group", listOf("in 1 h 10 min, the library")),
        due = TypesetFigure("3", null, "due today", listOf("Essay draft", "Pick up the parcel", "and 1 more")),
        left = TypesetFigure("\$1,240", null, "left for November", listOf("of \$2,000, 21 days left")),
    )

    private val card = ConversationCard.Checklist(
        title = "Due today",
        listName = "Due today",
        items = listOf(ChecklistLine("Essay draft", false), ChecklistLine("Pick up the parcel", false)),
        provenance = CardProvenance(source = "Calendar", readAtMs = System.currentTimeMillis() - 120_000),
    )

    @Test
    fun `typeset home, day`() = shot("after-typeset-home-day.png") {
        edition(TypesetEdition.DAY) { Home(cards = emptyList(), night = false) }
    }

    @Test
    fun `typeset home, night`() = shot("after-typeset-home-night.png") {
        edition(TypesetEdition.NIGHT) { Home(cards = emptyList(), night = true) }
    }

    @Test
    fun `typeset home answering, day`() = shot("after-typeset-home-answer-day.png") {
        edition(TypesetEdition.DAY) { Home(cards = listOf(card), night = false, phase = OrbPhase.SPEAKING) }
    }

    @Test
    fun `controls panel, day`() = shot("after-controls-day.png") {
        edition(TypesetEdition.DAY) {
            ControlsPanel(
                ControlsUi(
                    quietOn = false,
                    feedBlockOn = true,
                    feedNote = "6 feed apps blocked",
                    feedGateOpen = false,
                    clockNote = "Next alarm 6:30 AM",
                    claudeNote = "1 running",
                ),
                ControlsActions(),
            )
        }
    }

    @Test
    fun `box breathing, day`() = shot("after-breathe-day.png") {
        edition(TypesetEdition.DAY) { BoxBreathingContent(BoxBreath.at(2_300), reducedMotion = false, onEnd = {}) }
    }

    @Test
    fun `box breathing, night`() = shot("after-breathe-night.png") {
        edition(TypesetEdition.NIGHT) { BoxBreathingContent(BoxBreath.at(9_100), reducedMotion = false, onEnd = {}) }
    }

    @Test
    fun `box breathing, reduced motion`() = shot("after-breathe-reduced.png") {
        edition(TypesetEdition.DAY) { BoxBreathingContent(BoxBreath.at(2_300), reducedMotion = true, onEnd = {}) }
    }

    @Test
    fun `field compass, day`() = shot("after-compass-day.png") {
        edition(TypesetEdition.DAY) { CompassPage() }
    }

    @Test
    fun `field compass, night`() = shot("after-compass-night.png") {
        edition(TypesetEdition.NIGHT) { CompassPage() }
    }

    @Composable
    private fun CompassPage() {
        Column(Modifier.background(SoftColors.ground).padding(16.dp)) {
            Text("FIELD", style = TypesetType.caps, color = SoftColors.text2)
            TypesetCompass(heading = 32f, bearing = 74f, modifier = Modifier.size(320.dp))
            Text("032° heading, true north", style = TypesetType.lead, color = SoftColors.text)
            Text("Camp: 074°, 1.3 km", style = TypesetType.body, color = SoftColors.text)
        }
    }

    @Composable
    private fun Home(cards: List<ConversationCard>, night: Boolean, phase: OrbPhase = OrbPhase.IDLE) {
        Column(Modifier.background(SoftColors.ground)) {
            ShellStatusBar()
            TypesetHomeContent(
                glance = glance,
                phase = phase,
                status = if (phase == OrbPhase.SPEAKING) "Alfred is talking" else "Tap to talk",
                night = night,
                cards = cards,
                actions = TypesetHomeActions(),
            )
        }
    }

    @Composable
    private fun OrbRow() {
        Row(Modifier.background(SoftColors.ground).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            for (p in OrbPhase.entries) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    TypesetOrb(p, Modifier.size(72.dp))
                    Text(p.name.lowercase(), style = TypesetType.caps, color = SoftColors.text2)
                }
            }
        }
    }
}
