package com.kevin.legion.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.kevin.legion.games.AndroidGame
import com.kevin.legion.games.Emulator
import com.kevin.legion.games.Game
import com.kevin.legion.games.GameSystem
import com.kevin.legion.ui.home.GamesFolderState
import com.kevin.legion.ui.home.GamesHome
import com.kevin.legion.ui.home.GamesSheetContent
import com.kevin.legion.ui.home.GamesUiState
import com.kevin.legion.ui.home.ResumeUi
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.theme.soft.SoftTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Retro-games ticket 03's screenshot step: the Games sheet with sample games (box art for some, the
 * words-on-a-flat-colour fallback for the rest, one native Android game, one emulator missing) and
 * with no folder granted. Drawn from the content composable in a sheet-shaped container, as
 * [CategorySheetsScreenshotTest] does, because a real ModalBottomSheet is its own window.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS)
class GamesSheetScreenshotTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun game(system: GameSystem, title: String, ext: String) =
        Game(system, title, "$title.$ext", "primary:ROMs/${system.folder}/$title.$ext")

    private fun solid(color: Color): ImageBitmap {
        val bitmap = ImageBitmap(200, 264)
        Canvas(bitmap).drawRect(0f, 0f, 200f, 264f, Paint().apply { this.color = color })
        return bitmap
    }

    private val harvest = game(GameSystem.PSX, "Harvest Moon: Back to Nature", "cue")
    private val flow = AndroidGame("com.flowdrive.phone", "Flow Drive")

    private val sample = GamesUiState(
        folder = GamesFolderState.Granted("/sdcard/ROMs"),
        scanned = true,
        games = listOf(
            harvest,
            game(GameSystem.GBA, "Pokemon Emerald", "gba"),
            game(GameSystem.GBA, "Metroid Zero Mission", "gba"),
            game(GameSystem.SNES, "Super Mario World", "sfc"),
            game(GameSystem.NDS, "Pokemon Platinum", "nds"),
        ),
        resume = ResumeUi(harvest, null, "2 h ago"),
        missingEmulators = setOf(Emulator.PPSSPP),
    )

    /** Box art for a few; null for the rest, so the sheet shows both a cover and the fallback. */
    /** The second shot: an emulator that is not installed, and the native Android games shelf. */
    private val androidShelf = sample.copy(
        games = listOf(game(GameSystem.PSP, "Crisis Core", "iso"), game(GameSystem.GBA, "Pokemon Emerald", "gba")),
        androidGames = listOf(flow, AndroidGame("com.example.puzzle", "Block Puzzle")),
        resume = ResumeUi(null, flow, "yesterday"),
    )

    private val covers: suspend (Game) -> ImageBitmap? = { g ->
        when (g.title) {
            "Harvest Moon: Back to Nature" -> solid(Color(0xFF3F6A3B))
            "Pokemon Emerald" -> solid(Color(0xFF1D6B5A))
            "Super Mario World" -> solid(Color(0xFF6D3A86))
            else -> null
        }
    }

    private val icons: suspend (AndroidGame) -> ImageBitmap? = { app ->
        if (app == flow) solid(Color(0xFF2F6FE0)) else null
    }

    private fun sheet(fileName: String, home: GamesHome) {
        composeTestRule.setContent {
            SoftTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFF000000))) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .fillMaxHeight(0.93f)
                            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                            .background(SoftColors.card),
                    ) { GamesSheetContent(home, onClose = {}) }
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(fileName)
    }

    @Test
    fun `games sheet - continue card and shelves`() {
        sheet("games-sheet.png", GamesHome(state = sample, cover = covers, appIcon = icons))
    }

    @Test
    fun `games sheet - Android games shelf, PSP emulator missing, Continue is a native game`() {
        sheet("games-sheet-android.png", GamesHome(state = androidShelf, cover = covers, appIcon = icons))
    }

    @Test
    fun `games sheet - no folder granted says so with a button`() {
        sheet("games-sheet-no-folder.png", GamesHome(state = GamesUiState()))
    }
}
