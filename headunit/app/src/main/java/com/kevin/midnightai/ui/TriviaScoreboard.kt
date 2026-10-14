package com.kevin.midnightai.ui

// Ships (unlike ProtoTriviaScoreboard.kt, which stays on the throwaway proto/trivia-scoreboard
// branch). Shape/palette/motion decided in .scratch/trivia-game/issues/03-scoreboard-celebration-ui.md;
// wired to real state here instead of the prototype's fake ProtoTriviaState.
//
// Callers: CruiseScreen (top-right, amber accent) and LightsOutScreen (dim magenta accent,
// see .scratch/trivia-game/issues/03's answer for why the two palettes differ).

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevin.midnightai.service.TriviaController
import com.kevin.midnightai.ui.theme.AriaColors
import com.kevin.midnightai.ui.theme.AriaTheme
import com.kevin.midnightai.ui.theme.AriaType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

private const val CELEBRATION_DURATION_MS = 8_000L

private val LowerThirdCream = Color(0xFFF0E6CC)
private val LowerThirdInk = Color(0xFF1A1410)

/**
 * A single trivia overlay slot: renders the live scoreboard while a game is running, then a
 * short celebration once it ends, then nothing. Reads [TriviaController] directly - no state
 * is threaded in from the caller beyond the palette, since Cruise/Lights Out both just want
 * "show whatever's happening" in a corner.
 */
@Composable
fun TriviaOverlay(
    modifier: Modifier = Modifier,
    accent: Color,
    dimAccent: Color,
    panel: Color,
    textColor: Color,
) {
    val game by TriviaController.current.collectAsState()
    var celebrating by remember { mutableStateOf<TriviaController.Game?>(null) }

    LaunchedEffect(Unit) {
        TriviaController.ended.collectLatest { ended ->
            celebrating = ended
            delay(CELEBRATION_DURATION_MS)
            celebrating = null
        }
    }

    val activeGame = game
    val endedGame = celebrating
    when {
        activeGame != null -> TriviaScoreboardCard(activeGame, accent, dimAccent, panel, textColor, modifier)
        endedGame != null -> TriviaCelebrationOverlay(endedGame, accent, panel, textColor)
    }
}

@Composable
private fun TriviaScoreboardCard(
    game: TriviaController.Game,
    accent: Color,
    dimAccent: Color,
    panel: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    val glowPhase = triangle(animPhase(3_200))
    val leaderIsDriver = game.driverScore >= game.passengerScore

    Column(
        modifier = modifier
            .widthIn(max = 220.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(panel.copy(alpha = 0.88f))
            .border(1.5.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            "TRIVIA · ${game.category.uppercase()}",
            style = AriaType.neonLabel.copy(fontSize = 10.sp, letterSpacing = 1.5.sp),
            color = dimAccent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(4.dp))

        ScoreRow(game.driverName, game.driverScore, accent, dimAccent, textColor, if (leaderIsDriver) glowPhase else 0f)
        ScoreRow(game.passengerName, game.passengerScore, accent, dimAccent, textColor, if (!leaderIsDriver) glowPhase else 0f)
    }
}

@Composable
private fun ScoreRow(name: String, score: Int, accent: Color, dimAccent: Color, textColor: Color, glow: Float) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            name,
            style = AriaType.readout.copy(fontSize = 14.sp),
            color = textColor.copy(alpha = 0.75f + glow * 0.25f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 120.dp),
        )
        Text(
            score.toString(),
            style = AriaType.readout.copy(fontSize = 16.sp),
            color = if (glow > 0f) accent else dimAccent,
        )
    }
}

@Composable
private fun TriviaCelebrationOverlay(game: TriviaController.Game, accent: Color, panel: Color, textColor: Color) {
    val enter = rememberRamp(600)
    val winnerName = if (game.driverScore >= game.passengerScore) game.driverName else game.passengerName
    val winnerScore = maxOf(game.driverScore, game.passengerScore)
    val loserScore = minOf(game.driverScore, game.passengerScore)

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .widthIn(max = 240.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(panel.copy(alpha = 0.9f))
                .border(1.5.dp, accent.copy(alpha = 0.35f + 0.35f * enter), RoundedCornerShape(10.dp))
                .padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("GAME OVER", style = AriaType.neonLabel.copy(fontSize = 12.sp), color = accent.copy(alpha = 0.6f + 0.4f * enter))
            Spacer(Modifier.width(6.dp))
            Text("$winnerName wins", style = AriaType.readout.copy(fontSize = 20.sp), color = textColor)
            Text("$winnerScore — $loserScore", style = AriaType.readout.copy(fontSize = 14.sp), color = textColor.copy(alpha = 0.7f))
        }

        Column(modifier = Modifier.align(Alignment.BottomStart).padding(bottom = 40.dp)) {
            Text(
                "Good game — $winnerName takes it, $winnerScore to $loserScore.",
                style = AriaType.readout.copy(fontSize = 16.sp),
                color = LowerThirdInk,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .widthIn(max = 380.dp)
                    .clip(RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp))
                    .background(LowerThirdCream.copy(alpha = 0.92f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Preview(name = "Trivia over Cruise", widthDp = 400, heightDp = 260, backgroundColor = 0xFF0D1418)
@Composable
private fun TriviaOverCruisePreview() {
    AriaTheme {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopEnd) {
            TriviaScoreboardCard(
                game = TriviaController.Game("80s movies", 10, "Kevin", "Sam", 2, 3),
                accent = AriaColors.Amber,
                dimAccent = AriaColors.AmberDim,
                panel = AriaColors.Panel,
                textColor = AriaColors.TextPrimary,
            )
        }
    }
}
