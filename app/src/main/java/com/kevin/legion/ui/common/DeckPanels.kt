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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kevin.legion.R
import com.kevin.legion.ui.theme.DRAW_IN_MS
import com.kevin.legion.ui.theme.LegionMotion
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.ui.theme.deckEntranceEnabled
import com.kevin.legion.ui.theme.deckMotionEnabled
import com.kevin.legion.ui.theme.soft.MsIcon
import com.kevin.legion.ui.theme.soft.SoftColors

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
 * swaps which of its two colours is the fill and which is the pace tick; and the shell gained an
 * entirely new primitive, `DeckBezel` (see below), plus [DeckSectionRule]. [DeckTag]/[QuarantineTag]/
 * [StatusLine] are UNCHANGED by this ticket - ticket 03 answer #5 ruled both survive untouched,
 * and the only edits below are the recolours forced by [DeckRow]'s renamed value token.
 *
 * This ticket does not build any screen and does not touch the tag/control vocabulary that reads
 * these primitives (that is the next mission-control ticket). [ThemePreview.kt] is the L11 gate
 * every later ticket reads before wiring a real screen to these.
 *
 * **Mission-control ticket 14 (2026-08-14) wires `DeckBezel` into the real shell** (it shipped in
 * ticket 13 unused by anything) and touches one more thing ticket 13 called UNCHANGED: [StatusLine]
 * gains the ALARM segment (ticket 04) and the yielding cursor (`cursorSolid`, ticket 07) - both
 * additive, both dead by default at the time, see that composable's own doc for what is and is not
 * wired to real data as of THIS ticket. It also briefly gave `DeckBezel` boot-trace parameters;
 * **boot was dropped 2026-08-14 by Kevin** and they went with it.
 *
 * **`DeckBezel` itself, and [StatusLine]'s cursor/ALARM shape from ticket 14, are RETIRED by
 * home-launcher ticket 02 (2026-09-27, ADR 0051)** - the mission-control look this file's own name
 * still carries is superseded surface by surface, starting with the shell chrome. `DeckBezel` is
 * deleted outright (see the comment at its old location, just above [DeckSectionRule]); [StatusLine]
 * keeps its alarm handling but drops the blinking cursor entirely - see that composable's own,
 * rewritten doc for the soft-Material shape it has now. Every OTHER primitive in this file
 * ([DeckPane], [DeckTag], [QuarantineTag], [DeckMeter], [DeckRow], [DeckFeedRow], [DeckSectionRule])
 * is untouched - they still serve the mission-control screens ADR 0051 has not reached yet.
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
 *
 * **[valueColor] defaults to [LegionSemantics.data] (mint) - unchanged for every pre-existing
 * caller** (added 2026-09-01, `ui/MetersScreen.kt`'s absence-vs-data defect: every hero on that
 * screen rendered mint regardless of whether it was a real reading, an absence like "not logged"
 * or "disconnected", or a breach, so nothing on the screen drew the eye and an unreadable state
 * looked identical to a healthy one). Mint stays reserved for an actual measured value; a caller
 * with an absence to report passes [LegionSemantics.ghost] (muted, not alarmed - "no data" is not
 * a fault), and a caller reporting a breach or a down state passes [LegionSemantics.chromeText]
 * (the warning tier already used for OVER/OVERDUE tags elsewhere on this same screen). The WORDS
 * ("NOT LOGGED", "DISCONNECTED", "0 DUE") still carry the meaning either way - CLAUDE.md §7's
 * "colour is never the only signal" - this parameter only stops colour from actively misleading.
 */
@Composable
fun DeckRow(
    label: String,
    value: String,
    tag: (@Composable RowScope.() -> Unit)? = null,
    modifier: Modifier = Modifier,
    valueColor: Color? = null,
) {
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
            color = valueColor ?: sem.data,
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

// [DeckBezel] REMOVED (home-launcher ticket 02, ADR 0051). It was the one global mission-control
// frame ("a rounded rect with two straight-line BREAKS... four L-shaped registration ticks"),
// wired into the shell by mission-control ticket 14 and wrapping MainActivity's whole Scaffold.
// Ticket 01's resolution ("How far the look reaches now") retires it along with the rest of the
// mission-control chrome - a soft-Material HOME is not framed in the old bezel, and the ticket's
// own words are explicit: "the mission-control bezel goes, so home is not framed in the old look."
// `MainActivity.kt`'s `LegionShell` no longer wraps its `Scaffold` in this - see that file's own
// comment at the removal site. Grep-confirmed no other caller in `app/src` before deleting the
// composable itself; `python tools/docs_check.py` confirmed nothing under `docs/` names it either.

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
 * The global top status line, restyled for the soft-Material shell (home-launcher ticket 02, ADR
 * 0050) - one of the two pieces of shell chrome this ticket converts (the other is
 * [com.kevin.legion.ui.assistant.AssistantStrip]). Every other screen keeps
 * [com.kevin.legion.ui.theme.LegionTheme] until its own ticket, so `MainActivity.kt`'s `LegionShell`
 * wraps just this call in [com.kevin.legion.ui.theme.soft.SoftTheme] rather than this composable
 * moving out of the shared file it has always lived in.
 *
 * **What is RETIRED from the mission-control version, and why.** The blinking block cursor
 * (cyberdeck-ui ticket 04's "the app's ONE ambient animation") is gone with the design language it
 * was part of - a soft chrome has no bezel/registration-tick vocabulary for a cursor to read
 * alongside, and nothing in ticket 02's spec asks for a replacement ambient element. [cursorSolid]
 * and the `fleetSweepActive` plumbing that fed it and ONLY it (mission-control ticket 07's "the
 * cursor yields") are retired with it - see `MainActivity.kt`'s own comment at the removal site. The
 * SETUP text stamp becomes a real `IconButton` (`ms_settings`) - the same 48dp touch target the
 * stamp already padded to, just drawn as an icon instead of tracked caps.
 *
 * **What SURVIVES, reshaped.** Alarm handling (cyberdeck-ui ticket 04) still hides nothing: the pill
 * renders BESIDE the ordinary sync/OBD/key content rather than replacing it, a deliberate widening
 * from the mission-control version's "the segment replaces SYNC and OBD" (that version was
 * space-constrained by a fixed-width monospace stamp; a wrapped, proportional-type row is not).
 * Ticket 02's own "the key segment still survives beside [the alarm pill]" instruction is honoured
 * trivially under this reading - [keyLabel], when non-null, always renders, alarm or not, so there
 * is nothing for the pill to displace.
 *
 * Left to right: an 8dp sync dot ([SoftColors.good] / [SoftColors.text3]) plus "Synced"/"Sync off",
 * the OBD state in words, [keyLabel] if there is one to show (null means the key is armed - nothing
 * to disclose, matching CLAUDE.md §7's "estimates/failures are labelled, a healthy state need not
 * shout"), then the alarm pill once [alarmCount] is positive. Right: the clock, then an APPS icon
 * button if [onOpenApps] is given, then the settings icon button - the only way into `settings/`,
 * unchanged in that respect from the mission-control version (2026-08-12: the sole
 * `navigate(LegionRoute.SETTINGS)` call site elsewhere in the app is
 * [com.kevin.legion.ui.assistant.AssistantStrip]'s mic-blocked branch, which itself requires the
 * assistant to already be on - a closed loop broken only by this button).
 *
 * **States are worded, never colour alone** (CLAUDE.md §4/§7): "Sync off" / "OBD off" / [keyLabel]'s
 * own "Key not set" text carry the meaning; colour (`good`/`text3`, `caution`) reinforces it.
 */
@Composable
fun StatusLine(
    synced: Boolean,
    obdConnected: Boolean,
    clock: String,
    modifier: Modifier = Modifier,
    /** Null (the default, meaning the key is armed) discloses nothing - see the class doc. */
    keyLabel: String? = null,
    onOpenSettings: (() -> Unit)? = null,
    /**
     * The app drawer (ADR 0050, LEGION as the phone's home app): reachable from the header on
     * every screen. Null hides the button.
     */
    onOpenApps: (() -> Unit)? = null,
    /**
     * The quiet-mode toggle. Added 2026-09-27, same session as [onOpenApps]. Unlike that one, no
     * commit existed to check against at the time this parameter pair was first added (checked
     * `origin/dev`, `memory/library/decisions.md` and every `.scratch` ticket title naming "quiet"
     * - none described this exact toggle) - it was built on the orchestrator's instruction alone.
     * The exact parameter names and the "Quiet"/"Quiet on" wording were subsequently confirmed by
     * the session that owns the `QuietMode` controller (legion-10). Null (the default) hides the
     * button entirely, matching [onOpenApps]'s own posture - this ticket's `MainActivity.kt`
     * caller passes neither; wiring a real callback is `QuietMode`'s own build, not this ticket's.
     */
    onToggleQuiet: (() -> Unit)? = null,
    /** Whether quiet mode is currently on - see [onToggleQuiet]. Ignored while that is null. */
    quietOn: Boolean = false,
    alarmCount: Int = 0,
    onOpenAlarm: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(SoftColors.ground)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // weight(1f) on a FlowRow, not Arrangement.SpaceBetween with a plain Row - the left content
        // is the side that can genuinely grow long (sync + OBD + an optional key clause + an
        // optional alarm pill), and it must never come at the cost of the clock/apps/settings
        // cluster on the right, or of silently hiding one of its own clauses. Two shapes were tried
        // and rejected first, both caught in the recorded PNGs before this fix: a plain
        // Arrangement.SpaceBetween Row let a long left combination (synced, OBD linked, "Key not
        // set", "2 alarms") squeeze the clock down to a width where IT wrapped onto two lines
        // (`status-line-2-alarms-and-key.png`, first cut); giving that same plain Row a weight(1f)
        // protected the clock but then CLIPPED the alarm pill's own text down to an unreadable
        // sliver instead - exactly the "silently hiding a warning" CLAUDE.md §4/§7 forbids, just
        // moved to a different element. A [FlowRow] wraps whichever clauses do not fit onto a
        // second line instead of clipping or squeezing any of them - the row's "about 40dp" height
        // (this composable's own doc) is the ordinary case; a rare worst-case combination of every
        // disclosure at once costs height instead of costing legibility.
        FlowRow(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(if (synced) SoftColors.good else SoftColors.text3, CircleShape),
                )
                Text(
                    if (synced) "Synced" else "Sync off",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SoftColors.text2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                if (obdConnected) "OBD linked" else "OBD off",
                style = MaterialTheme.typography.bodyMedium,
                color = SoftColors.text2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (keyLabel != null) {
                Text(
                    keyLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SoftColors.caution,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (alarmCount > 0) {
                AlarmPill(count = alarmCount, onClick = onOpenAlarm)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                clock,
                // `tnum` (tabular figures): Figtree is proportional, not mono like the retired
                // Martian Mono stamp - this is the OpenType feature that keeps a minute-to-minute
                // clock tick from shifting width. A no-op if Figtree's own font tables happen not to
                // carry that feature, never a crash risk (Skia ignores an unsupported tag).
                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                color = SoftColors.text,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
            if (onToggleQuiet != null) {
                QuietPill(on = quietOn, onClick = onToggleQuiet)
            }
            if (onOpenApps != null) {
                IconButton(onClick = onOpenApps) {
                    MsIcon(res = R.drawable.ms_apps, contentDescription = "Apps", tint = SoftColors.text2)
                }
            }
            if (onOpenSettings != null) {
                IconButton(onClick = onOpenSettings) {
                    MsIcon(res = R.drawable.ms_settings, contentDescription = "Settings", tint = SoftColors.text2)
                }
            }
        }
    }
}

/**
 * The quiet-mode toggle (added 2026-09-27, see [StatusLine.onToggleQuiet]'s own doc for how this
 * parameter pair was commissioned). A compact pill, 48dp touch target, worded rather than
 * colour-only per CLAUDE.md §4/§7: "Quiet" (outlined, [SoftColors.outline]/[SoftColors.text2]) when
 * [on] is false, "Quiet on" (filled [SoftColors.primaryContainer]/[SoftColors.onPrimaryContainer])
 * when true - the same outlined-vs-filled two-tone convention
 * [com.kevin.legion.ui.assistant.AssistantStrip]'s off-vs-enabled pill already uses, for visual
 * consistency across this ticket's two restyled chrome elements. `contentDescription` on the
 * clickable surface repeats the same words the label already shows, so TalkBack announces "Quiet"
 * / "Quiet on" - never a bare icon-only state.
 */
@Composable
private fun QuietPill(on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = if (on) "Quiet on" else "Quiet"
    val shape = RoundedCornerShape(percent = 50)
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .let {
                if (on) it.background(SoftColors.primaryContainer) else it.border(1.dp, SoftColors.outline, shape)
            }
            .clickable(onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (on) SoftColors.onPrimaryContainer else SoftColors.text2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The alarm pill (cyberdeck-ui ticket 04, restyled soft per home-launcher ticket 02):
 * [SoftColors.alertContainer] fill, [SoftColors.onAlert] text, fully rounded, reading "1 alarm" /
 * "N alarms". Tappable via [onClick], same as the mission-control version's inverted segment - only
 * the paint job and the label's wording changed (sentence case, not "ALARM $count" caps, per ADR
 * 0050: "Sentence-case words, not uppercase stamps").
 */
@Composable
private fun AlarmPill(count: Int, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(SoftColors.alertContainer)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            if (count == 1) "1 alarm" else "$count alarms",
            style = MaterialTheme.typography.labelMedium,
            color = SoftColors.onAlert,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
