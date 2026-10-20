@file:Suppress("FunctionNaming", "MagicNumber") // PascalCase composables; the fractions are drawing proportions

package com.kevin.legion.ui.games

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import com.kevin.legion.games.tetris.Board
import com.kevin.legion.games.tetris.GameSnapshot
import com.kevin.legion.games.tetris.Piece

/**
 * The wall band is this fraction of a cell on the left, right, top and bottom, so the canvas is
 * `COLUMNS + 2 * WALL_FRAC` cells wide and `VISIBLE_ROWS + 2 * WALL_FRAC` cells tall.
 */
const val WALL_FRAC = 0.4f
const val VISIBLE_ROWS = Board.ROWS - Board.HIDDEN_ROWS

private fun cellOf(widthPx: Float) = widthPx / (Board.COLUMNS + 2 * WALL_FRAC)

private const val CELL_INSET = 0.06f
private const val FLICK_CELLS = 4
private const val FLICK_MAX_MS = 250L
private const val SOFT_DROP_MIN_MS = 120L
private const val NANOS_PER_MS = 1_000_000L
private const val GHOST_ALPHA = 0.9f
private const val GHOST_FILL_ALPHA = 0.18f
private const val GHOST_STROKE = 0.1f

/**
 * The well, with the touch controls as the PWA has them (ticket 04/05): tap rotates, a horizontal
 * drag moves a cell per cell-width, a slow downward drag soft-drops, a fast downward flick
 * hard-drops. A flick is not scored as soft drops on the way: soft drop only starts once the finger
 * has been down [SOFT_DROP_MIN_MS], so a quick flick is one hard drop and nothing else.
 */
@Composable
fun TetrisBoard(snapshot: GameSnapshot?, palette: TetrisPalette, input: BoardInput, modifier: Modifier = Modifier) {
    val current by rememberUpdatedState(input)
    Canvas(
        modifier
            .pointerInput(Unit) { detectTapGestures(onTap = { current.onRotate() }) }
            .pointerInput(Unit) {
                val cell = cellOf(size.width.toFloat())
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
        val cell = cellOf(size.width)
        drawWell(palette, cell)
        snapshot?.let { drawSnapshot(it, palette, cell) }
    }
}

/** Wall band first, then the well inside it, its edge line and faint grid so columns and rows read. */
private fun DrawScope.drawWell(p: TetrisPalette, cell: Float) {
    val wall = cell * WALL_FRAC
    drawRect(p.wall)
    val wellSize = Size(cell * Board.COLUMNS, cell * VISIBLE_ROWS)
    drawRect(p.well, Offset(wall, wall), wellSize)
    for (col in 1 until Board.COLUMNS) {
        drawLine(p.grid, Offset(wall + col * cell, wall), Offset(wall + col * cell, wall + wellSize.height))
    }
    for (row in 1 until VISIBLE_ROWS) {
        drawLine(p.grid, Offset(wall, wall + row * cell), Offset(wall + wellSize.width, wall + row * cell))
    }
    val edge = (wall * 0.35f).coerceAtLeast(2f)
    drawRect(p.wallEdge, Offset(wall - edge / 2, wall - edge / 2), Size(wellSize.width + edge, wellSize.height + edge),
        style = Stroke(width = edge))
}

private fun DrawScope.drawSnapshot(s: GameSnapshot, p: TetrisPalette, cell: Float) {
    val origin = cell * WALL_FRAC
    for (row in Board.HIDDEN_ROWS until Board.ROWS) {
        for (col in 0 until Board.COLUMNS) {
            val v = s.cells[row * Board.COLUMNS + col]
            if (v == 0) continue
            drawBlock(p, p.pieces[v - 1], origin + col * cell, origin + (row - Board.HIDDEN_ROWS) * cell, cell)
        }
    }
    val active = s.active ?: return
    val tint = p.colorOf(active.piece)
    val ghostColor = p.edgeOf(tint)
    for (c in s.ghost) {
        if (c.y < Board.HIDDEN_ROWS) continue
        val inset = cell * CELL_INSET
        val topLeft = Offset(origin + c.x * cell + inset, origin + (c.y - Board.HIDDEN_ROWS) * cell + inset)
        val box = Size(cell - 2 * inset, cell - 2 * inset)
        drawRect(ghostColor.copy(alpha = GHOST_FILL_ALPHA), topLeft, box)
        drawRect(ghostColor.copy(alpha = GHOST_ALPHA), topLeft, box, style = Stroke(width = cell * GHOST_STROKE))
    }
    for (c in active.cells()) {
        if (c.y >= Board.HIDDEN_ROWS) {
            drawBlock(p, tint, origin + c.x * cell, origin + (c.y - Board.HIDDEN_ROWS) * cell, cell)
        }
    }
}

/** One block in the theme's style, with its cell's top-left at ([x], [y]). */
private fun DrawScope.drawBlock(p: TetrisPalette, color: Color, x: Float, y: Float, cell: Float) {
    val inset = cell * CELL_INSET
    val topLeft = Offset(x + inset, y + inset)
    val side = cell - 2 * inset
    val box = Size(side, side)
    when (p.style) {
        BlockStyle.BEVEL -> {
            // Hard-edged strips, no anti-aliased gradient: the Game Boy look.
            val bevel = side * 0.18f
            drawRect(color, topLeft, box)
            drawRect(lerp(color, Color.White, 0.45f), topLeft, Size(side, bevel))
            drawRect(lerp(color, Color.White, 0.45f), topLeft, Size(bevel, side))
            drawRect(lerp(color, Color.Black, 0.4f), Offset(topLeft.x, topLeft.y + side - bevel), Size(side, bevel))
            drawRect(lerp(color, Color.Black, 0.4f), Offset(topLeft.x + side - bevel, topLeft.y), Size(bevel, side))
        }
        BlockStyle.GLOW -> {
            val glow = cell * 0.12f
            val halo = Size(side + 2 * glow, side + 2 * glow)
            drawRect(color.copy(alpha = 0.22f), Offset(topLeft.x - glow, topLeft.y - glow), halo)
            drawRect(color.copy(alpha = 0.85f), topLeft, box)
            drawRect(lerp(color, Color.White, 0.55f), topLeft, box, style = Stroke(width = cell * 0.07f))
        }
        BlockStyle.OUTLINE -> {
            drawRect(color, topLeft, box)
            drawRect(p.edgeOf(color), topLeft, box, style = Stroke(width = cell * 0.08f))
        }
    }
}

/** A piece drawn centred in a small box, for the hold and next panels. */
@Composable
fun PiecePreview(piece: Piece?, dimmed: Boolean, palette: TetrisPalette, modifier: Modifier = Modifier) {
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
        val base = palette.colorOf(piece)
        val color = if (dimmed) base.copy(alpha = DIM_ALPHA) else base
        for (c in cells) {
            drawBlock(palette, color, left + (c.x - minX) * unit, top + (c.y - minY) * unit, unit)
        }
    }
}

private const val PREVIEW_SPAN = 4f
private const val DIM_ALPHA = 0.35f

/**
 * The other player's board in a race (ADR 0060, amended 2026-10-19), drawn small in this player's
 * own theme from the [com.kevin.legion.games.race.BoardWire] cells the engine relays. It takes no
 * input. [stale] dims it; the words beside it say why, never the dimming alone.
 */
@Composable
fun OpponentBoardCanvas(cells: IntArray?, palette: TetrisPalette, stale: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.alpha(if (stale) STALE_ALPHA else 1f)) {
        val cell = cellOf(size.width)
        drawWell(palette, cell)
        if (cells == null) return@Canvas
        val origin = cell * WALL_FRAC
        for (row in Board.HIDDEN_ROWS until Board.ROWS) {
            for (col in 0 until Board.COLUMNS) {
                val v = cells.getOrElse(row * Board.COLUMNS + col) { 0 }
                if (v == 0) continue
                val y = origin + (row - Board.HIDDEN_ROWS) * cell
                drawBlock(palette, palette.pieces[v - 1], origin + col * cell, y, cell)
            }
        }
    }
}

private const val STALE_ALPHA = 0.4f
