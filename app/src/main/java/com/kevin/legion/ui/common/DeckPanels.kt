package com.kevin.legion.ui.common

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kevin.legion.ui.theme.DRAW_IN_MS
import com.kevin.legion.ui.theme.LegionMotion
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.ui.theme.deckEntranceEnabled
import com.kevin.legion.ui.theme.deckMotionEnabled

/**
 * The shared VACUUM/SENTRY components (mission-control ticket 03's bezel-and-chrome geometry,
 * built onto cyberdeck-ui ticket 01's panel language and ticket 03's tag ladder), because every
 * downstream data surface reads the same primitives rather than each hand-rolling a panel. Same
 * extraction posture as [CommonRows.kt] and [GapRow.kt] - built once, on purpose, so a caller
 * needing a new shape is a decision for Kevin, not a per-screen workaround.
 *
 * All components read [LocalLegionSemantics] directly (not a threaded parameter), matching
 * [CommonRows.kt]'s convention: production screens are always inside one
 * [com.kevin.legion.ui.theme.LegionTheme].
 *
 * **Mission-control ticket 13 (2026-08-14) reshapes this file to ticket 03's resolved bezel/chrome
 * spec**: [DeckPane] trades its header row for a label pill and its two-corner brackets for a full
 * frame; [DeckRow] moves to 48dp and gains a 22dp display-only sibling, [DeckFeedRow]; [DeckMeter]
 * swaps which of its two colours is the fill and which is the pace tick; and the shell gains two
 * entirely new primitives, [DeckBezel] and [DeckSectionRule]. [DeckTag]/[QuarantineTag]/
 * [StatusLine] are UNCHANGED by this ticket - ticket 03 answer #5 ruled both survive untouched,
 * and the only edits below are the recolours forced by [DeckRow]'s renamed value token.
 *
 * This ticket does not build any screen and does not touch the tag/control vocabulary that reads
 * these primitives (that is the next mission-control ticket). [ThemePreview.kt] is the L11 gate
 * every later ticket reads before wiring a real screen to these.
 *
 * **Mission-control ticket 14 (2026-08-14) wires [DeckBezel] into the real shell** (it shipped in
 * ticket 13 unused by anything) and touches one more thing ticket 13 called UNCHANGED: [StatusLine]
 * gains the ALARM segment (ticket 04) and the yielding cursor (`cursorSolid`, ticket 07) - both
 * additive, both dead by default, see that composable's own doc for exactly what is and is not
 * wired to real data. It also briefly gave [DeckBezel] boot-trace parameters; **boot was dropped
 * 2026-08-14 by Kevin** and they are gone with it.
 */

// ------------------------------------------------------------------- DeckPane

/**
 * The panel: [com.kevin.legion.ui.theme.DeckPanel] fill, a full 1dp
 * [LegionSemantics.chromeDim] frame (ticket 03 answer, section 3's "pane outline/fill" row -
 * replaces the old faint-border-plus-two-corner-brackets read, which the same ticket judged reads
 * as "double-bordered" rather than "bracketed" once tried against this palette), and a **label
 * pill straddling the top rule** in place of the old header [Row].
 *
 * The pill is what makes the frame read as a pane rather than a plain rounded card: it is drawn
 * OUTSIDE the frame's own clipped content (this composable is a [BoxWithConstraints], not a bare
 * [Column]) and painted with [pillBackground] - the colour of whatever sits BEHIND the pane, not
 * the pane's own fill - so it visually occludes the segment of the top rule it sits over rather
 * than the rule drawing through it. [pillBackground] defaults to
 * [MaterialTheme.colorScheme.background] (the ordinary screen ground); an alarm pane sitting on a
 * differently-coloured surface passes its own.
 *
 * [header] renders [LegionSemantics.chromeText] (the pill's own bright tier, matching its
 * [LegionSemantics.chrome] outline); [headerAccent] is optional and renders
 * [LegionSemantics.faint] - ticket 03 answer: green is gone from the palette entirely, so the old
 * "accent clause is a status word in green" treatment demotes to the same muted tier the header
 * itself used to sit at, rather than inventing a fourth signal colour for one clause.
 *
 * The pill truncates with ellipsis at the pane's own width minus 16dp - **never wraps, never steps
 * the type down** (ticket 03: a pill at one size on one pane and a different size on the next
 * breaks the grid rhythm). A label that does not fit at [MaterialTheme.typography.labelSmall] is a
 * copy problem for the call site to shorten, not a reason to add a second pill size here.
 */
@Composable
fun DeckPane(
    header: String,
    headerAccent: String? = null,
    modifier: Modifier = Modifier,
    pillBackground: Color = MaterialTheme.colorScheme.background,
    // Mission-control ticket 16 follow-up: opt-in, defaulting false, so every pre-existing caller
    // (INTAKE, AGENDA, ALERTS, and every non-HOME screen) is BYTE-FOR-BYTE unaffected. Only
    // [HalfTile]'s BIO/CRED/FLEET/LOG tiles pass true, from inside [EqualHeightRow], which is the
    // one place a caller actually bounds this pane's height and wants the frame to fill it (two
    // tiles sharing a row draw the same border/background height, not just the same invisible
    // outer bounds). Made opt-in rather than unconditional after an unconditional `.fillMaxHeight()`
    // here was tried first and coincided with the ALERTS pane silently losing all its row content
    // on-device - never fully root-caused, and not worth risking on every other caller of this
    // widely-shared composable to chase further. Reasoned to be a genuine no-op under an
    // unconstrained (LazyColumn item) height per Compose's own `FillModifier` source - degrades to
    // passing the incoming min/max straight through when `constraints.hasBoundedHeight` is false -
    // but the on-device symptom said otherwise, and a shared file this many screens read from is
    // not the place to leave an unresolved contradiction sitting on `reasoned` alone.
    stretchToParentHeight: Boolean = false,
    // Mission-control ticket 04's ALARM pane treatment ("panelAlarm fill on the pane, and the
    // pane's border at full chrome"), wired by ticket 04's build ("`DeckPane` gets an opt-in
    // `alarm: Boolean = false`"). Opt-in, defaulting false, so every existing caller across the
    // app is byte-for-byte unaffected - the same posture as [stretchToParentHeight] just above.
    // The only caller passing `true` today is TodayScreen's ALERTS pane, when
    // [com.kevin.legion.ui.TodayGapResolvers.buildAlertRows] returns at least one
    // [com.kevin.legion.ui.AlertTier.ALARM] row.
    alarm: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sem = LocalLegionSemantics.current
    // Ticket 04 section 2's two ALARM materials that land on the frame itself (the pill's own
    // inverted/pulsing treatment is a SEPARATE call site's job, not this shared primitive's - see
    // TodayScreen's ALERTS pane, which renders its own [QuarantineTag] pills per-row): the fill
    // swaps from ordinary panel to [MaterialTheme.colorScheme.errorContainer] (`panelAlarm`,
    // `#170604` - see Theme.kt's DarkScheme doc), and the border swaps from the everyday structural
    // [LegionSemantics.chromeDim] to full-strength [LegionSemantics.chrome], the same red every
    // other alarm surface in the app (DeckBezel's registration ticks, the shell's AlarmSegment)
    // reserves for "something is actually live" (ticket 03's "one hue, spent rarely" audit).
    val paneFill = if (alarm) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface
    val paneBorder = if (alarm) sem.chrome else sem.chromeDim
    // Ticket 14 point 4, "pane entrance": a one-shot fade-in on this pane's own first
    // composition, never on recomposition and never on an ALARM surface - [deckEntranceEnabled]
    // is the pure gate ([alarm] plus the shared [deckMotionEnabled] read) that both conditions
    // reduce to. `LaunchedEffect(Unit)` keys on the pane's OWN composition lifetime: it runs once
    // when this [DeckPane] enters the tree and never again for as long as the same call site stays
    // composed, which is what "once on first composition" means in practice - a value update that
    // merely recomposes this same pane (a new [header], a changed row inside [content]) does not
    // restart it. An alarming pane's [Animatable] starts already at full alpha, so a quarantine or
    // safety row that turns a pane alarming never has to wait on a fade to become visible.
    val motionEnabled = deckMotionEnabled()
    val entranceEnabled = deckEntranceEnabled(alarm = alarm, motionEnabled = motionEnabled)
    val entranceAlpha = remember { Animatable(if (entranceEnabled) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (entranceEnabled) {
            entranceAlpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(LegionMotion.PANE_ENTRANCE_MS, easing = LegionMotion.STANDARD_EASING),
            )
        }
    }
    BoxWithConstraints(modifier.fillMaxWidth().graphicsLayer { alpha = entranceAlpha.value }) {
        Column(
            Modifier
                .fillMaxWidth()
                .let { if (stretchToParentHeight) it.fillMaxHeight() else it }
                // The 8dp top gap is not a taste choice - it is the space the
                // pill straddles INTO. The pill (16dp tall, aligned to this
                // Box's own top-left below) has no top offset of its own, so
                // it spans from this composable's y=0 to y=16dp; the frame's
                // own top rule, pushed down by this padding, lands at y=8dp -
                // exactly centered under the pill, 8dp above / 8dp below.
                .padding(top = 8.dp)
                .background(paneFill)
                .border(1.dp, paneBorder)
                .padding(start = 9.dp, top = 13.dp, end = 9.dp, bottom = 9.dp)
                // Ticket 14 point 5, "state-change animation": a pane whose content grows or
                // shrinks (an empty state collapsing, a row appearing) resizes smoothly instead of
                // snapping - gated on the SAME [entranceEnabled] pure check as the fade above, so
                // an alarm pane's size changes stay instant along with everything else about it.
                .let { if (entranceEnabled) it.animateContentSize(tween(LegionMotion.CONTENT_CHANGE_MS, easing = LegionMotion.STANDARD_EASING)) else it },
        ) {
            content()
        }
        DeckLabelPill(
            header = header,
            headerAccent = headerAccent,
            // Follows the pane's own fill while alarming (ticket 04 build item 5's "pill background
            // following the pane so it still occludes the top rule correctly") - the pill's bottom
            // half overlaps the frame's own top border (see the padding-math comment above), so a
            // pill background that stayed at the caller's ordinary [pillBackground] would show a
            // visible seam of the wrong colour sitting on top of the alarm fill instead of
            // continuing it, right where it most needs to read as one continuous alarm block.
            pillBackground = if (alarm) MaterialTheme.colorScheme.errorContainer else pillBackground,
            // Pane width minus 16dp, per ticket 03 section 2's "max width"
            // row. Never negative: a pane narrower than 16dp is already a
            // broken layout upstream, not a case this clamp should hide.
            maxWidth = (maxWidth - 16.dp).coerceAtLeast(0.dp),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 8.dp),
            alarm = alarm,
        )
    }
}

/**
 * The pill itself, split out of [DeckPane] because it is genuinely a second composable sitting
 * beside the frame, not a decoration inside it - see [DeckPane]'s doc for why it cannot be a child
 * of the frame's own [Column]. Not exported: every caller reaches it through [DeckPane], same
 * posture as keeping this file's helpers scoped to what a build ticket is actually meant to call.
 *
 * **[alarm] (ticket 04 build item 7): the ~0.5Hz pulse belongs HERE**, on the alarming pane's own
 * label pill, not on the shell status line's `AlarmSegment` - that composable's own doc is explicit
 * it stays static, and section 2 of the ticket's answer is explicit the static chrome-fill-plus-word
 * treatment already carries the whole meaning on its own; the pulse here is a bonus, never the sole
 * carrier, which is exactly what makes collapsing it to solid under reduced motion (below) safe
 * rather than a silent loss of the escalation. `~2s period` per the ticket - 1000ms up, 1000ms down,
 * `RepeatMode.Reverse`. Alpha is read at DRAW time via the `graphicsLayer` lambda overload, same
 * "drive draw-phase reads, not composition" discipline [StatusLine]'s own cursor already uses, so
 * the pulse invalidates only this small leaf rather than recomposing the whole pane above it.
 */
@Composable
private fun DeckLabelPill(
    header: String,
    headerAccent: String?,
    pillBackground: Color,
    maxWidth: Dp,
    modifier: Modifier = Modifier,
    alarm: Boolean = false,
) {
    val sem = LocalLegionSemantics.current
    val pillShape = RoundedCornerShape(2.dp)
    val motionEnabled = deckMotionEnabled()
    val pillAlpha = if (alarm && motionEnabled) {
        val transition = rememberInfiniteTransition(label = "alarm-pill-pulse")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.55f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1000),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "alarm-pill-pulse-alpha",
        )
    } else {
        // Reduced motion (or a non-alarm pill, which never animated in the first place) - solid,
        // no InfiniteTransition created at all, matching [StatusLine]'s cursor's own branch.
        remember { mutableStateOf(1f) }
    }
    Box(
        modifier
            .widthIn(max = maxWidth)
            .height(16.dp)
            .graphicsLayer { alpha = pillAlpha.value }
            .clip(pillShape)
            .background(pillBackground)
            .border(1.dp, sem.chrome, pillShape)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = sem.chromeText)) { append(header.uppercase()) }
                if (headerAccent != null) {
                    withStyle(SpanStyle(color = sem.faint)) { append("  //  ") }
                    withStyle(SpanStyle(color = sem.faint)) { append(headerAccent.uppercase()) }
                }
            },
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// -------------------------------------------------------------------- DeckTag

/**
 * The fixed weight ladder from ticket 03, in FULL: silence (no tag) is the
 * strong state for a verified row, [OUTLINE_MUTED] is informational
 * (`EST`/`REPORTED`), [INVERTED_AMBER] is an advisory on data
 * (`UNRECONCILED`, `SET PLAN`, `PACING HOT`), [INVERTED_GREEN] is
 * armed/ok (`ARMED`, `OK`). Red is NOT in this enum: ticket 03 answer #1
 * reserves it exclusively for failed-gate/crisis states, and senior review of
 * ee201c3 (finding 4) ruled that a comment-only guard on an enum value is not
 * enough for a rule CLAUDE.md treats as load-bearing - so the only path to a
 * red tag is [QuarantineTag], and a grep for it IS the audit of every red in
 * the app.
 *
 * **UNCHANGED by mission-control ticket 13** - ticket 03's bezel-and-chrome
 * answer #5 ruled this survives untouched, rendering being ticket 04's.
 */
enum class DeckTagStyle { OUTLINE_MUTED, INVERTED_AMBER, INVERTED_GREEN }

@Composable
fun DeckTag(text: String, style: DeckTagStyle, modifier: Modifier = Modifier) {
    val sem = LocalLegionSemantics.current
    // Not a Triple: its type parameters are invariant, and the OUTLINE_MUTED
    // branch's `bg = null` would infer as `Triple<Nothing?, Color, Boolean>`,
    // which does not unify with the other branches' `Triple<Color, Color,
    // Boolean>` under invariance. Three plain nullable/typed vals sidestep the
    // inference entirely and are no less readable.
    val bg: androidx.compose.ui.graphics.Color? = when (style) {
        DeckTagStyle.OUTLINE_MUTED -> null
        DeckTagStyle.INVERTED_AMBER -> MaterialTheme.colorScheme.primary
        DeckTagStyle.INVERTED_GREEN -> sem.credit
    }
    // Inverted fills take their text from `background` (the ground token), not
    // `onPrimary`: the intent is "ground-dark text on a bright fill", and
    // `background` says that structurally. `onPrimary` happened to hold the
    // same value but was matched to the wrong role - a future retheme that
    // diverged the `on*` roles would have silently miscolored green tags
    // (ee201c3 review, finding 7).
    val fg = if (style == DeckTagStyle.OUTLINE_MUTED) sem.faint else MaterialTheme.colorScheme.background
    val outlined = style == DeckTagStyle.OUTLINE_MUTED
    Box(
        modifier
            .let { if (bg != null) it.background(bg) else it }
            .let { if (outlined) it.border(1.dp, sem.faint) else it }
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text.uppercase(), style = LegionType.stamp, color = fg)
    }
}

/**
 * **The only red tag in the app**, and deliberately the only way to render
 * one. Ticket 03 answer #1: red means a failed-gate/crisis-tier state,
 * exclusively - never a debit, never over-budget, never an advisory. Keeping
 * red out of [DeckTagStyle] makes that rule compiler-visible: a caller cannot
 * reach for red by picking an enum value, it has to name the state
 * (`QuarantineTag`), and misuse shows up in a one-line grep. This is the
 * ee201c3 review's finding 4, applied as API shape rather than comment.
 *
 * `onError` is the role-matched foreground for an [LegionSemantics.quarantined]
 * fill (both are the error family), unlike the borrowed `onPrimary` the
 * inverted tags used to share.
 *
 * **UNCHANGED by mission-control ticket 13** - see [DeckTag]'s doc.
 */
@Composable
fun QuarantineTag(text: String, modifier: Modifier = Modifier) {
    val sem = LocalLegionSemantics.current
    Box(
        modifier
            .background(sem.quarantined)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text.uppercase(), style = LegionType.stamp, color = MaterialTheme.colorScheme.onError)
    }
}

// ------------------------------------------------------------------ DeckMeter

/**
 * A meter bar, 6dp tall (down from 12dp - mission-control ticket 03's "dense row rhythm" section):
 * [LegionSemantics.ruleFaint] (`line`) track, [LegionSemantics.data] (mint) fill, and an optional
 * 2dp [MaterialTheme.colorScheme.primary] (amber) tick at [paceFraction].
 *
 * **The fill and pace-tick colours swap from the MILSPEC build (ticket 13)**: the fill used to be
 * amber with a green pace tick; it is now mint with an amber tick. Ticket 03's reasoning: a meter
 * shows a VALUE, and every value in this palette is mint now that green is retired, while a pace/
 * target line is a HIGHLIGHT, not a verdict - which is exactly what amber ([DeckAmber]'s own doc:
 * "highlights... target line") already means elsewhere in the app. The old green tick read as a
 * pass/fail judgment on the pace; amber reads as "here is the target", which is what it actually is.
 *
 * The fill animates from its previous value to [fraction] over [DRAW_IN_MS]
 * (ticket 04 answer #2, "meters fill... over ~350ms on screen entry. Never
 * loop") - [androidx.compose.animation.core.animateFloatAsState] re-animates
 * only when [fraction] itself changes, so a meter that is not being given a
 * new value never redraws; it does not loop or pulse on its own. When
 * [com.kevin.legion.ui.theme.deckMotionEnabled] is false the same state holder
 * uses [snap] instead of [tween], so the very first frame already shows the
 * final fill - ticket 04 answer #5.
 */
@Composable
fun DeckMeter(fraction: Float, paceFraction: Float? = null, modifier: Modifier = Modifier) {
    val sem = LocalLegionSemantics.current
    val motionEnabled = deckMotionEnabled()
    val target = fraction.coerceIn(0f, 1f)
    val animatedFraction by animateFloatAsState(
        targetValue = target,
        animationSpec = if (motionEnabled) tween(DRAW_IN_MS) else snap(),
        label = "deck-meter-fill",
    )
    val trackColor = sem.ruleFaint
    val fillColor = sem.data
    val paceColor = MaterialTheme.colorScheme.primary
    Canvas(modifier.fillMaxWidth().height(6.dp)) {
        drawRect(trackColor)
        drawRect(fillColor, size = size.copy(width = size.width * animatedFraction))
        if (paceFraction != null) {
            val x = size.width * paceFraction.coerceIn(0f, 1f)
            drawRect(paceColor, topLeft = Offset(x - 1.dp.toPx(), 0f), size = size.copy(width = 2.dp.toPx()))
        }
    }
}

// -------------------------------------------------------------------- DeckRow

/**
 * The tappable row, 48dp tall (M3's own touch-target floor - the same 48dp
 * [StatusLine]'s SETUP stamp already pads to). Mission-control ticket 03's "a 22dp feed row cannot
 * be tappable" finding is what splits the old dynamic-height [DeckRow] into two components: this
 * one for anything a screen wires a click onto, [DeckFeedRow] for a dense display-only feed.
 *
 * A dashed top hairline (ticket 01: "rows separated by DASHED hairlines"), [label] in muted caps
 * on the left that TRUNCATES ([TextOverflow.Ellipsis]) because a label is a description, and
 * [value] in bold mono on the right that NEVER truncates - a value getting clipped is a worse
 * failure than a label running long, matching CLAUDE.md §4's money-never-lies posture extended to
 * layout. [tag] is optional and sits between the two, per ticket 03's exception-tagging rule: most
 * rows pass `tag = null` and read as silence, the strong state.
 *
 * [value]'s colour moves from [MaterialTheme.colorScheme.primary] (amber) to
 * [LegionSemantics.data] (mint) under ticket 03 - amber is a highlight now, not the default value
 * colour; an ordinary row reading is mint like every other value in the app.
 */
@Composable
fun DeckRow(label: String, value: String, tag: (@Composable RowScope.() -> Unit)? = null, modifier: Modifier = Modifier) {
    val sem = LocalLegionSemantics.current
    val dashStroke = with(LocalDensity.current) { 1.dp.toPx() }
    Row(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .drawBehind {
                drawLine(
                    color = sem.ruleFaint,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = dashStroke,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f), 0f),
                )
            }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            label.uppercase(),
            style = LegionType.stamp,
            color = sem.faint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (tag != null) tag()
        Text(
            value,
            style = LegionType.amount,
            color = sem.data,
            maxLines = 1,
            overflow = TextOverflow.Visible,
        )
    }
}

// ---------------------------------------------------------------- DeckFeedRow

/**
 * The dense feed row - 22dp tall, **display only, never tappable** (mission-control ticket 03's
 * sharpest finding: a 22dp row cannot carry a 48dp touch target, so a dense feed and a tappable
 * list are two components, not one component with a flag). Three columns - [code] fixed at 40dp,
 * [name] flex, [value] auto-sized to its own text - with 8dp gutters between them, matching
 * [DeckRow]'s column rhythm.
 *
 * Same dashed 1dp [LegionSemantics.ruleFaint] top hairline as [DeckRow] (6-on-5 dash, carried
 * verbatim), and the same "the label truncates, the value never does" rule - here that applies to
 * BOTH [code] and [name], since either can run long in a real PID/ledger/pantry feed, while [value]
 * still never clips. [value] reads [LegionSemantics.data] (mint), same as [DeckRow]'s.
 *
 * **No zebra striping, ever** (ticket 03 section 3) - twenty rows stay scannable on the dashed
 * hairline and the 40dp code column alone; a second panel fill would compete with the fills this
 * palette already spends on real meaning.
 */
@Composable
fun DeckFeedRow(code: String, name: String, value: String, modifier: Modifier = Modifier) {
    val sem = LocalLegionSemantics.current
    val dashStroke = with(LocalDensity.current) { 1.dp.toPx() }
    Row(
        modifier
            .fillMaxWidth()
            .height(22.dp)
            .drawBehind {
                drawLine(
                    color = sem.ruleFaint,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = dashStroke,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f), 0f),
                )
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            code.uppercase(),
            style = LegionType.stamp,
            color = sem.faint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(40.dp),
        )
        Text(
            name.uppercase(),
            style = LegionType.stamp,
            color = sem.faint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = LegionType.amount,
            color = sem.data,
            maxLines = 1,
            overflow = TextOverflow.Visible,
        )
    }
}

// ------------------------------------------------------------------ DeckBezel

/**
 * **NEW (mission-control ticket 13, from ticket 03's bezel-and-chrome answer).** The one bezel
 * drawn once at shell level, wrapping the whole content area rather than each panel carrying its
 * own frame - the charting decision ticket 03 turned into geometry.
 *
 * Drawn with a single [drawBehind] pass rather than nested [border] modifiers, because the frame
 * is not a rectangle: a rounded rect with two straight-line BREAKS (top and bottom centre, 64dp
 * each, where the line is simply not drawn) is not expressible as a stack of borders. The four
 * corners are drawn as quarter-circle arcs (14dp radius), the left/right edges as unbroken
 * vertical lines, and the top/bottom edges as two line segments each, split around the centred
 * break. All ten pieces read [LegionSemantics.chromeDim] - the STRUCTURAL chrome tier, per ticket
 * 01's finding that full-strength chrome on every structural line would turn the screen into a
 * grid of alarms.
 *
 * The four L-shaped registration ticks are the one place full-strength [LegionSemantics.chrome]
 * appears when nothing is wrong (ticket 03 answer). Each is an elbow inset 5dp inside its corner
 * with two 6dp arms extending inward, following the same "elbow at the near point, arms extend
 * into the content" convention [DeckPane]'s old corner brackets used - not specified further by
 * ticket 03's geometry table, so this is the most literal reading of "6dp arms... inset 5dp inside
 * each corner" available without a rendered mock to check against.
 *
 * Does **not** apply system-bar insets itself - ticket 03's "6dp inset from the edge, inside
 * system insets, not under them" describes the intended on-screen result once a caller sizes/pads
 * this to sit inside the window insets (e.g. `Modifier.fillMaxSize().windowInsetsPadding(...)`
 * passed in as [modifier]); wiring that into a real shell is scoped to a later ticket, not this
 * one, which builds the primitive only.
 *
 * Wraps [content] in a plain [Box] with **no clip** - the content padding (9 left / 10 top / 9
 * right / 12 bottom, matching ticket 03's "content padding inside the line" row) keeps ordinary
 * content off the frame, but nothing here stops a caller's own content from drawing past it on
 * purpose.
 *
 * **Wired into the shell by mission-control ticket 14** ([com.kevin.legion.ui.MainActivity]'s
 * `LegionShell` wraps the whole `Scaffold` - content AND the pinned status line / Alfred strip /
 * hard-key row - in one [DeckBezel], per ticket 08 answer #1: driving mode gets the full deck
 * language too, so there is deliberately no `isDrivingMode` carve-out at this call site; the
 * bezel is unconditional and it is [NavHost]'s own destinations, not the shell, that decide what
 * shows inside it.
 *
 * **The frame is drawn in one static pass.** It briefly carried `traceProgress`/`ticksVisible`
 * parameters so the boot sequence could trace it on from the corners (ticket 14, spending ticket
 * 07 answer #8). **Boot was dropped 2026-08-14 by Kevin** - cold process start on the target device
 * exceeds 1.2s to first draw against an 800ms sequence, so the animation was largely invisible in
 * practice and did not earn its complexity. Both parameters and the interpolation they drove are
 * gone rather than left defaulted, since a dead parameter pointing at a deleted file is exactly the
 * rot this file's own conventions warn about. The side edges stay pre-split into halves: that is
 * now purely a drawing convenience, not the remnant of an animation.
 */
@Composable
fun DeckBezel(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val sem = LocalLegionSemantics.current
    val lineColor = sem.chromeDim
    val tickColor = sem.chrome
    Box(
        modifier
            .fillMaxSize()
            .drawBehind {
                val inset = 6.dp.toPx()
                val radius = 14.dp.toPx()
                val breakLen = 64.dp.toPx()
                val tickArm = 6.dp.toPx()
                val tickInset = 5.dp.toPx()
                val strokeWidth = 1.dp.toPx()
                val stroke = Stroke(width = strokeWidth)

                val left = inset
                val top = inset
                val right = size.width - inset
                val bottom = size.height - inset
                val diameter = radius * 2f

                // Four corner arcs (quarter circles). drawArc's own convention: 0deg = 3
                // o'clock, sweeping clockwise as the angle grows (Canvas y grows downward).
                val sweep = 90f
                drawArc(
                    lineColor,
                    startAngle = 180f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(left, top),
                    size = androidx.compose.ui.geometry.Size(diameter, diameter),
                    style = stroke,
                )
                drawArc(
                    lineColor,
                    startAngle = 270f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(right - diameter, top),
                    size = androidx.compose.ui.geometry.Size(diameter, diameter),
                    style = stroke,
                )
                drawArc(
                    lineColor,
                    startAngle = 0f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(right - diameter, bottom - diameter),
                    size = androidx.compose.ui.geometry.Size(diameter, diameter),
                    style = stroke,
                )
                drawArc(
                    lineColor,
                    startAngle = 90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(left, bottom - diameter),
                    size = androidx.compose.ui.geometry.Size(diameter, diameter),
                    style = stroke,
                )

                // Left and right edges, each drawn as a top half and a bottom half that meet at
                // the midpoint. Two calls per side rather than one is a leftover shape from the
                // dropped boot trace; it draws identically to a single line and is kept because
                // splitting at the midpoint costs nothing and reads symmetrically with the
                // top/bottom edges, which genuinely are split around their break.
                val vHalf = ((bottom - radius) - (top + radius)) / 2f
                drawLine(lineColor, Offset(left, top + radius), Offset(left, top + radius + vHalf), strokeWidth)
                drawLine(lineColor, Offset(left, bottom - radius), Offset(left, bottom - radius - vHalf), strokeWidth)
                drawLine(lineColor, Offset(right, top + radius), Offset(right, top + radius + vHalf), strokeWidth)
                drawLine(lineColor, Offset(right, bottom - radius), Offset(right, bottom - radius - vHalf), strokeWidth)

                // Top and bottom edges: each split into two segments around a 64dp break centred
                // on the edge. The break is what makes this read as a machined bezel rather than
                // a rounded rectangle (ticket 03 section 1). The coerce guards keep a very narrow
                // screen from drawing a segment backwards.
                val breakHalf = breakLen / 2f
                val centerX = left + (right - left) / 2f
                val breakLeftX = (centerX - breakHalf).coerceAtLeast(left + radius)
                val breakRightX = (centerX + breakHalf).coerceAtMost(right - radius)
                if (breakLeftX > left + radius) drawLine(lineColor, Offset(left + radius, top), Offset(breakLeftX, top), strokeWidth)
                if (breakRightX < right - radius) drawLine(lineColor, Offset(right - radius, top), Offset(breakRightX, top), strokeWidth)
                if (breakLeftX > left + radius) drawLine(lineColor, Offset(left + radius, bottom), Offset(breakLeftX, bottom), strokeWidth)
                if (breakRightX < right - radius) drawLine(lineColor, Offset(right - radius, bottom), Offset(breakRightX, bottom), strokeWidth)

                // Four L-shaped registration ticks, full-strength chrome. Elbow inset 5dp
                // inside each corner, arms extending 6dp further inward. These are the only
                // full-strength chrome on screen when nothing is wrong (ticket 03 section 1).
                // Top-left.
                run {
                    val ex = left + tickInset
                    val ey = top + tickInset
                    drawLine(tickColor, Offset(ex, ey), Offset(ex + tickArm, ey), strokeWidth)
                    drawLine(tickColor, Offset(ex, ey), Offset(ex, ey + tickArm), strokeWidth)
                }
                // Top-right.
                run {
                    val ex = right - tickInset
                    val ey = top + tickInset
                    drawLine(tickColor, Offset(ex, ey), Offset(ex - tickArm, ey), strokeWidth)
                    drawLine(tickColor, Offset(ex, ey), Offset(ex, ey + tickArm), strokeWidth)
                }
                // Bottom-left.
                run {
                    val ex = left + tickInset
                    val ey = bottom - tickInset
                    drawLine(tickColor, Offset(ex, ey), Offset(ex + tickArm, ey), strokeWidth)
                    drawLine(tickColor, Offset(ex, ey), Offset(ex, ey - tickArm), strokeWidth)
                }
                // Bottom-right.
                run {
                    val ex = right - tickInset
                    val ey = bottom - tickInset
                    drawLine(tickColor, Offset(ex, ey), Offset(ex - tickArm, ey), strokeWidth)
                    drawLine(tickColor, Offset(ex, ey), Offset(ex, ey - tickArm), strokeWidth)
                }
            }
            // Ticket 03's content padding is 9/10/9/12 measured FROM THE DRAWN LINE, and the line
            // itself sits 6dp inset with a 1dp stroke. This padding applies from the Box's own
            // edge, so each value carries the 7dp the frame occupies before it: 6dp inset + 1dp
            // line + the spec. That is what makes ticket 03's stated total width cost - "32dp,
            // 6 + 1 + 9, doubled" - come out right, and it puts the grid's interior at the 328dp
            // ticket 05's tile arithmetic assumes.
            //
            // Shipped briefly as a bare 9/10/9/12 (mission-control ticket 14), which left content
            // clearing the line by 2dp horizontally and 5dp vertically. That was caught twice
            // before it was understood: ticket 14 measured the bottom gap at ~5.5dp against 12dp
            // and filed it as a deviation, and ticket 16's HOME build measured the interior at
            // 341dp against 328dp and filed it as horizontal drift in the planning doc. One bug,
            // two symptoms, both by pixel sampling rather than by eye.
            .padding(start = 16.dp, top = 17.dp, end = 16.dp, bottom = 19.dp),
        content = content,
    )
}

// ------------------------------------------------------------- DeckSectionRule

/**
 * **NEW (mission-control ticket 13, from ticket 03's bezel-and-chrome answer).** A grouping device
 * for a dense feed - ticket 03 section 3: "grouping is the section rule's job", explicitly ruled
 * out zebra striping as the alternative. Previously hand-rolled per screen (ticket 03's own verdict
 * table); this is the one shared version every later data-surface ticket should reach for instead.
 *
 * [label] at [MaterialTheme.typography.labelSmall] in [LegionSemantics.chromeText] - the same
 * bright chrome tier [DeckPane]'s pill text uses, since a section rule is doing the same "this is
 * structure, not data" job a pill does - followed by a 1dp [LegionSemantics.chromeDim] line filling
 * the remaining width. 11dp above, 5dp below (ticket 03's table). The 8dp gap between the label and
 * the line is not itself specified by ticket 03's geometry table; it matches the 8dp gutter used
 * throughout [DeckRow]/[DeckFeedRow]'s own columns rather than inventing a new spacing constant.
 */
@Composable
fun DeckSectionRule(label: String, modifier: Modifier = Modifier) {
    val sem = LocalLegionSemantics.current
    Row(
        modifier
            .fillMaxWidth()
            .padding(top = 11.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = sem.chromeText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(sem.chromeDim),
        )
    }
}

// ----------------------------------------------------------------- StatusLine

/**
 * The global top status line: [left] on the leading edge with a trailing
 * blinking block cursor, [clock] trailing. **The app's ONE ambient
 * animation** (ticket 04 answer #3) - nothing else in the deck animates
 * continuously, on purpose, for battery and to keep continuous animation out
 * of recomposition-heavy trees.
 *
 * The blink is read in the DRAW phase, not the composition phase: the cursor
 * [Box] uses `Modifier.graphicsLayer { alpha = cursorAlpha.value }`, whose
 * lambda overload defers the [androidx.compose.runtime.State] read to
 * layout/draw, so the 500ms toggle invalidates only this small leaf's draw
 * pass rather than recomposing [StatusLine] or anything above it - the
 * "drive draw-phase reads, not composition" guidance this ticket's brief
 * points at (`compose-state-deferred-reads`).
 *
 * When [com.kevin.legion.ui.theme.deckMotionEnabled] is false, no
 * [androidx.compose.animation.core.InfiniteTransition] is created at all -
 * the cursor renders solid (`alpha = 1f`) and never toggles again, matching
 * ticket 04 answer #5's "the cursor stops blinking" for reduced motion.
 *
 * **[onOpenSettings] (2026-08-12): the SETUP stamp, and the only way into
 * `settings/` that exists.** Traced that day: the sole
 * `navigate(LegionRoute.SETTINGS)` call site in the app was
 * [com.kevin.legion.ui.assistant.AssistantStrip]'s mic-blocked branch, which
 * fires only when the assistant is ON *and* RECORD_AUDIO has been revoked -
 * and the assistant can only be switched on from Settings. That is a closed
 * loop: on any ordinary device Settings, and therefore the Gemini key, Drive
 * sync, companions, and Spotify screens under it, could not be reached at all.
 *
 * It lands HERE rather than as a sixth hard key because cyberdeck-ui ticket
 * 05's Answer is explicit - "utility screens stay reachable through the
 * existing settings route, no bespoke key" - and because this line already
 * reports SYNC/OBD/KEY, every one of which is fixed in Settings. Making the
 * line that reports a problem also the way to act on it is the coherent
 * placement, not merely the convenient one.
 *
 * Null (the default) renders no stamp, which keeps [StatusLine] previewable
 * and leaves ThemePreview's call untouched.
 *
 * **Mission-control ticket 14 reshapes this, in two ways**, both additive - every existing caller
 * (there was exactly one, [com.kevin.legion.ui.MainActivity]'s `LegionShell`) is byte-for-byte
 * unaffected until it opts in:
 *
 * 1. **[alarmCount] / [onOpenAlarm] / [keySegment]** (ticket 04 answer, section 6: "while an ALARM
 *    is present, the segment replaces SYNC and OBD; they return when it clears. The clock and date
 *    stay."). [left] keeps carrying whatever the caller normally shows in that slot (today: the
 *    `SYNC ... OBD ...` half of `MainActivity.kt`'s `shellStatusLine` split); [keySegment] is the
 *    part that must survive an alarm (the ticket's "KEY" clause) and is rendered AFTER the alarm
 *    pill instead of [left] once [alarmCount] is greater than zero.
 *
 *    **WIRED, ticket 04's build (2026-08-18)**: `LegionShell` now folds
 *    [com.kevin.legion.ledger.LedgerController.quarantinedCount] into the same `STATUS_POLL_MS`
 *    poll that already refreshed `shellStatusLine`, splits that function's old single string into
 *    `left` / `keySegment` via the pure `formatShellStatusLine`, and passes both plus the live
 *    count through to this composable, with `onOpenAlarm` navigating to `LegionRoute.TODAY`
 *    (ticket 04 answer §6: "tapping the segment navigates to TODAY", where the ALERTS pane lists
 *    every alarm - not to Money, since several ALARM rows can be live at once and ALERTS is the
 *    surface that owns the list, not any one aspect). An active vehicle fault (DTC) is ticket 04's
 *    OTHER named ALARM example and is deliberately NOT a second source feeding [alarmCount] here -
 *    see `IngestedFileDao.countQuarantined`'s own doc for why (a DTC read is a live OBD scan, not
 *    persisted state this poll can cheaply add).
 *    Rendered as the ticket's "inverted pill treatment: solid `chrome` fill, `ground`-coloured
 *    text" - see the private `AlarmSegment` helper below. Tapping it calls [onOpenAlarm].
 * 2. **[cursorSolid]** (ticket 07 answer, "the cursor yields": "on a surface that defines its own
 *    ambient element, the cursor renders solid"). Defaults `false`, preserving the shipped blink.
 *    **Nothing sets this yet** - FLEET's own build ticket is the first surface with a genuinely
 *    live ambient element (the uplink sweep, gated on OBD being connected), and it is that
 *    ticket's job to thread `true` down to this line while its own sweep is running, per ticket
 *    07's precedence stack (alarm pulse > surface ambient > shell cursor - a surface that sets
 *    [cursorSolid] because of its own ambient element should also set it, or set [alarmCount],
 *    while ITS OWN alarm pulse is running, so at most one element in view ever moves).
 *
 * **UNCHANGED from mission-control ticket 13** (ticket 03 answer #5): still lives inside
 * [DeckBezel] in the real shell (ticket 14 is what actually wires that), and the deferred-read
 * cursor mechanism itself is untouched - [cursorSolid] only changes which VALUE feeds it.
 */
@Composable
fun StatusLine(
    left: String,
    clock: String,
    modifier: Modifier = Modifier,
    onOpenSettings: (() -> Unit)? = null,
    keySegment: String? = null,
    alarmCount: Int = 0,
    onOpenAlarm: (() -> Unit)? = null,
    cursorSolid: Boolean = false,
) {
    val sem = LocalLegionSemantics.current
    val motionEnabled = deckMotionEnabled()
    // The cursor yields (ticket 07): a surface with its own ambient element, or an active alarm
    // elsewhere in the precedence stack, sets [cursorSolid] so this is never the second thing
    // moving on screen. Reduced motion already forces the same solid state via [motionEnabled];
    // [cursorSolid] is just a second, independent reason to skip the InfiniteTransition entirely.
    val cursorAlpha = if (motionEnabled && !cursorSolid) {
        val transition = rememberInfiniteTransition(label = "status-cursor")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = 1000
                    1f at 0
                    1f at 450
                    0f at 500
                    0f at 950
                },
                repeatMode = RepeatMode.Restart,
            ),
            label = "status-cursor-alpha",
        )
    } else {
        remember { mutableStateOf(1f) }
    }
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (alarmCount > 0) {
                AlarmSegment(count = alarmCount, onClick = onOpenAlarm)
                if (keySegment != null) {
                    Text(
                        keySegment.uppercase(),
                        style = LegionType.stamp,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            } else {
                Text(left.uppercase(), style = LegionType.stamp, color = MaterialTheme.colorScheme.onBackground)
            }
            Box(
                Modifier
                    .padding(start = 4.dp)
                    .size(width = 6.dp, height = 12.dp)
                    .graphicsLayer { alpha = cursorAlpha.value }
                    .background(MaterialTheme.colorScheme.onBackground),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onOpenSettings != null) {
                // Padding, not a .size() - the stamp is small text and the tap
                // target has to clear 48dp without the label growing to match.
                Text(
                    "SETUP",
                    style = LegionType.stamp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable(onClick = onOpenSettings)
                        .padding(horizontal = 10.dp, vertical = 14.dp),
                )
            }
            Text(clock, style = LegionType.stamp, color = sem.faint)
        }
    }
}

/**
 * The ALARM segment's inverted-pill rendering (ticket 04 answer, section 2: "structurally free per
 * ticket 03 - a pill already paints whatever is behind it, so this needs no new component" -
 * applied here to plain text since [StatusLine] sits directly on the shell ground rather than on a
 * pane, so there is no "paint what's behind it" trick to inherit from [DeckPane]; this is the same
 * solid-fill-plus-ground-text READING, built as its own small [Box] rather than a literal pill
 * shape). Solid [LegionSemantics.chrome] fill, [MaterialTheme.colorScheme.background] (ground)
 * text - full-strength chrome, matching [DeckBezel]'s registration ticks as the other place this
 * tier appears when something is actually live. Static, not pulsing: ticket 04 section 2 reserves
 * the ~0.5Hz pulse for the ALARM PANE's own pill on the alarming surface itself, not for this
 * summary segment, and the scope that added this segment asked for the inverted treatment only.
 * Not exported - [StatusLine] is the only caller, same posture as [DeckLabelPill].
 */
@Composable
private fun AlarmSegment(count: Int, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val sem = LocalLegionSemantics.current
    Box(
        modifier
            .background(sem.chrome)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            "ALARM $count".uppercase(),
            style = LegionType.stamp,
            color = MaterialTheme.colorScheme.background,
        )
    }
}
