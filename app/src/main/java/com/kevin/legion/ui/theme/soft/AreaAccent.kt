package com.kevin.legion.ui.theme.soft

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.kevin.legion.R

/**
 * One colour per area (home-launcher ticket 02, ADR 0051): the tonal icon-chip pair HOME's eight
 * tiles use, in the fixed order ticket 01 resolved ("2 columns x 4 rows... Calendar, Lists, Money,
 * Body, Fleet, Recordings, News, Reports"). Ticket 04 (the Lists screens) reuses the same eight
 * pairs as its own list palette rather than inventing a second area-colour system - see ticket 02's
 * own text, "Ticket 04 reuses these eight pairs as the list palette."
 *
 * [icon] is the area's HOME tile glyph (the same `ms_*` drawable `HomeScreen` passes), so a drill-down's
 * header can draw the same tonal chip without re-listing eight resource names - see [AreaChip].
 *
 * Values are lifted verbatim from the prototype canvas (`research/prototype-canvas/Main.dc.html`),
 * same posture as [SoftColors].
 */
@Suppress("MagicNumber") // palette hex literals
enum class AreaAccent(
    /**
     * The original dark fill, fixed: for surfaces that never follow the page (the Mapbox map:
     * mapbox-plus ticket 01 ruling 5, readability beats theme on a map; the book reader, which sets
     * its own Day, Sepia and Night page).
     */
    val darkContainer: Color,
    /** The original pastel, fixed, beside [darkContainer]. */
    val darkOnContainer: Color,
    private val dayContainer: Color,
    private val dayOnContainer: Color,
    @DrawableRes val icon: Int,
) {
    CALENDAR(Color(0xFF1F3450), Color(0xFFA8C8FF), Color(0xFFE3EAF4), Color(0xFF2B4C7E), R.drawable.ms_calendar_month),
    LISTS(Color(0xFF43340F), Color(0xFFFFD36B), Color(0xFFF3EAD2), Color(0xFF6E5410), R.drawable.ms_checklist),
    MONEY(
        Color(0xFF173D2B), Color(0xFF7EDBA5), Color(0xFFE0EEE5), Color(0xFF1F6B45),
        R.drawable.ms_account_balance_wallet,
    ),
    BODY(Color(0xFF4A1F2C), Color(0xFFFFB1C3), Color(0xFFF4E1E6), Color(0xFF8A2D47), R.drawable.ms_monitor_heart),
    FLEET(Color(0xFF0F3B40), Color(0xFF77DCE5), Color(0xFFDDEEEF), Color(0xFF1D5E64), R.drawable.ms_directions_car),
    RECORDINGS(Color(0xFF33265A), Color(0xFFCDBDFF), Color(0xFFE9E3F4), Color(0xFF54418A), R.drawable.ms_graphic_eq),
    NEWS(Color(0xFF4A2A12), Color(0xFFFFB77C), Color(0xFFF4E6DA), Color(0xFF7E4416), R.drawable.ms_newspaper),
    REPORTS(Color(0xFF262E57), Color(0xFFB9C3FF), Color(0xFFE4E6F4), Color(0xFF3E4A8A), R.drawable.ms_bar_chart),
    ;

    /**
     * The chip fill. On day paper (home-ui 2026-11-17) a light tint of the hue, so a chip is not a
     * dark disc on paper; everywhere else (night, Matrix, the soft-dark look) the original dark tone.
     */
    val container: Color @Composable @ReadOnlyComposable get() = if (onDayPaper()) dayContainer else darkContainer

    /** The glyph and text colour: a deep tone of the hue on day paper (at least 5.4:1 on paper, on
     * card and on [container]), the original pastel everywhere else. */
    val onContainer: Color @Composable @ReadOnlyComposable get() = if (onDayPaper()) dayOnContainer else darkOnContainer
}
