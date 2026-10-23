package com.kevin.legion.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import com.kevin.legion.ui.dumb.MatrixColorScheme
import com.kevin.legion.ui.dumb.MatrixSemantics

/**
 * True while Matrix mode is on (Kevin, 2026-10-23, matrix batch item 2): LEGION's own screens then
 * draw in the Matrix scheme. Provided once at the root (`MainActivity`) from the mode's state flow;
 * both [LegionTheme] and `SoftTheme` read it, so no screen needs to know. Defaults to false, which is
 * what every other activity and every preview gets.
 *
 * **Reach, stated honestly:** this swaps the Material scheme and [LegionSemantics]. Code that reads a
 * `SoftColors.*` constant directly keeps its Soft colour; that is a call-site migration, not a theme
 * change. **Not applied to the Mapbox navigation map** (mapbox-plus ticket 01 ruling 5: readability
 * beats theme on a map), which is not a Material surface and never reads this. Code rain stays on
 * Matrix home only.
 */
val LocalMatrixMode = staticCompositionLocalOf { false }

/** The scheme to hand `MaterialTheme`: Matrix's while the mode is on, otherwise [normal]. Pure. */
internal fun pickColorScheme(matrix: Boolean, normal: ColorScheme): ColorScheme =
    if (matrix) MatrixColorScheme else normal

/** The semantic roles to provide: Matrix's while the mode is on, otherwise [normal]. Pure. */
internal fun pickSemantics(matrix: Boolean, normal: LegionSemantics): LegionSemantics =
    if (matrix) MatrixSemantics else normal
