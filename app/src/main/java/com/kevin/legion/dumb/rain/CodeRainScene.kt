package com.kevin.legion.dumb.rain

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.provider.Settings

/** The rain's colours. Plain RGB ints so the contrast test can read them without Android. */
object RainPalette {
    /** The bright head: a pale green-white. */
    const val HEAD_RGB = 0xCCFFDD

    /** Matrix green, the colour of the trail and of the text on the home screen. */
    const val GREEN_RGB = 0x00FF41

    /** Home A's text colours, chosen so each clears 4.5:1 against the brightest rain under the scrim. */
    const val TEXT_RGB = GREEN_RGB
    const val BODY_RGB = 0x7DFF9F
    const val DIM_RGB = 0x00DC3C

    /** How opaque the black scrim over the rain is. Higher is more readable and less rain. */
    const val SCRIM_ALPHA = 0.72f
}

/**
 * The one renderer behind the home background, the live wallpaper and the screensaver (ADR 0062).
 * Owns a [CodeRainModel] sized to the surface it is given, advances it, and paints it onto any
 * [Canvas]. It paints glyphs only; the host fills the black behind them.
 *
 * Not thread-safe: each host drives its scene from one thread. Rendering is owed on a device; the
 * model underneath is what the unit tests cover.
 */
class CodeRainScene(private val seed: Long = System.nanoTime()) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    private var model: CodeRainModel? = null
    private var cellPx = 0f

    /** Sizes the grid to a surface of [widthPx] by [heightPx]; a no-op when nothing changed. */
    fun resize(widthPx: Int, heightPx: Int, density: Float) {
        val cell = (RainTuning.CELL_DP * density).coerceAtLeast(MIN_CELL_PX)
        val columns = (widthPx / cell).toInt().coerceAtLeast(1)
        val rows = (heightPx / cell).toInt().coerceAtLeast(1)
        val current = model
        val unchanged = current?.columns == columns && current.rows == rows && cell == cellPx
        if (unchanged) return
        cellPx = cell
        model = CodeRainModel(columns, rows, seed)
    }

    fun advance(dtSeconds: Float) {
        model?.step(dtSeconds)
    }

    fun draw(canvas: Canvas) {
        val m = model ?: return
        paint.textSize = cellPx * GLYPH_SCALE
        for (c in 0 until m.columns) {
            for (r in 0 until m.rows) {
                val b = m.brightness(c, r)
                if (b < RainTuning.MIN_VISIBLE) continue
                paint.color = colorFor(b)
                canvas.drawText(m.glyphAt(c, r), c * cellPx, (r + 1) * cellPx, paint)
            }
        }
    }

    private fun colorFor(brightness: Float): Int {
        val alpha = (brightness * ALPHA_MAX).toInt().coerceIn(0, ALPHA_MAX)
        return when {
            brightness >= HEAD_THRESHOLD -> OPAQUE or RainPalette.HEAD_RGB
            else -> (alpha shl ALPHA_SHIFT) or RainPalette.GREEN_RGB
        }
    }

    private companion object {
        const val MIN_CELL_PX = 8f
        const val GLYPH_SCALE = 0.9f
        const val ALPHA_MAX = 255
        const val ALPHA_SHIFT = 24
        const val OPAQUE = ALPHA_MAX shl ALPHA_SHIFT

        /** Only the very first cell of a drop is the bright head; the rest are green. */
        const val HEAD_THRESHOLD = 0.98f
    }
}

/** True when the user has turned system animations off; the rain then shows one still frame. */
fun animationsOff(context: Context): Boolean =
    runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
        .getOrDefault(false)
