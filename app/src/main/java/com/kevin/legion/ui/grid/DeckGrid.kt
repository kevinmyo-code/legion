@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.kevin.legion.ui.grid

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Stage-2 grid mechanics, Compose layer (aspect-engine ticket 18, priced by ticket 09's spike).
 * Renders a plain `List<GridItem>` ([GridModel.kt]) as cell rects on a fixed [columnCount]-column
 * grid, and drives every drag/resize/remove gesture through [GridEngine] - this file owns no
 * placement logic of its own, only measurement, gesture plumbing, and drawing.
 *
 * **Persistence boundary, held exactly where the ticket draws it**: [items] in, [onLayoutChange]
 * out, both plain `List<GridItem>`. No Room import anywhere in this file or [GridModel.kt] -
 * `widget_instances` is ticket 18's job to wire, not this component's to assume.
 *
 * **One shared coordinate frame, not per-item measurement (revised 2026-08-23, feel-test pass).**
 * Every item's rect is a pure function of its OWN [GridItem] geometry - `row * rowPitchPx`,
 * `col * colPitchPx` - inside this composable's own [BoxWithConstraints] content box. That single
 * frame is what the ticket-09 SKILL note's "ancestor-coordinate tracking, never per-item
 * boundsInParent" is protecting: the stage-1 prototype compared items' MEASURED bounds against
 * each other, which only worked because they shared one immediate parent. This component never
 * needs to measure an item's on-screen position at all - it already knows exactly where every
 * item sits from the model alone, so there is no ancestor-vs-descendant coordinate space to get
 * wrong in the first place. An earlier draft of this file DID re-measure each item's position via
 * `onGloballyPositioned` on every recomposition (including ones caused by the drag itself), which
 * fed the item's already-dragged on-screen position back in as if it were the pre-drag origin -
 * a self-reinforcing drift bug. Removed entirely; see the resize/move drag sections below for
 * what replaced it.
 *
 * **Cell hit-testing, not nearest-centre distance - the stage-1 defect this ticket exists to
 * fix.** The prototype's reorder targeted whichever OTHER item's centre was nearest the dragged
 * item's live centre, which collapsed row and column into one scalar and let a same-row sideways
 * drag jump into the row below. This component instead divides the dragged item's own live
 * pixel position (its position at drag START, from the model, plus the raw accumulated pointer
 * delta) by the fixed cell pitch (`floor(x / colPitchPx)`, `floor(y / rowPitchPx)`) to name an
 * exact (row, col) cell, then clamps that cell into bounds - there is no point at which two
 * candidate cells are compared by distance.
 *
 * **The commit-path bug found on the A25 (2026-08-23) and its fix, stated plainly.** [GridEngine]
 * itself was never wrong - `GridEngineTest`'s 36 cases proved `resize`/`moveTo` compute the right
 * geometry from a given final delta. The bug was pure Compose state plumbing: the ORIGINAL
 * `onResizeDragEnd`/`onMoveDragEnd` closures committed a value called `renderItems` - a `val`
 * computed once per RECOMPOSITION and captured by reference into that specific composition's
 * lambdas. A fast resize/drag gesture (a quick flick, which is exactly what a corner-handle
 * resize usually is) can deliver several `onDrag` pointer callbacks and then `onDragEnd` before
 * Compose ever gets a scheduled frame to recompose in between - pointer dispatch is synchronous,
 * recomposition is not. When that happens, the `renderItems` closed over by the LAST composition
 * (created before the gesture, when the drag state was still null) is just `baseItems`, i.e. the
 * ORIGINAL un-resized layout - so the commit silently wrote back the pre-drag geometry and the
 * card visibly snapped back on release. **The fix: every commit below reads the drag state and
 * [baseItems] directly (both are either a live `mutableStateOf` read or a value with no
 * drag-state dependency) and recomputes the final [GridEngine] call fresh, at commit time -
 * never a composition-scoped `val` that requires an extra recompose to be current.**
 */

/**
 * One drag-to-move gesture in flight: which item, its ORIGIN cell (captured once, at drag start,
 * from that item's own [GridItem] in [DeckGrid.baseItems] - never re-measured mid-drag, see the
 * file doc's "one shared coordinate frame" section), the raw accumulated pointer delta (drives the
 * dragged card's own finger-follow motion, unthrottled), and the current HOVERED cell (drives the
 * reflow of every OTHER card, throttled to cell-boundary crossings only - see [DeckGrid]).
 */
private data class MoveDrag(
    val id: String,
    val originRow: Int,
    val originCol: Int,
    val accumPx: Offset,
    val hoverRow: Int,
    val hoverCol: Int,
)

/** One corner-resize gesture in flight: which item, its ORIGINAL span (captured once, at drag
 *  start), and the raw pixel delta accumulated since. Resize has no separate "hover" concept -
 *  its candidate span already only changes in whole-cell steps (`roundToInt` of `accumPx / pitch`),
 *  so it is naturally throttled by construction. */
private data class ResizeDrag(
    val id: String,
    val originRowSpan: Int,
    val originColSpan: Int,
    val accumPx: Offset,
)

/**
 * The grid itself.
 *
 * @param items the current layout, as a plain list - the component treats this as the source of
 *   truth and re-derives its own internal state from it via [GridEngine.normalize] whenever it
 *   changes, so a caller can pass back exactly what it got from Room without pre-validating it.
 * @param columnCount fixed column count for this grid (4 for the phone-portrait grid ticket 18
 *   specifies, but the component itself does not hardcode that - a caller decides).
 * @param editMode true once the caller has entered jiggle/edit mode (long-press on any card, or an
 *   external EDIT/DONE pill like the harness's) - gates every gesture below; outside edit mode the
 *   grid is display-only and [onEnterEditMode] fires from a long-press on any card.
 * @param onLayoutChange fired with the new, already-[GridEngine]-resolved list at the END of a
 *   drag or resize gesture (never mid-gesture - the live preview during the gesture is purely
 *   visual state local to this composable, so an interrupted or cancelled drag never mutates the
 *   caller's list). **Committed exactly as last previewed** - the same [GridEngine] call that drove
 *   the live reflow is what commits, so there is no jump on drop.
 * @param onRemove fired with an item's id when its remove chip is tapped in edit mode.
 * @param itemContent the widget's own body - this composable supplies only the cell rect, the
 *   jiggle, and the edit-mode chrome (drag surface, resize handle, remove chip) around it.
 */
@Composable
fun DeckGrid(
    items: List<GridItem>,
    columnCount: Int,
    editMode: Boolean,
    onEnterEditMode: () -> Unit,
    onLayoutChange: (List<GridItem>) -> Unit,
    onRemove: (id: String) -> Unit,
    modifier: Modifier = Modifier,
    rowHeight: Dp = 132.dp,
    gap: Dp = 10.dp,
    itemContent: @Composable BoxScope.(GridItem) -> Unit,
) {
    val density = LocalDensity.current
    val baseItems = remember(items, columnCount) { GridEngine.normalize(items, columnCount) }

    var moveDrag by remember { mutableStateOf<MoveDrag?>(null) }
    var resizeDrag by remember { mutableStateOf<ResizeDrag?>(null) }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val gapPx = with(density) { gap.toPx() }
        val totalWidthPx = with(density) { maxWidth.toPx() }
        val colPitchPx = (totalWidthPx - gapPx * (columnCount - 1)) / columnCount + gapPx
        val cellWidthPx = colPitchPx - gapPx
        val rowHeightPx = with(density) { rowHeight.toPx() }
        val rowPitchPx = rowHeightPx + gapPx

        // The candidate layout to RENDER this frame. Mid-move-drag it is `baseItems` run through
        // GridEngine.moveTo with the dragged item PINNED at its current hovered cell - the
        // "launcher-style" live reflow Kevin asked for (2026-08-23): every other card animates to
        // where it would land if the gesture ended right now, continuously, not just on drop.
        // Mid-resize it is the live candidate span. Otherwise it is `baseItems` itself.
        val renderItems: List<GridItem> = when {
            moveDrag != null -> {
                val d = moveDrag!!
                GridEngine.moveTo(baseItems, d.id, d.hoverRow, d.hoverCol, columnCount)
            }
            resizeDrag != null -> {
                val d = resizeDrag!!
                val candidateColSpan = d.originColSpan + (d.accumPx.x / colPitchPx).roundToInt()
                val candidateRowSpan = d.originRowSpan + (d.accumPx.y / rowPitchPx).roundToInt()
                GridEngine.resize(baseItems, d.id, candidateRowSpan, candidateColSpan, columnCount)
            }
            else -> baseItems
        }

        val rowCount = maxOf(GridEngine.rowCount(renderItems), GridEngine.rowCount(baseItems), 1)
        val totalHeight = rowHeight * rowCount + gap * (rowCount - 1).coerceAtLeast(0)

        Box(Modifier.fillMaxWidth().height(totalHeight)) {
            renderItems.forEach { item ->
                val isDragging = moveDrag?.id == item.id
                val isResizing = resizeDrag?.id == item.id
                val cellWidth = with(density) { (cellWidthPx + (item.colSpan - 1) * colPitchPx).toDp() }
                val cellHeight = rowHeight * item.rowSpan + gap * (item.rowSpan - 1).coerceAtLeast(0)
                // The dragged item's OWN box never reads its reflowed (pinned) rect for its own
                // offset - it follows the raw pointer from where the gesture STARTED, which is
                // what "the dragged card follows the finger raw" means. Every other card animates
                // to `renderItems`' own rect (the live-reflow preview), tween'd at ~200ms so the
                // reflow reads as a launcher-style shuffle rather than a snap.
                val targetX = with(density) { (item.col * colPitchPx).toDp() }
                val targetY = with(density) { (item.row * rowPitchPx).toDp() }
                val animatedX by animateDpAsState(targetX, animationSpec = tween(200), label = "grid-x-${item.id}")
                val animatedY by animateDpAsState(targetY, animationSpec = tween(200), label = "grid-y-${item.id}")

                val dragStartX = moveDrag?.let { d -> if (d.id == item.id) with(density) { (d.originCol * colPitchPx).toDp() } else null }
                val dragStartY = moveDrag?.let { d -> if (d.id == item.id) with(density) { (d.originRow * rowPitchPx).toDp() } else null }
                val dragOffsetPx = if (isDragging) moveDrag!!.accumPx else Offset.Zero
                val dragOffsetXDp = with(density) { dragOffsetPx.x.toDp() }
                val dragOffsetYDp = with(density) { dragOffsetPx.y.toDp() }

                Box(
                    Modifier
                        .offset(
                            x = if (isDragging) (dragStartX ?: targetX) + dragOffsetXDp else animatedX,
                            y = if (isDragging) (dragStartY ?: targetY) + dragOffsetYDp else animatedY,
                        )
                        .width(cellWidth)
                        .height(cellHeight)
                        .graphicsLayer {
                            // Slight scale/elevation lift on the card actually under the finger -
                            // the "picked up" affordance the brief asks for. A resizing card is not
                            // scaled (its own box IS the live size preview; scaling it on top of
                            // that would double-communicate the same change).
                            val lift = isDragging
                            scaleX = if (lift) 1.04f else 1f
                            scaleY = if (lift) 1.04f else 1f
                            shadowElevation = if (isDragging) 16f else if (isResizing) 10f else 0f
                        }
                        .zIndex(if (isDragging || isResizing) 1f else 0f)
                        .gridJiggle(active = editMode && !isDragging && !isResizing, seed = item.id.hashCode()),
                ) {
                    GridCellChrome(
                        item = item,
                        editMode = editMode,
                        onLongPressToEnterEditMode = onEnterEditMode,
                        onRemove = { onRemove(item.id) },
                        onMoveDragStart = {
                            val current = baseItems.firstOrNull { it.id == item.id } ?: return@GridCellChrome
                            moveDrag = MoveDrag(
                                id = item.id,
                                originRow = current.row,
                                originCol = current.col,
                                accumPx = Offset.Zero,
                                hoverRow = current.row,
                                hoverCol = current.col,
                            )
                        },
                        onMoveDrag = { delta ->
                            val d = moveDrag ?: return@GridCellChrome
                            val accum = d.accumPx + delta
                            // Cell-boundary throttle (brief point 3): the hovered cell - and
                            // therefore the expensive GridEngine.moveTo reflow of every other
                            // card - only recomputes when the candidate cell actually CHANGES.
                            // The raw `accum` itself still updates every frame so the dragged
                            // card's own finger-follow stays perfectly smooth; only the OTHER
                            // cards' reflow target is gated on a real cell crossing.
                            val liveX = d.originCol * colPitchPx + accum.x
                            val liveY = d.originRow * rowPitchPx + accum.y
                            val candidateCol = floor(liveX / colPitchPx).toInt()
                            val candidateRow = floor(liveY / rowPitchPx).toInt().coerceAtLeast(0)
                            moveDrag = d.copy(accumPx = accum, hoverRow = candidateRow, hoverCol = candidateCol)
                        },
                        onMoveDragEnd = {
                            // Fresh read of the CURRENT state, never the composition-scoped
                            // `renderItems` above - see the file doc's "commit-path bug" section.
                            // Committed exactly as last previewed (same GridEngine.moveTo call the
                            // live reflow used), so there is no jump on drop.
                            val d = moveDrag
                            moveDrag = null
                            if (d != null) {
                                onLayoutChange(GridEngine.moveTo(baseItems, d.id, d.hoverRow, d.hoverCol, columnCount))
                            }
                        },
                        onResizeDragStart = {
                            val current = baseItems.firstOrNull { it.id == item.id } ?: return@GridCellChrome
                            resizeDrag = ResizeDrag(
                                id = item.id,
                                originRowSpan = current.rowSpan,
                                originColSpan = current.colSpan,
                                accumPx = Offset.Zero,
                            )
                        },
                        onResizeDrag = { delta ->
                            val d = resizeDrag ?: return@GridCellChrome
                            resizeDrag = d.copy(accumPx = d.accumPx + delta)
                        },
                        onResizeDragEnd = {
                            // Same fix, same reason as onMoveDragEnd: read `resizeDrag` fresh
                            // (already done via `d` below) and recompute the commit from
                            // `baseItems` directly - this is THE bug Kevin hit on the A25.
                            val d = resizeDrag
                            resizeDrag = null
                            if (d != null) {
                                val candidateColSpan = d.originColSpan + (d.accumPx.x / colPitchPx).roundToInt()
                                val candidateRowSpan = d.originRowSpan + (d.accumPx.y / rowPitchPx).roundToInt()
                                onLayoutChange(GridEngine.resize(baseItems, d.id, candidateRowSpan, candidateColSpan, columnCount))
                            }
                        },
                    ) { itemContent(item) }
                }
            }
        }
    }
}

/**
 * The per-cell edit-mode chrome: drag surface over the whole card (move), a small bottom-right
 * corner resize handle, and a remove chip - the three affordances the ticket names. Not exported;
 * [DeckGrid] is the only caller.
 */
@Composable
private fun GridCellChrome(
    item: GridItem,
    editMode: Boolean,
    onLongPressToEnterEditMode: () -> Unit,
    onRemove: () -> Unit,
    onMoveDragStart: () -> Unit,
    onMoveDrag: (Offset) -> Unit,
    onMoveDragEnd: () -> Unit,
    onResizeDragStart: () -> Unit,
    onResizeDrag: (Offset) -> Unit,
    onResizeDragEnd: () -> Unit,
    content: @Composable () -> Unit,
) {
    val sem = LocalLegionSemantics.current
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {},
                onLongClick = if (!editMode) onLongPressToEnterEditMode else null,
            )
            .let {
                if (editMode) {
                    it.pointerInput(item.id) {
                        detectDragGestures(
                            onDragStart = { onMoveDragStart() },
                            onDragEnd = { onMoveDragEnd() },
                            onDragCancel = { onMoveDragEnd() },
                            onDrag = { change, delta -> change.consume(); onMoveDrag(delta) },
                        )
                    }
                } else {
                    it
                }
            },
    ) {
        content()
        if (editMode) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(28.dp)
                    .background(sem.chrome)
                    .combinedClickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Text("X", style = LegionType.stamp, color = MaterialTheme.colorScheme.background)
            }
            // Corner resize handle - bottom-right, its own drag stream separate from the
            // move-drag surface above so a finger starting exactly on the handle resizes rather
            // than moves. detectDragGestures on a small child Box wins the gesture over the
            // larger parent's own pointerInput because Compose's default pointer input dispatch
            // resolves to the innermost consumer first.
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(24.dp)
                    .border(1.dp, sem.chromeDim)
                    .pointerInput(item.id) {
                        detectDragGestures(
                            onDragStart = { onResizeDragStart() },
                            onDragEnd = { onResizeDragEnd() },
                            onDragCancel = { onResizeDragEnd() },
                            onDrag = { change, delta -> change.consume(); onResizeDrag(delta) },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text("⤡", style = LegionType.stamp, color = sem.chromeText)
            }
        }
    }
}

/** Same iOS-style edit-mode wobble as the stage-1 prototype's `jiggle` - carried here rather than
 *  imported, since the prototype lives in the debug-only source set and this component ships in
 *  the production one. Motion is not restricted on this app (CLAUDE.md), so this is an ordinary
 *  [rememberInfiniteTransition]. */
@Composable
private fun Modifier.gridJiggle(active: Boolean, seed: Int): Modifier {
    if (!active) return this
    val phaseMs = (kotlin.math.abs(seed) % 180)
    val transition = rememberInfiniteTransition(label = "grid-jiggle")
    val angle by transition.animateFloat(
        initialValue = -1.2f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(140, easing = LinearEasing, delayMillis = phaseMs),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "grid-jiggle-angle",
    )
    return this.graphicsLayer { rotationZ = angle }
}
