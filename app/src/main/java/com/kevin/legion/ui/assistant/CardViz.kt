package com.kevin.legion.ui.assistant

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kevin.legion.assistant.cards.CardFlag
import com.kevin.legion.assistant.cards.Reading
import com.kevin.legion.assistant.cards.RowStatus
import com.kevin.legion.assistant.cards.StatusLevel
import com.kevin.legion.assistant.cards.StatusMeter
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.theme.soft.TypesetType

/** Test tags on the pictures (2026-11-15). */
internal const val CARD_HEADLINE_TAG = "card-headline"
internal const val CARD_METER_TAG = "card-meter"
internal const val CARD_SPLIT_TAG = "card-split"
internal const val CARD_LEVEL_TAG = "card-level"

/**
 * The marks a card draws so it reads at a glance (Kevin, 2026-11-15: "more visualization of data
 * where possible instead of words"). Typeset rules (ADR 0081): ink on paper, square ends, the one
 * accent only for what needs attention, and an estimate hatched so it never looks measured. Each
 * picture repeats what the words beside it say, so it is hidden from a screen reader unless it
 * carries a fact of its own (a level), which it then says as its description.
 */
@Composable
internal fun HeadlineBlock(headline: CardPictureWords.Headline) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp).testTag(CARD_HEADLINE_TAG)) {
        headline.label?.let { Text(it.uppercase(), style = TypesetType.caps, color = SoftColors.text2) }
        Text(
            headline.value,
            style = TypesetType.fig,
            color = if (headline.attention) SoftColors.caution else SoftColors.text,
        )
    }
}

/** A reading on its scale: the track in the rule grey, the value in ink (hatched for a flag). */
@Composable
internal fun ReadingMeter(r: Reading) {
    val scale = r.scale ?: return
    val at = CardGeometry.reading(r.value, scale, r.mark?.value) ?: return
    val ink = if (r.attention) SoftColors.caution else SoftColors.text
    val track = SoftColors.barRule
    val tick = SoftColors.text
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Canvas(Modifier.fillMaxWidth().height(METER_BOX).testTag(CARD_METER_TAG).clearAndSetSemantics {}) {
            val top = (size.height - METER.toPx()) / 2
            drawRect(track, Offset(0f, top), Size(size.width, METER.toPx()))
            fillBar(ink, Offset(0f, top), Size(size.width * at.first, METER.toPx()), r.flag)
            at.second?.let { m -> drawTick(tick, size.width * m, size.height) }
        }
        r.mark?.let { mark -> TickLabel(mark.label, at.second ?: 0f) }
    }
}

/**
 * An amount against its limit (a budget's spent against its cap): ink up to the limit, the part
 * past it in the accent, the limit and the mark as ticks, the amount in words under it. A row
 * that needs attention is drawn in the accent whole; its status word says why.
 */
@Composable
internal fun StatusMeterBar(m: StatusMeter, status: RowStatus) {
    val g = CardGeometry.meter(m)
    val flagged = status == RowStatus.ATTENTION || status == RowStatus.FAILED
    val ink = if (flagged) SoftColors.caution else SoftColors.text
    val accent = SoftColors.caution
    val track = SoftColors.barRule
    val tick = SoftColors.text
    val meta = SoftColors.text2
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Canvas(Modifier.fillMaxWidth().height(METER_BOX).testTag(CARD_METER_TAG).clearAndSetSemantics {}) {
            val h = METER.toPx()
            val top = (size.height - h) / 2
            drawRect(track, Offset(0f, top), Size(size.width * g.limit, h))
            val inside = minOf(g.fill, g.limit)
            drawRect(ink, Offset(0f, top), Size(size.width * inside, h))
            if (g.over) drawRect(accent, Offset(size.width * g.limit, top), Size(size.width * (g.fill - g.limit), h))
            drawTick(tick, size.width * g.limit, size.height)
            g.mark?.let { drawTick(meta, size.width * it, size.height, dashed = true) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(CardPictureWords.meter(m), style = TypesetType.small, color = SoftColors.text)
            m.mark?.let { Text(it.label, style = TypesetType.smallItalic, color = SoftColors.text2) }
        }
    }
}

/** One whole in parts, each a share of the bar, hatched when they are estimates; words under it. */
@Composable
internal fun SplitBar(r: Reading) {
    val split = r.split ?: return
    val shares = CardGeometry.shares(split.parts.map { it.value })
    val ink = SoftColors.text
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        if (shares.isNotEmpty()) {
            Canvas(Modifier.fillMaxWidth().height(SPLIT).testTag(CARD_SPLIT_TAG).clearAndSetSemantics {}) {
                var x = 0f
                shares.forEachIndexed { i, share ->
                    val w = size.width * share - if (i < shares.lastIndex) GAP.toPx() else 0f
                    val tone = ink.copy(alpha = PART_TONES[i % PART_TONES.size])
                    if (w > 0f) fillBar(tone, Offset(x, 0f), Size(w, size.height), r.flag)
                    x += size.width * share
                }
            }
        }
        CardPictureWords.split(r)?.let { Text(it, style = TypesetType.small, color = SoftColors.text2) }
    }
}

/** A reading's pictures under its row: its bar on a scale, and its parts. */
@Composable
internal fun ReadingPictures(r: Reading) {
    ReadingMeter(r)
    SplitBar(r)
}

/** A rank on a named scale: [StatusLevel.of] squares, the first [StatusLevel.value] filled. */
@Composable
internal fun LevelStrip(level: StatusLevel, status: RowStatus) {
    val flagged = status == RowStatus.ATTENTION || status == RowStatus.FAILED
    val fill = if (flagged) SoftColors.caution else SoftColors.text
    val empty = SoftColors.barRule
    val words = CardPictureWords.level(level)
    Row(
        Modifier.padding(top = 6.dp).testTag(CARD_LEVEL_TAG).semantics { contentDescription = words },
        horizontalArrangement = Arrangement.spacedBy(GAP),
    ) {
        repeat(level.of) { i ->
            Canvas(Modifier.size(width = LEVEL_W, height = LEVEL_H)) {
                drawRect(if (i < level.value) fill else empty)
            }
        }
    }
}

/** The mark before a status row's name: ink dot for OK, accent for attention, a ring when unknown. */
@Composable
internal fun StatusMark(status: RowStatus) {
    val ink = SoftColors.text
    val accent = SoftColors.caution
    val meta = SoftColors.text2
    Canvas(Modifier.padding(top = 8.dp, end = 10.dp).size(DOT_BOX).clearAndSetSemantics {}) {
        val r = size.minDimension / 2
        when (status) {
            RowStatus.OK -> drawCircle(ink, r)
            RowStatus.ATTENTION, RowStatus.FAILED -> drawCircle(accent, r)
            RowStatus.UNKNOWN, RowStatus.NOT_REACHED ->
                drawCircle(meta, r - RING.toPx() / 2, style = Stroke(RING.toPx()))
        }
    }
}

/** A mark's label under its tick, kept inside the card's width. */
@Composable
private fun TickLabel(label: String, at: Float) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = maxWidth
        val alignRight = at > HALF
        Box(Modifier.fillMaxWidth()) {
            Text(
                label,
                style = TypesetType.smallItalic,
                color = SoftColors.text2,
                textAlign = if (alignRight) TextAlign.End else TextAlign.Start,
                modifier = if (alignRight) {
                    Modifier.fillMaxWidth().padding(end = width * (1f - at))
                } else {
                    Modifier.offset(x = width * at)
                },
            )
        }
    }
}

/**
 * A bar's fill: solid ink for a stated figure; for an estimate or an unverified figure, the ink as
 * 45 degree hatching over a pale wash, so a guess never looks like a measurement.
 */
internal fun DrawScope.fillBar(ink: Color, at: Offset, size: Size, flag: CardFlag?) {
    if (size.width <= 0f) return
    if (flag == null) {
        drawRect(ink, at, size)
        return
    }
    drawRect(ink.copy(alpha = WASH), at, size)
    clipRect(at.x, at.y, at.x + size.width, at.y + size.height) {
        val step = HATCH.toPx()
        var x = at.x - size.height
        while (x < at.x + size.width) {
            drawLine(ink, Offset(x, at.y + size.height), Offset(x + size.height, at.y), strokeWidth = HATCH_W.toPx())
            x += step
        }
    }
}

/** A reference tick across a bar, the full height of its box. */
internal fun DrawScope.drawTick(color: Color, x: Float, height: Float, dashed: Boolean = false) {
    val w = TICK.toPx()
    if (!dashed) {
        drawRect(color, Offset(x - w / 2, 0f), Size(w, height))
        return
    }
    val seg = height / DASHES
    for (i in 0 until DASHES step 2) drawRect(color, Offset(x - w / 2, seg * i), Size(w, seg))
}

private val METER = 8.dp
private val METER_BOX = 16.dp
private val SPLIT = 14.dp
private val GAP: Dp = 2.dp
private val TICK = 2.dp
private val HATCH = 4.dp
private val HATCH_W = 1.25.dp
private val LEVEL_W = 18.dp
private val LEVEL_H = 8.dp
private val DOT_BOX = 10.dp
private val RING = 1.5.dp
private const val WASH = 0.12f
private const val HALF = 0.5f
private const val DASHES = 5
private const val TONE_1 = 1f
private const val TONE_2 = 0.62f
private const val TONE_3 = 0.34f
private const val TONE_4 = 0.18f
private val PART_TONES = floatArrayOf(TONE_1, TONE_2, TONE_3, TONE_4)
