package com.kevin.legion.ui.assistant

import com.kevin.legion.service.Phase

/**
 * What the strip should show, resolved from the three signals it can see:
 * [Phase] (from [com.kevin.legion.service.CompanionPhase], mirroring
 * [com.kevin.legion.service.LiveSessionController]), a live caption, a
 * transient notice, and the live RECORD_AUDIO grant. Pure and Android-free -
 * `Phase` is a plain enum with no Android dependency, so this is a JVM unit
 * test, no Robolectric.
 *
 * A missing mic grant wins over everything else: a phase/caption/notice from
 * a PAST granted session can still be sitting in [CompanionPhase] (its flows
 * are process-global and outlive any one permission state), and showing
 * "Listening..." over a mic the OS will actually refuse is the silent-tap
 * failure the ticket calls out by name.
 */
object AssistantStripResolver {

    /** The strip's tap target and the two lines of text it draws. */
    data class State(
        val label: String,
        val subtitle: String?,
        /** True when a tap should route to Settings instead of starting a turn. */
        val micBlocked: Boolean,
        /** True for LISTENING/SPEAKING - the phases worth an animated cue. */
        val active: Boolean,
    )

    fun resolve(phase: Phase, caption: String, notice: String?, micGranted: Boolean): State {
        if (!micGranted) {
            return State(
                label = "Microphone permission needed",
                subtitle = "Tap to open Settings",
                micBlocked = true,
                active = false,
            )
        }
        // A notice ("ON A CALL", "NO SIGNAL OUT HERE", a dropped connection)
        // is a driver-facing failure LiveSessionController.onTap/handleEvent
        // just surfaced - it takes the label slot until it clears, same as
        // the Cruise/Lights Out flash CompanionPhase's own doc describes.
        if (notice != null) {
            return State(label = notice, subtitle = null, micBlocked = false, active = false)
        }
        return State(
            label = phaseLabel(phase),
            subtitle = caption.ifBlank { null },
            micBlocked = false,
            active = phase == Phase.LISTENING || phase == Phase.SPEAKING,
        )
    }

    /** Legible-at-a-glance text for each [Phase]. Never rely on colour alone (CLAUDE.md §7). */
    fun phaseLabel(phase: Phase): String = when (phase) {
        Phase.IDLE -> "Tap to talk"
        Phase.CONNECTING -> "Connecting…"
        Phase.LISTENING -> "Listening…"
        Phase.THINKING -> "Thinking…"
        Phase.SPEAKING -> "Speaking…"
    }
}
