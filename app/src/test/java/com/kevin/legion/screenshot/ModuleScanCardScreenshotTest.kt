package com.kevin.legion.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.kevin.legion.assistant.cards.ConversationCard
import com.kevin.legion.ui.assistant.CardStageSurface
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.typeset.TypesetPage
import com.kevin.legion.vehicle.diag.AdapterSummary
import com.kevin.legion.vehicle.diag.BusKind
import com.kevin.legion.vehicle.diag.CodeRead
import com.kevin.legion.vehicle.diag.Coverage
import com.kevin.legion.vehicle.diag.DiagCard
import com.kevin.legion.vehicle.diag.EngineReport
import com.kevin.legion.vehicle.diag.ModuleDtc
import com.kevin.legion.vehicle.diag.ModuleReport
import com.kevin.legion.vehicle.diag.Outcome
import com.kevin.legion.vehicle.diag.ScanReport
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The module scan card (`DiagCard.scan`, a status_list card) on the card stage under the shell's
 * bar, day and night at 412x915: codes first, then modules not known to be clear, clean last; and a
 * scan that stopped. Made-up car and modules only: the repo is public.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ModuleScanCardScreenshotTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun render(name: String, night: Boolean, card: ConversationCard, mustSay: String) {
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
        rule.onRoot().captureRoboImage("cards-module-scan-$name-412x915-${if (night) "night" else "day"}.png")
    }

    /** Read two minutes before the render, as TypesetShotsTest does: the card stage ages it by the wall clock. */
    private fun report(engine: EngineReport?, modules: List<ModuleReport>, stopped: String? = null) = ScanReport(
        startedAtMs = System.currentTimeMillis() - AGE_MS - 40_000, finishedAtMs = System.currentTimeMillis() - AGE_MS,
        adapter = AdapterSummary("ELM327", "ELM327 v1.5", words = ""),
        bus = BusKind.CAN_11, engine = engine, modules = modules,
        coverage = Coverage("quick", 6, 6, ""), stopped = stopped,
    )

    private fun module(address: String, name: String, read: CodeRead) =
        ModuleReport(address, "", name, codes = read)

    private val scan get() = DiagCard.scan(
        "2004 Sample Pickup",
        report(
            EngineReport(
                CodeRead(Outcome.READ, "03", listOf(ModuleDtc("P0420"))),
                CodeRead(Outcome.READ, "07", listOf(ModuleDtc("P0171"))),
                CodeRead(Outcome.READ, "0A"),
            ),
            listOf(
                module("760", "ABS", CodeRead(Outcome.READ, "19", listOf(ModuleDtc("C1095"), ModuleDtc("C1145")))),
                module("737", "Airbag", CodeRead(Outcome.REFUSED, "19", detail = "security access denied")),
                module("726", "Body", CodeRead(Outcome.NO_ANSWER, "19")),
                module("7E1", "Transmission", CodeRead(Outcome.READ, "19")),
            ),
        ),
    )

    private val stopped get() = DiagCard.scan(
        "2004 Sample Pickup",
        report(null, emptyList(), stopped = "The car is moving. A scan runs only when it is parked."),
    )

    @Test @Config(qualifiers = A25) fun `scan day`() = render("mixed", false, scan, "C1095")
    @Test @Config(qualifiers = NIGHT) fun `scan night`() = render("mixed", true, scan, "are not listed")
    @Test @Config(qualifiers = A25) fun `stopped day`() = render("stopped", false, stopped, "The car is moving")

    private companion object {
        const val A25 = "w412dp-h915dp"
        const val NIGHT = "w412dp-h915dp-night"
        const val FRAME_MS = 800L
        const val AGE_MS = 120_000L
    }
}
