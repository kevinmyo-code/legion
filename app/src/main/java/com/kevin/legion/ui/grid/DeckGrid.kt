@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.kevin.legion.ui.grid

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VectorConverter
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import kotlinx.coroutines.launch
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
 * **Android-launcher semantics (2026-08-23, second feel-test pass) - the model this file now
 * implements, stated once so nothing below reads as an accident:**
 * - **No reflow, ever.** No card moves because another card was dragged or resized - not on drop,
 *   not live during the gesture. [GridEngine.moveTo]/[GridEngine.resize] (the push-and-compact
 *   versions) are never called from here; only [GridEngine.clampMoveTarget]/
 *   [GridEngine.clampResizeTarget]/[GridEngine.overlapsAny]/[GridEngine.commitIfValid].
 * - **The grid becomes visible in edit mode** - a low-contrast dotted line at every cell boundary,
 *   drawn once in [DeckGrid]'s own `drawBehind`, invisible outside edit mode. See the grid-line
 *   drawing block below.
 * - **A snap-preview outline**, not a live-reflowing ghost, shows where the dragged/resized card
 *   would land: the dragged card itself follows the raw pointer (with a lift), while a separate
 *   dashed rect - drawn at the CLAMPED candidate cell, before any validity check - tracks the
 *   nearest legal cell alignment. Normal tone (chrome) when the candidate is unoccupied, error
 *   tone ([com.kevin.legion.ui.theme.LegionSemantics.quarantined]) when it collides with another
 *   card or would exit bounds.
 * - **Occupied target = invalid = reject.** [GridEngine.commitIfValid] returns `null` on overlap;
 *   this composable then leaves [items] untouched and animates the card back to where it started.
 *   A valid release commits exactly the previewed candidate and animates the small residual
 *   distance between the raw drop point and the snapped cell (see [MoveDrag]/[ResizeDrag]'s own
 *   "settle" animation below) - both cases share ONE animation mechanism, they only differ in
 *   which rect they animate toward.
 *
 * **Cell hit-testing, not nearest-centre distance - the stage-1 defect this ticket exists to
 * fix.** The prototype's reorder targeted whichever OTHER item's centre was nearest the dragged
 * item's live centre, which collapsed row and column into one scalar and let a same-row sideways
 * drag jump into the row below. This component instead divides the dragged item's own live pixel
 * position (its position at drag START, from the model, plus the raw accumulated pointer delta)
 * by the fixed cell pitch (`floor(x / colPitchPx)`, `floor(y / rowPitchPx)`) to name an exact
 * (row, col) cell, then clamps that cell into bounds - there is no point at which two candidate
 * cells are compared by distance.
 *
 * **The commit-path bug found on the A25 (2026-08-23, first feel-test pass) and its fix, stated
 * plainly.** [GridEngine] itself was never wrong - `GridEngineTest`'s cases proved the engine
 * functions compute the right geometry from a given final delta. The bug was pure Compose state
 * plumbing: an earlier draft's `onResizeDragEnd`/`onMoveDragEnd` closures committed a value
 * computed once per RECOMPOSITION and captured by reference into that specific composition's
 * lambdas. A fast resize/drag gesture can deliver several `onDrag` pointer callbacks and then
 * `onDragEnd` before Compose ever gets a scheduled frame to recompose in between - pointer
 * dispatch is synchronous, recomposition is not. **Every commit below reads drag state and
 * [baseItems] directly at commit time** (both are either a live `mutableStateOf` read or a value
 * with no drag-state dependency) - never a composition-scoped `val` that requires an extra
 * recompose to be current.
 */

/**
 * One drag-to-move gesture in flight: which item, its ORIGIN cell (captured once, at drag start,
 * from that item's own [GridItem] in [DeckGrid.baseItems] - never re-measured mid-drag), and the
 * raw accumulated pointer delta, which drives BOTH the dragged card's own finger-follow motion AND
 * (via [GridEngine.clampMoveTarget]) the snap-preview outline's candidate cell every frame.
 */
private data class MoveDrag(val id: String, val originRow: Int, val originCol: Int, val accumPx: Offset)

/** One corner-resize gesture in flight: which item, its ORIGINAL span (captured once, at drag
 *  start), and the raw pixel delta accumulated since. */
private data class ResizeDrag(val id: String, val originRowSpan: Int, val originColSpan: Int, val accumPx: Offset)

/**
 * The grid itself.
 *
 * @param items the current layout, as a plain list - the component treats this as the source of
 *   truth and re-derives its own internal state from it via [GridEngine.normalize] whenever it
 *   changes, so a caller can pass back exactly what it got from Room without pre-validating it.
 *   [GridEngine.normalize] is the ONE place in this component's whole call graph that still
 *   pushes/compacts - it exists to make sense of untrusted input, never to reflow a live gesture.
 * @param columnCount fixed column count for this grid (4 for the phone-portrait grid ticket 18
 *   specifies, but the component itself does not hardcode that - a caller decides).
 * @param editMode true once the caller has entered jiggle/edit mode - gates every gesture below
 *   AND the visible cell-boundary grid lines; outside edit mode the grid is display-only (no
 *   lines, no drag/resize/remove chrome) and [onEnterEditMode] fires from a long-press on any card.
 * @param onLayoutChange fired with the new list at the END of a drag or resize gesture, ONLY when
 *   the drop was valid (an invalid drop never calls this - see the file doc's "occupied target"
 *   rule). Never mid-gesture.
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
    val sem = LocalLegionSemantics.current
    val scope = rememberCoroutineScope()
    val baseItems = remember(items, columnCount) { GridEngine.normalize(items, columnCount) }

    var moveDrag by remember { mutableStateOf<MoveDrag?>(null) }
    var resizeDrag by remember { mutableStateOf<ResizeDrag?>(null) }

    // The "settle" animation - shared by BOTH a valid drop (small residual snap from raw pointer
    // position to the exact cell) and an invalid drop (full return trip back to the origin cell).
    // Only one of these is ever active at a time (one gesture in flight per grid), so one pair of
    // Animatables covers move and resize's positional/size residual respectively.
    val moveSettle = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    var settlingMoveId by remember { mutableStateOf<String?>(null) }
    val resizeSettle = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    var settlingResizeId by remember { mutableStateOf<String?>(null) }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val gapPx = with(density) { gap.toPx() }
        val totalWidthPx = with(density) { maxWidth.toPx() }
        val colPitchPx = (totalWidthPx - gapPx * (columnCount - 1)) / columnCount + gapPx
        val cellWidthPx = colPitchPx - gapPx
        val rowHeightPx = with(density) { rowHeight.toPx() }
        val rowPitchPx = rowHeightPx + gapPx

        fun widthPxFor(colSpan: Int) = cellWidthPx + (colSpan - 1) * colPitchPx
        fun heightPxFor(rowSpan: Int) = rowHeightPx * rowSpan + gapPx * (rowSpan - 1).coerceAtLeast(0)

        // baseItems never changes mid-gesture (nothing here reflows), so the row count only needs
        // to account for what is actually committed, plus one spare row while dragging/resizing so
        // there is somewhere to drop a card below the last occupied row.
        val rowCount = maxOf(GridEngine.rowCount(baseItems), 1) + if (moveDrag != null || resizeDrag != null) 1 else 0
        val totalHeight = rowHeight * rowCount + gap * (rowCount - 1).coerceAtLeast(0)

        // The live snap-preview candidate (clamped, NOT yet reflowed - nothing here ever reflows):
        // null when no gesture is in flight. Rendered as a dashed outline, normal tone if legal,
        // error tone if it collides or would exit bounds.
        val previewCandidate: GridItem? = when {
            moveDrag != null -> {
                val d = moveDrag!!
                val current = baseItems.firstOrNull { it.id == d.id }
                if (current == null) {
                    null
                } else {
                    val liveX = d.originCol * colPitchPx + d.accumPx.x
                    val liveY = d.originRow * rowPitchPx + d.accumPx.y
                    val candidateCol = floor(liveX / colPitchPx).toInt()
                    val candidateRow = floor(liveY / rowPitchPx).toInt().coerceAtLeast(0)
                    GridEngine.clampMoveTarget(current, candidateRow, candidateCol, columnCount)
                }
            }
            resizeDrag != null -> {
                val d = resizeDrag!!
                val current = baseItems.firstOrNull { it.id == d.id }
                if (current == null) {
                    null
                } else {
                    val candidateColSpan = d.originColSpan + (d.accumPx.x / colPitchPx).roundToInt()
                    val candidateRowSpan = d.originRowSpan + (d.accumPx.y / rowPitchPx).roundToInt()
                    GridEngine.clampResizeTarget(current, candidateRowSpan, candidateColSpan, columnCount)
                }
            }
            else -> null
        }
        val previewValid = previewCandidate?.let { candidate ->
            !GridEngine.overlapsAny(candidate, baseItems.filter { it.id != candidate.id })
        } ?: true

        Box(
            Modifier
                .fillMaxWidth()
                .height(totalHeight)
                .let { base ->
                    if (!editMode) return@let base
                    // The visible cell-boundary grid (brief point 2): a subtle dotted line at
                    // every column and row boundary, low-contrast so it reads as a SURFACE (like
                    // graph paper) rather than as chrome competing with the cards on top of it.
                    // Drawn once here rather than per-cell, since the lines belong to the GRID,
                    // not to any one widget.
                    base.background(MaterialTheme.colorScheme.background).drawGridLines(
                        color = sem.ruleFaint,
                        columnCount = columnCount,
                        rowCount = rowCount,
                        colPitchPx = colPitchPx,
                        rowPitchPx = rowPitchPx,
                        cellWidthPx = cellWidthPx,
                        rowHeightPx = rowHeightPx,
                    )
                },
        ) {
            // The snap-preview outline - drawn BELOW every card (added to the Box first) so a card
            // dragged over another one still reads the other card's own content on top.
            if (previewCandidate != null) {
                val outlineColor = if (previewValid) sem.chromeText else sem.quarantined
                val pWidth = with(density) { widthPxFor(previewCandidate.colSpan).toDp() }
                val pHeight = with(density) { heightPxFor(previewCandidate.rowSpan).toDp() }
                val pX = with(density) { (previewCandidate.col * colPitchPx).toDp() }
                val pY = with(density) { (previewCandidate.row * rowPitchPx).toDp() }
                Box(
                    Modifier
                        .offset(x = pX, y = pY)
                        .width(pWidth)
                        .height(pHeight)
                        .dashedOutline(outlineColor),
                )
            }

            baseItems.forEach { item ->
                val isDragging = moveDrag?.id == item.id
                val isResizing = resizeDrag?.id == item.id
                val isSettlingMove = settlingMoveId == item.id
                val isSettlingResize = settlingResizeId == item.id

                val baseWidth = with(density) { widthPxFor(item.colSpan).toDp() }
                val baseHeight = with(density) { heightPxFor(item.rowSpan).toDp() }
                val targetX = with(density) { (item.col * colPitchPx).toDp() }
                val targetY = with(density) { (item.row * rowPitchPx).toDp() }

                val dragStartX = if (isDragging) with(density) { (moveDrag!!.originCol * colPitchPx).toDp() } else null
                val dragStartY = if (isDragging) with(density) { (moveDrag!!.originRow * rowPitchPx).toDp() } else null
                val dragOffsetXDp = if (isDragging) with(density) { moveDrag!!.accumPx.x.toDp() } else 0.dp
                val dragOffsetYDp = if (isDragging) with(density) { moveDrag!!.accumPx.y.toDp() } else 0.dp

                val settleXDp = if (isSettlingMove) with(density) { moveSettle.value.x.toDp() } else 0.dp
                val settleYDp = if (isSettlingMove) with(density) { moveSettle.value.y.toDp() } else 0.dp
                val settleWidthDp = if (isSettlingResize) with(density) { resizeSettle.value.x.toDp() } else 0.dp
                val settleHeightDp = if (isSettlingResize) with(density) { resizeSettle.value.y.toDp() } else 0.dp

                val offsetX = when {
                    isDragging -> (dragStartX ?: targetX) + dragOffsetXDp
                    isSettlingMove -> targetX + settleXDp
                    else -> targetX
                }
                val offsetY = when {
                    isDragging -> (dragStartY ?: targetY) + dragOffsetYDp
                    isSettlingMove -> targetY + settleYDp
                    else -> targetY
                }
                val boxWidth = if (isSettlingResize) baseWidth + settleWidthDp else baseWidth
                val boxHeight = if (isSettlingResize) baseHeight + settleHeightDp else baseHeight

                Box(
                    Modifier
                        .offset(x = offsetX, y = offsetY)
                        .width(boxWidth)
                        .height(boxHeight)
                        .graphicsLayer {
                            // Slight scale/elevation lift on the card actually under the finger -
                            // the "picked up" affordance the brief asks for. A resizing card is not
                            // scaled (its own box IS the live size preview).
                            val lift = isDragging
                            scaleX = if (lift) 1.04f else 1f
                            scaleY = if (lift) 1.04f else 1f
                            shadowElevation = if (isDragging) 16f else if (isResizing) 10f else 0f
                        }
                        .zIndex(if (isDragging || isResizing) 1f else 0f)
                        .gridJiggle(active = editMode && !isDragging && !isResizing && !isSettlingMove && !isSettlingResize, seed = item.id.hashCode()),
                ) {
                    GridCellChrome(
                        item = item,
                        editMode = editMode,
                        onLongPressToEnterEditMode = onEnterEditMode,
                        onRemove = { onRemove(item.id) },
                        onMoveDragStart = {
                            val current = baseItems.firstOrNull { it.id == item.id } ?: return@GridCellChrome
                            moveDrag = MoveDrag(item.id, current.row, current.col, Offset.Zero)
                        },
                        onMoveDrag = { delta ->
                            val d = moveDrag ?: return@GridCellChrome
                            moveDrag = d.copy(accumPx = d.accumPx + delta)
                        },
                        onMoveDragEnd = {
                            val d = moveDrag
                            moveDrag = null
                            if (d != null) {
                                val current = baseItems.firstOrNull { it.id == d.id }
                                if (current != null) {
                                    val candidate = GridEngine.clampMoveTarget(current, run {
                                        val liveX = d.originCol * colPitchPx + d.accumPx.x
                                        floor(liveX / colPitchPx).toInt()
                                    }, run {
                                        val liveY = d.originRow * rowPitchPx + d.accumPx.y
                                        floor(liveY / rowPitchPx).toInt().coerceAtLeast(0)
                                    }, columnCount)
                                    // clampMoveTarget's signature is (item, targetRow, targetCol, columnCount) -
                                    // recomputed explicitly here (never reused from a composition-scoped val)
                                    // per the file doc's commit-path-bug fix.
                                    val committed = GridEngine.commitIfValid(baseItems, candidate)
                                    val settleTarget = if (committed != null) candidate else current
                                    if (committed != null) onLayoutChange(committed)
                                    val originPx = Offset(d.originCol * colPitchPx, d.originRow * rowPitchPx)
                                    val settlePx = Offset(settleTarget.col * colPitchPx, settleTarget.row * rowPitchPx)
                                    val rawDropPx = originPx + d.accumPx
                                    val residual = rawDropPx - settlePx
                                    settlingMoveId = d.id
                                    scope.launch {
                                        moveSettle.snapTo(residual)
                                        moveSettle.animateTo(Offset.Zero, tween(200))
                                        settlingMoveId = null
                                    }
                                }
                            }
                        },
                        onResizeDragStart = {
                            val current = baseItems.firstOrNull { it.id == item.id } ?: return@GridCellChrome
                            resizeDrag = ResizeDrag(item.id, current.rowSpan, current.colSpan, Offset.Zero)
                        },
                        onResizeDrag = { delta ->
                            val d = resizeDrag ?: return@GridCellChrome
                            resizeDrag = d.copy(accumPx = d.accumPx + delta)
                        },
                        onResizeDragEnd = {
                            // This is THE bug Kevin hit on the A25 in the first feel-test pass:
                            // read `d` fresh (already done, it is a direct property read of the
                            // live state) and recompute the candidate from `baseItems` directly -
                            // never a composition-scoped val that needs an extra recompose.
                            val d = resizeDrag
                            resizeDrag = null
                            if (d != null) {
                                val current = baseItems.firstOrNull { it.id == d.id }
                                if (current != null) {
                                    val candidateColSpan = d.originColSpan + (d.accumPx.x / colPitchPx).roundToInt()
                                    val candidateRowSpan = d.originRowSpan + (d.accumPx.y / rowPitchPx).roundToInt()
                                    val candidate = GridEngine.clampResizeTarget(current, candidateRowSpan, candidateColSpan, columnCount)
                                    val committed = GridEngine.commitIfValid(baseItems, candidate)
                                    val settleTarget = if (committed != null) candidate else current
                                    if (committed != null) onLayoutChange(committed)
                                    val rawWidthPx = widthPxFor(current.colSpan) + d.accumPx.x
                                    val rawHeightPx = heightPxFor(current.rowSpan) + d.accumPx.y
                                    val settleWidthPx = widthPxFor(settleTarget.colSpan)
                                    val settleHeightPx = heightPxFor(settleTarget.rowSpan)
                                    val residual = Offset(rawWidthPx - settleWidthPx, rawHeightPx - settleHeightPx)
                                    settlingResizeId = d.id
                                    scope.launch {
                                        resizeSettle.snapTo(residual)
                                        resizeSettle.animateTo(Offset.Zero, tween(200))
                                        settlingResizeId = null
                                    }
                                }
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

/** The visible cell-boundary grid drawn in edit mode only (brief point 2) - a dotted line at every
 *  internal column and row boundary (the outer edge is left undrawn; the cards' own frame already
 *  reads that boundary, and drawing it too would double the line at row/col 0). Deliberately a
 *  dotted [PathEffect.dashPathEffect] rather than a solid rule, at the caller-supplied [color]
 *  (always [com.kevin.legion.ui.theme.LegionSemantics.ruleFaint], the same low-contrast structural
 *  tier [com.kevin.legion.ui.common.DeckRow]'s own hairline already uses), so it reads as graph
 *  paper - a surface - rather than as chrome competing with the cards drawn on top of it. */
private fun Modifier.drawGridLines(
    color: Color,
    columnCount: Int,
    rowCount: Int,
    colPitchPx: Float,
    rowPitchPx: Float,
    cellWidthPx: Float,
    rowHeightPx: Float,
): Modifier = this.drawBehind {
    val dash = PathEffect.dashPathEffect(floatArrayOf(3f, 5f), 0f)
    val strokeWidth = 1.dp.toPx()
    // Internal COLUMN boundaries only (i in 1 until columnCount) - the outer edges at col 0 and
    // col `columnCount` are left undrawn, per this function's own doc.
    for (i in 1 until columnCount) {
        val x = i * colPitchPx - (colPitchPx - cellWidthPx) / 2f
        drawLine(color = color, start = Offset(x, 0f), end = Offset(x, rowCount * rowPitchPx), strokeWidth = strokeWidth, pathEffect = dash)
    }
    // Internal ROW boundaries only (i in 1 until rowCount).
    for (i in 1 until rowCount) {
        val y = i * rowPitchPx - (rowPitchPx - rowHeightPx) / 2f
        drawLine(color = color, start = Offset(0f, y), end = Offset(columnCount * colPitchPx, y), strokeWidth = strokeWidth, pathEffect = dash)
    }
}

/** The snap-preview outline's own dashed border - a distinct visual language from [drawGridLines]'s
 *  cell lines (bolder dash, brighter colour) so it reads as "this is where the card lands", not as
 *  another grid line. Colour is chosen by the caller ([DeckGrid]: chrome-text tone when legal, the
 *  quarantine/error tone when the candidate overlaps or would exit bounds - see the file doc's
 *  "occupied target = invalid" rule). */
private fun Modifier.dashedOutline(color: Color): Modifier = this.drawBehind {
    val stroke = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f), 0f))
    drawRect(color = color, style = stroke)
}
