package com.kevin.legion.ui.typeset

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.theme.soft.TypesetType
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Typeset compass rose for the Field screen (ADR 0081 style): a ruled ring with a tick every 10
 * degrees, longer every 30, N E S W in Source Serif small caps, all in ink, turned so north points
 * north. A fixed lubber mark at the top is where the phone points. [bearing], when set, draws the
 * waypoint arrow from the centre: a thin ink line with a filled head. No accent: nothing here is a
 * flag. [heading] null (no sensor, or not calibrated) draws the rose unturned with no lubber mark;
 * the caller says why in words beside it. Pure drawing: the Field screen wires the sensors.
 */
@Composable
fun TypesetCompass(heading: Float?, bearing: Float?, modifier: Modifier = Modifier) {
    val ink = SoftColors.text
    val meta = SoftColors.text2
    val measurer = rememberTextMeasurer()
    val label = TypesetType.caps.copy(color = ink)
    val words = buildString {
        append(heading?.let { "Heading ${CompassMath.degrees(it)}" } ?: "No heading")
        bearing?.let { append(", waypoint bearing ${CompassMath.degrees(it)}") }
    }
    Canvas(modifier.semantics { contentDescription = words }) {
        val r = size.minDimension / 2f * RING
        val c = center
        val turn = -(heading ?: 0f)
        drawCircle(ink, radius = r, center = c, style = Stroke(1.dp.toPx()))
        rotate(turn, c) {
            for (deg in 0 until FULL step TICK_STEP) {
                val w = CompassMath.tickWeight(deg)
                val a = Math.toRadians(deg.toDouble()).toFloat()
                val dir = Offset(sin(a), -cos(a))
                drawLine(
                    if (w == 0) meta else ink,
                    start = c + dir * r,
                    end = c + dir * (r - r * TICK_LEN[w]),
                    strokeWidth = (if (w == 2) 2.dp else 1.dp).toPx(),
                )
                CompassMath.cardinal(deg)?.let { letter ->
                    val text = measurer.measure(letter, label)
                    val at = c + dir * (r * LETTER_AT)
                    rotate(-turn, at) {
                        drawText(text, topLeft = at - Offset(text.size.width / 2f, text.size.height / 2f))
                    }
                }
            }
        }
        if (heading != null) lubber(ink, c, r)
        if (bearing != null) arrow(ink, c, r, CompassMath.relative(bearing, heading ?: 0f))
    }
}

private fun DrawScope.lubber(ink: Color, c: Offset, r: Float) {
    val h = LUBBER.dp.toPx()
    val gap = 2.dp.toPx()
    val p = Path().apply {
        moveTo(c.x, c.y - r - gap)
        lineTo(c.x - h / 2f, c.y - r - h - gap)
        lineTo(c.x + h / 2f, c.y - r - h - gap)
        close()
    }
    drawPath(p, ink)
}

private fun DrawScope.arrow(ink: Color, c: Offset, r: Float, relative: Float) {
    rotate(relative, c) {
        val tip = Offset(c.x, c.y - r * ARROW_LEN)
        drawLine(ink, start = c, end = tip, strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
        val h = ARROW_HEAD.dp.toPx()
        val head = Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(tip.x - h / 2f, tip.y + h)
            lineTo(tip.x + h / 2f, tip.y + h)
            close()
        }
        drawPath(head, ink)
        drawCircle(ink, radius = HUB.dp.toPx(), center = c)
    }
}

private const val FULL = 360
private const val TICK_STEP = 10
private const val RING = 0.86f
private const val LETTER_AT = 0.68f
private const val ARROW_LEN = 0.55f
private const val ARROW_HEAD = 12
private const val LUBBER = 12
private const val HUB = 3
private const val TICK_MINOR = 0.05f
private const val TICK_MAJOR = 0.09f
private const val TICK_CARDINAL = 0.14f
private val TICK_LEN = floatArrayOf(TICK_MINOR, TICK_MAJOR, TICK_CARDINAL)
