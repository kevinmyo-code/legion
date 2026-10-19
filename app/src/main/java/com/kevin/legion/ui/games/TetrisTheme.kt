@file:Suppress("MagicNumber") // the colour literals are the palettes

package com.kevin.legion.ui.games

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import com.kevin.legion.games.tetris.Piece
import com.kevin.legion.ui.theme.soft.SoftColors

/** How a locked or falling block is painted. */
enum class BlockStyle { BEVEL, GLOW, OUTLINE }

/**
 * One theme's colours. [wall] is the band round the well and must read against the PAGE
 * ([SoftColors.ground]); [wallEdge] is the thin line on the well side of the band. [pieces] is in
 * [Piece] enum order I J L O S T Z.
 */
class TetrisPalette(
    val well: Color,
    val grid: Color,
    val wall: Color,
    val wallEdge: Color,
    val pieces: List<Color>,
    val style: BlockStyle,
) {
    fun colorOf(piece: Piece): Color = pieces[piece.ordinal]

    /** The colour that carries a block's shape against the well: the outline where there is one. */
    fun edgeOf(color: Color): Color =
        if (style == BlockStyle.OUTLINE) lerp(color, Color.Black, OUTLINE_DARKEN) else color
}

private const val OUTLINE_DARKEN = 0.55f

/** The three themes Kevin asked for (2026-10-19), picked on the Tetris screen and remembered. */
enum class TetrisTheme(val label: String, val palette: TetrisPalette) {
    /** Game Boy DMG: four greens. The well is the lightest, blocks use the two darkest. */
    CLASSIC(
        "Classic",
        TetrisPalette(
            well = Color(0xFF9BBC0F),
            grid = Color(0xFF8BAC0F),
            wall = Color(0xFF8BAC0F),
            wallEdge = Color(0xFF0F380F),
            pieces = listOf(
                Color(0xFF0F380F), Color(0xFF306230), Color(0xFF0F380F), Color(0xFF306230),
                Color(0xFF306230), Color(0xFF0F380F), Color(0xFF306230),
            ),
            style = BlockStyle.BEVEL,
        ),
    ),

    /** Guideline colours on a near-black well with a bright cyan wall. */
    NEON(
        "Neon",
        TetrisPalette(
            well = Color(0xFF07080D),
            grid = Color(0xFF1B1F2E),
            wall = Color(0xFF00B8D4),
            wallEdge = Color(0xFFB2F5FF),
            pieces = listOf(
                Color(0xFF00E5FF), Color(0xFF3D6BFF), Color(0xFFFF9100), Color(0xFFFFE000),
                Color(0xFF00E676), Color(0xFFB04DFF), Color(0xFFFF3B3B),
            ),
            style = BlockStyle.GLOW,
        ),
    ),

    /**
     * A light well on LEGION's dark soft page. The brief said a dark wall line; a dark band on the
     * dark page fails the wall-vs-page check, so the band is mid slate and the dark line is its edge.
     */
    SOFT(
        "Soft",
        TetrisPalette(
            well = Color(0xFFEDEAF3),
            grid = Color(0xFFD6D2E0),
            wall = Color(0xFF8A8F9B),
            wallEdge = Color(0xFF2A2D34),
            pieces = listOf(
                Color(0xFF5FB8C9), Color(0xFF7D8FE8), Color(0xFFF0A56A), Color(0xFFE8C23E),
                Color(0xFF6FCB8B), Color(0xFFB28CE8), Color(0xFFEE7F85),
            ),
            style = BlockStyle.OUTLINE,
        ),
    );

    fun next(): TetrisTheme = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromName(name: String?): TetrisTheme = entries.firstOrNull { it.name == name } ?: CLASSIC
    }
}

/** WCAG contrast ratio, 1 (identical) to 21. */
fun contrastRatio(a: Color, b: Color): Double {
    val la = a.luminance().toDouble()
    val lb = b.luminance().toDouble()
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

/** The page every theme sits on. */
val TetrisPage: Color get() = SoftColors.ground

/** The chosen theme, kept on the device (local UI preference, so SharedPreferences like the rest). */
class TetrisThemeStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): TetrisTheme = TetrisTheme.fromName(prefs.getString(KEY, null))

    fun save(theme: TetrisTheme) {
        prefs.edit().putString(KEY, theme.name).apply()
    }

    private companion object {
        const val PREFS = "legion_tetris"
        const val KEY = "theme"
    }
}
