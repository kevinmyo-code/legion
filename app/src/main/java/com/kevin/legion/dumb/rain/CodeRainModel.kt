package com.kevin.legion.dumb.rain

import kotlin.random.Random

/**
 * Every number that sets how the code rain looks and moves, in ONE place (Kevin, 2026-10-22: green,
 * classic movie speed). The speed and density here are a judgement of what "classic" looks like
 * (a slow, steady fall of roughly a dozen glyph rows a second); changing the feel is changing this
 * object and nothing else. The home background, the live wallpaper and the screensaver all read it.
 */
object RainTuning {
    /** Glyph cell size. The cell is square and the glyph is drawn a little smaller inside it. */
    const val CELL_DP = 16f

    /** How fast a column's bright head falls, in glyph rows per second. */
    const val FALL_CELLS_PER_SEC = 11f

    /** Each column's speed is the base times a random factor in 1 +/- this. */
    const val SPEED_JITTER = 0.35f

    /** A column's visible trail behind the head, in rows, chosen per drop. */
    const val TRAIL_MIN = 10
    const val TRAIL_MAX = 28

    /** About what fraction of the time a column has a drop on screen. 1.0 would be wall to wall. */
    const val DENSITY = 0.6f

    /** How often, per column per second, one of its glyphs changes to another. */
    const val MUTATE_PER_COLUMN_PER_SEC = 2.5f

    /** The rain redraws at about this interval. 30 frames a second is plenty for a slow fall. */
    const val FRAME_MS = 33L

    /** Below this brightness a trail glyph is not drawn at all. */
    const val MIN_VISIBLE = 0.03f
}

/**
 * The glyphs: half-width katakana, the digits and a few Latin letters, as the film's rain was.
 * Held as one-character strings so drawing allocates nothing per frame.
 */
object RainGlyphs {
    val ALL: List<String> = buildList {
        for (code in KATAKANA_FIRST..KATAKANA_LAST) add(code.toChar().toString())
        for (digit in '0'..'9') add(digit.toString())
        for (letter in LATIN) add(letter.toString())
    }

    private const val KATAKANA_FIRST = 0xFF66
    private const val KATAKANA_LAST = 0xFF9D
    private const val LATIN = "ZTHEMARIX"
}

/**
 * The rain's state, pure and deterministic for a given [seed] (ADR 0062). A grid of [columns] by
 * [rows]; each column has one falling drop (a bright head and a fading trail) and then, after a gap,
 * another. Nothing here draws or reads a clock: the host calls [step] with elapsed seconds.
 */
class CodeRainModel(val columns: Int, val rows: Int, seed: Long) {
    private val random = Random(seed)
    private val glyphIndex = IntArray(columns * rows) { random.nextInt(RainGlyphs.ALL.size) }
    private val head = FloatArray(columns)
    private val speed = FloatArray(columns)
    private val trail = IntArray(columns)

    init {
        for (c in 0 until columns) {
            newDrop(c)
            // Start mid-rain rather than with every column waiting at the top.
            head[c] = random.nextFloat() * (rows + trail[c]) - trail[c] * HALF
        }
    }

    /** The head row (fractional, negative while the drop has not entered) of [column]. */
    fun headRow(column: Int): Float = head[column]

    fun trailLength(column: Int): Int = trail[column]

    fun speedOf(column: Int): Float = speed[column]

    /** Advances every drop by [dtSeconds] and mutates a few glyphs. */
    fun step(dtSeconds: Float) {
        if (dtSeconds <= 0f) return
        for (c in 0 until columns) {
            head[c] += speed[c] * dtSeconds
            if (head[c] - trail[c] > rows) newDrop(c)
            if (random.nextFloat() < RainTuning.MUTATE_PER_COLUMN_PER_SEC * dtSeconds) {
                glyphIndex[c * rows + random.nextInt(rows)] = random.nextInt(RainGlyphs.ALL.size)
            }
        }
    }

    /** 1.0 at the head, fading to 0 across the trail, 0 where the drop is not. */
    fun brightness(column: Int, row: Int): Float = brightnessFor(head[column], row, trail[column])

    fun glyphAt(column: Int, row: Int): String = RainGlyphs.ALL[glyphIndex[column * rows + row]]

    private fun newDrop(column: Int) {
        trail[column] = RainTuning.TRAIL_MIN + random.nextInt(RainTuning.TRAIL_MAX - RainTuning.TRAIL_MIN + 1)
        val jitter = 1f + (random.nextFloat() * 2f - 1f) * RainTuning.SPEED_JITTER
        speed[column] = RainTuning.FALL_CELLS_PER_SEC * jitter
        // The wait before the next drop makes the column busy DENSITY of the time on average.
        val activeRows = rows + trail[column]
        val idleRows = activeRows * (1f - RainTuning.DENSITY) / RainTuning.DENSITY
        head[column] = -random.nextFloat() * 2f * idleRows
    }

    companion object {
        /**
         * The brightness of [row] for a drop whose head is at [head] with a trail of [trail] rows.
         * The head cell (the row the head is inside) is 1.0; each row behind loses 1/[trail];
         * rows ahead of the head, and rows past the trail, are 0.
         */
        private const val HALF = 0.5f

        fun brightnessFor(head: Float, row: Int, trail: Int): Float {
            val behind = kotlin.math.floor(head).toInt() - row
            return when {
                trail <= 0 || behind < 0 -> 0f
                behind >= trail -> 0f
                else -> 1f - behind.toFloat() / trail
            }
        }
    }
}
