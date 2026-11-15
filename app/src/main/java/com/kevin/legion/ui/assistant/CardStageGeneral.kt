package com.kevin.legion.ui.assistant

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kevin.legion.assistant.cards.ConversationCard
import com.kevin.legion.assistant.cards.RowStatus
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.theme.soft.TypesetType

/** Test tag on a trend card's picture. */
internal const val TREND_CHART_TAG = "card-trend-chart"

/**
 * The general kinds (2026-11-15) on a card page: readings, status rows and a trend. Every value,
 * status and series is said in words ([CardWords]); the trend's picture is decoration beside them,
 * hidden from a screen reader, and a status word in the accent repeats a flag it already says.
 */
@Composable
internal fun ReadingsBody(card: ConversationCard.Readings) {
    AsOfLine(card.at)
    card.items.forEach { r ->
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    r.label,
                    style = TypesetType.body,
                    color = SoftColors.text,
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                )
                Text(
                    CardGeneralWords.reading(r),
                    style = TypesetType.bodyStrong,
                    color = if (r.value == null) SoftColors.text2 else SoftColors.text,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            r.note?.let { Text(it, style = TypesetType.smallItalic, color = SoftColors.text2) }
        }
        HorizontalDivider(thickness = 1.dp, color = SoftColors.barRule)
    }
    MoreLine(card.more)
}

@Composable
internal fun StatusBody(card: ConversationCard.StatusList) {
    AsOfLine(card.at)
    card.items.forEach { row ->
        val flagged = row.status == RowStatus.ATTENTION || row.status == RowStatus.FAILED
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    row.name,
                    style = TypesetType.body,
                    color = SoftColors.text,
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                )
                Text(
                    row.status.words,
                    style = TypesetType.bodyStrong,
                    color = if (flagged) SoftColors.caution else SoftColors.text,
                    textAlign = TextAlign.End,
                )
            }
            row.note?.let { Text(it, style = TypesetType.smallItalic, color = SoftColors.text2) }
        }
        HorizontalDivider(thickness = 1.dp, color = SoftColors.barRule)
    }
    MoreLine(card.more)
}

@Composable
internal fun TrendBody(card: ConversationCard.Trend) {
    if (card.series.sumOf { it.points.size } > 1) TrendChart(card)
    card.series.forEach { RuledLine(CardGeneralWords.series(it, card.unit)) }
}

/** The line(s) in ink, the second dashed so two series differ without colour. */
@Composable
private fun TrendChart(card: ConversationCard.Trend) {
    val ink = SoftColors.text
    val rule = SoftColors.barRule
    val plot = CardGeneralWords.plot(card)
    Canvas(
        Modifier.fillMaxWidth().height(CHART).padding(vertical = 8.dp).testTag(TREND_CHART_TAG)
            .clearAndSetSemantics {},
    ) {
        val pad = 4.dp.toPx()
        val w = size.width - 2 * pad
        val h = size.height - 2 * pad
        fun at(p: Pair<Float, Float>) = Offset(pad + p.first * w, pad + (1f - p.second) * h)
        drawLine(rule, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
        plot.forEachIndexed { i, points ->
            when {
                points.size == 1 -> drawCircle(ink, radius = 3.dp.toPx(), center = at(points.single()))
                points.size > 1 -> {
                    val path = Path().apply {
                        moveTo(at(points.first()).x, at(points.first()).y)
                        points.drop(1).forEach { lineTo(at(it).x, at(it).y) }
                    }
                    val dash = if (i == 0) null else PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
                    drawPath(path, ink, style = Stroke(width = (if (i == 0) 2f else 1.5f).dp.toPx(), pathEffect = dash))
                }
            }
        }
    }
}

@Composable
private fun AsOfLine(at: String?) {
    CardGeneralWords.asOf(at)?.let {
        Text(it, style = TypesetType.smallItalic, color = SoftColors.text2, modifier = Modifier.padding(top = 6.dp))
    }
}

private val CHART = 120.dp
