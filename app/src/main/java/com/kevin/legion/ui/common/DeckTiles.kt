package com.kevin.legion.ui.common

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics

/**
 * The HALF-tile family: [EqualHeightRow] + [HalfTile], mission-control ticket 16's HOME build
 * (`TodayScreen.kt`'s original BIO/CRED/FLEET/LOG row). **Moved here by ticket 16's BIO/FLEET
 * build** so the two of them can reuse the exact same shell rather than duplicating it - the
 * ticket's own "reuse `EqualHeightRow`/`HalfTile`, move them to `ui/common/` if they need to be
 * shared" instruction, taken literally. [TodayScreen] keeps calling these, unchanged, through this
 * import instead of its own former `private` copies.
 */

/** Two independent subcompositions of the same content - [EqualHeightRow]'s own slot ids, private to it. */
private enum class EqualHeightRowSlot { PROBE, REAL }

/**
 * A row of equal-width, equal-height children - the mechanism [HalfTile]'s BIO/CRED/FLEET/LOG row
 * needs (ticket 05's grammar treats HALF as one shape, not two shapes that happen to sit side by
 * side), built on [androidx.compose.ui.layout.SubcomposeLayout] rather than a plain custom
 * [androidx.compose.ui.layout.Layout] or `Row(Modifier.height(IntrinsicSize.Min))`.
 *
 * **Two earlier attempts both crashed the app on every launch, each caught only in `dumpsys
 * dropbox` - not by eye, not by the compile/unit suite, neither of which runs a real Compose
 * layout pass:**
 * 1. `Row(Modifier.height(IntrinsicSize.Min))` + `Modifier.weight(1f).fillMaxHeight()` on each
 *    tile: `IllegalStateException: Asking for intrinsic measurements of SubcomposeLayout layouts
 *    is not supported`. [DeckPane] wraps [androidx.compose.foundation.layout.BoxWithConstraints]
 *    for its label-pill max-width sizing (see that composable's own doc comment), and
 *    `BoxWithConstraints` is itself built on `SubcomposeLayout` - Compose refuses an
 *    intrinsic-measurement query (`minIntrinsicHeight`, exactly what `IntrinsicSize.Min` asks
 *    every child for) against anything built on `SubcomposeLayout`. `LazyColumn`/`TabRow` carry
 *    the identical restriction for the identical reason.
 * 2. A plain custom [androidx.compose.ui.layout.Layout] measuring each `Measurable` TWICE (a loose
 *    pass to learn the tallest child, then a fixed pass to force every child to that height):
 *    `IllegalStateException: measure() may not be called multiple times on the same Measurable`.
 *    Real, not an intrinsics-only rule - a `Measurable` a `Layout` receives can be measured
 *    exactly once per layout pass, full stop.
 *
 * **What actually works, and is Compose's own documented answer to this exact problem:**
 * subcompose the SAME [content] TWICE, under two different slot ids, rather than measuring one
 * subcomposition's `Measurable`s twice. [EqualHeightRowSlot.PROBE]'s placeables are measured at a
 * loose height to learn the tallest child and then discarded (never placed, so nothing from this
 * pass ever draws); [EqualHeightRowSlot.REAL] is a SEPARATE subcomposition - genuinely different
 * `Measurable` instances - measured at the tallest height from the probe pass and actually placed.
 * Each `Measurable`, across either subcomposition, is still measured exactly once, so rule 2 above
 * is never violated; no intrinsics API is ever called, so rule 1 above is never violated either.
 * The cost is composing the row's content twice per layout pass (the probe copy is thrown away) -
 * real but small, and paid once per layout pass, not per recomposition of unrelated state
 * elsewhere on the screen.
 *
 * [DeckPane]'s own inner `Column` carries a matching `.fillMaxHeight()` (mission-control ticket 16
 * follow-up) so it actually stretches its drawn border/background to fill the forced height,
 * rather than the fixed constraint being satisfied by empty space Compose adds around a
 * wrap-content child.
 */
@Composable
fun EqualHeightRow(
    modifier: Modifier = Modifier,
    horizontalGap: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    SubcomposeLayout(modifier) { constraints ->
        val gapPx = horizontalGap.roundToPx()

        // PROBE: a throwaway subcomposition, measured once at a loose height purely to learn how
        // tall the tallest child wants to be at its real final width. Never placed below.
        val probeMeasurables = subcompose(EqualHeightRowSlot.PROBE, content)
        val childCount = probeMeasurables.size
        val totalGapPx = gapPx * (childCount - 1).coerceAtLeast(0)
        val childWidthPx = ((constraints.maxWidth - totalGapPx) / childCount.coerceAtLeast(1)).coerceAtLeast(0)
        val probeConstraints = Constraints(minWidth = childWidthPx, maxWidth = childWidthPx, minHeight = 0, maxHeight = Constraints.Infinity)
        val maxHeightPx = probeMeasurables.maxOfOrNull { it.measure(probeConstraints).height } ?: 0

        // REAL: an independent second subcomposition of the identical content, measured at the
        // shared fixed height the probe pass found, and actually placed.
        val fixedConstraints = Constraints(minWidth = childWidthPx, maxWidth = childWidthPx, minHeight = maxHeightPx, maxHeight = maxHeightPx)
        val placeables = subcompose(EqualHeightRowSlot.REAL, content).map { it.measure(fixedConstraints) }

        layout(constraints.maxWidth, maxHeightPx) {
            var x = 0
            placeables.forEach { placeable ->
                placeable.placeRelative(x, 0)
                x += childWidthPx + gapPx
            }
        }
    }
}

/**
 * One HALF tile (ticket 05's tiling grammar, ticket 16's first real caller): a [DeckPane] sized to
 * half the interior width by the enclosing [EqualHeightRow]'s own layout (NOT a bare
 * `Modifier.weight(1f)` inside a plain `Row` - see that composable's doc for why), holding
 * [HalfTileHero] above [HalfTileCaption] above whatever [extra] the caller supplies (HOME's CRED
 * tile sparkline; BIO's INTAKE/SLEEP tiles and FLEET's DRIVES tile reuse the same slot). Every
 * caller shares this one shell so a surface's half tiles read as one family, not hand-rolled panels
 * - `DeckPanels.kt`'s own vocabulary ([DeckPane]/[DeckSparkline]) does the actual drawing, this only
 * fixes their arrangement.
 */
@Composable
fun HalfTile(
    header: String,
    hero: String,
    caption: String,
    modifier: Modifier = Modifier,
    secondHero: String? = null,
    secondCaption: String? = null,
    extra: @Composable () -> Unit = {},
) {
    DeckPane(header = header, modifier = modifier, stretchToParentHeight = true) {
        HalfTileHero(hero)
        HalfTileCaption(caption)
        // A SECOND figure in the same grammar, not a smaller stamp line under the first (Kevin,
        // 2026-08-18, of HOME's CRED tile: "2 figures, 2 subtitles"). Drawing the second one in
        // `extra` with stamp type would have made it read as a footnote to the first rather than
        // its equal, which is the opposite of what a spend figure and a balance are to each other.
        // Optional, so every other caller renders exactly as before.
        if (secondHero != null) {
            HalfTileHero(secondHero)
            if (secondCaption != null) HalfTileCaption(secondCaption)
        }
        extra()
    }
}

/**
 * A HALF tile's hero figure. Ticket 05's grammar names 30sp/7-characters as the half-tile hero
 * budget; this app's real type scale (`ui/theme/Type.kt`) never landed on that literal size (see
 * that file's own "steps down roughly 10%" doc comment), so [MaterialTheme.typography.displayMedium]
 * (27sp) is read as the closest real role to ticket 05's "30sp" - its mono advance at 141dp of
 * content width still clears 7 characters with margin (checked, not eyeballed: 27sp * 0.6em ≈
 * 16.2dp/char, 7 chars ≈ 113dp against 141dp available).
 *
 * A hero longer than 7 characters steps down to [MaterialTheme.typography.displaySmall] (22sp)
 * rather than wrapping or clipping - ticket 05's own "never let a number ellipsize", read onto a
 * half tile. `TextOverflow.Visible` is redundant insurance on top of the step-down, not the
 * primary defence - the step-down is what actually keeps the string on one line at these widths.
 */
@Composable
private fun HalfTileHero(text: String) {
    val sem = LocalLegionSemantics.current
    val style = if (text.length <= 7) MaterialTheme.typography.displayMedium else MaterialTheme.typography.displaySmall
    Text(
        text,
        style = style,
        // A HALF tile's hero is a reading, mint like every other value in the app (ticket 01's
        // "mint is every value, amber is every highlight") - matches [DeckRow]'s own `value` slot,
        // which is mint unconditionally even for a text state like "NOT LOGGED"/"NO LINK" (see
        // that composable's own doc comment on the ticket 03 amber-to-mint move). Coordinator
        // caught this reading MaterialTheme.colorScheme.primary (amber) instead - sampled at
        // #FFBA1F on device across all four real figures.
        color = sem.data,
        maxLines = 1,
        overflow = TextOverflow.Visible,
        // softWrap = false is load-bearing, not decoration. Compose's default `softWrap = true`
        // wraps at the space in a two-word hero like "NOT LOGGED" BEFORE `overflow`/`maxLines` ever
        // get a say - the wrap happens first, "LOGGED" lands on a second line, and `maxLines = 1`
        // then hides that whole second line. The result reads as "NOT" with the rest silently gone,
        // which is exactly the "let a number ellipsize" failure ticket 05 forbids, just via a
        // wrap instead of an ellipsis glyph. Caught on-device (mission-control ticket 16's BIO
        // build): BIO's INTAKE/SLEEP tiles were the first real callers to ever render a HALF tile's
        // "NOT LOGGED" empty-state hero - HOME's own CRED/BIO/LOG tiles carry the identical string
        // but nobody had screenshotted a fresh-install HOME to notice. `softWrap = false` forces
        // the whole string onto its one allowed line, so `overflow = TextOverflow.Visible` is what
        // actually gets to decide what happens past the tile's own width, as originally intended.
        softWrap = false,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** A HALF tile's qualifier line - unlike [HalfTileHero], this is a description, not a value, so it
 * truncates like [DeckRow]'s own label column rather than being held to the 7-character rule. */
@Composable
private fun HalfTileCaption(text: String) {
    val sem = LocalLegionSemantics.current
    Text(
        text.uppercase(),
        style = LegionType.stamp,
        color = sem.faint,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
    )
}
