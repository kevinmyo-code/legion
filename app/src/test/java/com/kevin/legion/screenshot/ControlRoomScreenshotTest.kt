package com.kevin.legion.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.kevin.legion.ui.controlroom.ActionCardUi
import com.kevin.legion.ui.controlroom.BacklogRowUi
import com.kevin.legion.ui.controlroom.BoardRowUi
import com.kevin.legion.ui.controlroom.BoardSectionUi
import com.kevin.legion.ui.controlroom.ControlRoomActions
import com.kevin.legion.ui.controlroom.ControlRoomContent
import com.kevin.legion.ui.controlroom.ControlRoomUiState
import com.kevin.legion.ui.controlroom.ControlTab
import com.kevin.legion.ui.controlroom.DecisionUi
import com.kevin.legion.ui.controlroom.SessionUi
import com.kevin.legion.ui.controlroom.TaskCardUi
import com.kevin.legion.ui.theme.soft.SoftTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The control room (control room C1-C4) in the day and night editions, one shot per tab. Made-up
 * sessions, cards and ideas only: the repo is public.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ControlRoomScreenshotTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun shot(tab: ControlTab, file: String, expect: String) {
        rule.mainClock.autoAdvance = false
        rule.setContent { SoftTheme { ControlRoomContent(STATE.copy(tab = tab), ControlRoomActions()) } }
        rule.mainClock.advanceTimeBy(FRAME_MS)
        rule.onNodeWithText(expect).assertIsDisplayed()
        rule.onRoot().captureRoboImage(file)
    }

    @Test
    @Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS)
    fun `sessions, day`() = shot(ControlTab.SESSIONS, "control-room-sessions-day.png", "planner")

    @Test
    @Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS + "-night")
    fun `sessions, night`() = shot(ControlTab.SESSIONS, "control-room-sessions-night.png", "planner")

    @Test
    @Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS)
    fun `approvals, day`() = shot(ControlTab.APPROVALS, "control-room-approvals-day.png", "Deploy the engine at 1a2b3c4d")

    @Test
    @Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS + "-night")
    fun `approvals, night`() = shot(ControlTab.APPROVALS, "control-room-approvals-night.png", "Deploy the engine at 1a2b3c4d")

    @Test
    @Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS)
    fun `board, day`() = shot(ControlTab.BOARD, "control-room-board-day.png", "A quieter kettle alarm")

    @Test
    @Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS + "-night")
    fun `board, night`() = shot(ControlTab.BOARD, "control-room-board-night.png", "A quieter kettle alarm")

    @Test
    @Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS)
    fun `decisions, day`() = shot(ControlTab.DECISIONS, "control-room-decisions-day.png", "Pick a teapot")

    @Test
    @Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS + "-night")
    fun `decisions, night`() = shot(ControlTab.DECISIONS, "control-room-decisions-night.png", "Pick a teapot")

    private companion object {
        const val FRAME_MS = 500L
        val STATE = ControlRoomUiState(
            loading = false,
            sessions = listOf(
                SessionUi("s-1", "planner", "garden", "Alienware", "Waiting on you", true, "waiting 12 min", true),
                SessionUi("s-2", "notes", "kitchen", "work laptop", "Busy", false, "", false),
            ),
            tasks = listOf(TaskCardUi(7, "Alienware", "Send this message to your Claude session planner: \"water the beans\"")),
            actions = listOf(
                ActionCardUi(
                    "rec-1", "b".repeat(64), "Deploy the engine at 1a2b3c4d",
                    listOf("Commit 1a2b3c4d, RED tier", "Review confirmed this commit", "Every check green",
                        "Expires 2031-04-02 10:30 UTC"),
                    "Waiting on you", open = true,
                ),
            ),
            board = BoardSectionUi(
                freshness = "Live: sent just now.",
                inFlight = listOf(BoardRowUi("Scone recipe", "Reading notes", false)),
                testing = listOf(BoardRowUi("Kettle firmware", "CI run on 1a2b3c4d", false)),
                doneToday = listOf(BoardRowUi("Bread proofed", "", false)),
                backlog = listOf(
                    BacklogRowUi("idea-000000000a", "A quieter kettle alarm", "RICE 12.0, YELLOW", "p1"),
                    BacklogRowUi("idea-000000000b", "Tea timer on the watch", "Not scored, tier unknown", ""),
                ),
                backlogMore = "Showing 2 of 5 ideas.",
            ),
            decisions = listOf(
                DecisionUi("teapot", "Pick a teapot", "Blue or green?", listOf("blue", "green"), true, "a".repeat(64),
                    chatOnly = false, pending = false),
                DecisionUi("kettle", "Kettle firmware", "Ship it?", listOf("yes", "no"), false, "c".repeat(64),
                    chatOnly = true, pending = false),
            ),
        )
    }
}
