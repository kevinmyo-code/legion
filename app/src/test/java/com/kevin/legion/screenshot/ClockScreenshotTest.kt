package com.kevin.legion.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.kevin.legion.clock.AlarmMath
import com.kevin.legion.clock.Bedtime
import com.kevin.legion.clock.ClockAlarm
import com.kevin.legion.clock.ClockState
import com.kevin.legion.clock.ClockTimer
import com.kevin.legion.clock.FakeTime
import com.kevin.legion.clock.RingKind
import com.kevin.legion.clock.RingRequest
import com.kevin.legion.clock.Stopwatch
import com.kevin.legion.clock.TimerState
import com.kevin.legion.ui.clock.ClockActions
import com.kevin.legion.ui.clock.ClockScreen
import com.kevin.legion.ui.clock.ClockUi
import com.kevin.legion.ui.clock.ProblemRow
import com.kevin.legion.ui.clock.RingActions
import com.kevin.legion.ui.clock.RingScreen
import com.kevin.legion.ui.theme.soft.SoftTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/** alfred-clock ticket 05: each Clock tab, a problem banner, and the ring screen for an alarm and a timer. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS)
class ClockScreenshotTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val time = FakeTime.at("2026-11-09T22:00")

    private val state = ClockState(
        alarms = listOf(
            ClockAlarm(1, 6, 30, AlarmMath.WEEKDAYS, "Work"),
            ClockAlarm(2, 9, 0, AlarmMath.WEEKEND, "", enabled = false),
            ClockAlarm(3, 7, 15, emptySet(), "Flight", date = LocalDate.parse("2026-11-10")),
        ),
        timers = listOf(
            ClockTimer(4, "pasta", 600_000, TimerState.RUNNING, 600_000, endsAtElapsed = time.elapsed + 252_000, bootCount = 1),
            ClockTimer(5, "laundry", 3_600_000, TimerState.PAUSED, 2_065_000),
        ),
        stopwatch = Stopwatch(running = false, accumulatedMs = 95_400, laps = listOf(65_000L, 95_000L)),
        bedtime = Bedtime(enabled = true, hour = 22, minute = 30, active = false),
        nextId = 6,
    )

    private fun clock(tab: Int, file: String, problems: List<ProblemRow> = emptyList(), dnd: ProblemRow? = null) {
        val ui = ClockUi.from(state, time, tab, problems, dnd, notice = null)
        composeTestRule.setContent { SoftTheme { ClockScreen(ui, ClockActions()) } }
        composeTestRule.onRoot().captureRoboImage(file)
    }

    @Test
    fun `clock - alarms`() = clock(0, "clock-alarms.png")

    @Test
    fun `clock - alarms with a battery problem`() = clock(
        0,
        "clock-alarms-battery.png",
        problems = listOf(
            ProblemRow("Battery optimisation is on for LEGION. Samsung may put it to sleep and an alarm may not ring.", "Exempt LEGION", "h0"),
        ),
    )

    @Test
    fun `clock - timers`() = clock(1, "clock-timers.png")

    @Test
    fun `clock - stopwatch`() = clock(2, "clock-stopwatch.png")

    @Test
    fun `clock - bedtime without DND access`() = clock(
        3,
        "clock-bedtime.png",
        dnd = ProblemRow("Bedtime turns Quiet on, which needs Do Not Disturb access for LEGION. Without it only the feed block comes on.", "Grant access", "dnd"),
    )

    @Test
    fun `ring - alarm with Brief me`() {
        composeTestRule.setContent {
            SoftTheme { RingScreen("07:00", RingRequest(RingKind.ALARM, 1, "Work"), canBrief = true, problem = null, actions = RingActions()) }
        }
        composeTestRule.onRoot().captureRoboImage("clock-ring-alarm.png")
    }

    @Test
    fun `ring - timer done`() {
        composeTestRule.setContent {
            SoftTheme { RingScreen("18:42", RingRequest(RingKind.TIMER, 4, "pasta"), canBrief = true, problem = null, actions = RingActions()) }
        }
        composeTestRule.onRoot().captureRoboImage("clock-ring-timer.png")
    }
}
