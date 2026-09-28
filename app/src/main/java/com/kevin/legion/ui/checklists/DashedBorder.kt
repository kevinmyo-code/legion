package com.kevin.legion.ui.checklists

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The "New list" card's `1.5dp dashed outline` border - `research/prototype-canvas/ListsC.dc.html`'s
 * own `border: 1.5px dashed #3A3E47`. Compose has no built-in dashed-border modifier, so this draws
 * one directly with a [PathEffect.dashPathEffect] stroke, matched to [cornerRadius] via
 * [RoundedCornerShape] the same way the filled cards are clipped.
 */
/** Dash length, then gap length, in px - `ListsC.dc.html` states no dash cadence of its own for
 * its dashed border, so these are this file's own pick, named so detekt's [MagicNumber] rule has
 * something other than a bare literal to point at. */
private const val DASH_LENGTH_PX = 8f
private const val DASH_GAP_PX = 6f

fun Modifier.dashedBorder(color: Color, cornerRadius: Dp, width: Dp = 1.5.dp): Modifier = this.drawBehind {
    val strokePx = width.toPx()
    val radiusPx = cornerRadius.toPx()
    val inset = strokePx / 2f
    drawRoundRect(
        color = color,
        topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
        size = androidx.compose.ui.geometry.Size(size.width - strokePx, size.height - strokePx),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(radiusPx - inset, radiusPx - inset),
        style = Stroke(width = strokePx, pathEffect = PathEffect.dashPathEffect(floatArrayOf(DASH_LENGTH_PX, DASH_GAP_PX), 0f)),
    )
}
