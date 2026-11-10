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
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** The compass's arithmetic, pure. Pinned by `CompassMathTest`. */
object CompassMath {
    private const val FULL_TURN = 360f
    private const val MAJOR_EVERY = 30
    private const val CARDINAL_EVERY = 90

    /** Any angle to 0 until 360. */
    fun normalize(deg: Float): Float = ((deg % FULL_TURN) + FULL_TURN) % FULL_TURN

    /** Where the waypoint lies relative to where the phone points: 0 is dead ahead, 90 to the right. */
    fun relative(bearing: Float, heading: Float): Float = normalize(bearing - heading)

    /** "042°": three figures, as a bearing is written on a map. */
    fun degrees(deg: Float): String =
        String.format(Locale.US, "%03d°", normalize(deg).roundToInt() % FULL_TURN.toInt())

    /** The letter at a cardinal point, or null between them. */
    @Suppress("MagicNumber") // the four cardinal points, in degrees
    fun cardinal(deg: Int): String? = when (deg) {
        0 -> "N"
        90 -> "E"
        180 -> "S"
        270 -> "W"
        else -> null
    }

    /** Tick weight: 2 at a cardinal, 1 every 30 degrees, 0 for the 10 degree marks. */
    fun tickWeight(deg: Int): Int = when {
        deg % CARDINAL_EVERY == 0 -> 2
        deg % MAJOR_EVERY == 0 -> 1
        else -> 0
    }
}

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
private val TICK_LEN = floatArrayOf(0.05f, 0.09f, 0.14f)
