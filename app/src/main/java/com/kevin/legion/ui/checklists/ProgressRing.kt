package com.kevin.legion.ui.checklists

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kevin.legion.ui.theme.soft.SoftColors

/** The icon card's 68dp ring (home-launcher ticket 04), around whatever [content] draws (the 58dp
 * coloured chip). [fraction] `null` draws no ring at all - the "Couldn't load" card's own "no
 * ring" ([ListProgress]'s own doc comment) - so a failed read is never dressed up with a track
 * that implies a real, if empty, measurement. `0f` still draws the track (a visibly empty ring),
 * distinct from no ring at all. */
@Composable
fun ProgressRing(
    fraction: Float?,
    ringColor: Color,
    size: Dp = 68.dp,
    strokeWidth: Dp = 4.dp,
    content: @Composable () -> Unit,
) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        if (fraction != null) {
            Canvas(Modifier.size(size)) {
                val stroke = Stroke(width = strokeWidth.toPx())
                val inset = stroke.width / 2f
                val arcSize = Size(this.size.width - stroke.width, this.size.height - stroke.width)
                drawArc(
                    color = SoftColors.cardHighest,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                    size = arcSize,
                    style = stroke,
                )
                if (fraction > 0f) {
                    drawArc(
                        color = ringColor,
                        startAngle = -90f,
                        sweepAngle = 360f * fraction.coerceIn(0f, 1f),
                        useCenter = false,
                        topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                        size = arcSize,
                        style = stroke,
                    )
                }
            }
        }
        content()
    }
}
