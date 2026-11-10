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
 * goes: spacing, secondary lines, the big figures (inline) and the dock's names, all before the hero
 * orb gives up size; then the orb shrinks step by step, then the pinned tiles go (they stay in All
 * apps), and last the contents line packs seven to a row (two rows, not three). The shell's StatusLine sits above
 * the page since 2026-11-10 and can wrap to two lines on a narrow phone at a large font; [PACKED]
 * is what keeps the time readable there.
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
    val contentsPerRow: Int = 5,
) {
    // The orb is the hero (Kevin, 2026-11-10: "about 2.5x the size of what it is now", 96dp then):
    // the first tiers give up spacing and secondary lines before the orb gives up any size.
    // Columns: gap, figureLines, stacked, dateline, orb, statusOwnLine, dockTiles, dockNames.
    ROOMY(12.dp, 2, true, true, HERO, true, true, true),
    SNUG(8.dp, 1, true, true, HERO, true, true, true),
    TRIM(6.dp, 0, true, true, HERO, true, true, true),
    INLINE(4.dp, 0, false, true, HERO, true, true, false),
    // Still the full hero: the dateline goes and the state words share a line with "Type".
    HERO_FIT(2.dp, 0, false, false, HERO, false, true, false),
    // Still the full hero: the contents line packs seven to a row.
    HERO_PACK(2.dp, 0, false, false, HERO, false, true, false, contentsPerRow = 7),
    LARGE(4.dp, 0, false, true, 200.dp, true, true, false),
    MEDIUM(2.dp, 0, false, false, 160.dp, true, true, false),
    SMALL(2.dp, 0, false, false, 128.dp, false, true, false),
    TIGHT(2.dp, 0, false, false, 96.dp, false, true, false),
    FLOOR(0.dp, 0, false, false, 80.dp, false, false, false),
    PACKED(
        0.dp,
        0,
        stacked = false,
        dateline = false,
        orb = 64.dp,
        statusOwnLine = false,
        dockTiles = false,
        dockNames = false,
        contentsPerRow = 7,
    ),
}

/** The hero orb: 2.5x the 96dp it was (Kevin, 2026-11-10). */
internal val HERO = 240.dp

/**
 * Lays [content] out at the roomiest [FitTier] that fits the height it is given. Each tier is first
 * composed with `measuring = true` (the flexible slot drawn as its minimum, no orb animation) and
 * measured at unbounded height; the chosen tier is then composed for real at the exact height, where
 * the flexible slot takes `weight(1f)`. Height the chosen tier leaves over goes to the orb first, up
 * to [HERO] (Kevin, 2026-11-10: the orb is the hero), then to the flexible slot; [content] gets the
 * orb size to draw. Unbounded height (a scrolling parent) draws the roomiest tier in its measuring
 * shape, so nothing asks a weight of infinity.
 */
@Composable
internal fun FitTiers(
    modifier: Modifier = Modifier,
    content: @Composable (tier: FitTier, orb: Dp, measuring: Boolean) -> Unit,
) {
    SubcomposeLayout(modifier) { c ->
        if (!c.hasBoundedHeight) {
            val first = FitTier.entries.first()
            val ps = subcompose("open") { content(first, first.orb, true) }.map { it.measure(c) }
            val h = ps.maxOfOrNull { it.height } ?: 0
            return@SubcomposeLayout layout(c.maxWidth, h) { ps.forEach { it.place(0, 0) } }
        }
        val loose = Constraints(minWidth = c.minWidth, maxWidth = c.maxWidth)
        var used = 0
        val chosen = FitTier.entries.firstOrNull { tier ->
            used = subcompose(tier) { content(tier, tier.orb, true) }.sumOf { it.measure(loose).height }
            used <= c.maxHeight
        } ?: FitTier.entries.last()
        val spare = (c.maxHeight - used).coerceAtLeast(0).toDp()
        val orb = (chosen.orb + spare).coerceAtMost(maxOf(HERO, chosen.orb))
        val exact = c.copy(minHeight = c.maxHeight)
        val ps = subcompose("final") { content(chosen, orb, false) }.map { it.measure(exact) }
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
