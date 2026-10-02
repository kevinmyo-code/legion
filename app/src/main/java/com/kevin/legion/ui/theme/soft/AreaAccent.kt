package com.kevin.legion.ui.theme.soft

import androidx.compose.ui.graphics.Color

/**
 * One colour per area (home-launcher ticket 02, ADR 0051): the tonal icon-chip pair HOME's eight
 * tiles use, in the fixed order ticket 01 resolved ("2 columns x 4 rows... Calendar, Lists, Money,
 * Body, Fleet, Recordings, News, Reports"). Ticket 04 (the Lists screens) reuses the same eight
 * pairs as its own list palette rather than inventing a second area-colour system - see ticket 02's
 * own text, "Ticket 04 reuses these eight pairs as the list palette."
 *
 * Values are lifted verbatim from the prototype canvas (`research/prototype-canvas/Main.dc.html`),
 * same posture as [SoftColors].
 */
enum class AreaAccent(val container: Color, val onContainer: Color) {
    CALENDAR(Color(0xFF1F3450), Color(0xFFA8C8FF)),
    LISTS(Color(0xFF43340F), Color(0xFFFFD36B)),
    MONEY(Color(0xFF173D2B), Color(0xFF7EDBA5)),
    BODY(Color(0xFF4A1F2C), Color(0xFFFFB1C3)),
    FLEET(Color(0xFF0F3B40), Color(0xFF77DCE5)),
    RECORDINGS(Color(0xFF33265A), Color(0xFFCDBDFF)),
    NEWS(Color(0xFF4A2A12), Color(0xFFFFB77C)),
    REPORTS(Color(0xFF262E57), Color(0xFFB9C3FF)),
}
