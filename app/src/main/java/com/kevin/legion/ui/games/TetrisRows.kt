@file:Suppress("FunctionNaming") // @Composable convention is PascalCase; detekt's rule does not know it.

package com.kevin.legion.ui.games

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kevin.legion.games.race.BoardWire
import com.kevin.legion.games.race.TheirBoard
import com.kevin.legion.games.tetris.GameSnapshot
import com.kevin.legion.ui.theme.soft.SoftColors

internal val SIDE_PANEL = 64.dp
private const val NEXT_SHOWN = 5
private const val RACE_NEXT_SHOWN = 3

/** The opponent's board, as a fraction of mine. */
private const val OPPONENT_SCALE = 0.4f

/**
 * The two lines above a stage: [TetrisUiState.error] (a refused tap, in the caution colour) and
 * [TetrisUiState.link] (the calm line after a sustained outage). A single failed poll is neither.
 */
@Composable
internal fun StatusLines(state: TetrisUiState) {
    state.error?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodyMedium,
            color = SoftColors.caution,
            modifier = Modifier.padding(vertical = 6.dp),
        )
    }
    state.link?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = SoftColors.text2,
            modifier = Modifier.padding(vertical = 4.dp),
        )
    }
}

/** Solo: Hold left, the board, the next five right. */
@Composable
internal fun SoloRow(state: TetrisUiState, input: BoardInput, actions: TetrisActions, cell: Dp) {
    val snapshot = state.board
    val palette = state.theme.palette
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.width(SIDE_PANEL), horizontalAlignment = Alignment.CenterHorizontally) {
            Label("HOLD")
            PiecePreview(
                snapshot?.held,
                dimmed = snapshot?.holdAvailable == false,
                palette = palette,
                modifier = Modifier.size(52.dp).background(palette.well).border(1.dp, palette.wall),
            )
        }
        OwnBoard(state, input, actions, cell)
        Column(Modifier.width(SIDE_PANEL), horizontalAlignment = Alignment.CenterHorizontally) {
            Label("NEXT")
            snapshot?.next?.take(NEXT_SHOWN)?.forEach {
                PiecePreview(
                    it, dimmed = false, palette = palette,
                    modifier = Modifier.size(36.dp).padding(vertical = 2.dp),
                )
            }
        }
    }
}

/**
 * A race (ADR 0060, amended 2026-10-19): my board large on the left; on the right the opponent's
 * name, live score and their board at [OPPONENT_SCALE] of mine in my theme, then Hold beside the
 * next three. A board with no new post for 3 s dims and says "Waiting for <name>" in words.
 */
@Composable
internal fun RaceRow(state: TetrisUiState, input: BoardInput, actions: TetrisActions, cell: Dp) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        OwnBoard(state, input, actions, cell)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    state.opponentName,
                    style = MaterialTheme.typography.labelLarge,
                    color = SoftColors.text2,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${state.opponentScore}",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = SoftColors.text,
                )
            }
            TheirBoardView(state, cell * OPPONENT_SCALE)
            HoldAndNext(state.board, state.theme.palette)
        }
    }
}

@Composable
private fun TheirBoardView(state: TetrisUiState, mini: Dp) {
    val theirCells = remember(state.opponentBoard) { BoardWire.decode(state.opponentBoard) }
    val their = state.theirBoard
    val words = when (their) {
        TheirBoard.Live -> null
        is TheirBoard.Waiting -> their.sentence
        is TheirBoard.Done -> their.sentence
    }
    val description = when (their) {
        TheirBoard.Live -> "${state.opponentName}'s board, live."
        is TheirBoard.Waiting -> "${state.opponentName}'s board, not updating. $words."
        is TheirBoard.Done -> "${state.opponentName}'s board. $words."
    }
    Box(
        Modifier
            .width(mini * (com.kevin.legion.games.tetris.Board.COLUMNS + 2 * WALL_FRAC))
            .height(mini * (VISIBLE_ROWS + 2 * WALL_FRAC))
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        OpponentBoardCanvas(
            theirCells, state.theme.palette, stale = their is TheirBoard.Waiting, modifier = Modifier.fillMaxSize(),
        )
        // Said in words over the board, never by the dimming alone.
        words?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = SoftColors.text,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(SoftColors.ground)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun HoldAndNext(snapshot: GameSnapshot?, palette: TetrisPalette) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Label("HOLD")
            PiecePreview(
                snapshot?.held,
                dimmed = snapshot?.holdAvailable == false,
                palette = palette,
                modifier = Modifier.size(40.dp).background(palette.well).border(1.dp, palette.wall),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Label("NEXT")
            snapshot?.next?.take(RACE_NEXT_SHOWN)?.forEach {
                PiecePreview(
                    it, dimmed = false, palette = palette,
                    modifier = Modifier.size(24.dp).padding(vertical = 1.dp),
                )
            }
        }
    }
}
