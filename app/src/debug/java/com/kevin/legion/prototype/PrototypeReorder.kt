@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.kevin.legion.prototype

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.zIndex
import com.kevin.legion.ui.common.DeckButton
import com.kevin.legion.ui.common.DeckPane
import com.kevin.legion.ui.common.DeckRow
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics

/**
 * The reorderable card column - the mechanics ticket 09 exists to prototype. Half-width widgets
 * pack two to a row (greedy, adjacent-only); full-width widgets take a whole row.
 *
 * **Reorder mechanics, stated plainly since this is the load-bearing spike code:** every widget's
 * own on-screen rect (in this Column's local coordinate space, computed via
 * [LayoutCoordinates.localPositionOf] so it is correct regardless of Row nesting) is tracked in
 * [itemBounds] on every layout pass. Dragging a card's handle accumulates a raw pointer delta;
 * each frame, the dragged item's live centre (its last-known rect centre plus the accumulated
 * delta) is compared against every OTHER item's last-known centre, and the nearest one becomes
 * the reorder target - reusing the SAME "sum of centres, no notion of row/column adjacency"
 * calculation regardless of whether the two widgets are HALF or FULL, in the same row or a
 * different one. **That collapse is exactly what stage 2's spike finding calls out**: a real 2D
 * grid needs a cell-aware target (row AND column), not a single nearest-neighbour scalar, or a
 * half-width widget dragged toward the opposite column of its own row reorders into the row below
 * instead of swapping sideways. See the ticket 09 report for the sizing on fixing that properly.
 */
private fun packRows(widgets: List<PrototypeWidget>): List<List<PrototypeWidget>> {
    val rows = mutableListOf<List<PrototypeWidget>>()
    var i = 0
    while (i < widgets.size) {
        val w = widgets[i]
        if (w.size == PrototypeWidgetSize.FULL) {
            rows.add(listOf(w))
            i += 1
        } else {
            val next = widgets.getOrNull(i + 1)
            if (next != null && next.size == PrototypeWidgetSize.HALF) {
                rows.add(listOf(w, next))
                i += 2
            } else {
                rows.add(listOf(w))
                i += 1
            }
        }
    }
    return rows
}

@Composable
fun ReorderableWidgetColumn(
    widgets: List<PrototypeWidget>,
    editMode: Boolean,
    onEnterEditMode: () -> Unit,
    onReorder: (fromId: String, toId: String) -> Unit,
    onRemove: (String) -> Unit,
    onToggleSize: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var columnCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    // Column-local rect per widget id, refreshed every layout pass. This is the ONLY source the
    // drag math below reads - never a stale index into `widgets`, which is why reordering keeps
    // working correctly across an arbitrary number of swaps mid-gesture.
    val itemBounds = remember { mutableStateMapOf<String, Rect>() }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var lastTargetId by remember { mutableStateOf<String?>(null) }

    val rows = remember(widgets) { packRows(widgets) }

    Column(
        modifier
            .fillMaxWidth()
            .onGloballyPositioned { columnCoords = it },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { widget ->
                    val isDragging = widget.id == draggingId
                    Box(
                        Modifier
                            .weight(1f)
                            .onGloballyPositioned { coords ->
                                val cc = columnCoords ?: return@onGloballyPositioned
                                val topLeft = cc.localPositionOf(coords, Offset.Zero)
                                itemBounds[widget.id] = Rect(topLeft, coords.size.toSize())
                            }
                            .graphicsLayer {
                                if (isDragging) {
                                    translationY = dragOffset.y
                                    translationX = dragOffset.x
                                    shadowElevation = 12f
                                }
                            }
                            .zIndex(if (isDragging) 1f else 0f)
                            .jiggle(active = editMode && !isDragging, seed = widget.id.hashCode()),
                    ) {
                        WidgetCard(
                            widget = widget,
                            editMode = editMode,
                            onLongPressToEnterEditMode = onEnterEditMode,
                            onRemove = { onRemove(widget.id) },
                            onToggleSize = { onToggleSize(widget.id) },
                            dragHandleModifier = if (editMode) {
                                Modifier.pointerInput(widget.id, widgets) {
                                    detectDragGestures(
                                        onDragStart = {
                                            draggingId = widget.id
                                            dragOffset = Offset.Zero
                                            lastTargetId = widget.id
                                        },
                                        onDragEnd = {
                                            draggingId = null
                                            dragOffset = Offset.Zero
                                            lastTargetId = null
                                        },
                                        onDragCancel = {
                                            draggingId = null
                                            dragOffset = Offset.Zero
                                            lastTargetId = null
                                        },
                                        onDrag = { change, delta ->
                                            change.consume()
                                            dragOffset += delta
                                            val myRect = itemBounds[widget.id] ?: return@detectDragGestures
                                            val liveCenter = myRect.center + dragOffset
                                            val targetId = itemBounds.entries
                                                .filter { it.key != widget.id }
                                                .minByOrNull { (it.value.center - liveCenter).getDistance() }
                                                ?.key
                                            if (targetId != null && targetId != lastTargetId) {
                                                lastTargetId = targetId
                                                onReorder(widget.id, targetId)
                                                // The swap just moved every rect; the item now
                                                // renders at its NEW slot, so the accumulated
                                                // offset is zeroed rather than carried - see the
                                                // class doc's "stated plainly" note on why this
                                                // is an approximation, not exact-follow.
                                                dragOffset = Offset.Zero
                                            }
                                        },
                                    )
                                }
                            } else {
                                Modifier
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                // A lone half-width widget (an odd count at the end of the list) keeps its own
                // column width rather than stretching to fill the row - visually distinct from a
                // FULL widget so the size toggle's effect stays legible.
                if (row.size == 1 && row.first().size == PrototypeWidgetSize.HALF) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** The jiggle affordance (iOS-style edit-mode wobble). A tiny per-item phase offset (from [seed])
 *  keeps every card from rotating in lockstep, which is what makes a jiggling grid read as "alive"
 *  rather than as one shape rotating. Motion is not restricted on this app (CLAUDE.md - phone-only
 *  lifted the frame-clock-only rule), so this is an ordinary [rememberInfiniteTransition]. */
@Composable
private fun Modifier.jiggle(active: Boolean, seed: Int): Modifier {
    if (!active) return this
    val phaseMs = (kotlin.math.abs(seed) % 180)
    val transition = rememberInfiniteTransition(label = "jiggle")
    val angle by transition.animateFloat(
        initialValue = -1.2f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(140, easing = LinearEasing, delayMillis = phaseMs),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "jiggle-angle",
    )
    return this.graphicsLayer { rotationZ = angle }
}

/** One widget card - [DeckPane] shell, edit-mode remove/drag chrome, and the per-kind mock body. */
@Composable
private fun WidgetCard(
    widget: PrototypeWidget,
    editMode: Boolean,
    onLongPressToEnterEditMode: () -> Unit,
    onRemove: () -> Unit,
    onToggleSize: () -> Unit,
    dragHandleModifier: Modifier,
    modifier: Modifier = Modifier,
) {
    val sem = LocalLegionSemantics.current
    val interactionSource = remember { MutableInteractionSource() }
    DeckPane(
        header = widget.title,
        headerAccent = widget.kind.name.replace('_', ' '),
        modifier = modifier.combinedClickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = {},
            onLongClick = if (!editMode) onLongPressToEnterEditMode else null,
        ),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (editMode) {
                // Drag handle - only this small target starts a drag, so the rest of the card
                // stays free for a future tap-to-open interaction (stage 2 concern, unbuilt here).
                Box(
                    Modifier
                        .size(28.dp)
                        .border(1.dp, sem.chromeDim)
                        .then(dragHandleModifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("::", style = LegionType.stamp, color = sem.chromeText)
                }
            }
            Column(Modifier.weight(1f)) {
                WidgetBody(widget)
            }
            if (editMode) {
                Box(
                    Modifier
                        .size(28.dp)
                        .background(sem.chrome)
                        .combinedClickable(onClick = onRemove),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("X", style = LegionType.stamp, color = MaterialTheme.colorScheme.background)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            SizeToggleChip(size = widget.size, onToggle = onToggleSize)
        }
    }
}

@Composable
private fun SizeToggleChip(size: PrototypeWidgetSize, onToggle: () -> Unit) {
    val sem = LocalLegionSemantics.current
    val label = if (size == PrototypeWidgetSize.HALF) "HALF" else "FULL"
    Box(
        Modifier
            .border(1.dp, sem.faint)
            .combinedClickable(onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(label, style = LegionType.stamp, color = sem.faint)
    }
}

/** `internal`, not `private` (2026-08-23, stage-2 harness) - [PrototypeGrid.kt]'s
 *  `PrototypeGridPage` reuses this exact rendering so the HOME page's stage-2 grid and the
 *  FLEET/LEDGER pages' stage-1 columns draw the SAME per-kind widget body, which is the whole
 *  point of the side-by-side comparison the harness is for. */
@Composable
internal fun WidgetBody(widget: PrototypeWidget) {
    val sem = LocalLegionSemantics.current
    when (widget.kind) {
        PrototypeWidgetKind.STAT_TILE -> {
            val (value, sub) = PrototypeFixtures.statValue(widget.id)
            Text(value, style = LegionType.amount, color = sem.data)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = sem.faint)
        }
        PrototypeWidgetKind.RECORD_LIST -> {
            Column {
                PrototypeFixtures.recordRows(widget.id).forEach { (label, value) ->
                    DeckRow(label = label, value = value)
                }
            }
        }
        PrototypeWidgetKind.NEXT_DUE -> {
            val (name, due) = PrototypeFixtures.nextDue(widget.id)
            DeckRow(label = name, value = due)
        }
        PrototypeWidgetKind.QUICK_ADD -> {
            DeckButton(text = widget.title, onClick = {})
        }
        PrototypeWidgetKind.AGENDA -> {
            Column {
                PrototypeFixtures.agendaRows(widget.id).forEach { (time, what) ->
                    DeckRow(label = time, value = what)
                }
            }
        }
    }
}
