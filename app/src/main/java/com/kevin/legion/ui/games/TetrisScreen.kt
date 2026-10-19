@file:Suppress("FunctionNaming") // @Composable convention is PascalCase; detekt's rule does not know it.

package com.kevin.legion.ui.games

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.kevin.legion.ui.home.GamesTone
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.theme.soft.SoftTheme

private val SIDE_PANEL = 72.dp
private val PAGE_PADDING = 12.dp
private const val SECONDS_PER_MINUTE = 60
private const val NEXT_SHOWN = 5

/** The Tetris screen's entry (retro-games ticket 06): owns the ViewModel, hands the stateless screen its state. */
@Composable
fun TetrisRoute(mode: TetrisMode, onBack: () -> Unit, viewModel: TetrisViewModel = viewModel()) {
    LaunchedEffect(mode) { viewModel.start(mode) }
    // Solo only (the ViewModel ignores it in a race): pieces must not fall while the app is away.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { viewModel.setPaused(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.setPaused(false) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val input = BoardInput(
        onLeft = viewModel::moveLeft,
        onRight = viewModel::moveRight,
        onRotate = { viewModel.rotate(clockwise = true) },
        onSoftDrop = viewModel::softDrop,
        onHardDrop = viewModel::hardDrop,
    )
    SoftTheme {
        TetrisScreen(
            state = state,
            input = input,
            actions = TetrisActions(
                onBack = onBack,
                onHold = viewModel::hold,
                onRotateBack = { viewModel.rotate(clockwise = false) },
                onAccept = viewModel::accept,
                onDecline = viewModel::decline,
                onCancel = viewModel::cancel,
                onPlayAgain = viewModel::playAgain,
            ),
        )
    }
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
    val onHold: () -> Unit,
    val onRotateBack: () -> Unit,
    val onAccept: () -> Unit,
    val onDecline: () -> Unit,
    val onCancel: () -> Unit,
    val onPlayAgain: () -> Unit,
)

@Composable
fun TetrisScreen(state: TetrisUiState, input: BoardInput, actions: TetrisActions) {
    Column(Modifier.fillMaxSize().background(SoftColors.ground).padding(PAGE_PADDING)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = actions.onBack, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Back", color = SoftColors.text)
            }
            Text(
                if (state.race) "Tetris race" else "Tetris",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = SoftColors.text,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        state.error?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = SoftColors.caution,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }
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

@Composable
private fun toneButton() = ButtonDefaults.buttonColors(
    containerColor = GamesTone.onContainer,
    contentColor = GamesTone.container,
)

private fun buttonModifier() = Modifier.heightIn(min = 48.dp).width(200.dp)

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

@Composable
private fun GameView(state: TetrisUiState, input: BoardInput, actions: TetrisActions) {
    val snapshot = state.board
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The well is 1:2. Width is whatever the side panels leave; height is what the header and
        // the control row leave. Take the smaller so it fits a short phone without scrolling.
        val byWidth: Dp = (maxWidth - SIDE_PANEL * 2 - 16.dp).coerceAtLeast(0.dp)
        val byHeight: Dp = ((maxHeight - 120.dp) / 2).coerceAtLeast(0.dp)
        val boardWidth = if (byWidth < byHeight) byWidth else byHeight
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Header(state)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.width(SIDE_PANEL), horizontalAlignment = Alignment.CenterHorizontally) {
                    Label("HOLD")
                    PiecePreview(
                        snapshot?.held,
                        dimmed = snapshot?.holdAvailable == false,
                        modifier = Modifier.size(56.dp),
                    )
                    OutlinedButton(
                        onClick = actions.onHold,
                        enabled = snapshot?.holdAvailable == true,
                        modifier = Modifier.heightIn(min = 44.dp),
                    ) { Text("Hold", color = SoftColors.text) }
                }
                Box(
                    Modifier
                        .width(boardWidth)
                        .height(boardWidth * 2)
                        .clip(RoundedCornerShape(6.dp))
                        .semantics { contentDescription = "Tetris board. Score ${snapshot?.score ?: 0}." },
                ) {
                    TetrisBoard(snapshot, input, Modifier.fillMaxSize())
                    Overlay(state)
                }
                Column(Modifier.width(SIDE_PANEL), horizontalAlignment = Alignment.CenterHorizontally) {
                    Label("NEXT")
                    snapshot?.next?.take(NEXT_SHOWN)?.forEach {
                        PiecePreview(it, dimmed = false, modifier = Modifier.size(44.dp))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                ControlButton("Left", input.onLeft)
                ControlButton("Right", input.onRight)
                ControlButton("Turn", input.onRotate)
                ControlButton("Turn back", actions.onRotateBack)
                ControlButton("Drop", input.onHardDrop)
            }
        }
    }
}

@Composable
private fun ControlButton(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(label, color = SoftColors.text, fontSize = 13.sp)
    }
}

@Composable
private fun Label(text: String) {
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
            val toppedOut = if (state.opponentToppedOut) " (topped out)" else ""
            Text(
                "You $mine   ${state.opponentName} ${state.opponentScore}$toppedOut",
                style = MaterialTheme.typography.titleMedium,
                color = SoftColors.text,
            )
            Text(clock(state.secondsLeft), style = MaterialTheme.typography.bodyMedium, color = SoftColors.text2)
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

/** The big number or sentence over the well while the board is not live. */
@Composable
private fun Overlay(state: TetrisUiState) {
    if (state.stage == TetrisStage.PLAYING) return
    Box(
        Modifier.fillMaxSize().background(SoftColors.ground.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            state.message,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = SoftColors.text,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(16.dp),
        )
    }
}

private fun clock(seconds: Int): String = "%d:%02d".format(seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE)
