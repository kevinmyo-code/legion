package com.kevin.legion.ui.assistant

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kevin.legion.assistant.cards.ConversationCard
import com.kevin.legion.assistant.cards.TrendStyle
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.theme.soft.TypesetType

/** Test tag on a trend card's picture. */
internal const val TREND_CHART_TAG = "card-trend-chart"

/**
 * A trend's picture (2026-11-15): its y span said at the left (low and high), its first and last
 * time under it, the guide line dashed in the meta ink, the first series solid and the second
 * dashed, each measured point a dot and the latest a larger one. Bars stand from zero in day slots.
 * The plot is decoration for a screen reader; the axis words, the legend and the series' own
 * sentences say everything it shows.
 */
@Composable
internal fun TrendChart(card: ConversationCard.Trend) {
    val layout = remember(card) { CardGeometry.trend(card) }
    val all = card.series.flatMap { it.points }
    val first = all.minByOrNull { CardGeneralWords.minutes(it.at) }?.at
    val last = all.maxByOrNull { CardGeneralWords.minutes(it.at) }?.at
    val ink = SoftColors.text
    val meta = SoftColors.text2
    val rule = SoftColors.barRule
    val paper = SoftColors.ground
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth().height(CHART)) {
            Column(
                Modifier.width(GUTTER).fillMaxHeight().clearAndSetSemantics {},
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(CardGeneralWords.withUnit(layout.high, card.unit), style = TypesetType.small, color = meta)
                Text(CardGeneralWords.withUnit(layout.low, card.unit), style = TypesetType.small, color = meta)
            }
            Canvas(Modifier.weight(1f).fillMaxHeight().testTag(TREND_CHART_TAG).clearAndSetSemantics {}) {
                drawAxes(rule)
                layout.guide?.let { drawGuide(meta, it) }
                if (card.style == TrendStyle.BARS) {
                    drawColumns(ink, layout)
                } else {
                    drawLines(ink, paper, layout, card.series.map { it.points.size })
                }
            }
        }
        if (first != null && last != null) {
            val withDay = first.take(DATE) != last.take(DATE)
            Row(Modifier.fillMaxWidth().padding(start = GUTTER).clearAndSetSemantics {}) {
                Text(
                    CardPictureWords.axisTime(first, withDay),
                    style = TypesetType.small,
                    color = meta,
                    modifier = Modifier.weight(1f),
                )
                if (last != first) {
                    Text(
                        CardPictureWords.axisTime(last, withDay),
                        style = TypesetType.small,
                        color = meta,
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
        TrendLegend(card, layout.guideOff)
    }
}

/** A key for two series (solid, dashed) and the guide line; one series is named by the title. */
@Composable
private fun TrendLegend(card: ConversationCard.Trend, guideOff: Int) {
    val ink = SoftColors.text
    val meta = SoftColors.text2
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (card.series.size > 1) {
            card.series.forEachIndexed { i, s ->
                Key(ink, dashed = i > 0)
                Text(
                    s.flag?.let { "${s.name} (${it.words})" } ?: s.name,
                    style = TypesetType.small,
                    color = SoftColors.text,
                )
            }
        }
        card.guide?.let { g ->
            Key(meta, dashed = true)
            val off = when (guideOff) {
                -1 -> ", below the chart"
                1 -> ", above the chart"
                else -> ""
            }
            Text(g.label + off, style = TypesetType.small, color = SoftColors.text)
        }
    }
}

@Composable
private fun Key(color: Color, dashed: Boolean) {
    Canvas(Modifier.size(width = KEY, height = 10.dp).clearAndSetSemantics {}) {
        val y = size.height / 2
        drawLine(
            color,
            Offset(0f, y),
            Offset(size.width, y),
            strokeWidth = 2.dp.toPx(),
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(DASH_ON, DASH_OFF)) else null,
        )
    }
}

private fun DrawScope.drawAxes(rule: Color) {
    val w = 1.dp.toPx()
    drawRect(rule, Offset(0f, 0f), Size(size.width, w))
    drawRect(rule, Offset(0f, size.height - w), Size(size.width, w))
}

private fun DrawScope.drawGuide(color: Color, y: Float) {
    val at = plotY(y)
    drawLine(
        color,
        Offset(0f, at),
        Offset(size.width, at),
        strokeWidth = 1.5.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(GUIDE_ON, GUIDE_OFF)),
    )
}

private fun DrawScope.plotY(y: Float): Float {
    val pad = PAD.toPx()
    return pad + (1f - y) * (size.height - 2 * pad)
}

private fun DrawScope.drawLines(ink: Color, paper: Color, layout: CardGeometry.TrendLayout, sizes: List<Int>) {
    val pad = PAD.toPx()
    fun at(p: Pair<Float, Float>) = Offset(pad + p.first * (size.width - 2 * pad), plotY(p.second))
    layout.lines.forEachIndexed { i, runs ->
        val dash = if (i == 0) null else PathEffect.dashPathEffect(floatArrayOf(DASH_ON, DASH_OFF))
        val width = (if (i == 0) LINE_FIRST else LINE_SECOND).toPx()
        runs.forEach { run ->
            if (run.size > 1) {
                val path = Path().apply {
                    moveTo(at(run.first()).x, at(run.first()).y)
                    run.drop(1).forEach { lineTo(at(it).x, at(it).y) }
                }
                val stroke = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = dash)
                drawPath(path, ink, style = stroke)
            }
            // Each measured point is a dot when there are few enough to see; a lone point always is.
            if (i == 0 && (sizes[i] <= DOTS_UP_TO || run.size == 1)) run.forEach { drawCircle(ink, DOT.toPx(), at(it)) }
        }
    }
    layout.lines.firstOrNull()?.lastOrNull()?.lastOrNull()?.let { p ->
        drawCircle(paper, (LAST + RING).toPx(), at(p))
        drawCircle(ink, LAST.toPx(), at(p))
    }
}

private fun DrawScope.drawColumns(ink: Color, layout: CardGeometry.TrendLayout) {
    val pad = PAD.toPx()
    val inner = size.width - 2 * pad
    val w = (inner * layout.slot * COLUMN).coerceAtMost(COLUMN_MAX.toPx())
    val base = plotY(CardGeometry.fraction(java.math.BigDecimal.ZERO, layout.low, layout.high))
    layout.bars.forEach { (x, y) ->
        val cx = pad + x * inner
        val top = plotY(y)
        drawRect(ink, Offset(cx - w / 2, minOf(top, base)), Size(w, kotlin.math.abs(base - top)))
    }
}

private val CHART = 132.dp
private val GUTTER = 64.dp
private val PAD = 6.dp
private val DOT = 2.5.dp
private val LAST = 4.dp
private val RING = 2.dp
private val LINE_FIRST = 2.dp
private val LINE_SECOND = 1.5.dp
private val KEY = 22.dp
private val COLUMN_MAX = 24.dp
private const val COLUMN = 0.6f
private const val DATE = 10
private const val DOTS_UP_TO = 31
private const val DASH_ON = 10f
private const val DASH_OFF = 8f
private const val GUIDE_ON = 4f
private const val GUIDE_OFF = 6f
