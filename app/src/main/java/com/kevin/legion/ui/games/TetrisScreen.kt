@file:Suppress("FunctionNaming") // @Composable convention is PascalCase; detekt's rule does not know it.

package com.kevin.legion.ui.games

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kevin.legion.games.tetris.Board
import com.kevin.legion.ui.home.GamesTone
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.theme.soft.SoftTheme

private val PAGE_PADDING = 12.dp
private const val SECONDS_PER_MINUTE = 60
/** The least the race's right-hand column (their board, hold, next) is left. */
private val RACE_COLUMN_MIN = 120.dp

/** The Tetris screen's entry (retro-games ticket 06): owns the ViewModel, hands the stateless screen its state. */
@Composable
fun TetrisRoute(mode: TetrisMode, onBack: () -> Unit, viewModel: TetrisViewModel = viewModel()) {
    LaunchedEffect(mode) { viewModel.start(mode) }
    // Solo only (the ViewModel ignores it in a race): pieces must not fall while the app is away.
    // ON_PAUSE is the earlier, broader signal (a pop-up window, a dialog); ON_STOP is the backgrounding.
    // Neither un-pauses on return: the user presses Resume.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { viewModel.autoPause() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.autoPause() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val input = BoardInput(
        onLeft = viewModel::moveLeft,
        onRight = viewModel::moveRight,
        onRotate = { viewModel.rotate(clockwise = true) },
        onSoftDrop = viewModel::softDrop,
        onHardDrop = viewModel::hardDrop,
    )
    val actions = TetrisActions(
        onBack = onBack,
        onAccept = viewModel::accept,
        onDecline = viewModel::decline,
        onCancel = viewModel::cancel,
        onPlayAgain = viewModel::playAgain,
        onResume = viewModel::resume,
        onTheme = viewModel::setTheme,
        onPadDown = viewModel::padDown,
        onPadUp = viewModel::padUp,
    )
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    SoftTheme {
        Box(
            Modifier
                .fillMaxSize()
                .focusRequester(focus)
                .focusable()
                .onPreviewKeyEvent { handlePadKey(it, actions) },
        ) {
            TetrisScreen(state = state, input = input, actions = actions)
        }
    }
}

/**
 * A physical controller or keyboard key becomes a pad press. The OS's own key repeat is ignored:
 * the ViewModel repeats at the DAS and ARR rates the on-screen pad uses, so both feel the same.
 * Returns false for keys that are not ours so Back and volume still work.
 */
private fun handlePadKey(event: KeyEvent, actions: TetrisActions): Boolean {
    val button = padButtonForKey(event.nativeKeyEvent.keyCode) ?: return false
    when (event.type) {
        KeyEventType.KeyDown -> if (event.nativeKeyEvent.repeatCount == 0) actions.onPadDown(button)
        KeyEventType.KeyUp -> actions.onPadUp(button)
        else -> Unit
    }
    return true
}

/** What the board's gestures ask the game to do. */
class BoardInput(
    val onLeft: () -> Unit,
    val onRight: () -> Unit,
    val onRotate: () -> Unit,
    val onSoftDrop: () -> Unit,
    val onHardDrop: () -> Unit,
)

class TetrisActions(
    val onBack: () -> Unit,
    val onAccept: () -> Unit,
    val onDecline: () -> Unit,
    val onCancel: () -> Unit,
    val onPlayAgain: () -> Unit,
    val onResume: () -> Unit,
    val onTheme: (TetrisTheme) -> Unit,
    val onPadDown: (PadButton) -> Unit,
    val onPadUp: (PadButton) -> Unit,
)

@Composable
fun TetrisScreen(state: TetrisUiState, input: BoardInput, actions: TetrisActions) {
    Column(Modifier.fillMaxSize().background(SoftColors.ground).padding(PAGE_PADDING)) {
        TopBar(state, actions)
        StatusLines(state)
        when (state.stage) {
            TetrisStage.STARTING -> Panel(state.message, busy = true) {}
            TetrisStage.INCOMING -> Panel(state.message) {
                Button(
                    onClick = actions.onAccept,
                    enabled = !state.busy,
                    colors = toneButton(),
                    modifier = buttonModifier(),
                ) { Text("Accept", fontWeight = FontWeight.Bold) }
                OutlinedButton(onClick = actions.onDecline, enabled = !state.busy, modifier = buttonModifier()) {
                    Text("Decline", color = SoftColors.text)
                }
            }
            TetrisStage.WAITING -> Panel(state.message, busy = true) {
                state.inviteNote?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = SoftColors.text2,
                        textAlign = TextAlign.Center,
                    )
                }
                OutlinedButton(onClick = actions.onCancel, enabled = !state.busy, modifier = buttonModifier()) {
                    Text("Cancel invite", color = SoftColors.text)
                }
            }
            TetrisStage.CLOSED -> Panel(state.message) {
                Button(onClick = actions.onBack, colors = toneButton(), modifier = buttonModifier()) { Text("Back") }
            }
            TetrisStage.RESULT -> Panel(state.message) {
                Text(
                    "You ${state.board?.score ?: 0}   ${state.opponentName} ${state.opponentScore}",
                    style = MaterialTheme.typography.titleMedium,
                    color = SoftColors.text2,
                )
                Button(onClick = actions.onBack, colors = toneButton(), modifier = buttonModifier()) { Text("Back") }
            }
            TetrisStage.SOLO_OVER -> Panel(state.message) {
                Button(onClick = actions.onPlayAgain, colors = toneButton(), modifier = buttonModifier()) {
                    Text("Play again", fontWeight = FontWeight.Bold)
                }
            }
            TetrisStage.COUNTDOWN, TetrisStage.PLAYING, TetrisStage.FINISHING -> GameView(state, input, actions)
        }
    }
}

private fun buttonModifier() = Modifier.heightIn(min = 48.dp).width(200.dp)

@Composable
private fun TopBar(state: TetrisUiState, actions: TetrisActions) {
    val live = state.stage == TetrisStage.COUNTDOWN || state.stage == TetrisStage.PLAYING ||
        state.stage == TetrisStage.FINISHING
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = actions.onBack, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Back", color = SoftColors.text)
        }
        if (live) {
            ThemeChips(state.theme, actions.onTheme, Modifier.weight(1f))
        } else {
            Text(
                if (state.race) "Tetris race" else "Tetris",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = SoftColors.text,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun Panel(message: String, busy: Boolean = false, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.titleMedium,
            color = SoftColors.text,
            textAlign = TextAlign.Center,
        )
        if (busy) CircularProgressIndicator(color = GamesTone.onContainer)
        content()
    }
}

// Heights the board must leave room for: score header, race note, pad (Hold row + D-pad), gaps.
private val HEADER_H = 64.dp
private val RACE_NOTE_H = 20.dp
private val PAD_H = 48.dp + 4.dp + 156.dp
private val GAPS_H = 16.dp
private const val WALL_CELLS = 2 * WALL_FRAC

@Composable
private fun GameView(state: TetrisUiState, input: BoardInput, actions: TetrisActions) {
    val snapshot = state.board
    val palette = state.theme.palette
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The canvas is the well plus its wall band: (10 + 0.8) cells by (20 + 0.8). Width is what
        // the side panels (solo) or the opponent's column (race) leave, height is what the header,
        // race note and pad leave. Take the smaller cell so it fits a short phone without scrolling.
        val note = if (state.race) RACE_NOTE_H else 0.dp
        val besides = if (state.race) RACE_COLUMN_MIN + 8.dp else SIDE_PANEL * 2 + 16.dp
        val byWidth: Dp = (maxWidth - besides).coerceAtLeast(0.dp) / (Board.COLUMNS + WALL_CELLS)
        val byHeight: Dp =
            (maxHeight - HEADER_H - note - PAD_H - GAPS_H).coerceAtLeast(0.dp) / (VISIBLE_ROWS + WALL_CELLS)
        val cell = if (byWidth < byHeight) byWidth else byHeight
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Header(state)
            if (state.race) {
                Text(RACE_NO_PAUSE, style = MaterialTheme.typography.bodySmall, color = SoftColors.text3)
            }
            if (state.race) {
                RaceRow(state, input, actions, cell)
            } else {
                SoloRow(state, input, actions, cell)
            }
            Spacer(Modifier.weight(1f))
            TetrisGamepad(
                startEnabled = !state.race && state.stage == TetrisStage.PLAYING,
                holdEnabled = snapshot?.holdAvailable == true,
                onDown = actions.onPadDown,
                onUp = actions.onPadUp,
            )
        }
    }
}

@Composable
internal fun OwnBoard(state: TetrisUiState, input: BoardInput, actions: TetrisActions, cell: Dp) {
    val snapshot = state.board
    Box(
        Modifier
            .width(cell * (Board.COLUMNS + WALL_CELLS))
            .height(cell * (VISIBLE_ROWS + WALL_CELLS))
            .semantics { contentDescription = "Tetris board. Score ${snapshot?.score ?: 0}." },
    ) {
        TetrisBoard(snapshot, state.theme.palette, input, Modifier.fillMaxSize())
        Overlay(state, actions)
    }
}

@Composable
internal fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = SoftColors.text3,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun Header(state: TetrisUiState) {
    val snapshot = state.board
    val mine = snapshot?.score ?: 0
    Column(Modifier.padding(bottom = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (state.race) {
            Text(
                "You $mine   ${clock(state.secondsLeft)}",
                style = MaterialTheme.typography.titleMedium,
                color = SoftColors.text,
            )
        } else {
            Text("Score $mine", style = MaterialTheme.typography.titleMedium, color = SoftColors.text)
        }
        Text(
            "Level ${snapshot?.level ?: 1}   Lines ${snapshot?.lines ?: 0}",
            style = MaterialTheme.typography.bodySmall,
            color = SoftColors.text3,
        )
    }
}

private fun clock(seconds: Int): String = "%d:%02d".format(seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE)
