package com.kevin.legion.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.assertIsDisplayed
import com.github.takahirom.roborazzi.captureRoboImage
import com.kevin.legion.assistant.cards.ConversationCard
import com.kevin.legion.assistant.cards.SharedCardFixtures
import com.kevin.legion.ui.assistant.CardStageSurface
import com.kevin.legion.ui.assistant.CARD_HEADLINE_TAG
import com.kevin.legion.ui.assistant.TREND_CHART_TAG
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.typeset.TypesetPage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The three general kinds (readings, status_list, trend) on the card stage under the shell's bar,
 * day and night at 412x915, drawn from `shared/cards/fixtures.json` so the picture is of the very
 * card the engine's and the web's tests hold. Each render also asserts a word the card must say.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GeneralCardsScreenshotTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun render(file: String, card: ConversationCard, mustSay: String) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            LegionTheme {
                TypesetPage {
                    Column(Modifier.fillMaxSize()) {
                        ShellStatusBar()
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            CardStageSurface(listOf(card), onClose = {}, onOpen = {}, onCloseAll = {})
                        }
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(FRAME_MS)
        rule.onNodeWithText(mustSay, substring = true).assertIsDisplayed()
        rule.onRoot().captureRoboImage(file)
    }

    private val readings get() = SharedCardFixtures.card("readings")
    private val statuses get() = SharedCardFixtures.card("status_list")
    private val trend get() = SharedCardFixtures.card("trend")
    private val sleep get() = SharedCardFixtures.card("trend", 2)

    @Test @Config(qualifiers = A25)
    fun `readings, day`() = render("cards-readings-412x915-day.png", readings, "72.5 °C (was 90 °C)")

    @Test @Config(qualifiers = "$A25-night")
    fun `readings, night`() = render("cards-readings-412x915-night.png", readings, "not read")

    @Test @Config(qualifiers = A25)
    fun `status list, day`() {
        render("cards-status-412x915-day.png", statuses, "Answering on port")
        rule.onNodeWithTag(CARD_HEADLINE_TAG).assertIsDisplayed()
        rule.onNodeWithContentDescription("Level 2 of 4").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "$A25-night")
    fun `status list, night`() = render("cards-status-412x915-night.png", statuses, "Not reached")

    @Test @Config(qualifiers = A25)
    fun `trend, day`() {
        render("cards-trend-412x915-day.png", trend, "Morning weight: latest 182.4 lb on Nov 14")
        rule.onNodeWithTag(TREND_CHART_TAG).assertIsDisplayed()
    }

    @Test @Config(qualifiers = "$A25-night")
    fun `trend, night`() = render("cards-trend-412x915-night.png", trend, "Trend (estimate): latest 182.1 lb")

    @Test @Config(qualifiers = A25)
    fun `trend as bars, day`() {
        render("cards-trend-bars-412x915-day.png", sleep, "Target 8 h")
        rule.onNodeWithTag(TREND_CHART_TAG).assertIsDisplayed()
    }

    @Test @Config(qualifiers = "$A25-night")
    fun `trend as bars, night`() = render("cards-trend-bars-412x915-night.png", sleep, "Asleep: latest 5.9 h")

    private companion object {
        const val FRAME_MS = 800L
        const val A25 = "w412dp-h915dp"
    }
}
