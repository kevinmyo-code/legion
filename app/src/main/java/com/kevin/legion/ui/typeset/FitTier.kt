package com.kevin.legion.ui.typeset

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Text
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * How much the front page gives up to fit one screen with no scroll (Kevin, 2026-11-10: "scale to
 * fit and no scrolling"). Tiers run roomiest first; [FitTiers] takes the first whose fixed parts
 * fit, and the time headline takes whatever height is left, shrinking to fit it. The order of what
 * goes is the brief's: spacing, then secondary lines, then the big figures (inline, smaller), then
 * the orb and the dock's names, and last the pinned tiles themselves (they stay in All apps).
 */
internal enum class FitTier(
    val gap: Dp,
    val figureLines: Int,
    val stacked: Boolean,
    val dateline: Boolean,
    val orb: Dp,
    val statusOwnLine: Boolean,
    val dockTiles: Boolean,
    val dockNames: Boolean,
) {
    ROOMY(
        gap = 12.dp,
        figureLines = 2,
        stacked = true,
        dateline = true,
        orb = 96.dp,
        statusOwnLine = true,
        dockTiles = true,
        dockNames = true,
    ),
    SNUG(
        gap = 8.dp,
        figureLines = 1,
        stacked = true,
        dateline = true,
        orb = 88.dp,
        statusOwnLine = true,
        dockTiles = true,
        dockNames = true,
    ),
    TRIM(
        gap = 6.dp,
        figureLines = 0,
        stacked = true,
        dateline = true,
        orb = 80.dp,
        statusOwnLine = true,
        dockTiles = true,
        dockNames = true,
    ),
    INLINE(
        gap = 4.dp,
        figureLines = 0,
        stacked = false,
        dateline = true,
        orb = 72.dp,
        statusOwnLine = true,
        dockTiles = true,
        dockNames = false,
    ),
    TIGHT(
        gap = 2.dp,
        figureLines = 0,
        stacked = false,
        dateline = false,
        orb = 56.dp,
        statusOwnLine = false,
        dockTiles = true,
        dockNames = false,
    ),
    FLOOR(
        gap = 0.dp,
        figureLines = 0,
        stacked = false,
        dateline = false,
        orb = 56.dp,
        statusOwnLine = false,
        dockTiles = false,
        dockNames = false,
    ),
}

/**
 * Lays [content] out at the roomiest [FitTier] that fits the height it is given. Each tier is first
 * composed with `measuring = true` (the flexible slot drawn as its minimum, no orb animation) and
 * measured at unbounded height; the chosen tier is then composed for real at the exact height, where
 * the flexible slot takes `weight(1f)`. Unbounded height (a scrolling parent) draws the roomiest
 * tier in its measuring shape, so nothing asks a weight of infinity.
 */
@Composable
internal fun FitTiers(modifier: Modifier = Modifier, content: @Composable (tier: FitTier, measuring: Boolean) -> Unit) {
    SubcomposeLayout(modifier) { c ->
        if (!c.hasBoundedHeight) {
            val ps = subcompose("open") { content(FitTier.ROOMY, true) }.map { it.measure(c) }
            val h = ps.maxOfOrNull { it.height } ?: 0
            return@SubcomposeLayout layout(c.maxWidth, h) { ps.forEach { it.place(0, 0) } }
        }
        val loose = Constraints(minWidth = c.minWidth, maxWidth = c.maxWidth)
        val chosen = FitTier.entries.firstOrNull { tier ->
            subcompose(tier) { content(tier, true) }.sumOf { it.measure(loose).height } <= c.maxHeight
        } ?: FitTier.FLOOR
        val exact = c.copy(minHeight = c.maxHeight)
        val ps = subcompose("final") { content(chosen, false) }.map { it.measure(exact) }
        layout(c.maxWidth, c.maxHeight) { ps.forEach { it.place(0, 0) } }
    }
}

/**
 * One line that never wraps: shrinks in steps from [style]'s size down to [min] to fit its width
 * (and height, where the parent bounds it). The money figure and the time use it (Kevin,
 * 2026-11-10: "make it scale to fit").
 */
@Composable
internal fun FitLine(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    min: TextUnit = 12.sp,
    align: TextAlign = TextAlign.Start,
) {
    BasicText(
        text,
        modifier = modifier,
        style = style.copy(color = color, textAlign = align, lineHeight = scaledLineHeight(style)),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        autoSize = TextAutoSize.StepBased(minFontSize = min, maxFontSize = style.fontSize, stepSize = 0.5.sp),
    )
}

/** A line height in sp becomes the same ratio in em, so it shrinks with the autosized font. */
private fun scaledLineHeight(style: TextStyle): TextUnit =
    if (style.lineHeight.isSp && style.fontSize.isSp) {
        (style.lineHeight.value / style.fontSize.value).em
    } else {
        style.lineHeight
    }

/** A text control: a 48dp target with a button role and its words as the label. */
internal fun Modifier.tap(label: String, onClick: () -> Unit): Modifier =
    this.heightIn(min = TapTarget).clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
        .wrapContentHeight()
        .padding(horizontal = 2.dp)

/** The smallest tap target on the Typeset front page. */
internal val TapTarget = 48.dp

/** One line, ellipsised: a figure's label or line, a dock tile's name. */
@Composable
internal fun OneLine(text: String, style: TextStyle, color: Color) {
    Text(text, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
