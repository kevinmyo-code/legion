package com.kevin.legion.games.tetris

import kotlin.math.pow

/**
 * Score, level and gravity from ticket 04. Single 100, double 300, triple 500, tetris 800, each
 * times the level BEFORE the clear; soft drop 1 per cell, hard drop 2 per cell; no T-spin, combo
 * or back-to-back bonus. The level multiplies the line award only, never the drop points
 * (`shared/tetris/fixtures.json` pins this: level 2, 1 line, 3 soft, 10 hard is 223).
 */
object Scoring {
    @Suppress("MagicNumber") // the published award table, indexed by lines cleared
    private val lineAward = intArrayOf(0, 100, 300, 500, 800)
    private const val LINES_PER_LEVEL = 10
    private const val BASE_GRAVITY = 0.8
    private const val GRAVITY_STEP = 0.007
    private const val HARD_DROP_PER_CELL = 2

    fun points(levelBeforeClear: Int, linesCleared: Int, softDropCells: Int, hardDropCells: Int): Int =
        lineAward[linesCleared.coerceIn(0, lineAward.lastIndex)] * levelBeforeClear +
            softDropCells + HARD_DROP_PER_CELL * hardDropCells

    fun level(totalLines: Int): Int = 1 + totalLines / LINES_PER_LEVEL

    /** Seconds one row of gravity takes: `(0.8 - (level - 1) * 0.007) ^ (level - 1)`. */
    fun secondsPerRow(level: Int): Double = (BASE_GRAVITY - (level - 1) * GRAVITY_STEP).pow(level - 1)
}
