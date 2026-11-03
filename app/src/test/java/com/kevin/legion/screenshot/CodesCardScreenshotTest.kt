package com.kevin.legion.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import com.kevin.legion.data.local.CodeClearEvent
import com.kevin.legion.data.local.CodeEvent
import com.kevin.legion.ui.fleet.CodesCard
import com.kevin.legion.ui.fleet.codesCardState
import com.kevin.legion.ui.theme.soft.SoftTheme
import com.kevin.legion.util.TempUnit
import com.kevin.legion.vehicle.DtcMeanings
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneOffset

/**
 * The codes card (2026-11-03): codes with meanings, when, mileage, cleared or not, and the
 * selected event's freeze frame as a parameter / value / unit table; a code with no snapshot says
 * so. Tapping an event switches the snapshot. Built from [codesCardState] with fixed epochs and a
 * fixed zone, no Room, so the goldens do not drift.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS)
class CodesCardScreenshotTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val now = 1_790_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private val withFrame = CodeEvent(
        id = 1, vehicleId = "car:jeep", timestamp = now, mileage = 187_400, codesJson = """["P0420"]""",
        freezeFrameJson = """{"rpm":2112,"coolant_c":88,"speed_kmh":64,"load_pct":41,"maf_gs":12.3,""" +
            """"stft_pct":-3.1,"ltft_pct":7.8,"iat_c":31,"map_kpa":52,"fuel_sys":2}""",
    )
    private val noFrame = CodeEvent(
        id = 2, vehicleId = "car:jeep", timestamp = now - 9 * day, mileage = null, codesJson = """["P1282"]""",
        freezeFrameJson = "",
    )
    private val clear = CodeClearEvent(
        vehicleId = "car:jeep", timestamp = now - 8 * day, outcome = "CLEARED", codesBeforeJson = """["P1282"]""",
    )

    private fun stateWith(
        learned: Map<String, Pair<String, String>> = emptyMap(),
        failed: Set<String> = emptySet(),
    ) = codesCardState(
        vehicleLabel = "1998 Jeep Cherokee",
        events = listOf(noFrame, withFrame),
        clears = listOf(clear),
        seed = mapOf("P0420" to ("Catalyst efficiency below threshold, bank 1" to "")),
        learned = learned,
        tempUnit = TempUnit.CELSIUS,
        zone = ZoneOffset.UTC,
        failed = failed,
    )

    // P1282's lookup failed: the golden shows the fallback in words, not a blank.
    private val state = stateWith(failed = setOf("P1282"))

    private fun show(initialSelected: Long?, cardState: com.kevin.legion.ui.fleet.CodesCardState = state) {
        composeTestRule.setContent {
            SoftTheme {
                var selected by remember { mutableStateOf(initialSelected) }
                Box(Modifier.background(MaterialTheme.colorScheme.surface)) {
                    CodesCard(cardState, selected, onSelect = { selected = it }, onClose = {})
                }
            }
        }
    }

    @Test
    fun `newest code with its freeze frame`() {
        show(null)
        composeTestRule.onNodeWithText("Freeze frame, P0420").assertIsDisplayed()
        composeTestRule.onNodeWithText("Engine speed").assertIsDisplayed()
        composeTestRule.onNodeWithText("Closed loop").assertIsDisplayed()
        composeTestRule.onRoot().captureRoboImage("codes-card-with-snapshot.png")
    }

    @Test
    fun `tapping a code with no snapshot says so`() {
        show(null)
        composeTestRule.onNodeWithText("P1282").performClick()
        composeTestRule.onNodeWithText("Freeze frame, P1282").assertIsDisplayed()
        composeTestRule.onNodeWithText("No snapshot was saved for this code.").assertIsDisplayed()
        composeTestRule.onNodeWithText("No description yet").assertIsDisplayed()
        composeTestRule.onNodeWithText(DtcMeanings.MANUFACTURER_NOTE).assertIsDisplayed()
        val engineRows = composeTestRule.onAllNodes(androidx.compose.ui.test.hasText("Engine speed"))
        assertTrue(engineRows.fetchSemanticsNodes().isEmpty())
        composeTestRule.onRoot().captureRoboImage("codes-card-no-snapshot.png")
    }

    @Test
    fun `an unknown code says it is being looked up`() {
        show(null, stateWith())
        composeTestRule.onNodeWithText("Looking this up...").assertIsDisplayed()
    }

    @Test
    fun `an AI-written meaning is labelled in words`() {
        show(null, stateWith(learned = mapOf("P1282" to ("Fuel Pump Relay Control Circuit" to "Relay or wiring."))))
        composeTestRule.onNodeWithText("Fuel Pump Relay Control Circuit").assertIsDisplayed()
        composeTestRule.onNodeWithText("AI explanation, not from the code standard").assertIsDisplayed()
        val aiOnSeedCode = composeTestRule.onAllNodes(androidx.compose.ui.test.hasText(DtcMeanings.AI_LABEL))
        assertTrue(aiOnSeedCode.fetchSemanticsNodes().size == 1)
    }

    @Test
    fun `rows say cleared or not in words`() {
        assertTrue(state.rows[0].whenText, !state.rows[0].cleared)
        assertTrue(state.rows[1].cleared)
        show(null)
        composeTestRule.onNodeWithText("Cleared since", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Not cleared since", substring = true).assertIsDisplayed()
    }
}
