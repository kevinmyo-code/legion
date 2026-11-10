package com.kevin.legion.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import com.kevin.legion.assistant.cards.ConversationCard
import com.kevin.legion.service.Phase
import com.kevin.legion.ui.assistant.AssistantStripContent
import com.kevin.legion.ui.assistant.AssistantStripResolver
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.typeset.OrbPhase
import com.kevin.legion.ui.typeset.TypesetDockTile
import com.kevin.legion.ui.typeset.TypesetHomeActions
import com.kevin.legion.ui.typeset.TypesetHomeContent
import com.kevin.legion.ui.typeset.TypesetPage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * BEFORE: every surface an answer card appears on, as drawn at dev d3991d4f, with one card and with
 * three, day and night, at 360x640 font 1.3 and 412x915. Kevin, 2026-11-10: "the genui cards does
 * not fill the screen ... right now it pops up a short window". The home's answering state and the
 * talk strip on any other route. Renders only, no assertions: this commit exists to be the before.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CardSurfacesScreenshotTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun render(file: String, fontScale: Float, content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                LegionTheme { content() }
            }
        }
        rule.mainClock.advanceTimeBy(FRAME_MS)
        rule.onRoot().captureRoboImage(file)
    }

    private fun home(file: String, cards: List<ConversationCard>, fontScale: Float) = render(file, fontScale) {
        ShellHome {
            TypesetPage {
                TypesetHomeContent(
                    glance = CardFixtures.GLANCE,
                    phase = OrbPhase.SPEAKING,
                    status = "Speaking…",
                    night = isSystemInDarkTheme(),
                    cards = cards,
                    actions = TypesetHomeActions(),
                    dock = DOCK,
                    groups = { Row(Modifier.fillMaxWidth()) {} },
                )
            }
        }
    }

    private fun route(file: String, cards: List<ConversationCard>, fontScale: Float) = render(file, fontScale) {
        TypesetPage {
            Column(Modifier.fillMaxSize()) {
                ShellStatusBar()
                Box(Modifier.weight(1f).fillMaxWidth()) { FakeRoute() }
                AssistantStripContent(
                    AssistantStripResolver.resolve(
                        Phase.SPEAKING, CardFixtures.CAPTION, null, micGranted = true, silenced = false,
                    ),
                    onTap = {},
                    card = cards.lastOrNull(),
                )
            }
        }
    }

    // Home, 360x640 at font 1.3
    @Test @Config(qualifiers = SMALL)
    fun `home, one card, small, day`() = home("cards-home-1-360x640-font13-day.png", CardFixtures.ONE, BIG_FONT)

    @Test @Config(qualifiers = "$SMALL-night")
    fun `home, one card, small, night`() = home("cards-home-1-360x640-font13-night.png", CardFixtures.ONE, BIG_FONT)

    @Test @Config(qualifiers = SMALL)
    fun `home, three cards, small, day`() =
        home("cards-home-3-360x640-font13-day.png", CardFixtures.THREE, BIG_FONT)

    @Test @Config(qualifiers = "$SMALL-night")
    fun `home, three cards, small, night`() =
        home("cards-home-3-360x640-font13-night.png", CardFixtures.THREE, BIG_FONT)

    // Home, 412x915
    @Test @Config(qualifiers = A25)
    fun `home, one card, a25, day`() = home("cards-home-1-412x915-day.png", CardFixtures.ONE, 1f)

    @Test @Config(qualifiers = "$A25-night")
    fun `home, one card, a25, night`() = home("cards-home-1-412x915-night.png", CardFixtures.ONE, 1f)

    @Test @Config(qualifiers = A25)
    fun `home, three cards, a25, day`() = home("cards-home-3-412x915-day.png", CardFixtures.THREE, 1f)

    @Test @Config(qualifiers = "$A25-night")
    fun `home, three cards, a25, night`() = home("cards-home-3-412x915-night.png", CardFixtures.THREE, 1f)

    // Another route, 360x640 at font 1.3
    @Test @Config(qualifiers = SMALL)
    fun `route, one card, small, day`() = route("cards-route-1-360x640-font13-day.png", CardFixtures.ONE, BIG_FONT)

    @Test @Config(qualifiers = "$SMALL-night")
    fun `route, one card, small, night`() =
        route("cards-route-1-360x640-font13-night.png", CardFixtures.ONE, BIG_FONT)

    @Test @Config(qualifiers = SMALL)
    fun `route, three cards, small, day`() =
        route("cards-route-3-360x640-font13-day.png", CardFixtures.THREE, BIG_FONT)

    @Test @Config(qualifiers = "$SMALL-night")
    fun `route, three cards, small, night`() =
        route("cards-route-3-360x640-font13-night.png", CardFixtures.THREE, BIG_FONT)

    // Another route, 412x915
    @Test @Config(qualifiers = A25)
    fun `route, one card, a25, day`() = route("cards-route-1-412x915-day.png", CardFixtures.ONE, 1f)

    @Test @Config(qualifiers = "$A25-night")
    fun `route, one card, a25, night`() = route("cards-route-1-412x915-night.png", CardFixtures.ONE, 1f)

    @Test @Config(qualifiers = A25)
    fun `route, three cards, a25, day`() = route("cards-route-3-412x915-day.png", CardFixtures.THREE, 1f)

    @Test @Config(qualifiers = "$A25-night")
    fun `route, three cards, a25, night`() = route("cards-route-3-412x915-night.png", CardFixtures.THREE, 1f)

    private companion object {
        const val FRAME_MS = 800L
        const val BIG_FONT = 1.3f
        const val SMALL = "w360dp-h640dp"
        const val A25 = "w412dp-h915dp"
        val DOCK = listOf(
            TypesetDockTile("Ch", "Chatter"),
            TypesetDockTile("Ma", "Mailbox"),
            TypesetDockTile("Pi", "Pictures"),
            TypesetDockTile("Tu", "Tunes"),
            TypesetDockTile("Ca", "Camera"),
        )
    }
}
