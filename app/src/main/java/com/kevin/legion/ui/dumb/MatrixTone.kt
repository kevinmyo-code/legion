package com.kevin.legion.ui.dumb

import androidx.compose.ui.graphics.Color

/**
 * The kit every Matrix screen is built from (matrix-open tickets 03-06, Kevin 2026-10-24): green
 * monospace on black, rows you tap, nothing else. No cards, icons, images, charts or rain, so there is
 * deliberately no composable here that draws a box.
 *
 * How a line is coloured. [HEADING] and [BODY] are the greens; [DIM] is secondary. [ALERT], [WARNING]
 * and [UNVERIFIED] are never green (see [MatrixColors]) but the WORDS still carry the meaning, because
 * on a screen where every ordinary value is green a hue alone would not (CLAUDE.md section 4 rule 7).
 */
internal enum class MatrixTone { HEADING, BODY, DIM, ALERT, WARNING, UNVERIFIED }

internal fun MatrixTone.color(): Color = when (this) {
    MatrixTone.HEADING -> MatrixColors.text
    MatrixTone.BODY -> MatrixColors.body
    MatrixTone.DIM -> MatrixColors.dim
    MatrixTone.ALERT -> MatrixColors.alert
    MatrixTone.WARNING -> MatrixColors.warning
    MatrixTone.UNVERIFIED -> MatrixColors.unverified
}
