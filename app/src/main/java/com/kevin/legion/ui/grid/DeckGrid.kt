@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.kevin.legion.ui.grid

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
 * **Android-launcher semantics with DISPLACEMENT (2026-08-23, third feel-test pass) - the model
 * this file now implements, stated once so nothing below reads as an accident:**
 * - **No AMBIENT reflow, ever.** No card moves on its own because another card merely started
 *   being dragged - there is no live shuffling of the whole grid the way stage 2's first two
 *   passes tried and Kevin rejected twice. [GridEngine.moveTo]/[GridEngine.resize] (the
 *   push-and-compact versions) are never called from here.
 * - **The grid becomes visible in edit mode** - a low-contrast dotted line at every cell boundary,
 *   drawn once in [DeckGrid]'s own `drawBehind`, invisible outside edit mode. See the grid-line
 *   drawing block below.
 * - **A snap-preview outline** shows where the dragged card would land: the dragged card
 *   itself follows the raw pointer (with a lift), while a separate dashed rect - drawn at the
 *   CLAMPED candidate cell - tracks the nearest legal cell alignment. Chrome tone when
 *   [GridEngine.displaceForPlacement] finds a workable arrangement, error tone
 *   ([com.kevin.legion.ui.theme.LegionSemantics.quarantined]) when NOTHING fits at all (see the
 *   next point).
 * - **Occupied target = DISPLACE, not reject (third feel-test pass, superseding the second pass's
 *   outright-reject rule).** Kevin: "it doesnt replace or move the items in the grid up if
 *   something is already there." [GridEngine.displaceForPlacement] accepts the candidate exactly
 *   as proposed and relocates whatever it overlaps (and any knock-on chain that causes) to the
 *   nearest free space, upward first. Every occupant this WOULD move is shown live as a
 *   dimmer ("chrome-dim") ghost outline at its own new cell while the gesture is still in
 *   flight - "preview honesty" (the brief's own phrase): the eventual commit can never differ
 *   from what was previewed. `null` is now reserved for the genuinely-impossible case (see that
 *   function's own doc) - THAT is what still triggers the error-tone-and-animate-home rejection
 *   path, not a mere overlap.
 * - **A valid release commits exactly the previewed arrangement** and animates the small residual
 *   distance between the raw drop point and the snapped cell for the ACTIVELY dragged card
 *   (see [MoveDrag]'s own "settle" animation below) - both the valid-commit and the
 *   rejected-and-returned case share ONE animation mechanism, they only differ in which rect they
 *   animate toward. **Every OTHER occupant a drop displaces gets the same 200ms settle too**
 *   (the deferred polish, wired in the same rework that fixed the fourth feel-test pass's bug
 *   below) - `displacementSettle`, a map keyed by id rather than a single shared `Animatable`,
 *   since an arbitrary number of occupants can be displaced by one drop (a knock-on chain).
 * - **FREE RESIZE IS RETIRED (2026-08-23, after the seventh feel-test pass).** Kevin: "card sizing
 *   is not accurate. lets just implement set size cards. like android widgets." [ResizeDrag], the
 *   corner-resize handle, and the resize half of the snap-preview/settle machinery above are gone.
 *   In their place: edit mode's former handle position now hosts a SIZE CHIP (see
 *   [GridCellChrome]) that, on tap, cycles the card through its own kind's supported
 *   [GridPreset]s via [GridEngine.nextPreset] and commits the next one through the SAME
 *   [GridEngine.displaceForPlacement] path a drag commits through - occupants make room exactly
 *   as they would for a drag, and a preset step that fits nowhere leaves the layout untouched and
 *   flashes the chip to the quarantine tone rather than silently doing nothing. See [GridPreset]'s
 *   own doc in `GridModel.kt` for the full catalog and rationale. Drag-to-move is UNCHANGED by
 *   this rework - only the resize gesture is gone.
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
 * **Two Compose state-plumbing bugs found on the A25, both fixed, stated plainly.** [GridEngine]
 * itself was never wrong either time - `GridEngineTest`'s cases proved the engine functions
 * compute the right geometry from a given final delta or a given `List<GridItem>`.
 *
 * *First feel-test pass (resize did not commit):* an earlier draft's `onResizeDragEnd`/
 * `onMoveDragEnd` closures committed a value computed once per RECOMPOSITION and captured by
 * reference into that specific composition's lambdas. A fast resize/drag gesture can deliver
 * several `onDrag` pointer callbacks and then `onDragEnd` before Compose ever gets a scheduled
 * frame to recompose in between - pointer dispatch is synchronous, recomposition is not. **Every
 * commit below reads drag state and [baseItems] directly at commit time** (both are either a live
 * `mutableStateOf` read or a value with no drag-state dependency) - never a composition-scoped
 * `val` that requires an extra recompose to be current.
 *
 * *Fourth feel-test pass (every drop appeared to revert):* `baseItems` used to be
 * `remember(items, columnCount) { GridEngine.normalize(items, columnCount) }`, keying directly on
 * `items`. The harness's `items` is a `SnapshotStateList` mutated IN PLACE (`clear()` + `addAll()`,
 * never replaced), so every recomposition after the very first commit compared the SAME object
 * reference against itself - `remember`'s change check degenerated to `x.equals(x)`, always true,
 * so `baseItems` froze at whatever it computed on this composable's FIRST-EVER composition and
 * never recomputed again. Every card rendered its pre-drag position forever, and every settle
 * animation's target was that same frozen position - exactly "drop card to new position, it snaps
 * back to old position": `onLayoutChange` correctly updated the caller's own state, but this
 * composable's own `baseItems` never noticed. **Fixed by keying on `items.toList()`** - a genuinely
 * new immutable copy taken fresh every recomposition, so the comparison is real structural List
 * equality against a frozen snapshot from last time, not an object against itself. A coordinator
 * hypothesis for this same symptom (a self-overlap flaw in [GridEngine.displaceForPlacement], the
 * dragged item's own OLD position counting as an obstacle to itself) was checked FIRST via
 * `GridEngineTest` and disproven - `others` in that function already filters `it.id !=
 * candidate.id` before any collision check runs, so the mover's own old rect was never a real
 * obstacle at that layer. This bug lived entirely in this file's own composition, not in the
 * pure model - see `GridEngineTest`'s own doc comment for the exact test name.
 */

/**
 * One drag-to-move gesture in flight: which item, its ORIGIN cell (captured once, at drag start,
 * from that item's own [GridItem] in [DeckGrid.baseItems] - never re-measured mid-drag), and the
 * raw accumulated pointer delta, which drives BOTH the dragged card's own finger-follow motion AND
 * (via [GridEngine.clampMoveTarget]) the snap-preview outline's candidate cell every frame.
 */
private data class MoveDrag(val id: String, val originRow: Int, val originCol: Int, val accumPx: Offset)

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
 *   [GridEngine.displaceForPlacement] found a workable arrangement (the genuinely-impossible case
 *   never calls this - see the file doc's "occupied target" rule). Never mid-gesture.
 * @param onRemove fired with an item's id when its remove chip is tapped in edit mode.
 * @param presetsFor the fixed [GridPreset] subset a given card supports (a `STAT_TILE` supports
 *   only [GridPreset.SMALL], a `RECORD_LIST` supports [GridPreset.WIDE] and [GridPreset.LARGE],
 *   etc - see [GridPreset]'s own doc). Consulted only in edit mode, when the size chip is tapped;
 *   [GridEngine.nextPreset] cycles through exactly this list.
 * @param itemContent the widget's own body - this composable supplies only the cell rect, the
 *   jiggle, and the edit-mode chrome (drag surface, size chip, remove chip) around it.
 */
@Composable
fun DeckGrid(
    items: List<GridItem>,
    columnCount: Int,
    editMode: Boolean,
    onEnterEditMode: () -> Unit,
    onLayoutChange: (List<GridItem>) -> Unit,
    onRemove: (id: String) -> Unit,
    presetsFor: (GridItem) -> List<GridPreset>,
    modifier: Modifier = Modifier,
    rowHeight: Dp = 132.dp,
    gap: Dp = 10.dp,
    itemContent: @Composable BoxScope.(GridItem) -> Unit,
) {
    val density = LocalDensity.current
    val sem = LocalLegionSemantics.current
    val scope = rememberCoroutineScope()
    // THE FOURTH-PASS BUG (fixed, kept here for the record): `remember(items, columnCount) { ... }`
    // used to key directly on `items`. The harness's `items` is a `SnapshotStateList` mutated IN
    // PLACE (`clear()` + `addAll()`, never replaced), so `remember`'s change check degenerated to
    // `sameObject.equals(sameObject)` - always true regardless of content - and `baseItems` froze
    // at whatever it computed on this composable's very first composition. Fixing that (keying on
    // `items.toList()`, a genuinely new copy every recomposition) uncovered the FIFTH-pass bug
    // below, because it made `baseItems` recompute for real on every content change for the first
    // time - which is exactly when `GridEngine.normalize`'s own unconditional compaction became
    // visible: "drop into an empty space, it snaps to the top" (see [GridEngine.normalize]'s own
    // KDoc - `compact` pulls a valid, deliberately-gapped layout upward on EVERY call, by design,
    // for untrusted first input; it was never meant to run on an already-valid, already-committed
    // layout). **The actual fix: stop calling `normalize` on every recomposition at all.**
    // `normalize` now runs EXACTLY ONCE, on this `DeckGrid` instance's first-ever composition
    // (`remember { }` with no keys, per its own contract, runs its block once and never again for
    // the lifetime of this composable) - that is the "genuinely untrusted FIRST input" pass. Every
    // recomposition after that trusts `items` verbatim: no compaction, no collision resolution,
    // because a value that already round-tripped through this component's own commit path
    // ([GridEngine.displaceForPlacement], [GridEngine.clampMoveTarget]/[GridEngine.clampResizeTarget])
    // is ALREADY valid, and reprocessing it is exactly what silently relocated the user's own
    // deliberate placement. Proven at the `GridEngine` layer by
    // `GridEngineTest.normalize does NOT round-trip a valid gapped layout untouched`.
    val firstPassSeed = remember { GridEngine.normalize(items, columnCount) }
    var isFirstComposition by remember { mutableStateOf(true) }
    // `firstPassSeed` (defensively normalized) on this composable's true first-ever pass; `items`
    // verbatim - no processing at all - on every pass after that, whether triggered by our own
    // commit or an external add/remove. `isFirstComposition = false` is a plain state write during
    // composition, not inside a `remember` calculation block - the accepted "one-shot flag" idiom
    // (see e.g. Compose's own side-effects guidance): it can only ever flip true-to-false once per
    // composable instance, so it cannot loop, and the one extra recomposition it schedules right
    // after mount is the standard, cheap cost of that pattern.
    val baseItems = if (isFirstComposition) firstPassSeed else items
    isFirstComposition = false

    var moveDrag by remember { mutableStateOf<MoveDrag?>(null) }

    // The "settle" animation - shared by BOTH a valid drop (small residual snap from raw pointer
    // position to the exact cell) and an invalid drop (full return trip back to the origin cell).
    // Only one move gesture is ever in flight at a time per grid, so one Animatable covers it.
    val moveSettle = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    var settlingMoveId by remember { mutableStateOf<String?>(null) }

    // The size-chip's error flash (fourth generation, presets replacing free resize): the id of
    // whichever card's preset-cycle tap just found NO arrangement that fits, for a short,
    // self-clearing window - see [cyclePreset] below. `null` the rest of the time.
    var presetErrorId by remember { mutableStateOf<String?>(null) }

    // Displaced-OCCUPANT settle (the deferred polish from the third feel-test pass, added here):
    // one independent Animatable per id currently animating from its pre-commit pixel position
    // down to zero residual against its new (post-commit) cell - unlike [moveSettle]/[resizeSettle]
    // above (exactly one active gesture's own card at a time), an arbitrary NUMBER of occupants can
    // be displaced by a single drop (a knock-on chain), so this is a map keyed by id rather than a
    // single shared Animatable. An id is present in the map only while its own settle animation is
    // running; [startDisplacementSettle] removes it on completion.
    val displacementSettle = remember { mutableStateMapOf<String, Animatable<Offset, AnimationVector2D>>() }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val gapPx = with(density) { gap.toPx() }
        val totalWidthPx = with(density) { maxWidth.toPx() }
        val colPitchPx = (totalWidthPx - gapPx * (columnCount - 1)) / columnCount + gapPx
        val cellWidthPx = colPitchPx - gapPx
        val rowHeightPx = with(density) { rowHeight.toPx() }
        val rowPitchPx = rowHeightPx + gapPx

        fun widthPxFor(colSpan: Int) = cellWidthPx + (colSpan - 1) * colPitchPx
        fun heightPxFor(rowSpan: Int) = rowHeightPx * rowSpan + gapPx * (rowSpan - 1).coerceAtLeast(0)

        // Kicks off (or restarts) a settle animation for every item in `committed` whose row/col
        // differs from its counterpart in `preCommit` - i.e. every OCCUPANT a drop or a preset-cycle
        // tap just displaced, excluding `excludeId` (the actively dragged card or the card whose
        // chip was just tapped, which already gets its own dedicated [moveSettle] treatment, or - for
        // a preset tap - no positional settle of its own at all, since it did not move). Called once,
        // right after a valid [GridEngine.displaceForPlacement] commit, from onMoveDragEnd and from
        // [cyclePreset].
        fun startDisplacementSettles(preCommit: List<GridItem>, committed: List<GridItem>, excludeId: String) {
            val preById = preCommit.associateBy { it.id }
            for (moved in committed) {
                if (moved.id == excludeId) continue
                val before = preById[moved.id] ?: continue
                if (before.row == moved.row && before.col == moved.col) continue
                val oldPx = Offset(before.col * colPitchPx, before.row * rowPitchPx)
                val newPx = Offset(moved.col * colPitchPx, moved.row * rowPitchPx)
                val residual = oldPx - newPx
                val anim = Animatable(residual, Offset.VectorConverter)
                displacementSettle[moved.id] = anim
                scope.launch {
                    anim.animateTo(Offset.Zero, tween(200))
                    // Only clear this id's entry if IT is still the animation we started - a
                    // second drop displacing the same occupant again before this one finished
                    // would have already replaced the map entry with a NEW Animatable, and this
                    // stale coroutine finishing later must not clobber that newer one.
                    if (displacementSettle[moved.id] === anim) displacementSettle.remove(moved.id)
                }
            }
        }

        // Preset-cycle tap (fourth generation - see the file doc's own "FREE RESIZE IS RETIRED"
        // paragraph): commit the widget's own NEXT supported [GridPreset] through the exact same
        // [GridEngine.displaceForPlacement] path a drag commits through. No preview machinery here
        // at all - unlike a drag, a tap has no in-flight pointer position to preview against, it
        // either commits immediately or it does not. A `null` result (nothing fits ANY row for the
        // displaced occupant - see [GridEngine.displaceForPlacement]'s own doc for when that is even
        // possible) leaves `baseItems` untouched and flashes [presetErrorId] for a short, self-
        // clearing window instead - the size-chip equivalent of a drag animating back to its origin.
        fun cyclePreset(itemId: String) {
            val current = baseItems.firstOrNull { it.id == itemId } ?: return
            val supported = presetsFor(current)
            if (supported.isEmpty()) return
            val currentPreset = GridPreset.match(current) ?: supported.first()
            val next = GridEngine.nextPreset(currentPreset, supported)
            val candidate = GridEngine.clampPresetTarget(current, next, columnCount)
            val committed = GridEngine.displaceForPlacement(baseItems, candidate, columnCount)
            if (committed != null) {
                onLayoutChange(committed)
                startDisplacementSettles(baseItems, committed, excludeId = itemId)
            } else {
                presetErrorId = itemId
                scope.launch {
                    delay(280)
                    // Same "only clear if I'm still the flash that fired" guard [startDisplacementSettles]
                    // uses - a second failed tap on the SAME chip before this delay elapses must not
                    // have its own flash cut short by this stale coroutine.
                    if (presetErrorId == itemId) presetErrorId = null
                }
            }
        }

        // baseItems never changes mid-gesture (nothing here reflows), so the row count only needs
        // to account for what is actually committed, plus one spare row while dragging so there is
        // somewhere to drop a card below the last occupied row. A preset-cycle tap commits
        // synchronously (no drag in flight), so it needs no extra row of its own here.
        val rowCount = maxOf(GridEngine.rowCount(baseItems), 1) + if (moveDrag != null) 1 else 0
        val totalHeight = rowHeight * rowCount + gap * (rowCount - 1).coerceAtLeast(0)

        // The live snap-preview candidate (clamped, NOT yet reflowed - nothing here ever reflows):
        // null when no drag is in flight. Rendered as a dashed outline, normal tone if legal,
        // error tone if it collides or would exit bounds.
        val previewCandidate: GridItem? = if (moveDrag != null) {
            val d = moveDrag!!
            val current = baseItems.firstOrNull { it.id == d.id }
            if (current == null) {
                null
            } else {
                // GridGesture.candidateCell - the ONE pixel-to-cell implementation (sixth
                // feel-test pass fix), also called by onMoveDragEnd below, so there is no
                // second inline computation left to diverge from this one.
                val (candidateRow, candidateCol) = GridGesture.candidateCell(
                    originRow = d.originRow,
                    originCol = d.originCol,
                    accumPxX = d.accumPx.x,
                    accumPxY = d.accumPx.y,
                    colPitchPx = colPitchPx,
                    rowPitchPx = rowPitchPx,
                )
                GridEngine.clampMoveTarget(current, candidateRow, candidateCol, columnCount)
            }
        } else {
            null
        }
        // DISPLACE, not reject (third feel-test pass, 2026-08-23): the full arrangement that
        // WOULD result from committing `previewCandidate` right now - `null` only when no
        // arrangement fits at all (see GridEngine.displaceForPlacement's own doc). Computed fresh
        // every frame the candidate changes, same as the candidate itself - this is what makes
        // the ghost ("preview honesty", brief point 3) show EXACTLY what a drop would commit.
        val previewArrangement: List<GridItem>? = previewCandidate?.let { candidate ->
            GridEngine.displaceForPlacement(baseItems, candidate, columnCount)
        }
        val previewValid = previewArrangement != null
        // Every occupant whose position in `previewArrangement` differs from its CURRENT
        // (baseItems) position - i.e. every card that would actually be displaced by this drop,
        // including knock-on chains - rendered as its own ghost outline at the NEW cell it would
        // land in. The dragged/resized card itself is excluded (it gets the brighter candidate
        // outline above, not a second ghost).
        val candidateId = previewCandidate?.id
        val displacedGhosts: List<GridItem> = if (previewArrangement != null && candidateId != null) {
            val baseById = baseItems.associateBy { it.id }
            previewArrangement.filter { moved ->
                moved.id != candidateId &&
                    baseById[moved.id]?.let { it.row != moved.row || it.col != moved.col } == true
            }
        } else {
            emptyList()
        }

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
            // Preview honesty (brief point 3): a ghost outline, at the CHROME-DIM tone (dimmer
            // than the candidate's own chrome-text outline so the two read as primary/secondary,
            // not as two competing highlights), for every occupant this drop would actually
            // displace - exactly the arrangement `previewArrangement` already computed, so the
            // eventual commit can never surprise the user with a move they were not shown live.
            displacedGhosts.forEach { ghost ->
                val gWidth = with(density) { widthPxFor(ghost.colSpan).toDp() }
                val gHeight = with(density) { heightPxFor(ghost.rowSpan).toDp() }
                val gX = with(density) { (ghost.col * colPitchPx).toDp() }
                val gY = with(density) { (ghost.row * rowPitchPx).toDp() }
                Box(
                    Modifier
                        .offset(x = gX, y = gY)
                        .width(gWidth)
                        .height(gHeight)
                        .dashedOutline(sem.chromeDim),
                )
            }

            baseItems.forEach { item ->
                val isDragging = moveDrag?.id == item.id
                val isSettlingMove = settlingMoveId == item.id
                // The deferred polish: an occupant a drop or a preset-cycle tap just displaced
                // (never the actively dragged card itself - that one is covered by isSettlingMove
                // above, and is excluded from this map by startDisplacementSettles's own `excludeId`).
                val displacementAnim = displacementSettle[item.id]

                // Box size now animates directly toward whatever `item`'s OWN colSpan/rowSpan
                // currently says (fourth generation: a preset-cycle tap changes these fields
                // outright via `onLayoutChange`, with no in-between drag state to read a live span
                // from the way the old resize gesture had) - a plain animateDpAsState per axis gives
                // the size change the same "settle" feel a drag/drop gets, at a fraction of the
                // hand-rolled Animatable bookkeeping resize used to need. Motion is not restricted on
                // this app (CLAUDE.md), so an ordinary spring-based animateDpAsState is the right
                // tool rather than a bespoke Animatable pair.
                val baseWidth = with(density) { widthPxFor(item.colSpan).toDp() }
                val baseHeight = with(density) { heightPxFor(item.rowSpan).toDp() }
                val boxWidth by animateDpAsState(baseWidth, label = "grid-card-width")
                val boxHeight by animateDpAsState(baseHeight, label = "grid-card-height")
                val targetX = with(density) { (item.col * colPitchPx).toDp() }
                val targetY = with(density) { (item.row * rowPitchPx).toDp() }

                val dragStartX = if (isDragging) with(density) { (moveDrag!!.originCol * colPitchPx).toDp() } else null
                val dragStartY = if (isDragging) with(density) { (moveDrag!!.originRow * rowPitchPx).toDp() } else null
                val dragOffsetXDp = if (isDragging) with(density) { moveDrag!!.accumPx.x.toDp() } else 0.dp
                val dragOffsetYDp = if (isDragging) with(density) { moveDrag!!.accumPx.y.toDp() } else 0.dp

                val settleXDp = if (isSettlingMove) with(density) { moveSettle.value.x.toDp() } else 0.dp
                val settleYDp = if (isSettlingMove) with(density) { moveSettle.value.y.toDp() } else 0.dp
                val displacementXDp = if (displacementAnim != null) with(density) { displacementAnim.value.x.toDp() } else 0.dp
                val displacementYDp = if (displacementAnim != null) with(density) { displacementAnim.value.y.toDp() } else 0.dp

                val offsetX = when {
                    isDragging -> (dragStartX ?: targetX) + dragOffsetXDp
                    isSettlingMove -> targetX + settleXDp
                    displacementAnim != null -> targetX + displacementXDp
                    else -> targetX
                }
                val offsetY = when {
                    isDragging -> (dragStartY ?: targetY) + dragOffsetYDp
                    isSettlingMove -> targetY + settleYDp
                    displacementAnim != null -> targetY + displacementYDp
                    else -> targetY
                }

                Box(
                    Modifier
                        .offset(x = offsetX, y = offsetY)
                        .width(boxWidth)
                        .height(boxHeight)
                        .graphicsLayer {
                            // Slight scale/elevation lift on the card actually under the finger -
                            // the "picked up" affordance the brief asks for.
                            val lift = isDragging
                            scaleX = if (lift) 1.04f else 1f
                            scaleY = if (lift) 1.04f else 1f
                            shadowElevation = if (isDragging) 16f else 0f
                        }
                        .zIndex(if (isDragging) 1f else 0f)
                        // Content clipping (fifth feel-test pass, defect 3): a widget mock whose
                        // own content is taller than its assigned rowSpan must never bleed a
                        // half-visible glyph past the card's own bottom edge - a HARD clip at
                        // exactly this box's own bounds turns any overflow into a clean edge
                        // instead of a mid-glyph slice. This is the defensive, general guarantee;
                        // the SPECIFIC agenda/record-list mocks that actually overflowed at the
                        // default row height are also fixed at the source (see
                        // `PrototypeGrid.kt`'s `initialGridItems` - those two widget kinds now
                        // start at 2 rows tall, which is comfortably enough for 3 DeckRows plus
                        // chrome) so nothing needs to rely on this clip alone in practice.
                        .clipToBounds()
                        .gridJiggle(active = editMode && !isDragging && !isSettlingMove && displacementAnim == null, seed = item.id.hashCode()),
                ) {
                    GridCellChrome(
                        item = item,
                        editMode = editMode,
                        presetLabel = GridPreset.match(item)?.label ?: "?",
                        presetError = presetErrorId == item.id,
                        onLongPressToEnterEditMode = onEnterEditMode,
                        onRemove = { onRemove(item.id) },
                        onCyclePreset = { cyclePreset(item.id) },
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
                                    // THE SIXTH-PASS BUG lived exactly here: this used to be two
                                    // adjacent, UNNAMED `run { }` blocks passed positionally into
                                    // clampMoveTarget(item, targetRow, targetCol, columnCount) - the
                                    // first block computed a COLUMN value and landed in the
                                    // targetRow slot, the second computed a ROW value and landed in
                                    // targetCol. Every full-width card's targetCol range is exactly
                                    // [0, 0] (columnCount - colSpan == 0), so the swapped-in large
                                    // row-progress value always clamped to col 0, and the swapped-in
                                    // near-zero column value always floored to row 0 - "always
                                    // (0, 0)" regardless of drag direction or distance. Recomputed
                                    // explicitly here (never reused from a composition-scoped val,
                                    // per the fourth-pass commit-path-bug fix) via the SAME
                                    // GridGesture.candidateCell the live preview above calls - one
                                    // implementation, correctly ordered, nothing left to diverge.
                                    val (targetRow, targetCol) = GridGesture.candidateCell(
                                        originRow = d.originRow,
                                        originCol = d.originCol,
                                        accumPxX = d.accumPx.x,
                                        accumPxY = d.accumPx.y,
                                        colPitchPx = colPitchPx,
                                        rowPitchPx = rowPitchPx,
                                    )
                                    val candidate = GridEngine.clampMoveTarget(current, targetRow, targetCol, columnCount)
                                    // DISPLACE, not reject (third feel-test pass): a null result
                                    // here means no arrangement fit at all, not merely "something
                                    // is in the way" - see GridEngine.displaceForPlacement's own doc.
                                    val committed = GridEngine.displaceForPlacement(baseItems, candidate, columnCount)
                                    val settleTarget = if (committed != null) candidate else current
                                    if (committed != null) {
                                        onLayoutChange(committed)
                                        // The deferred polish: every OTHER occupant this drop
                                        // displaced gets the same 200ms settle the dragged card
                                        // itself gets, instead of jumping straight to its new cell.
                                        startDisplacementSettles(baseItems, committed, excludeId = d.id)
                                    }
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
                    ) { itemContent(item) }
                }
            }
        }
    }
}

/** How much of the card's own RIGHT edge the remove chip (top) and size chip (bottom, formerly
 *  the resize handle - see [GridCellChrome]'s own doc) each need reserved so nothing the card's
 *  own content draws can be overlapped by either - the fifth feel-test pass's "half-width card
 *  chrome is broken" defect. 28dp is each chip's own width; 8dp is a small breathing gap so the
 *  reservation does not read as a hard clip line. Reserved on BOTH corners at once by a single END
 *  padding on the whole content column (see [GridCellChrome]), since the two chips sit on the SAME
 *  right edge, top and bottom respectively. */
private val CHIP_CLEARANCE = 36.dp

/**
 * The per-cell edit-mode chrome: drag surface over the whole card (move), a small bottom-right
 * size chip (preset-cycle, formerly a free-resize handle), and a remove chip - the affordances the
 * ticket names. Not exported; [DeckGrid] is the only caller.
 *
 * **The half-width chrome-overlap fix (fifth feel-test pass, 2026-08-23).** The remove chip and
 * size chip are drawn by THIS file, layered on top of `content()` (the caller's own
 * [com.kevin.legion.ui.common.DeckPane]) - `DeckPane` itself has no idea either exists, so its own
 * label-pill ellipsis clamp (`paneWidth - 16dp`) was computed against the FULL card width, with no
 * awareness that the outer 28dp chip could sit on top of the last ~30-40dp of that width. On a
 * full-width card the pill's own text is short enough this never mattered; on a HALF-width card
 * with a two-clause title ("LOG A DRIVE // QUICK ADD"), the pill legitimately rendered text (with
 * its own ellipsis appended) reaching into the chip's zone, and the chip - an OPAQUE box drawn on
 * top - visually swallowed the ellipsis dots along with the last few characters, reading as a raw
 * truncation rather than the intended `…`. **Fix: `content()` itself is measured against a
 * narrower width** ([CHIP_CLEARANCE] reserved off the trailing edge, edit mode only) so
 * `DeckPane`'s OWN ellipsis point - and any right-reaching content inside it, like
 * [com.kevin.legion.ui.common.DeckButton]'s fill width - naturally stops clear of where the chip
 * and size-chip actually sit, rather than teaching `DeckPane` (a widely-shared production
 * component) about an overlay it does not otherwise need to know exists.
 *
 * **The bottom-right corner is a SIZE CHIP now, not a drag-resize handle (fourth generation,
 * 2026-08-23) - see `DeckGrid`'s own file doc.** A single tap (not a drag) cycles the card through
 * its own [GridPreset] subset via [DeckGrid]'s `cyclePreset`; [presetLabel] is the CURRENT preset's
 * one-letter tag ([GridPreset.label] - "S"/"W"/"T"/"L") and [presetError] is a short-lived flash
 * (the tap's own [GridEngine.displaceForPlacement] found nothing that fits) rendered as the chip's
 * background swapping to the quarantine tone, the exact "reject" language the drag/drop snap-preview
 * already uses elsewhere in this file for the identical situation.
 */
@Composable
private fun GridCellChrome(
    item: GridItem,
    editMode: Boolean,
    presetLabel: String,
    presetError: Boolean,
    onLongPressToEnterEditMode: () -> Unit,
    onRemove: () -> Unit,
    onCyclePreset: () -> Unit,
    onMoveDragStart: () -> Unit,
    onMoveDrag: (Offset) -> Unit,
    onMoveDragEnd: () -> Unit,
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
        Box(Modifier.fillMaxWidth().padding(end = if (editMode) CHIP_CLEARANCE else 0.dp)) {
            content()
        }
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
            // The size chip - bottom-right, where the free-resize handle used to be. A plain tap
            // target (combinedClickable, no pointerInput/detectDragGestures at all - this is the
            // whole point of retiring the drag-to-resize gesture), so a finger starting exactly here
            // cycles the preset rather than moving the card; the move-drag surface covers the WHOLE
            // card including this corner, but Compose's default pointer dispatch resolves to the
            // innermost consumer first, so a tap here still reaches this box's own onClick.
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(28.dp)
                    .background(if (presetError) sem.quarantined else sem.chrome)
                    .combinedClickable(onClick = onCyclePreset),
                contentAlignment = Alignment.Center,
            ) {
                Text(presetLabel, style = LegionType.stamp, color = MaterialTheme.colorScheme.background)
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
