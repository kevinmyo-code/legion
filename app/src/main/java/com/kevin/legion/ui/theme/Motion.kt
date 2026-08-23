package com.kevin.legion.ui.theme

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * The app's motion vocabulary (command-center ticket 14, "the app learns to
 * move" - Kevin 2026-08-22, following CLAUDE.md sec 7's "Motion is NOT
 * restricted anymore. Use normal Compose animation" - the old frame-clock-only
 * rule was a head-unit constraint and is LIFTED for the phone pivot).
 *
 * **This is the one place a duration or easing gets invented.** A screen or a
 * shared component reads [LegionMotion]'s constants; nothing picks its own
 * fade length inline. Two screens quietly drifting to two different fade
 * durations is exactly the failure this file exists to prevent - before this
 * ticket nothing in the deck had used the permission CLAUDE.md granted at all,
 * so there was no drift yet to fix, only a vocabulary to write down before the
 * next screen invents its own.
 *
 * **Relationship to [DeckMotion]**: that file already owns the deck's
 * one-shot draw-in duration ([DRAW_IN_MS]) and the single reduced-motion read
 * every motion consumer in the app must gate on ([deckMotionEnabled]) - both
 * predate this ticket and are NOT duplicated here. This file adds the four
 * things ticket 14 introduces that [DeckMotion] never had reason to hold:
 * route transitions, a shared press response, one-shot pane entrance, and the
 * pure gate that keeps entrance animation off an ALARM surface. Every
 * composable in this file (and every consumer of it) still calls
 * [deckMotionEnabled] for the reduced-motion read rather than inventing a
 * second one.
 *
 * **Every duration here is short on purpose.** CLAUDE.md's "no compulsion
 * mechanics" clause and ticket 03's "one hue, spent rarely" audit both apply
 * to motion the same way they apply to colour and copy: a vocabulary that is
 * fast and quiet is furniture; one that lingers or performs is theatre, and
 * theatre is rationed to the two moments (ingest commit, quarantine) ticket
 * 04's answer already spent it on. Nothing in this file is a third spend.
 */
object LegionMotion {
    /** Route fade-through on the NavHost - enter, exit, and pop, all the same length. */
    const val ROUTE_FADE_MS: Int = 200

    /** Uniform press response for a Deck button or a tappable row. Deliberately shorter than a route fade - a press has to feel instant, not merely present. */
    const val PRESS_MS: Int = 100

    /** How far a press scales down. Subtle: this is feedback that a tap landed, not a bounce animation. */
    const val PRESS_SCALE: Float = 0.97f

    /** One-shot fade-in for a [com.kevin.legion.ui.common.DeckPane]'s content on its first composition. Under the ticket's 250ms ceiling. */
    const val PANE_ENTRANCE_MS: Int = 220

    /** Shared `animateContentSize`/`Crossfade` duration for a pane whose content grows, shrinks, or swaps a hero value in place. */
    const val CONTENT_CHANGE_MS: Int = 220

    /**
     * The idle breathing pulse, shared with [com.kevin.legion.ui.assistant.AssistantStrip]'s
     * `PhaseDot` so a listening/speaking pulse and any future ambient pulse read as the same
     * heartbeat rather than two different tempos. That composable's own doc explains why the
     * pulse is gated on `active` (LISTENING/SPEAKING) and never on IDLE - CLAUDE.md's "the one
     * earned pulse" - this constant only carries the shared timing, not the gate.
     */
    const val PULSE_MS: Int = 700

    /** The single easing curve every eased tween in this vocabulary uses. One curve, not a matched pair per screen. */
    val STANDARD_EASING: Easing = FastOutSlowInEasing
}

/**
 * Whether a [com.kevin.legion.ui.common.DeckPane] (or any other shared component with a one-shot
 * entrance) should run its entrance animation. Pure and parameter-driven so it is directly
 * testable without standing up Compose - the two inputs are exactly the two hard rules ticket 14
 * carries: **nothing animates that carries an ALARM state** (CLAUDE.md's quarantine/safety-row
 * rule, applied to entrance the same way [DeckPane] already applies it to fill and border colour),
 * and reduced motion ([deckMotionEnabled] upstream) collapses every one-shot to instant. Either
 * one being true is enough to skip the animation - they are not combined with anything else.
 */
fun deckEntranceEnabled(alarm: Boolean, motionEnabled: Boolean): Boolean = !alarm && motionEnabled

/**
 * The shared press response every tappable Deck primitive uses - a gentle scale toward
 * [LegionMotion.PRESS_SCALE] while [interactionSource] reports a press, back to full size the
 * instant it releases. Built as a `graphicsLayer` scale rather than a footprint-changing
 * transform, so it never nudges layout or a sibling's position, and reads [deckMotionEnabled]
 * itself so a caller never has to remember to gate it - reduced motion collapses this to a no-op
 * (`scale` pinned at `1f`) the same way [DeckPane]'s entrance and [DeckMotion]'s draw-ins do.
 *
 * Composable rather than [Modifier.composed] on purpose: every call site here (`DeckButton`, the
 * shell's tappable text) constructs this modifier inline inside its own `@Composable` body, so it
 * already gets its own recomposition scope from the caller - there is no second element reusing
 * the same `Modifier` instance across positions that `composed {}`'s extra indirection would be
 * guarding against.
 */
@Composable
fun Modifier.legionPressScale(interactionSource: InteractionSource): Modifier {
    val motionEnabled = deckMotionEnabled()
    val pressed by interactionSource.collectIsPressedAsState()
    val targetScale = if (pressed && motionEnabled) LegionMotion.PRESS_SCALE else 1f
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = tween(LegionMotion.PRESS_MS, easing = LegionMotion.STANDARD_EASING),
        label = "legion-press-scale",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
