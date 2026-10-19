package com.kevin.legion.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.kevin.legion.games.tetris.GameSnapshot
import com.kevin.legion.games.tetris.TetrisGame
import com.kevin.legion.ui.games.BoardInput
import com.kevin.legion.ui.games.TetrisActions
import com.kevin.legion.ui.games.TetrisScreen
import com.kevin.legion.ui.games.TetrisStage
import com.kevin.legion.ui.games.TetrisTheme
import com.kevin.legion.ui.games.TetrisUiState
import com.kevin.legion.ui.theme.soft.SoftTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The three Tetris themes on the A25's 384dp canvas (Kevin, 2026-10-19: black lanes you could not
 * tell the walls from). A mid-game board with some locked rows, one shot per theme, plus the
 * paused overlay and the race screen that says why there is no pause.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS)
class TetrisThemesScreenshotTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val noInput = BoardInput({}, {}, {}, {}, {})
    private val noActions = TetrisActions({}, {}, {}, {}, {}, {}, {}, {}, {})

    /** A seeded game with a few pieces dropped so the well has stacked blocks and a ghost. */
    private fun midGame(): GameSnapshot {
        val g = TetrisGame(7L)
        repeat(7) {
            g.moveHorizontal(if (it % 2 == 0) -it else it)
            g.rotate(true)
            g.hardDrop()
        }
        g.moveHorizontal(2)
        return g.snapshot()
    }

    private fun shot(file: String, state: TetrisUiState) {
        composeTestRule.setContent { SoftTheme { TetrisScreen(state, noInput, noActions) } }
        composeTestRule.onRoot().captureRoboImage(file)
    }

    private fun playing(theme: TetrisTheme, paused: Boolean = false, race: Boolean = false) = TetrisUiState(
        stage = TetrisStage.PLAYING,
        race = race,
        board = midGame(),
        theme = theme,
        paused = paused,
        opponentName = "Mia",
        opponentScore = 400,
        secondsLeft = 95,
    )

    @Test
    fun classic() = shot("tetris-classic.png", playing(TetrisTheme.CLASSIC))

    @Test
    fun neon() = shot("tetris-neon.png", playing(TetrisTheme.NEON))

    @Test
    fun soft() = shot("tetris-soft.png", playing(TetrisTheme.SOFT))

    @Test
    fun paused() = shot("tetris-paused.png", playing(TetrisTheme.NEON, paused = true))

    @Test
    fun race() = shot("tetris-race.png", playing(TetrisTheme.SOFT, race = true))
}
