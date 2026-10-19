@file:Suppress("FunctionNaming", "MagicNumber") // PascalCase composables; the colour literals are the palette

package com.kevin.legion.ui.games

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import com.kevin.legion.games.tetris.Board
import com.kevin.legion.games.tetris.GameSnapshot
import com.kevin.legion.games.tetris.Piece

private val WELL = Color(0xFF0E0F13)
private val GRID_LINE = Color(0xFF1C1E24)

/** One colour per [Piece], in enum order I J L O S T Z. */
private val PIECE_COLORS = listOf(
    Color(0xFF4DD0E1), // I
    Color(0xFF5C7CFA), // J
    Color(0xFFFF9F43), // L
    Color(0xFFFFD43B), // O
    Color(0xFF69DB7C), // S
    Color(0xFFB197FC), // T
    Color(0xFFFF6B6B), // Z
)

private fun colorOf(piece: Piece) = PIECE_COLORS[piece.ordinal]

private const val CELL_INSET = 0.06f
private const val FLICK_CELLS = 4
private const val FLICK_MAX_MS = 250L
private const val SOFT_DROP_MIN_MS = 120L
private const val NANOS_PER_MS = 1_000_000L
private const val GHOST_ALPHA = 0.55f
private const val GHOST_STROKE = 0.08f

/**
 * The well, with the touch controls as the PWA has them (ticket 04/05): tap rotates, a horizontal
 * drag moves a cell per cell-width, a slow downward drag soft-drops, a fast downward flick
 * hard-drops. A flick is not scored as soft drops on the way: soft drop only starts once the finger
 * has been down [SOFT_DROP_MIN_MS], so a quick flick is one hard drop and nothing else.
 */
@Composable
fun TetrisBoard(snapshot: GameSnapshot?, input: BoardInput, modifier: Modifier = Modifier) {
    val current by rememberUpdatedState(input)
    Canvas(
        modifier
            .pointerInput(Unit) { detectTapGestures(onTap = { current.onRotate() }) }
            .pointerInput(Unit) {
                val cell = size.width / Board.COLUMNS.toFloat()
                var downAtNs = 0L
                var accX = 0f
                var accY = 0f
                var totalY = 0f
                detectDragGestures(
                    onDragStart = {
                        downAtNs = System.nanoTime()
                        accX = 0f
                        accY = 0f
                        totalY = 0f
                    },
                    onDragEnd = {
                        val heldMs = (System.nanoTime() - downAtNs) / NANOS_PER_MS
                        if (totalY > cell * FLICK_CELLS && heldMs < FLICK_MAX_MS) current.onHardDrop()
                    },
                    onDrag = { change, drag ->
                        change.consume()
                        accX += drag.x
                        while (accX >= cell) {
                            accX -= cell
                            current.onRight()
                        }
                        while (accX <= -cell) {
                            accX += cell
                            current.onLeft()
                        }
                        totalY += drag.y
                        val heldMs = (System.nanoTime() - downAtNs) / NANOS_PER_MS
                        if (drag.y > 0 && heldMs >= SOFT_DROP_MIN_MS) {
                            accY += drag.y
                            while (accY >= cell) {
                                accY -= cell
                                current.onSoftDrop()
                            }
                        }
                    },
                )
            },
    ) {
        val cell = size.width / Board.COLUMNS
        drawRect(WELL)
        for (col in 1 until Board.COLUMNS) {
            drawLine(GRID_LINE, Offset(col * cell, 0f), Offset(col * cell, size.height))
        }
        snapshot?.let { drawSnapshot(it, cell) }
    }
}

private fun DrawScope.drawSnapshot(s: GameSnapshot, cell: Float) {
    for (row in Board.HIDDEN_ROWS until Board.ROWS) {
        for (col in 0 until Board.COLUMNS) {
            val v = s.cells[row * Board.COLUMNS + col]
            if (v != 0) drawCell(col, row - Board.HIDDEN_ROWS, cell, PIECE_COLORS[v - 1])
        }
    }
    val active = s.active ?: return
    val tint = colorOf(active.piece)
    for (c in s.ghost) {
        if (c.y < Board.HIDDEN_ROWS) continue
        val inset = cell * CELL_INSET
        drawRect(
            tint.copy(alpha = GHOST_ALPHA),
            Offset(c.x * cell + inset, (c.y - Board.HIDDEN_ROWS) * cell + inset),
            Size(cell - 2 * inset, cell - 2 * inset),
            style = Stroke(width = cell * GHOST_STROKE),
        )
    }
    for (c in active.cells()) {
        if (c.y >= Board.HIDDEN_ROWS) drawCell(c.x, c.y - Board.HIDDEN_ROWS, cell, tint)
    }
}

private fun DrawScope.drawCell(col: Int, row: Int, cell: Float, color: Color) {
    val inset = cell * CELL_INSET
    drawRect(color, Offset(col * cell + inset, row * cell + inset), Size(cell - 2 * inset, cell - 2 * inset))
}

/** A piece drawn centred in a small box, for the hold and next panels. */
@Composable
fun PiecePreview(piece: Piece?, dimmed: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (piece == null) return@Canvas
        val cells = piece.cells(0)
        val minX = cells.minOf { it.x }
        val maxX = cells.maxOf { it.x }
        val minY = cells.minOf { it.y }
        val maxY = cells.maxOf { it.y }
        val unit = size.width / PREVIEW_SPAN
        val left = (size.width - (maxX - minX + 1) * unit) / 2f
        val top = (size.height - (maxY - minY + 1) * unit) / 2f
        val color = if (dimmed) colorOf(piece).copy(alpha = DIM_ALPHA) else colorOf(piece)
        for (c in cells) {
            val inset = unit * CELL_INSET
            drawRect(
                color,
                Offset(left + (c.x - minX) * unit + inset, top + (c.y - minY) * unit + inset),
                Size(unit - 2 * inset, unit - 2 * inset),
            )
        }
    }
}

private const val PREVIEW_SPAN = 4f
private const val DIM_ALPHA = 0.35f
