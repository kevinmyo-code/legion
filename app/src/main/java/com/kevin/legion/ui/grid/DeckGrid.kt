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
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
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
 * **Ancestor-coordinate drag tracking, per the ticket-09 SKILL note.** Every drag/resize gesture
 * below measures against [rootCoords] (this grid's own [LayoutCoordinates], captured once via
 * [onGloballyPositioned] on the outer [Box]) via [LayoutCoordinates.localPositionOf], never a
 * per-item `boundsInParent()` - the stage-1 prototype's reorder math read per-item bounds
 * directly and it happened to work there only because every item shared one immediate parent
 * ([androidx.compose.foundation.layout.Row]/[androidx.compose.foundation.layout.Column]); a grid
 * has no such guarantee once nested layout is involved, so this component tracks against ONE
 * fixed ancestor from the start.
 *
 * **Cell hit-testing, not nearest-centre distance - the stage-1 defect this ticket exists to
 * fix.** The prototype's reorder targeted whichever OTHER item's centre was nearest the dragged
 * item's live centre, which collapsed row and column into one scalar and let a same-row sideways
 * drag jump into the row below. This component instead divides the dragged item's own live
 * top-left pixel position by the fixed cell pitch (`floor(x / colPitchPx)`, `floor(y /
 * rowPitchPx)`) to name an exact (row, col) cell, then clamps that cell into bounds - there is no
 * point at which two candidate cells are compared by distance.
 */

/** One drag-to-move gesture in flight: which item, and the raw pixel delta accumulated since
 *  [androidx.compose.foundation.gestures.detectDragGestures]'s `onDragStart`. */
private data class MoveDrag(val id: String, val accumPx: Offset)

/** One corner-resize gesture in flight: which item, and the raw pixel delta accumulated since
 *  the resize handle's own drag started. */
private data class ResizeDrag(val id: String, val accumPx: Offset)

/**
 * The grid itself.
 *
 * @param items the current layout, as a plain list - the component treats this as the source of
 *   truth and re-derives its own internal state from it via [GridEngine.normalize] whenever it
 *   changes, so a caller can pass back exactly what it got from Room without pre-validating it.
 * @param columnCount fixed column count for this grid (4 for the phone-portrait grid ticket 18
 *   specifies, but the component itself does not hardcode that - a caller decides).
 * @param editMode true once the caller has entered jiggle/edit mode (long-press on any card, or an
 *   external EDIT/DONE pill like the stage-1 harness's) - gates every gesture below; outside edit
 *   mode the grid is display-only and [onEnterEditMode] fires from a long-press on any card.
 * @param onLayoutChange fired with the new, already-[GridEngine]-resolved list at the END of a
 *   drag or resize gesture (never mid-gesture - the ghost/snap preview during the gesture is purely
 *   visual state local to this composable, so an interrupted or cancelled drag never mutates the
 *   caller's list).
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
    var rootCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    // Column-local rect per item id, refreshed every layout pass, ancestor-coordinate tracked -
    // same discipline as the stage-1 prototype's `itemBounds`, just against a grid root instead
    // of a Column. Read by both drag gestures below to know an item's CURRENT on-screen top-left
    // before adding the raw accumulated pointer delta.
    val itemOriginPx = remember { mutableStateOf<Map<String, Offset>>(emptyMap()) }

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .onGloballyPositioned { rootCoords = it },
    ) {
        val gapPx = with(density) { gap.toPx() }
        val totalWidthPx = with(density) { maxWidth.toPx() }
        val colPitchPx = (totalWidthPx - gapPx * (columnCount - 1)) / columnCount + gapPx
        val cellWidthPx = colPitchPx - gapPx
        val rowHeightPx = with(density) { rowHeight.toPx() }
        val rowPitchPx = rowHeightPx + gapPx

        // The candidate layout to RENDER this frame: mid-drag it is `baseItems` run through
        // GridEngine with the in-flight candidate cell, so every OTHER item visibly reflows to
        // make room in real time ("live reflow preview"); once the gesture ends, `baseItems`
        // itself (derived from the caller's own `items`) is what renders again, because
        // `onLayoutChange` will have already asked the caller to update `items` to match.
        val renderItems: List<GridItem> = when {
            moveDrag != null -> {
                val d = moveDrag!!
                val origin = itemOriginPx.value[d.id] ?: Offset.Zero
                val liveX = origin.x + d.accumPx.x
                val liveY = origin.y + d.accumPx.y
                val candidateCol = floor(liveX / colPitchPx).toInt()
                val candidateRow = floor(liveY / rowPitchPx).toInt().coerceAtLeast(0)
                GridEngine.moveTo(baseItems, d.id, candidateRow, candidateCol, columnCount)
            }
            resizeDrag != null -> {
                val d = resizeDrag!!
                val current = baseItems.firstOrNull { it.id == d.id }
                if (current == null) {
                    baseItems
                } else {
                    val candidateColSpan = current.colSpan + (d.accumPx.x / colPitchPx).roundToInt()
                    val candidateRowSpan = current.rowSpan + (d.accumPx.y / rowPitchPx).roundToInt()
                    GridEngine.resize(baseItems, d.id, candidateRowSpan, candidateColSpan, columnCount)
                }
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
                val targetX = with(density) { (item.col * colPitchPx).toDp() }
                val targetY = with(density) { (item.row * rowPitchPx).toDp() }
                // Everything but the item actively being dragged animates smoothly to its new
                // slot (the reflow preview); the dragged item itself follows the raw pointer with
                // no animation, so it never lags the finger.
                val animatedX by animateDpAsState(targetX, label = "grid-x-${item.id}")
                val animatedY by animateDpAsState(targetY, label = "grid-y-${item.id}")
                val dragOffsetPx = if (isDragging) moveDrag!!.accumPx else Offset.Zero
                val dragOffsetXDp = with(density) { dragOffsetPx.x.toDp() }
                val dragOffsetYDp = with(density) { dragOffsetPx.y.toDp() }

                Box(
                    Modifier
                        .offset(
                            x = if (isDragging) targetX + dragOffsetXDp else animatedX,
                            y = if (isDragging) targetY + dragOffsetYDp else animatedY,
                        )
                        .width(cellWidth)
                        .height(cellHeight)
                        .onGloballyPositioned { coords ->
                            val root = rootCoords ?: return@onGloballyPositioned
                            val topLeft = root.localPositionOf(coords, Offset.Zero)
                            itemOriginPx.value = itemOriginPx.value + (item.id to topLeft)
                        }
                        .graphicsLayer { shadowElevation = if (isDragging || isResizing) 12f else 0f }
                        .zIndex(if (isDragging || isResizing) 1f else 0f)
                        .gridJiggle(active = editMode && !isDragging && !isResizing, seed = item.id.hashCode()),
                ) {
                    GridCellChrome(
                        item = item,
                        editMode = editMode,
                        onLongPressToEnterEditMode = onEnterEditMode,
                        onRemove = { onRemove(item.id) },
                        onMoveDragStart = { moveDrag = MoveDrag(item.id, Offset.Zero) },
                        onMoveDrag = { delta ->
                            val current = moveDrag ?: return@GridCellChrome
                            moveDrag = current.copy(accumPx = current.accumPx + delta)
                        },
                        onMoveDragEnd = {
                            val d = moveDrag
                            moveDrag = null
                            if (d != null) onLayoutChange(GridEngine.normalize(renderItems, columnCount))
                        },
                        onResizeDragStart = { resizeDrag = ResizeDrag(item.id, Offset.Zero) },
                        onResizeDrag = { delta ->
                            val current = resizeDrag ?: return@GridCellChrome
                            resizeDrag = current.copy(accumPx = current.accumPx + delta)
                        },
                        onResizeDragEnd = {
                            val d = resizeDrag
                            resizeDrag = null
                            if (d != null) onLayoutChange(GridEngine.normalize(renderItems, columnCount))
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
