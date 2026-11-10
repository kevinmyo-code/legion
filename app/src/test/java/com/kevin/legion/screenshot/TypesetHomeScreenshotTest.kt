package com.kevin.legion.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
 * The Typeset front page and Controls panel (ADR 0081), committed baselines. `SoftTheme` with no
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

    @Test
    @Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS)
    fun `front page - off-normal words beside Controls`() = shot("typeset-home-status-words.png") {
        Home(cards = emptyList(), night = false, statusWords = "Not synced, Quiet, 2 alarms")
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
        statusWords: String? = null,
    ) {
        TypesetHomeContent(
            glance = GLANCE,
            phase = phase,
            status = status,
            statusWords = statusWords,
            night = night,
            cards = cards,
            actions = TypesetHomeActions(),
        )
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
