package com.kevin.legion.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.kevin.legion.finance.FinanceRead
import com.kevin.legion.finance.NetCashHistoryWire
import com.kevin.legion.finance.NetCashMissingWire
import com.kevin.legion.finance.NetCashPartWire
import com.kevin.legion.finance.NetCashPointWire
import com.kevin.legion.finance.NetCashText
import com.kevin.legion.finance.NetCashWire
import com.kevin.legion.ui.money.NetCashSection
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.theme.soft.SoftTheme
import com.kevin.legion.ui.typeset.OrbPhase
import com.kevin.legion.ui.typeset.TypesetFigure
import com.kevin.legion.ui.typeset.TypesetGlance
import com.kevin.legion.ui.typeset.TypesetHomeActions
import com.kevin.legion.ui.typeset.TypesetHomeContent
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Net cash (ADR 0094): the Typeset front page with the big number near the top (on the phone's
 * 1080x2340 config, the first rows still on screen), and the Money screen's working in each state
 * (normal, a missing card, a saved copy over a day old, below the floor). Invented figures only.
 * The clock and zone are fixed so no baseline rots.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NetCashScreenshotTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun shot(file: String, content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent { SoftTheme { Column(Modifier.fillMaxSize().background(SoftColors.ground)) { content() } } }
        rule.mainClock.advanceTimeBy(FRAME_MS)
        rule.onRoot().captureRoboImage(file)
    }

    @Composable
    private fun Home(cash: NetCashText.Model) {
        ShellHome {
            TypesetHomeContent(
                glance = GLANCE.copy(cash = cash),
                phase = OrbPhase.IDLE,
                status = "Tap to talk",
                night = false,
                cards = emptyList(),
                actions = TypesetHomeActions(),
            )
        }
    }

    @Composable
    private fun Money(read: FinanceRead<NetCashWire>, floor: Long? = null) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
            NetCashSection(read, floor, onSetFloor = {}, now = NOW, zone = ZoneOffset.UTC)
        }
    }

    @Test
    @Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS)
    fun `home - net cash near the top, the first rows still on screen`() {
        shot("net-cash-home.png") { Home(NetCashText.model(FinanceRead.Fresh(BODY), null, NOW, ZoneOffset.UTC)) }
        rule.onNodeWithText("As of 14:05 today").assertExists()
        rule.onNodeWithText("Study group").assertExists()
    }

    @Test
    @Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS + "-night")
    fun `home - below zero, flagged in words, night`() {
        val low = NetCashText.model(FinanceRead.Fresh(BODY.copy(netCents = -4_500)), null, NOW, ZoneOffset.UTC)
        shot("net-cash-home-below-zero-night.png") { Home(low) }
        rule.onNodeWithText("net cash, below zero").assertExists()
    }

    @Test
    @Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS)
    fun `home - a card with no balance gives no figure`() {
        shot("net-cash-home-missing.png") {
            Home(NetCashText.model(FinanceRead.Fresh(MISSING), null, NOW, ZoneOffset.UTC))
        }
        rule.onNodeWithText("No figure").assertExists()
    }

    @Test
    @Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS)
    fun `money - the working, the line and no floor`() {
        shot("net-cash-money.png") { Money(FinanceRead.Fresh(BODY)) }
        rule.onNodeWithText("Rewards Card ending 7823").assertExists()
    }

    @Test
    @Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS)
    fun `money - below the floor`() {
        shot("net-cash-money-floor.png") { Money(FinanceRead.Fresh(BODY), floor = 200_000) }
        rule.onNodeWithText("net cash, below your \$2,000 floor").assertExists()
    }

    @Test
    @Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS)
    fun `money - a missing card names the account`() {
        shot("net-cash-money-missing.png") { Money(FinanceRead.Fresh(MISSING)) }
        rule.onNodeWithText("No figure").assertExists()
    }

    @Test
    @Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS)
    fun `money - offline, last known and over a day old`() {
        val old = BODY.copy(asOf = "2026-11-15T09:12:00+00:00")
        shot("net-cash-money-saved-stale.png") {
            Money(FinanceRead.Saved(old, 0L, "Saved 5 hours ago. The engine could not be reached."))
        }
        rule.onNodeWithText("Last known, over a day old: as of 09:12 yesterday").assertExists()
    }

    private companion object {
        const val FRAME_MS = 800L
        val NOW: Instant = Instant.parse("2026-11-16T15:00:00Z")
        const val AS_OF = "2026-11-16T14:05:00+00:00"
        val BODY = NetCashWire(
            available = true,
            netCents = 140_218,
            currency = "USD",
            asOf = AS_OF,
            pendingCents = -2_150,
            parts = listOf(
                NetCashPartWire("checking", "Everyday Checking", "1000", "USD", 180_000, "bank_available", -650, 1, AS_OF),
                NetCashPartWire("card", "Rewards Card", "7823", "USD", -39_782, "current_plus_pending", -1_500, 2, AS_OF),
            ),
            history = NetCashHistoryWire(
                start = "2026-11-03",
                end = "2026-11-16",
                short = true,
                points = (3..16).map { day ->
                    NetCashPointWire("2026-11-%02d".format(day), 160_000L - day * 1_400L)
                }.dropLast(1) + NetCashPointWire("2026-11-16", 140_218),
            ),
        )
        val MISSING = NetCashWire(
            available = false,
            reason = "No net cash figure: Rewards Card ending 7823: the bank sent no balance for this card.",
            parts = BODY.parts.take(1),
            missing = listOf(NetCashMissingWire("card", "Rewards Card", "7823", "the bank sent no balance for this card")),
        )
        val GLANCE = TypesetGlance(
            dateline = "Monday, 16 November",
            time = "15:00",
            next = TypesetFigure("16:30", null, "Study group", listOf("in 1 h 30 min")),
            due = TypesetFigure("2", null, "due today", listOf("Essay draft", "Pick up the parcel")),
            left = TypesetFigure("\$1,240", null, "left for November", listOf("of \$2,000, 14 days left")),
        )
    }
}
